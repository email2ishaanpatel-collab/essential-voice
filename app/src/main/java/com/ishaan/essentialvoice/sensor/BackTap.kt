package com.ishaan.essentialvoice.sensor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.KeyguardManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.ishaan.essentialvoice.Prefs
import com.ishaan.essentialvoice.Settings
import com.ishaan.essentialvoice.game.GameMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Two knocks on the back of the phone start a dictation.
 *
 * The same idea as Back Tap on an iPhone and Quick Tap on a Pixel, and none of
 * those use special hardware: a knock on the case is a short, sharp transient in
 * the accelerometer. Recognising that is easy. **Everything hard about this
 * feature is not recognising a phone being put down on a table**, which produces
 * a far larger transient, and then a second one as the case rings — a textbook
 * double tap by any naive measure.
 *
 * **This phone has no tap sensor**, which decides the whole design. Read off the
 * CMF Phone 2 Pro (`dumpsys sensorservice`):
 *
 *     icm4n607_acc | accelerometer | minRate=5Hz maxRate=400Hz
 *                  | FIFO (max,reserved) = (4500, 3000) | non-wakeUp
 *
 * Twenty-five hardware sensors and not one detects a tap, so there is no
 * low-power sensor-hub route and the detection has to happen here, on samples.
 * Pixels do this in a TFLite model; that model is Google's, extracted from Pixel
 * firmware, and Tap Tap — the app that ports it — is GPL-3. Neither can go into
 * an Apache-2.0 repository, so what follows is a plain heuristic.
 *
 * **It cannot work with the screen off, and that is a property of the phone.**
 * Both accelerometer entries are `non-wakeUp`, so the sensor never wakes the
 * application processor. The 4500-event FIFO does keep batching while the phone
 * sleeps, but those samples are only handed over when something *else* wakes it
 * — a trigger that fires several minutes late is worse than one that does not
 * fire — and the only way round it is a permanent partial wakelock, which is a
 * feature that eats a battery to answer a knock nobody made. So the listener is
 * registered on screen-on and dropped on screen-off.
 *
 * **The battery cost is smaller than it looks.** The accelerometer on this phone
 * is *already* running continuously with three other clients on it — 200ms,
 * 200ms and 20ms, so 50Hz — all day, whatever this app does. Registering here
 * does not wake a sleeping sensor; it raises the rate of one that is already on,
 * and only while the screen is on.
 *
 * 200Hz exactly, deliberately: above 200 the framework requires
 * HIGH_SAMPLING_RATE_SENSORS, and a knock's transient is a handful of
 * milliseconds, so 200Hz puts two or three samples inside it.
 */
object BackTap {

    private const val TAG = "EVBackTap"

    /** 200Hz. See the note on the class about why not more. */
    private const val RATE_US = 5_000

    /**
     * The window a second knock has to land in.
     *
     * The floor is not padding: a single hard knock rings the case, and the ring
     * crosses the threshold again a few milliseconds later. Anything under
     * [MIN_GAP_MS] is that ring, not a finger.
     */
    private const val MIN_GAP_MS = 70L
    private const val MAX_GAP_MS = 500L

    /** After a peak, how long before another crossing counts as a new knock. */
    private const val REFRACTORY_MS = 55L

    /** After firing, or after a rejection worth backing off from. */
    private const val COOLDOWN_MS = 900L

    /**
     * How much the two knocks are allowed to differ in strength.
     *
     * A real double tap is two similar knocks from the same finger.
     */
    private const val PEAK_RATIO = 3.5f

    /**
     * The hardest a *finger* is allowed to be.
     *
     * This is the single most valuable gate here and the one the first version
     * was missing. A fingertip on the back of a phone is a few m/s². A phone
     * meeting a table is tens to hundreds — often enough to clip the sensor's
     * range outright. Nothing about the shape of the two events distinguishes
     * them; the size does, completely.
     */
    private const val PEAK_CEILING = 35f

