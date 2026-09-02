package com.ishaan.essentialvoice.volume

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.ishaan.essentialvoice.R
import android.view.View
import android.view.animation.PathInterpolator

/**
 * The volume slider: the laptop's island silhouette stood on its end, with a
 * scale of dots in it and a pill lying over them.
 *
 * **The shape is not a rounded rectangle, and that is the whole point.** It is
 * attached along its long side and flares *outward* into the screen edge at both
 * ends of it, so it reads as something growing out of the border rather than a
 * pill parked next to it. A border radius only ever curves inward, so the
 * outline is a path.
 *
 * Three things about that path are load-bearing and none of them is taste:
 *
 * 1. [FLARE_F] + [TOP_F] must be 1. The concave fillet ends with a vertical
 *    tangent and the convex corner begins with one, and what lies between them
 *    is a *straight* segment `thickness - flare - top` long. `island.js`'s
 *    0.288/0.423 leaves a third of the thickness flat there, and stood on its
 *    end that flat is the top edge — which is what reads as "boxy", and no
 *    amount of rounding the corner fixes it, because the flat belongs to
 *    neither curve. At a sum of 1 both arc centres land on one line, the segment
 *    vanishes and the two arcs meet tangentially: one sweep, edge to long side.
 * 2. [STRETCH] then pulls those two arcs out *along the length*, making them
 *    elliptical rather than circular. Tangency survives it — it is a property of
 *    the thickness-direction radii — so this is the one dial that makes the ends
 *    longer and lazier without putting the flat back.
 * 3. The dots are kept clear of the curve by where the outline crosses the
 *    *centre line*, not by where the shape reaches full thickness. Those are far
 *    apart once the ends are stretched, and using the latter throws away most of
 *    a short slider's length for no reason.
 */
class VolumeSliderView(context: Context) : View(context) {

    /** One stream's worth of scale. */
    data class Col(
        val stream: Int,
        val icon: Int,
        var level: Int,
        var max: Int,
        /** The ringer is silenced, so this column says so instead of a level. */
        var vibrate: Boolean,
    ) {
    }

    /**
     * How thick the slider is drawn, in dp — the collapsed width of the window
     * and the width of one column in the expanded panel.
     *
     * A property rather than [WIDTH_DP] because it is a setting now. Everything
     * else about the shape is derived from it: the corner language, the flare at
     * the ends, and how far in from a cap the scale can start. Set it and the
     * whole drawing follows, which is why the arithmetic below never reads the
     * constant.
     */
    var thicknessDp: Float = WIDTH_DP
        set(v) {
            val next = v.coerceIn(MIN_WIDTH_DP, MAX_WIDTH_DP)
            if (field == next) return
            field = next
            invalidate()
        }

