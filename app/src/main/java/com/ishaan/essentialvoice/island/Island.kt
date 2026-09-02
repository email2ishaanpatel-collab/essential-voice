package com.ishaan.essentialvoice.island

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.PathInterpolator
import com.ishaan.essentialvoice.Prefs
import com.ishaan.essentialvoice.media.NowPlaying
import com.ishaan.essentialvoice.notify.Feed
import com.ishaan.essentialvoice.voice.Dictation
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Essential Island: a permanent tap target for dictation.
 *
 * The Essential Key is the fast way in and always will be, but it is one key on
 * one phone. This is the way in that works on any phone, in any app, without a
 * key to hold: tap the lozenge, talk, tap it again.
 *
 * **Hosted by the accessibility service**, exactly like [Dictation], and for
 * exactly the same reason — the system binds an accessibility service with
 * BIND_FOREGROUND_SERVICE, so this app needs no foreground service and
 * therefore has no permanent notification. That is the single most important
 * property of this file. A dynamic-island clone written the usual way ships a
 * foreground service and an ongoing notification, and it would have made the
 * app worse than the feature is worth. It also means the island can only exist
 * when dictation can actually run, so there is no state where it is on screen
 * and dead to the touch.
 *
 * **The window is a TYPE_ACCESSIBILITY_OVERLAY, and that is what makes it
 * tappable at the camera.** Window layers, read off this phone:
 *
 *     APPLICATION_OVERLAY     111000
 *     STATUS_BAR              151000
 *     ACCESSIBILITY_OVERLAY   311000
 *
 * As a TYPE_APPLICATION_OVERLAY the island sat *below* the status bar, and the
 * status bar does not merely paint over it — it takes the touches, across the
 * full width, down to the inset. An island drawn at the camera rendered
 * perfectly and could not be tapped at all; dragging it pulled the notification
 * shade down instead. The old workaround was to draw the lozenge tall enough to
 * hang below that line and put everything a finger goes for down there.
 *
 * TYPE_ACCESSIBILITY_OVERLAY is sixteen layers above the status bar, needs no
 * SYSTEM_ALERT_WINDOW, and is available to this app for the same reason
 * everything else here is: the service is already bound. **The window has to be
 * added through the accessibility service's own Context** — the application
 * context cannot create one — which is why [attach] keeps the service rather
 * than only its application context.
 *
 * The window is still deliberately no bigger than the lozenge: a window larger
 * than what it draws would swallow taps meant for the app underneath. That is
 * the same lesson the pill learned; see [Dictation].
 *
 * FLAG_NOT_FOCUSABLE has to stay. The island is tapped while another app has a
 * text field focused, and taking focus would empty the field the transcript is
 * meant to land in.
 */
object Island {

    private const val TAG = "EVIsland"

    /** How long a notification peek stays up. */

    private var app: Context? = null

    /**
     * The accessibility service itself.
     *
     * Not interchangeable with [app]: a TYPE_ACCESSIBILITY_OVERLAY may only be
     * added by the WindowManager belonging to a bound accessibility service, and
     * the application context's one throws.
     */
    private var svc: Context? = null
    private var wm: WindowManager? = null
    private var prefs: Prefs? = null

    private var view: IslandView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var attached = false

    /**
     * Whether the lozenge is actually on screen right now.
     *
     * Not the same as the setting: game mode takes the island off the screen
     * without touching it, and the window is gone entirely while the
     * accessibility service is unbound. Anything that suppresses part of the
     * phone's own behaviour on the island's behalf has to ask *this*, not the
     * switch — silencing a banner because a setting is on, while the island
     * that was supposed to replace it is not there, loses the notification.
     */
    val isLive: Boolean get() = attached

