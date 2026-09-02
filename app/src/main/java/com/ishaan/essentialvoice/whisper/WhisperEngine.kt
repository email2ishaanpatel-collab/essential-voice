package com.ishaan.essentialvoice.whisper

import android.content.Context
import android.util.Log
import com.ishaan.essentialvoice.Prefs
import com.ishaan.essentialvoice.voice.SAMPLE_RATE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the one whisper context in the process.
 *
 * Loading a model costs 1–3 seconds and hundreds of MB, so it is loaded on the
 * *hold* rather than on release — the load overlaps the sentence being spoken and
 * is never felt — and dropped again after a spell of not being used.
 */
object WhisperEngine {

    private const val TAG = "EVEngine"

    private val lock = Mutex()

    @Volatile private var ptr: Long = 0L
    /**
     * The file name of the resident model, not the tier id.
     *
     * Two things share one file — Accurate and Maximum differ only in beam
     * settings, which are transcribe-time — and one tier now names two files,
     * one per language variant. The file name is the only key that is right in
     * both directions: it reloads when the language changes and does not
     * reload when it need not.
     */
    @Volatile private var loadedFile: String? = null
    @Volatile private var lastUsedAt: Long = 0L

    /** False on a CPU too old for the instructions this build was compiled with. */
    val isSupported: Boolean get() = WhisperLib.isSupported

    fun systemInfo(): String =
        if (!WhisperLib.ensureLoaded()) "unsupported CPU"
        else runCatching { WhisperLib.nativeSystemInfo() }.getOrElse { "unavailable" }

    fun defaultThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /**
     * The audio context to run the encoder over for a clip of [samples] length.
     *
     * whisper pads every clip to thirty seconds and then encodes all fifteen
     * hundred frames of it, so a two-second dictation costs the same as a
     * half-minute one — most of it spent encoding silence. Fifty frames buy a
     * second of audio; the rest is margin, because cutting the context to
     * exactly the audio starts costing words at the very end of a sentence.
     *
     * Clamped below at a third of the window: the encoder is trained on thirty
     * seconds and a very short context is where accuracy actually suffers, and
     * a tiny clip is cheap either way.
     */
    fun audioCtxFor(samples: Int, marginSeconds: Float = 2f): Int {
        val seconds = samples.toFloat() / SAMPLE_RATE + marginSeconds
        return (seconds * FRAMES_PER_SECOND).toInt().coerceIn(MIN_AUDIO_CTX, FULL_AUDIO_CTX)
    }

    /** whisper's own numbers: 1500 frames of audio context, thirty seconds. */
    private const val FULL_AUDIO_CTX = 1500
    private const val FRAMES_PER_SECOND = 50
    private const val MIN_AUDIO_CTX = 500

    /**
     * A word list handed to whisper as preceding context, to bias it toward the
     * two words this app treats as commands.
     *
     * "Gemini" is a proper noun `tiny.en` has never been trained to expect, and
     * on this phone it came back as "Jabinai", "Jibni", "JEBNE", "J.B.I.",
     * "Jim and I" and "Germany" — wrong about half the time. A command word the
     * model cannot hear is not a command, and no amount of pattern-matching on
     * the far side fixes that; the fix has to be at the decoder.
     *
     * whisper's `initial_prompt` is exactly that. The cost is worth stating
     * plainly: priming a word makes it slightly likelier to appear when it was
     * not said. That is tolerable here because the prefix only fires as the
     * **first** word, so a spurious "Gemini" mid-sentence changes nothing.
     *
     * Kept to the command words only. Every word added here is a word the model
     * is nudged toward inventing, so this is not a place to park vocabulary.
     * "Task" and "Record" earn their place the same way "Note" does: they are
     * the words the probe listens for in the opening of a sentence, and a
     * command word the model cannot hear is not a command.
     */
    private const val VOCAB_PROMPT = "Gemini. Note. Task. Record."

