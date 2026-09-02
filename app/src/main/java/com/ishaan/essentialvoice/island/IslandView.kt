package com.ishaan.essentialvoice.island

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Path
import android.graphics.RectF as GRectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.animation.LinearInterpolator
import com.ishaan.essentialvoice.voice.PillStyle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The island: a pitch-black lozenge that sits behind the camera, with a small
 * grey dot on its right that says the microphone is off.
 *
 * Tapping it expands the lozenge sideways and the dot becomes the five dots the
 * pill uses, dancing on the level of your voice. Tapping again ends the
 * dictation and it collapses back.
 *
 * **Why it hangs below the status bar rather than being a slim bar at the
 * camera.** The window is a TYPE_APPLICATION_OVERLAY, which is layered *below*
 * TYPE_STATUS_BAR — and the status bar does not merely paint over it, it takes
 * the touches. Straight from the input dispatcher on this phone:
 *
 *     StatusBar   frame=[0,0][1080,126]  touchableRegion=[0,0][1080,126]
 *     essentialvoice                     touchableRegion=[415,138][690,228]
 *
 * The status bar is listed above this window, so every touch with y < 126 is
 * dispatched to it and never reaches here. The camera hole is a circle at
 * (540, 68.6) r=21.5, which is entirely inside that band. An island drawn there
 * renders perfectly and cannot be tapped at all — dragging it pulls the
 * notification shade down instead. No window type above the status bar is
 * available to an ordinary app.
 *
 * So the lozenge is drawn tall enough to reach past that line: its top sits
 * behind the camera where it is wanted, and [BAND_TOP_PX] marks where it starts
 * being touchable. Everything the finger is meant to go for — the grey dot, the
 * dots while listening — is laid out in that lower band, which is the only part
 * of this view a finger can actually reach.
 */
class IslandView(context: Context) : View(context) {

    enum class State {
        /** Nothing happening. A grey dot. */
        IDLE,

        /**
         * A dictation is running, but it was not started from here — the key, the
         * assistant, the side pill. The lozenge does not move or grow; only the
         * dot changes, to the accent colour. The pill is already saying
         * everything else, and two things narrating one dictation is noise.
         */
        ARMED,

        /** Started from here: the lozenge widens and the dots do the talking. */
        LISTENING,
        WORKING,
    }

    companion object {
        /** Matched to the pill, so the two read as one family. */
        const val DOTS = 5
        private const val BAR_W_DP = 4f
        private const val BAR_STEP_DP = 9f

        /** Pitch black, fully opaque: at the camera it is pretending to be a
         *  hole in the screen, and anything the wallpaper shows through is a
         *  shape sitting on the glass instead. */
        private const val FILL = 0xFF000000.toInt()

        /** The microphone is off. Grey, because it is a resting light. */
        private const val DOT_OFF = 0xFF6E6E73.toInt()

        /* ------------------------------------------------------ the player */

        /** Laid out from the DynamicSpot capture, converted to dp at 3x. */
        const val PLAYER_W_DP = 340f
        const val PLAYER_H_DP = 196f
        private const val PLAYER_R_DP = 34f
        private const val PAD_DP = 18f
        private const val ART_DP = 62f
        private const val ART_R_DP = 14f

        /** Transport glyph half-width, and how far the outer two sit from centre. */
        private const val GLYPH_DP = 11f
        private const val GLYPH_SPREAD_DP = 76f
        private const val BAR_H_DP = 4f

        private const val INK = 0xFFFFFFFF.toInt()
        private const val INK_DIM = 0xFFB3B3B8.toInt()
        private const val TRACK = 0x38FFFFFF

        /** What [hitTest] answers. */
        const val HIT_NONE = 0
        const val HIT_PREV = 1
        const val HIT_PLAY = 2
        const val HIT_NEXT = 3
        const val HIT_SEEK = 4
        const val HIT_ART = 5
        const val HIT_ANSWER = 6
        const val HIT_DECLINE = 7
        const val HIT_ALERT = 8

        /* ------------------------------------------------- calls and alerts */

        const val CALL_W_DP = 320f
        const val CALL_H_DP = 168f
        const val ALERT_W_DP = 300f
        /**
         * The alert card's *readable* height — the part below the camera, not
         * the whole card. [Island.alertHeightPx] adds the band the lens covers
         * on top of this, so the card grows as needed and the words always get
         * the same room. It replaced a total-height constant, which was right
         * for exactly one value of the island's "From the top" setting.
         */
        const val ALERT_BODY_DP = 54f

        /** The two call buttons. */
        private const val CALL_BTN_DP = 58f
        private const val CALL_SPREAD_DP = 62f
        private const val ANSWER = 0xFF32D74B.toInt()
        private const val DECLINE = 0xFFFF453A.toInt()
    }