    /**
     * How far down the screen the camera reaches, in pixels.
     *
     * The lozenge is *supposed* to sit behind the lens — that is the whole
     * design, and the settings screen offers "thin enough to hide behind the
     * camera" as a virtue. What must not sit behind it is anything you have to
     * read. So the card keeps its background up there, filling the notch as
     * before, and puts its words below this line.
     *
     * Worth stating because it cost time: **a screenshot does not show the
     * camera hole.** Those pixels are rendered and then physically covered by
     * the lens, so a text layout that is obviously broken on the phone looks
     * perfect in `adb exec-out screencap`. The number has to come from
     * DisplayCutout, and it can never be checked by eye on a screenshot.
     */
    private var cutoutBottomPx = 0f

    private fun measureCutout() {
        val w = wm ?: return
        cutoutBottomPx = runCatching {
            w.currentWindowMetrics.windowInsets.displayCutout?.safeInsetTop?.toFloat()
        }.getOrNull() ?: 0f
    }

    /**
     * Tell the view where the readable area starts, in *its own* coordinates.
     *
     * Recomputed rather than stored, because it is the difference between two
     * things that both move: the camera is fixed on the screen, but the window
     * is wherever the user put the island, and it slides during a morph.
     */
    private fun syncBand() {
        val v = view ?: return
        val y = lp?.y ?: 0
        v.bandTopPx = (cutoutBottomPx - y).coerceAtLeast(0f)
    }

    /**
     * How tall the alert card has to be for its two lines to clear the camera.
     *
     * Derived, not a constant: the band depends on where the user has put the
     * island, so a fixed height is right for exactly one value of "From the
     * top". The card grows to fit the text rather than the text being squeezed
     * into the card — Ishaan's call, 2026-08-31, and the right one: the island
     * is briefly taller, and the alternative is words nobody can read.
     */
    private fun alertHeightPx(): Int {
        val y = lp?.y ?: 0
        val band = (cutoutBottomPx - y).coerceAtLeast(0f)
        return (band + dp(IslandView.ALERT_BODY_DP)).toInt()
    }

    /**
     * Touch state.
     *
     * There is no drag. The island used to be draggable and is not any more:
     * it sits over every app all day, so a press that can move it is a press
     * that can be *nudged* by a finger aiming at something underneath — and the
     * one thing a permanent control must never do is quietly stop being where
     * you left it. Position is a setting now, edited in the app where changing
     * it is deliberate. See Prefs.islandX / islandTopDp and the settings screen.
     */
    private var downHitSlopX = 0f
    private var downHitSlopY = 0f

    /**
     * Whether the dictation now running was started by tapping this island.
     *
     * It decides which of the island's two active looks is right. Started here,
     * the island is the interface: it widens and the dots move, and the side
     * pill is suppressed because two surfaces narrating one dictation is noise.
     * Started anywhere else — the key, the assistant — the pill is already doing
     * that job, so the island only turns its dot to the accent colour.
     */
    private var startedHere = false

    /**
     * Whether the dictation now running was started from the island, and so has
     * the island itself as its stop control. See [Bar.hasClaim].
     */
    val hasClaim: Boolean get() = startedHere

    /** Called by the accessibility service once the system has bound it. */
    fun attach(context: Context) {
        val c = context.applicationContext
        app = c
        svc = context
        // From the service, not from `c`: see the note on [svc].
        wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = Prefs.get(c)
        measureCutout()
    }

    fun detach() {
        remove()
        app = null
        svc = null
        wm = null
        prefs = null
    }

    /**
     * Bring the island into line with the settings.
     *
     * Idempotent, because it is called on every settings change rather than
     * only on the one that concerns it — cheaper than working out which setting
     * moved, and it cannot drift out of step.
     */
    fun apply(s: com.ishaan.essentialvoice.Settings) {
        // islandVisible, not island: game mode takes the lozenge off the screen
        // without touching the setting, so that switching game mode off brings
        // back whatever was there before. See Settings.islandVisible.
        if (!s.islandVisible) {
            remove()
            return
        }
        add()
        view?.setStyle(s.pill)
        // Geometry is a setting now, so a change to it has to reach the window
        // and not just the next time it is created.
        // Whatever has taken the window over owns the whole geometry; a settings
        // change underneath it is picked up when it hands the window back.
        if (mediaOpen || shape != IslandView.Mode.COMPACT) return
        setWidth(s.islandWidthDp.toFloat())
        // Don't fight an expansion in progress: the height belongs to
        // setExpanded until the dictation ends.
        if (!expanded) lp?.height = dp(s.islandHeightDp.toFloat())
        place()
    }

