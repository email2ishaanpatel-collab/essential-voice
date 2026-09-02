package com.ishaan.essentialvoice.notes

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * One recording playing, anywhere in the app.
 *
 * A single object rather than a player per row, because two clips playing over
 * each other is never what anybody meant, and because the same clip can be on
 * screen twice — in the card the pill grew into and in the library behind it.
 * Both watch [state], so starting playback in one place moves the progress bar
 * in the other.
 *
 * Everything here runs on the main thread. A MediaPlayer is not thread-safe and
 * both callers are UI, so the simplest correct answer is to say so and hold to
 * it rather than to synchronise.
 */
object Playback {

    private const val TAG = "EVPlayback"

    /** How often the position is republished while playing. */
    private const val TICK_MS = 60L

    private val main = Handler(Looper.getMainLooper())

    /**
     * What is playing and where it has got to.
     *
     * [id] is the library row. A null [state] means nothing is playing at all,
     * which is different from a paused clip — a paused one keeps its row and its
     * position so that pressing play again continues rather than restarts.
     */
    data class State(
        val id: Long,
        val positionMs: Int,
        val durationMs: Int,
        val playing: Boolean,
    ) {
        val fraction: Float
            get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    }

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    private var player: MediaPlayer? = null
    private var current: Long? = null

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            val playing = runCatching { p.isPlaying }.getOrDefault(false)
            publish(playing)
            if (playing) main.postDelayed(this, TICK_MS)
        }
    }

    /**
     * Play [note], or pause it if it is the one already playing.
     *
     * One entry point rather than play/pause/resume, because every caller is a
     * single button whose meaning is "the other thing to this".
     */
    fun toggle(context: Context, note: NoteStore.Note) {
        val file = NoteStore.audioFile(context, note) ?: return
        if (!file.exists()) {
            Log.w(TAG, "no audio at $file")
            return
        }
        if (current == note.id) {
            val p = player
            if (p != null) {
                if (runCatching { p.isPlaying }.getOrDefault(false)) {
                    runCatching { p.pause() }
                    main.removeCallbacks(ticker)
                    publish(false)
                } else {
                    runCatching { p.start() }
                    main.post(ticker)
                }
                return
            }
        }
        start(file, note.id)
    }

    private fun start(file: File, id: Long) {
        stop()
        val p = MediaPlayer()
        runCatching {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            p.setDataSource(file.absolutePath)
            p.prepare()
            p.setOnCompletionListener {
                // Back to the beginning and paused, not gone: the commonest
                // thing after listening to a clip is listening to it again.
                main.removeCallbacks(ticker)
                runCatching { it.seekTo(0) }
                publish(false)
            }
            p.start()
        }.onFailure {
            Log.w(TAG, "could not play $file", it)
            runCatching { p.release() }
            return
        }
        player = p
        current = id
        main.post(ticker)
    }

    /** Jump to a fraction of the clip, from a drag on the waveform. */
    fun seekTo(fraction: Float) {
        val p = player ?: return
        val duration = runCatching { p.duration }.getOrDefault(0)
        if (duration <= 0) return
        runCatching { p.seekTo((duration * fraction.coerceIn(0f, 1f)).toInt()) }
        publish(runCatching { p.isPlaying }.getOrDefault(false))
    }

    /**
     * Let go of the audio entirely.
     *
     * Called when the card closes, when a recording is deleted, and before
     * starting another clip. A MediaPlayer that is never released holds a codec
     * and an audio track open for the life of the process.
     */
    fun stop() {
        main.removeCallbacks(ticker)
        player?.let { p ->
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        player = null
        current = null
        _state.value = null
    }

    private fun publish(playing: Boolean) {
        val p = player ?: return
        val id = current ?: return
        val position = runCatching { p.currentPosition }.getOrDefault(0)
        val duration = runCatching { p.duration }.getOrDefault(0)
        _state.value = State(id, position, duration, playing)
    }
}