    /**
     * What the island is right now.
     *
     * One window, several shapes. The order they take precedence in is [Island]'s
     * business, not the view's — the view draws whichever it is told.
     */
    enum class Mode { COMPACT, PLAYER, CALL, ALERT }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val artMatrix = Matrix()
    private val rect = RectF()
    private val levels = FloatArray(DOTS)

    /**
     * The album cover, or null when nothing is playing.
     *
     * Drawn as a circle at the left end, which is the shape the reference uses
     * and the shape that reads as a *thing on* the lozenge rather than a panel
     * cut into it. A shader rather than a clipped drawBitmap: the cover is
     * square and the target is a circle, so it has to be scaled and centred
     * anyway, and a shader does both in one pass.
     */
    var art: Bitmap? = null
        set(value) {
            if (field === value) return
            field = value
            artPaint.shader = null
            artSquareShader = null
            invalidate()
        }

    var mode: Mode = Mode.COMPACT
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Pushed by [Island] off NowPlaying; the view never reads the session. */
    var mediaTitle: String? = null
    var mediaArtist: String? = null
    var mediaPlaying: Boolean = false
    var mediaPosition: Long = 0L
    var mediaDuration: Long = 0L

    fun setMedia(
        title: String?,
        artist: String?,
        playing: Boolean,
        position: Long,
        duration: Long,
    ) {
        mediaTitle = title
        mediaArtist = artist
        mediaPlaying = playing
        mediaPosition = position
        mediaDuration = duration
        invalidate()
    }

    private var artSquareShader: Shader? = null
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPath = Path()
    private val iconBounds = GRectF()

    /* ------------------------------------------------------- call and alert */

    /**
     * How far through a shape change the window currently is, 0..1.
     *
     * Driven by [Island.morphTo]. The *window* is what grows — that is the
     * geometry — but a card's text arriving at full strength on a lozenge that
     * is still half its width looks like a label that overflowed, so the
     * content is faded against the same curve. Background is never faded: the
     * lozenge and the card are one surface, and it has to stay solid the whole
     * way or the app underneath shows through mid-expansion.
     */
    var morph: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** Content alpha for the takeover shapes; see [morph]. */
    private fun contentAlpha(): Int = (255f * morph * morph).toInt().coerceIn(0, 255)


    var callWho: String? = null
    var callRinging: Boolean = false

    fun setCall(who: String?, ringing: Boolean) {
        callWho = who
        callRinging = ringing
        invalidate()
    }

    var alertApp: String? = null
    var alertTitle: String? = null
    var alertText: String? = null
    var alertIcon: Drawable? = null

    fun setAlert(app: String?, title: String?, text: String?, icon: Drawable?) {
        alertApp = app
        alertTitle = title
        alertText = text
        alertIcon = icon
        invalidate()
    }

    /**
     * The running timer, shown on the compact lozenge.
     *
     * Null when nothing is counting. It is deliberately *only* the compact
     * state: a timer is a number you glance at, and a card you have to dismiss
     * to see the screen again is the wrong shape for a glance.
     */
    var timerText: String? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val phoneIcon: Drawable? by lazy {
        runCatching {
            context.getDrawable(android.R.drawable.sym_action_call)?.mutate()
        }.getOrNull()
    }

    /**
     * Volatile because [pushLevel] reads it from the recorder's own thread while
     * [setState] writes it from the main one. Without it that thread is entitled
     * to go on seeing IDLE indefinitely, and the bars would simply never move.
     */
    @Volatile
    private var state = State.IDLE
    private var style: PillStyle? = null
    private var held = false
    private var phase = 0f
    private var chase: ValueAnimator? = null

