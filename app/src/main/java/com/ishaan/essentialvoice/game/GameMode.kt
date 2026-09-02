package com.ishaan.essentialvoice.game

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ishaan.essentialvoice.Prefs
import com.ishaan.essentialvoice.Settings
import com.ishaan.essentialvoice.voice.Dictation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Game mode: one switch that gets the phone, and this app, out of the way.
 *
 * **The switch is a preference, not a method call.** Everything that turns game
 * mode on — the toggle in the app, the Quick Settings tile, a game coming to the
 * front — does exactly one thing: it writes `gameArmed`. This object watches the
 * settings flow like [com.ishaan.essentialvoice.island.Island] does and brings
 * the phone into line with whatever it says. That is what keeps three surfaces
 * that can each be tapped while the other two are not on screen from ever
 * disagreeing about whether game mode is on, and it is why [apply] is
 * idempotent and is called on every settings change rather than on the one that
 * concerns it.
 *
 * **Hosted by the accessibility service.** The same reason as everything else
 * here: the system binds it, so the app needs no foreground service and has no
 * permanent notification, and the one thing game mode does that needs a window
 * — hiding the island — can only be done from a bound service anyway.
 *
 * **What it cannot do.** It cannot make the game itself run faster. Nothing a
 * sideloaded app can reach schedules another app's threads, raises its priority,
 * or pins it to a core, and `ActivityManager.killBackgroundProcesses` has only
 * been able to kill the caller's *own* processes since Android 14 — so "free up
 * RAM" would be a button that does nothing. The platform's own answer,
 * `GameManager`, is reachable — but from `adb shell cmd game`, not from here.
 *
 * What is here instead is real, and it is only what survived being measured:
 * fewer interruptions, nothing of this app's drawing over the game, and the
 * animation frames the system spends between every screen. Four further levers
 * were removed after `dumpsys` showed they bought nothing — see [GameProfile].
 */
object GameMode {

    private const val TAG = "EVGame"

    /**
     * How long a non-game may be in front before an automatic session ends.
     *
     * A game is left for a moment constantly — a share sheet, a permission
     * dialog, the shade, an ad that opens the browser — and a mode that dropped
     * the moment any of those appeared would spend the session flapping. The
     * obvious transients are filtered by name in [GameApps.isTransient]; this
     * covers the rest.
     */
    private const val DISARM_GRACE_MS = 2_500L

    private var svc: AccessibilityService? = null
    private var app: Context? = null
    private var prefs: Prefs? = null
    private var scope: CoroutineScope? = null

    private val handler = Handler(Looper.getMainLooper())
    private var pendingDisarm: Runnable? = null

    /** Whether the levers are currently pulled, in *this* process. */
    private var applied = false

    /**
     * The profile the pulled levers were pulled from.
     *
     * Kept so that changing a lever *while* game mode is on takes effect, rather
     * than sitting there dead until the next session — which is what a switch
     * inside a mode that is currently running has to do to be believable. Null
     * for a session inherited from a process that died, where all this object
     * knows is that something is applied.
     */
    private var appliedProfile: GameProfile? = null

    /* ------------------------------------------------------------ lifecycle */

    /**
     * Attached by the accessibility service, and the first thing it does is put
     * back anything an earlier process left pulled.
     *
     * This is the safety net the whole design hangs off. The service is
     * restarted after a crash, after an app update and after its switch is
     * toggled in Settings, and each of those can happen with the rotation
     * locked and the notifications silenced. A snapshot on disk with the switch
     * off means exactly that, and it is restored here before anything else
     * happens.
     */
    fun attach(service: AccessibilityService) {
        svc = service
        val c = service.applicationContext
        app = c
        val p = Prefs.get(c)
        prefs = p
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        val stored = p.gameSnapshot
        if (!p.now.gameArmed) {
            if (stored.isNotEmpty()) {
                Log.i(TAG, "restoring a session that outlived its process")
                Levers.restore(c, Levers.Snapshot.decode(stored))
                p.gameSnapshot = ""
            }
            applied = false
        } else {
            // Resuming an armed session. The system settings are still where
            // they were left, so they must not be captured again — a second
            // capture would record game mode's own values as the ones to
            // restore, and the phone would never find its way back.
            applied = stored.isNotEmpty()
        }
    }

    /**
     * Let the phone go.
     *
     * Deliberately restores rather than leaving the levers pulled: the service
     * being torn down is the one moment this object can be sure it will not get
     * another chance. `gameArmed` is left alone, so if the service is coming
     * back — an update, a crash — [apply] arms it again on the way in, and if it
     * is not, the phone is already normal.
     */
    fun detach() {
        cancelPendingDisarm()
        release()
        scope?.cancel()
        scope = null
        svc = null
        app = null
        prefs = null
    }

