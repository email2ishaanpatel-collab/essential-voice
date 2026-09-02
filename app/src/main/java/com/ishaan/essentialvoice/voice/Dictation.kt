package com.ishaan.essentialvoice.voice

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.widget.Toast
import com.ishaan.essentialvoice.Prefs
import com.ishaan.essentialvoice.Features
import com.ishaan.essentialvoice.ai.AskCommand
import com.ishaan.essentialvoice.ai.Gemini
import com.ishaan.essentialvoice.island.Island
import com.ishaan.essentialvoice.notes.Clip
import com.ishaan.essentialvoice.notes.NoteCommand
import com.ishaan.essentialvoice.notes.Playback
import com.ishaan.essentialvoice.notes.Transcribe
import com.ishaan.essentialvoice.speech.GoogleSpeech
import com.ishaan.essentialvoice.notify.Feed
import com.ishaan.essentialvoice.notes.NoteStore
import com.ishaan.essentialvoice.whisper.WhisperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One dictation, start to finish: the pill, the microphone and the transcript.
 *
 * Hosted by the accessibility service rather than a foreground service, which is
 * the whole reason this app has no permanent notification. The system binds an
 * accessibility service with BIND_FOREGROUND_SERVICE, so the process sits at a
 * uid state that is allowed to open the microphone — no foreground service, and
 * so no notification, required. If that ever stops being true the failure is
 * loud rather than silent: Android hands out digital silence instead of an
 * error, so a clip whose peak is exactly zero is reported as a blocked mic.
 */
object Dictation {

    private const val TAG = "EVDictation"

    /** How hard the frosted style blurs what is behind it. */
    private const val BLUR_RADIUS_PX = 56

    /**
     * When to look at the opening of the sentence for the word "note", and how
     * much of it to look at.
     *
     * whisper cannot transcribe live, so this runs a second, throwaway decode
     * over just the first second and a half *while the microphone is still
     * running*. Long enough to contain a first word said at any normal speed,
     * short enough that even the small model finishes it inside a second. If it
     * misses, nothing breaks: the full decode after release catches the word
     * anyway and the card opens then instead.
     */
    private const val PROBE_AFTER_MS = 1_250L
    private const val PROBE_MIN_SECONDS = 0.9f
    private const val PROBE_MAX_SECONDS = 2.2f

    /**
     * The probe gets a second go, a beat later, if the first heard something
     * that was not a note.
     *
     * A first word caught half-said is the failure that actually happens: the
     * microphone opens tens of milliseconds after the key does, whisper is
     * given a second of audio that starts mid-consonant, and a small model
     * answers confidently with something else entirely — "You know what?" for
     * "Notes, buy me milk". The retry sees nearly twice as much audio and the
     * whole word, and costs nothing when the first attempt already succeeded,
     * because then it never runs.
     */
    private const val PROBE_ATTEMPTS = 2
    private const val PROBE_RETRY_MS = 900L

    /**
     * How much encoder the probe is worth.
     *
     * It is a throwaway decode of a couple of seconds looking for one word, so
     * it runs on half the encoder window and stops after the first segment.
     * Left at whisper's default it would cost as much as the real
     * transcription that follows — the same thirty seconds of encoder, on every
     * hold, for a feature used in a handful of them. Cut further, to a quarter,
     * it started getting the word wrong, which is worse than free.
     */
    private const val PROBE_AUDIO_CTX = 768
    private const val PROBE_THREADS = 2

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val ENTER: Interpolator = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    private val EXIT: Interpolator = PathInterpolator(0.5f, 0f, 0.9f, 0.2f)

    private var app: Context? = null
    private var wm: WindowManager? = null
    private var prefs: Prefs? = null

    private var pill: PillView? = null

    /**
     * What the window manager actually holds.
     *
     * The overlay used to be the [PillView] on its own. It needs to be a group
     * now, because a note that can be typed into needs a real text field over
     * it — a Canvas cannot host a caret, an IME connection or a selection
     * handle, and reimplementing those on top of a custom view is how you end
     * up with a text field that is subtly wrong in nine ways.
     */
    private var host: FrameLayout? = null

    /** The field laid over the card's body while a note is being edited. */
    private var editor: EditText? = null

    /** True between a tap on the note's text and the keyboard going away. */
    private var editing = false

    private var params: WindowManager.LayoutParams? = null
    private var slideAnim: ValueAnimator? = null
    private var attached = false

    private var recorder: Recorder? = null

    // Both of these carry state the island has to mirror, and both are assigned
    // from a dozen places between begin(), end(), cancel(), finish() and
    // detach(). A custom setter is what makes "tell the island" impossible to
    // forget at a new assignment site — hooking the call sites by hand would
    // have gone stale the first time one was added.
    private var capturing = false
        set(value) { field = value; notifyActivity() }
    private var busy = false
        set(value) { field = value; notifyActivity() }
    private var work: Job? = null
    private var idleJob: Job? = null

    /**
     * The note the card is currently open on, or null when the pill is a pill.
     *
     * A note lasts one hold. Saying "note buy milk" opens the card, saves it
     * and closes; releasing the key is the end of the note, not the start of a
     * mode you then have to get out of.
     *
     * The one exception is the bare word "note", which opens a card with
     * nothing in it. That one waits for the next hold, because a note with no
     * words in it is not worth keeping and there is nothing else it could mean.
     */
    private var openNoteId: Long? = null
    private var probeJob: Job? = null

    /**
     * Which of the three the open card is, and which the badge announced.
     *
     * Held across a hold rather than re-derived, because the chained holds that
     * add to an open card do not repeat the command word — "note buy milk",
     * release, "and bread" — so after the first sentence the kind exists only
     * here.
     */
    private var openKind: NoteStore.Kind = NoteStore.Kind.NOTE

    /** True while the card on screen is a clip rather than words. */
    private var recordCard = false

    /**
     * The hold's audio, kept only long enough to find out whether it was a
     * recording.
     *
     * The fast path never uses it — a hold the probe recognised as a recording
     * skips the transcription entirely and goes straight from the microphone to
     * the card. This is the slow path: the probe missed the word, the whole
     * clip was decoded to find it, and the samples that produced that
     * transcript are the recording. Cleared the moment it is answered either
     * way, because it is megabytes.
     */
    private var pendingAudio: FloatArray? = null

    /** Feeds the card's waveform from whatever is playing. */
    private var playbackJob: Job? = null

    /**
     * The Google recogniser's dictation, when that is the engine.
     *
     * Null on whisper, and null again the moment a result lands. Never non-null
     * at the same time as [recorder] is running: two things cannot hold the
     * microphone.
     */
    private var google: GoogleSpeech.Session? = null

    /**
     * Whether the window is card-shaped. Separate from [openNoteId] because the
     * card now opens *during* the hold, before there is any text to put in a
     * note — the probe knows it is a note long before the words exist.
     */
    private var cardOpen = false
    private var badgeOpen = false

    val isListening: Boolean get() = busy && capturing

    /** Listening, or transcribing what was just heard. */
    val isBusy: Boolean get() = busy

    /**
     * The surfaces following along, by name.
     *
     * There used to be one nullable callback here, because the island was the
     * only thing watching. There are two now — the island and the bottom bar —
     * and a single slot is the kind of thing that works until the day both are
     * switched on and the second one to attach silently unhooks the first. Keyed
     * rather than a list so that attaching twice, which the service does every
     * time the system rebinds it, replaces a registration instead of stacking
     * another copy of it.
     *
     * Still plain callbacks rather than a flow: every consumer is a View that
     * has to be touched on the main thread anyway, which is where [notifyActivity]
     * delivers them.
     */
    private val watchers =
        java.util.concurrent.ConcurrentHashMap<String, (Boolean, Boolean) -> Unit>()
    private val levelWatchers =
        java.util.concurrent.ConcurrentHashMap<String, (Float) -> Unit>()

    /**
     * Follow this dictation: [onActivity] whenever one starts, finishes, or moves
     * between listening and transcribing, and [onLevel] for the microphone level
     * on every buffer.
     */
    fun watch(
        key: String,
        onActivity: ((busy: Boolean, listening: Boolean) -> Unit)? = null,
        onLevel: ((Float) -> Unit)? = null,
    ) {
        if (onActivity != null) watchers[key] = onActivity else watchers.remove(key)
        if (onLevel != null) levelWatchers[key] = onLevel else levelWatchers.remove(key)
    }

