package com.ishaan.essentialvoice.volume

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Handler
import android.os.PowerManager
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import com.ishaan.essentialvoice.Prefs
import com.ishaan.essentialvoice.R
import android.view.View
import com.ishaan.essentialvoice.Settings

/**
 * The volume slider: what pressing the volume keys looks like when this app is
 * the one drawing it.
 *
 * **Why this can exist at all.** The volume keys are ordinary keys as far as the
 * window manager is concerned — unlike the power button and unlike the Essential
 * Key, neither of which any app can intercept, because both are consumed by the
 * window manager policy *above* accessibility key filtering. Volume is not: it
 * is passed through to be dispatched, which means a bound accessibility service
 * holding FLAG_REQUEST_FILTER_KEY_EVENTS sees the press first and can swallow
 * it. Swallowing it is the whole feature: the system panel is drawn by SystemUI
 * in response to the volume change *and its FLAG_SHOW_UI*, so a press this app
 * consumes and re-issues with no flags moves the volume and draws nothing.
 *
 * That is also the one thing that has to be got right. If the key is consumed
 * and the volume is not adjusted here, the phone's volume buttons are simply
 * dead — so every path out of [onVolumeKey] that returns true has adjusted it,
 * and the setting being off returns false before anything else happens.
 *
 * **Hosted by the accessibility service**, like [com.ishaan.essentialvoice.island.Island]
 * and [com.ishaan.essentialvoice.voice.Bar], and a TYPE_ACCESSIBILITY_OVERLAY
 * for the same reason: that layer sits above the status bar and the navigation
 * bar, and an ordinary application overlay does not.
 *
 * **It never takes a touch.** FLAG_NOT_TOUCHABLE is not an oversight — the
 * capsule stands on the very edge of the screen, which is where the back gesture
 * lives, and a window that swallowed touches there would break swiping back for
 * the second and a half after every volume press. The keys are the control; this
 * is the readout.
 */
object VolumeSlider {

    private const val TAG = "EVVol"

    /** How long the capsule stays after the last press, if the setting is unreadable. */
    private const val LINGER_FALLBACK_MS = 1500L

    /**
     * How long it takes to arrive and to leave, and on what curve.
     *
     * Lifted from the laptop's OSD — `extension.js`, IN_MS 460 on EASE_OUT_EXPO
     * and OUT_MS 320 on EASE_IN_CUBIC — because this is meant to be the same
     * object appearing on the phone, and an island that arrives on a different
     * curve is a different island.
     */
    private const val IN_MS = 460L
    private const val OUT_MS = 320L

    private val ENTER: Interpolator = PathInterpolator(0.19f, 1f, 0.22f, 1f)
    private val EXIT: Interpolator = PathInterpolator(0.32f, 0f, 0.67f, 0f)

    /**
     * Holding the key, done by hand.
     *
     * Android's own auto-repeat never arrives here: measured on the phone, an
     * accessibility service is handed the down and the up of a held volume key
     * and **nothing in between** — every event delivered to onKeyEvent came
     * through with repeatCount 0. So a hold moved the volume exactly one step,
     * which is the one thing a volume button must not do. The repeat is this
     * object's own timer now, started on the press and stopped on the release.
     */
    private const val REPEAT_DELAY_MS = 150L

    /**
     * The ramp. A held key starts stepping at [REPEAT_START_MS] and accelerates
     * to [REPEAT_MIN_MS] over [REPEAT_RAMP_MS] of holding, so a nudge is
     * controllable and crossing the whole scale is quick — which is the same
     * bargain the system's own repeat makes, and the reason a fixed interval
     * feels wrong at one end or the other whatever it is set to.
     */
    private const val REPEAT_START_MS = 42L
    private const val REPEAT_MIN_MS = 6L
    private const val REPEAT_RAMP_MS = 1100f

    /** A held key that never reports its release must not ramp forever. */
    private const val REPEAT_MAX_MS = 20_000L

    private val main = Handler(Looper.getMainLooper())

    private var app: Context? = null