    /**
     * How much the phone is allowed to be *moving*, in m/s².
     *
     * The trick is that a stationary phone reads exactly 1g in every
     * orientation, so the smoothed magnitude minus 9.81 is a direct measure of
     * real linear acceleration — of being carried, lifted or lowered — and it
     * does not care which way up the phone is.
     *
     * This is what the first version got wrong. Its stillness check watched the
     * *high-passed* signal, which is high-frequency vibration; lowering a phone
     * onto a table is smooth and slow, so it sailed through a gate that was
     * looking for the wrong thing entirely. Watching the low-frequency side
     * instead is what actually sees a hand putting a phone down.
     */
    private const val MOTION_MAX = 1.2f

    /**
     * How long a knock has to *not* be followed by another one.
     *
     * An impact rings: contact, bounce, settle. Two of those look exactly like a
     * double tap, and the third is what gives it away — so the decision waits
     * this long and is thrown away if anything else arrives. It costs the
     * gesture a sixth of a second, which for a toggle is nothing, and it removes
     * the entire class of false positive that a bouncing phone produces.
     *
     * With a triple tap assigned the window has to be longer, because the same
     * pause is now where a *third* knock is listened for and a human third knock
     * is not always quick. That is the price of the triple: every double gets
     * slower, which is exactly why triple tap is off unless it is asked for.
     */
    private const val CONFIRM_MS = 170L
    private const val CONFIRM_TRIPLE_MS = 320L

    /** How fast the baseline follows the signal, per sample at 200Hz. */
    private const val BASELINE_ALPHA = 0.15f

    /** How fast the resting-noise estimate moves. Slow: it is a mood, not a value. */
    private const val REST_ALPHA = 0.02f

    /**
     * Per-sample decay of the motion peak. 0.995 at 200Hz halves it in ~700ms,
     * which is about how long ago "was the phone moving" is still relevant.
     */
    private const val MOTION_DECAY = 0.995f

    private var app: Context? = null
    private var sensors: SensorManager? = null
    private var accel: Sensor? = null
    private var proximity: Sensor? = null

    /**
     * Sampling runs on its own thread.
     *
     * 200 callbacks a second is not heavy work, but the default is to deliver
     * them on the main looper, and the main looper of this process belongs to
     * the accessibility service — the thread that has to answer a key press
     * without a pause in it.
     */
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private val main = Handler(Looper.getMainLooper())

    private var listening = false
    private var wanted = false

    /** Why the last pair of knocks did or did not become a dictation. */
    enum class Verdict {
        /** A complete gesture, and its action ran. [Reading.taps] says how many. */
        HEARD,

        /** One knock, still waiting for its pair. */
        LONE,

        /** Far too hard to be a finger — a table, a pocket, a drop. */
        TOO_HARD,

        /** The phone was being moved, so this was not somebody tapping it. */
        MOVING,

        /** Something is over the top of the screen. */
        COVERED,

        /** A third knock followed, so the first two were a bounce. */
        RINGING,

        /** Two knocks, but of wildly different strengths. */
        MISMATCHED,

        /**
         * The phone is locked.
         *
         * Both Back Tap and Quick Tap are unlocked-only, and they are right to
         * be: a knock in a bag should not be able to start a recording or open
         * an app on a phone somebody has deliberately locked.
         */
        LOCKED,
    }

    /**
     * The last thing the detector decided, and the numbers behind it.
     *
     * This exists so the sensitivity setting can be set by knocking rather than
     * by guessing. How hard a knock reads depends on the case as much as on the
     * phone — and this phone has a screwed-on back plate rather than a sealed
     * one — so a number chosen here in advance is worth much less than the
     * reading somebody gets from their own phone.
     */
    data class Reading(
        val at: Long,
        val verdict: Verdict,
        val first: Float,
        val second: Float,
        val gapMs: Long,
        val motion: Float,
        /** How many knocks the gesture ended up being. */
        val taps: Int = 0,
    )

    private val _last = MutableStateFlow<Reading?>(null)
    val last: StateFlow<Reading?> = _last

