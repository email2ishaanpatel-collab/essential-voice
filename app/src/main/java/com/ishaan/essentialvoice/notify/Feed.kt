package com.ishaan.essentialvoice.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Everything the island shows that is not media or dictation: the call that is
 * ringing, the timer that is counting, and the notification that just arrived.
 *
 * All three come off the notification listener the media player already needed
 * ([com.ishaan.essentialvoice.media.MediaObserver]) — which is the reason they
 * are worth doing at all. The expensive permission is already paid for; these
 * are what it buys.
 *
 * ------------------------------------------------------------------- calls
 *
 * Read from the dialer's own notification rather than from telephony. That
 * avoids READ_PHONE_STATE, READ_CALL_LOG and ANSWER_PHONE_CALLS entirely — three
 * more permissions on an app that already asks for a lot — and it works for
 * anything that posts a call notification, so WhatsApp and Signal calls land on
 * the island the same way a cellular one does.
 *
 * Answering is the notification's own action, fired as the user. Android 12+
 * `CallStyle` notifications put those intents in named extras; older and
 * hand-rolled ones only have the action list, so both are read and the extras
 * win when present.
 *
 * -------------------------------------------------------------------- timers
 *
 * A countdown notification carries `EXTRA_SHOW_CHRONOMETER` with
 * `EXTRA_CHRONOMETER_COUNT_DOWN`, and its `when` is the moment it reaches zero.
 * So the remaining time is arithmetic on a single number and needs no polling of
 * anything — the island ticks its own clock against it.
 */
object Feed {

    private const val TAG = "EVFeed"

    private val handler = Handler(Looper.getMainLooper())

    /** Rung on the main thread whenever any of the three below changes. */
    var onChange: (() -> Unit)? = null

    var call: Call? = null
        private set
    var timer: Timer? = null
        private set

    /**
     * The most recent notification worth peeking at, or null.
     *
     * Transient by design: [Island] shows it briefly and then clears it. The
     * island is not a notification shade and must not become a list.
     */
    var alert: Alert? = null
        private set

    /* ------------------------------------------------------------- the shapes */

    /** `type` mirrors CallStyle's own `android.callType`. */
    data class Call(
        val key: String,
        val who: String,
        val ringing: Boolean,
        val icon: Bitmap?,
        val answer: PendingIntent?,
        val decline: PendingIntent?,
    )