    fun unwatch(key: String) {
        watchers.remove(key)
        levelWatchers.remove(key)
    }

    /**
     * Suppress the pill for the next dictation.
     *
     * Set by the island when the dictation was started by tapping it, and by the
     * bottom bar when it has claimed one: that surface then shows the level
     * itself, and a pill appearing somewhere else on screen would be a second
     * surface narrating the same sentence. Cleared here rather than by the
     * caller, so a suppressed dictation cannot leak into the next one started by
     * the key.
     */
    @Volatile
    var suppressPill = false

    private fun notifyActivity() {
        if (watchers.isEmpty()) return
        val b = busy
        val l = busy && capturing
        main.post { watchers.values.forEach { it(b, l) } }
    }

    /** Called by the accessibility service once the system has bound it. */
    fun attach(context: Context) {
        val c = context.applicationContext
        app = c
        wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = Prefs.get(c)
        // Read once, here, so the first dictation of the day already knows the
        // full language tag to ask for. Without it the first one falls back to
        // whisper's bare code, which the recogniser refuses outright — and the
        // catalogue would only have been filled by somebody opening the
        // language picker. One query, and it answers for all hundred languages.
        GoogleSpeech.refreshCatalogue(c)
        Log.i(TAG, "ready — ${WhisperEngine.systemInfo()}")
    }

    fun detach() {
        disarmWatchdog()
        work?.cancel()
        idleJob?.cancel()
        probeJob?.cancel()
        slideAnim?.cancel()
        google?.cancel()
        google = null
        recorder?.release()
        recorder = null
        capturing = false
        busy = false
        host?.let { h -> if (attached) runCatching { wm?.removeViewImmediate(h) } }
        attached = false
        pill = null
        host = null
        editor = null
        editing = false
        app = null
    }

    val isReady: Boolean get() = app != null

    // ---- the gesture -------------------------------------------------------

    /**
     * Start a dictation. Returns whether one actually started.
     *
     * The answer matters: [com.ishaan.essentialvoice.trigger.EssentialKeyService]
     * marks the key as held *before* calling this, and a refusal here used to
     * leave it believing it owned a dictation it had never started.
     */
    fun begin(): Boolean {
        val ctx = app ?: return false
        val prefs = prefs ?: return false
        if (busy) return false

        if (!Settings.canDrawOverlays(ctx)) {
            toast("Essential Voice needs \"draw over other apps\"")
            return false
        }

        // Never open the microphone with nothing on screen to close it with.
        //
        // suppressPill is set by whichever surface is about to take the
        // dictation — the bar, or the island — and cleared in finish(). Both of
        // those callers guard their claim with `if (!isBusy)`, so a toggle that
        // arrives during the few hundred milliseconds finish() spends animating
        // sets no claim at all, while the flag left over from the *previous*
        // dictation is still standing. The result was a capture with no pill and
        // no bar: nothing to tap, and the key dead behind `if (busy)` above,
        // until the process was restarted. If nothing owns this one, the pill is
        // the way out.
        val owned = (Features.ISLAND && Island.hasClaim) || Bar.hasClaim
        if (suppressPill && !owned) {
            Log.w(TAG, "no surface claimed this dictation; showing the pill")
            suppressPill = false
        }

        // A background decode must never be what the key is waiting for.
        Transcribe.yieldToDictation()

        // A clip's card is not something you talk into: it has no body, and its
        // words are being found in the background rather than dictated. Holding
        // the key again means "the next thing", so the recording is kept and the
        // card gets out of the way — synchronously, because the pill is about to
        // reuse the window it is standing in.
        if (recordCard) dismissRecordCard(save = true)

        // A dictation that is not going into an open card starts with no
        // opinion about what it is. Without this, one "task …" would make every
        // plain note after it a task.
        if (!cardOpen) openKind = NoteStore.Kind.NOTE

        // Speaking into a card that is being typed into: keep the typing, put
        // the keyboard away, and let the new line join what is already there.
        // Losing it because the key was held would be the worst thing this
        // screen could do.
        if (editing) endEditing(commit = true)

        // A card already on screen stays where it is: sliding it out and back
        // for every line would be pure noise.
        val p = when {
            suppressPill -> null
            cardOpen && attached -> pill
            else -> attachPill()
        }
        if (p == null && !suppressPill) return false
        busy = true
        p?.reset(PillView.State.LISTENING)
        // Armed for the length of the dictation and disarmed by finish(), so the
        // pill only eats touches while there is something a swipe could mean.
        p?.onSwipeAway = { cancel() }
        tick(18)

        // Google's recogniser records for itself, so this is a fork rather than
        // a swap: the two must never have the microphone at the same time, and
        // everything below — the Recorder, the warm-up, the note probe — is
        // whisper's half of it. See [GoogleSpeech].
        if (prefs.now.engine == Prefs.ENGINE_GOOGLE) {
            segments.clear()
            restarts = 0
            holdingKey = true
            if (!beginGoogle(ctx)) {
                holdingKey = false
                return false
            }
            capturing = true
            armWatchdog()
            return true
        }

        val rec = recorder ?: Recorder(
            // The microphone hands over a raw peak, and speech peaks well below
            // full scale — so the useful range is mapped onto 0..1 here, once,
            // before anything draws it. Google's session does its own shaping;
            // everything downstream now agrees that a level is a 0..1 figure.
            onLevel = { peak -> emitLevel(kotlin.math.min(1f, peak / 0.35f)) },
            // The ceiling, or a microphone that stopped answering. Either way the
            // capture is over and only this object can close the dictation out.
            onEndedItself = { endedByItself() },
        ).also { recorder = it }
        if (!rec.start()) {
            finish(PillView.State.ERROR, "The microphone could not be opened")
            return false
        }
        capturing = true
        armWatchdog()

        // Loading costs a couple of hundred milliseconds; overlap it with the
        // sentence rather than making the user wait for it after they stop.
        work = scope.launch { withContext(Dispatchers.Default) { WhisperEngine.warm(ctx) } }

        // No pill means no card to open a note into, so there is nothing for the
        // probe to do but cost an extra decode.
        if (!suppressPill && !cardOpen && !badgeOpen) startNoteProbe(ctx)
        return true
    }

    /**
     * Start a dictation on Android's recogniser.
     *
     * There is no warm-up and no [startNoteProbe] here. Both exist because
     * whisper cannot tell you anything until it has finished; this engine sends
     * partial results as the sentence is being said, which is the same thing the
     * probe was faking at the cost of a whole extra decode.
     */
    private fun beginGoogle(ctx: Context): Boolean {
        val settings = prefs?.now ?: return false
        google?.cancel()

        // What the recogniser said about this language last time it was asked.
        // A dictation cannot wait for that query — the round trip would sit
        // between the key going down and the microphone opening — so an
        // unanswered one starts the query for next time and this one goes out
        // on a best guess. The settings screen asks as soon as the engine is
        // switched on, so in practice this is answered long before the key is.
        if (GoogleSpeech.known(settings.language) == null) {
            GoogleSpeech.check(ctx, settings.language) {}
        }
        // The tag and the recogniser come back together, and they have to:
        // asking one recogniser for the other one's tag is LANGUAGE_UNAVAILABLE
        // every time. See [GoogleSpeech.choose].
        val choice = GoogleSpeech.choose(settings.language)
        val session = GoogleSpeech.Session(
            context = ctx,
            languageTag = choice.tag,
            onDevice = choice.onDevice,
            // "Prefer offline" is really "refuse rather than go online", which
            // is why it is a setting and not a default. See GoogleSpeech.
            preferOffline = !settings.googleOnline,
            onLevel = { level -> emitLevel(level) },
            onPartial = { partial ->
                // The note card, opened from words that have actually been
                // heard rather than from a guess at the opening of the clip.
                val hit = if (capturing && !badgeOpen && !cardOpen) NoteCommand.parse(partial)
                else null
                if (hit != null) {
                    Log.i(TAG, "${hit.kind} heard in the opening: \"$partial\"")
                    enterBadge(hit.kind)
                    // Google's recogniser hands back words and never audio, so
                    // on this engine a recording has to be captured ourselves.
                    // The hand-over happens here, mid-hold, the moment the word
                    // is heard — everything before it is the word "record"
                    // being said, which is the one part of the clip nobody
                    // wants anyway.
                    if (hit.kind == NoteStore.Kind.RECORDING) switchToRawCapture(ctx)
                }
            },
            onDone = { result -> onGoogleResult(result) },
        )
        google = session
        if (!session.start()) {
            finish(PillView.State.ERROR, "Google's recogniser would not start")
            return false
        }
        return true
    }

