package com.ishaan.essentialvoice.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.min

/**
 * The bar that sits over the gesture handle while a toggled dictation runs.
 *
 * The pill is for the key: it is small, it lives wherever it was placed, and it
 * is on screen for exactly as long as a finger is on the Essential Key. A
 * toggled dictation — from the home bar, from two knocks on the back — has no
 * finger holding it open, so it needs two things the pill does not have to
 * offer: somewhere obvious to look, and a way to stop.
 *
 * So it is wide, it is at the bottom, and the whole thing is the stop control.
 * The round glyph on the right says where the action is; making only that disc
 * tappable would be a 44dp target on a bar you are trying to hit without
 * looking, and there is nothing else this bar could mean.
 *
 * Drawn rather than composed, like [PillView] and for the same reason: it
 * repaints on every audio buffer, on top of whatever app is in focus.
 */
class BarView(context: Context) : View(context) {

    enum class State { LISTENING, THINKING, DONE, ERROR }

    companion object {
        /** Height of the lozenge, and the margin it keeps from the screen edges. */
        const val BAR_H_DP = 62f
        const val SIDE_MARGIN_DP = 12f

        /**
         * How far the bar's bottom edge sits above the bottom of the screen.
         *
         * Small on purpose: it is meant to sit *over* the gesture handle, which
         * is what makes holding the home bar and then looking at the home bar
         * one gesture rather than two places to look. Swiping up still goes
         * home — SystemUI watches the gesture through a spy window, which sees
         * touches whatever is layered above it.
         */
        const val BOTTOM_GAP_DP = 8f

        private const val PAD_DP = 22f

        /** The stop control: a disc with a square in it. */
        private const val STOP_R_DP = 21f
        private const val STOP_SQUARE_DP = 15f
        private const val STOP_SQUARE_CORNER_DP = 3.5f

        /** The waveform. Same bar shape as the pill's dots, more of them. */
        private const val BAR_STEP_DP = 8.5f
        private const val BAR_W_DP = 4.0f
        private const val BAR_MIN_H_DP = 4.0f
        private const val BAR_MAX_H_DP = 30f
        private const val MAX_BARS = 32

        /** Gap between the label and the waveform, and between wave and stop. */
        private const val GAP_DP = 18f

        /**
         * How long the wave keeps moving with nothing to draw.
         *
         * The same guard the card has: this bar can be left up by a dictation
         * that is waiting on a slow decode, and an indefinite animator is a
         * phone that never stops doing display work.
         */
        private const val IDLE_WAVE_MS = 30_000L
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var style: PillStyle = PillStyles.byId(PillStyles.DEFAULT_ID)
    private var styleSunk: Int = PillStyles.sunk(style)

    // Colours are set in [paintStyle] rather than in these initialisers.
    // `apply { color = style.fill }` does not do what it reads like: inside the
    // block `this` is the Paint, and Paint has a `style` of its own, so the name
    // resolves to that and never to the field above.
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        letterSpacing = 0.09f
        textSize = dp(12f)
        typeface = runCatching {
            androidx.core.content.res.ResourcesCompat.getFont(
                context, com.ishaan.essentialvoice.R.font.geist_mono_medium,
            )
        }.getOrNull() ?: Typeface.MONOSPACE
    }

    init { paintStyle() }

    private val rect = RectF()
    private val bar = RectF()
    private val square = RectF()

    /** True when the window behind this is being blurred rather than filled. */
    val isBlurred: Boolean get() = style.blurred

    fun setStyle(next: PillStyle) {
        if (next == style) return
        style = next
        styleSunk = PillStyles.sunk(next)
        paintStyle()
        invalidate()
    }

    private fun paintStyle() {
        fillPaint.color = style.fill
        inkPaint.color = style.ink
        discPaint.color = styleSunk
        labelPaint.color = style.ink
    }

    var state: State = State.LISTENING
        private set

    private val levels = FloatArray(MAX_BARS)
    private var phase = 0f
    private var phaseAnim: ValueAnimator? = null
    private var waveUntil = 0L
    private val waving: Boolean get() = android.os.SystemClock.uptimeMillis() < waveUntil

    fun setState(next: State) {
        if (next == state) return
        state = next
        if (next == State.THINKING) startPhase() else stopPhase()
        if (next != State.LISTENING) java.util.Arrays.fill(levels, 0f)
        invalidate()
    }

    fun reset(next: State) {
        java.util.Arrays.fill(levels, 0f)
        state = next
        if (next == State.THINKING) startPhase() else stopPhase()
        invalidate()
    }

    /** One level, already on 0..1. [PillView.pushLevel] explains why. */
    fun pushLevel(level: Float) {
        val shaped = min(1f, kotlin.math.max(0f, level))
        post {
            System.arraycopy(levels, 1, levels, 0, MAX_BARS - 1)
            levels[MAX_BARS - 1] = shaped
            invalidate()
        }
    }

