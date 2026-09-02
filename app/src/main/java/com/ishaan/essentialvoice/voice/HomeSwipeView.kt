package com.ishaan.essentialvoice.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/**
 * The lozenge that grows out of the home bar while two fingers drag up.
 *
 * The whole gesture is one shape changing size. At rest it is the gesture
 * handle's own geometry — 108dp by 5dp, 8dp off the bottom — so the first thing
 * that happens is that the handle appears to turn the pill's colour under the
 * fingers. By the time the drag is complete it is exactly [BarView]'s lozenge,
 * in exactly the place [Bar] rests, which is what makes the hand-off invisible:
 * this view is removed and the real bar is added in the same position and at the
 * same size, so nothing moves.
 *
 * Drawn rather than composed, like [BarView] and [PillView], because it repaints
 * on every touch move on top of whatever app is in focus.
 *
 * The glass style is drawn as a flat scrim here rather than as a blurred window.
 * A blur is a compositor pass over everything behind the window, and this window
 * changes size on every frame of a drag; the two together are a real cost for
 * something on screen for a third of a second. The bar it hands over to blurs
 * properly.
 */
class HomeSwipeView(context: Context) : View(context) {

    companion object {
        /** The system's gesture handle, which is what the shape starts as. */
        private const val HANDLE_W_DP = 108f
        private const val HANDLE_H_DP = 5f

        /** How far down the fade-in is finished. */
        private const val FADE_TO = 0.18f

        /** Where the waveform starts appearing inside the growing lozenge. */
        private const val DOTS_FROM = 0.5f

        /** The waveform, matching [BarView] so that the hand-off lines up. */
        private const val BAR_STEP_DP = 8.5f
        private const val BAR_W_DP = 4.0f
        private const val BAR_H_DP = 4.0f
        private const val DOTS_PAD_DP = 26f
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var style: PillStyle = PillStyles.byId(PillStyles.DEFAULT_ID)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val rect = RectF()
    private val bar = RectF()

    /** 0 at the handle, 1 at the bar. */
    private var progress = 0f

    /** How far the fingers have carried it past the commit point, in pixels. */
    private var lift = 0f

    init { paintStyle() }

    fun setStyle(next: PillStyle) {
        if (next == style) return
        style = next
        paintStyle()
        invalidate()
    }

    private fun paintStyle() {
        fillPaint.color = style.fill
        inkPaint.color = style.ink
    }

    fun setDrag(progress: Float, lift: Float) {
        val p = progress.coerceIn(0f, 1f)
        if (p == this.progress && lift == this.lift) return
        this.progress = p
        this.lift = lift
        invalidate()
    }

    /**
     * Where the lozenge is right now, in this view's coordinates: left, top,
     * width, height.
     *
     * At `progress == 1` this is deliberately, exactly [Bar]'s resting geometry
     * — the same side margin, the same height, the same 8dp off the bottom —
     * because that is what makes the hand-off invisible. If either end of that
     * pair is ever changed, both have to move together.
     */
    private fun geometry(): FloatArray {
        val e = progress * progress * (3f - 2f * progress)
        val fullW = (width - dp(BarView.SIDE_MARGIN_DP) * 2f).coerceAtLeast(dp(160f))
        val w = dp(HANDLE_W_DP) + (fullW - dp(HANDLE_W_DP)) * e
        val h = dp(HANDLE_H_DP) + (dp(BarView.BAR_H_DP) - dp(HANDLE_H_DP)) * e
        val bottom = height - dp(BarView.BOTTOM_GAP_DP) - lift
        return floatArrayOf((width - w) / 2f, bottom - h, w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val a = min(1f, progress / FADE_TO)
        if (a <= 0f) return

        val g = geometry()
        rect.set(g[0], g[1], g[0] + g[2], g[1] + g[3])
        val r = g[3] / 2f

        fillPaint.alpha = (android.graphics.Color.alpha(style.fill) * a).toInt()
        canvas.drawRoundRect(rect, r, r, fillPaint)
        fillPaint.alpha = android.graphics.Color.alpha(style.fill)

        // The row of dots the bar wears, arriving late so that the shape reads
        // as the handle for the first half of the drag and as the bar for the
        // second. Flat rather than moving: nothing is being heard yet, and a
        // waveform that dances before the microphone is open is a lie.
        if (progress <= DOTS_FROM) return
        val dotsAlpha = (progress - DOTS_FROM) / (1f - DOTS_FROM)
        inkPaint.alpha = (255 * dotsAlpha).toInt()

        val step = dp(BAR_STEP_DP)
        val inner = g[2] - dp(DOTS_PAD_DP) * 2f
        val n = (inner / step).toInt()
        if (n > 0) {
            val cy = g[1] + g[3] / 2f
            val bw = dp(BAR_W_DP)
            val bh = dp(BAR_H_DP)
            val left = g[0] + (g[2] - (n - 1) * step) / 2f
            for (i in 0 until n) {
                val x = left + i * step
                bar.set(x - bw / 2f, cy - bh / 2f, x + bw / 2f, cy + bh / 2f)
                canvas.drawRoundRect(bar, bw / 2f, bw / 2f, inkPaint)
            }
        }
        inkPaint.alpha = 255
    }
}