    /**
     * The recogniser answered — which may be *before* the key was released.
     *
     * Its silence timeouts are hints and it is free to decide a sentence ended.
     * When that happens mid-hold the words are still the words, so this closes
     * the dictation out with them rather than throwing them away; the key
     * release that follows lands on `if (!busy || !capturing)` in [end] and
     * does nothing, which is the correct nothing.
     */
    private fun onGoogleResult(result: Result<String>) {
        val ctx = app ?: return
        if (!busy) return

        // The recogniser stops when *it* thinks the sentence ended, which is not
        // the same question as whether the key is still down. It ends a hold on
        // a pause for thought, and it ends one on a moment of quiet before the
        // first word — and either way this used to be the end of the dictation,
        // so the pill closed under a finger that was still pressing it.
        //
        // While the key is held, an ending is a *segment* ending. There is no
        // way to ask this engine to hold the microphone open indefinitely — the
        // silence extras that claim to are hints, and the one this phone
        // honoured closed the microphone 500ms in — so listening again is what
        // "keep listening" has to mean here.
        //
        // [GoogleSpeech.Session] does that itself now, on its own binding, so
        // reaching this point mid-hold means that failed: the recogniser
        // service went away rather than the sentence ending. What is left here
        // is the heavier recovery — a whole new session, new binding and all —
        // with the words so far kept.
        val text = result.getOrNull()
        // Whether this answer has already been filed as a segment. The restart
        // path below files it *before* it knows whether a restart is possible,
        // and when one is not the code falls through to the join at the bottom —
        // which used to add the same words a second time. A hold whose last
        // segment failed to restart ended "…and bread and bread".
        var banked = false
        if (holdingKey) {
            if (!text.isNullOrBlank()) {
                segments += text.trim()
                banked = true
                Log.i(TAG, "google segment: \"$text\"")
            }
            val fatal = (result.exceptionOrNull() as? GoogleSpeech.SpeechError)?.let { it.code !in RETRYABLE }
            if (fatal != true && ++restarts <= MAX_RESTARTS) {
                google = null
                if (beginGoogle(ctx)) return
            }
        }

        val wasCapturing = capturing
        capturing = false
        google = null
        holdingKey = false
        if (wasCapturing) {
            pill?.morphTo(PillView.State.THINKING)
            tick(10)
        }
        val last = if (banked) null else text?.trim()?.ifBlank { null }
        val whole = (segments + listOfNotNull(last))
            .filter { it.isNotBlank() }
            .joinToString(" ")
        segments.clear()
        restarts = 0
        work = scope.launch {
            if (whole.isNotBlank()) {
                Log.i(TAG, "google heard: \"$whole\"")
                handleTranscript(ctx, whole)
            } else {
                val t = result.exceptionOrNull()
                Log.w(TAG, "google failed", t)
                finish(PillView.State.ERROR, t?.message)
            }
        }
    }

    /**
     * Take the microphone off Google's recogniser and record it ourselves.
     *
     * Only ever called from a partial result, so the key is still down and the
     * hold has somewhere to go. From here on this dictation is indistinguishable
     * from a whisper one: [google] is null, [recorder] is running, and [end]
     * falls through to the path that already knows what to do with a clip.
     *
     * The retry is not optimism. `SpeechRecognizer.cancel` releases the
     * microphone asynchronously, and an AudioRecord constructed in the same
     * breath can be handed a device that is still held — which fails at
     * construction rather than returning silence, so it is visible and worth
     * waiting a beat for.
     */
    private fun switchToRawCapture(ctx: Context) {
        google?.cancel()
        google = null
        holdingKey = false
        segments.clear()
        restarts = 0

        scope.launch {
            var rec: Recorder? = null
            // A labelled loop rather than `repeat`, because the exit here has to
            // be a *break*. `return@repeat` is a continue: it went on building
            // three more Recorders after one had already taken the microphone,
            // and each one that started overwrote `rec` and left its predecessor
            // holding the device with nothing left pointing at it to release it.
            attempts@ for (attempt in 0 until 4) {
                if (!capturing) return@launch
                if (attempt > 0) delay(120)
                val candidate = Recorder(
                    onLevel = { peak -> emitLevel(kotlin.math.min(1f, peak / 0.35f)) },
                    onEndedItself = { endedByItself() },
                )
                if (candidate.start()) { rec = candidate; break@attempts }
                candidate.release()
            }
            val started = rec
            if (started == null) {
                Log.w(TAG, "could not take the microphone back from the recogniser")
                finish(PillView.State.ERROR, "The microphone could not be opened")
                return@launch
            }
            recorder = started
            Log.i(TAG, "recording on our own microphone")
        }
    }

    /**
     * Set while the key that started a Google dictation is still down.
     *
     * Only the Google path needs it: whisper's [Recorder] holds the microphone
     * until it is told to let go, so nothing there can end a dictation early.
     */
    private var holdingKey = false

    /** The segments this hold has collected so far, oldest first. */
    private val segments = mutableListOf<String>()

    private var restarts = 0

    /**
     * Listen for "note" at the front of the sentence, while it is still being
     * said, and open the card the moment it is heard.
     *
     * Deliberately a whole second decode thrown away: it shares the model that
     * is already warm, so it costs CPU for a fraction of a second and nothing
     * else. Every exit here is silent — a probe that fails, times out or
     * disagrees must never affect the real transcription that follows.
     */
    private fun startNoteProbe(ctx: Context) {
        probeJob?.cancel()
        probeJob = scope.launch {
            repeat(PROBE_ATTEMPTS) { attempt ->
                delay(if (attempt == 0) PROBE_AFTER_MS else PROBE_RETRY_MS)
                if (!capturing || badgeOpen || cardOpen) return@launch

                val snap = recorder?.snapshot(PROBE_MIN_SECONDS, PROBE_MAX_SECONDS)
                if (snap == null) {
                    Log.i(TAG, "probe: not enough audio yet")
                    return@repeat
                }

                val prepared = withContext(Dispatchers.Default) {
                    Audio.normalise(snap)
                    Audio.padTo(snap, 1.1f)
                }
                val heard = WhisperEngine.transcribe(
                    ctx,
                    prepared,
                    audioCtx = PROBE_AUDIO_CTX,
                    singleSegment = true,
                    threads = PROBE_THREADS,
                ).getOrNull() ?: return@repeat
                Log.i(TAG, "probe ${attempt + 1}/$PROBE_ATTEMPTS heard: \"$heard\"")

                // The key may have been released while that ran. Opening the
                // card after the fact is the job of the full transcription,
                // not this.
                if (!capturing || badgeOpen || cardOpen) return@launch
                val hit = NoteCommand.parse(heard) ?: return@repeat

                Log.i(TAG, "${hit.kind} heard in the opening: \"$heard\"")
                enterBadge(hit.kind)
                return@launch
            }
        }
    }


    /**
     * What a finished transcript means, whichever engine produced it.
     *
     * Lifted out of [end] when Google's recogniser became a second engine.
     * Every branch below is about the *words*, not about how they were heard,
     * and having two copies of this would be two places for the note command to
     * drift apart.
     */
    private suspend fun handleTranscript(ctx: Context, text: String) {
        // "note ..." starts one; once the card is open everything lands in it
        // until it is closed.
        val hit = NoteCommand.parse(text)
        // Checked before the note card, and after it is open the ask is
        // deliberately unreachable: with a note on screen every word belongs in
        // it, and a question that escaped into the network from inside a note
        // would be the worst kind of surprise. See AskCommand for why the
        // prefix is anchored.
        val asAsk = if (cardOpen || !Features.GEMINI) null else AskCommand.parse(text)
        when {
            text.isBlank() -> finish(PillView.State.ERROR, null)
            // The probe missed the word and the whole clip was decoded to find
            // it. The samples behind that transcript are the recording, and the
            // transcript itself is the one this clip would otherwise have had
            // to be decoded a second time for — so it is written straight in
            // and the background pass never sees this row.
            hit?.kind == NoteStore.Kind.RECORDING -> {
                val audio = pendingAudio
                pendingAudio = null
                if (audio == null) finish(PillView.State.ERROR, "There was nothing to record")
                else takeRecording(ctx, audio, hit.body)
            }
            hit != null -> takeNote(ctx, hit.body, hit.kind)
            asAsk != null -> askGemini(ctx, asAsk)
            cardOpen -> takeNote(ctx, text, openKind)
            deliver(ctx, text) -> finish(PillView.State.DONE, null)
            else -> finish(
                PillView.State.ERROR,
                "Nowhere to type that, so it is on the clipboard",
            )
        }
    }