    companion object {
        /** The collapsed thickness a fresh install gets, and the width of one column. */
        const val WIDTH_DP = 38f
        const val HEIGHT_DP = 200f

        /**
         * What the thickness may be set to.
         *
         * The floor is where the icon and the chevron inside it stop being
         * legible; the ceiling is where a slider stops reading as an edge
         * control and starts reading as a panel stuck to the side of the screen.
         */
        const val MIN_WIDTH_DP = 26f
        const val MAX_WIDTH_DP = 64f

        /**
         * Headroom at each end of the *window*, outside the shape.
         *
         * The enclosure stretches when the volume hits an end of the scale, and
         * a window exactly as big as the shape in it would clip the stretched
         * end flat. This is the room it grows into.
         */
        const val ROOM_DP = 14f

        const val COL_GAP_DP = 6f

        fun expandedWidthDp(cols: Int, thicknessDp: Float = WIDTH_DP) =
            cols * thicknessDp + (cols + 1) * COL_GAP_DP

        fun windowHeightDp(lengthDp: Float) = lengthDp + 2f * ROOM_DP

        private const val FLARE_F = 0.40f
        private const val TOP_F = 0.60f

        /** Never closer together than this, however many notches a stream has. */
        private const val DOT_MIN_STEP_DP = 8f

        private const val DOT_R_DP = 2.2f

        /** What the fill swells to while a finger is on it. */
        private const val HELD_R_DP = 4.2f
        private const val HELD_MS = 140L

        private const val CHEV_H_DP = 16f
        private const val ICON_H_DP = 22f
        /**
         * Between the scale and the icon, and between the icon and the chevron.
         *
         * Generous on purpose: the chevron is a *control* sitting under a
         * readout, and a control that close to a scale reads as the bottom of
         * the scale.
         */
        private const val BAND_GAP_DP = 16f

        private const val ON = 0xFFFFFFFF.toInt()
        private const val OFF = 0xFF6B6B6B.toInt()

        /**
         * The unfilled scale, darker than [OFF].
         *
         * Separate from it on purpose. The dots and the icons were one grey, but
         * they are not one job: the dots are a ruler behind the reading and want
         * to sit back into the black, while an icon and the chevron are things
         * to be *read* and go invisible at the same value. So the ruler gets its
         * own, and only it moves.
         */
        private const val DOT_OFF = 0xFF3E3E3E.toInt()

        /** Nothing OS's own accent, and the pill's "red" — see PillStyles. */
        private const val HOT = 0xFFD71921.toInt()

        private const val FILL = 0xFF000000.toInt()

        /** The fill gliding to a new value. */
        private const val LEVEL_MS = 210L

        /** The stretch when the level lands on an end of the scale. */
        private const val IMPACT_DP = 11f
        private const val IMPACT_MS = 460L

        /** How much of that is spent going out. The rest is the settle. */
        private const val IMPACT_RISE = 0.16f

        /**
         * How close to an end of the scale counts as the fill having got there.
         *
         * The stretch waits for the pill rather than for the volume, because the
         * volume reaches the end the instant the key goes down and the pill
         * takes [LEVEL_MS] to follow. Waiting for the *animator* to end is what
         * that used to mean, and under a held key it means "after you let go":
         * every repeat cancels the glide in flight and starts a new one, so the
         * animator that would have fired the stretch never finishes until the
         * key comes up. The fill still gets to the end all the same — it just
         * gets there by asymptote — so arrival is measured on the fill itself,
         * and the last two percent of the scale is close enough to be it.
         */
        private const val ARRIVE_F = 0.02f
    }

    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FILL }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    private val path = Path()
    private val oval = RectF()
    private val matrix = Matrix()

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    var onLeft = true
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Every column that would be shown expanded. Collapsed shows [active]. */
    var columns: List<Col> = emptyList()
        set(v) { field = v; shown = FloatArray(v.size); syncShown(); invalidate() }

    var active = 0
        set(v) { if (field != v) { field = v; invalidate() } }

    var expanded = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Where each column's fill is drawn, which chases its level. */
    private var shown = FloatArray(0)
    private var chase: ValueAnimator? = null

    /**
     * Which column [chase] belongs to.
     *
     * Without it, refreshing the *other* columns cancels the animation on the
     * one that is moving — which is every readout, because a readout touches
     * them all and only the active one is animated.
     */
    private var chaseIndex = -1

    /**
     * A long, soft deceleration rather than the pill's usual snap.
     *
     * The fill is the one thing on this surface that *travels*, so it is the one
     * thing whose curve is worth spending time on: it leaves quickly, arrives
     * slowly, and never overshoots. A key press moves it one notch and that has
     * to read as a glide rather than a redraw.
     */
    private val ease = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

    /** A finger is on the slider, so the fill is thicker while it lasts. */
    var held = false
        set(v) {
            if (field == v) return
            field = v
            heldAnim?.cancel()
            heldAnim = ValueAnimator.ofFloat(heldF, if (v) 1f else 0f).apply {
                duration = HELD_MS
                addUpdateListener {
                    heldF = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
    private var heldF = 0f
    private var heldAnim: ValueAnimator? = null

    private var impactTop = true
    private var impactT = 1f
    private var impactAnim: ValueAnimator? = null

    private fun syncShown() {
        for (i in columns.indices) shown[i] = frac(columns[i])
    }

    private fun frac(c: Col): Float =
        if (c.max <= 0) 0f else (c.level.toFloat() / c.max).coerceIn(0f, 1f)

    /**
     * Move one column's fill to where its level now says.
     *
     * [animate] is false for a drag and for the first draw. A finger is already
     * a smooth input, and easing towards where it was 200ms ago is exactly what
     * makes a drag feel a frame behind the fingertip.
     */
    fun refresh(index: Int, animate: Boolean) {
        if (index !in columns.indices) return
        val target = frac(columns[index])
        if (!animate) {
            if (chaseIndex == index) chase?.cancel()
            shown[index] = target
            invalidate()
            return
        }
        val from = shown[index]
        if (from == target) return
        chase?.cancel()
        chaseIndex = index
        chase = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = LEVEL_MS
            interpolator = ease
            addUpdateListener {
                val f = it.animatedValue as Float
                shown[index] = from + (target - from) * f
                // The stretch is owed to the *fill* arriving, and the fill has
                // arrived once it is against the end — not when this particular
                // animator happens to finish, which under a held key it never
                // does. See [ARRIVE_F].
                pendingImpact?.let { top ->
                    if (index == active && arrived(shown[index], top)) impact(top)
                }
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                // onAnimationEnd runs for a cancelled animator too, so without
                // the flag a second press part-way through the first one's glide
                // would fire the stretch early — the exact thing deferring it
                // was meant to stop.
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled) pendingImpact?.let { impact(it) }
                }
            })
            start()
        }
    }

    /** Redraw everything from the columns as they stand, with no animation. */
    fun refreshAll() {
        chase?.cancel()
        chaseIndex = -1
        syncShown()
        invalidate()
    }

    /**
     * Stretch the enclosure off one end of the scale.
     *
     * Not a bounce, and not on the fill. The *shape* is what reacts — it runs on
     * past the end and eases back, once, with no swing through its rest length:
     * a bounce is something hitting a wall, and this is meant to read as the
     * thing itself refusing to go further.
     */
    /**
     * Ask for the stretch, to happen when the fill has finished arriving.
     *
     * The volume reaches the end of the scale the instant the key is pressed,
     * but the *pill* takes [LEVEL_MS] to get there — so firing the stretch on
     * the volume made the enclosure recoil before anything had touched its tip.
     * Deferred to the end of the glide, unless nothing is gliding.
     */
    fun requestImpact(fromTop: Boolean) {
        val f = shown.getOrNull(active)
        if (chase?.isRunning == true && f != null && !arrived(f, fromTop)) {
            pendingImpact = fromTop
            return
        }
        impact(fromTop)
    }

    private var pendingImpact: Boolean? = null

    fun impact(fromTop: Boolean) {
        pendingImpact = null
        impactAnim?.cancel()
        impactTop = fromTop
        impactAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = IMPACT_MS
            interpolator = null
            addUpdateListener {
                impactT = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Whether the fill is against the end [top] names. */
    private fun arrived(f: Float, top: Boolean): Boolean =
        if (top) f >= 1f - ARRIVE_F else f <= ARRIVE_F

    /** Out fast, back slow, never past the rest length. */
    private fun stretchAt(t: Float): Float {
        if (t >= 1f) return 0f
        if (t < IMPACT_RISE) return t / IMPACT_RISE
        val back = (t - IMPACT_RISE) / (1f - IMPACT_RISE)
        val inv = 1f - back
        return inv * inv * inv
    }

    fun stopAnimating() {
        chase?.cancel()
        chase = null
        impactAnim?.cancel()
        impactAnim = null
        impactT = 1f
        heldAnim?.cancel()
        heldAnim = null
        pendingImpact = null
    }

    override fun onDetachedFromWindow() {
        stopAnimating()
        super.onDetachedFromWindow()
    }

    // ---- geometry -----------------------------------------------------------

    private fun shapeTop() = dp(ROOM_DP)
    private fun shapeBottom() = height - dp(ROOM_DP)

    /**
     * How far in from an end the scale has to start: the point at which the
     * shape has reached its full thickness, which with tangent arcs is exactly
     * the thickness itself.
     */
    private fun capPad(): Float = dp(thicknessDp)

    private fun colCount() = if (expanded) columns.size.coerceAtLeast(1) else 1

    private fun colWidth(): Float {
        val n = colCount()
        if (n <= 1) return width.toFloat()
        val gap = dp(COL_GAP_DP)
        return ((width - gap * (n + 1)) / n).coerceAtLeast(dp(6f))
    }

    private fun colCentre(i: Int): Float {
        if (!expanded || columns.size <= 1) return width / 2f
        val gap = dp(COL_GAP_DP)
        val cw = colWidth()
        return gap + i * (cw + gap) + cw / 2f
    }

    /** The band each part of a column lives in. */
    private fun scaleTop() = shapeTop() + capPad()

    private fun showsIcon(i: Int) = expanded

    private fun scaleBottom(i: Int): Float {
        var b = shapeBottom() - capPad() - dp(CHEV_H_DP) - dp(BAND_GAP_DP)
        if (showsIcon(i)) b -= dp(ICON_H_DP) + dp(BAND_GAP_DP)
        return b
    }

    /**
     * How many dots to draw on a scale [span] long.
     *
     * Capped by the stream's notches — there is nothing below one dot per notch
     * worth showing — and by how close together dots may sit, which is what
     * makes a short slider draw a coarser scale instead of a smear. The fill is
     * a continuous pill, so the dots no longer have to be able to represent the
     * level on their own; they are the ruler, not the reading.
     */
    private fun dotsFor(span: Float, notches: Int): Int {
        val room = (span / dp(DOT_MIN_STEP_DP)).toInt() + 1
        return minOf(notches.coerceAtLeast(2), room).coerceAtLeast(2)
    }

    // ---- hit testing --------------------------------------------------------

    /** Which column is under [x], or -1. */
    fun columnAt(x: Float): Int {
        if (!expanded) return active
        val cw = colWidth()
        val gap = dp(COL_GAP_DP)
        for (i in columns.indices) {
            val c = colCentre(i)
            if (x >= c - cw / 2f - gap / 2f && x <= c + cw / 2f + gap / 2f) return i
        }
        return -1
    }

    /** Whether [y] is in the chevron's band — the whole width of it. */
    fun isChevron(y: Float): Boolean {
        val b = shapeBottom() - capPad()
        return y >= b - dp(CHEV_H_DP) - dp(BAND_GAP_DP)
    }

    /** Which notch of column [i] a finger at [y] is asking for, or -1. */
    fun notchAt(y: Float, i: Int): Int {
        val c = columns.getOrNull(i) ?: return -1
        val top = scaleTop()
        val bottom = scaleBottom(i)
        val span = bottom - top
        if (span <= 0f) return -1
        val f = ((bottom - y) / span).coerceIn(0f, 1f)
        return Math.round(f * c.max)
    }

    // ---- drawing ------------------------------------------------------------

    private fun buildPath(w: Float, top: Float, bottom: Float) {
        val len = bottom - top
        val base = dp(thicknessDp)
        // The corner language is the collapsed capsule's, whatever the panel
        // widens to: radii that grew with the panel would give a 180dp-wide
        // sheet 70dp corners, which is a different object.
        val thick = minOf(w, base)
        val flare = FLARE_F * thick
        val tp = minOf(TOP_F * thick, w - flare, len / 2f).coerceAtLeast(0f)
        path.reset()
        // Lying down: length along x, thickness down y, attached at y = w. The
        // far side is y = 0 and the corner arcs are centred on y = tp, which is
        // the one thing to get right — put their centre anywhere else and the
        // arc leaves the shape and a straight run appears where the two curves
        // were supposed to meet.
        //
        // The straight run along the long side, `w - flare - tp`, is zero
        // exactly when FLARE_F + TOP_F is 1 and the thickness is the collapsed
        // one. The expanded panel is wider than its radii and *should* have long
        // straight sides; that is the same expression, doing the right thing.
        path.moveTo(0f, w)
        oval.set(-flare, w - 2f * flare, flare, w)
        path.arcTo(oval, 90f, -90f)
        path.lineTo(flare, tp)
        oval.set(flare, 0f, flare + 2f * tp, 2f * tp)
        path.arcTo(oval, 180f, 90f)
        path.lineTo(len - flare - tp, 0f)
        oval.set(len - flare - 2f * tp, 0f, len - flare, 2f * tp)
        path.arcTo(oval, 270f, 90f)
        path.lineTo(len - flare, w - flare)
        oval.set(len - flare, w - 2f * flare, len + flare, w)
        path.arcTo(oval, 180f, -90f)
        path.close()

        matrix.setValues(
            if (onLeft) {
                floatArrayOf(0f, -1f, w, 1f, 0f, top, 0f, 0f, 1f)
            } else {
                floatArrayOf(0f, 1f, 0f, 1f, 0f, top, 0f, 0f, 1f)
            },
        )
        path.transform(matrix)
    }

    private val icons = HashMap<Int, Drawable?>()

    private fun icon(id: Int): Drawable? = icons.getOrPut(id) {
        ContextCompat.getDrawable(context, id)?.mutate()
    }

    /**
     * One icon, tinted and centred.
     *
     * [rotate] is degrees clockwise, which only the chevron uses: it is drawn
     * pointing right and turned to point wherever the panel will open.
     */
    private fun drawIcon(
        canvas: Canvas,
        id: Int,
        cx: Float,
        cy: Float,
        size: Float,
        colour: Int,
        rotate: Float = 0f,
    ) {
        val dr = icon(id) ?: return
        val half = (size / 2f).toInt()
        dr.setBounds(-half, -half, half, half)
        dr.setTint(colour)
        canvas.save()
        canvas.translate(cx, cy)
        if (rotate != 0f) canvas.rotate(rotate)
        dr.draw(canvas)
        canvas.restore()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        if (w <= 0f || height <= 0) return

        // The enclosure, stretched off whichever end was hit.
        val kick = dp(IMPACT_DP) * stretchAt(impactT)
        val sTop = shapeTop() - if (impactTop) kick else 0f
        val sBottom = shapeBottom() + if (!impactTop) kick else 0f
        buildPath(w, sTop, sBottom)
        canvas.drawPath(path, body)

        if (columns.isEmpty()) return

        // The fill swells under a finger. The grey scale does not: the dots are
        // the ruler and a ruler that changes size while being read is no ruler.
        val r = dp(DOT_R_DP) + (dp(HELD_R_DP) - dp(DOT_R_DP)) * heldF
        val dotR = dp(DOT_R_DP)
        val indices = if (expanded) columns.indices else (active..active)

        for (i in indices) {
            val c = columns.getOrNull(i) ?: continue
            val cx = colCentre(i)
            val top = scaleTop()
            val bottom = scaleBottom(i)
            val span = bottom - top
            if (span <= 0f) continue

            if (c.vibrate) {
                // A silenced ringer has no level to report. Only the icon says
                // so — a stub of pill next to it would be a reading, and there
                // is no reading: the stream is off, not quiet.
                drawIcon(
                    canvas,
                    R.drawable.ic_vol_vibrate,
                    cx,
                    (top + bottom) / 2f,
                    dp(ICON_H_DP) * 1.15f,
                    HOT,
                )
            } else {
                val count = dotsFor(span, c.max)
                val gap = span / (count - 1).toFloat()
                dot.color = DOT_OFF
                for (k in 0 until count) canvas.drawCircle(cx, bottom - gap * k, dotR, dot)

                val f = shown.getOrNull(i) ?: 0f
                if (f > 0f) {
                    dot.color = if (c.max > 0 && c.level >= c.max) HOT else ON
                    val topY = bottom - span * f
                    oval.set(cx - r, topY - r, cx + r, bottom + r)
                    canvas.drawRoundRect(oval, r, r, dot)
                }
            }

            if (showsIcon(i)) {
                // The band always names the *stream*. That a silenced one is
                // silenced is said in the middle of its scale, where the reading
                // would have been — saying it twice names neither.
                val id = c.icon
                val iconY = bottom + dp(BAND_GAP_DP) + dp(ICON_H_DP) / 2f
                drawIcon(canvas, id, cx, iconY, dp(ICON_H_DP), if (c.vibrate) HOT else OFF)
            }
        }

        // The chevron, centred on the whole enclosure rather than on a column:
        // it belongs to the panel, not to any one stream. It points *inward*
        // when closed — the direction the panel will grow — and back at the
        // border when open, which on the right-hand edge is the mirror of the
        // left and never simply "right".
        val inward = if (onLeft) 0f else 180f
        val chevY = shapeBottom() - capPad() - dp(CHEV_H_DP) / 2f
        drawIcon(
            canvas,
            R.drawable.ic_vol_chevron,
            width / 2f,
            chevY,
            dp(CHEV_H_DP) * 1.5f,
            OFF,
            rotate = if (expanded) inward + 180f else inward,
        )
    }
}
