package com.ishaan.essentialvoice.voice

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import com.ishaan.essentialvoice.Prefs

/**
 * The bottom bar: what a *toggled* dictation looks like.
 *
 * Holding the Essential Key needs nothing on screen but a pill, because the
 * finger on the key is the interface — let go and it stops. Every other way into
 * this app is a toggle, and a toggle without a visible way out is a microphone
 * you cannot be sure you closed. So: holding the home bar, or two knocks on the
 * back, put a wide lozenge over the gesture handle with a stop control in it.
 *
 * **Hosted by the accessibility service**, exactly like [com.ishaan.essentialvoice.island.Island],
 * and as a TYPE_ACCESSIBILITY_OVERLAY for the same reason the island is one:
 * that layer (311000) is above the status bar and the navigation bar, and an
 * ordinary TYPE_APPLICATION_OVERLAY (111000) sits *under* the system bars, which
 * take the touches across their whole row. A bar drawn over the gesture handle
 * as an app overlay would render perfectly and be completely dead.
 *
 * Sitting over the gesture handle does not break it. SystemUI watches the
 * navigation gestures through a spy window, which is handed touches whatever is
 * layered above it, so swiping up still goes home while the bar is on screen.
 * Only a *tap* — which the handle does not use — is this bar's.
 *
 * The window is no taller than the lozenge, so the rest of the screen belongs to
 * the app underneath, and it only exists while a dictation is actually running.
 */
object Bar {

    private const val TAG = "EVBar"

    /** How long the bar stays up after the transcript lands, before sliding out. */
    private const val LINGER_MS = 220L

    private val ENTER: Interpolator = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    private val EXIT: Interpolator = PathInterpolator(0.5f, 0f, 0.9f, 0.2f)

    private val main = Handler(Looper.getMainLooper())

    private var app: Context? = null

    /**
     * The accessibility service itself. Not interchangeable with [app]: a
     * TYPE_ACCESSIBILITY_OVERLAY may only be added through the WindowManager
     * belonging to a bound accessibility service; the application context's one
     * throws.
     */
    private var svc: Context? = null
    private var wm: WindowManager? = null
    private var prefs: Prefs? = null

    private var view: BarView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var attached = false
    private var slide: ValueAnimator? = null

    /** How far the keyboard has pushed the bar up. */
    /** See [ImeTop]: the keyboard's top on screen, not this window's inset. */
    private val imeTop = ImeTop()

    /**
     * Whether the dictation now running belongs to this bar.
     *
     * Set by [claim] before the dictation is started, never inferred afterwards.
     * A held key and a toggled dictation look identical from inside
     * [Dictation] — busy and capturing, either way — so the only honest way to
     * know which surface should be on screen is for the thing that started it to
     * say so.
     */
    private var claimed = false

    /**
     * Whether the next appearance should skip the slide in from below.
     *
     * For [HomeSwipe], and only for it. That gesture has already drawn this
     * shape, at this size, in this place, under the user's fingers — so the bar
     * sliding up from off screen would mean the lozenge leaving and coming back
     * for no reason. Every other way in has nothing on screen beforehand and
     * wants the slide.
     */
    private var enterInstantly = false

    /**
     * Whether the dictation now running has a bar to stop it with.
     *
     * Read by [Dictation.begin] so that it can refuse to start a dictation that
     * nothing on screen can end — see the note there.
     */
    val hasClaim: Boolean get() = claimed

    /** Called by the accessibility service once the system has bound it. */
    fun attach(context: Context) {
        val c = context.applicationContext
        app = c
        svc = context
        wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = Prefs.get(c)

        Dictation.watch(
            TAG,
            onActivity = { busy, listening ->
                if (!claimed) return@watch
                if (!busy) {
                    hide()
                } else {
                    show()
                    view?.setState(
                        if (listening) BarView.State.LISTENING else BarView.State.THINKING,
                    )
                }
            },
            onLevel = { level -> if (claimed) view?.pushLevel(level) },
        )
    }

    fun detach() {
        Dictation.unwatch(TAG)
        slide?.cancel()
        remove()
        claimed = false
        enterInstantly = false
        app = null
        svc = null
        wm = null
        prefs = null
    }

    /**
     * Take the next dictation.
     *
     * Called *before* starting one, by whatever is about to toggle it. Also
     * suppresses the pill: two surfaces narrating the same sentence in two
     * different corners of the screen is noise, and the bar is the one with the
     * stop control on it.
     *
     * Does nothing when the bar is switched off, so callers do not each have to
     * check the setting — they simply say what they are and let the setting
     * decide whether it is drawn.
     */
    fun claim(instant: Boolean = false) {
        if (prefs?.now?.bottomBar != true) return
        claimed = true
        enterInstantly = instant
        Dictation.suppressPill = true
    }

    /** Whether the bar is currently on screen. */

    // ---- the window --------------------------------------------------------

    private fun dp(v: Float): Float {
        val d = app?.resources?.displayMetrics?.density ?: 3f
        return v * d
    }

    private fun screen(): Pair<Int, Int> {
        val b = wm?.maximumWindowMetrics?.bounds ?: return 1080 to 2400
        return b.width() to b.height()
    }