    /**
     * The accessibility service. Not interchangeable with [app]: a
     * TYPE_ACCESSIBILITY_OVERLAY may only be added through the WindowManager of
     * a bound accessibility service, and the application context's one throws.
     */
    private var svc: Context? = null
    private var wm: WindowManager? = null
    private var prefs: Prefs? = null
    private var audio: AudioManager? = null
    private var power: PowerManager? = null

    /**
     * The streams the panel offers, in the order the phone's own panel puts
     * them. Rebuilt at every showing rather than once, because whether there is
     * a call in progress changes the list.
     */
    private var cols: List<VolumeSliderView.Col> = emptyList()

    /** Which of [cols] the collapsed slider is about. */
    private var activeIndex = 0

    private var expanded = false

    /** A drag is live, started on the scale rather than on the chevron. */
    private var dragging = false

    /**
     * A full-screen window that exists only while the panel is open.
     *
     * It is what "tapping anywhere else closes it" is made of, and it is added
     * *after* the slider so it is reliably on top — which means it takes every
     * touch, including the ones meant for the panel. So it routes: a touch
     * inside the slider's window rect is translated into that window's
     * coordinates and handled as if it had landed there, and anything else
     * closes the panel. Routing rather than fighting over z-order is the only
     * version of this with one answer.
     */
    private var scrim: View? = null

    private var view: VolumeSliderView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var attached = false
    private var slide: ValueAnimator? = null

    /** Which stream this showing is about, so the readout cannot drift onto another. */
    private var stream = AudioManager.STREAM_MUSIC

    private val dismiss = Runnable { hide() }

    /**
     * Whether the slider is currently showing an end of the scale.
     *
     * Kept so the bounce fires once on *arriving* at an end rather than on every
     * repeat that lands there: a key held at full volume goes on being delivered
     * every few milliseconds, and re-firing a 620ms settle into itself at that
     * rate is not a bounce, it is a vibration.
     */
    private var wasAtMax = false
    private var wasAtMin = false

    /** The direction being held, or 0. */
    private var held = 0
    private var heldSince = 0L
    private val repeat = object : Runnable {
        override fun run() {
            val dir = held
            if (dir == 0) return
            if (android.os.SystemClock.uptimeMillis() - heldSince > REPEAT_MAX_MS) {
                held = 0
                return
            }
            if (!step(dir, fresh = false)) {
                held = 0
                return
            }
            val heldFor = android.os.SystemClock.uptimeMillis() - heldSince
            // Squared, so the ramp itself gets steeper the longer the key is
            // down: a linear one spends most of a long hold at nearly the speed
            // it started at, which is exactly the hold that wanted to be fast.
            val t = ((heldFor - REPEAT_DELAY_MS) / REPEAT_RAMP_MS).coerceIn(0f, 1f)
            val gap = REPEAT_START_MS + (REPEAT_MIN_MS - REPEAT_START_MS) * t * t
            main.postDelayed(this, gap.toLong())
        }
    }

    private fun releaseKey() {
        held = 0
        main.removeCallbacks(repeat)
    }

    /** Called by the accessibility service once the system has bound it. */
    fun attach(context: Context) {
        val c = context.applicationContext
        app = c
        svc = context
        wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        audio = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        power = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        prefs = Prefs.get(c)
    }

    fun detach() {
        releaseKey()
        main.removeCallbacks(dismiss)
        slide?.cancel()
        remove()
        app = null
        svc = null
        wm = null
        prefs = null
        audio = null
        power = null
    }

    /**
     * The setting changed. Nothing to start — the capsule only exists while a
     * volume key is being pressed — but a slider switched off, or moved, while
     * it happens to be on screen should not sit there in the old place.
     */
    fun apply(s: Settings) {
        if (!attached) return
        if (!s.volumeSlider) {
            main.removeCallbacks(dismiss)
            // Straight out, panel and all: hide() refuses to act while the panel
            // is open, which is right for a timeout and wrong for the switch
            // that just turned the whole thing off.
            expanded = false
            view?.expanded = false
            removeScrim()
            hide()
        } else {
            reposition()
        }
    }

    // ---- the key ------------------------------------------------------------

