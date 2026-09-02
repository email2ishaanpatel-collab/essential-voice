package com.ishaan.essentialvoice.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * What is playing, and the controls for it.
 *
 * ------------------------------------------------- every route, and which shut
 *
 * Measured on the phone (A001, Android 16) with the accessibility service bound
 * and no notification access. Recorded so nobody re-derives it:
 *
 * | Route | Result |
 * |---|---|
 * | `getActiveSessions(self)` | SecurityException: Missing permission to control media |
 * | `getActiveSessions(null)` | same |
 * | `getMediaKeyEventSession()` | same |
 * | `getMediaKeyEventSessionPackageName()` | same |
 * | `pm grant MEDIA_CONTENT_CONTROL` | "not a changeable permission type" |
 * | token off a notification, via accessibility | see below |
 *
 * That last one looked like the way through, and is the interesting failure.
 * `getActiveSessions` is gated because it *enumerates* sessions you were never
 * given; holding a `MediaSession.Token` is a different thing, and
 * `MediaController(context, token)` is ungated. A MediaStyle notification
 * carries its own token in `EXTRA_MEDIA_SESSION`, and an accessibility service
 * subscribed to TYPE_NOTIFICATION_STATE_CHANGED is handed the posted
 * Notification — so the player ought to hand the token over the front door.
 *
 * It does not, for two independent reasons:
 *
 *  1. **The Notification is not attached any more.** The event arrives and
 *     `parcelableData` is null:
 *
 *         EVENT type=TYPE_NOTIFICATION_STATE_CHANGED pkg=com.android.shell data=null
 *
 *  2. **A media notification does not fire the event at all.** Spotify played
 *     throughout; pause, play and skip-to-next produced no event of any type.
 *     Only a freshly posted notification did. Ongoing, silently updated
 *     notifications are not announced.
 *
 * So [MediaObserver] exists, and this reads sessions the ordinary way — which is
 * the better API regardless, because it has no cold start: a track already
 * playing when the service binds is visible immediately rather than at the next
 * track change.
 *
 * ------------------------------------------------------------------- lifetime
 *
 * The controller is swapped, never accumulated. `getActiveSessions` returns
 * every session on the phone in priority order and the first is the one the user
 * means — media keys go to the same one, so the island and the headset button
 * can never disagree about what they are controlling. A callback is registered
 * on whichever that is and unregistered the moment it stops being it, so a
 * paused browser tab from an hour ago cannot go on calling back.
 */
object NowPlaying {

    private const val TAG = "EVMedia"

    private val handler = Handler(Looper.getMainLooper())

    private var app: Context? = null
    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var watching = false

    /** Rung on the main thread whenever anything below it changes. */
    var onChange: (() -> Unit)? = null

    /** The player's package — the app icon on the collapsed island. */
    var packageName: String? = null
        private set

    var title: String? = null
        private set
    var artist: String? = null
        private set
    var art: Bitmap? = null
        private set
    var playing: Boolean = false
        private set

    /** Milliseconds throughout: MediaSession has no microseconds anywhere. */
    var duration: Long = 0L
        private set

    /** Whether there is a session worth drawing. */
    val isActive: Boolean get() = controller != null && (title != null || art != null)

    private var stateAt: Long = 0L
    private var statePos: Long = 0L
    private var speed: Float = 0f

    /**
     * Where the track is *now*.
     *
     * PlaybackState reports a position and the moment it was measured, then says
     * nothing until something changes — so the live position is that reading
     * carried forward at the playback speed. Asking the session again returns
     * the same stale pair, which is why this is arithmetic and not a poll.
     */
    val position: Long
        get() {
            if (stateAt <= 0L) return statePos
            val since = SystemClock.elapsedRealtime() - stateAt
            val p = statePos + (since * speed).toLong()
            return if (duration > 0) p.coerceIn(0, duration) else p.coerceAtLeast(0)
        }

    fun attach(context: Context) {
        app = context.applicationContext
        manager = runCatching {
            context.getSystemService(MediaSessionManager::class.java)
        }.getOrNull()
        startWatching()
        refresh()
    }

