package com.ishaan.essentialvoice.voice

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import com.ishaan.essentialvoice.Prefs
import com.ishaan.essentialvoice.Settings
import kotlin.math.sqrt

/**
 * Two fingers up from the home bar.
 *
 * **Why this gesture and not the obvious one.** Holding the gesture handle is
 * the natural way to summon an assistant and on this phone it is permanently
 * Circle to Search's: the launcher offers that hold to Contextual Search and to
 * nothing else, and when Contextual Search declines it stops rather than falling
 * through to the assistant. That is measured, in `DEVELOPING.md`, and it is not
 * something an app can change.
 *
 * A *two-finger* swipe up from the same place was believed to be free, and it is
 * not. That is why [com.ishaan.essentialvoice.Features.HOME_SWIPE] is off, and
 * the flag carries the measurement: `NTLauncherSwipeHandlerV2` tracks two
 * pointers and its `[Gesture Monitor] swipe-up` spy pilfers ours 28-68px into
 * the drag, then finishes its own gesture into Overview. Everything below still
 * works exactly as written — it is simply never given the chance to finish.
 *
 * **What it costs, honestly.** Touches have to be caught somewhere, and the only
 * way an app catches them is a window. So there is a strip [CATCH_DP] tall along
 * the very bottom of the screen — the height of the navigation bar, and no more
 * — and while this setting is on, single-finger touches that *land* in that strip
 * go to this app instead of to the app underneath. What it does not cost is the
 * home gesture: SystemUI watches that through a spy window which is handed a
 * copy of every touch whatever is layered above it, which is the same reason
 * [Bar] can sit over the handle without breaking it. Swiping up still goes home.
 *
 * **Hosted by the accessibility service**, like [Bar] and the island, because a
 * TYPE_ACCESSIBILITY_OVERLAY may only be added through a bound accessibility
 * service's own context — and because that layer is above the navigation bar,
 * where an ordinary application overlay would render perfectly and be dead.
 *
 * The drag is followed rather than merely detected: [HomeSwipeView] grows the
 * gesture handle into the bar under the fingers, and at the end this view is
 * removed and the real [Bar] is added at exactly the same size in exactly the
 * same place, so the hand-off does not move.
 */
object HomeSwipe {

    private const val TAG = "EVHomeSwipe"

    /**
     * Write every touch this strip sees to `files/homeswipe.log`.
     *
     * Temporary, and a file rather than logcat because this ROM does not show
     * this app's own `Log` output at all — see DEVELOPING.md. Read it with
     * `adb shell run-as com.ishaan.essentialvoice cat files/homeswipe.log`.
     */
    private const val TRACE = false

    /**
     * The height of the strip that catches the touch, in dp.
     *
     * 24dp because that is exactly the navigation bar's height on this phone
     * (its window is 72px tall at 3x), so the strip covers the row the system
     * already owns and not one pixel of the app above it.
     */
    private const val CATCH_DP = 24f

    /** How tall the window that draws the growing lozenge is. */
    private const val PREVIEW_DP = 220f

    /**
     * How long after the first finger the second one may land.
     *
     * Two fingers put down together arrive a frame or two apart, not on the same
     * event. Longer than this is a finger arriving on a touch that was already
     * something else.
     */
    private const val PAIR_MS = 350L

    /** How far the pair has to travel before anything is drawn. */
    private const val SLOP_DP = 10f

    /** And how far before letting go starts a dictation. */
    private const val COMMIT_DP = 96f

    /** How far past the commit point the lozenge will follow the fingers. */
    private const val OVERSHOOT_DP = 22f

    private val SETTLE: Interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    private var app: Context? = null

    /** The service's own context; [Bar] explains why it is not interchangeable. */
    private var svc: Context? = null
    private var wm: WindowManager? = null
    private var prefs: Prefs? = null

    private var catcher: View? = null
    private var preview: HomeSwipeView? = null
    private var previewLp: WindowManager.LayoutParams? = null
    private var settle: ValueAnimator? = null

    // ---- the gesture -------------------------------------------------------

    /** Set when a second finger lands in time on a touch that started here. */
    private var tracking = false