    data class Timer(
        val key: String,
        val label: String,
        /** Wall clock at which it reaches zero, `System.currentTimeMillis()` base. */
        val endsAt: Long,
    ) {
        val remaining: Long get() = (endsAt - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    data class Alert(
        val key: String,
        val appName: String,
        val title: String,
        val text: String,
        val icon: Drawable?,
        val open: PendingIntent?,
        /**
         * What a tap does, when it is not "open the app that posted this".
         *
         * Set only for alerts the app raises itself — a Gemini answer, whose
         * tap pastes rather than launching anything. Preferred over [open] when
         * both exist, though in practice a locally-raised alert has no [open]
         * and a real notification has no [onTap].
         */
        val onTap: (() -> Unit)? = null,
        /**
         * How long this stays up. The default is a peek; an answer somebody is
         * waiting to *read* needs longer than one that only says something
         * arrived, and a question still being answered needs longer again.
         */
        val holdMs: Long = 4200L,
    )

    /* -------------------------------------------------------------- ingestion */

    /**
     * Take a posted notification.
     *
     * Returns true only when this became a *peek* — the island is about to show
     * it, and so the system's own banner is the duplicate. The caller uses that
     * answer to decide whether to suppress the banner, which is why it is a
     * return value rather than a second predicate: the decision has to be the
     * same one that produced the peek, or the two drift and a notification gets
     * silenced without ever being shown. Calls and timers deliberately return
     * false — they take over the island for as long as they last rather than
     * peeking, and a ringing call must keep every alert route the phone has.
     */
    fun onPosted(context: Context, sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        return when {
            isCall(n) -> { readCall(sbn, n); false }
            isCountdown(n) -> { readTimer(sbn, n); false }
            else -> readAlert(context, sbn, n)
        }
    }

    fun onRemoved(sbn: StatusBarNotification) {
        val key = sbn.key ?: return
        var changed = false
        if (call?.key == key) {
            call = null
            changed = true
        }
        if (timer?.key == key) {
            timer = null
            changed = true
        }
        if (alert?.key == key) {
            alert = null
            changed = true
        }
        if (changed) notifyChanged()
    }

    /**
     * Raise an alert the app produced itself, rather than one off a
     * notification.
     *
     * The island already knows how to show an alert and how to get out of the
     * way afterwards, so an answer from Gemini is not a new shape — it is the
     * same peek with different words in it and a different thing behind the
     * tap. Reusing it is what keeps the island one surface with a few states
     * instead of a drawer of special cases.
     *
     * The key is fixed per [source] so that a follow-up replaces the alert it
     * updates: "Thinking…" becoming the answer has to be one thing changing on
     * screen, not two peeks in a row.
     */
    fun raise(
        source: String,
        title: String,
        text: String,
        holdMs: Long = 4200L,
        onTap: (() -> Unit)? = null,
    ) {
        alert = Alert(
            key = "local:$source",
            appName = source,
            title = title,
            text = text,
            icon = null,
            open = null,
            onTap = onTap,
            holdMs = holdMs,
        )
        notifyChanged()
    }

    /** Called by [Island] once it has shown a peek, so it is not shown twice. */
    fun clearAlert() {
        if (alert != null) {
            alert = null
            notifyChanged()
        }
    }

    fun reset() {
        call = null
        timer = null
        alert = null
        notifyChanged()
    }

    /* ----------------------------------------------------------------- calls */

    private fun isCall(n: Notification): Boolean =
        n.category == Notification.CATEGORY_CALL

    private fun readCall(sbn: StatusBarNotification, n: Notification) {
        val extras = n.extras ?: return
        // CallStyle's own vocabulary: 1 incoming, 2 ongoing, 3 screening. A
        // notification without it is treated as ringing only if it still offers
        // something that looks like an answer.
        val type = runCatching { extras.getInt("android.callType", 0) }.getOrDefault(0)
        val answer = pendingExtra(extras, "android.answerIntent")
            ?: actionMatching(n, ANSWER_WORDS)
        val decline = pendingExtra(extras, "android.declineIntent")
            ?: pendingExtra(extras, "android.hangUpIntent")
            ?: actionMatching(n, DECLINE_WORDS)

        val who = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: "Unknown"

        val ringing = when (type) {
            1 -> true
            2, 3 -> false
            else -> answer != null
        }

        call = Call(
            key = sbn.key ?: return,
            who = who,
            ringing = ringing,
            icon = null,
            answer = answer,
            decline = decline,
        )
        Log.i(TAG, "call: $who ringing=$ringing answer=${answer != null}")
        notifyChanged()
    }

    private val ANSWER_WORDS = listOf("answer", "accept", "pick up")
    private val DECLINE_WORDS = listOf("decline", "reject", "hang up", "end call", "dismiss")

    private fun pendingExtra(extras: android.os.Bundle, key: String): PendingIntent? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable(key, PendingIntent::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable(key) as? PendingIntent
            }
        }.getOrNull()

    private fun actionMatching(n: Notification, words: List<String>): PendingIntent? {
        val actions = n.actions ?: return null
        for (a in actions) {
            val title = a.title?.toString()?.lowercase() ?: continue
            if (words.any { title.contains(it) }) return a.actionIntent
        }
        return null
    }

    /* ---------------------------------------------------------------- timers */

    private fun isCountdown(n: Notification): Boolean {
        val e = n.extras ?: return false
        val chrono = runCatching {
            e.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        }.getOrDefault(false)
        val down = runCatching {
            e.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false)
        }.getOrDefault(false)
        return chrono && down
    }

    private fun readTimer(sbn: StatusBarNotification, n: Notification) {
        val e = n.extras ?: return
        // `when` is the instant it hits zero for a counting-down chronometer.
        val endsAt = if (n.`when` > 0) n.`when` else return
        val label = e.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "Timer"
        timer = Timer(sbn.key ?: return, label, endsAt)
        notifyChanged()
    }

    /* ----------------------------------------------------------------- peeks */

    private fun readAlert(
        context: Context,
        sbn: StatusBarNotification,
        n: Notification,
    ): Boolean {
        // Anything the user is not meant to be interrupted by is not a peek:
        // ongoing work, group summaries, and the app's own notifications.
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (sbn.packageName == context.packageName) return false
        if (!sbn.isClearable) return false

        val e = n.extras ?: return false
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return false

        alert = Alert(
            key = sbn.key ?: return false,
            appName = appLabel(context, sbn.packageName),
            title = title,
            text = text,
            icon = runCatching {
                context.packageManager.getApplicationIcon(sbn.packageName)
            }.getOrNull(),
            open = n.contentIntent,
        )
        notifyChanged()
        return true
    }

    private fun appLabel(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun notifyChanged() {
        handler.post { onChange?.invoke() }
    }
}
