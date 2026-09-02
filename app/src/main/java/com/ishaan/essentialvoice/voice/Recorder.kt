package com.ishaan.essentialvoice.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Microphone capture for one dictation. Records straight into a growing float
 * buffer at 16kHz so no resampling or file round-trip stands between the user
 * releasing the key and whisper starting.
 */
class Recorder(
    private val onLevel: (Float) -> Unit,
    /**
     * Capture ended by itself — the ceiling was reached, or the microphone
     * stopped answering.
     *
     * Delivered on the main thread. Without it, hitting the ceiling stopped the
     * worker and nothing else: the AudioRecord was never released and the
     * dictation that owned it stayed busy for ever, which is exactly what made
     * the Essential Key go dead until the process was restarted.
     */
    private val onEndedItself: () -> Unit = {},
) {

    private companion object {
        const val TAG = "EVRecorder"
        const val MAX_SECONDS = 90

        /** Consecutive failed reads that mean the microphone is not coming back. */
        const val ERROR_LIMIT = 50
    }

    private val main = Handler(Looper.getMainLooper())

    private var record: AudioRecord? = null
    @Volatile private var running = false
    private var worker: Thread? = null

    /**
     * The capture buffer, allocated on the first [start] rather than with the
     * object.
     *
     * It is 90 seconds of 16kHz float — 5.7MB — and this class is owned by the
     * accessibility service, which is resident all day. Allocating it in the
     * constructor meant the process carried 5.7MB from the first dictation
     * until it was killed, and [switchToRawCapture]-style retry loops paid it
     * again for every candidate they built and threw away.
     */
    private var buffer: FloatArray? = null
    @Volatile private var written = 0

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        // A recorder still holding the microphone is a capture that was never
        // closed out. Reusing it silently is what turned that into a dead key:
        // `written` was left where it was, so the next sentence was appended to
        // the last one in a buffer already near its ceiling. Close it properly
        // and start clean.
        if (running || record != null) stop()
        written = 0
        val buf = buffer ?: FloatArray(SAMPLE_RATE * MAX_SECONDS).also { buffer = it }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.e(TAG, "getMinBufferSize returned $minBuf")
            return false
        }

        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord construction failed", t)
            return false
        }

        if (r.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialised (state=${r.state})")
            r.release()
            return false
        }

        record = r
        running = true
        r.startRecording()

        worker = thread(name = "ev-mic", priority = Thread.MAX_PRIORITY) {
            val chunk = ShortArray(minBuf)
            var failures = 0
            while (running) {
                val n = r.read(chunk, 0, chunk.size)
                if (n <= 0) {
                    // A stopped or released AudioRecord returns an error code
                    // immediately, and for ever. Spinning on that burns a whole
                    // core at MAX_PRIORITY and the dictation never ends, so a
                    // run of them is treated as the microphone being gone.
                    if (n < 0 && ++failures >= ERROR_LIMIT) {
                        Log.w(TAG, "read kept returning $n; giving up")
                        running = false
                        main.post { onEndedItself() }
                    }
                    continue
                }
                failures = 0

                var peak = 0f
                var i = 0
                while (i < n && written < buf.size) {
                    val v = chunk[i] / 32768f
                    buf[written++] = v
                    val m = abs(v)
                    if (m > peak) peak = m
                    i++
                }
                onLevel(peak)
                if (written >= buf.size) {
                    Log.w(TAG, "hit the ${MAX_SECONDS}s ceiling, stopping")
                    running = false
                    // Stopping the loop is not stopping the dictation. This used
                    // to be the end of it: the AudioRecord stayed open, finish()
                    // never ran, and busy stayed set for the life of the process.
                    main.post { onEndedItself() }
                }
            }
        }
        return true
    }

    /**
     * A copy of the first [seconds] of what has been captured so far, or null
     * if that much has not been recorded yet.
     *
     * Safe to call from another thread while the mic thread is still writing:
     * it only ever reads below [written], and those samples are finished with.
     * This is what lets the app look for the word "note" in the opening of a
     * sentence without waiting for the sentence to end.
     */
    fun snapshot(minSeconds: Float, maxSeconds: Float): FloatArray? {
        // A range, not an exact length. Asking for exactly as much audio as the
        // probe waited for never works: the mic starts tens of milliseconds
        // after the key does, so at the 1.5s mark there is always slightly less
        // than 1.5s recorded, and an exact request returns null every time.
        val buf = buffer ?: return null
        val floor = (minSeconds * SAMPLE_RATE).toInt()
        val ceiling = (maxSeconds * SAMPLE_RATE).toInt()
        val have = written
        if (have < floor) return null
        return buf.copyOfRange(0, minOf(have, ceiling))
    }

    /** Stops capture and hands back exactly the samples recorded. */
    fun stop(): FloatArray {
        if (!running && record == null) return FloatArray(0)
        stopWorkerAndDevice()
        // `written` is read *after* the join, not before: the mic thread goes on
        // filling the buffer until it actually stops, and sampling the count
        // first would clip the last chunk off the end of every sentence.
        return buffer?.copyOf(written) ?: FloatArray(0)
    }

    fun release() {
        stopWorkerAndDevice()
        // Let the capture buffer go with the recorder. Keeping it would hold
        // 5.7MB in a process that is resident all day for the sake of an
        // allocation that costs a couple of milliseconds on the next hold.
        buffer = null
        written = 0
    }

    /**
     * Stop the reader thread, *then* the device — in that order, and waiting in
     * between.
     *
     * Releasing an AudioRecord while a `read()` is in flight on it is a native
     * crash, not an exception: the buffer the read is filling is freed
     * underneath it. [stop] always joined first; [release] did not, and it is
     * the one called from `Dictation.detach()` and from every candidate a
     * failed microphone hand-over throws away — which is exactly when a read is
     * most likely to be in flight.
     */
    private fun stopWorkerAndDevice() {
        running = false
        worker?.let { w ->
            // Long enough for a read of one buffer to return. A worker still
            // running after this is one blocked in the driver, and releasing
            // under it is the lesser of the two risks — the alternative is
            // hanging whichever thread called us, which is usually the main one.
            runCatching { w.join(500) }
            if (w.isAlive) Log.w(TAG, "mic thread did not stop in time")
        }
        worker = null
        record?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        record = null
    }
}
