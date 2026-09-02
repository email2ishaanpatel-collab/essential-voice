package com.ishaan.essentialvoice.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The pill: a small flat-yellow lozenge holding five black dots.
 *
 * Modelled directly on the reference — squat, barely wider than the dots it
 * carries, and fully opaque. There is no text and no transparency anywhere in
 * it: the dots carry every state, the way the Glyph does on the back of the
 * phone. Sliding in from the edge is done by moving the *window*, which is why
 * this view knows nothing about where on screen it sits.
 *
 * Drawn rather than composed of child views because it repaints on every audio
 * buffer and has to stay cheap on top of whatever app is in focus.
 */
class PillView(context: Context) : View(context) {

    enum class State { HIDDEN, LISTENING, THINKING, DONE, ERROR }

    /**
     * The pill draws two different things in the same window.
     *
     * [Mode.PILL] is the lozenge. [Mode.NOTE] is the card it becomes when a
     * dictation began with the word "note" — same view, same surface, resized
     * by the window rather than by drawing somewhere the surface does not
     * reach. Keeping it one view means the yellow never blinks between the two.
     */
    enum class Mode {
        /** The lozenge. */
        PILL,

        /**
         * Still a lozenge, just wider, with the word NOTES in it. Shown the
         * moment "note" is heard in the opening of a sentence — while the key
         * is still down — so it is obvious the words are being kept rather than
         * typed. The card does not appear until the sentence is finished.
         */
        BADGE,

        /** The card. */
        NOTE,
    }