    /* --------------------------------------------------------------- the switch */

    /** Whether the Essential Key should be left alone right now. */
    val mutesKey: Boolean
        get() = prefs?.now?.let { it.gameArmed && it.game.silenceKey } == true


    /**
     * Turn it on. [auto] records whether this was the phone's idea, because only
     * a session the phone started is allowed to end itself.
     */
    fun arm(context: Context, auto: Boolean = false) {
        val p = Prefs.get(context)
        cancelPendingDisarm()
        p.gameAutoArmed = auto
        p.setGameArmed(true)
    }

    fun disarm(context: Context) {
        cancelPendingDisarm()
        Prefs.get(context).setGameArmed(false)
    }

    fun toggle(context: Context) {
        if (Prefs.get(context).now.gameArmed) disarm(context) else arm(context, auto = false)
    }

    /* ------------------------------------------------------------ reconciling */

    /**
     * Bring the phone into line with [s].
     *
     * Called on every settings change, from the same collector that drives the
     * island. Idempotent: the work runs only on the edge where `gameArmed`
     * changes, or where the profile behind an armed session changed — so
     * turning one lever off mid-session takes effect without the session ending.
     */
    fun apply(s: Settings) {
        val context = app ?: return
        when {
            s.gameArmed && !applied -> pull(context, s)
            !s.gameArmed && applied -> release()
            // A lever moved mid-session. Put everything back and pull it again
            // from the new profile: restoring first is what keeps the snapshot
            // holding the phone's own values rather than game mode's.
            s.gameArmed && appliedProfile != null && appliedProfile != s.game -> {
                release()
                pull(context, s)
            }
        }
    }

    private fun pull(context: Context, s: Settings) {
        Log.i(TAG, "arming: ${s.game}")
        // A dictation in flight would be left with nowhere to put its text and a
        // pill over the game. Ending it is kinder than racing it.
        if (s.game.silenceKey) runCatching { Dictation.cancel() }

        // Read, write it down, *then* change anything. If this process dies
        // between the second and third lines the worst case is a restore that
        // puts back values nothing had changed; any other order has a window in
        // which the levers are pulled and no record exists of what they were,
        // and a death inside that window strands the phone for good. The write
        // is a commit() rather than an apply() for the same reason — this one is
        // worth blocking a few milliseconds for.
        prefs?.gameSnapshot = Levers.capture(context, s.game).encode()
        Levers.apply(context, s.game)
        applied = true
        appliedProfile = s.game

        GameTile.refresh(context)
    }

    private fun release() {
        val context = app ?: return
        val stored = prefs?.gameSnapshot.orEmpty()
        if (stored.isNotEmpty()) {
            Levers.restore(context, Levers.Snapshot.decode(stored))
            prefs?.gameSnapshot = ""
        }
        applied = false
        appliedProfile = null
        // The tile is the surface most likely to be looking at this and least
        // likely to have caused it — auto-arm changes the state with nobody's
        // finger anywhere near it.
        GameTile.refresh(context)
        Log.i(TAG, "disarmed")
    }

    /* -------------------------------------------------------------- auto-arm */

    /**
     * The app in front changed.
     *
     * Fed by the accessibility service, and *only* while [GameProfile.autoArm]
     * is on — the service subscribes to window events when the switch goes on
     * and unsubscribes when it goes off, so a phone whose owner has not asked
     * for this does not pay for it. See EssentialKeyService.watchWindows.
     */
    fun onForeground(packageName: String) {
        val context = app ?: return
        val p = prefs ?: return
        val s = p.now
        if (!s.game.autoArm) return
        if (GameApps.isTransient(context, packageName)) return

        if (packageName in s.game.armFor) {
            cancelPendingDisarm()
            if (!s.gameArmed) {
                Log.i(TAG, "auto-arming for $packageName")
                arm(context, auto = true)
            }
            return
        }

        // Only a session the phone started may be ended by the phone. Somebody
        // who turned game mode on by hand meant it, and does not expect opening
        // their messages to switch it off.
        if (s.gameArmed && p.gameAutoArmed && pendingDisarm == null) {
            val r = Runnable {
                pendingDisarm = null
                if (prefs?.gameAutoArmed == true) {
                    Log.i(TAG, "auto-disarming, game left")
                    disarm(context)
                }
            }
            pendingDisarm = r
            handler.postDelayed(r, DISARM_GRACE_MS)
        }
    }

    private fun cancelPendingDisarm() {
        pendingDisarm?.let { handler.removeCallbacks(it) }
        pendingDisarm = null
    }
}