    private fun startPhase() {
        waveUntil = android.os.SystemClock.uptimeMillis() + IDLE_WAVE_MS
        if (phaseAnim?.isRunning == true) return
        phaseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                if (!waving) { stopPhase(); return@addUpdateListener }
                invalidate()
            }
            start()
        }
    }

    private fun stopPhase() {
        phaseAnim?.cancel()
        phaseAnim = null
    }

    override fun onDetachedFromWindow() {
        stopPhase()
        super.onDetachedFromWindow()
    }

    // ---- drawing -----------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        rect.set(0f, 0f, w, h)
        val r = h / 2f
        canvas.drawRoundRect(rect, r, r, fillPaint)

        val cy = h / 2f
        val pad = dp(PAD_DP)

        val label = labelFor(state)
        val labelW = if (label.isEmpty()) 0f else labelPaint.measureText(label)
        if (label.isNotEmpty()) {
            val fm = labelPaint.fontMetrics
            canvas.drawText(label, pad, cy - (fm.ascent + fm.descent) / 2f, labelPaint)
        }

        // The stop control, on the right. Faded out once there is nothing left
        // to stop — it stays drawn so the bar does not change shape underneath a
        // finger already on its way to it.
        val stopR = dp(STOP_R_DP)
        val stopCx = w - pad - stopR
        val live = state == State.LISTENING
        drawStop(canvas, stopCx, cy, stopR, if (live) 255 else 90)

        // Whatever is left between the two is the waveform.
        val waveL = pad + labelW + (if (label.isEmpty()) 0f else dp(GAP_DP))
        val waveR = stopCx - stopR - dp(GAP_DP)
        if (waveR - waveL < dp(BAR_STEP_DP) * 3) return

        when (state) {
            State.LISTENING -> drawLevels(canvas, waveL, waveR, cy)
            State.THINKING -> drawThinking(canvas, waveL, waveR, cy)
            State.DONE, State.ERROR -> drawFlat(canvas, waveL, waveR, cy)
        }
    }

    private fun labelFor(state: State) = when (state) {
        State.LISTENING -> "LISTENING"
        State.THINKING -> "TRANSCRIBING"
        State.DONE -> "DONE"
        State.ERROR -> "STOPPED"
    }

    private fun drawStop(canvas: Canvas, cx: Float, cy: Float, r: Float, alpha: Int) {
        discPaint.alpha = alpha
        canvas.drawCircle(cx, cy, r, discPaint)
        discPaint.alpha = 255

        val s = dp(STOP_SQUARE_DP) / 2f
        val c = dp(STOP_SQUARE_CORNER_DP)
        square.set(cx - s, cy - s, cx + s, cy + s)
        inkPaint.alpha = alpha
        canvas.drawRoundRect(square, c, c, inkPaint)
        inkPaint.alpha = 255
    }

    /**
     * How many columns fit, and where they start.
     *
     * Right-aligned to [right]: the newest sample is the rightmost bar and the
     * row scrolls left, so the wave has to end at a fixed edge or the whole
     * thing shifts every time the available width changes by a pixel.
     */
    private fun columns(left: Float, right: Float): Int {
        val step = dp(BAR_STEP_DP)
        return min(MAX_BARS, ((right - left) / step).toInt().coerceAtLeast(0))
    }

    private fun columnX(i: Int, count: Int, right: Float): Float {
        val step = dp(BAR_STEP_DP)
        return right - (count - 1 - i) * step - step / 2f
    }

    private fun drawBar(canvas: Canvas, x: Float, cy: Float, level: Float) {
        val w = dp(BAR_W_DP)
        val h = dp(BAR_MIN_H_DP) + (dp(BAR_MAX_H_DP) - dp(BAR_MIN_H_DP)) * level
        bar.set(x - w / 2f, cy - h / 2f, x + w / 2f, cy + h / 2f)
        canvas.drawRoundRect(bar, w / 2f, w / 2f, inkPaint)
    }

    private fun drawLevels(canvas: Canvas, left: Float, right: Float, cy: Float) {
        val n = columns(left, right)
        // The newest sample is at the end of [levels], so the last n of them are
        // the ones on screen.
        for (i in 0 until n) {
            drawBar(canvas, columnX(i, n, right), cy, levels[MAX_BARS - n + i])
        }
    }

    /** One swell travelling along the row while whisper decodes. */
    private fun drawThinking(canvas: Canvas, left: Float, right: Float, cy: Float) {
        val n = columns(left, right)
        if (n == 0) return
        val head = phase * n
        for (i in 0 until n) {
            val d = min(abs(head - i), abs(head - i - n))
            val level = (1f - min(1f, d / 3f)).coerceAtLeast(0f) * 0.7f
            drawBar(canvas, columnX(i, n, right), cy, level)
        }
    }

    /** Settled: still a row of dots, no longer spending a frame each. */
    private fun drawFlat(canvas: Canvas, left: Float, right: Float, cy: Float) {
        val n = columns(left, right)
        for (i in 0 until n) drawBar(canvas, columnX(i, n, right), cy, 0f)
    }
}