    fun end(heldMs: Long = Long.MAX_VALUE) {
        val ctx = app ?: return
        val prefs = prefs ?: return
        Log.i(TAG, "end(heldMs=$heldMs) busy=$busy capturing=$capturing google=${google != null}")
        if (!busy || !capturing) return

        // On Google's engine the transcript arrives through onGoogleResult, so
        // all this does is ask for it. capturing stays set until it lands, so
        // that a second release cannot ask twice.
        val session = google
        if (session != null) {
            if (heldMs < 350) {
                google = null
                capturing = false
                holdingKey = false
                segments.clear()
                session.cancel()
                finish(PillView.State.ERROR, null)
                return
            }
            // The pill has to say "thinking" the moment the key comes up, and
            // capturing has to drop with it: it is what `isListening` is made
            // of, so the island and the bar would otherwise sit there claiming
            // the microphone was still open. It is also what makes a second
            // release fall out of the guard above instead of asking twice.
            // The key is up, so the next answer is the last one. Everything
            // collected so far is kept and the final segment joins it.
            holdingKey = false
            capturing = false
            session.stop()
            pill?.morphTo(PillView.State.THINKING)
            tick(10)
            return
        }

        val rec = recorder ?: return
        val audio = rec.stop()
        capturing = false
        probeJob?.cancel()

        val seconds = audio.size.toFloat() / SAMPLE_RATE
        // A key held for less than this was a fumble, not a sentence.
        if (heldMs < 350 || seconds < 0.25f) {
            finish(PillView.State.ERROR, null)
            return
        }

        // Android hands a blocked recorder digital silence rather than an error,
        // so an exactly-zero peak is the signature of a permission problem, not
        // of a quiet room.
        if (Audio.peak(audio) == 0f) {
            finish(PillView.State.ERROR, "The microphone returned silence — check its permission")
            return
        }

        // The probe already heard "record", so there is nothing to transcribe
        // before the card can open: the clip *is* the answer. Skipping whisper
        // here is the whole reason the card appears the instant the key comes
        // up rather than however long a minute of audio takes to decode. Its
        // words are found afterwards, by [Transcribe].
        if (badgeOpen && openKind == NoteStore.Kind.RECORDING) {
            pendingAudio = null
            tick(24)
            takeRecording(ctx, audio, null)
            return
        }

        pill?.morphTo(PillView.State.THINKING)
        tick(10)

        // Kept for the case where the transcript turns out to begin with
        // "record" after all. Handed over or dropped in handleTranscript; the
        // failure paths below go through finish(), which clears it.
        pendingAudio = audio

        work = scope.launch {
            val started = System.currentTimeMillis()
            val prepared = withContext(Dispatchers.Default) {
                Audio.normalise(audio)
                Audio.padTo(audio, 1.1f)
            }

            WhisperEngine.transcribe(ctx, prepared).fold(
                onSuccess = { text ->
                    val ms = System.currentTimeMillis() - started
                    Log.i(TAG, "transcribed ${"%.1f".format(seconds)}s in ${ms}ms: \"$text\"")
                    handleTranscript(ctx, text)
                },
                onFailure = { t ->
                    Log.w(TAG, "transcribe failed", t)
                    finish(PillView.State.ERROR, t.message)
                },
            )
        }
    }

    /**
     * One shaped 0..1 level, to the pill and to whatever else is watching.
     *
     * Dropped unless a microphone is actually open. The pill moves one dot per
     * level it is handed, so a level from anywhere other than *this* dictation
     * does not brighten the row, it speeds it up — and the sources are engines
     * with their own lifetimes, one of which is a self-reposting tick. This is
     * the invariant rather than a fix for any one of them: no capture, no
     * levels, whatever is still holding a callback.
     */
    private fun emitLevel(level: Float) {
        if (!capturing) return
        pill?.pushLevel(level)
        levelWatchers.values.forEach { it(level) }
    }

    fun toggle() { if (isListening) end() else begin() }

    // ---- the backstop ------------------------------------------------------

    /**
     * The longest a single dictation may hold the microphone before this object
     * closes it out itself.
     *
     * Comfortably past [Recorder]'s own ninety-second ceiling, because the
     * ceiling is the ordinary way a runaway capture ends and this is only for
     * the case where even that did not put the state back.
     */
    private const val WATCHDOG_MS = 120_000L

    /**
     * How many times a held Google dictation may start listening again.
     *
     * A ceiling rather than a limit anybody will meet: each segment is a
     * sentence or a pause, so thirty of them is a very long hold, and the point
     * of the number is that a recogniser failing instantly in a loop stops
     * rather than spinning for as long as a finger is down.
     */
    private const val MAX_RESTARTS = 30

    /**
     * The errors that mean "nothing that time", rather than "this will not work".
     *
     * Only these restart. A refused microphone or a missing recogniser restarted
     * would be the same failure thirty times over.
     */
    private val RETRYABLE = setOf(
        android.speech.SpeechRecognizer.ERROR_NO_MATCH,
        android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        // Both mean "that binding is gone", which a fresh session is exactly
        // the answer to. They used to be fatal, and they were being *caused*
        // here: the old recogniser's destroy() was posted rather than run, so
        // it arrived after its replacement had bound and disconnected it.
        android.speech.SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
    )

    private var watchdog: Runnable? = null

    private fun armWatchdog() {
        disarmWatchdog()
        val r = Runnable {
            watchdog = null
            if (busy) forceReset("a dictation ran past ${WATCHDOG_MS / 1000}s")
        }
        watchdog = r
        main.postDelayed(r, WATCHDOG_MS)
    }

    private fun disarmWatchdog() {
        watchdog?.let { main.removeCallbacks(it) }
        watchdog = null
    }

    /**
     * Capture stopped without anybody asking it to — [Recorder] hit its ceiling,
     * or the microphone stopped answering.
     *
     * This used to be nobody's job, and that is the whole bug: the worker thread
     * stopped, the AudioRecord was never released, finish() never ran, and busy
     * stayed set for the life of the process — so every later press of the
     * Essential Key fell straight out of begin() on `if (busy)`.
     */
    private fun endedByItself() {
        if (!busy) return
        Log.w(TAG, "capture ended by itself; closing the dictation out")
        if (capturing) end() else forceReset("capture ended with nothing to end")
    }

    /**
     * Put everything back by force. The last resort, and never the normal route
     * out of a dictation — every call is a bug that got this far.
     */
    private fun forceReset(reason: String) {
        Log.w(TAG, "forcing a reset: $reason")
        disarmWatchdog()
        WhisperEngine.abort()
        google?.cancel()
        google = null
        work?.cancel()
        probeJob?.cancel()
        recorder?.release()
        recorder = null
        capturing = false
        finish(PillView.State.ERROR, null)
    }

    fun cancel() {
        Log.i(TAG, "cancel() busy=$busy capturing=$capturing")
        if (!busy) return
        holdingKey = false
        segments.clear()
        WhisperEngine.abort()
        work?.cancel()
        google?.cancel()
        google = null
        if (capturing) { recorder?.stop(); capturing = false }
        finish(PillView.State.ERROR, null)
    }

    // ---- asking ------------------------------------------------------------

