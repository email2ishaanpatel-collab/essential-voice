package com.ishaan.essentialvoice.trigger

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.PersistableBundle
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ishaan.essentialvoice.Features
import com.ishaan.essentialvoice.Prefs
import com.ishaan.essentialvoice.game.GameMode
import com.ishaan.essentialvoice.island.Island
import com.ishaan.essentialvoice.media.NowPlaying
import com.ishaan.essentialvoice.sensor.BackTap
import com.ishaan.essentialvoice.voice.Bar
import com.ishaan.essentialvoice.voice.HomeSwipe
import com.ishaan.essentialvoice.volume.VolumeSlider
import com.ishaan.essentialvoice.UpdateNotice
import com.ishaan.essentialvoice.notes.NotesWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.ishaan.essentialvoice.voice.Dictation

/**
 * Watches the hardware keys and turns a *held* Essential Key into a dictation.
 *
 * Held, and only held. There was a tap mode once — press to start, press again
 * to send — and it was never right on this key: the system opens Essential
 * Space on a tap before anything here is consulted, so the first press did two
 * things and the second one did nothing anybody could see. Hold is the gesture
 * the key can actually give this app.
 *
 * An accessibility service is the only way a third-party app gets to see a
 * hardware key before the app in focus does, and — critically — the only way to
 * see the key *release*, which is what a hold-to-talk gesture is made of. It is
 * also what puts the finished text into the field the user was already typing in.
 */
class EssentialKeyService : AccessibilityService() {

    companion object {
        private const val TAG = "EVKey"

        /**
         * How long to leave the transcript on the clipboard before taking it
         * back. ACTION_PASTE returns before the target app has read the clip,
         * so clearing immediately pastes nothing.
         */
        private const val CLIPBOARD_RELEASE_MS = 400L

        @Volatile var instance: EssentialKeyService? = null
            private set

        val isRunning: Boolean get() = instance != null

        /**
         * Keys the system relies on. Never swallowed, and never offered as a
         * trigger, however tempting it is to bind volume-down.
         */
        private val RESERVED = setOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_WAKEUP,
            KeyEvent.KEYCODE_SLEEP,
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: Prefs

    /** Set between the hold firing and the key coming back up. */
    private var holding = false
    private var downAt = 0L
    private var pendingStart: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // Ask for no screen events at all. The manifest has to name an event
        // type for the service to be accepted, but this service never reads one
        // — it watches keys — and every type it stays subscribed to costs the
        // *system* an event built, marshalled and delivered here to be dropped,
        // for every app on the phone, all day. Keys are unaffected: they come
        // through flagRequestFilterKeyEvents, which is independent of this.
        //
        // Subscribing to typeNotificationStateChanged was tried, as a way to
        // catch a media session token off a posted notification. It does not
        // work — see NowPlaying — so the subscription is off again.
        runCatching {
            serviceInfo = serviceInfo?.apply { eventTypes = 0 }
        }.onFailure { Log.w(TAG, "could not drop event subscriptions", it) }

        prefs = Prefs.get(this)
        // This service is also the app's host: being bound by the system is what
        // lets it open the microphone without a foreground service, and so
        // without a permanent notification.
        Dictation.attach(this)
        // The island is hosted here too, and for the same reason — see Island.
        // It follows the setting rather than being switched on by the settings
        // screen directly, because this service is the only thing that can hold
        // the window and it is not necessarily alive when the switch is tapped.
        if (Features.ISLAND) Island.attach(this)
        // Game mode is hosted here for the same reasons again — it hides the
        // island and has to survive this service being restarted with the
        // phone's settings still changed. Attached before the collector runs,
        // because attaching is what restores a session that outlived its
        // process.
        if (Features.GAME_MODE) GameMode.attach(this)
        // The bottom bar is hosted here for the same reason the island is: a
        // TYPE_ACCESSIBILITY_OVERLAY can only be added through a bound
        // accessibility service's own Context. See Bar.
        Bar.attach(this)
        // And a fourth time for the two-finger swipe, which needs a window at
        // the bottom of the screen to catch the touch in — and needs it to be
        // above the navigation bar, which only this layer is. See HomeSwipe.
        if (Features.HOME_SWIPE) HomeSwipe.attach(this)
        // The volume slider is hosted here for the third time for the same two
        // reasons: only a bound accessibility service can add a
        // TYPE_ACCESSIBILITY_OVERLAY, and only a bound accessibility service
        // sees a volume key before the app in front does. See VolumeSlider.
        if (Features.VOLUME_SLIDER) VolumeSlider.attach(this)
        // Two knocks on the back of the phone. Nothing is registered with the
        // sensor until the setting asks for it — see BackTap.apply.
        if (Features.BACK_TAP) BackTap.attach(this)
        scope.launch {
            prefs.state.collect {
                if (Features.GAME_MODE) {
                    GameMode.apply(it)
                    watchWindows(it.game.autoArm)
                }
                if (Features.ISLAND) Island.apply(it)
                // After GameMode.apply, which is what decides whether the key —
                // and so the knock — is silenced for a game.
                if (Features.BACK_TAP) BackTap.apply(it)
                Bar.apply(it.pill)
                if (Features.HOME_SWIPE) HomeSwipe.apply(it)
                if (Features.VOLUME_SLIDER) VolumeSlider.apply(it)
            }
        }
        // Cheap, once a day, and the only thing that will ever remind the user a
        // new build exists — nobody opens a settings screen to go looking.
        scope.launch { UpdateNotice.checkIfDue(this@EssentialKeyService) }
        // The widget is normally told by NoteStore on every write, which covers
        // everything except a process that died between one write and the next.
        // This service is the first thing to come back, so it is the cheapest
        // place to make sure the home screen is showing the real list.
        NotesWidget.refresh(this)
        // Reads the media session out of posted notifications. Attached here
        // rather than by the island, because the token has to be caught whenever
        // one goes by — including while the island is switched off.
        if (Features.ISLAND) NowPlaying.attach(this)
        Log.i(TAG, "connected")
    }