    /**
     * Where the bar rests: full width less a margin, sitting on the bottom —
     * or on the keyboard, when there is one.
     */
    private fun restingY(height: Int): Int {
        val (_, sh) = screen()
        val floor = if (imeTop.y > 0) imeTop.y else sh
        return floor - height - dp(BarView.BOTTOM_GAP_DP).toInt()
    }

    private fun show() {
        if (attached) return
        val ctx = svc ?: return
        val wm = wm ?: return
        val s = prefs?.now ?: return

        val v = BarView(ctx)
        v.setStyle(s.pill)
        v.reset(BarView.State.LISTENING)
        v.contentDescription = "Essential Voice — listening. Tap to stop."
        // Through performClick rather than straight out of a touch listener, so
        // the bar answers TalkBack's activation the same way it answers a finger.
        v.setOnClickListener {
            // Only while there is something to stop. Between the stop and the
            // transcript the bar is still up, and a tap then would land on
            // Dictation.end()'s early return and look like the bar had died.
            if (Dictation.isListening) Dictation.end()
        }

        val (sw, _) = screen()
        val margin = dp(BarView.SIDE_MARGIN_DP).toInt()
        val w = (sw - margin * 2).coerceAtLeast(dp(160f).toInt())
        val h = dp(BarView.BAR_H_DP).toInt()

        val params = WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = margin
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        applyBlur(v, params, s.pill)

        // Starts fully below the screen and rises into place. Moving the window
        // rather than the view, because a view cannot paint outside its own
        // surface — the same reason the pill slides in the way it does.
        val (_, sh) = screen()
        // Off the bottom, ready to rise — unless something else has already
        // drawn this shape at rest under the user's fingers, in which case the
        // bar is added there and the rise has already happened.
        params.y = if (enterInstantly) restingY(h) else sh
        imeTop.clear()

        // The keyboard does not resize an overlay window, so the bar is lifted
        // by hand, by exactly the height the IME took. Without this it would sit
        // on top of the keyboard's bottom row for the whole of a dictation into
        // a text field, which is most of them.
        v.setOnApplyWindowInsetsListener { view, insets ->
            if (imeTop.update(insets, view)) liftForIme()
            insets
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
        if (!enterInstantly) slideTo(restingY(h), 340, ENTER, null)
        enterInstantly = false
    }

    private fun hide() {
        if (!attached) {
            claimed = false
            return
        }
        // A beat before it goes. The transcript has just landed in the field
        // underneath, and a bar that vanishes on the same frame reads as a
        // glitch rather than as an ending.
        main.postDelayed({
            if (!attached) return@postDelayed
            val (_, sh) = screen()
            slideTo(sh, 260, EXIT) {
                remove()
                claimed = false
            }
        }, LINGER_MS)
    }

    private fun remove() {
        val v = view
        if (attached && v != null) runCatching { wm?.removeViewImmediate(v) }
        attached = false
        view = null
        lp = null
        imeTop.clear()
    }

    /**
     * The frosted style is a window property, not something the view can paint:
     * the window asks the compositor to blur what is behind it and the style's
     * colour is a scrim over the result. Blur is a privilege — the system drops
     * it under battery saver and on hardware that cannot afford it — so the
     * scrim has to stand on its own when the answer is no.
     */
    private fun applyBlur(v: BarView, params: WindowManager.LayoutParams, style: PillStyle) {
        val allowed = style.blurred &&
            runCatching { wm?.isCrossWindowBlurEnabled == true }.getOrDefault(false)
        if (allowed) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            params.blurBehindRadius = (56 * (app?.resources?.displayMetrics?.density ?: 3f) / 3f).toInt()
        } else {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
            params.blurBehindRadius = 0
        }
        v.setStyle(style)
    }

    private fun liftForIme() {
        val v = view ?: return
        val params = lp ?: return
        if (!attached) return
        // Only when the bar is at rest. Moving it mid-slide would fight the
        // animator for the same field and land somewhere neither of them meant.
        if (slide?.isRunning == true) return
        val y = restingY(params.height)
        if (params.y == y) return
        params.y = y
        runCatching { wm?.updateViewLayout(v, params) }
    }

    /** A rotation changes where the bottom of the screen is. */
    fun reposition() {
        val v = view ?: return
        val params = lp ?: return
        if (!attached) return
        val (sw, _) = screen()
        val margin = dp(BarView.SIDE_MARGIN_DP).toInt()
        params.x = margin
        params.width = (sw - margin * 2).coerceAtLeast(dp(160f).toInt())
        params.y = restingY(params.height)
        runCatching { wm?.updateViewLayout(v, params) }
    }

    private fun slideTo(y: Int, ms: Long, interpolator: Interpolator, then: (() -> Unit)?) {
        val v = view ?: return
        val params = lp ?: return
        slide?.cancel()
        slide = ValueAnimator.ofInt(params.y, y).apply {
            duration = ms
            this.interpolator = interpolator
            addUpdateListener {
                params.y = it.animatedValue as Int
                if (attached) runCatching { wm?.updateViewLayout(v, params) }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { then?.invoke() }
            })
            start()
        }
    }

    /** Keep the bar in step with a colour change made while it is on screen. */
    fun apply(style: PillStyle) {
        view?.setStyle(style)
    }
}