    /**
     * Send a dictated question to Gemini and put the answer on the island.
     *
     * The pill is finished first, on purpose. Dictation's own job — listen,
     * transcribe — is genuinely over by this point, and leaving the pill
     * spinning through a network round trip would say the microphone was still
     * open when it is not. The island picks the wait up instead, which is the
     * surface that can afford to sit there for two seconds.
     *
     * With no island on screen there is nowhere for an answer to appear, so it
     * goes wherever dictation would have gone — typed into the field, or the
     * clipboard. That is the fallback and not the point, but an answer that
     * arrives nowhere is worse than one that arrives in the wrong place.
     */
    private suspend fun askGemini(ctx: Context, question: String) {
        val key = prefs?.now?.geminiKey.orEmpty()
        if (key.isBlank()) {
            finish(PillView.State.ERROR, "Add a Gemini key in the app's settings")
            return
        }

        val onIsland = Features.ISLAND && Island.isLive
        if (onIsland) {
            // Held open well past the round trip; replaced, not followed, by the
            // answer — see Feed.raise on why the key is fixed.
            Feed.raise("Gemini", question, "Thinking…", holdMs = 30_000L)
        }
        finish(PillView.State.DONE, null)

        Gemini.ask(key, question).fold(
            onSuccess = { answer ->
                Log.i(TAG, "gemini answered ${answer.length} chars")
                if (onIsland) {
                    Feed.raise(
                        source = "Gemini",
                        title = question,
                        text = answer,
                        holdMs = 14_000L,
                        // The island shows two lines; the answer may be longer
                        // than that, so the tap is how you get the whole of it.
                        onTap = { deliver(ctx, answer) },
                    )
                } else {
                    deliver(ctx, answer)
                }
            },
            onFailure = { t ->
                val why = t.message ?: "Gemini did not answer"
                Log.w(TAG, "gemini failed: $why")
                if (onIsland) {
                    Feed.raise("Gemini", "Gemini", why, holdMs = 6_000L)
                } else {
                    toast(why)
                }
            },
        )
    }

    // ---- notes -------------------------------------------------------------

    /**
     * Put [body] into the open note, opening one if there is not one yet.
     *
     * Does not call [finish]: finishing takes the window off the screen, and the
     * whole point of the card is that it stays up while you keep talking. It
     * still has to clear [busy] itself, or the key would do nothing next time.
     */
    private fun takeNote(ctx: Context, body: String, kind: NoteStore.Kind) {
        openKind = kind
        if (!cardOpen) {
            // Before the card grows, so the first frame it draws already says
            // the right word. Setting it afterwards makes every task card open
            // saying "Notes" and correct itself a frame later.
            pill?.setBadgeLabel(NoteCommand.badgeLabel(kind), false)
            pill?.setCardTitle(NoteCommand.badgeLabel(kind))
            openCard()
        }

        val id = openNoteId
        val note = when {
            id == null -> NoteStore.start(ctx, body, kind).also { openNoteId = it.id }
            body.isBlank() -> NoteStore.notes.value.firstOrNull { it.id == id }
            // A null from append means the note the card was opened on is gone
            // — deleted from the widget or the notes tab, or typed empty in the
            // card itself, which deletes it. Dropping the sentence on the floor
            // is the one unacceptable answer, so it starts a new note instead.
            else -> NoteStore.append(ctx, id, body)
                ?: NoteStore.start(ctx, body, kind).also { openNoteId = it.id }
        }

        if (pill == null) {
            // No pill means no card — openCard() above returned without doing
            // anything — so there is nothing to keep open and nothing to keep it
            // open *for*. The note is written; the dictation now has to end the
            // way any other does.
            //
            // Falling through instead would leave three things dangling, because
            // everything that tidies up lives in finish() and this path never
            // reaches it: openNoteId would point at a card nobody can see or
            // close, the model would never be scheduled for its idle unload, and
            // suppressPill would stay true — so the *next* dictation, started
            // from the key, would come up with no pill at all.
            openNoteId = null
            tick(24)
            finish(PillView.State.DONE, null)
            return
        }

        pill?.setNoteText(note?.text.orEmpty())
        pill?.morphTo(PillView.State.DONE)
        tick(24)
        busy = false
        // Nothing is scheduled. The card stays until Save or Delete is pressed,
        // and holding the key again adds another line to the same note.
    }

    // ---- recordings ---------------------------------------------------------

    /**
     * Keep [samples] as a clip and open the card over it.
     *
     * The recording is written and in the library *before* the card appears,
     * not when SAVE is pressed. The card is a chance to throw the clip away and
     * a chance to hear it back; it is not a gate the audio has to get through,
     * because a clip lost to a swipe, a crash or a flat battery is a minute of
     * someone's life that cannot be recorded again. DELETE is the undo.
     *
     * [transcript] is non-null only when the words were already decoded on the
     * way here — the slow path. Otherwise the row is left pending and
     * [Transcribe] finds its words while nobody is waiting.
     */
    private fun takeRecording(ctx: Context, samples: FloatArray, transcript: String?) {
        probeJob?.cancel()
        disarmWatchdog()
        busy = true

        work = scope.launch {
            val name = Clip.freshName()
            val written = withContext(Dispatchers.Default) {
                // Levelled before it is written, not on the way out to the
                // speaker. A phone microphone lands speech near a tenth of full
                // scale, which plays back as a whisper and draws as a flat
                // waveform; whisper wants the same lift for the transcript. One
                // gain, applied once, and every reader of the file agrees.
                Audio.normalise(samples)
                Clip.write(java.io.File(NoteStore.audioDir(ctx), name), samples)
            }
            if (!written) {
                finish(PillView.State.ERROR, "The recording could not be saved")
                return@launch
            }

            val wave = withContext(Dispatchers.Default) { Clip.wave(samples) }
            val durationMs = Clip.durationMs(samples.size)
            val note = NoteStore.startRecording(ctx, name, durationMs, wave)
            openNoteId = note.id
            openKind = NoteStore.Kind.RECORDING
            if (!transcript.isNullOrBlank()) NoteStore.setTranscript(ctx, note.id, transcript)

            val p = pill
            if (p == null) {
                // Nothing on screen to put it on. The clip is saved either way;
                // this just ends the dictation the way any suppressed one ends.
                openNoteId = null
                Transcribe.sweep(ctx)
                finish(PillView.State.DONE, null)
                return@launch
            }

            p.setBadgeLabel(NoteCommand.badgeLabel(NoteStore.Kind.RECORDING), true)
            p.setCardTitle(NoteCommand.badgeLabel(NoteStore.Kind.RECORDING))
            p.setClip(wave, durationMs)
            p.setNoteText("")
            recordCard = true
            if (!cardOpen) openCard()
            wirePlayback(ctx, note.id)
            busy = false
            // Only once the card is up. Starting the decode first would have
            // whisper and the card's opening animation on the same big cores.
            Transcribe.sweep(ctx)
        }
    }

    /** Point the card's disc and waveform at whatever is playing. */
    private fun wirePlayback(ctx: Context, id: Long) {
        val p = pill ?: return
        p.onPlayToggle = {
            NoteStore.notes.value.firstOrNull { it.id == id }
                ?.let { Playback.toggle(ctx, it) }
        }
        p.onSeek = { fraction -> Playback.seekTo(fraction) }
        playbackJob?.cancel()
        playbackJob = scope.launch {
            Playback.state.collect { st ->
                val view = pill ?: return@collect
                if (st == null || st.id != id) view.setPlayback(0f, false)
                else view.setPlayback(st.fraction, st.playing)
            }
        }
    }

    /**
     * Take the clip's card off the screen without an animation, keeping the
     * window.
     *
     * Only for the case where the key was pressed while it was up: the pill is
     * about to be shown in that same window, and [closeNote]'s outro ends by
     * removing the view — which would take the new pill with it a third of a
     * second after it appeared.
     */
    private fun dismissRecordCard(save: Boolean) {
        val ctx = app
        val id = openNoteId
        Playback.stop()
        playbackJob?.cancel()
        playbackJob = null
        openNoteId = null
        cardOpen = false
        recordCard = false
        if (ctx != null && id != null && !save) NoteStore.delete(ctx, id)

        val p = pill ?: return
        p.onSave = null
        p.onDelete = null
        p.onEditBody = null
        p.onPlayToggle = null
        p.onSeek = null
        p.setMode(PillView.Mode.PILL)
        val (px, py, pw, ph) = pillBounds()
        placeWindow(px, py, pw, ph)
    }

    // ---- typing into the card ----------------------------------------------

    /** How far the keyboard has pushed the card up. */
    /**
     * Where the keyboard is, in screen coordinates rather than as an inset —
     * see [ImeTop] for why that distinction is the whole difference between a
     * card that lifts and a card that shakes.
     */
    private val imeTop = ImeTop()