    /** Whether the sensor this needs exists at all. Answered once, on attach. */
    val isSupported: Boolean get() = accel != null

    // ---- detector state (worker thread only) -------------------------------

    private var baseline = SensorManager.GRAVITY_EARTH
    private var rest = 0f
    private var motionPeak = 0f
    private var threshold = 4.5f
    private var lastPeakAt = 0L
    private var lastPeak = 0f
    private var deafUntil = 0L

    /** What each gesture does. Read out of the settings by [apply]. */
    private var doubleAction = TapAction.DICTATE
    private var doubleApp = ""
    private var tripleAction = TapAction.NOTHING
    private var tripleApp = ""
    private val tripleWanted: Boolean get() = tripleAction != TapAction.NOTHING

    /**
     * The run of knocks being assembled.
     *
     * A run rather than a pair, because a triple tap is the same thing one knock
     * longer. A knock either continues the run — right gap, similar strength —
     * or starts a new one.
     */
    private var runCount = 0
    private var runFirstPeak = 0f
    private var runLastPeak = 0f
    private var runLastAt = 0L
    private var runGap = 0L

    /** Set while a complete run waits to see whether anything follows it. */
    private var pending = false
    private var pendingTaps = 0

    /** True while something is over the proximity sensor — a pocket, or a hand. */
    @Volatile
    private var covered = false

    // ---- lifecycle ---------------------------------------------------------

    /** Called by the accessibility service once the system has bound it. */
    fun attach(context: Context) {
        val c = context.applicationContext
        app = c
        val sm = c.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sensors = sm
        accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        proximity = sm?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (accel == null) Log.w(TAG, "no accelerometer; back tap is unavailable")
    }

    fun detach() {
        wanted = false
        stop()
        runCatching { app?.unregisterReceiver(screenReceiver) }
        registeredScreen = false
        app = null
        sensors = null
        accel = null
        proximity = null
    }

    /**
     * Follow the settings.
     *
     * Idempotent and called on every settings change, the same way the island
     * is: working out which setting moved is more code than doing the cheap
     * thing twice.
     */
    fun apply(s: Settings) {
        threshold = thresholdFor(s.backTapSensitivity)
        // Before the guard below, so that changing what a knock *does* takes
        // effect without the detector having to be stopped and restarted.
        doubleAction = TapAction.byId(s.backTapAction)
        doubleApp = s.backTapApp
        tripleAction = TapAction.byId(s.backTapTripleAction)
        tripleApp = s.backTapTripleApp
        // Game mode silences the key; a phone being held and tapped through a
        // game is the worst possible place for a knock detector, so it silences
        // this by the same switch rather than by one of its own.
        val on = s.backTap && accel != null && !GameMode.mutesKey
        if (on == wanted) return
        wanted = on
        if (on) {
            watchScreen()
            if (screenOn()) start() else stop()
        } else {
            stop()
        }
    }

    /**
     * Sensitivity 1..5 as a threshold in m/s².
     *
     * A starting point rather than a measurement, which is why [last] exists.
     * The gates that reject a table are [PEAK_CEILING], [MOTION_MAX] and
     * [CONFIRM_MS], not this number — this one only decides how firm a knock has
     * to be before it counts as one at all.
     */
    private fun thresholdFor(sensitivity: Int): Float = when (sensitivity.coerceIn(1, 5)) {
        1 -> 8.0f
        2 -> 6.0f
        3 -> 4.5f
        4 -> 3.2f
        else -> 2.2f
    }

    // ---- the screen --------------------------------------------------------

