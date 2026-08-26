package com.ishaan.essentialvoice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Tells you a newer build exists. Deliberately does not install it.
 *
 * Downloading and installing an APK needs REQUEST_INSTALL_PACKAGES, and an app
 * that already declares an accessibility service, the microphone and an overlay
 * reads to a malware scanner as a banking trojan the moment it can also install
 * software. Play Protect blocks sideloaded accessibility apps on principle; there
 * is no reason to hand it a second, avoidable reason to shout.
 *
 * So this reads the manifest and opens the release page in a browser. The
 * download and install are the browser's job — or Obtainium's, for anyone who
 * wants it automatic. No extra permission, and nothing here can install anything.
 *
 * The manifest:
 *
 * ```json
 * {
 *   "versionCode": 2,
 *   "versionName": "1.1",
 *   "url": "https://…/essential-voice-1.1.apk",
 *   "page": "https://…/releases/tag/v1.1",
 *   "notes": "What changed, in a sentence.",
 *   "whatsNew": [
 *     { "title": "Heading", "body": "A line.", "image": "https://…/shot.png" }
 *   ]
 * }
 * ```
 *
 * `whatsNew` is optional and every field in an entry except `title` is too. It
 * is the only place a picture can come from, since a picture of a feature is
 * made after the build containing it has already been signed.
 */
object Updater {

    data class Release(
        val versionCode: Int,
        val versionName: String,
        val url: String,
        /** Where a human should be sent. Falls back to the APK itself. */
        val page: String,
        val notes: String,
        /** The short list of what this release changed. May be empty. */
        val whatsNew: List<WhatsNew.Item> = emptyList(),
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        /**
         * Nothing newer than what is installed. It still carries the release,
         * because the manifest is then describing the installed build — which
         * is the only way its What's new pictures can ever be shown.
         */
        data class UpToDate(val checkedAt: Long, val release: Release) : State
        data class Available(val release: Release) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun installedVersionCode(context: Context): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION") info.versionCode
        }
    }

    fun installedVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    /**
     * A manifest baked into debug builds, if one is there.
     *
     * The pictures in `whatsNew` are uploaded *after* a release is cut, so on a
     * build nobody has released yet there is nothing to point at and no way to
     * see whether that part of the panel works. This lets a debug build carry a
     * pretend manifest — `asset:` picture URLs included — so the whole panel can
     * be looked at before anything is published. Release builds never read it;
     * the file only exists in the debug source set.
     */
    private fun debugManifest(context: Context): String? {
        if (!BuildConfig.DEBUG) return null
        return runCatching {
            context.assets.open(DEBUG_MANIFEST).bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    private fun download(): String {
        val conn = (URL(BuildConfig.UPDATE_MANIFEST_URL).openConnection() as HttpURLConnection)
            .apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("Cache-Control", "no-cache")
            }
        // HttpURLConnection is not Closeable; it is disconnected, not used.
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    suspend fun check(context: Context): State = withContext(Dispatchers.IO) {
        _state.value = State.Checking
        val result = runCatching {
            val body = debugManifest(context) ?: download()
            val o = JSONObject(body)
            val url = o.getString("url")
            Release(
                versionCode = o.getInt("versionCode"),
                versionName = o.optString("versionName", "?"),
                url = url,
                page = o.optString("page", "").ifBlank { url },
                notes = o.optString("notes", ""),
                whatsNew = parseWhatsNew(o.optJSONArray("whatsNew")),
            )
        }

        val next = result.fold(
            onSuccess = { release ->
                if (release.versionCode > installedVersionCode(context)) {
                    State.Available(release)
                } else {
                    State.UpToDate(System.currentTimeMillis(), release)
                }
            },
            onFailure = { State.Failed(readableError(it)) },
        )
        _state.value = next
        next
    }

    /**
     * Say what went wrong in a sentence a person can act on.
     *
     * The exception's own message used to go straight to the screen, so a weak
     * signal produced "Unable to resolve host raw.githubusercontent.com: No
     * address associated with hostname" — which is true, and tells someone
     * holding a phone nothing. Every failure here is one of three things: no
     * connection, a server that did not answer, or a manifest that did not
     * parse. Those are worth telling apart; the stack trace is not.
     */
    private fun readableError(t: Throwable): String = when (t) {
        is UnknownHostException ->
            "No internet connection. The check needs one \u2014 it reads a small " +
                "file from GitHub."
        is SocketTimeoutException ->
            "The update server took too long to answer. Try again in a moment."
        is JSONException ->
            "The update server answered with something unreadable."
        is IOException ->
            "Could not reach the update server. Check your connection."
        else -> t.message ?: "The update check did not work."
    }

    /**
     * A malformed entry is skipped rather than failing the whole check: a typo
     * in a changelog must never be able to stop the app noticing an update.
     */
    private fun parseWhatsNew(array: JSONArray?): List<WhatsNew.Item> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("title").trim()
            val body = o.optString("body").trim()
            if (title.isEmpty() && body.isEmpty()) return@mapNotNull null
            WhatsNew.Item(
                title = title,
                body = body,
                image = o.optString("image").trim().ifBlank { null },
            )
        }
    }

    /** Hand the release page to a browser. Nothing here installs anything. */
    fun openRelease(context: Context, release: Release) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(release.page))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun reset() { _state.value = State.Idle }

    private const val DEBUG_MANIFEST = "update-debug.json"
}