    /**
     * A tap on the note's text: put a real field over it and open the keyboard.
     *
     * The caret goes after the last word rather than where the tap landed. The
     * tap here means "let me add to this", almost never "let me get in between
     * those two words", and starting at the end is what makes the first
     * keystroke do the expected thing.
     */
    private fun beginEditing() {
        val p = pill ?: return
        val field = editor ?: return
        val lp = params ?: return
        val v = root() ?: return
        val wm = wm ?: return
        if (editing || !cardOpen) return

        val bounds = android.graphics.Rect()
        if (!p.bodyBounds(bounds)) return

        editing = true
        p.setEditing(true)

        field.layoutParams = FrameLayout.LayoutParams(bounds.width(), bounds.height()).apply {
            leftMargin = bounds.left
            topMargin = bounds.top
        }
        val text = openNoteText()
        field.setText(text)
        field.setSelection(text.length)
        field.visibility = View.VISIBLE

        // Two flags have to go. Without dropping FLAG_NOT_FOCUSABLE there is no
        // input connection and so no keyboard at all; without dropping
        // FLAG_LAYOUT_NO_LIMITS the window is laid out ignoring the system's
        // insets, and the IME inset — the one thing that says how far to lift
        // the card — never arrives.
        lp.flags = lp.flags and
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() and
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS.inv()
        // The system only resizes ordinary activity windows for the keyboard.
        // This one is moved by hand in [liftForIme], so it asks for nothing.
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        runCatching { wm.updateViewLayout(v, lp) }

        field.requestFocus()
        // Posted: the window has to have finished becoming focusable before
        // there is anything for the IME to attach to.
        field.post {
            val imm = app?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
        tick(10)
    }

    /**
     * Put the keyboard away and give focus back to whatever had it.
     *
     * [commit] is false only when the note is being thrown away anyway.
     */
    private fun endEditing(commit: Boolean) {
        if (!editing) return
        editing = false

        if (commit) commitEdit()

        editor?.let { field ->
            val imm = app?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            runCatching { imm?.hideSoftInputFromWindow(field.windowToken, 0) }
            field.clearFocus()
            field.visibility = View.GONE
        }
        pill?.setEditing(false)
        imeTop.clear()

        val lp = params
        val v = root()
        val wm = wm
        if (lp != null && v != null && wm != null) {
            lp.flags = lp.flags or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            lp.softInputMode = 0
            if (cardOpen) {
                val (x, y, w, h) = cardBounds()
                lp.x = x; lp.y = y; lp.width = w; lp.height = h
            }
            if (attached) runCatching { wm.updateViewLayout(v, lp) }
        }
    }

    /** What the open note says, whether it came from speech or from typing. */
    private fun openNoteText(): String {
        val id = openNoteId ?: return ""
        return NoteStore.notes.value.firstOrNull { it.id == id }?.text.orEmpty()
    }

    /**
     * Write the field back to the note.
     *
     * Typing into a card that was opened by the bare word "note" — one with no
     * note behind it yet — creates the note, which is the only way that card
     * ever becomes something without speaking to it.
     */
    private fun commitEdit() {
        val ctx = app ?: return
        val field = editor ?: return
        val text = field.text?.toString().orEmpty()
        val id = openNoteId
        if (id == null) {
            if (text.isBlank()) return
            openNoteId = NoteStore.start(ctx, text).id
        } else {
            NoteStore.update(ctx, id, text)
        }
        pill?.setNoteText(text)
    }

    /**
     * Lift the card clear of the keyboard.
     *
     * The window manager does not resize an overlay for the IME, so the card is
     * moved by exactly the height the keyboard reported and put back when it
     * reports zero.
     */
    private fun liftForIme() {
        // Only while this card is the thing being typed into. Insets are
        // dispatched to the overlay whatever raised the keyboard, so without
        // this the card jumps up out of the way of a keyboard belonging to the
        // app behind it.
        if (!editing || !cardOpen) return
        val lp = params ?: return
        val (x, resting, w, h) = cardBounds()
        val margin = dp(PillView.NOTE_MARGIN_DP).toInt()
        // The card sits its own margin above the keyboard, and never below
        // where it would have sat anyway.
        val y = if (imeTop.y <= 0) resting
        else (imeTop.y - margin - h).coerceIn(0, resting)
        if (lp.x == x && lp.y == y && lp.width == w && lp.height == h) return
        placeWindow(x, y, w, h)
    }

    /**
     * Put the window exactly where it needs to be, in one call.
     *
     * Never called from an animation loop. Each of these is an IPC to the window
     * manager and a new surface, and doing that per frame is what made growing
     * into the card judder — the shape is animated on the canvas instead, inside
     * a window that was already the right size.
     */
    private fun placeWindow(x: Int, y: Int, w: Int, h: Int) {
        val wm = wm ?: return
        val lp = params ?: return
        lp.x = x
        lp.y = y
        lp.width = w
        lp.height = h
        val v = root() ?: return
        if (attached) runCatching { wm.updateViewLayout(v, lp) }
    }

    /** Where the card sits: the same gap left, right and below. */
    private fun cardBounds(): IntArray {
        val (sw, sh) = screen()
        val margin = dp(PillView.NOTE_MARGIN_DP).toInt()
        val w = sw - margin * 2
        val h = dp(PillView.NOTE_H_DP).toInt()
        return intArrayOf(margin, sh - margin - h, w, h)
    }

    /** Where a resting pill's window goes. */
    private fun pillBounds(): IntArray {
        val s = prefs?.now ?: return intArrayOf(0, 0, 1, 1)
        val (sw, sh) = screen()
        val w = dp(PillView.WINDOW_W_DP).toInt()
        val h = dp(PillView.WINDOW_H_DP).toInt()
        val x = (s.pillX * sw - w / 2f).toInt().coerceIn(0, (sw - w).coerceAtLeast(0))
        val y = (s.pillY * sh - h / 2f).toInt().coerceIn(0, (sh - h).coerceAtLeast(0))
        return intArrayOf(x, y, w, h)
    }

    /**
     * "Note" was heard while the key is still down: widen the lozenge and say
     * NOTES in it.
     *
     * Deliberately not the card. The card is for a finished note, and opening it
     * mid-sentence would cover the screen before there is anything to put in it.
     * This is just the pill saying it understood.
     */
    private fun enterBadge(kind: NoteStore.Kind) {
        val p = pill ?: return
        val lp = params ?: return
        if (badgeOpen || cardOpen) return
        badgeOpen = true
        openKind = kind

        // Before the width is measured, not after: the badge is exactly as wide
        // as what is written in it, and "Recording" with a blinking dot and a
        // clock is a different width from "Notes" with five dots.
        p.setBadgeLabel(NoteCommand.badgeLabel(kind), kind == NoteStore.Kind.RECORDING)
        p.setCardTitle(NoteCommand.badgeLabel(kind))

        val (sw, _) = screen()
        val w = p.badgeWidthPx().toInt()
        val x = (lp.x + lp.width / 2 - w / 2).coerceIn(0, (sw - w).coerceAtLeast(0))

        // Where the lozenge is standing right now, written in the coordinates of
        // the wider window that is about to replace this one. Without it the
        // badge grows from the new window's middle — which, once the window has
        // been clamped against the screen edge, is not where the pill was, and
        // the widening reads as a second pill opening on top of the first.
        val pillL = lp.x + lp.width / 2f - dp(PillView.PILL_W_DP) / 2f
        val cy = lp.height / 2f
        val hh = dp(PillView.PILL_H_DP) / 2f
        p.setOrigin(
            pillL - x,
            cy - hh,
            pillL - x + dp(PillView.PILL_W_DP),
            cy + hh,
        )

        p.setMode(PillView.Mode.BADGE)
        placeWindow(x, lp.y, w, lp.height)
        tick(14)
        p.animateExpand(0f, 1f, 300)
    }

    /**
     * Grow whatever is on screen into the card, in one movement.
     *
     * The pill stays where it is and expands until it reaches the bottom rather
     * than travelling there first: two movements read as the pill being
     * replaced, one reads as it becoming the card.
     */
    private fun openCard() {
        val p = pill ?: return
        val lp = params ?: return
        if (cardOpen) return
        cardOpen = true
        badgeOpen = false

        // Where it is now, on screen, before the window moves under it.
        val fromX = lp.x.toFloat()
        val fromY = lp.y.toFloat()
        val fromW = lp.width.toFloat()
        val fromH = lp.height.toFloat()

        val (x, y, w, h) = cardBounds()
        p.onSave = { closeNote(save = true) }
        p.onDelete = { closeNote(save = false) }
        p.onEditBody = { beginEditing() }
        p.setMode(PillView.Mode.NOTE)
        // The old position expressed inside the new window, so the card appears
        // to grow out of exactly where the lozenge was standing.
        p.setOrigin(fromX - x, fromY - y, fromX - x + fromW, fromY - y + fromH)
        placeWindow(x, y, w, h)
        p.animateExpand(0f, 1f, 380)
    }

    /**
     * Put the card away, in a single movement out of the side of the screen.
     *
     * The only way out, and the only place the card's outro plays.
     */
    private fun closeNote(save: Boolean) {
        // Before openNoteId is cleared, or a note typed into and then saved
        // would have nowhere to be written.
        endEditing(commit = save)

        Playback.stop()
        playbackJob?.cancel()
        playbackJob = null

        val ctx = app
        val id = openNoteId
        val wasRecording = recordCard
        openNoteId = null
        cardOpen = false
        recordCard = false

        if (ctx != null && id != null) {
            // A clip is never "empty" — it has audio whether or not anyone said
            // anything into it — so the only question DELETE asks is whether to
            // keep it, and SAVE has nothing to tidy.
            if (save) { if (!wasRecording) NoteStore.discardIfEmpty(ctx, id) }
            else NoteStore.delete(ctx, id)
        }
        // A clip that has just been kept is the one thing waiting for words.
        if (save && wasRecording && ctx != null) Transcribe.sweep(ctx)

        val p = pill
        val lp = params
        if (p == null || lp == null) { busy = false; return }

        p.onSave = null
        p.onDelete = null
        p.onEditBody = null
        p.onPlayToggle = null
        p.onSeek = null

        // One movement, not two. The card used to shrink back to the pill's
        // home, redraw itself as a lozenge there and only then slide off — two
        // endings for one note, and the shrink was half-invisible because the
        // pill's home is outside the card's own window, which is all the view
        // is allowed to paint in. Now the card narrows into a lozenge against
        // the edge it leaves by and keeps going through it, all inside the
        // window it already has.
        val cy = lp.height / 2f
        val hh = dp(PillView.PILL_H_DP) / 2f
        val pw = dp(PillView.PILL_W_DP)
        if (slidesFromRight()) {
            p.setOrigin(lp.width.toFloat(), cy - hh, lp.width + pw, cy + hh)
        } else {
            p.setOrigin(-pw, cy - hh, 0f, cy + hh)
        }
        if (save) tick(24)

        p.animateExpand(1f, 0f, 320, EXIT) {
            p.setNoteText("")
            p.setMode(PillView.Mode.PILL)
            p.stop()
            root()?.let { v -> if (attached) runCatching { wm?.removeViewImmediate(v) } }
            attached = false
            badgeOpen = false
            cardOpen = false
            // The params object is reused, so it has to go back to pill size or
            // the next pill floats in a card-sized invisible rectangle.
            val (px, py, pw2, ph2) = pillBounds()
            lp.x = px
            lp.y = py
            lp.width = pw2
            lp.height = ph2
            busy = false
            scheduleIdleUnload()
        }
    }

    /**
     * Hand the text over however the user asked for it. The two destinations are
     * independent — typing it in and keeping a copy are different wants — so
     * this reports success if *either* landed.
     */
    private fun deliver(ctx: Context, text: String): Boolean {
        val s = prefs?.now ?: return false
        var landed = false

        if (s.copyToClipboard) {
            copy(ctx, text)
            landed = true
        }
        if (s.typeIntoField) {
            val typed = com.ishaan.essentialvoice.trigger.EssentialKeyService
                .instance?.insertText(text) ?: false
            if (typed) {
                landed = true
            } else if (!s.copyToClipboard) {
                // There was nowhere to type and no copy was asked for; putting it
                // on the clipboard anyway beats dropping what was just dictated.
                copy(ctx, text)
                toast("No text field was focused, so it is on the clipboard")
                landed = true
            }
        }
        if (!landed) {
            // Both switches off: say the words rather than lose them.
            toast(text)
            landed = true
        }
        return landed
    }

    /**
     * Put the transcript on the clipboard, flagged so the system keeps it out of
     * clipboard history and out of the paste toast.
     *
     * The paste route in
     * [com.ishaan.essentialvoice.trigger.EssentialKeyService.insertText] has
     * always marked its clip sensitive, for the reason that applies just as much
     * here: this is dictation, and it may well be a message, an address or a
     * password said out loud. This path — "copy to clipboard", and the fallback
     * when there was nowhere to type — did not, so the one setting a user turns
     * on to *keep* a transcript was also the one that put it in a system list
     * they never asked for.
     */
    private fun copy(ctx: Context, text: String) {
        val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val data = ClipData.newPlainText("Essential Voice", text).apply {
            description.extras = android.os.PersistableBundle().apply {
                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clip.setPrimaryClip(data)
    }

    /**
     * Close out: show the ending glyph, say anything the user needs to act on,
     * then slide the pill back out the way it came.
     */
    private fun finish(state: PillView.State, message: String?) {
        // The dictation is over one way or another; the backstop has nothing
        // left to catch.
        disarmWatchdog()
        // Megabytes of audio that turned out not to be a recording. Every path
        // out of a dictation reaches here, which is what makes this the one
        // place it has to be dropped.
        pendingAudio = null
        app?.let { Transcribe.resume(it) }
        message?.let { toast(it) }
        val p = pill
        // Whatever is left on screen is an ending playing out, not a dictation.
        // A swipe over it should not cancel a thing that has already stopped —
        // and, more to the point, the pill has to go back to letting touches
        // through the moment there is nothing to abandon.
        p?.onSwipeAway = null
        if (p == null) {
            // A suppressed dictation has no pill to play an ending on; it still
            // has to let go of the microphone and the model.
            busy = false
            suppressPill = false
            scheduleIdleUnload()
            return
        }

        val ending = {
            // Picked per dictation, and handed over before the morph so the
            // first frame of DONE is already the right words.
            if (state == PillView.State.DONE) p.setSignOff(SignOff.pick())
            p.morphTo(state)
            if (state == PillView.State.DONE) tick(24)
            val linger = if (state == PillView.State.DONE) 420L else 620L
            main.postDelayed({
                detachPill()
                busy = false
                suppressPill = false
                scheduleIdleUnload()
            }, linger)
        }

        // The probe can hear "note" in the opening and be wrong about it — the
        // full sentence is the one that decides. When it disagrees, the badge
        // has to become a pill again before the ending glyph is drawn in it, or
        // a plain dictation finishes inside a lozenge that says Notes. It
        // narrows back the way it widened; snapping would be a second animation
        // nobody asked for.
        if (badgeOpen) {
            badgeOpen = false
            p.animateExpand(1f, 0f, 200, EXIT) {
                val lp = params
                if (lp != null) {
                    val (sw, _) = screen()
                    val w = dp(PillView.WINDOW_W_DP).toInt()
                    val h = dp(PillView.WINDOW_H_DP).toInt()
                    val x = (lp.x + lp.width / 2 - w / 2)
                        .coerceIn(0, (sw - w).coerceAtLeast(0))
                    placeWindow(x, lp.y, w, h)
                }
                p.setMode(PillView.Mode.PILL)
                ending()
            }
            return
        }

        ending()
    }

    private fun toast(text: String) {
        val ctx = app ?: return
        main.post { Toast.makeText(ctx, text, Toast.LENGTH_LONG).show() }
    }

    // ---- the window --------------------------------------------------------

    private fun dp(v: Float): Float {
        val d = app?.resources?.displayMetrics?.density ?: 3f
        return v * d
    }

    /**
     * A window only as big as the pill.
     *
     * Deliberately not full-screen: the system caps a touch-passthrough overlay
     * at 0.8 opacity, which would leave the pill translucent. A small window
     * that takes its own touches keeps full opacity, and it only covers anything
     * while a dictation is actually happening. FLAG_NOT_FOCUSABLE has to stay —
     * the field being typed into must keep input focus or the text has nowhere
     * to land.
     */
    private fun buildParams() = WindowManager.LayoutParams(
        dp(PillView.WINDOW_W_DP).toInt(),
        dp(PillView.WINDOW_H_DP).toInt(),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    /**
     * The view the window manager holds.
     *
     * The group and the pill are built together in [ensureHost], so this is the
     * group in practice; the fallback is only for the window of time before
     * anything has been built, when nothing calls it anyway.
     */
    private fun root(): View? = host ?: pill

    /**
     * Build the overlay's view tree, once.
     *
     * A group with the card in it and a text field over the card. The field is
     * GONE until a note is tapped — a focusable field sitting in an overlay
     * with nothing to do is a field that can take focus away from whatever the
     * user is actually typing in.
     */
    private fun ensureHost(ctx: Context): FrameLayout {
        host?.let { return it }

        val p = pill ?: PillView(ctx).also { pill = it }
        val field = EditText(ctx).apply {
            visibility = View.GONE
            background = null
            setPadding(0, 0, 0, 0)
            gravity = Gravity.TOP or Gravity.START
            // A note is prose: several lines, sentence case, and a return key
            // that inserts a line rather than closing anything.
            setSingleLine(false)
            isVerticalScrollBarEnabled = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
            p.styleAsBody(this)
            // The default caret is the platform accent, which on a yellow card
            // is a blue line in the middle of black text.
            runCatching {
                textCursorDrawable = android.graphics.drawable.GradientDrawable().apply {
                    setSize((2 * ctx.resources.displayMetrics.density).toInt(), 0)
                    setColor(p.bodyInk())
                }
            }
        }
        editor = field

        val group = object : FrameLayout(ctx) {
            // Back closes the keyboard rather than the note. While the window
            // is focusable it is the only thing that will see the gesture, and
            // losing a half-typed note to it would be unforgivable.
            override fun dispatchKeyEventPreIme(event: android.view.KeyEvent): Boolean {
                if (editing && event.keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                    event.action == android.view.KeyEvent.ACTION_UP
                ) {
                    endEditing(commit = true)
                    return true
                }
                return super.dispatchKeyEventPreIme(event)
            }
        }
        group.addView(
            p,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        group.addView(field, FrameLayout.LayoutParams(0, 0))

        // The keyboard does not resize an overlay window — the system's
        // adjust-resize only applies to ordinary activity windows — so the card
        // is lifted by hand, by exactly the height the IME took.
        group.setOnApplyWindowInsetsListener { v, insets ->
            if (imeTop.update(insets, v)) liftForIme()
            insets
        }

        host = group
        return group
    }

    /**
     * Dress the pill, and tell the window whether it is a colour or a hole.
     *
     * The frosted style is not a fill: the window asks the compositor to blur
     * everything behind it and the style's colour is a scrim over the result.
     * That is a window property, not something the view can paint, which is why
     * it is set here rather than in [PillView].
     *
     * Blur is a privilege, not a guarantee — the system turns it off under
     * battery saver, on hardware that cannot afford it, and behind a developer
     * option. [WindowManager.isCrossWindowBlurEnabled] is the only honest way to
     * ask, and when the answer is no the scrim alone still reads as a dark
     * translucent lozenge rather than as nothing at all.
     */
    private fun applyStyle(p: PillView, lp: WindowManager.LayoutParams, style: PillStyle) {
        p.setStyle(style)
        val blurAllowed = style.blurred &&
            runCatching { wm?.isCrossWindowBlurEnabled == true }.getOrDefault(false)
        if (blurAllowed) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            lp.blurBehindRadius = BLUR_RADIUS_PX
        } else {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
            lp.blurBehindRadius = 0
        }
    }

    private fun screen(): Pair<Int, Int> {
        val b = wm?.maximumWindowMetrics?.bounds ?: return 1080 to 2400
        return b.width() to b.height()
    }

    private fun slidesFromRight(): Boolean {
        val s = prefs?.now ?: return true
        return when (s.slideFrom) {
            "left" -> false
            "right" -> true
            else -> s.pillX >= 0.5f
        }
    }

    private fun attachPill(): PillView? {
        val ctx = app ?: return null
        val wm = wm ?: return null
        val s = prefs?.now ?: return null

        val group = ensureHost(ctx)
        val p = pill ?: return null
        val lp = params ?: buildParams().also { params = it }
        applyStyle(p, lp, s.pill)

        val (sw, sh) = screen()
        val targetX = (s.pillX * sw - lp.width / 2f).toInt()
            .coerceIn(0, (sw - lp.width).coerceAtLeast(0))
        lp.y = (s.pillY * sh - lp.height / 2f).toInt()
            .coerceIn(0, (sh - lp.height).coerceAtLeast(0))
        lp.x = if (slidesFromRight()) sw else -lp.width

        if (!attached) {
            runCatching { wm.addView(group, lp) }
                .onFailure { Log.e(TAG, "addView failed", it); return null }
            attached = true
        } else {
            runCatching { wm.updateViewLayout(group, lp) }
        }

        slideTo(targetX, 380, ENTER, null)
        return p
    }

    private fun detachPill() {
        endEditing(commit = true)
        // The card is going away, so the note it was open on is finished with.
        // Without this the id survives the teardown and the *next* "note buy
        // milk" appends to it instead of starting a new one — which is what a
        // stray sub-350ms press on an open card used to do, because that ends
        // in finish() and finish() ends here rather than in closeNote().
        app?.let { ctx -> openNoteId?.let { NoteStore.discardIfEmpty(ctx, it) } }
        Playback.stop()
        playbackJob?.cancel()
        playbackJob = null
        openNoteId = null
        badgeOpen = false
        cardOpen = false
        recordCard = false
        val p = pill ?: return
        val wm = wm ?: return
        if (!attached) { pill = null; return }
        val lp = params ?: return
        val (sw, _) = screen()
        slideTo(if (slidesFromRight()) sw else -lp.width, 260, EXIT) {
            p.stop()
            root()?.let { v -> if (attached) runCatching { wm.removeViewImmediate(v) } }
            attached = false
        }
    }

    /**
     * The intro and outro are the window moving, not the view drawing itself
     * somewhere else — a view cannot paint outside its own surface.
     */
    private fun slideTo(toX: Int, ms: Long, interp: Interpolator, onEnd: (() -> Unit)?) {
        val p = pill ?: return
        val lp = params ?: return
        val wm = wm ?: return
        slideAnim?.cancel()
        slideAnim = ValueAnimator.ofInt(lp.x, toX).apply {
            duration = ms
            interpolator = interp
            addUpdateListener {
                lp.x = it.animatedValue as Int
                val v = root()
                if (attached && v != null) runCatching { wm.updateViewLayout(v, lp) }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { onEnd?.invoke() }
            })
            start()
        }
    }

    // ---- odds and ends -----------------------------------------------------

    private fun tick(ms: Long) {
        if (prefs?.now?.haptics != true) return
        buzz(app ?: return, ms)
    }

    /** Also used by the settings screen, so switching haptics on can be felt. */
    fun buzz(context: Context, ms: Long) {
        val v = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        runCatching { v.vibrate(VibrationEffect.createOneShot(ms, 90)) }
    }

    private fun scheduleIdleUnload() {
        val ctx = app ?: return
        idleJob?.cancel()
        val window = prefs?.now?.idleUnloadSeconds ?: return
        if (window <= 0) return
        idleJob = scope.launch {
            delay(window * 1000L + 2_000L)
            if (busy) return@launch
            WhisperEngine.unloadIfIdle(ctx)
            // The recorder holds a ninety-second capture buffer — nearly six
            // megabytes — for the whole life of the process. Kept across a
            // conversation's worth of dictations it saves an allocation; kept
            // overnight it is just weight, and a fatter process is a process
            // the system kills sooner, which costs a model reload to come back
            // from. It goes with the model, on the same timer.
            recorder?.release()
            recorder = null
        }
    }

    /** The chosen tier changed; drop whatever is resident. */
    fun onTierChanged() {
        scope.launch { WhisperEngine.unload() }
    }

    /**
     * The colour was changed in the settings.
     *
     * Every dictation picks the current style up in [attachPill] anyway, so this
     * only matters for the one case where that is not called: a note card left
     * open on screen while the settings page is scrolled behind it. Repainting
     * it there costs one window update and saves the card looking like it
     * disagrees with the swatch that is selected.
     */
    fun onStyleChanged() {
        val p = pill ?: return
        val lp = params ?: return
        val style = prefs?.now?.pill ?: return
        applyStyle(p, lp, style)
        val v = root() ?: return
        if (attached) runCatching { wm?.updateViewLayout(v, lp) }
    }
}