    /**
     * Where the touchable band begins, in this view's own coordinates. Set by
     * [Island], which is the only thing that knows where the window was placed.
     */
    /**
     * The corner radius, in pixels — taken from the *collapsed* height.
     *
     * Not min(w, h) / 2, which is what this used to be: at double height that
     * makes the radius half the width and the lozenge becomes a black egg.
     * Holding the curvature the collapsed shape had is what makes the expansion
     * read as the same object stretching downward.
     */
    var cornerPx: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var bandTopPx: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    fun setStyle(s: PillStyle) {
        style = s
        invalidate()
    }

    fun setState(next: State) {
        if (state == next) return
        state = next
        if (next == State.WORKING) startChase() else stopChase()
        if (next == State.IDLE) levels.fill(0f)
        invalidate()
    }

    /** Pressed feedback is colour only — the lozenge never moves under the finger. */
    fun setHeld(down: Boolean) {
        if (held == down) return
        held = down
        invalidate()
    }

    /** One level, already on 0..1. [com.ishaan.essentialvoice.voice.PillView.pushLevel] explains why. */
    fun pushLevel(level: Float) {
        if (state != State.LISTENING) return
        val shaped = min(1f, kotlin.math.max(0f, level))
        post {
            System.arraycopy(levels, 1, levels, 0, DOTS - 1)
            levels[DOTS - 1] = shaped
            invalidate()
        }
    }