    /** Whether the lozenge is currently at its doubled height. */
    private var expanded = false

    /** Whether the media player is open. */
    private var mediaOpen = false

    /** Which of the takeover shapes is on screen, if any. */
    private var shape = IslandView.Mode.COMPACT



    /** Which control the current press went down on. */
    private var downHit = IslandView.HIT_NONE

    /** Geometry to restore when the player closes. */
    private var restoreW = 0
    private var restoreH = 0
    private var restoreX = 0
    private var restoreY = 0

    /** Ticks the scrubber while the player is open, and only then. */
    private var tick: Runnable? = null
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Open the player: the lozenge becomes a card.
     *
     * The window is resized rather than a second window being added — one window
     * means one thing to place, one thing to drag and one thing to tear down,
     * and the card is the same object as the lozenge in the fiction the island
     * is selling.
     */
    private fun openPlayer() {
        val params = lp ?: return
        val v = view ?: return
        if (!attached || mediaOpen) return
        if (shape != IslandView.Mode.COMPACT) return
        restoreW = params.width
        restoreH = params.height
        restoreX = params.x
        restoreY = params.y

        val (sw, sh) = screen() ?: return
        val w = dp(IslandView.PLAYER_W_DP)
        val h = dp(IslandView.PLAYER_H_DP)
        mediaOpen = true
        shape = IslandView.Mode.PLAYER
        v.mode = IslandView.Mode.PLAYER
        params.width = w
        params.height = h
        // Centred horizontally and pinned just under the top edge, like the
        // reference — a card that opened off-centre would read as a dialog.
        params.x = ((sw - w) / 2).coerceAtLeast(0)
        params.y = restoreY.coerceIn(0, (sh - h).coerceAtLeast(0))
        runCatching { wm?.updateViewLayout(v, params) }
        pushMedia()
        startTicking()
    }

    /* --------------------------------------------------- calls, alerts, timers */

    private fun showCall(call: Feed.Call) {
        val v = view ?: return
        v.setCall(call.who, call.ringing)
        if (shape == IslandView.Mode.CALL) return
        if (mediaOpen) closePlayer()
        stopPeekTimeout()
        takeShape(
            IslandView.Mode.CALL,
            dp(IslandView.CALL_W_DP),
            dp(IslandView.CALL_H_DP),
        )
    }

    private fun showAlert() {
        val a = Feed.alert ?: return
        val v = view ?: return
        v.setAlert(a.appName, a.title, a.text, a.icon)
        takeShape(
            IslandView.Mode.ALERT,
            dp(IslandView.ALERT_W_DP),
            alertHeightPx(),
        )
        // A peek is a peek. It says something arrived and then gets out of the
        // way; an island that keeps the last notification up forever is a
        // notification shade nobody asked for, permanently over every app.
        ticker.removeCallbacks(peekOut)
        // The alert says how long it needs. A notification is a peek; an answer
        // somebody is waiting to read is not.
        ticker.postDelayed(peekOut, a.holdMs)
    }

    private val peekOut = Runnable {
        if (shape == IslandView.Mode.ALERT) {
            Feed.clearAlert()
            restoreShape()
        }
    }

    private fun stopPeekTimeout() {
        ticker.removeCallbacks(peekOut)
    }