    /**
     * A volume key arrived. Returns whether it was consumed.
     *
     * Both the press and its release have to be consumed together: letting the
     * release through on its own gives the app underneath half a key, which is
     * how a media app ends up thinking a button is stuck down.
     */
    fun onVolumeKey(event: KeyEvent): Boolean {
        val s = prefs?.now ?: return false
        if (!s.volumeSlider) return false
        if (audio == null || svc == null) return false

        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
            KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
            else -> return false
        }

        if (event.action != KeyEvent.ACTION_DOWN) {
            // The release does nothing but stop the ramp and get eaten. Letting
            // it through on its own would hand the app underneath half a key,
            // which is how a media app ends up thinking a button is stuck down.
            releaseKey()
            return true
        }

        // A repeat the system did deliver is ignored rather than acted on: the
        // timer below is already stepping, and doing both would ramp at two
        // different speeds added together.
        if (event.repeatCount > 0) return true

        if (!step(direction, fresh = true)) return false

        // Held from here until the release. The first repeat waits, so a normal
        // press is one step and only a deliberate hold ramps.
        releaseKey()
        held = direction
        heldSince = android.os.SystemClock.uptimeMillis()
        main.postDelayed(repeat, REPEAT_DELAY_MS)
        return true
    }

    /**
     * One notch, and the slider that says so. Returns false if the volume did
     * not move — the caller must then *not* consume the key, because a swallowed
     * key that changed nothing is a dead volume button.
     */
    private fun step(direction: Int, fresh: Boolean): Boolean {
        val am = audio ?: return false

        // Which stream the press is about, decided once per showing. Chosen the
        // way the system chooses: a call wins, then anything actually playing,
        // and the ringer is what is left.
        if (!attached) stream = streamFor(am)

        // Read *before* adjusting. This is what decides whether the press about
        // to happen is an arrival at the end of the scale or another press
        // against an end it was already sitting on, and only one of those is a
        // bounce. Taken after the adjustment it can never tell the two apart.
        val before = nowOf(am, stream)
        val max = maxOf(am, stream)

        // Adjusted with no flags. FLAG_SHOW_UI is what asks SystemUI to draw the
        // panel this is replacing, so its absence is the feature.
        // The last step down on the ringer is a mode change, not a volume, and
        // adjustStreamVolume will not make it.
        val moved = if (
            direction == AudioManager.ADJUST_LOWER && ringerStream(stream) && before <= 1
        ) {
            setRinger(am, silent = true)
            true
        } else {
            if (ringerStream(stream) && silenced(am, stream) &&
                direction == AudioManager.ADJUST_RAISE
            ) {
                setRinger(am, silent = false)
            }
            runCatching { am.adjustStreamVolume(stream, direction, 0) }.isSuccess
        }
        if (!moved) {
            Log.w(TAG, "could not adjust the volume; letting the key through")
            return false
        }

        // With the screen off there is nothing to draw on and nobody to draw
        // for, but the key still has to move the volume — which it just did, so
        // it is still consumed. Building a window here would be a window nobody
        // sees, added and removed on every press of a phone in a pocket.
        if (power?.isInteractive != false) {
            show()
            wasAtMax = max > 0 && before >= max
            wasAtMin = before <= 0
            readOut(am, animate = true)
            // A *fresh* press against an end that was already an end still gets
            // the stretch — pushing at a wall should do something — but an
            // auto-repeat against it does not, or a held key would shake.
            if (fresh) {
                if (direction == AudioManager.ADJUST_RAISE && wasAtMax) {
                    view?.requestImpact(fromTop = true)
                }
                if (direction == AudioManager.ADJUST_LOWER && wasAtMin) {
                    view?.requestImpact(fromTop = false)
                }
            }
            main.removeCallbacks(dismiss)
            main.postDelayed(dismiss, lingerMs())
        }
        return true
    }

    /**
     * Set the volume from a finger at [y] on the slider.
     *
     * Absolute rather than relative: the dots are the scale, so the notch under
     * the finger is the volume being asked for, and a drag that accumulated
     * deltas instead would drift away from the dot it started on.
     *
     * Written straight with `setStreamVolume` and no flags, for the same reason
     * `step` uses none — FLAG_SHOW_UI here would put the system's panel on
     * screen next to this one.
     */
    private fun dragTo(x: Float, y: Float) {
        val am = audio ?: return
        val v = view ?: return
        val i = v.columnAt(x)
        val c = cols.getOrNull(i) ?: return
        if (c.max <= 0) return
        val notch = v.notchAt(y, i)
        if (notch < 0) return

        // A silenced ringer is a mode, not a level. Dragging it is how you take
        // it out of that mode, so the first touch puts the phone back to normal
        // and the drag then means what it says.
        if (c.vibrate && notch > 0) {
            setRinger(am, silent = false)
            c.vibrate = silenced(am, c.stream)
        }

        // Drawn first, from what the finger asked for. The volume write is a
        // binder round trip and the pill must not wait on it.
        c.level = notch.coerceIn(0, c.max)
        v.refresh(i, animate = false)
        if (i == activeIndex) markEnds(c.level, c.max)
        if (c.level == 0 && ringerStream(c.stream)) {
            // Same as the key: the bottom of these two scales is silence, and
            // silence is a mode.
            setRinger(am, silent = true)
            c.vibrate = silenced(am, c.stream)
        } else {
            runCatching { am.setStreamVolume(c.stream, c.level, 0) }
                .onFailure { Log.w(TAG, "could not set the volume", it) }
        }
    }

    private fun lingerMs(): Long =
        prefs?.now?.volumeLingerMs?.toLong()?.coerceAtLeast(200L) ?: LINGER_FALLBACK_MS

    /** Everything the panel shows, in the phone's own order. */
    private fun buildCols(am: AudioManager): List<VolumeSliderView.Col> {
        val list = mutableListOf<VolumeSliderView.Col>()
        val inCall = am.mode == AudioManager.MODE_IN_CALL ||
            am.mode == AudioManager.MODE_IN_COMMUNICATION
        // The call only exists while there is one, which is also the only time
        // the system offers it.
        if (inCall) list += col(am, AudioManager.STREAM_VOICE_CALL, R.drawable.ic_vol_call)
        list += col(am, AudioManager.STREAM_MUSIC, R.drawable.ic_vol_media)
        list += col(am, AudioManager.STREAM_RING, R.drawable.ic_vol_ring)
        list += col(am, AudioManager.STREAM_NOTIFICATION, R.drawable.ic_vol_notification)
        list += col(am, AudioManager.STREAM_ALARM, R.drawable.ic_vol_alarm)
        return list
    }

    private fun col(am: AudioManager, stream: Int, icon: Int) =
        VolumeSliderView.Col(
            stream = stream,
            icon = icon,
            level = nowOf(am, stream),
            max = maxOf(am, stream),
            vibrate = silenced(am, stream),
        )

    /**
     * Whether this stream is *off* rather than merely quiet.
     *
     * Only the ringer and notifications have that state, and it is the ringer
     * mode that holds it — a stream sitting at zero with the phone in normal
     * mode is a level, and a stream in vibrate mode is a mode. Drawing them the
     * same way is what makes a silenced phone look like a bug.
     */
    private fun ringerStream(stream: Int) =
        stream == AudioManager.STREAM_RING || stream == AudioManager.STREAM_NOTIFICATION

    /**
     * Take the ringer to silence, or bring it back.
     *
     * **Zero is not a volume on these two streams, it is a ringer mode**, and
     * that is why holding volume-down used to stop at one notch and a drag to
     * the bottom refused to arrive: `setStreamVolume(RING, 0)` does not silence
     * a phone, `ringerMode` does, and Android quietly declines to make the last
     * step for you. Wrapped, because the mode is behind Do Not Disturb access on
     * some builds and a phone that will not go silent is better than a crash.
     */
    private fun setRinger(am: AudioManager, silent: Boolean) {
        runCatching {
            am.ringerMode =
                if (silent) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL
        }.onFailure { Log.w(TAG, "could not change the ringer mode", it) }
    }

    private fun silenced(am: AudioManager, stream: Int): Boolean {
        if (!ringerStream(stream)) return false
        return runCatching { am.ringerMode != AudioManager.RINGER_MODE_NORMAL }
            .getOrDefault(false)
    }

    private fun refreshCols(am: AudioManager) {
        cols.forEach {
            it.level = nowOf(am, it.stream)
            it.max = maxOf(am, it.stream)
            it.vibrate = silenced(am, it.stream)
        }
    }

    /**
     * Which stream a bare volume press is about.
     *
     * A call wins, and everything else is **media**. This used to fall through
     * to the ringer whenever nothing happened to be playing — which is what the
     * bare platform does, and it is the wrong default here: the volume keys on
     * this phone are reached for to change what you are about to listen to, and
     * landing on the ringer means the press before the music starts went to the
     * wrong scale. The ringer is still one swipe away in the expanded panel,
     * which is where a deliberate change to it belongs.
     */
    private fun streamFor(am: AudioManager): Int = when {
        am.mode == AudioManager.MODE_IN_CALL ||
            am.mode == AudioManager.MODE_IN_COMMUNICATION -> AudioManager.STREAM_VOICE_CALL
        else -> AudioManager.STREAM_MUSIC
    }

    private fun maxOf(am: AudioManager, stream: Int): Int =
        runCatching { am.getStreamMaxVolume(stream) }.getOrDefault(0)

    private fun nowOf(am: AudioManager, stream: Int): Int =
        runCatching { am.getStreamVolume(stream) }.getOrDefault(0)

    /** Push every stream's real level onto the slider. */
    private fun readOut(am: AudioManager, animate: Boolean) {
        val v = view ?: return
        refreshCols(am)
        if (animate) {
            for (i in cols.indices) v.refresh(i, animate = i == activeIndex)
        } else {
            v.refreshAll()
        }
        cols.getOrNull(activeIndex)?.let { markEnds(it.level, it.max) }
    }

    /**
     * Stretch the enclosure if the level has just *arrived* at either end.
     *
     * The edge, not the state: [wasAtMax] and [wasAtMin] exist so that holding
     * the key against the end of the scale stretches once and then stays still.
     */
    private fun markEnds(level: Int, max: Int) {
        val atMax = max > 0 && level >= max
        val atMin = level <= 0
        if (atMax && !wasAtMax) view?.requestImpact(fromTop = true)
        if (atMin && !wasAtMin) view?.requestImpact(fromTop = false)
        wasAtMax = atMax
        wasAtMin = atMin
    }

    // ---- the window ---------------------------------------------------------

    private fun dp(v: Float): Float {
        val d = app?.resources?.displayMetrics?.density ?: 3f
        return v * d
    }

    private fun screen(): Pair<Int, Int> {
        val b = wm?.maximumWindowMetrics?.bounds ?: return 1080 to 2400
        return b.width() to b.height()
    }

    /**
     * Which edge to stand on, and how far down it, *right now*.
     *
     * In portrait this is simply the setting. In landscape it is not: the
     * setting says "left" or "right" of a screen that has turned ninety
     * degrees, and half the time that puts the slider along the phone's top
     * edge, under the camera. So landscape overrides both — the slider goes on
     * the border **opposite the display cutout**, which is the phone's chin,
     * and centred on it.
     *
     * The cutout is asked for rather than derived from `Display.getRotation`,
     * whose sense is a thing to be wrong about: the cutout's own bounds are
     * already in the coordinates being placed into.
     */
    private fun placement(s: Settings): Pair<Boolean, Float> {
        val (sw, sh) = screen()
        if (sw <= sh) return (s.volumeSide == Prefs.SIDE_LEFT) to s.volumeY
        val cutoutLeft = cutoutOnLeft(sw)
        // Opposite the camera, and centred.
        return (cutoutLeft != true) to 0.5f
    }

    /** True, false, or null when the phone has no cutout to be on a side of. */
    private fun cutoutOnLeft(screenWidth: Int): Boolean? {
        val cut = runCatching {
            wm?.currentWindowMetrics?.windowInsets?.displayCutout
        }.getOrNull() ?: return null
        val rects = cut.boundingRects
        if (rects.isEmpty()) return null
        val centre = rects.sumOf { it.centerX() } / rects.size
        return centre < screenWidth / 2
    }

    /** Where the capsule rests: flush against its edge. */
    private fun restingX(width: Int, onLeft: Boolean): Int {
        val (sw, _) = screen()
        return if (onLeft) 0 else sw - width
    }

    /** Where it starts from and returns to: entirely off that edge. */
    private fun offscreenX(width: Int, onLeft: Boolean): Int {
        val (sw, _) = screen()
        return if (onLeft) -width else sw
    }

    private fun topFor(height: Int, fraction: Float): Int {
        val (_, sh) = screen()
        val centre = sh * fraction.coerceIn(0f, 1f)
        return (centre - height / 2f).toInt().coerceIn(0, (sh - height).coerceAtLeast(0))
    }

    private fun show() {
        val s = prefs?.now ?: return
        if (attached) return
        val ctx = svc ?: return
        val wm = wm ?: return
        val am = audio ?: return

        val v = VolumeSliderView(ctx)
        v.onLeft = placement(s).first
        v.contentDescription = "Volume"
        cols = buildCols(am)
        activeIndex = cols.indexOfFirst { it.stream == stream }.coerceAtLeast(0)
        v.columns = cols
        v.active = activeIndex
        v.expanded = false
        expanded = false
        v.refreshAll()
        v.setOnTouchListener { _, ev -> onSliderTouch(ev.actionMasked, ev.x, ev.y) }

        // Thickness before the window is measured, not after: the view draws
        // itself to fit whatever it is given, so a window sized from the
        // constant and a drawing sized from the setting would disagree by
        // however far the two had drifted apart.
        v.thicknessDp = s.volumeWidthDp.toFloat()
        val w = dp(v.thicknessDp).toInt()
        val h = dp(VolumeSliderView.windowHeightDp(s.volumeHeightDp.toFloat())).toInt()
        val (onLeft, downBy) = placement(s)

        val params = WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Touchable, so the slider can be dragged. FLAG_NOT_FOCUSABLE has
            // to stay: taking focus would close whatever is typing underneath.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = topFor(h, downBy)
            x = offscreenX(w, onLeft)
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        view = v
        lp = params

        if (runCatching { wm.addView(v, params) }.isFailure) {
            Log.e(TAG, "addView failed")
            view = null
            lp = null
            return
        }
        attached = true

        val now0 = cols.getOrNull(activeIndex)?.level ?: 0
        val max0 = cols.getOrNull(activeIndex)?.max ?: 0
        // Seeded, not marked: arriving already at the end is where the volume
        // *was*, and a stretch for it would be the slider reacting to nothing.
        wasAtMax = max0 > 0 && now0 >= max0
        wasAtMin = now0 <= 0

        slideTo(restingX(w, onLeft), IN_MS, ENTER, null)
    }

    /**
     * A touch on the slider, in the slider's own coordinates.
     *
     * Shared by the slider's own listener and by the scrim's routing, so that a
     * touch means the same thing whichever window happened to catch it.
     */
    private fun onSliderTouch(action: Int, x: Float, y: Float): Boolean {
        val v = view ?: return true
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                releaseKey()
                main.removeCallbacks(dismiss)
                // The chevron is decided *here and only here*. Testing it on
                // every move as well is what made a drag stop partway down: the
                // chevron's band is below the scale, so a finger heading for
                // zero crossed into it and every further move was ignored — the
                // fill froze a few notches short of the bottom and stayed there.
                if (v.isChevron(y)) {
                    dragging = false
                    toggleExpanded()
                    return true
                }
                dragging = true
                v.held = true
                dragTo(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return true
                releaseKey()
                main.removeCallbacks(dismiss)
                dragTo(x, y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                v.held = false
                // The stream may not have taken exactly what was asked for — Do
                // Not Disturb refuses the ringer, for one — so the finger coming
                // off is where the truth gets drawn.
                audio?.let { readOut(it, animate = true) }
                armDismiss()
            }
        }
        return true
    }

    /**
     * Start the countdown to going away — unless the panel is open, which is a
     * thing the user opened and only the user closes.
     */
    private fun armDismiss() {
        main.removeCallbacks(dismiss)
        if (!expanded) main.postDelayed(dismiss, lingerMs())
    }

    // ---- the panel ----------------------------------------------------------

    private fun toggleExpanded() {
        if (expanded) collapse() else expand()
    }

    private fun expand() {
        val v = view ?: return
        val params = lp ?: return
        val s = prefs?.now ?: return
        if (expanded || !attached) return
        expanded = true
        v.expanded = true
        main.removeCallbacks(dismiss)
        audio?.let { readOut(it, animate = false) }
        addScrim()
        // Every touch now arrives through the scrim, which is above this window;
        // leaving this one touchable as well would mean the two disagree about
        // which of them owns a press.
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm?.updateViewLayout(v, params) }
        widthTo(
            dp(VolumeSliderView.expandedWidthDp(cols.size, s.volumeWidthDp.toFloat())).toInt(),
        )
    }

    private fun collapse() {
        val v = view ?: return
        val params = lp ?: return
        if (!expanded) return
        expanded = false
        v.expanded = false
        removeScrim()
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { wm?.updateViewLayout(v, params) }
        widthTo(dp(v.thicknessDp).toInt())
        armDismiss()
    }

    private fun addScrim() {
        if (scrim != null) return
        val ctx = svc ?: return
        val wm = wm ?: return
        val (sw, sh) = screen()
        val sc = View(ctx)
        sc.setOnTouchListener { _, ev ->
            val params = lp
            if (params == null) {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) collapse()
                return@setOnTouchListener true
            }
            val lx = ev.x - params.x
            val ly = ev.y - params.y
            val inside = lx >= 0f && ly >= 0f && lx <= params.width && ly <= params.height
            if (inside) {
                onSliderTouch(ev.actionMasked, lx, ly)
            } else if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                collapse()
            }
            true
        }
        val p = WindowManager.LayoutParams(
            sw,
            sh,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        if (runCatching { wm.addView(sc, p) }.isFailure) {
            Log.w(TAG, "scrim addView failed; the panel will not close on an outside tap")
            return
        }
        scrim = sc
    }

    private fun removeScrim() {
        val sc = scrim ?: return
        runCatching { wm?.removeViewImmediate(sc) }
        scrim = null
    }

    /** Grow or shrink the panel, keeping whichever edge it is attached to. */
    private fun widthTo(target: Int) {
        val v = view ?: return
        val params = lp ?: return
        val s = prefs?.now ?: return
        val onLeft = placement(s).first
        slide?.cancel()
        val from = params.width
        if (from == target) return
        slide = ValueAnimator.ofInt(from, target).apply {
            duration = 320L
            interpolator = ENTER
            addUpdateListener {
                if (!attached) return@addUpdateListener
                params.width = it.animatedValue as Int
                params.x = restingX(params.width, onLeft)
                runCatching { wm?.updateViewLayout(v, params) }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (slide === animation) slide = null
                }
            })
            start()
        }
    }

    /** A rotation changes where both edges are, and how far down the middle is. */
    fun reposition() {
        val v = view ?: return
        val params = lp ?: return
        if (!attached) return
        if (slide?.isRunning == true) return
        val s = prefs?.now ?: return
        val (onLeft, downBy) = placement(s)
        params.x = restingX(params.width, onLeft)
        params.y = topFor(params.height, downBy)
        v.onLeft = onLeft
        runCatching { wm?.updateViewLayout(v, params) }
    }

    private fun hide() {
        if (!attached) return
        val params = lp ?: return
        if (expanded) return
        val onLeft = prefs?.now?.let { placement(it).first } ?: true
        slideTo(offscreenX(params.width, onLeft), OUT_MS, EXIT) { remove() }
    }

    private fun remove() {
        val v = view
        removeScrim()
        if (attached && v != null) runCatching { wm?.removeViewImmediate(v) }
        v?.stopAnimating()
        attached = false
        expanded = false
        view = null
        lp = null
    }

    private fun slideTo(x: Int, ms: Long, interpolator: Interpolator, then: (() -> Unit)?) {
        val v = view ?: return
        val params = lp ?: return
        slide?.cancel()
        val from = params.x
        if (from == x) {
            then?.invoke()
            return
        }
        slide = ValueAnimator.ofInt(from, x).apply {
            duration = ms
            this.interpolator = interpolator
            addUpdateListener {
                if (!attached) return@addUpdateListener
                params.x = it.animatedValue as Int
                runCatching { wm?.updateViewLayout(v, params) }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (slide === animation) slide = null
                    then?.invoke()
                }
            })
            start()
        }
    }
}