    /** Set once the pair has passed the slop and the lozenge is on screen. */
    private var drawing = false

    private var downAt = 0L
    private var startY = 0f
    private var progress = 0f

    fun attach(context: Context) {
        app = context.applicationContext
        svc = context
        wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = Prefs.get(context.applicationContext)
    }

    fun detach() {
        settle?.cancel()
        settle = null
        removePreview()
        removeCatcher()
        app = null
        svc = null
        wm = null
        prefs = null
    }

    /** Follow the setting, and the colour. */
    fun apply(settings: Settings) {
        if (settings.homeSwipe) addCatcher() else removeCatcher()
        preview?.setStyle(settings.pill)
    }

    /** A rotation changes where the bottom of the screen is, and how wide it is. */
    fun reposition() {
        if (catcher == null) return
        removeCatcher()
        addCatcher()
    }

    // ---- the strip ---------------------------------------------------------

    private fun dp(v: Float): Float {
        val d = app?.resources?.displayMetrics?.density ?: 3f
        return v * d
    }

    private fun screen(): Pair<Int, Int> {
        val b = wm?.maximumWindowMetrics?.bounds ?: return 1080 to 2400
        return b.width() to b.height()
    }

    private fun addCatcher() {
        if (catcher != null) return
        val ctx = svc ?: return
        val wm = wm ?: return

        val v = object : View(ctx) {
            @Suppress("ClickableViewAccessibility")
            override fun onTouchEvent(event: MotionEvent): Boolean = onStripTouch(event)
        }
        // Nothing is ever drawn here — this window exists to be touched. The
        // lozenge is a second window, so that the thing catching touches can
        // stay the height of the navigation bar while the thing being drawn is
        // free to be as tall as the drag.
        v.setWillNotDraw(true)

        val (sw, sh) = screen()
        val h = dp(CATCH_DP).toInt()
        val params = WindowManager.LayoutParams(
            sw,
            h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSPARENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = sh - h
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        if (runCatching { wm.addView(v, params) }.isFailure) {
            Log.e(TAG, "could not add the catch strip")
            return
        }
        catcher = v
    }

    private fun removeCatcher() {
        val v = catcher ?: return
        runCatching { wm?.removeViewImmediate(v) }
        catcher = null
        reset()
    }

    // ---- reading the drag --------------------------------------------------

    private fun trace(line: String) {
        if (!TRACE) return
        val ctx = app ?: return
        runCatching {
            java.io.File(ctx.filesDir, "homeswipe.log").appendText(
                "${SystemClock.uptimeMillis()} $line\n",
            )
        }
    }

    private fun onStripTouch(event: MotionEvent): Boolean {
        trace("action=${event.actionMasked} n=${event.pointerCount} " +
            "y0=${event.getY(0)} tracking=$tracking drawing=$drawing")
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = SystemClock.uptimeMillis()
                tracking = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount != 2) {
                    // A third finger is not a bigger version of this gesture; it
                    // is something else happening. Give up rather than guess.
                    end(commit = false)
                    return true
                }
                if (SystemClock.uptimeMillis() - downAt > PAIR_MS) {
                    trace("  too late: ${SystemClock.uptimeMillis() - downAt}ms")
                    return true
                }
                if (!available()) {
                    trace("  unavailable: ready=${Dictation.isReady} busy=${Dictation.isBusy}")
                    return true
                }
                tracking = true
                startY = midY(event)
                progress = 0f
                trace("  tracking from $startY")
            }

            MotionEvent.ACTION_MOVE -> {
                if (!tracking || event.pointerCount != 2) return true
                val travel = startY - midY(event)
                trace("  travel=$travel slop=${dp(SLOP_DP)}")
                if (!drawing) {
                    if (travel < dp(SLOP_DP)) return true
                    // Only now is this certainly the gesture and not a two-finger
                    // rest on the bottom of the screen, so only now is anything
                    // drawn or felt.
                    drawing = true
                    addPreview()
                    tick(12)
                }
                val was = progress
                progress = ((travel - dp(SLOP_DP)) / (dp(COMMIT_DP) - dp(SLOP_DP)))
                    .coerceIn(0f, 1f)
                // Once it is committed the lozenge keeps following the fingers,
                // but slower and not far: the shape has arrived, and the give is
                // there to say the gesture has been understood rather than to
                // move it somewhere else.
                val past = (travel - dp(COMMIT_DP)).coerceAtLeast(0f)
                preview?.setDrag(progress, rubberBand(past))
                if (was < 1f && progress >= 1f) tick(18)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (tracking) end(commit = progress >= 1f)
            }