    /**
     * The shape change, as a movement rather than a cut.
     *
     * A notification used to *appear*: one updateViewLayout and the card was
     * simply there, at full size, which reads as a panel sliding in over the
     * screen — the very thing the island exists instead of. Growing the window
     * from the lozenge is what makes it read as the island itself expanding,
     * and it is the whole of the difference.
     *
     * The window is animated, not a view inside it. That is forced rather than
     * chosen: a view cannot paint outside its own surface, so a lozenge-sized
     * window has nowhere to draw a card. It is the same constraint the pill's
     * slide-in ran into. The cost is an updateViewLayout per frame, which for
     * one small window is cheap enough — and it is bounded, because the island
     * only ever morphs on an event the user is already looking at.
     *
     * Interruptible on purpose: a call landing on top of a peek has to be able
     * to take the window mid-growth, so any morph in flight is cancelled rather
     * than queued. [done] fires only on a morph that finished, never on one
     * that was cancelled, which is what keeps a cancelled collapse from
     * flipping the mode back to COMPACT underneath the shape that replaced it.
     */
    private var morph: ValueAnimator? = null

    /** Android's emphasised-decelerate: leaves fast, settles slow. */
    private val MORPH_CURVE = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

    private const val MORPH_MS = 340L

    private fun morphTo(w: Int, h: Int, x: Int, y: Int, done: (() -> Unit)? = null) {
        val params = lp ?: return
        val v = view ?: return
        if (!attached) return

        morph?.cancel()

        val w0 = params.width
        val h0 = params.height
        val x0 = params.x
        val y0 = params.y
        if (w0 == w && h0 == h && x0 == x && y0 == y) {
            v.morph = 1f
            done?.invoke()
            return
        }

        // Growing fades content in, shrinking fades it out, and "growing" is
        // decided by area so a card that is wider but shorter still counts.
        val growing = w.toLong() * h >= w0.toLong() * h0
        var finished = false

        morph = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MORPH_MS
            interpolator = MORPH_CURVE
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                val p = lp ?: return@addUpdateListener
                val vv = view ?: return@addUpdateListener
                p.width = (w0 + (w - w0) * t).roundToInt()
                p.height = (h0 + (h - h0) * t).roundToInt()
                p.x = (x0 + (x - x0) * t).roundToInt()
                p.y = (y0 + (y - y0) * t).roundToInt()
                vv.morph = if (growing) t else 1f - t
                syncBand()
                if (attached) runCatching { wm?.updateViewLayout(vv, p) }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    finished = false
                }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!finished) return
                    view?.morph = 1f
                    done?.invoke()
                }
                override fun onAnimationStart(animation: android.animation.Animator) {
                    finished = true
                }
            })
            start()
        }
    }

    /**
     * Grow the window into one of the takeover shapes, remembering what to go
     * back to.
     *
     * Only the *first* takeover records the resting geometry — a call arriving
     * over an alert must not record the alert's size as the thing to restore.
     */
    private fun takeShape(next: IslandView.Mode, w: Int, h: Int) {
        val params = lp ?: return
        val v = view ?: return
        if (!attached) return
        if (shape == IslandView.Mode.COMPACT) {
            restoreW = params.width
            restoreH = params.height
            restoreX = params.x
            restoreY = params.y
        }
        val (sw, sh) = screen() ?: return
        shape = next
        // Set before the morph, not after: the point of growing rather than
        // appearing is that you watch the lozenge *become* the card, so the
        // card's content has to be what is being revealed the whole way.
        v.mode = next
        morphTo(
            w = w,
            h = h,
            x = ((sw - w) / 2).coerceAtLeast(0),
            y = restoreY.coerceIn(0, (sh - h).coerceAtLeast(0)),
        )
    }

    /** Back to the lozenge. */
    private fun restoreShape() {
        val params = lp ?: return
        val v = view ?: return
        if (shape == IslandView.Mode.COMPACT) return
        stopPeekTimeout()
        shape = IslandView.Mode.COMPACT
        if (!attached) {
            v.mode = IslandView.Mode.COMPACT
            return
        }
        // Mode flips only once the window is back to the lozenge's size. Going
        // the other way it flips first (see takeShape) — both so that what is
        // on screen during the morph is the *larger* of the two states, which
        // is what makes it read as one shape changing rather than two shapes
        // swapping.
        morphTo(restoreW, restoreH, restoreX, restoreY) {
            v.mode = IslandView.Mode.COMPACT
            place()
        }
    }

    /**
     * The countdown, once a second, and only while one is running.
     *
     * The remaining time is arithmetic against a single instant the notification
     * gave us (see Feed.Timer), so this is a redraw of one small window and
     * nothing more — but it still stops the moment the timer does.
     */
    private var timerTick: Runnable? = null

    private fun syncTimerTicker(want: Boolean) {
        if (want == (timerTick != null)) return
        if (!want) {
            stopTimerTicker()
            return
        }
        val r = object : Runnable {
            override fun run() {
                val t = Feed.timer ?: return stopTimerTicker()
                view?.timerText = fmtRemaining(t.remaining)
                ticker.postDelayed(this, 1000L)
            }
        }
        timerTick = r
        ticker.postDelayed(r, 1000L)
    }

    private fun stopTimerTicker() {
        timerTick?.let { ticker.removeCallbacks(it) }
        timerTick = null
    }

    /** m:ss under an hour, h:mm over it — a glance, not a stopwatch. */
    private fun fmtRemaining(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return if (h > 0) "%d:%02d".format(h, m) else "%d:%02d".format(m, sec)
    }

    private fun closePlayer() {
        val params = lp ?: return
        val v = view ?: return
        if (!mediaOpen) return
        mediaOpen = false
        shape = IslandView.Mode.COMPACT
        stopTicking()
        v.mode = IslandView.Mode.COMPACT
        params.width = restoreW
        params.height = restoreH
        params.x = restoreX
        params.y = restoreY
        if (attached) runCatching { wm?.updateViewLayout(v, params) }
        place()
    }

    /**
     * A second a tick, and only while the card is on screen.
     *
     * Position is arithmetic rather than a query (see NowPlaying), so this costs
     * a redraw of one small window and nothing else — but a redraw of anything,
     * forever, for a card nobody is looking at, is exactly the kind of thing
     * that makes an overlay app a battery complaint.
     */
    private fun startTicking() {
        stopTicking()
        val r = object : Runnable {
            override fun run() {
                if (!mediaOpen) return
                pushMedia()
                ticker.postDelayed(this, 1000L)
            }
        }
        tick = r
        ticker.postDelayed(r, 1000L)
    }

    private fun stopTicking() {
        tick?.let { ticker.removeCallbacks(it) }
        tick = null
    }

    private fun pushMedia() {
        view?.setMedia(
            NowPlaying.title,
            NowPlaying.artist,
            NowPlaying.playing,
            NowPlaying.position,
            NowPlaying.duration,
        )
    }

    /**
     * Double the height while a dictation this island started is running, and
     * put it back afterwards.
     *
     * Downward, because the window's gravity is TOP and the top edge is the part
     * that has to stay behind the camera. The half that appears is below the
     * status bar, so it is both visible and touchable — which is the whole
     * reason the growth is worth anything.
     */
    private fun setExpanded(active: Boolean) {
        val params = lp ?: return
        val v = view ?: return
        if (!attached) return
        // The card owns the geometry while it is open. A dictation started from
        // the key or the assistant would otherwise resize the window out from
        // under the player, and put it back at the lozenge's size.
        if (mediaOpen || shape != IslandView.Mode.COMPACT) return
        val base = dp((prefs?.now?.islandHeightDp ?: 30).toFloat())
        val target = if (active) base * 2 else base
        expanded = active
        if (params.height != target) {
            params.height = target
            runCatching { wm?.updateViewLayout(v, params) }
        }
        // Re-sync the whole geometry on the way back down. apply() skips the
        // height while expanded, so any change made to the settings *during* a
        // dictation was dropped and did not reappear until the next unrelated
        // setting happened to change.
        if (!active) place()
        syncBand()
    }

    /** The stored position is a fraction of the screen, so a rotation re-places it. */
    fun reposition() {
        if (!attached) return
        // A rotation moves the camera relative to the screen.
        measureCutout()
        place()
    }

    private fun add() {
        if (attached) return
        val ctx = svc ?: return
        val wm = wm ?: return

        // No canDrawOverlays check. A TYPE_ACCESSIBILITY_OVERLAY is granted by
        // the service binding, not by SYSTEM_ALERT_WINDOW, so requiring the
        // overlay permission here would refuse to show an island the system is
        // perfectly willing to give us. The pill still needs it; this does not.

        val v = IslandView(ctx)
        v.setStyle(prefs?.now?.pill ?: com.ishaan.essentialvoice.voice.PillStyles.all[0])
        v.setOnTouchListener(touch)
        // The tap goes through performClick rather than being fired straight out
        // of the touch listener, so the island answers TalkBack's activation the
        // same way it answers a finger.
        v.setOnClickListener {
            // The cover is the media button; the rest of the lozenge is still the
            // dictation button it has always been. Splitting by region rather
            // than by mode means the gesture people already have never changes
            // meaning under them because something started playing.
            if (mediaOpen) return@setOnClickListener
            if (downHit == IslandView.HIT_ART && NowPlaying.isActive) {
                openPlayer()
                return@setOnClickListener
            }
            if (!Dictation.isReady) return@setOnClickListener
            // isBusy rather than isListening: between the release and the
            // transcript the dictation is still running but not capturing, and a
            // tap then would set these flags for a begin() that is about to
            // return early on `if (busy)` — leaving suppressPill set for whatever
            // starts next.
            if (!Dictation.isBusy) {
                startedHere = true
                // The island is the whole interface for this one; the pill would
                // be a second thing saying the same thing somewhere else.
                Dictation.suppressPill = true
            }
            Dictation.toggle()
        }
        v.contentDescription = "Essential Voice — tap to dictate"

        val settings = prefs?.now
        val params = WindowManager.LayoutParams(
            dp((settings?.islandWidthDp ?: 120).toFloat()),
            dp((settings?.islandHeightDp ?: 30).toFloat()),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Allowed into the cutout row, which is the whole point of an island.
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        view = v
        lp = params
        place()

        if (runCatching { wm.addView(v, params) }.isFailure) {
            Log.e(TAG, "addView failed")
            view = null
            lp = null
            return
        }
        attached = true

        // Follow the dictation rather than poll it. Keyed, because the bottom
        // bar follows it too — see Dictation.watch.
        Dictation.watch(
            TAG,
            onActivity = { busy, listening ->
                if (!busy) startedHere = false
                view?.setState(
                    when {
                        !busy -> IslandView.State.IDLE
                        !startedHere -> IslandView.State.ARMED
                        listening -> IslandView.State.LISTENING
                        else -> IslandView.State.WORKING
                    },
                )
                // Only a dictation this island started makes it grow, and it
                // grows *downward*: the top edge stays put behind the camera and
                // the new half drops below the status bar, which is the only
                // part of the screen this window can actually be seen and
                // touched in. The bars are drawn in that half — see IslandView.
                setExpanded(busy && startedHere)
            },
            onLevel = { level -> view?.pushLevel(level) },
        )

        // What is playing, when anything is. The token arrives with a posted
        // notification and is caught by the service, not by this file — the
        // island only draws what NowPlaying already knows.
        NowPlaying.onChange = { syncMedia() }
        Feed.onChange = { syncFeed() }
        syncMedia()
        syncFeed()
    }

    /**
     * Decide what the island should be, and be it.
     *
     * The order is the whole design. A ringing phone outranks everything,
     * including a dictation in progress — nothing else on this island is worth
     * missing a call over. A dictation the user is in the middle of outranks a
     * timer and an alert, because it is the one thing here they are actively
     * *doing*. The media card is only ever opened deliberately, so it is never
     * displaced by anything below a call.
     */
    private fun syncFeed() {
        val call = Feed.call
        val timer = Feed.timer

        // The compact lozenge carries the countdown whatever else is going on.
        view?.timerText = timer?.let { fmtRemaining(it.remaining) }
        syncTimerTicker(timer != null)

        when {
            // Ringing only. Answering is the thing the island is for; once the
            // call is connected the dialer owns the screen, and a card of ours
            // parked over the top of it for the whole conversation would be
            // something to get rid of rather than something to use.
            call != null && call.ringing -> showCall(call)
            shape == IslandView.Mode.CALL -> restoreShape()
            // Never over a dictation. The island is a button first, and four
            // seconds of somebody else's notification sitting on it is four
            // seconds it cannot be pressed to stop recording.
            Feed.alert != null && !mediaOpen && !expanded && !Dictation.isBusy ->
                showAlert()
            else -> Unit
        }
    }

    /** Push the current cover onto the lozenge, and the rest onto the card. */
    private fun syncMedia() {
        val live = NowPlaying.isActive
        view?.art = if (live) NowPlaying.art else null
        if (mediaOpen) {
            // The player outliving the thing it is playing is the one state this
            // card must never be in.
            if (!live) closePlayer() else pushMedia()
        }
    }

    private fun remove() {
        stopTicking()
        stopPeekTimeout()
        shape = IslandView.Mode.COMPACT
        mediaOpen = false
        startedHere = false
        Dictation.unwatch(TAG)
        NowPlaying.onChange = null
        Feed.onChange = null
        stopTimerTicker()
        stopPeekTimeout()
        val v = view
        if (attached && v != null) runCatching { wm?.removeViewImmediate(v) }
        attached = false
        view = null
        lp = null
    }

    /** Puts the window where the stored fraction says, clamped onto the screen. */
    private fun place() {
        val params = lp ?: return
        val s = prefs?.now ?: return
        val (sw, sh) = screen() ?: return
        params.x = (s.islandX * sw - params.width / 2f).roundToInt()
            .coerceIn(0, (sw - params.width).coerceAtLeast(0))
        params.y = dp(s.islandTopDp.toFloat()).coerceIn(yRange(sh, params.height))
        // Zero now, and permanently: above the status bar the whole lozenge is
        // reachable, so there is no band to keep out of. Left as a field rather
        // than deleted because the expanded layout is about to be rewritten
        // around the media player, and IslandView's positioning goes with it.
        syncBand()
        // Curvature comes from the collapsed height, so doubling stretches the
        // lozenge rather than rounding it into a blob.
        view?.cornerPx = dp(s.islandHeightDp.toFloat()) / 2f
        val v = view
        if (attached && v != null) runCatching { wm?.updateViewLayout(v, params) }
    }

    /**
     * Grow or shrink the lozenge about its own centre.
     *
     * The window is resized rather than a large window being drawn into: a
     * window bigger than what it paints swallows taps meant for the app
     * underneath, and this one sits over the top of every app all day.
     */
    private fun setWidth(widthDp: Float) {
        val params = lp ?: return
        val v = view ?: return
        if (!attached) return
        val target = dp(widthDp)
        if (params.width == target) return
        val (sw, _) = screen() ?: return
        val centre = params.x + params.width / 2f
        params.width = target
        params.x = (centre - target / 2f).roundToInt()
            .coerceIn(0, (sw - target).coerceAtLeast(0))
        runCatching { wm?.updateViewLayout(v, params) }
    }

    /**
     * The vertical range the island's *top* may occupy: anywhere on the screen.
     *
     * This used to hold the lozenge down far enough to keep a tappable band
     * below the status bar, and that was wrong. Someone who sets "From the top"
     * to 0 wants it behind the camera, and quietly moving it to 78px instead
     * makes the setting look broken — which is exactly how it looked. Whether
     * the island can be tapped is a consequence of the geometry, not a rule to
     * enforce over the person choosing it; the settings screen says plainly when
     * the current numbers put it out of reach, and that is the right place for
     * it.
     */
    private fun yRange(screenH: Int, windowH: Int): IntRange =
        0..(screenH - windowH).coerceAtLeast(0)

    private fun screen(): Pair<Int, Int>? {
        val b = wm?.currentWindowMetrics?.bounds ?: return null
        return b.width() to b.height()
    }

    private val touch = View.OnTouchListener { v, e ->
        val island = v as? IslandView ?: return@OnTouchListener false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downHitSlopX = e.rawX
                downHitSlopY = e.rawY
                downHit = island.hitTest(e.x, e.y)
                // The card's own controls do not light the whole surface up.
                island.setHeld(!mediaOpen)
                true
            }

            MotionEvent.ACTION_UP -> {
                island.setHeld(false)
                // A press that wandered is not a tap. Nothing moves any more, so
                // the slop is only here to keep a scroll that started on the
                // island from firing it on the way past.
                val slop = ViewConfiguration.get(v.context).scaledTouchSlop
                val strayed = abs(e.rawX - downHitSlopX) + abs(e.rawY - downHitSlopY) > slop
                if (!strayed) {
                    when {
                        shape == IslandView.Mode.CALL -> handleCallTap()
                        shape == IslandView.Mode.ALERT -> handleAlertTap()
                        mediaOpen -> handlePlayerTap(island, e.x)
                        else -> island.performClick()
                    }
                }
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                island.setHeld(false)
                true
            }

            else -> false
        }
    }

    /**
     * A tap on the call card.
     *
     * The card is never dismissed by tapping past the buttons, unlike the media
     * card. A call is not something you glance at and put away — it ends when it
     * ends, and a stray touch that made a ringing phone disappear would be the
     * worst bug in this file.
     */
    private fun handleCallTap() {
        val call = Feed.call ?: return
        val intent = when (downHit) {
            IslandView.HIT_ANSWER -> call.answer
            IslandView.HIT_DECLINE -> call.decline
            else -> null
        } ?: return
        // The dialer's own action, fired as the user. Nothing here knows how to
        // answer a call and nothing here needs to.
        runCatching { intent.send() }
            .onFailure { Log.w(TAG, "call action refused: $it") }
    }

    /** A peek is a shortcut to the app that posted it, or nothing at all. */
    private fun handleAlertTap() {
        val a = Feed.alert
        val open = a?.open
        val local = a?.onTap
        Feed.clearAlert()
        if (local != null) {
            restoreShape()
            runCatching { local() }
                .onFailure { Log.w(TAG, "alert action failed: $it") }
            return
        }
        restoreShape()
        if (open != null) {
            runCatching { open.send() }
                .onFailure { Log.w(TAG, "alert intent refused: $it") }
        }
    }

    /**
     * A tap on the open card.
     *
     * Anything that is not a control closes it. That is the reference's own
     * behaviour and it is the right one: the card is a transient thing you
     * opened, so the cheapest possible gesture has to put it away again.
     */
    private fun handlePlayerTap(island: IslandView, x: Float) {
        when (downHit) {
            IslandView.HIT_PREV -> {
                NowPlaying.previous()
                pushMedia()
            }
            IslandView.HIT_PLAY -> {
                NowPlaying.toggle()
                pushMedia()
            }
            IslandView.HIT_NEXT -> {
                NowPlaying.next()
                pushMedia()
            }
            IslandView.HIT_SEEK -> {
                val d = NowPlaying.duration
                if (d > 0) {
                    NowPlaying.seekTo((island.seekFraction(x) * d).toLong())
                    pushMedia()
                }
            }
            else -> closePlayer()
        }
    }

    private fun dp(v: Float): Int {
        val d = app?.resources?.displayMetrics?.density ?: 3f
        return (v * d).roundToInt()
    }
}
