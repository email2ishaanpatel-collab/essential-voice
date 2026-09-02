package com.ishaan.essentialvoice.media

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.ishaan.essentialvoice.island.Island
import com.ishaan.essentialvoice.notify.Feed

/**
 * The notification listener: the island's window onto calls, timers and alerts.
 *
 * It was first added for one narrow reason.
 * `MediaSessionManager.getActiveSessions` will only answer a caller that holds
 * MEDIA_CONTENT_CONTROL (signature|privileged, which a sideloaded app can never
 * have) or names an **enabled notification listener** — so declaring this
 * service is what makes the app eligible, and the component name is handed to
 * `getActiveSessions` as the credential. See [NowPlaying] for the five other
 * routes that were measured and are shut.
 *
 * It now also reads notifications, which is a real change and is worth stating
 * plainly rather than burying: the ringing call, the counting timer and the
 * alert that just arrived all come from here, through [Feed]. **Nothing is
 * stored and nothing leaves the device** — [Feed] holds at most one call, one
 * timer and one alert in memory, replaces them as they change, and drops them
 * when the notification is dismissed. There is no history, no file and no
 * network anywhere on this path.
 *
 * Reading the dialer's notification is also what keeps three more permissions
 * off the manifest: answering a call this way needs no READ_PHONE_STATE, no
 * READ_CALL_LOG and no ANSWER_PHONE_CALLS, and it works for WhatsApp and Signal
 * calls as well as cellular ones.
 *
 * **The class name is load-bearing.** Notification access is granted per
 * *component*, so renaming this class — however much "MediaObserver" now
 * undersells it — silently revokes the grant on every phone it is already
 * enabled on, with no error and no prompt. It stays.
 *
 * Play Protect blocks a sideloaded APK that declares this. It already blocked
 * this one for declaring an accessibility service, so the install path does not
 * change; see the README.
 */
class MediaObserver : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        NowPlaying.onListenerReady()
        // The call that is already ringing, the timer already counting: a
        // listener is only told about *changes*, so the state at connect has to
        // be read out of the current list or it is invisible until it changes.
        runCatching { activeNotifications }.getOrNull()?.forEach {
            runCatching { Feed.onPosted(this, it) }
        }
    }

    override fun onListenerDisconnected() {
        instance = null
        NowPlaying.onListenerLost()
        Feed.reset()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val peeked = runCatching { Feed.onPosted(this, sbn) }
            .onFailure { Log.w("EVFeed", "posted read failed", it) }
            .getOrDefault(false)
        if (peeked) suppressBanner(sbn)
    }

    /**
     * Take Android's own heads-up banner away from a notification the island is
     * about to show.
     *
     * Two surfaces announcing one message is the thing the island was supposed
     * to replace, not add to. There is no public API that says "post this
     * quietly": the one that exists is `NotificationAssistantService`, which can
     * downgrade importance before the banner is built, and it is a hidden
     * @SystemApi held on this phone by Google's ext.services — not available.
     *
     * So the banner is pulled rather than prevented. Snoozing removes it from
     * the shade *and* takes the heads-up down with it, and an unsnoozed
     * notification is re-posted without alerting again — which is exactly the
     * shape wanted here: the island announces it, and it is waiting in the shade
     * afterwards. The snooze is timed to the peek so the two hand over cleanly.
     *
     * The honest cost is that this is reactive: the listener is called as the
     * notification is posted, so a frame or two of banner can appear before it
     * is pulled. That is measurable and was measured; see DEVELOPING.md.
     *
     * [suppressed] is what stops this eating itself. Unsnoozing re-posts the
     * notification, which calls this back with the same key, which would snooze
     * it again forever. A key is suppressed once and then left alone.
     */
    private fun suppressBanner(sbn: StatusBarNotification) {
        // Only ever on the island's behalf. With no island on screen there is
        // nothing replacing the banner, and a silenced notification would
        // simply be a lost one.
        if (!Island.isLive) return
        val key = sbn.key ?: return
        synchronized(suppressed) {
            if (!suppressed.add(key)) return
            // Bounded: the island holds one alert, but this set would otherwise
            // grow for the life of the process.
            if (suppressed.size > SUPPRESSED_MAX) {
                val it = suppressed.iterator()
                it.next()
                it.remove()
            }
        }
        runCatching { snoozeNotification(key, SNOOZE_MS) }
            .onFailure { Log.w("EVFeed", "could not pull the banner", it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        runCatching { Feed.onRemoved(sbn) }
    }

    companion object {

        /**
         * How long a peeked notification is held out of the shade.
         *
         * Matched to the island's own peek so that the notification reappears
         * about when the island finishes showing it — one hand-over, not a gap
         * and then a surprise.
         */
        private const val SNOOZE_MS = 4200L

        /** Keys whose banner has already been pulled; see suppressBanner. */
        private val suppressed = LinkedHashSet<String>()

        private const val SUPPRESSED_MAX = 64

        /**
         * The bound listener, or null.
         *
         * Held so that game mode can ask for Do Not Disturb through it. An
         * enabled notification listener may set the interruption filter, and
         * this app has one for the island — which means the quietest half of
         * game mode costs nothing extra to have. `requestInterruptionFilter` is
         * an instance method on the service, so there has to be an instance to
         * call it on; see [com.ishaan.essentialvoice.game.Levers].
         */
        @Volatile
        var instance: MediaObserver? = null
            private set

        fun component(context: Context) =
            ComponentName(context.applicationContext, MediaObserver::class.java)

        /**
         * Read from Settings rather than from whether this service happens to be
         * alive: the system does not bind it until access is granted, so its own
         * state cannot answer the question of whether it is allowed to run.
         *
         * The stored value is a colon-separated list of flattened components,
         * and a package can appear in it under a component that no longer
         * exists, so the match is on this exact component.
         */
        fun isEnabled(context: Context): Boolean {
            val want = component(context)
            val raw = runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_notification_listeners",
                )
            }.getOrNull() ?: return false
            return raw.split(':').any {
                val c = ComponentName.unflattenFromString(it)
                c != null && c == want
            }
        }
    }
}