    override fun onDestroy() {
        scope.cancel()
        if (Features.BACK_TAP) BackTap.detach()
        Bar.detach()
        if (Features.HOME_SWIPE) HomeSwipe.detach()
        if (Features.VOLUME_SLIDER) VolumeSlider.detach()
        if (Features.GAME_MODE) GameMode.detach()
        if (Features.ISLAND) {
            Island.detach()
            NowPlaying.detach()
        }
        Dictation.detach()
        instance = null
        super.onDestroy()
    }

    /**
     * A rotation changes what "centred" means. The island's position is stored
     * as a fraction of the screen precisely so it survives one, but the window
     * still has to be moved to the new pixels.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Features.ISLAND) Island.reposition()
        Bar.reposition()
        if (Features.HOME_SWIPE) HomeSwipe.reposition()
        if (Features.VOLUME_SLIDER) VolumeSlider.reposition()
    }

    /**
     * The only event this service ever subscribes to, and only while game mode
     * is asked to arm itself — see [watchWindows].
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (Features.GAME_MODE) GameMode.onForeground(pkg)
    }

    /**
     * Subscribe to window changes, or stop.
     *
     * [onServiceConnected] sets `eventTypes = 0` for a reason worth repeating:
     * every type this service stays subscribed to costs the *system* an event
     * built, marshalled and delivered here for every app on the phone, all day.
     * Game mode's auto-arm needs to know which app is in front, so it pays that
     * cost — and stops paying it the moment the switch goes off. That is the
     * whole reason auto-arm is a switch rather than something the app just does.
     */
    private var watchingWindows = false

    private fun watchWindows(on: Boolean) {
        if (on == watchingWindows) return
        val info = serviceInfo ?: return
        runCatching {
            info.eventTypes = if (on) AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED else 0
            serviceInfo = info
            watchingWindows = on
            Log.i(TAG, "window events ${if (on) "on" else "off"}")
        }.onFailure { Log.w(TAG, "could not change the event subscription", it) }
    }

    override fun onInterrupt() = Unit

    // ---- the key --------------------------------------------------------

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode

        // Learn mode: report whatever arrives so the app can show which key this
        // build of Nothing OS actually produces.
        //
        // Both halves matter. The Essential Key has no entry in the key layout,
        // so the framework reports KEYCODE_UNKNOWN (0) and the *scancode* — 250
        // on this phone — is the only thing that names it.
        if (prefs.learnModeLive) {
            if (event.action == KeyEvent.ACTION_DOWN && code !in RESERVED) {
                prefs.reportKey(code, event.scanCode)
                Log.i(TAG, "learn: keyCode=$code scan=${event.scanCode} dev=${event.device?.name}")
                return true
            }
            return false
        }

        // The volume keys, before the reserved list turns them away.
        //
        // They are on that list as *triggers* — binding a dictation to
        // volume-down would be binding it to something the phone needs — and
        // that is still true. Drawing the volume instead of the system doing it
        // is a different thing entirely, and the only key the system leaves
        // reachable that is worth drawing. VolumeSlider returns false unless it
        // both wants the key and has already acted on it, so a press is never
        // swallowed without the volume having moved.
        if (Features.VOLUME_SLIDER &&
            (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            if (VolumeSlider.onVolumeKey(event)) return true
        }

        if (code in RESERVED) return false
        // Game mode leaves the key entirely alone — not consumed, not acted on.
        // A key that is swallowed but does nothing is the worst of both: the
        // game underneath never sees it either, and it looks like the app has
        // hung rather than like it is out of the way.
        if (Features.GAME_MODE && GameMode.mutesKey) return false
        if (!matchesTrigger(event)) return false
        val settings = prefs.now

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return settings.consumeKey
                Log.i(TAG, "key down")
                downAt = event.eventTime

                pendingStart?.let { handler.removeCallbacks(it) }
                val r = Runnable {
                    // Only claim the hold if a dictation actually started. It
                    // does not when one is already running — a toggle from the
                    // power button or the island, or the tail of the previous
                    // press still finishing — and setting this unconditionally
                    // meant the release below ended a dictation this key never
                    // began.
                    holding = startDictation()
                }
                pendingStart = r
                handler.postDelayed(r, settings.holdMs.toLong())
            }