    private var registeredScreen = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> if (wanted) start()
                Intent.ACTION_SCREEN_OFF -> stop()
            }
        }
    }

    private fun watchScreen() {
        if (registeredScreen) return
        val ctx = app ?: return
        // Dynamic only: SCREEN_ON and SCREEN_OFF are not delivered to manifest
        // receivers, by design.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching { ctx.registerReceiver(screenReceiver, filter) }
            .onSuccess { registeredScreen = true }
            .onFailure { Log.w(TAG, "could not watch the screen", it) }
    }

    private fun screenOn(): Boolean {
        val pm = app?.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isInteractive
    }

    // ---- sampling ----------------------------------------------------------

    private fun start() {
        if (listening) return
        val sm = sensors ?: return
        val sensor = accel ?: return

        val t = thread ?: HandlerThread("ev-backtap").also {
            it.start()
            thread = it
            worker = Handler(it.looper)
        }
        val h = worker ?: return
        reset()

        val ok = runCatching { sm.registerListener(sampler, sensor, RATE_US, h) }
            .getOrDefault(false)
        if (!ok) {
            Log.w(TAG, "the accelerometer refused the registration")
            return
        }
        proximity?.let {
            runCatching { sm.registerListener(near, it, SensorManager.SENSOR_DELAY_NORMAL, h) }
        }
        listening = true
        Log.i(TAG, "listening at ${1_000_000 / RATE_US}Hz, threshold ${threshold}m/s² (${t.name})")
    }

    private fun stop() {
        if (!listening) return
        val sm = sensors
        runCatching { sm?.unregisterListener(sampler) }
        runCatching { sm?.unregisterListener(near) }
        worker?.removeCallbacks(confirm)
        listening = false
        covered = false
        pending = false
        Log.i(TAG, "stopped")
    }

    private fun reset() {
        baseline = SensorManager.GRAVITY_EARTH
        rest = 0f
        motionPeak = 0f
        lastPeakAt = 0L
        lastPeak = 0f
        deafUntil = 0L
        resetRun()
    }

    private val near = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val max = proximity?.maximumRange ?: return
            covered = event.values.firstOrNull()?.let { it < max } ?: false
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val sampler = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            // Magnitude rather than the z axis alone. Which way "the back" points
            // depends on how the phone is being held, and a knock shows up in the
            // magnitude whichever axis it arrived on.
            val mag = sqrt(x * x + y * y + z * z)

            baseline += (mag - baseline) * BASELINE_ALPHA
            val hp = abs(mag - baseline)
            val now = SystemClock.uptimeMillis()

            // A stationary phone reads exactly 1g whichever way up it is, so this
            // is real linear acceleration and nothing else: a hand carrying,
            // lifting or lowering the phone. Decayed rather than instantaneous,
            // because what matters at the moment of an impact is whether the
            // phone was moving in the moments *before* it.
            motionPeak *= MOTION_DECAY
            if (hp < threshold * 0.5f) {
                rest += (hp - rest) * REST_ALPHA
                motionPeak = max(motionPeak, abs(baseline - SensorManager.GRAVITY_EARTH))
            }

            if (now < deafUntil) return
            if (hp < threshold) return

            if (now - lastPeakAt < REFRACTORY_MS) {
                // Still inside one knock's ringing. Keep the loudest sample of it
                // rather than the first, so the strength comparison later is
                // between two peaks and not between two arbitrary crossings.
                if (hp > lastPeak) lastPeak = hp
                return
            }
            lastPeakAt = now
            lastPeak = hp

            // Too big to be a finger. A table, a drop, a bag closing.
            if (hp > PEAK_CEILING) {
                resetRun()
                deafUntil = now + COOLDOWN_MS
                report(Verdict.TOO_HARD, hp, 0f, 0L, motionPeak)
                return
            }

            // The phone is being moved, so nobody is tapping it on purpose.
            if (motionPeak > MOTION_MAX) {
                resetRun()
                deafUntil = now + COOLDOWN_MS
                report(Verdict.MOVING, hp, 0f, 0L, motionPeak)
                return
            }

            // High-frequency noise: a bumpy ride, a phone being shaken.
            if (rest > threshold * 0.45f) {
                resetRun()
                report(Verdict.MOVING, hp, 0f, 0L, motionPeak)
                return
            }

            if (covered) {
                resetRun()
                report(Verdict.COVERED, hp, 0f, 0L, motionPeak)
                return
            }

            // Both Back Tap and Quick Tap are unlocked-only. Cheap to ask here,
            // because a threshold crossing is rare; asking it every sample would
            // be a binder call two hundred times a second.
            if (locked()) {
                resetRun()
                deafUntil = now + COOLDOWN_MS
                report(Verdict.LOCKED, hp, 0f, 0L, motionPeak)
                return
            }

            // Does this knock belong to the run being assembled, or start a new
            // one? The same question for the second knock and the third.
            val gap = now - runLastAt
            val continues = runCount > 0 &&
                gap in MIN_GAP_MS..MAX_GAP_MS &&
                similar(runLastPeak, hp)

            if (pending) {
                worker?.removeCallbacks(confirm)
                pending = false

                // A third knock that fits is a triple tap — but only if one has
                // been assigned. If not, it is the bounce of something dropped,
                // which is exactly what the waiting was for.
                if (tripleWanted && pendingTaps == 2 && continues) {
                    runCount = 3
                    runGap = gap
                    runLastAt = now
                    runLastPeak = hp
                    pendingTaps = 3
                    pending = true
                    worker?.postDelayed(confirm, confirmMs())
                    return
                }

                resetRun()
                deafUntil = now + COOLDOWN_MS
                report(Verdict.RINGING, hp, 0f, 0L, motionPeak)
                return
            }

            if (continues) {
                runCount++
                runGap = gap
                runLastAt = now
                runLastPeak = hp
                pendingTaps = runCount
                pending = true
                worker?.postDelayed(confirm, confirmMs())
                return
            }

            // A knock on its own — either the first of a gesture, or a second
            // that did not qualify, in which case it becomes the new first so a
            // mistimed tap followed by two good ones still works.
            //
            // A near miss is worth saying out loud rather than silently
            // restarting: "one knock, 4.1" and "those two were too uneven" are
            // the two readings somebody setting the sensitivity needs to see.
            if (runCount > 0 && gap in MIN_GAP_MS..MAX_GAP_MS) {
                report(Verdict.MISMATCHED, runLastPeak, hp, gap, motionPeak)
            } else {
                report(Verdict.LONE, hp, 0f, 0L, motionPeak)
            }
            runCount = 1
            runFirstPeak = hp
            runLastPeak = hp
            runLastAt = now
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Nothing followed the run, so it really was the gesture it looked like. */
    private val confirm = Runnable {
        if (!pending) return@Runnable
        pending = false
        val taps = pendingTaps
        val action = if (taps >= 3) tripleAction else doubleAction
        val app = if (taps >= 3) tripleApp else doubleApp
        deafUntil = SystemClock.uptimeMillis() + COOLDOWN_MS
        report(Verdict.HEARD, runFirstPeak, runLastPeak, runGap, motionPeak, taps)
        resetRun()
        Log.i(TAG, "$taps taps -> ${action.id}")
        main.post { TapActions.run(action, app) }
    }

    /**
     * Longer when a triple tap is assigned, because then this pause is where the
     * third knock is listened for rather than only where a bounce would show up.
     */
    private fun confirmMs(): Long = if (tripleWanted) CONFIRM_TRIPLE_MS else CONFIRM_MS

    private fun resetRun() {
        runCount = 0
        runFirstPeak = 0f
        runLastPeak = 0f
        runLastAt = 0L
        runGap = 0L
        pending = false
        pendingTaps = 0
        worker?.removeCallbacks(confirm)
    }

    private fun locked(): Boolean {
        val km = app?.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            ?: return false
        return runCatching { km.isKeyguardLocked }.getOrDefault(false)
    }

    private fun similar(a: Float, b: Float): Boolean {
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        return lo > 0f && hi / lo <= PEAK_RATIO
    }

    private fun report(
        v: Verdict,
        first: Float,
        second: Float,
        gap: Long,
        motion: Float,
        taps: Int = 0,
    ) {
        _last.value = Reading(SystemClock.uptimeMillis(), v, first, second, gap, motion, taps)
    }
}