    /**
     * Load the configured tier if it is not already resident. Safe to call from
     * anywhere; concurrent callers queue on the same load.
     */
    suspend fun warm(context: Context): Boolean = withContext(Dispatchers.Default) {
        val settings = Prefs.get(context).now
        val tier = settings.tier
        val variant = tier.model
        if (!WhisperLib.ensureLoaded()) {
            Log.e(TAG, "native library unavailable on this CPU")
            return@withContext false
        }
        lock.withLock {
            if (ptr != 0L && loadedFile == variant.fileName) {
                lastUsedAt = System.currentTimeMillis()
                return@withLock true
            }
            unloadLocked()
            if (!variant.isInstalled(context)) {
                Log.w(TAG, "${variant.fileName} not downloaded")
                return@withLock false
            }
            val t0 = System.currentTimeMillis()
            val p = WhisperLib.nativeInit(variant.file(context).absolutePath, false)
            if (p == 0L) {
                Log.e(TAG, "nativeInit returned null for ${variant.fileName}")
                return@withLock false
            }
            ptr = p
            loadedFile = variant.fileName
            lastUsedAt = System.currentTimeMillis()
            Log.i(TAG, "loaded ${variant.fileName} in ${System.currentTimeMillis() - t0}ms")
            true
        }
    }

    /**
     * Blocking transcription. [audio] must be 16kHz mono float in -1..1.
     *
     * [audioCtx] is the encoder window in frames, 0 for whisper's full thirty
     * seconds; [singleSegment] stops the decoder after the first segment, which
     * is all the note probe ever looks at.
     */
    suspend fun transcribe(
        context: Context,
        audio: FloatArray,
        audioCtx: Int = audioCtxFor(audio.size),
        singleSegment: Boolean = false,
        threads: Int = defaultThreads(),
    ): Result<String> =
        withContext(Dispatchers.Default) {
            val settings = Prefs.get(context).now
            val tier = settings.tier

            if (!warm(context)) {
                val what = "The ${tier.label} model"
                return@withContext Result.failure(
                    IllegalStateException("$what is not downloaded yet")
                )
            }

            lock.withLock {
                val p = ptr
                if (p == 0L) return@withLock Result.failure(IllegalStateException("Model unloaded"))

                // English, stated rather than detected, and stated as a
                // constant rather than read from the setting. Every tier here
                // is an `.en` build — see [ModelCatalog] — so English is not
                // the language that happens to be chosen, it is the only one
                // the loaded model has weights for. Passing the preference
                // instead would let one stale pair of settings ask an
                // English-only model for Hindi, which is not a worse transcript
                // but a different alphabet. Non-English is Google's; the
                // pairing is kept in [com.ishaan.essentialvoice.Prefs].
                val text = runCatching {
                    WhisperLib.nativeTranscribe(
                        p, audio, threads, Languages.whisperCode(Languages.DEFAULT), false,
                        tier.beamSize, tier.bestOf, 0.6f, VOCAB_PROMPT,
                        audioCtx, singleSegment,
                    )
                }.getOrElse { return@withLock Result.failure(it) }

                lastUsedAt = System.currentTimeMillis()
                Result.success(text.trim())
            }
        }

    fun abort() = runCatching { WhisperLib.nativeAbort(true) }

    /** Drop the model if it has gone unused for longer than the configured window. */
    suspend fun unloadIfIdle(context: Context) {
        val window = Prefs.get(context).now.idleUnloadSeconds
        if (window <= 0) return
        lock.withLock {
            if (ptr == 0L) return@withLock
            if (System.currentTimeMillis() - lastUsedAt < window * 1000L) return@withLock
            Log.i(TAG, "unloading idle model")
            unloadLocked()
        }
    }

    suspend fun unload() = lock.withLock { unloadLocked() }

    private fun unloadLocked() {
        if (ptr != 0L) {
            WhisperLib.nativeFree(ptr)
            ptr = 0L
            loadedFile = null
            // Freeing without this leaves the arenas charged to our RSS.
            runCatching { WhisperLib.nativeTrimHeap() }
        }
    }
}