    private fun startChase() {
        stopChase()
        chase = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopChase() {
        chase?.cancel()
        chase = null
    }

    override fun onDetachedFromWindow() {
        stopChase()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val s = style ?: return
        val w = width.toFloat()
        val h = height.toFloat()

        when (mode) {
            Mode.PLAYER -> {
                drawPlayer(canvas, w, h)
                return
            }
            Mode.CALL -> {
                drawCall(canvas, w, h)
                return
            }
            Mode.ALERT -> {
                drawAlert(canvas, w, h)
                return
            }
            Mode.COMPACT -> Unit
        }

        fillPaint.color = if (held) 0xFF141416.toInt() else FILL
        rect.set(0f, 0f, w, h)
        val radius = min(if (cornerPx > 0f) cornerPx else h / 2f, min(w, h) / 2f)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        // Everything interactive lives in the band a finger can reach. Centred
        // in it rather than in the lozenge, which would put it under the status
        // bar and out of reach.
        // Centred in the lozenge, which is what looks right — but never drawn
        // where the status bar would be covering it, because a dot a finger
        // cannot reach is a button that does not work. On a lozenge that clears
        // the bar entirely these are the same number; it is only a tall one
        // reaching up behind the camera where they differ, and there the target
        // has to come down to meet the finger.
        val bandTop = bandTopPx.coerceIn(0f, h)
        val centre = h / 2f
        val cy = if (bandTop > 0f && bandTop < h - dp(10f)) {
            max(centre, bandTop + dp(9f))
        } else {
            centre
        }

        drawArt(canvas, h)

        // A running timer takes the right-hand end, where the resting dot lives:
        // it is the same slot saying the same kind of thing — what this island is
        // currently busy with — and two indicators side by side at this size is
        // just clutter.
        val counting = timerText
        if (counting != null && (state == State.IDLE || state == State.ARMED)) {
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textPaint.textSize = min(dp(13f), h * 0.42f)
            textPaint.color = INK
            val tw = textPaint.measureText(counting)
            canvas.drawText(
                counting,
                w - tw - dp(12f),
                cy + textPaint.textSize * 0.36f,
                textPaint,
            )
            return
        }

        when (state) {
            State.IDLE, State.ARMED -> {
                // The resting light, on the right — the side of the camera the
                // lozenge has spare room on. Grey when the microphone is off,
                // the accent colour when a dictation is running that this island
                // did not start.
                dotPaint.color = if (state == State.ARMED) indicator(s) else DOT_OFF
                // Small on purpose: it is a resting light, not a control of its
                // own — the whole lozenge is the button.
                val r = min(dp(3.75f), h / 5f)
                canvas.drawCircle(w - r - dp(11f), cy, r, dotPaint)
            }
            // In the bottom half, which is the half that has dropped below the
            // status bar: the top half is still behind the camera, where nothing
            // drawn can be seen properly or reached.
            State.LISTENING -> drawBars(canvas, w / 2f, h * 0.75f, indicator(s))
            State.WORKING -> drawChase(canvas, w / 2f, h * 0.75f, indicator(s))
        }
    }

    /* ---------------------------------------------------------- the player */

    /**
     * The expanded player.
     *
     * Laid out from the reference: cover top-left as a rounded square, title and
     * artist beside it, a full-width scrubber with elapsed on the left and
     * *remaining* on the right, and three large transport glyphs centred under
     * it. The glyphs are Paths rather than icons — a filled triangle at this
     * size is a handful of lineTos, and pulling in a vector drawable for it
     * would mean a theme lookup on a window that has no theme.
     */
    private fun drawPlayer(canvas: Canvas, w: Float, h: Float) {
        val r = dp(PLAYER_R_DP)
        fillPaint.color = FILL
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, fillPaint)

        val pad = dp(PAD_DP)
        val artSide = dp(ART_DP)
        drawSquareArt(canvas, pad, pad, artSide)

        // Title and artist, beside the cover, optically centred on it.
        val textX = pad + artSide + dp(14f)
        val textW = (w - pad - textX).coerceAtLeast(1f)
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textPaint.textSize = dp(19f)
        textPaint.color = INK
        val titleBase = pad + artSide / 2f - dp(3f)
        canvas.drawText(
            ellipsize(mediaTitle ?: "Nothing playing", textW),
            textX, titleBase, textPaint,
        )
        textPaint.typeface = Typeface.DEFAULT
        textPaint.textSize = dp(15f)
        textPaint.color = INK_DIM
        mediaArtist?.let {
            canvas.drawText(ellipsize(it, textW), textX, titleBase + dp(24f), textPaint)
        }

        // The scrubber. Times sit outside the bar, which is what makes the bar
        // itself the full-width object the eye follows.
        val dur = mediaDuration
        val pos = if (dur > 0) mediaPosition.coerceIn(0, dur) else 0L
        val rowY = pad + artSide + dp(30f)
        textPaint.textSize = dp(12.5f)
        textPaint.color = INK_DIM
        val left = fmt(pos)
        val right = "-" + fmt((dur - pos).coerceAtLeast(0))
        val leftW = textPaint.measureText(left)
        val rightW = textPaint.measureText(right)
        canvas.drawText(left, pad, rowY + dp(4.5f), textPaint)
        canvas.drawText(right, w - pad - rightW, rowY + dp(4.5f), textPaint)

        val barL = pad + leftW + dp(12f)
        val barR = w - pad - rightW - dp(12f)
        val barH = dp(BAR_H_DP)
        if (barR > barL) {
            dotPaint.color = TRACK
            rect.set(barL, rowY - barH / 2f, barR, rowY + barH / 2f)
            canvas.drawRoundRect(rect, barH / 2f, barH / 2f, dotPaint)
            if (dur > 0) {
                val end = barL + (barR - barL) * (pos.toFloat() / dur)
                dotPaint.color = INK
                rect.set(barL, rowY - barH / 2f, max(end, barL + barH), rowY + barH / 2f)
                canvas.drawRoundRect(rect, barH / 2f, barH / 2f, dotPaint)
            }
        }

        // Transport.
        val cy = h - pad - dp(20f)
        val cx = w / 2f
        val spread = dp(GLYPH_SPREAD_DP)
        dotPaint.color = INK
        drawSkip(canvas, cx - spread, cy, back = true)
        drawPlayPause(canvas, cx, cy)
        drawSkip(canvas, cx + spread, cy, back = false)
    }

    /**
     * The incoming call.
     *
     * Who it is, large, and two buttons far enough apart that the wrong one
     * cannot be hit — this is a card that appears without warning under a thumb
     * that was doing something else, so the targets are round, big, and a long
     * way from each other.
     */
    private fun drawCall(canvas: Canvas, w: Float, h: Float) {
        val r = dp(PLAYER_R_DP)
        fillPaint.color = FILL
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, fillPaint)

