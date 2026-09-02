package com.ishaan.essentialvoice.whisper

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resumable model download. A half-megabyte tier is one thing; "Maximum" is
 * 574MB, so a dropped connection must not mean starting over — the partial file
 * is kept as .part and continued with a Range request.
 */
object ModelDownloader {

    sealed interface State {
        data object Idle : State
        data class Running(val tierId: String, val done: Long, val total: Long) : State {
            val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
        }
        data class Failed(val tierId: String, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    @Volatile private var cancelRequested = false

    fun cancel() { cancelRequested = true }

    /** Returns true when the model file ends up complete and the right size. */
    suspend fun download(context: Context, tier: QualityTier): Boolean = withContext(Dispatchers.IO) {
        cancelRequested = false
        // Which of the tier's two files depends on the language that is set
        // *now*. The .part is named after the file, not the tier, so a language
        // switched mid-download leaves the old partial where its own retry will
        // find it rather than overwriting it with a different model.
        val variant = tier.model
        val target = variant.file(context)
        if (variant.isInstalled(context)) {
            _state.value = State.Idle
            return@withContext true
        }

        val part = File(target.parentFile, variant.fileName + ".part")
        var done = if (part.isFile) part.length() else 0L
        // A .part bigger than the finished file means a stale or corrupt attempt.
        if (done > variant.bytes) { part.delete(); done = 0L }

        _state.value = State.Running(tier.id, done, variant.bytes)

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(variant.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                if (done > 0) setRequestProperty("Range", "bytes=$done-")
            }
            val code = conn.responseCode

            // 200 on a resume attempt means the server ignored Range: start over.
            if (done > 0 && code == HttpURLConnection.HTTP_OK) {
                part.delete()
                done = 0L
            } else if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                _state.value = State.Failed(tier.id, "Server returned $code")
                return@withContext false
            }

            RandomAccessFile(part, "rw").use { out ->
                out.seek(done)
                conn.inputStream.use { input ->
                    val buf = ByteArray(256 * 1024)
                    var lastPublish = 0L
                    while (true) {
                        if (cancelRequested) {
                            _state.value = State.Idle
                            return@withContext false
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        // Publishing every chunk would spam the UI thread; 400KB is plenty.
                        if (done - lastPublish > 400_000) {
                            lastPublish = done
                            _state.value = State.Running(tier.id, done, variant.bytes)
                        }
                    }
                }
            }

            if (part.length() != variant.bytes) {
                _state.value = State.Failed(
                    tier.id, "Incomplete: got ${part.length() / 1_000_000}MB of ${variant.sizeMb}MB"
                )
                return@withContext false
            }

            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                _state.value = State.Failed(tier.id, "Could not move file into place")
                return@withContext false
            }

            installedChanged()
            _state.value = State.Idle
            true
        } catch (t: Throwable) {
            _state.value = State.Failed(tier.id, t.message ?: t.javaClass.simpleName)
            false
        } finally {
            conn?.disconnect()
        }
    }

    /** Deletes the variant for the current language, which is the one shown. */
    fun delete(context: Context, tier: QualityTier) {
        val variant = tier.model
        variant.file(context).delete()
        File(ModelCatalog.dir(context), variant.fileName + ".part").delete()
        installedChanged()
    }

    /**
     * Bumped whenever a model file appears or disappears.
     *
     * Which models are on disk is read with `File.isFile`, and a file read is
     * not a state read — so nothing recomposed when one was deleted and the
     * card went on offering Delete for a model that was already gone. Download
     * never showed the bug because [state] is a flow and the card was
     * recomposing for the progress anyway. This is the same fix `SetupState`
     * already applies to permissions, for the same reason.
     */
    private val _installed = MutableStateFlow(0)
    val installed: StateFlow<Int> = _installed

    internal fun installedChanged() { _installed.value++ }
}