            MotionEvent.ACTION_UP -> {
                if (tracking) end(commit = progress >= 1f)
                reset()
            }

            MotionEvent.ACTION_CANCEL -> end(commit = false)
        }
        return true
    }

    /**
     * Resistance past the commit point: square-root rather than linear, so the
     * lozenge moves freely for the first few pixels and then plainly stops,
     * which is what tells a finger it has reached the end without a line being
     * drawn anywhere.
     */
    private fun rubberBand(past: Float): Float {
        val max = dp(OVERSHOOT_DP)
        return (sqrt(past / max) * max).coerceAtMost(max)
    }

    /**
     * The midpoint of the two fingers, in the strip's own coordinates.
     *
     * Which is enough, and is the reason there is no conversion to screen
     * coordinates anywhere here: every use of this is a *difference* between two
     * readings taken in the same window, so whatever offset the window has
     * cancels out. The fingers leave the strip almost immediately — the window
     * that received the touch goes on receiving it wherever it travels — and the
     * numbers simply go negative, which subtracts correctly.
     */
    private fun midY(event: MotionEvent): Float = (event.getY(0) + event.getY(1)) / 2f

    /**
     * Whether there is a dictation to start.
     *
     * Not while one is already running: it would be a second way to reach a
     * toggle that already has a stop control on screen, and dragging the bar
     * upwards to switch off the bar is not a sentence anybody reads that way.
     */
    private fun available(): Boolean = Dictation.isReady && !Dictation.isBusy

    private fun end(commit: Boolean) {
        if (!drawing) {
            reset()
            return
        }
        if (commit) {
            preview?.setDrag(1f, 0f)
            // The bar arrives without its slide, because the lozenge it is
            // replacing is already exactly where the bar rests and exactly the
            // size the bar is. Sliding in from below the screen would mean the
            // shape leaving and coming back.
            Bar.claim(instant = true)
            Dictation.toggle()
            tick(22)
            // A frame for the bar to be added, so that there is never a gap with
            // neither shape on screen.
            preview?.post { removePreview() }
            reset()
            return
        }
        settleBack()
    }

    /** Nothing was committed: the lozenge shrinks back into the handle and goes. */
    private fun settleBack() {
        val v = preview ?: run { reset(); return }
        settle?.cancel()
        settle = ValueAnimator.ofFloat(progress, 0f).apply {
            duration = 190
            interpolator = SETTLE
            addUpdateListener { v.setDrag(it.animatedValue as Float, 0f) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { removePreview() }
            })
            start()
        }
        reset()
    }

    private fun reset() {
        tracking = false
        drawing = false
        progress = 0f
    }

    private fun tick(ms: Long) {
        if (prefs?.now?.haptics != true) return
        Dictation.buzz(app ?: return, ms)
    }

    // ---- the lozenge -------------------------------------------------------

    private fun addPreview() {
        if (preview != null) return
        val ctx = svc ?: return
        val wm = wm ?: return

        val v = HomeSwipeView(ctx)
        v.setStyle(prefs?.now?.pill ?: PillStyles.byId(PillStyles.DEFAULT_ID))
        v.setDrag(0f, 0f)

        val (sw, sh) = screen()
        val h = dp(PREVIEW_DP).toInt()
        val params = WindowManager.LayoutParams(
            sw,
            h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Not touchable: the strip below already has the fingers, and a
            // second window reaching for the same touches would take them off it
            // halfway through the drag.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = sh - h
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        if (runCatching { wm.addView(v, params) }.isFailure) {
            Log.e(TAG, "could not add the lozenge")
            return
        }
        preview = v
        previewLp = params
    }

    private fun removePreview() {
        val v = preview ?: return
        runCatching { wm?.removeViewImmediate(v) }
        preview = null
        previewLp = null
    }
}