        val pad = dp(PAD_DP)
        textPaint.typeface = Typeface.DEFAULT
        textPaint.textSize = dp(13f)
        textPaint.color = INK_DIM
        canvas.drawText(
            if (callRinging) "Incoming call" else "On a call",
            pad, pad + dp(13f), textPaint,
        )

        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textPaint.textSize = dp(22f)
        textPaint.color = INK
        canvas.drawText(
            ellipsize(callWho ?: "Unknown", w - 2f * pad),
            pad, pad + dp(44f), textPaint,
        )

        val cy = h - pad - dp(CALL_BTN_DP) / 2f
        val cx = w / 2f
        val spread = dp(CALL_SPREAD_DP)
        if (callRinging) {
            drawCallButton(canvas, cx - spread, cy, DECLINE, hangUp = true)
            drawCallButton(canvas, cx + spread, cy, ANSWER, hangUp = false)
        } else {
            drawCallButton(canvas, cx, cy, DECLINE, hangUp = true)
        }
    }

    /**
     * A round call button.
     *
     * The handset is the platform's own `sym_action_call`, tinted — a phone
     * glyph is a specific, recognisable shape and hand-rolling one out of
     * Béziers to save a drawable lookup would only make it worse. Hanging up is
     * the same glyph turned 135°, which is the convention everywhere.
     */
    private fun drawCallButton(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        color: Int,
        hangUp: Boolean,
    ) {
        val d = dp(CALL_BTN_DP)
        dotPaint.color = color
        canvas.drawCircle(cx, cy, d / 2f, dotPaint)

        val icon = phoneIcon ?: return
        val g = d * 0.46f
        icon.setTint(INK)
        icon.setBounds(
            (cx - g / 2f).toInt(), (cy - g / 2f).toInt(),
            (cx + g / 2f).toInt(), (cy + g / 2f).toInt(),
        )
        canvas.save()
        if (hangUp) canvas.rotate(135f, cx, cy)
        icon.draw(canvas)
        canvas.restore()
    }

    /**
     * A notification, as a peek.
     *
     * One line of who and one of what, and no actions: this is the island
     * telling you something arrived, not a shade. Tapping it opens the app,
     * which is where anything more belongs.
     */
    private fun drawAlert(canvas: Canvas, w: Float, h: Float) {
        // Capped rather than h/2: a card this tall would otherwise be a stadium,
        // and the taller it grows the more it would round into a blob.
        val r = min(h / 2f, dp(36f))
        fillPaint.color = if (held) 0xFF141416.toInt() else FILL
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, fillPaint)

        val layer = canvas.saveLayerAlpha(0f, 0f, w, h, contentAlpha())

        // The background fills the whole card, including the part the camera
        // covers — that is what keeps the notch black and the morph continuous.
        // Everything readable lives strictly below the lens. See Island.syncBand.
        val bandTop = bandTopPx.coerceIn(0f, h)
        val ch = (h - bandTop).coerceAtLeast(1f)
        val cy = bandTop + ch / 2f

        val inset = dp(7f)
        val d = (ch - 2f * inset).coerceAtLeast(1f)
        alertIcon?.let {
            val top = bandTop + inset
            iconBounds.set(inset, top, inset + d, top + d)
            it.setBounds(
                iconBounds.left.toInt(), iconBounds.top.toInt(),
                iconBounds.right.toInt(), iconBounds.bottom.toInt(),
            )
            it.draw(canvas)
        }

        val textX = inset + d + dp(11f)
        val textW = (w - textX - dp(16f)).coerceAtLeast(1f)
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textPaint.textSize = dp(13.5f)
        textPaint.color = INK
        val head = alertTitle?.takeIf { it.isNotBlank() } ?: alertApp.orEmpty()
        canvas.drawText(ellipsize(head, textW), textX, cy - dp(2f), textPaint)

        textPaint.typeface = Typeface.DEFAULT
        textPaint.textSize = dp(12.5f)
        textPaint.color = INK_DIM
        alertText?.takeIf { it.isNotBlank() }?.let {
            canvas.drawText(ellipsize(it, textW), textX, cy + dp(16f), textPaint)
        }
        canvas.restoreToCount(layer)
    }

    /** The cover as a rounded square, for the player. */
    private fun drawSquareArt(canvas: Canvas, x: Float, y: Float, side: Float) {
        val radius = dp(ART_R_DP)
        val bmp = art
        rect.set(x, y, x + side, y + side)
        if (bmp == null || bmp.isRecycled) {
            fillPaint.color = 0xFF232326.toInt()
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
            return
        }
        if (artSquareShader == null) {
            val src = min(bmp.width, bmp.height).toFloat()
            if (src <= 0f) return
            val scale = side / src
            artMatrix.reset()
            artMatrix.setScale(scale, scale)
            artMatrix.postTranslate(
                x + side / 2f - bmp.width * scale / 2f,
                y + side / 2f - bmp.height * scale / 2f,
            )
            artSquareShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                .apply { setLocalMatrix(artMatrix) }
        }
        val saved = artPaint.shader
        artPaint.shader = artSquareShader
        canvas.drawRoundRect(rect, radius, radius, artPaint)
        artPaint.shader = saved
    }

    /** Two stacked triangles, pointing the way they skip. */
    private fun drawSkip(canvas: Canvas, cx: Float, cy: Float, back: Boolean) {
        val g = dp(GLYPH_DP)
        val dir = if (back) -1f else 1f
        for (i in 0..1) {
            val ox = cx + dir * (i * g * 0.98f - g * 0.49f)
            glyphPath.reset()
            glyphPath.moveTo(ox + dir * g * 0.5f, cy)
            glyphPath.lineTo(ox - dir * g * 0.5f, cy - g * 0.82f)
            glyphPath.lineTo(ox - dir * g * 0.5f, cy + g * 0.82f)
            glyphPath.close()
            canvas.drawPath(glyphPath, dotPaint)
        }
    }

    private fun drawPlayPause(canvas: Canvas, cx: Float, cy: Float) {
        val g = dp(GLYPH_DP) * 1.25f
        if (mediaPlaying) {
            val bw = g * 0.42f
            val gap = g * 0.30f
            rect.set(cx - gap - bw, cy - g, cx - gap, cy + g)
            canvas.drawRoundRect(rect, bw * 0.28f, bw * 0.28f, dotPaint)
            rect.set(cx + gap, cy - g, cx + gap + bw, cy + g)
            canvas.drawRoundRect(rect, bw * 0.28f, bw * 0.28f, dotPaint)
        } else {
            glyphPath.reset()
            glyphPath.moveTo(cx + g * 0.86f, cy)
            glyphPath.lineTo(cx - g * 0.62f, cy - g)
            glyphPath.lineTo(cx - g * 0.62f, cy + g)
            glyphPath.close()
            canvas.drawPath(glyphPath, dotPaint)
        }
    }

    /**
     * Which control is under a point, in this view's coordinates.
     *
     * The view owns this rather than [Island] because the view is what decided
     * where the glyphs went; a second copy of the layout in the touch handler is
     * a second thing to keep in step.
     */
    fun hitTest(x: Float, y: Float): Int {
        if (mode == Mode.ALERT) return HIT_ALERT
        if (mode == Mode.CALL) {
            val w = width.toFloat()
            val h = height.toFloat()
            val pad = dp(PAD_DP)
            val cy = h - pad - dp(CALL_BTN_DP) / 2f
            val cx = w / 2f
            val grab = dp(CALL_BTN_DP) * 0.62f
            if (y < cy - grab) return HIT_NONE
            if (!callRinging) {
                return if (kotlin.math.abs(x - cx) < grab) HIT_DECLINE else HIT_NONE
            }
            val spread = dp(CALL_SPREAD_DP)
            if (kotlin.math.abs(x - (cx - spread)) < grab) return HIT_DECLINE
            if (kotlin.math.abs(x - (cx + spread)) < grab) return HIT_ANSWER
            return HIT_NONE
        }
        if (mode == Mode.COMPACT) {
            val h = height.toFloat()
            val inset = dp(3.5f)
            val d = h - 2f * inset
            return if (art != null && x <= inset + d + dp(4f)) HIT_ART else HIT_NONE
        }
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dp(PAD_DP)
        val cy = h - pad - dp(20f)
        val cx = w / 2f
        val spread = dp(GLYPH_SPREAD_DP)
        // Generous targets: the glyphs are small and this is a window sitting
        // over other apps, so a near miss must not fall through to them.
        val grab = dp(30f)
        if (y > cy - grab && y < cy + grab) {
            if (x > cx - spread - grab && x < cx - spread + grab) return HIT_PREV
            if (x > cx - grab && x < cx + grab) return HIT_PLAY
            if (x > cx + spread - grab && x < cx + spread + grab) return HIT_NEXT
        }
        val rowY = pad + dp(ART_DP) + dp(30f)
        if (y > rowY - dp(16f) && y < rowY + dp(16f)) return HIT_SEEK
        return HIT_NONE
    }

    /** Where along the track a seek at [x] lands, 0..1. */
    fun seekFraction(x: Float): Float {
        val w = width.toFloat()
        val pad = dp(PAD_DP)
        textPaint.textSize = dp(12.5f)
        val leftW = textPaint.measureText(fmt(mediaPosition))
        val rightW = textPaint.measureText("-" + fmt(mediaDuration))
        val barL = pad + leftW + dp(12f)
        val barR = w - pad - rightW - dp(12f)
        if (barR <= barL) return 0f
        return ((x - barL) / (barR - barL)).coerceIn(0f, 1f)
    }

    private fun ellipsize(text: String, width: Float): String =
        TextUtils.ellipsize(text, textPaint, width, TextUtils.TruncateAt.END).toString()

    private fun fmt(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val m = total / 60
        val sec = total % 60
        return "%d:%02d".format(m, sec)
    }

    /**
     * The cover, inset at the left end and cut to a circle.
     *
     * The inset is the same on all four sides, so the circle is as large as the
     * lozenge's height allows and sits concentric with it — which is what makes
     * it look set into the black rather than dropped on top of it.
     */
    private fun drawArt(canvas: Canvas, h: Float) {
        val bmp = art ?: return
        if (bmp.isRecycled) return
        val inset = dp(3.5f)
        val d = h - 2f * inset
        if (d <= 0f) return
        val r = d / 2f
        val cx = inset + r

        if (artPaint.shader == null) {
            val src = min(bmp.width, bmp.height).toFloat()
            if (src <= 0f) return
            val scale = d / src
            artMatrix.reset()
            artMatrix.setScale(scale, scale)
            // Centre the square crop of a cover that is not square.
            artMatrix.postTranslate(
                cx - bmp.width * scale / 2f,
                h / 2f - bmp.height * scale / 2f,
            )
            artPaint.shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                .apply { setLocalMatrix(artMatrix) }
        }
        canvas.drawCircle(cx, h / 2f, r, artPaint)
    }

    /**
     * What to draw the bars in, given the lozenge is always black.
     *
     * The pill's own fill is the first choice, so the two surfaces match — but
     * the palette contains a black pill, and black bars on a black island are
     * simply invisible. So anything too dark to read here falls back to that
     * style's ink, which was chosen to be legible against its fill and is
     * therefore light whenever the fill is dark.
     */
    private fun indicator(style: PillStyle): Int {
        val c = style.fill
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        val luminance = (r * 299 + g * 587 + b * 114) / 1000
        return if (luminance < 60) style.ink else c
    }

    private fun drawBars(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        dotPaint.color = color
        val bw = dp(BAR_W_DP)
        for (i in 0 until DOTS) {
            // Never fully collapse: five invisible dots is not a resting state,
            // it is a control that looks broken while it is working.
            val hh = (bw / 2f) + levels[i] * min(dp(8f), height / 3.2f)
            val x = dotX(i, cx)
            rect.set(x - bw / 2f, cy - hh, x + bw / 2f, cy + hh)
            canvas.drawRoundRect(rect, bw / 2f, bw / 2f, dotPaint)
        }
    }

    private fun drawChase(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        dotPaint.color = color
        val r = dp(BAR_W_DP) / 2f
        val head = phase * DOTS
        for (i in 0 until DOTS) {
            val d = min(abs(head - i), abs(head - i - DOTS))
            val lift = (1f - min(1f, d)).coerceAtLeast(0f)
            canvas.drawCircle(dotX(i, cx), cy, r * (0.7f + 0.6f * lift), dotPaint)
        }
    }

    private fun dotX(i: Int, cx: Float) = cx + (i - (DOTS - 1) / 2f) * dp(BAR_STEP_DP)

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