            KeyEvent.ACTION_UP -> {
                Log.i(TAG, "key up after ${event.eventTime - downAt}ms, holding=$holding")
                pendingStart?.let { handler.removeCallbacks(it) }
                pendingStart = null

                if (holding) {
                    holding = false
                    endDictation(event.eventTime - downAt)
                }
            }
        }
        return settings.consumeKey
    }

    /**
     * A learned key matches on whichever identifier it actually had. Scancode
     * wins when the framework could not name the key, which is the Essential
     * Key's situation.
     */
    private fun matchesTrigger(event: KeyEvent): Boolean {
        val s = prefs.now
        if (s.triggerScanCode > 0 && event.scanCode == s.triggerScanCode) return true
        return s.triggerKeyCode > 0 && event.keyCode == s.triggerKeyCode
    }

    private fun startDictation(): Boolean = Dictation.begin()

    private fun endDictation(heldMs: Long) = Dictation.end(heldMs)

    // ---- putting the text back ------------------------------------------

    /**
     * Insert [text] wherever the user was typing.
     *
     * Clipboard-then-paste rather than SET_TEXT: pasting keeps the caret where it
     * was and works inside editors that manage their own text, whereas SET_TEXT
     * replaces the field wholesale and loses anything already in it.
     * Returns true if it landed in a field, false if it is only on the clipboard.
     */
    fun insertText(text: String): Boolean {
        if (text.isBlank()) return false

        val focus = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            ?: return false

        return try {
            if (!focus.isEditable) return false

            // Pasting keeps the caret where it was and works inside editors that
            // manage their own text, so the clipboard is briefly borrowed even
            // when the user did not ask for a copy. Whatever happens below, it
            // has to be given back — see [releaseClipboard].
            val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            // Almost always null, and that is not a bug to fix. Since Android 10
            // the clipboard can only be *read* by whatever has focus, and an
            // accessibility service never does, so this is a best effort at
            // putting things back exactly and not something to depend on.
            val previous = runCatching { clip.primaryClip }.getOrNull()

            clip.setPrimaryClip(dictatedClip(text))
            val pasted = focus.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (pasted) {
                releaseClipboard(clip, previous)
                return true
            }

            // Fall back to appending, preserving whatever was already typed. The
            // clipboard was not needed for this route, but it was already taken
            // by the attempt above, so it still has to be handed back.
            val existing = focus.text?.toString() ?: ""
            val joined = if (existing.isEmpty()) text else "$existing $text"
            val args = android.os.Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, joined,
                )
            }
            focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                .also { releaseClipboard(clip, previous) }
        } catch (t: Throwable) {
            Log.w(TAG, "insertText failed", t)
            false
        } finally {
            @Suppress("DEPRECATION")
            runCatching { focus.recycle() }
        }
    }

    /**
     * The transcript, flagged so the system does not put it in clipboard history
     * or flash it up in the paste toast. It is dictation: it may well be a
     * message, an address or a password spoken out loud.
     */
    private fun dictatedClip(text: String): ClipData =
        ClipData.newPlainText("Essential Voice", text).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }

    /**
     * Give the clipboard back after a paste.
     *
     * With "Copy to clipboard" off, the transcript must not still be sitting
     * there afterwards — that switch being off is the whole promise. Restoring
     * [previous] is the nice outcome, but it is usually null (the clipboard
     * cannot be read from here), and the old code only restored when it was
     * non-null, which meant that in practice the transcript was simply left on
     * the clipboard and the switch did nothing. So: put back what there was if
     * it is known, and otherwise clear it outright.
     *
     * Delayed, because ACTION_PASTE has already returned by the time the target
     * app actually reads the clip.
     */
    private fun releaseClipboard(clip: ClipboardManager, previous: ClipData?) {
        if (Prefs.get(this).now.copyToClipboard) return
        handler.postDelayed({
            runCatching {
                if (previous != null) {
                    clip.setPrimaryClip(previous)
                } else {
                    clip.clearPrimaryClip()
                }
            }.onFailure { Log.w(TAG, "could not release the clipboard", it) }
        }, CLIPBOARD_RELEASE_MS)
    }
}
