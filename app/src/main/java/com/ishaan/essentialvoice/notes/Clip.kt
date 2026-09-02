package com.ishaan.essentialvoice.notes

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A recording on disk: how it is written, how it is read back, and the picture
 * of it the library draws.
 *
 * **WAV, uncompressed, at whisper's own 16kHz mono.** An m4a would be a tenth
 * of the size, and it was not chosen, for two reasons that both matter more
 * here than bytes do. Encoding needs MediaCodec, an encoder thread and an
 * output format negotiation, all on the path between the key coming up and the
 * card appearing; and the clip has to be handed *back* to whisper a moment
 * later for its transcript, which means decoding it again. Writing the floats
 * the microphone already produced is a header and a loop, and reading them back
 * is the same loop backwards. At 32KB a second a long hold is a couple of
 * megabytes — see [com.ishaan.essentialvoice.voice.Recorder]'s ceiling, which
 * caps a single clip at ninety seconds and so at just under three.
 */
object Clip {

    private const val TAG = "EVClip"

    /** 16kHz mono, 16-bit — what the recorder produces and what whisper wants. */
    private const val SAMPLE_RATE = 16_000
    private const val CHANNELS = 1
    private const val BITS = 16

    private const val HEADER_BYTES = 44

    /**
     * How many peaks a waveform is drawn from.
     *
     * Enough that a bar is a few pixels wide across a phone, few enough that
     * the whole picture is a hundred small integers in a JSON file. The
     * *drawing* decides how many of these it shows; a narrow row in the library
     * strides over them rather than asking for a different number.
     */
    const val WAVE_BUCKETS = 96

    /**
     * A name no other clip has.
     *
     * From the clock rather than from the row's id, because the file is written
     * *before* the row exists — the row has to carry the name, so the name
     * cannot come from the row. Two recordings inside one millisecond is not a
     * thing a person can do with a key they have to hold.
     */
    fun freshName(): String = "rec-${System.currentTimeMillis()}.wav"

    /**
     * Write [samples] out as a WAV, returning true if it landed.
     *
     * Written to a temporary file and renamed, like the note store, so a clip
     * interrupted halfway through is absent rather than truncated — a
     * zero-length WAV that plays as a click is worse than no file at all,
     * because the row still says there is audio there.
     */
    fun write(target: File, samples: FloatArray): Boolean {
        val tmp = File(target.parentFile, target.name + ".tmp")
        return runCatching {
            val bytes = ByteBuffer.allocate(HEADER_BYTES + samples.size * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
            val dataBytes = samples.size * 2
            val byteRate = SAMPLE_RATE * CHANNELS * BITS / 8

            bytes.put("RIFF".toByteArray())
            bytes.putInt(36 + dataBytes)
            bytes.put("WAVE".toByteArray())
            bytes.put("fmt ".toByteArray())
            bytes.putInt(16)                       // PCM header length
            bytes.putShort(1)                      // PCM, uncompressed
            bytes.putShort(CHANNELS.toShort())
            bytes.putInt(SAMPLE_RATE)
            bytes.putInt(byteRate)
            bytes.putShort((CHANNELS * BITS / 8).toShort())
            bytes.putShort(BITS.toShort())
            bytes.put("data".toByteArray())
            bytes.putInt(dataBytes)

            samples.forEach { v ->
                bytes.putShort((v.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
            }

            tmp.writeBytes(bytes.array())
            if (!tmp.renameTo(target)) {
                target.writeBytes(tmp.readBytes())
                tmp.delete()
            }
            true
        }.getOrElse {
            Log.e(TAG, "could not write $target", it)
            runCatching { tmp.delete() }
            false
        }
    }

    /**
     * Read a clip back as the floats whisper takes.
     *
     * Deliberately not a general WAV reader: it reads the files this object
     * wrote, so the format is known and the header is skipped rather than
     * parsed. A file that is not one of ours reads as noise, which is why
     * nothing outside this app can hand one in.
     */
    fun read(source: File): FloatArray? = runCatching {
        RandomAccessFile(source, "r").use { f ->
            val length = f.length() - HEADER_BYTES
            if (length <= 0) return null
            val raw = ByteArray(length.toInt())
            f.seek(HEADER_BYTES.toLong())
            f.readFully(raw)
            val shorts = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
        }
    }.getOrElse {
        Log.w(TAG, "could not read $source", it)
        null
    }

    /** How long [samples] lasts, in milliseconds. */
    fun durationMs(samples: Int): Long = samples * 1000L / SAMPLE_RATE

    /**
     * The picture of a clip: the loudest sample in each of [WAVE_BUCKETS]
     * slices, scaled 0..100.
     *
     * Peak rather than average, because an average of speech is a flat line at
     * about a tenth of full scale and reads as no waveform at all. Normalised
     * against the clip's own loudest moment so a quiet recording still draws a
     * full-height picture — the bars say where the words are, not how close to
     * clipping the microphone got.
     */
    fun wave(samples: FloatArray, buckets: Int = WAVE_BUCKETS): List<Int> {
        if (samples.isEmpty()) return emptyList()
        val per = (samples.size / buckets).coerceAtLeast(1)
        val peaks = FloatArray(buckets)
        var loudest = 0f
        for (b in 0 until buckets) {
            val from = b * per
            if (from >= samples.size) break
            val to = minOf(from + per, samples.size)
            var peak = 0f
            for (i in from until to) {
                val m = abs(samples[i])
                if (m > peak) peak = m
            }
            peaks[b] = peak
            if (peak > loudest) loudest = peak
        }
        if (loudest <= 1e-5f) return List(buckets) { 0 }
        return peaks.map { (it / loudest * 100f).roundToInt().coerceIn(0, 100) }
    }
}
