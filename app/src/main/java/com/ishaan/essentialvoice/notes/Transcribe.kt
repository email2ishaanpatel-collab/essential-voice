package com.ishaan.essentialvoice.notes

import android.content.Context
import android.util.Log
import com.ishaan.essentialvoice.voice.Audio
import com.ishaan.essentialvoice.voice.Dictation
import com.ishaan.essentialvoice.whisper.WhisperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Words for the recordings, found while nobody is waiting for them.
 *
 * A recording is saved the moment the key comes up, as audio and nothing else —
 * the card appears immediately and the clip is safe. Its transcript is a
 * separate, slower thing that happens afterwards, so that a minute of audio does
 * not put a minute of whisper between pressing the key and seeing the card. By
 * the time the library is opened the words are usually already there.
 *
 * **It always loses to a dictation.** The key is the app; a background decode
 * that made the next hold wait several seconds for the engine would have traded
 * the one thing that has to be instant for something nobody asked to watch. Two
 * things enforce that: nothing starts while [Dictation.isBusy], and a decode
 * already running is [WhisperEngine.abort]ed the moment a dictation begins,
 * losing that clip's work and picking it up again when the phone is quiet. The
 * abort flag is cleared by the next `nativeTranscribe`, so the dictation that
 * interrupted is unaffected.
 */
object Transcribe {

    private const val TAG = "EVTranscribe"

    /** How long to wait before looking again while a dictation is in progress. */
    private const val POLL_MS = 400L

    /**
     * A breath after a dictation ends before taking the engine back.
     *
     * The pill's card is still up and the user may well hold the key again to
     * add a line to it, which is the commonest thing they do next. Starting a
     * long decode into that gap is the one case the abort above exists to
     * handle, and not needing it is better than handling it.
     */
    private const val SETTLE_MS = 2_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** Set while a dictation wants the engine; cleared once it is over. */
    @Volatile private var yielding = false

    /**
     * Clips that failed this session, and are not to be retried until the next
     * launch.
     *
     * The commonest failure is the model not being downloaded yet, which will
     * not fix itself inside a loop. Kept in memory rather than written to the
     * store so that a phone which gets its model tomorrow simply tries again.
     */
    private val failed = mutableSetOf<Long>()

    /** The recording being decoded right now, for the row that says so. */
    private val _working = MutableStateFlow<Long?>(null)
    val working: StateFlow<Long?> = _working

    /**
     * True when there are clips waiting and nothing that can read them.
     *
     * Recording works on either engine — the microphone is the app's own the
     * moment the word is heard — but the *words* only ever come from whisper,
     * because Google's recogniser takes a live microphone and will not read a
     * file. So a phone set to Google with no model downloaded records
     * perfectly and can never transcribe, and a row that just said "waiting"
     * for ever would be the app quietly failing. The library says what is
     * missing instead.
     */
    private val _needsModel = MutableStateFlow(false)
    val needsModel: StateFlow<Boolean> = _needsModel

    /**
     * Look for anything still waiting, and work through it.
     *
     * Safe to call from anywhere and as often as you like: a sweep already
     * running is left alone. Called when a recording is saved, and again when
     * the library is opened — the second is what picks up a clip whose decode
     * was interrupted by the process being killed.
     */
    fun sweep(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            NoteStore.load(app)
            while (true) {
                val next = NoteStore.notes.value.firstOrNull {
                    it.isRecording && !it.transcribed && it.id !in failed
                } ?: break

                // Wait for the phone to be doing nothing else. This is also
                // where a clip that was interrupted comes back round to.
                while (Dictation.isBusy) delay(POLL_MS)
                delay(SETTLE_MS)
                if (Dictation.isBusy) continue
                yielding = false

                // Nothing to read it with. Stop rather than failing one clip
                // at a time, and say so; the sweep runs again when the library
                // is opened, which is after any download has finished.
                if (!com.ishaan.essentialvoice.Prefs.get(app).now.tier.isInstalled(app)) {
                    Log.i(TAG, "no model installed; ${next.id} and any others must wait")
                    _needsModel.value = true
                    break
                }
                _needsModel.value = false

                val file = NoteStore.audioFile(app, next)
                val samples = file?.let { Clip.read(it) }
                if (samples == null || samples.isEmpty()) {
                    // The audio is gone or unreadable. There is nothing to
                    // decode and never will be, so the row stops asking.
                    Log.w(TAG, "no readable audio for ${next.id}")
                    NoteStore.setTranscript(app, next.id, next.text)
                    continue
                }

                _working.value = next.id
                val started = System.currentTimeMillis()
                Audio.normalise(samples)
                val result = WhisperEngine.transcribe(app, samples)
                _working.value = null

                if (yielding) {
                    // The key was pressed. Whatever came back is a fragment of
                    // an aborted decode; drop it and take this clip again once
                    // the dictation is over.
                    Log.i(TAG, "yielded on ${next.id}; will retry")
                    continue
                }

                result.fold(
                    onSuccess = { text ->
                        val ms = System.currentTimeMillis() - started
                        Log.i(TAG, "recording ${next.id} decoded in ${ms}ms: \"$text\"")
                        NoteStore.setTranscript(app, next.id, text)
                    },
                    onFailure = { t ->
                        Log.w(TAG, "recording ${next.id} failed to decode", t)
                        failed += next.id
                    },
                )
            }
            _working.value = null
        }
    }

    /**
     * A dictation is starting: give the engine up.
     *
     * Called from [Dictation] rather than observed from here, because "the key
     * went down" has to be acted on in the same breath it happens — a poll
     * would leave a decode holding the engine's lock for however long the poll
     * interval is, which is exactly the stall this exists to prevent.
     */
    fun yieldToDictation() {
        if (_working.value == null) return
        yielding = true
        WhisperEngine.abort()
    }

    /** Nothing is waiting on the engine any more. Called when a dictation ends. */
    fun resume(context: Context) {
        yielding = false
        sweep(context)
    }
}
