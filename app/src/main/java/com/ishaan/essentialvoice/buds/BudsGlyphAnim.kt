package com.ishaan.essentialvoice.buds

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos

/**
 * Blinks the dot while a pair is connecting.
 *
 * A widget cannot animate itself. `RemoteViews` will only call methods marked
 * `@RemotableViewMethod`, which rules out starting an animator, and a
 * `ViewFlipper` throws at apply time for the same reason — the old standalone
 * widget's README recorded that as "Can't load widget". The one thing that does
 * work is computing the frames *here*, in this process, and pushing each one to
 * the launcher with `partiallyUpdateAppWidget`.
 *
 * So this is deliberately short-lived and bounded. It runs only between a tap
 * and the link coming up, it stops the moment [BudsStateReceiver] hears the
 * result, and it gives up on its own after [MAX_MS] whatever happens — a
 * blink that never stops is worse than no blink, and every frame is a binder
 * transaction to another process.
 */
object BudsGlyphAnim {

    /** Long enough for a pair that is awake, short enough not to hang about. */
    private const val MAX_MS = 4_500L

    /** ~14fps. Enough for a fade this short, and each frame costs a binder. */
    private const val FRAME_MS = 70L

    /** One dim-and-back. Slow enough to read as a blink, not a flicker. */
    private const val PERIOD_MS = 900f

    /** Never all the way out: a dot that vanishes reads as the widget breaking. */
    private const val MIN_ALPHA = 0.16f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var job: Job? = null

    val running: Boolean get() = job?.isActive == true

    /** For a caller that has to stay alive while the frames are being pushed. */
    val current: Job? get() = job

    fun start(context: Context): Job {
        job?.cancel()
        val app = context.applicationContext
        val started = SystemClock.uptimeMillis()
        val j = scope.launch {
            while (isActive && SystemClock.uptimeMillis() - started < MAX_MS) {
                BudsWidget.pushGlyph(app, alphaAt(SystemClock.uptimeMillis() - started))
                delay(FRAME_MS)
            }
            // However it ended, leave the widget showing the truth rather than
            // whatever frame happened to be last.
            BudsWidget.refresh(app)
        }
        job = j
        return j
    }

    /**
     * A cosine rather than a hard on/off: at this frame rate a square blink
     * lands on uneven frames and stutters, where a fade does not. Starts at
     * full, so the tap's first frame is the dot lighting up.
     */
    private fun alphaAt(elapsedMs: Long): Float {
        val phase = cos(2.0 * PI * elapsedMs / PERIOD_MS).toFloat()
        val lit = 0.5f + 0.5f * phase
        return MIN_ALPHA + (1f - MIN_ALPHA) * lit
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