    companion object {
        const val YELLOW = 0xFFFFD900.toInt()
        const val YELLOW_SUNK = 0xFFE8C500.toInt()
        const val INK = 0xFF1B1B1D.toInt()

        /** Window the pill is drawn inside, in dp. A little margin either side. */
        const val WINDOW_W_DP = 96f
        const val WINDOW_H_DP = 76f

        /**
         * The note card. One margin, used on the left, the right and the
         * bottom, measured from the screen edge — so the gap looks the same on
         * all three sides. The gesture bar is allowed to sit over the corner;
         * pushing the card up to clear it is what made the bottom gap look
         * wrong in the first place.
         */
        /**
         * The widened lozenge that says Notes. It is not a fixed width — see
         * [badgeWidthPx], which measures the word rather than trusting a number
         * to stay right across fonts and densities.
         */
        const val BADGE_LABEL = "Notes"

        /**
         * The badge's clock, measured against the widest thing it can say.
         *
         * A monospaced face draws "0:07" and "1:28" at the same width, so the
         * badge does not breathe once a second while a recording runs — but the
         * *window* still has to be wide enough for the longest one, and the
         * recorder's ceiling is ninety seconds.
         */
        private const val BADGE_CLOCK_SAMPLE = "0:00"

        /** Breathing room between the badge and the edge of its window. */
        const val BADGE_INSET_DP = 8f

        /** Word to the left edge, dots to the right edge. */
        private const val BADGE_PAD_DP = 22f

        /** The smallest gap allowed between the word and the dots. */
        private const val BADGE_GAP_DP = 26f

        const val NOTE_MARGIN_DP = 12f
        const val NOTE_H_DP = 320f
        private const val NOTE_CORNER_DP = 30f
        private const val NOTE_PAD_DP = 20f

        private const val BTN_H_DP = 46f
        private const val BTN_GAP_DP = 10f
        private const val BTN_CORNER_DP = 14f

        /** The recording card's transport: the disc you press to hear it back. */
        private const val PLAY_R_DP = 25f

        /** Air between the disc and the waveform beside it. */
        private const val PLAY_GAP_DP = 18f

        /** How tall the waveform stands, peak to peak. */
        private const val WAVE_H_DP = 74f

        /** One bar and the gap after it. */
        private const val WAVE_BAR_W_DP = 3f
        private const val WAVE_STEP_DP = 5f

        /** The shortest a bar is drawn, so silence is a line rather than a gap. */
        private const val WAVE_MIN_H_DP = 2.5f

        /** The blinking dot that says the microphone is open. */
        private const val REC_DOT_R_DP = 4.5f
        private const val REC_DOT_GAP_DP = 9f

        /** Nothing-OS red, for the one thing on the pill that is not the ink. */
        const val REC_RED = 0xFFD71921.toInt()

        const val PILL_W_DP = 76f
        const val PILL_H_DP = 54f

        /**
         * How long the dots keep waving with nothing happening. A note card can
         * be left open indefinitely, and an indefinite animation is a phone
         * that will not sleep its display work.
         */
        private const val IDLE_WAVE_MS = 20_000L

        /** Five, as in the reference. */
        const val DOTS = 5

        // Tighter together than the pill is wide, so the dots sit in a clear
        // island of yellow rather than running to the edges.
        private const val BAR_STEP_DP = 7.6f
        private const val BAR_W_DP = 4.0f
        private const val BAR_MIN_H_DP = 4.0f
        private const val BAR_MAX_H_DP = 30f

        /** The sign-off's own breathing room inside the lozenge. */
        private const val SIGN_PAD_DP = 6f

        /** Pitch and radius of a [DotGlyph]'s dots. Five of each way. */
        private const val SIGN_STEP_DP = 3.6f
        private const val SIGN_DOT_R_DP = 1.4f

        private const val SIGN_TEXT_DP = 13f

        /** Below this a shrunk sign-off stops being readable, so it clips. */
        private const val SIGN_TEXT_MIN_DP = 8.5f
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = YELLOW }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.08f
        textSize = dp(13f)
        typeface = runCatching {
            androidx.core.content.res.ResourcesCompat.getFont(
                context, com.ishaan.essentialvoice.R.font.geist_mono_medium,
            )
        }.getOrNull() ?: Typeface.MONOSPACE
    }


    private val rect = RectF()
    private val bar = RectF()

    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.12f
        textSize = dp(12f)
        typeface = runCatching {
            androidx.core.content.res.ResourcesCompat.getFont(
                context, com.ishaan.essentialvoice.R.font.geist_mono_medium,
            )
        }.getOrNull() ?: Typeface.MONOSPACE
    }

    /**
     * The badge's word. Deliberately its own paint: the card's [titlePaint] has
     * its alpha driven up and down by the card's fade, and a badge borrowing it
     * inherits whatever the last card left behind.
     */
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        textSize = dp(16f)
        typeface = runCatching {
            androidx.core.content.res.ResourcesCompat.getFont(
                context, com.ishaan.essentialvoice.R.font.geist_medium,
            )
        }.getOrNull() ?: Typeface.DEFAULT_BOLD
    }

    /**
     * The badge's clock and the card's, in the mono face.
     *
     * Its own paint rather than [badgePaint] with a different typeface, because
     * both are drawn in the same pass and swapping a face on a shared paint
     * between two draws is the kind of thing that works until something else
     * starts sharing it.
     */
    private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        textSize = dp(14f)
        typeface = runCatching {
            androidx.core.content.res.ResourcesCompat.getFont(
                context, com.ishaan.essentialvoice.R.font.geist_mono_medium,
            )
        }.getOrNull() ?: Typeface.MONOSPACE
    }

    private val recPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = REC_RED }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        textSize = dp(17f)
        typeface = runCatching {
            androidx.core.content.res.ResourcesCompat.getFont(
                context, com.ishaan.essentialvoice.R.font.geist_medium,
            )
        }.getOrNull() ?: Typeface.DEFAULT_BOLD
    }

    private val bodyPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        textSize = dp(13.5f)
        typeface = runCatching {
            androidx.core.content.res.ResourcesCompat.getFont(
                context, com.ishaan.essentialvoice.R.font.geist_regular,
            )
        }.getOrNull() ?: Typeface.DEFAULT
    }

    /**
     * The colours in force. Everything the view paints reads [styleFill],
     * [styleInk] or [styleSunk] rather than the constants above, which are now
     * only the default's values.
     */
    private var style: PillStyle = PillStyles.byId(PillStyles.DEFAULT_ID)
    private val styleFill: Int get() = style.fill
    private val styleInk: Int get() = style.ink
    private var styleSunk: Int = YELLOW_SUNK

    /** True when the window behind this is being blurred rather than filled. */
    val isBlurred: Boolean get() = style.blurred

    fun setStyle(next: PillStyle) {
        if (next == style) return
        style = next
        styleSunk = PillStyles.sunk(next)
        pillPaint.color = next.fill
        dotPaint.color = next.ink
        textPaint.color = next.ink
        badgePaint.color = next.ink
        clockPaint.color = next.ink
        titlePaint.color = next.ink
        bodyPaint.color = next.ink
        // The body is laid out with bodyPaint, so a colour change has to throw
        // the cached layout away or the note keeps its old ink until the text
        // happens to change.
        noteLayout = null
        laidOutFor = -1
        invalidate()
    }

    var mode: Mode = Mode.PILL
        private set

    /**
     * How far grown the badge or card is: 0 is the lozenge it came from, 1 is
     * the finished shape.
     *
     * The growing is drawn, not laid out. Animating the window's size means an
     * IPC to the window manager and a fresh surface *every frame*, which is
     * what made this jitter — so the window is resized once, up front, and the
     * shape inside it is what actually animates.
     */
    private var expand = 1f
    private val originRect = RectF()
    private var expandAnim: ValueAnimator? = null

    /**
     * How wide the badge's window has to be for the word and the dots to sit in
     * it with the padding they are supposed to have.
     *
     * Measured, not guessed. A constant here was a constant that had to be
     * re-checked every time the font, the text size or the number of dots
     * moved, and the failure it produces — a word clipped out of its own
     * lozenge — looks exactly like the word not being drawn at all.
     */
    fun badgeWidthPx(): Float {
        val right =
            if (recording) clockPaint.measureText(BADGE_CLOCK_SAMPLE)
            else (DOTS - 1) * dp(BAR_STEP_DP) + dp(BAR_W_DP)
        val left =
            badgePaint.measureText(badgeLabel) +
                if (recording) dp(REC_DOT_R_DP) * 2 + dp(REC_DOT_GAP_DP) else 0f
        return dp(BADGE_INSET_DP) * 2 + dp(BADGE_PAD_DP) * 2 +
            left + dp(BADGE_GAP_DP) + right
    }

    /**
     * Which of the three the pill is currently being.
     *
     * A label and a flag rather than the store's own `Kind`, deliberately: this
     * view is drawn over other people's apps and knows nothing about where the
     * words end up. What it needs is what to write on itself and whether there
     * is a microphone open, and that is all it is told.
     */
    private var badgeLabel: String = BADGE_LABEL
    private var cardTitle: String = BADGE_LABEL

    /** True for a recording, in both the badge and the card. */
    private var recording = false

    /** When the hold that is being recorded began, for the badge's clock. */
    private var recordingSince = 0L

    fun setBadgeLabel(label: String, isRecording: Boolean) {
        if (badgeLabel == label && recording == isRecording) return
        badgeLabel = label
        recording = isRecording
        if (isRecording) recordingSince = android.os.SystemClock.uptimeMillis()
        invalidate()
    }

    fun setCardTitle(title: String) {
        if (cardTitle == title) return
        cardTitle = title
        invalidate()
    }

    /** How long the badge's clock says, as m:ss. */
    private fun elapsedLabel(): String {
        val ms = (android.os.SystemClock.uptimeMillis() - recordingSince).coerceAtLeast(0L)
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    // ---- the recording card ------------------------------------------------

    /**
     * The clip the card is showing: its picture, its length, and where playback
     * has got to.
     *
     * Handed in as plain numbers, like everything else here. The card does not
     * know what a MediaPlayer is — it draws a fraction and reports a tap.
     */
    private var wave: IntArray = IntArray(0)
    private var clipMs = 0L
    private var playFraction = 0f
    private var playingBack = false

    /** Non-null while a finger is dragging along the waveform. */
    private var scrubbing = false

    fun setClip(peaks: List<Int>, durationMs: Long) {
        wave = peaks.toIntArray()
        clipMs = durationMs
        playFraction = 0f
        playingBack = false
        invalidate()
    }

    fun setPlayback(fraction: Float, playing: Boolean) {
        // A drag owns the head while it lasts. Without this the player's own
        // position, arriving sixteen times a second, fights the finger and the
        // head stutters backwards under it.
        if (scrubbing) return
        val f = fraction.coerceIn(0f, 1f)
        if (playFraction == f && playingBack == playing) return
        playFraction = f
        playingBack = playing
        invalidate()
    }

    /** The disc, and a drag along the waveform. */
    var onPlayToggle: (() -> Unit)? = null
    var onSeek: ((Float) -> Unit)? = null

    private val playRect = RectF()
    private val waveRect = RectF()

    /** Where the shape grows from, in this view's coordinates. */
    fun setOrigin(l: Float, t: Float, r: Float, b: Float) = originRect.set(l, t, r, b)

    fun animateExpand(
        from: Float,
        to: Float,
        ms: Long,
        interp: android.view.animation.Interpolator =
            android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f),
        onEnd: (() -> Unit)? = null,
    ) {
        expandAnim?.cancel()
        expand = from
        expandAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = ms
            interpolator = interp
            addUpdateListener { expand = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    expand = to
                    invalidate()
                    onEnd?.invoke()
                }
            })
            start()
        }
    }

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

    /**
     * The card's two buttons. Nothing else on it is tappable, and the pill is
     * not tappable at all — a note now leaves only when one of these is pressed.
     */
    var onSave: (() -> Unit)? = null
    var onDelete: (() -> Unit)? = null

    private val saveRect = RectF()
    private val deleteRect = RectF()

    /**
     * Tapping the note's text opens it for typing.
     *
     * The card was read-only: the only way to change a note was to say more of
     * it, and the only way to fix a word was to open the app. A note you cannot
     * correct where you are looking at it is a note you have to go and find
     * later.
     */
    var onEditBody: (() -> Unit)? = null

    /** Where the body text is, in view space, for the tap and for the field
     *  that gets laid over it. Empty until the card has finished growing. */
    private val bodyRect = RectF()

    /**
     * True while a real EditText is sitting on top of the body.
     *
     * The card stops painting its own text then — two copies of the same words,
     * one with a caret in it and one without, is the kind of thing that looks
     * like a rendering bug even when it is only half a pixel out.
     */
    private var editing = false

    /** Where to put the field. False if the card is not open far enough yet. */
    fun bodyBounds(out: android.graphics.Rect): Boolean {
        if (bodyRect.isEmpty) return false
        bodyRect.roundOut(out)
        return true
    }

    /**
     * Make [view] draw text exactly as the card's body does.
     *
     * The field that opens over the note has to be indistinguishable from the
     * text it replaces, or tapping a note would make it jump. Rather than
     * writing the size and the face down twice, the card hands them over.
     */
    fun styleAsBody(view: android.widget.TextView) {
        view.setTextColor(styleInk)
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, bodyPaint.textSize)
        view.typeface = bodyPaint.typeface
        view.setLineSpacing(dp(3f), 1f)
    }

    /** The ink the card writes in, for a caret that matches it. */
    fun bodyInk(): Int = styleInk

    fun setEditing(on: Boolean) {
        if (editing == on) return
        editing = on
        invalidate()
    }

    /** What the open note says so far. Re-laid out only when it changes. */
    private var noteText: String = ""
    private var noteLayout: android.text.StaticLayout? = null
    private var laidOutFor: Int = -1

    /**
     * Where the row of dots was last drawn, and how long the idle wave has left
     * to run.
     *
     * The dots are the only thing moving for most of a pill's life, and they
     * occupy a strip a few dp tall. Repainting the whole surface for them — a
     * full-width, 320dp card, at whatever the display is running at — is most
     * of what this view costs when nothing is happening.
     */
    private var rowCxDrawn = 0f
    private var rowCyDrawn = 0f
    private var rowKnown = false
    private var waveUntil = 0L

    /** Rolling amplitude history, newest at the right. */
    private val levels = FloatArray(DOTS)

    private var phase = 0f

    var state: State = State.HIDDEN
        private set

    private var phaseAnim: ValueAnimator? = null

    // ---- transitions -------------------------------------------------------

    fun reset(next: State) {
        state = next
        levels.fill(0f)
        syncPhase()
        invalidate()
    }

    /** The pill never changes size, so a state change is only a change of glyph. */
    fun morphTo(next: State) {
        if (state == next) return
        state = next
        syncPhase()
        invalidate()
    }

    fun stop() {
        state = State.HIDDEN
        stopPhase()
    }

    /**
     * Start or stop the dot clock to match what is on screen.
     *
     * Thinking animates. So does a card or a badge sitting there, because they
     * outlive the dictation that opened them and dead dots under live text read
     * as a frozen app. A listening pill does not — the microphone is already
     * redrawing it — and a finished pill has nothing moving at all.
     */
    private fun syncPhase() {
        val needed = when {
            state == State.HIDDEN -> false
            state == State.THINKING -> true
            else -> mode == Mode.NOTE || mode == Mode.BADGE
        }
        if (needed) startPhase() else stopPhase()
    }

    fun setMode(next: Mode) {
        if (mode == next) return
        mode = next
        laidOutFor = -1
        // The card outlives the dictation that opened it, so it owns the clock
        // its dots run on rather than borrowing the one reset() started.
        syncPhase()
        invalidate()
    }

    /**
     * What the pill says when it finishes. Chosen per dictation by [SignOff]
     * and handed over just before the morph, so the pill itself has no opinion
     * about which one it is showing.
     */
    private var signOff: SignOff = SignOff.PLAIN

    fun setSignOff(next: SignOff) {
        signOff = next
        invalidate()
    }

    fun setNoteText(text: String) {
        if (noteText == text) return
        noteText = text
        laidOutFor = -1
        invalidate()
    }

    /** Called from the mic thread on every buffer. */
    /**
     * One level, already on 0..1, for the newest dot.
     *
     * The shaping used to happen here — `peak / 0.35f`, because speech peaks
     * well below full scale — and that was fine while the only caller was the
     * microphone handing over a raw amplitude. It stopped being fine when
     * Google's recogniser became a second caller: its `onRmsChanged` is already
     * a 0..1 figure, so dividing it by 0.35 again saturated every dot at a third
     * of the way up, and the row sat pinned at maximum the moment anybody spoke.
     * Each engine shapes its own now, in
     * [com.ishaan.essentialvoice.voice.Dictation].
     */
    fun pushLevel(level: Float) {
        val shaped = min(1f, max(0f, level))
        post {
            System.arraycopy(levels, 1, levels, 0, DOTS - 1)
            levels[DOTS - 1] = shaped
            invalidateRow()
        }
    }

    /**
     * The clock the moving dots run on, started only for the states that
     * actually use it.
     *
     * Listening does not: the microphone pushes a level per buffer and that is
     * what redraws the row. Running a display-rate animator alongside it was
     * two invalidations for one thing moving.
     */
    private fun startPhase() {
        waveUntil = android.os.SystemClock.uptimeMillis() + IDLE_WAVE_MS
        if (phaseAnim?.isRunning == true) return
        phaseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                // A card left open on the table is a card animating at the
                // display's refresh rate until someone comes back to it. The
                // wave settles after a while; anything happening starts it
                // again.
                if (state != State.THINKING && !(recording && mode == Mode.BADGE) &&
                    android.os.SystemClock.uptimeMillis() > waveUntil
                ) {
                    stopPhase()
                }
                invalidateRow()
            }
            start()
        }
    }

    /** Only the strip the dots live in, not the whole card. */
    private fun invalidateRow() {
        // The recording badge has a blinking dot at one end and a clock at the
        // other, and the strip between them is the only part invalidateRow
        // knows about. It is a lozenge a couple of hundred dp wide; repainting
        // all of it costs less than being wrong about which parts moved.
        if (recording && mode == Mode.BADGE) { invalidate(); return }
        if (!rowKnown) { invalidate(); return }
        val halfW = (DOTS - 1) / 2f * dp(BAR_STEP_DP) + dp(BAR_W_DP)
        val halfH = dp(BAR_MAX_H_DP) / 2f + dp(2f)
        invalidate(
            (rowCxDrawn - halfW).toInt() - 1,
            (rowCyDrawn - halfH).toInt() - 1,
            (rowCxDrawn + halfW).toInt() + 1,
            (rowCyDrawn + halfH).toInt() + 1,
        )
    }

    /**
     * How far a finger has to travel over the pill before it counts as throwing
     * it away. Forty dp: further than a thumb wobbles while holding a key, and
     * shorter than the pill is wide.
     */
    private val SWIPE_AWAY_PX: Float get() = dp(40f)

    /** Whether the dots are currently moving of their own accord. */
    private val waving: Boolean get() = phaseAnim?.isRunning == true

    private fun stopPhase() {
        phaseAnim?.cancel()
        phaseAnim = null
    }

    /**
     * Swiped off the screen: stop, and throw the words away.
     *
     * Set while there is something to abandon. It is the only way out of a
     * dictation that nobody is holding a key for — one started by the assistant
     * gesture, or toggled — where letting go is not available because nothing is
     * being held.
     */
    var onSwipeAway: (() -> Unit)? = null

    /** Where a swipe started, and whether it has already been acted on. */
    private var swipeX = 0f
    private var swipeY = 0f
    private var swiped = false

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        // A swipe over the pill abandons the dictation.
        //
        // Taking the touch at all is a real cost — the app underneath does not
        // see it — so it is taken only while there is a dictation to abandon and
        // only over the pill itself, which is a small target the finger has to
        // be aimed at. A press that never travels is let go of at the end
        // without doing anything, which is the safe half of the trade: the
        // gesture has to be a *swipe* to mean anything.
        if (mode != Mode.NOTE && onSwipeAway != null) {
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    swipeX = event.rawX
                    swipeY = event.rawY
                    swiped = false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (swiped) return true
                    val dx = event.rawX - swipeX
                    val dy = event.rawY - swipeY
                    if (dx * dx + dy * dy >= SWIPE_AWAY_PX * SWIPE_AWAY_PX) {
                        swiped = true
                        onSwipeAway?.invoke()
                    }
                }
            }
            return true
        }

        // Only the card takes touches, and only on its two buttons. The pill is
        // decoration and must let whatever is underneath it keep working.
        // Only the card takes touches. The badge is a status light, not a
        // control, and must not eat taps meant for the app underneath.
        if (mode != Mode.NOTE) return false
        val x = event.x
        val y = event.y

        // A drag along the waveform moves the playhead. Handled before the
        // ACTION_UP gate below, which is all the note card ever needed: a note
        // has two buttons and a body, and none of them cares where a finger has
        // been, only where it was let go.
        if (recording) {
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN ->
                    if (waveRect.contains(x, y)) { scrubbing = true; scrubTo(x); return true }
                android.view.MotionEvent.ACTION_MOVE ->
                    if (scrubbing) { scrubTo(x); return true }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL ->
                    if (scrubbing) { scrubTo(x); scrubbing = false; return true }
            }
        }

        if (event.action != android.view.MotionEvent.ACTION_UP) return true
        when {
            saveRect.contains(x, y) -> onSave?.invoke()
            deleteRect.contains(x, y) -> onDelete?.invoke()
            recording && playRect.contains(x, y) -> onPlayToggle?.invoke()
            // Anywhere on the words. Not the whole card: the heading and the
            // bars are not text and tapping them should not put a caret in
            // anything.
            bodyRect.contains(x, y) -> onEditBody?.invoke()
        }
        return true
    }

    /**
     * Put the playhead where the finger is.
     *
     * The head is moved here rather than waiting for the player to report back,
     * because the round trip through seek and the next position tick is long
     * enough to see: the bars would lag a couple of frames behind the thumb,
     * which reads as the drag not working rather than as latency.
     */
    private fun scrubTo(x: Float) {
        if (waveRect.width() <= 0f) return
        val f = ((x - waveRect.left) / waveRect.width()).coerceIn(0f, 1f)
        playFraction = f
        invalidate()
        onSeek?.invoke(f)
    }

    override fun onDetachedFromWindow() {
        expandAnim?.cancel()
        stopPhase()
        super.onDetachedFromWindow()
    }

    // ---- drawing -----------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        if (state == State.HIDDEN) return
        if (mode == Mode.NOTE) { drawNoteCard(canvas); return }
        if (mode == Mode.BADGE) { drawBadge(canvas); return }

        val cx = width / 2f
        val cy = height / 2f
        val hw = dp(PILL_W_DP) / 2f
        val hh = dp(PILL_H_DP) / 2f
        rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
        // Full radius: the corner is half the height, so the ends are true
        // semicircles and the shape is a stadium rather than a rounded box.
        val r = hh
        canvas.drawRoundRect(rect, r, r, pillPaint)

        when (state) {
            State.LISTENING -> drawLevels(canvas, cx, cy)
            State.THINKING -> drawThinking(canvas, cx, cy)
            State.DONE -> drawDone(canvas, cx, cy)
            State.ERROR -> drawCross(canvas, cx, cy)
            State.HIDDEN -> Unit
        }
    }

    /**
     * The card: yellow, filling its window, with the heading on the left and the
     * same five bars in the top-right corner still reacting to the microphone.
     * The dots are the one thing carried over from the pill, so the card reads
     * as the pill having grown rather than as a different object arriving.
     */
    private fun drawNoteCard(canvas: Canvas) {
        val f = expand
        val l = lerp(originRect.left, 0f, f)
        val t = lerp(originRect.top, 0f, f)
        val rt = lerp(originRect.right, width.toFloat(), f)
        val b = lerp(originRect.bottom, height.toFloat(), f)
        rect.set(l, t, rt, b)
        // Starts as round as the lozenge it came from and squares off as it
        // opens, so the corner never looks like it jumps.
        val corner = lerp((originRect.height() / 2f).coerceAtLeast(1f), dp(NOTE_CORNER_DP), f)
        canvas.drawRoundRect(rect, corner, corner, pillPaint)

        // Contents belong to the finished card; they fade in over the last of
        // the growth rather than being scaled up from nothing.
        val ink = ((f - 0.55f) / 0.45f).coerceIn(0f, 1f)
        if (ink <= 0f) {
            saveRect.setEmpty()
            deleteRect.setEmpty()
            bodyRect.setEmpty()
            return
        }
        canvas.save()
        canvas.clipRect(rect)
        canvas.translate(l, t)
        drawCardContents(canvas, (rt - l).toInt(), (b - t).toInt(), ink)
        canvas.restore()
    }

    private fun drawCardContents(canvas: Canvas, width: Int, height: Int, ink: Float) {
        val alpha = (255 * ink).toInt()
        titlePaint.alpha = alpha
        bodyPaint.alpha = alpha
        dotPaint.alpha = alpha

        val pad = dp(NOTE_PAD_DP)
        val fm = titlePaint.fontMetrics
        val titleBase = pad - fm.ascent
        canvas.drawText(cardTitle, pad, titleBase, titlePaint)

        val rowCy = pad + (-fm.ascent + fm.descent) / 2f
        val rowRight = width - pad
        if (recording) {
            // Where the dots would be, the clock. A finished clip has no
            // microphone behind it, so a row of levels there would be five dots
            // sitting still for as long as the card is up — and the one number
            // anybody wants from a recording is how long it is.
            clockPaint.alpha = alpha
            val cfm = clockPaint.fontMetrics
            val clock = clockLabel()
            canvas.drawText(
                clock, rowRight - clockPaint.measureText(clock),
                rowCy - (cfm.ascent + cfm.descent) / 2f, clockPaint,
            )
            clockPaint.alpha = 255
        } else {
            // Same row of bars as the pill, right-aligned on the heading's centre.
            val rowCx = rowRight - (DOTS - 1) / 2f * dp(BAR_STEP_DP)
            when (state) {
                State.LISTENING -> drawLevels(canvas, rowCx, rowCy)
                State.THINKING -> drawThinking(canvas, rowCx, rowCy)
                else -> drawIdle(canvas, rowCx, rowCy)
            }
        }

        val btnH = dp(BTN_H_DP)
        val btnGap = dp(BTN_GAP_DP)
        val btnTop = height - pad - btnH
        val half = (width - pad * 2 - btnGap) / 2f
        deleteRect.set(pad, btnTop, pad + half, btnTop + btnH)
        saveRect.set(width - pad - half, btnTop, width - pad, btnTop + btnH)
        // Recorded in card-local space; shifted into view space for hit testing
        // once the card has finished growing.
        val ox = lerp(originRect.left, 0f, expand)
        val oy = lerp(originRect.top, 0f, expand)
        deleteRect.offset(ox, oy)
        saveRect.offset(ox, oy)
        drawButton(canvas, deleteRect, "DELETE", styleSunk, styleInk)
        drawButton(canvas, saveRect, "SAVE", styleInk, styleFill)

        val bodyTop = titleBase + fm.descent + dp(14f)
        val bodyWidth = (width - pad * 2).toInt()
        if (bodyWidth <= 0) { resetAlphas(); return }

        if (recording) {
            // A clip has no words to tap into, so the body is a transport
            // rather than text and [bodyRect] stays empty — which is what stops
            // a tap on the middle of the card opening a keyboard over it.
            bodyRect.setEmpty()
            drawTransport(canvas, pad, bodyTop, width - pad, btnTop - dp(14f), ox, oy, ink)
            resetAlphas()
            return
        }

        // In view space, like the buttons above, so a tap can be tested against
        // it and the text field can be laid exactly over it.
        bodyRect.set(pad, bodyTop, pad + bodyWidth, btnTop - dp(14f))
        bodyRect.offset(ox, oy)

        // The field is drawing the words now.
        if (editing) { resetAlphas(); return }

        if (laidOutFor != bodyWidth || noteLayout == null) {
            val shown = noteText.ifBlank { "Hold the key and say it. Letting go saves it." }
            bodyPaint.alpha = if (noteText.isBlank()) 130 else 255
            noteLayout = android.text.StaticLayout.Builder
                .obtain(shown, 0, shown.length, bodyPaint, bodyWidth)
                .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(dp(3f), 1f)
                .setIncludePad(false)
                .build()
            laidOutFor = bodyWidth
        }

        val layout = noteLayout ?: run { resetAlphas(); return }
        canvas.save()
        // Newest lines matter most, so a note taller than the card shows its
        // bottom rather than its beginning.
        val room = btnTop - dp(14f) - bodyTop
        val dy = if (layout.height > room) room - layout.height else 0f
        canvas.translate(pad, bodyTop + dy)
        canvas.clipRect(0f, -dy, bodyWidth.toFloat(), -dy + room)
        layout.draw(canvas)
        canvas.restore()
        resetAlphas()
    }

    /** Where playback has got to, over how long, as the card's heading clock. */
    private fun clockLabel(): String {
        fun mmss(ms: Long): String {
            val total = (ms / 1000).coerceAtLeast(0L)
            return "%d:%02d".format(total / 60, total % 60)
        }
        val at = (clipMs * playFraction).toLong()
        return if (playFraction <= 0f) mmss(clipMs) else mmss(at) + " / " + mmss(clipMs)
    }

    /**
     * The recording card's middle: a disc to press and the clip's own picture
     * beside it.
     *
     * The waveform is the scrubber. There is no separate progress line under it
     * — the bars already say where in the clip you are, because the ones behind
     * the head are drawn at full strength and the ones ahead are not, so adding
     * a track would be the same information twice.
     */
    private fun drawTransport(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        ox: Float,
        oy: Float,
        ink: Float,
    ) {
        val cy = (top + bottom) / 2f
        val r = dp(PLAY_R_DP)
        val discCx = left + r

        // The disc. Ink-filled with the pill's own fill punched out of it, so
        // it reads as the same object as the SAVE button rather than as a
        // control borrowed from somewhere else.
        buttonPaint.color = styleInk
        buttonPaint.alpha = (255 * ink).toInt()
        canvas.drawCircle(discCx, cy, r, buttonPaint)
        buttonPaint.alpha = 255

        buttonPaint.color = styleFill
        buttonPaint.alpha = (255 * ink).toInt()
        if (playingBack) {
            // Pause: two bars, at the same weight as the waveform's own.
            val bw = dp(4f)
            val bh = dp(15f)
            val gap = dp(4f)
            canvas.drawRoundRect(
                RectF(discCx - gap / 2f - bw, cy - bh / 2f, discCx - gap / 2f, cy + bh / 2f),
                bw / 2f, bw / 2f, buttonPaint,
            )
            canvas.drawRoundRect(
                RectF(discCx + gap / 2f, cy - bh / 2f, discCx + gap / 2f + bw, cy + bh / 2f),
                bw / 2f, bw / 2f, buttonPaint,
            )
        } else {
            // Play: a triangle, nudged right by an eighth of its width because
            // a triangle centred on its bounding box always looks left of
            // centre inside a circle.
            val half = dp(9f)
            val path = android.graphics.Path().apply {
                moveTo(discCx - half * 0.62f + dp(1.5f), cy - half)
                lineTo(discCx + half + dp(1.5f), cy)
                lineTo(discCx - half * 0.62f + dp(1.5f), cy + half)
                close()
            }
            canvas.drawPath(path, buttonPaint)
        }
        buttonPaint.alpha = 255

        playRect.set(discCx - r, cy - r, discCx + r, cy + r)
        playRect.offset(ox, oy)

        val waveLeft = discCx + r + dp(PLAY_GAP_DP)
        val waveW = right - waveLeft
        if (waveW <= 0f) { waveRect.setEmpty(); return }
        waveRect.set(waveLeft, cy - dp(WAVE_H_DP) / 2f, right, cy + dp(WAVE_H_DP) / 2f)

        val step = dp(WAVE_STEP_DP)
        val bars = (waveW / step).toInt().coerceAtLeast(1)
        val barW = dp(WAVE_BAR_W_DP)
        val minH = dp(WAVE_MIN_H_DP)
        val maxH = dp(WAVE_H_DP)
        val headX = waveLeft + waveW * playFraction

        for (i in 0 until bars) {
            // The stored picture is a fixed number of buckets and the card is
            // however wide it is, so the bars stride over the buckets rather
            // than there being one of each. A narrower card samples the same
            // clip more coarsely instead of drawing part of it.
            val peak = if (wave.isEmpty()) 0
            else wave[(i.toFloat() / bars * wave.size).toInt().coerceIn(0, wave.size - 1)]
            val h = (minH + (maxH - minH) * (peak / 100f)).coerceAtLeast(minH)
            val x = waveLeft + i * step
            // Behind the head at full strength, ahead of it faded: the clip's
            // own picture is the progress bar.
            val played = x + barW / 2f <= headX
            dotPaint.alpha = ((if (played) 255 else 90) * ink).toInt()
            bar.set(x, cy - h / 2f, x + barW, cy + h / 2f)
            canvas.drawRoundRect(bar, barW / 2f, barW / 2f, dotPaint)
        }
        dotPaint.alpha = 255
        waveRect.offset(ox, oy)
    }

    private fun resetAlphas() {
        titlePaint.alpha = 255
        bodyPaint.alpha = 255
        dotPaint.alpha = 255
    }

    /**
     * The widened lozenge: the same pill, stretched, with the word Notes on the
     * left and the dots still moving on the right.
     *
     * Grown from [originRect] — the pill's rectangle expressed in this, wider,
     * window — and not from the window's centre. The window has to move when it
     * widens, and near the screen edge it is clamped as well, so a shape that
     * starts at the middle of the new window starts somewhere the pill never
     * was: it reads as a second pill appearing over the first. Starting from
     * where the pill actually stood makes it one object widening in place.
     */
    private fun drawBadge(canvas: Canvas) {
        val f = expand
        val cy = height / 2f
        val hh = dp(PILL_H_DP) / 2f
        val inset = dp(BADGE_INSET_DP)

        val l = lerp(originRect.left, inset, f)
        val r = lerp(originRect.right, width - inset, f)
        val t = lerp(originRect.top, cy - hh, f)
        val b = lerp(originRect.bottom, cy + hh, f)
        rect.set(l, t, r, b)
        val corner = rect.height() / 2f
        canvas.drawRoundRect(rect, corner, corner, pillPaint)

        val pad = dp(BADGE_PAD_DP)
        val rowCy = rect.centerY()

        // The word arrives once there is room for it rather than being squashed
        // into a lozenge that has not opened yet — but it is the whole point of
        // the badge, so it comes in early and is at full strength well before
        // the shape stops moving.
        val ink = ((f - 0.25f) / 0.35f).coerceIn(0f, 1f)
        val alpha = (255 * ink).toInt()

        if (recording) {
            // A recording says two things and neither of them is a level: that
            // the microphone is open, and how long it has been. The dots are
            // the wrong instrument for both — they say "something is being
            // heard", which is exactly what a recording does not need to be
            // told, and they would leave nowhere for the clock to sit.
            if (ink > 0f) {
                // Half a second on, half a second off. [phase] is a linear
                // 0..1 over 1200ms, so this blinks a little under once a
                // second — slow enough to read as deliberate rather than as an
                // alarm.
                val lit = if (phase < 0.5f) 1f else 0.28f
                recPaint.alpha = (255 * ink * lit).toInt()
                val dotR = dp(REC_DOT_R_DP)
                canvas.drawCircle(l + pad + dotR, rowCy, dotR, recPaint)
                recPaint.alpha = 255

                badgePaint.alpha = alpha
                val fm = badgePaint.fontMetrics
                canvas.drawText(
                    badgeLabel,
                    l + pad + dotR * 2 + dp(REC_DOT_GAP_DP),
                    rowCy - (fm.ascent + fm.descent) / 2f,
                    badgePaint,
                )
                badgePaint.alpha = 255

                clockPaint.alpha = alpha
                val cfm = clockPaint.fontMetrics
                val clock = elapsedLabel()
                canvas.drawText(
                    clock,
                    r - pad - clockPaint.measureText(clock),
                    rowCy - (cfm.ascent + cfm.descent) / 2f,
                    clockPaint,
                )
                clockPaint.alpha = 255
            }
            return
        }

        // The dots are the thing that never left, so they travel: centred while
        // it is still a pill, drifting to the right end as the room appears.
        val dotsRight = r - pad - dp(BAR_W_DP) / 2f
        val rowCx = lerp(rect.centerX(), dotsRight - (DOTS - 1) / 2f * dp(BAR_STEP_DP), f)
        when (state) {
            State.LISTENING -> drawLevels(canvas, rowCx, rowCy)
            State.THINKING -> drawThinking(canvas, rowCx, rowCy)
            else -> drawIdle(canvas, rowCx, rowCy)
        }

        if (ink <= 0f) return
        badgePaint.alpha = alpha
        val fm = badgePaint.fontMetrics
        canvas.drawText(badgeLabel, l + pad, rowCy - (fm.ascent + fm.descent) / 2f, badgePaint)
        badgePaint.alpha = 255
    }

    private fun drawButton(canvas: Canvas, r: RectF, label: String, fill: Int, ink: Int) {
        buttonPaint.color = fill
        val c = dp(BTN_CORNER_DP)
        // r is in view space; the canvas is translated into card space.
        val ox = lerp(originRect.left, 0f, expand)
        val oy = lerp(originRect.top, 0f, expand)
        canvas.drawRoundRect(
            r.left - ox, r.top - oy, r.right - ox, r.bottom - oy, c, c, buttonPaint,
        )
        buttonTextPaint.color = ink
        val fm = buttonTextPaint.fontMetrics
        canvas.drawText(
            label, r.centerX() - ox, r.centerY() - oy - (fm.ascent + fm.descent) / 2f,
            buttonTextPaint,
        )
    }

    private fun dotX(i: Int, cx: Float) = cx + (i - (DOTS - 1) / 2f) * dp(BAR_STEP_DP)

    /**
     * One bar per column, grown from the centre. At rest each is as tall as it
     * is wide, so the row reads as the five dots in the reference; loudness
     * stretches them into a waveform rather than fattening them into blobs.
     */
    private fun drawBar(canvas: Canvas, x: Float, cy: Float, level: Float) {
        val w = dp(BAR_W_DP)
        val h = dp(BAR_MIN_H_DP) + (dp(BAR_MAX_H_DP) - dp(BAR_MIN_H_DP)) * level
        bar.set(x - w / 2f, cy - h / 2f, x + w / 2f, cy + h / 2f)
        canvas.drawRoundRect(bar, w / 2f, w / 2f, dotPaint)
    }

    private fun drawLevels(canvas: Canvas, cx: Float, cy: Float) {
        markRow(cx, cy)
        for (i in 0 until DOTS) drawBar(canvas, dotX(i, cx), cy, levels[i])
    }

    private fun markRow(cx: Float, cy: Float) {
        rowCxDrawn = cx
        rowCyDrawn = cy
        rowKnown = true
    }

    /**
     * The row breathing while the card just sits there.
     *
     * The card is not an ending — the key can be held again and the next line
     * joins the same note — so the dots must never go flat and dead under the
     * text. A slow shallow wave, well under speaking amplitude, so it reads as
     * waiting rather than as hearing something.
     */
    private fun drawIdle(canvas: Canvas, cx: Float, cy: Float) {
        markRow(cx, cy)
        for (i in 0 until DOTS) {
            // Settled: still five dots, just no longer spending a frame each.
            val level =
                if (!waving) 0f else (0.5f + 0.5f * sin(phase * 2f * PI.toFloat() + i * 0.62f)) * 0.3f
            drawBar(canvas, dotX(i, cx), cy, level)
        }
    }

    /** One bar stretching and settling along the row while whisper decodes. */
    private fun drawThinking(canvas: Canvas, cx: Float, cy: Float) {
        markRow(cx, cy)
        val head = phase * DOTS
        for (i in 0 until DOTS) {
            val d = min(abs(head - i), abs(head - i - DOTS))
            drawBar(canvas, dotX(i, cx), cy, (1f - min(1f, d)).coerceAtLeast(0f) * 0.75f)
        }
    }

    /**
     * Says so — in whichever words it drew this time. See [SignOff].
     *
     * Three layouts, picked by what the sign-off has rather than by a flag: a
     * word on its own, a glyph on its own, or a glyph sitting over its word.
     * Side by side was tried and does not fit — a heart next to "Carl Pei"
     * leaves the words about a third of the lozenge, which is a size nobody
     * reads.
     */
    private fun drawDone(canvas: Canvas, cx: Float, cy: Float) {
        val sign = signOff
        val glyph = sign.glyph
        val avail = dp(PILL_W_DP) - dp(SIGN_PAD_DP) * 2

        textPaint.textSize = dp(SIGN_TEXT_DP)
        var wordW = if (sign.word.isEmpty()) 0f else textPaint.measureText(sign.word)
        if (wordW > avail) {
            // Shrunk rather than clipped. A sign-off missing its last letter
            // looks like the app failing to draw, not like a long word.
            textPaint.textSize =
                (dp(SIGN_TEXT_DP) * (avail / wordW)).coerceAtLeast(dp(SIGN_TEXT_MIN_DP))
            wordW = textPaint.measureText(sign.word)
        }

        val fm = textPaint.fontMetrics
        val wordH = if (sign.word.isEmpty()) 0f else -fm.ascent + fm.descent
        val glyphH = if (glyph == null) 0f else glyph.rows * dp(SIGN_STEP_DP)
        val gap = if (glyph == null || sign.word.isEmpty()) 0f else dp(4f)

        var top = cy - (glyphH + gap + wordH) / 2f
        if (glyph != null) {
            drawDotMatrix(canvas, glyph, cx, top + glyphH / 2f)
            top += glyphH + gap
        }
        if (sign.word.isNotEmpty()) canvas.drawText(sign.word, cx, top - fm.ascent, textPaint)

        // Shared paint. The badge and the card measure with it too, and a text
        // size left behind here is a word laid out at the wrong size there.
        textPaint.textSize = dp(SIGN_TEXT_DP)
    }

    /** A [DotGlyph], on the same grid and in the same paint as the five bars. */
    private fun drawDotMatrix(canvas: Canvas, glyph: DotGlyph, cx: Float, cy: Float) {
        val step = dp(SIGN_STEP_DP)
        val r = dp(SIGN_DOT_R_DP)
        for (y in 0 until glyph.rows) {
            for (x in 0 until glyph.cols) {
                if (!glyph.on(x, y)) continue
                canvas.drawCircle(
                    cx + (x - (glyph.cols - 1) / 2f) * step,
                    cy + (y - (glyph.rows - 1) / 2f) * step,
                    r,
                    dotPaint,
                )
            }
        }
    }

    /** A cross on the same five columns: something went wrong, look in the app. */
    private fun drawCross(canvas: Canvas, cx: Float, cy: Float) {
        val r = dp(2.2f)
        val s = dp(5.0f)
        canvas.drawCircle(dotX(0, cx), cy - s, r, dotPaint)
        canvas.drawCircle(dotX(4, cx), cy - s, r, dotPaint)
        canvas.drawCircle(dotX(2, cx), cy, r, dotPaint)
        canvas.drawCircle(dotX(0, cx), cy + s, r, dotPaint)
        canvas.drawCircle(dotX(4, cx), cy + s, r, dotPaint)
    }
}
