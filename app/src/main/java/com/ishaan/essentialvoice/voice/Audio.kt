package com.ishaan.essentialvoice.voice

import kotlin.math.abs

/** Sample rate whisper expects. Anything else has to be resampled first. */
const val SAMPLE_RATE = 16_000

object Audio {

    /** Peak we normalise quiet recordings up to. */
    private const val TARGET_PEAK = 0.55f

    /** Ceiling on the boost, so a silent room does not become amplified hiss. */
    private const val MAX_GAIN = 12f

    fun peak(a: FloatArray): Float {
        var p = 0f
        for (v in a) { val m = abs(v); if (m > p) p = m }
        return p
    }

    /**
     * A phone mic at a normal capture level lands speech near a tenth of full
     * scale, which is quiet enough that whisper's own gating returns an empty
     * transcript. Lift the peak toward [TARGET_PEAK] before handing it over.
     * Returns the gain that was applied, for logging.
     */
    fun normalise(a: FloatArray): Float {
        val p = peak(a)
        if (p <= 1e-5f) return 1f
        val gain = (TARGET_PEAK / p).coerceIn(1f, MAX_GAIN)
        if (gain <= 1.01f) return 1f
        for (i in a.indices) a[i] = (a[i] * gain).coerceIn(-1f, 1f)
        return gain
    }

    /** Pad short clips: whisper's encoder wants at least a moment of audio. */
    fun padTo(a: FloatArray, minSeconds: Float): FloatArray {
        val min = (minSeconds * SAMPLE_RATE).toInt()
        if (a.size >= min) return a
        return FloatArray(min).also { a.copyInto(it) }
    }
}
