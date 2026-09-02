package com.ishaan.essentialvoice.speech

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Dictation through Android's own recogniser instead of whisper.
 *
 * whisper's multilingual models are the weak point of this app outside English
 * — `tiny` and `base` especially, where a hundred languages share a budget that
 * the `.en` builds spend on one. The phone already carries a recogniser that is
 * far better at those languages, and it is free and needs no key: on this phone
 * `Settings.Secure.voice_recognition_service` is Speech Services by Google,
 * the same engine behind Gboard's voice typing.
 *
 * ------------------------------------------------------------ what it costs
 *
 * **It owns the microphone.** There is no handing it a buffer we recorded — the
 * documented route for that (`EXTRA_AUDIO_SOURCE`) is optional and not
 * implemented by every recogniser — so for this engine [com.ishaan.essentialvoice.voice.Recorder]
 * does not run at all and the two must never be open at once. What comes back
 * instead is [RecognitionListener.onRmsChanged] for the pill's level and
 * partial results for the note probe, both of which the rest of the app already
 * knows how to consume.
 *
 * **It can leave the phone.** That is the whole reason [preferOffline] exists
 * and defaults to on: with it set, the recogniser refuses rather than reaching
 * for the network, and a refusal is something we can explain ("the Hindi pack
 * is not installed") instead of a silent upload. The app's promise that nothing
 * is uploaded is only true offline, so going online is a setting the user turns
 * on, never a fallback taken on their behalf.
 *
 * **Everything here is main-thread.** `SpeechRecognizer` is a bound service
 * client and throws if it is created or driven from anywhere else.
 */
object GoogleSpeech {

    private const val TAG = "EVGoogle"

    /**
     * The one thread the framework's callback APIs are answered on.
     *
     * `checkRecognitionSupport` and `triggerModelDownload` each take an
     * Executor, and each call site used to build a fresh
     * `newSingleThreadExecutor()` and never shut it down — one non-daemon thread
     * leaked per language query, and the language picker asks about a hundred
     * languages. One shared daemon thread instead: these callbacks do nothing
     * but hop straight back to the main thread, so there is no work here to
     * serialise against.
     */
    private val callbacks: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "ev-speech-cb").apply { isDaemon = true }
        }
    }

    fun isAvailable(context: Context): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * What the recognisers can actually do with one language.
     *
     * [tag] is the language tag to *ask for*, and the whole reason this exists:
     * the first version of this file sent whisper's bare "hi" and got
     * `ERROR_LANGUAGE_NOT_SUPPORTED` 69ms later — as it did for "en", which is
     * how it was obvious the language was not the problem. The recognisers deal
     * in full tags ("en-US", "hi-IN"), and the only honest source of the right
     * one is the recogniser itself.
     */
    data class Support(
        val code: String,
        /** The tag to send, or null if neither recogniser offers this language. */
        val tag: String?,
        /** Ready to work offline right now. */
        val installed: Boolean,
        /** On-device recognition exists for it, but the pack is not downloaded. */
        val downloadable: Boolean,
        /** The pack is downloading. */
        val pending: Boolean,
        /** Recognisable, but only over the network. */
        val online: Boolean,
        /** True when [tag] belongs to the system's on-device recogniser. */
        val onDevice: Boolean,
        /** Set when the query itself failed. */
        val error: Int? = null,
    ) {
        val usable: Boolean get() = tag != null && (installed || online)
    }

    private val support = java.util.concurrent.ConcurrentHashMap<String, Support>()

    /**
     * Every language tag either recogniser reports, from one query.
     *
     * `checkRecognitionSupport` is asked about one language and answers with the
     * *whole* set the recogniser knows — installed, downloadable and online, all
     * of them, every time. So the language picker does not need a hundred
     * queries to say which languages Google can do; it needs one, kept here.
     */
    class Catalogue(
        val installed: List<String>,
        val downloadable: List<String>,
        val online: List<String>,
        /**
         * The installed tags split by *which* recogniser reported them.
         *
         * [installed] is the union, which is the right answer for the language
         * list and the wrong one for a dictation: a tag is only usable on the
         * recogniser that has it, and the union has forgotten which that was.
         * See [choose].
         */
        val onDeviceInstalled: List<String> = emptyList(),
        val defaultInstalled: List<String> = emptyList(),
    ) {
        /**
         * What Google can do with a whisper language code, right now.
         *
         * [State.None] means "not in the lists this phone reported", which is
         * *not* the same as "Google cannot do it" — the on-device lists are the
         * packs this phone knows about, and the online recogniser's list comes
         * back empty here. So None is shown as an unknown rather than a refusal,
         * and the language is still selectable: the only honest way to find out
         * is to try it, and trying it costs one held key.
         */
        fun state(code: String): State = when {
            pick(code, installed) != null -> State.Installed
            pick(code, downloadable) != null -> State.Downloadable
            pick(code, online) != null -> State.Online
            else -> State.None
        }

        /**
         * The full tag the recogniser itself listed for [code], or null.
         *
         * This is the whole reason the catalogue is worth keeping. The
         * recognisers deal in complete tags — "it-IT", "uk-UA" — and reject a
         * bare primary subtag outright with `LANGUAGE_NOT_SUPPORTED`, which is
         * exactly what "it" got. whisper's codes are primary subtags, so
         * anything sent to a recogniser has to be translated first, and the only
         * honest source of the translation is the list the recogniser gave us.
         */
        fun tagFor(code: String): String? =
            pick(code, installed) ?: pick(code, downloadable) ?: pick(code, online)
    }

    enum class State { Installed, Downloadable, Online, None }

    @Volatile
    private var catalogue: Catalogue? = null

    /** The last catalogue read, if anything has read one. */
    fun catalogue(): Catalogue? = catalogue

    /**
     * Read the catalogue, asking both recognisers and merging what they know.
     *
     * The union rather than one of them: the two genuinely differ — on this
     * phone Speech Services keeps `en-IN` and Android System Intelligence keeps
     * `en-US` and `hi-IN` — and a language either of them can do is a language
     * this app can do, because [check] picks the right one per dictation.
     */
    fun refreshCatalogue(context: Context, onDone: (Catalogue) -> Unit = {}) {
        // Any language will do as the question; the answer is the same list.
        check(context, "en") {
            onDone(catalogue ?: Catalogue(emptyList(), emptyList(), emptyList()))
        }
    }

    /** Fold one recogniser's lists into the catalogue. */
    private fun addToCatalogue(
        onDevice: Boolean,
        installed: List<String>,
        pending: List<String>,
        downloadable: List<String>,
        online: List<String>,
    ) {
        val had = catalogue
        catalogue = Catalogue(
            installed = ((had?.installed ?: emptyList()) + installed).distinct(),
            downloadable =
                ((had?.downloadable ?: emptyList()) + pending + downloadable).distinct(),
            online = ((had?.online ?: emptyList()) + online).distinct(),
            onDeviceInstalled = (
                (had?.onDeviceInstalled ?: emptyList()) + if (onDevice) installed else emptyList()
                ).distinct(),
            defaultInstalled = (
                (had?.defaultInstalled ?: emptyList()) + if (onDevice) emptyList() else installed
                ).distinct(),
        )
    }

    /** What [check] has already learned about [code], if anything. */
    fun known(code: String): Support? = support[code]

    /** A language tag and the recogniser it belongs to, decided together. */
    data class Choice(val tag: String, val onDevice: Boolean)

    /**
     * What to ask for, and which of the two recognisers to ask.
     *
     * One function because they are one decision, and taking them apart is a
     * bug with a name: a dictation that asked for `en-IN` — the right tag, from
     * the *default* recogniser's list — while binding the *on-device*
     * recogniser, which has `en-US` and has never heard of `en-IN`. That is
     * `error 13 (LANGUAGE_UNAVAILABLE)`, 100ms in, on every hold. The tag came
     * from the catalogue, which is the union of both recognisers and has
     * forgotten whose tag is whose, and `onDevice` came from [known], which
     * had answered about a different tag entirely.
     *
     * Exact tags are preferred over same-language ones, in both lists, before
     * either recogniser is preferred over the other. That ordering is what
     * Hinglish needs: it asks for `en-IN` specifically, because Indian English
     * keeps the English words English, and settling for `en-US` because it
     * happens to be on the closer recogniser gets the accent wrong on purpose.
     */
    fun choose(code: String): Choice {
        val want = whisperToBcp47(code)
        val cat = catalogue
        fun exact(tags: List<String>) = tags.firstOrNull { it.equals(want, ignoreCase = true) }

        // 1. The exact tag, installed, wherever it lives.
        cat?.let { c ->
            exact(c.onDeviceInstalled)?.let { return Choice(it, true) }
            exact(c.defaultInstalled)?.let { return Choice(it, false) }
        }

        // 2. The per-language query, which is the only source that reports a
        //    tag and its recogniser together.
        support[code]?.let { s ->
            if (s.tag != null) return Choice(s.tag, s.installed && s.onDevice)
        }

        // 3. The same language in some other region, installed.
        cat?.let { c ->
            pick(code, c.onDeviceInstalled)?.let { return Choice(it, true) }
            pick(code, c.defaultInstalled)?.let { return Choice(it, false) }
        }

        // 4. Nothing installed anywhere: the default recogniser, which is the
        //    one that can go online. A bare primary subtag is refused outright
        //    — "it" is LANGUAGE_NOT_SUPPORTED where "it-IT" is not — so this
        //    still sends a full tag if the catalogue knows one.
        return Choice(cat?.tagFor(code) ?: want, false)
    }

    /**
     * Ask both recognisers what they have, and cache the answer.
     *
     * Two of them, because they are genuinely different engines: the system's
     * on-device recogniser (`createOnDeviceSpeechRecognizer`, Android System
     * Intelligence on this phone) is the one that does offline dictation and
     * the one that can be asked to *download* a language, while the default
     * recogniser from `Settings.Secure.voice_recognition_service` is Speech
     * Services by Google. On-device wins when it has the language installed,
     * because it is the only one of the two that can keep the promise on the
     * front of this app.
     *
     * `checkRecognitionSupport` replaced the `ACTION_GET_LANGUAGE_DETAILS`
     * broadcast this used to send, which answered with nothing at all on this
     * phone — the ordered-broadcast route is effectively dead on Android 13+.
     */
    fun check(context: Context, code: String, onResult: (Support) -> Unit) {
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            queryOne(app, code, onDevice = false) { default ->
                queryOne(app, code, onDevice = true) { device ->
                    finishCheck(code, better(default, device), onResult)
                }
            }
        }
    }

    /**
     * Which of the two recognisers to believe.
     *
     * An installed pack beats everything — it is the only state that actually
     * works offline. Failing that, one that is downloading, then one that could
     * be. The tie is broken toward the **default** recogniser rather than the
     * on-device one, and that is measured rather than assumed: on this phone
     * `installed=[en-IN]` on Speech Services by Google and `installed=[]` on
     * Android System Intelligence, so the default is the one that actually
     * keeps packs, and a download aimed at the other would land nowhere useful.
     */
    private fun better(default: Support, device: Support): Support = when {
        default.installed -> default
        device.installed -> device
        default.pending -> default
        device.pending -> device
        default.downloadable -> default
        device.downloadable -> device
        default.online -> default
        device.online -> device
        default.tag != null -> default
        else -> device
    }

    private fun finishCheck(code: String, result: Support, onResult: (Support) -> Unit) {
        support[code] = result
        Log.i(
            TAG,
            "support for \"$code\": tag=${result.tag} installed=${result.installed} " +
                "downloadable=${result.downloadable} pending=${result.pending} " +
                "online=${result.online} onDevice=${result.onDevice} error=${result.error}",
        )
        onResult(result)
    }

    /** One recogniser's answer. Main thread; `SpeechRecognizer` insists. */
    private fun queryOne(
        context: Context,
        code: String,
        onDevice: Boolean,
        onResult: (Support) -> Unit,
    ) {
        val empty = Support(code, null, false, false, false, false, onDevice)
        // `checkRecognitionSupport` and everything it answers with is API 33.
        // `minSdk` is 31, so on Android 12 and 12L there is nothing to ask and
        // the honest answer is "this phone cannot tell us". It used to be left
        // to the `runCatching` below, which did swallow the NoSuchMethodError —
        // but a version check is what says *why* nothing came back, and it keeps
        // the call off the verifier's path entirely.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.i(TAG, "no support query before API 33")
            onResult(empty)
            return
        }
        val recognizer = runCatching {
            if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        }.getOrElse {
            Log.w(TAG, "no ${if (onDevice) "on-device" else "default"} recogniser", it)
            onResult(empty)
            return
        }

        // checkRecognitionSupport answers on the executor it was handed, and
        // SpeechRecognizer throws if it is touched from anywhere but the main
        // thread — including destroy(), and including the *next* query this
        // result leads to. Everything after the answer therefore hops back.
        val main = Handler(Looper.getMainLooper())
        var answered = false
        val done = { s: Support ->
            if (!answered) {
                answered = true
                main.post {
                    runCatching { recognizer.destroy() }
                    onResult(s)
                }
            }
        }

        runCatching {
            recognizer.checkRecognitionSupport(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, whisperToBcp47(code))
                },
                callbacks,
                object : android.speech.RecognitionSupportCallback {
                    override fun onSupportResult(rs: android.speech.RecognitionSupport) {
                        addToCatalogue(
                            onDevice,
                            rs.installedOnDeviceLanguages,
                            rs.pendingOnDeviceLanguages,
                            rs.supportedOnDeviceLanguages,
                            rs.onlineLanguages,
                        )
                        val installed = pick(code, rs.installedOnDeviceLanguages)
                        val pending = pick(code, rs.pendingOnDeviceLanguages)
                        val downloadable = pick(code, rs.supportedOnDeviceLanguages)
                        val online = pick(code, rs.onlineLanguages)
                        Log.i(
                            TAG,
                            "${if (onDevice) "on-device" else "default"} lists for \"$code\": " +
                                "installed=${rs.installedOnDeviceLanguages} " +
                                "pending=${rs.pendingOnDeviceLanguages} " +
                                "supported=${rs.supportedOnDeviceLanguages} " +
                                "online=${rs.onlineLanguages}",
                        )
                        done(
                            Support(
                                code = code,
                                tag = installed ?: pending ?: downloadable ?: online,
                                installed = installed != null,
                                downloadable = installed == null && downloadable != null,
                                pending = installed == null && pending != null,
                                online = online != null,
                                onDevice = onDevice,
                            ),
                        )
                    }

                    override fun onError(error: Int) {
                        Log.w(
                            TAG,
                            "${if (onDevice) "on-device" else "default"} support check " +
                                "failed: $error (${name(error)})",
                        )
                        done(empty.copy(error = error))
                    }
                },
            )
        }.onFailure {
            Log.w(TAG, "checkRecognitionSupport threw", it)
            done(empty)
        }
    }

    /**
     * Asks the system to download the on-device pack for [code].
     *
     * The one thing that makes offline recognition in a new language possible
     * without sending the user into three levels of the phone's settings. It is
     * fire-and-forget: the system shows its own progress, and the next
     * [check] is what notices it arrived.
     */
    fun triggerDownload(context: Context, code: String, onState: (String) -> Unit = {}) {
        // Try the on-device recogniser and, if it says it has never heard of the
        // language, the default one. Which of the two keeps a given pack is not
        // knowable in advance — this phone has en-IN on one and en-US, hi-IN on
        // the other — and asking both is one round trip against a guess that is
        // wrong half the time.
        download(context, code, onDevice = true, onState = onState) { first ->
            if (first == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
                download(context, code, onDevice = false, onState = onState)
            } else {
                onState("The system refused the download (${name(first)})")
            }
        }
    }

    private fun download(
        context: Context,
        code: String,
        onDevice: Boolean,
        onState: (String) -> Unit = {},
        onFailed: ((Int) -> Unit)? = null,
    ) {
        val app = context.applicationContext
        // `triggerModelDownload` is API 34. Below that there is no way to ask
        // the recogniser to fetch a pack, so say so rather than no-op silently:
        // the user can still install one from the phone's own settings.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Log.i(TAG, "no pack download before API 34")
            if (onFailed != null) onFailed(SpeechRecognizer.ERROR_CLIENT)
            else onState("This phone cannot fetch language packs from here")
            return
        }
        // The tag the recogniser itself listed, never whisper's bare code:
        // "it" is refused with LANGUAGE_NOT_SUPPORTED, "it-IT" is not.
        val tag = catalogue?.tagFor(code) ?: support[code]?.tag ?: whisperToBcp47(code)
        Handler(Looper.getMainLooper()).post {
            val sr = runCatching {
                if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
                else SpeechRecognizer.createSpeechRecognizer(app)
            }.getOrElse {
                Log.w(TAG, "no recogniser to download with", it)
                if (onFailed != null) onFailed(SpeechRecognizer.ERROR_CLIENT)
                else onState("The recogniser is not available")
                return@post
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            }
            // The listening overload, not the fire-and-forget one. The silent
            // version was tried first and told us nothing at all: the pack did
            // not arrive and no state anywhere said why. A download the user is
            // waiting on has to be able to report that it was refused.
            val ok = runCatching {
                sr.triggerModelDownload(
                    intent,
                    callbacks,
                    object : android.speech.ModelDownloadListener {
                        override fun onProgress(completedPercent: Int) {
                            Log.i(TAG, "$tag download $completedPercent%")
                            onState("Downloading $completedPercent%")
                        }

                        override fun onSuccess() {
                            Log.i(TAG, "$tag downloaded")
                            support.remove(code)
                            onState("Downloaded")
                        }

                        override fun onScheduled() {
                            Log.i(TAG, "$tag download scheduled")
                            onState("Queued by the system — it may wait for Wi-Fi")
                        }

                        override fun onError(error: Int) {
                            Log.w(
                                TAG,
                                "$tag download failed on the " +
                                    "${if (onDevice) "on-device" else "default"} " +
                                    "recogniser: $error (${name(error)})",
                            )
                            if (onFailed != null) onFailed(error)
                            else onState("The system refused the download (${name(error)})")
                        }
                    },
                )
                true
            }.getOrElse {
                Log.w(TAG, "could not ask for a download", it)
                if (onFailed != null) onFailed(SpeechRecognizer.ERROR_CLIENT)
                else onState("Could not ask for the download")
                false
            }
            if (ok) Log.i(TAG, "asked for $tag on the ${if (onDevice) "on-device" else "default"} recogniser")
        }
    }

    /**
     * The best of the recogniser's tags for whisper's [code].
     *
     * Exact match first, then the same primary subtag — there is no way to
     * choose between "en-GB" and "en-IN" from here, and the recogniser lists
     * them in its own order.
     */
    private fun pick(code: String, tags: List<String>?): String? {
        if (tags.isNullOrEmpty()) return null
        val want = whisperToBcp47(code)
        tags.firstOrNull { it.equals(want, ignoreCase = true) }?.let { return it }
        val primary = want.substringBefore('-')
        return tags.firstOrNull { it.substringBefore('-').equals(primary, ignoreCase = true) }
    }

    /**
     * whisper's spellings are not all valid language subtags.
     *
     * Only the ones that actually differ are listed. "jw" is whisper's Javanese
     * and the subtag is "jv"; "no" is Norwegian, which Android knows as the
     * Bokmål it means in practice. This is only ever a starting point for
     * matching against what the recogniser lists — it is not what gets sent.
     */
    private fun whisperToBcp47(code: String): String = when (code) {
        // Hinglish is this app's own entry, not whisper's and not a real
        // subtag. Indian English is the pack that handles it: it keeps the
        // English words as English rather than transliterating them, which is
        // the half of Hinglish the Hindi pack gets wrong.
        "hi-en" -> "en-IN"
        "jw" -> "jv"
        "no" -> "nb"
        else -> code
    }

    /**
     * One dictation.
     *
     * Deliberately start/stop rather than a suspending call, because the shape
     * of a dictation here is a key being held: the caller decides when it ends,
     * and the recogniser's own idea of when somebody stopped talking is exactly
     * what must not decide it.
     */
    class Session(
        private val context: Context,
        private val languageTag: String,
        /** Bind the system's on-device recogniser rather than the default one. */
        private val onDevice: Boolean,
        private val preferOffline: Boolean,
        private val onLevel: (Float) -> Unit,
        private val onPartial: (String) -> Unit,
        private val onDone: (Result<String>) -> Unit,
    ) : RecognitionListener {

        private val main = Handler(Looper.getMainLooper())
        private var recognizer: SpeechRecognizer? = null
        private var settled = false

        private companion object {
            /**
             * The shortest the recogniser is allowed to hold the microphone.
             *
             * Not a guess: 230ms of it is this engine's own start-up before the
             * microphone opens, measured on this phone, and the rest is enough
             * of a word for it to have something to decide about.
             */
            const val MIN_LISTEN_MS = 900L

            /**
             * How many times one hold may re-open the microphone. See [ended].
             *
             * A ceiling, not a limit anybody should meet: a segment is a
             * sentence or a pause, so sixty of them is several minutes of
             * talking. It exists so that a recogniser failing in a loop stops.
             */
            const val MAX_SEGMENTS = 60

            /**
             * Segments that ended almost as soon as they began, with nothing
             * heard, before this gives up. The shape of a recogniser that is
             * not going to work at all, as opposed to a pause for thought.
             */
            const val MAX_DUDS = 3

            /** Under this, a silent segment counts towards [MAX_DUDS]. */
            const val DUD_MS = 400L

            /**
             * The errors that mean "nothing that time" rather than "this will
             * not work". Only these are worth listening again after.
             */
            val RESTARTABLE = setOf(
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            )
        }

        /** Everything this hold has heard so far, oldest segment first. */
        private val heard = mutableListOf<String>()

        /** Set by [stop]: the key is up, so the next answer is the last one. */
        @Volatile private var stopping = false

        /** How many times [listen] has been called on this session. */
        private var segments = 0

        /** Consecutive segments that ended at once with nothing. See [ended]. */
        private var duds = 0

        /** A queued "listen again", so that [stop] can take it back. */
        private var again: Runnable? = null

        /** When the current segment asked for the microphone. */
        private var segmentAt = 0L

        /** True once the recogniser said it heard the microphone open. */
        @Volatile var started = false
            private set

        fun start(): Boolean {
            if (!isAvailable(context)) {
                settle(Result.failure(IllegalStateException("No speech recogniser on this phone")))
                return false
            }
            return runCatching {
                val sr =
                    if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    else SpeechRecognizer.createSpeechRecognizer(context)
                recognizer = sr
                sr.setRecognitionListener(this)
                listen()
                Log.i(
                    TAG,
                    "listening in $languageTag on the " +
                        "${if (onDevice) "on-device" else "default"} recogniser, " +
                        "offline=$preferOffline",
                )
                true
            }.getOrElse {
                Log.w(TAG, "could not start", it)
                settle(Result.failure(it))
                false
            }
        }

        /**
         * Open the microphone for one segment.
         *
         * Deliberately the *same* [SpeechRecognizer] every time. Creating a
         * second one to listen again is what broke this: the first one's
         * `destroy()` was posted rather than run, so it landed a few
         * milliseconds *after* the replacement had bound, and took the
         * connection with it — `error 11 (SERVER_DISCONNECTED)`, five
         * milliseconds after `startListening`, every hold that outlived one
         * sentence. Re-using the binding also skips the engine's 230ms
         * start-up, so the gap between segments is as short as it can be.
         */
        private fun listen() {
            val sr = recognizer ?: error("the recogniser was already released")
            readyAt = 0L
            segments++
            segmentAt = SystemClock.uptimeMillis()
            sr.startListening(intent())
        }

        private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            // Without this the recogniser is free to answer in the phone's
            // language when it does not like the one it was asked for, which is
            // the one thing a language setting must not do.
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Not forced. `true` here means "refuse rather than go online", and
            // that is what put a hundred languages out of reach: only the packs
            // this phone happens to have downloaded worked at all, and Ukrainian
            // — which Google recognises perfectly well — failed as though it did
            // not exist. Left unset, Android still uses the on-device pack when
            // there is one and only reaches the network when there is not.
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // **No timing extras.** There were three — a 500ms minimum length and
            // two 10s silence windows — and on this phone the recogniser closed
            // the microphone 500ms after opening it, every single time,
            // regardless of whether anybody was still speaking or still holding
            // the key. Measured: `onMicrophoneOpened` at t, `onStartOfSpeech` at
            // t+500, `onMicrophoneCloseRequested` at t+500, `MIC_END_OF_DATA`,
            // `NO_SPEECH_DETECTED`, `ERROR_NO_MATCH`. Android System
            // Intelligence appears to read `MINIMUM_LENGTH` as the length.
            //
            // They were only ever hints the recogniser was free to ignore, so
            // nothing is lost by not sending them and the defaults are sane. If
            // they ever go back in, the thing to watch is the gap between
            // `onMicrophoneOpened` and `onMicrophoneCloseRequested` in logcat —
            // it should be as long as the key is held, not a round number.
        }

        /**
         * The key was released: ask for the final transcript — but not before
         * the recogniser has had the microphone long enough to hear anything.
         *
         * This engine does not start when it is told to. Measured on this phone:
         * `startListening` at t=0, the recogniser's own microphone opens at
         * t+230ms, and a key released a second after it went down had the mic
         * open for 310ms — during which SODA reported `MIC_END_OF_DATA` and
         * `NO_SPEECH_DETECTED`, which arrives here as `ERROR_NO_MATCH`. Its
         * `onStartOfSpeech` landed *after* the stop. So a perfectly ordinary
         * hold produced "nothing was recognised" every time, and whisper had no
         * such problem because [com.ishaan.essentialvoice.voice.Recorder] is
         * already capturing by then.
         *
         * There is no way to make it open sooner, so the stop waits instead. The
         * pill has already gone to Thinking, so what this costs is a fraction of
         * a second of a state that was going to be on screen anyway.
         */
        fun stop() {
            stopping = true

            // Released in the gap between two segments: there is no microphone
            // to ask for a final answer, so the answer is what is already in
            // hand. Without this the queued restart would open the microphone
            // after the key came up and then be stopped a moment later, which
            // costs a second and can only add a segment of silence.
            again?.let {
                main.removeCallbacks(it)
                again = null
                Log.i(TAG, "stop() asked between segments; finishing with what is heard")
                main.post { finish(null) }
                return
            }

            val wait = when {
                // Words already in hand, so the warm-up window has been served
                // by an earlier segment and this one need not serve it again.
                heard.isNotEmpty() -> 0L
                // Never became ready at all. Give it the whole window rather
                // than none of it — this is exactly the case that was failing.
                readyAt == 0L -> MIN_LISTEN_MS
                else -> (readyAt + MIN_LISTEN_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)
            }
            Log.i(TAG, "stop() asked; waiting ${wait}ms before stopListening")
            main.postDelayed({
                Log.i(TAG, "stopListening now")
                runCatching { recognizer?.stopListening() }
                    .onFailure { Log.w(TAG, "stopListening failed", it) }
            }, wait)
        }

        /** Give up on this dictation without waiting for a result. */
        fun cancel() = main.post {
            Log.i(TAG, "cancel()")
            stopping = true
            settled = true
            runCatching { recognizer?.cancel() }
            release()
        }

        // ---- RecognitionListener ------------------------------------------

        /** When the recogniser said it had the microphone. See [stop]. */
        @Volatile private var readyAt = 0L

        override fun onReadyForSpeech(params: Bundle?) {
            // A recogniser that has been let go can still deliver one more
            // callback, and this one *starts a clock*. Without this guard the
            // tick below is posted after [release] took it away, and then
            // nothing ever takes it away again: it reposts itself every 33ms
            // for the life of the process, pushing levels into whichever
            // dictation happens to be on screen later. On whisper — whose own
            // microphone is already pushing one level per buffer — that reads
            // as a pill scrolling at twice the speed, with no dictation of its
            // own anywhere to blame.
            if (settled) return
            started = true
            readyAt = SystemClock.uptimeMillis()
            // Removed first: every segment says it is ready, and a second
            // posted copy of the tick would ease the pill twice as fast as the
            // one before it, then three times, for as long as the key is down.
            main.removeCallbacks(pulse)
            main.post(pulse)
        }
        override fun onBeginningOfSpeech() { Log.i(TAG, "beginning of speech") }
        override fun onEndOfSpeech() { Log.i(TAG, "end of speech") }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        /**
         * The recogniser's level, in decibels, as the pill's 0..1.
         *
         * Documented as roughly -2 to 10 dB and not clamped to it, which is why
         * this coerces. It is not the same measurement as [com.ishaan.essentialvoice.voice.Recorder]'s
         * peak, so the pill moves a little differently on this engine; matching
         * them exactly would mean inventing a calibration neither side agrees on.
         */
        override fun onRmsChanged(rmsdB: Float) {
            if (settled) return
            target = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        /**
         * The last level the recogniser reported, and the one being shown.
         *
         * They are two numbers because the recogniser only speaks about ten
         * times a second, and the pill draws a dot per level it is handed —
         * so fed straight through, the row moved in ten visible steps a second
         * while whisper's own microphone loop was feeding it three to five times
         * as many. It did not look like a quieter waveform, it looked like a
         * dropped-frame one.
         *
         * So the reports set a target and a steady 33ms tick eases towards it.
         * The rate is now the pill's, not the recogniser's, and what the
         * recogniser says only decides where the dots are heading.
         */
        @Volatile private var target = 0f
        private var shown = 0f

        private val pulse = object : Runnable {
            override fun run() {
                // The clock stops itself as well as being stopped. Every other
                // guard here is about not *starting* a stray tick; this is the
                // one that ends one that got started anyway.
                if (settled) return
                shown += (target - shown) * 0.35f
                onLevel(shown)
                main.postDelayed(this, 33)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (settled) return
            val text = first(partialResults) ?: return
            if (text.isNotBlank()) onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            val text = first(results).orEmpty()
            Log.i(TAG, "segment $segments: \"$text\"")
            ended(text.trim().ifBlank { null }, null)
        }

        override fun onError(error: Int) {
            // A recogniser that has already answered still reports "no match"
            // as it tears down; the first outcome is the real one.
            if (settled) return
            Log.w(TAG, "error $error (${name(error)}) on segment $segments")
            ended(null, error)
        }

        /**
         * One segment ended — which is not the same question as whether the
         * dictation did.
         *
         * This engine decides for itself when a sentence is over, and it is
         * generous about it: a pause for thought, or a moment of quiet before
         * the first word, and the microphone closes with the key still down.
         * There is no extra that turns that off — the silence windows are hints,
         * and the one this phone honoured closed the microphone 500ms in — so
         * "keep listening" has to mean listening again, on the same recogniser,
         * with the words so far kept. The dictation ends when [stop] says it
         * does, or when an error says this is not going to work at all.
         *
         * The two guards are for a recogniser that is failing rather than
         * pausing: [MAX_SEGMENTS] caps a very long hold, and [duds] stops the
         * loop when segments come back empty as fast as they are started.
         */
        private fun ended(text: String?, error: Int?) {
            if (settled) return
            if (text != null) heard += text

            val quick = SystemClock.uptimeMillis() - segmentAt < DUD_MS
            duds = if (text == null && quick) duds + 1 else 0

            val restartable = error == null || error in RESTARTABLE
            if (!stopping && restartable && segments < MAX_SEGMENTS && duds < MAX_DUDS) {
                // Posted rather than called: the recogniser is still inside its
                // own callback, and asking it to start again from in there is
                // how ERROR_RECOGNIZER_BUSY happens.
                val r = Runnable {
                    again = null
                    if (settled) return@Runnable
                    if (stopping) {
                        finish(null)
                        return@Runnable
                    }
                    runCatching { listen() }.onFailure {
                        Log.w(TAG, "could not listen again", it)
                        finish(error)
                    }
                }
                again = r
                main.post(r)
                return
            }
            finish(error)
        }

        /**
         * No more segments: hand back everything this hold heard.
         *
         * An error only fails the dictation when it left nothing behind. Once
         * a single segment has words in it, a later "no match" is the sound of
         * the recogniser being switched off mid-sentence, not of a failure.
         */
        private fun finish(error: Int?) {
            val whole = heard.joinToString(" ").trim()
            when {
                whole.isNotEmpty() -> {
                    Log.i(TAG, "heard over $segments segment(s): \"$whole\"")
                    settle(Result.success(whole))
                }
                error != null -> settle(Result.failure(SpeechError(error, message(error))))
                else -> settle(Result.success(""))
            }
        }

        private fun first(b: Bundle?): String? =
            b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

        private fun settle(result: Result<String>) {
            if (settled) return
            settled = true
            release()
            onDone(result)
        }

        private fun release() {
            main.removeCallbacks(pulse)
            again?.let { main.removeCallbacks(it) }
            again = null
            val sr = recognizer ?: return
            recognizer = null
            // Destroyed here and not a message later. A posted destroy runs
            // after whatever the result callback went on to do, and when that
            // was "start listening again" the teardown arrived on top of the
            // new session. See [listen].
            if (Looper.myLooper() == Looper.getMainLooper()) {
                runCatching { sr.destroy() }
            } else {
                main.post { runCatching { sr.destroy() } }
            }
        }

        private fun message(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "The recogniser could not open the microphone"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                "Google's recogniser was refused the microphone"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                if (preferOffline) {
                    "That language is not downloaded for offline use"
                } else {
                    "No network, and offline use is switched off"
                }
            SpeechRecognizer.ERROR_NO_MATCH ->
                if (readyAt == 0L) {
                    "Google's recogniser never opened the microphone"
                } else {
                    "Nothing was recognised — try holding a moment longer"
                }
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nothing was said"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recogniser is busy"
            SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                "Google's recogniser stopped answering"
            SpeechRecognizer.ERROR_CLIENT -> "The recogniser rejected the request"
            else ->
                if (Build.VERSION.SDK_INT >= 33 && (error == 12 || error == 13)) {
                    // ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE.
                    // The actionable one: offline recognition for a language is
                    // a pack the user installs in the phone's settings, and the
                    // app cannot install it for them.
                    "Download this language under Voice input → offline speech recognition"
                } else {
                    "The recogniser failed ($error)"
                }
        }

    }

    private fun name(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "CANNOT_CHECK_SUPPORT"
        else -> "?"
        }

    /** Carries the code as well as the words, so callers can tell them apart. */
    class SpeechError(val code: Int, message: String) : Exception(message)
}