    fun detach() {
        stopWatching()
        release()
        onChange = null
        manager = null
        app = null
    }

    /** [MediaObserver] is bound, so the credential is live. */
    fun onListenerReady() {
        handler.post {
            startWatching()
            refresh()
        }
    }

    fun onListenerLost() {
        handler.post {
            stopWatching()
            release()
            notifyChanged()
        }
    }

    /* ------------------------------------------------------------- discovery */

    private val sessionsChanged =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> adopt(list) }

    private fun startWatching() {
        val ctx = app ?: return
        val m = manager ?: return
        if (watching) return
        runCatching {
            m.addOnActiveSessionsChangedListener(
                sessionsChanged,
                MediaObserver.component(ctx),
                handler,
            )
            watching = true
        }.onFailure { Log.i(TAG, "cannot watch sessions yet: ${it.javaClass.simpleName}") }
    }

    private fun stopWatching() {
        val m = manager ?: return
        if (!watching) return
        runCatching { m.removeOnActiveSessionsChangedListener(sessionsChanged) }
        watching = false
    }

    /**
     * Re-read the session list now.
     *
     * Safe to call before notification access has been granted: it fails with
     * the SecurityException in the table above, which is caught and read as
     * "nothing playing" rather than taking down the service hosting the island.
     */
    fun refresh() {
        val ctx = app ?: return
        val m = manager ?: return
        val list = runCatching { m.getActiveSessions(MediaObserver.component(ctx)) }
            .onFailure { Log.i(TAG, "no session access yet: ${it.javaClass.simpleName}") }
            .getOrNull() ?: return
        adopt(list)
    }

    /** Take the session at the head of the list; see the note on priority above. */
    private fun adopt(list: List<MediaController>?) {
        val next = list?.firstOrNull { it.playbackState != null || it.metadata != null }
        if (next == null) {
            if (controller != null) {
                release()
                notifyChanged()
            }
            return
        }
        if (next.sessionToken == controller?.sessionToken) {
            // Same session; its own callback is already delivering the changes.
            return
        }

        release()
        controller = next
        packageName = next.packageName
        runCatching { next.registerCallback(callback, handler) }
        readMetadata(next.metadata)
        readState(next.playbackState)
        Log.i(TAG, "now playing via ${next.packageName}: $title")
        notifyChanged()
    }

    private fun release() {
        controller?.let { runCatching { it.unregisterCallback(callback) } }
        controller = null
        packageName = null
        title = null
        artist = null
        art = null
        playing = false
        duration = 0L
        stateAt = 0L
        statePos = 0L
        speed = 0f
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            readMetadata(metadata)
            notifyChanged()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            readState(state)
            notifyChanged()
        }

        override fun onSessionDestroyed() {
            release()
            refresh()
            notifyChanged()
        }
    }

    private fun readMetadata(m: MediaMetadata?) {
        if (m == null) {
            title = null
            artist = null
            art = null
            duration = 0L
            return
        }
        title = m.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        // In preference order: players fill these inconsistently, and the first
        // one present is the one that is actually the cover.
        art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
    }

    private fun readState(s: PlaybackState?) {
        if (s == null) {
            playing = false
            speed = 0f
            return
        }
        playing = s.state == PlaybackState.STATE_PLAYING
        statePos = s.position.coerceAtLeast(0L)
        stateAt = s.lastPositionUpdateTime
        // A paused track does not advance, whatever speed the player reports.
        speed = if (playing) s.playbackSpeed else 0f
    }

    /* ------------------------------------------------------------- transport */

    fun toggle() {
        val c = controller ?: return
        if (playing) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() {
        controller?.transportControls?.skipToNext()
    }

    fun previous() {
        controller?.transportControls?.skipToPrevious()
    }

    fun seekTo(ms: Long) {
        val d = duration
        controller?.transportControls?.seekTo(
            if (d > 0) ms.coerceIn(0L, d) else ms.coerceAtLeast(0L),
        )
    }

    private fun notifyChanged() {
        handler.post { onChange?.invoke() }
    }
}
