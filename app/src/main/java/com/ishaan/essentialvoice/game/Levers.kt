package com.ishaan.essentialvoice.game

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.ishaan.essentialvoice.media.MediaObserver

/**
 * The individual things game mode does to the phone, and the record of what
 * they were before.
 *
 * The whole file is built around one rule: **nothing is changed that cannot be
 * put back.** Every lever reads the current value first, and that reading is
 * written to disk before the change is made — not held in this process — because
 * the process that armed game mode is not necessarily the one that gets to
 * disarm it. An accessibility service is restarted by the system after a crash,
 * after an app update, and after the switch is toggled in Settings, and a phone
 * left with its rotation locked and its notifications off by a process that
 * died is the one failure this feature is not allowed to have. See
 * [GameMode.attach], which restores an orphaned snapshot before doing anything
 * else.
 *
 * The second rule is that a lever with no permission behind it does nothing and
 * says so, rather than throwing or pretending. [Grants] is what the settings
 * screen reads to show which is which.
 */
object Levers {

    private const val TAG = "EVGame"

    /**
     * What the phone looked like before game mode touched it.
     *
     * Every field is nullable and every null means "this was not changed, so
     * there is nothing to put back" — which is also what a lever that was
     * switched off, or that had no permission, leaves behind.
     */
    data class Snapshot(
        val rotation: Int? = null,
        val soundEffects: Int? = null,
        val hapticFeedback: Int? = null,
        val windowScale: Float? = null,
        val transitionScale: Float? = null,
        val animatorScale: Float? = null,
        val interruptionFilter: Int? = null,
    ) {
        /**
         * A flat `key=value;…` line, small enough to sit in SharedPreferences.
         *
         * Deliberately not JSON: this is written on every arm and read on every
         * service start, it has seven fields that will never be nested, and a
         * parser that cannot fail on anything but a value it wrote itself is
         * worth more here than a format.
         */
        fun encode(): String = buildList {
            rotation?.let { add("rot=$it") }
            soundEffects?.let { add("sfx=$it") }
            hapticFeedback?.let { add("haptic=$it") }
            windowScale?.let { add("anim_w=$it") }
            transitionScale?.let { add("anim_t=$it") }
            animatorScale?.let { add("anim_a=$it") }
            interruptionFilter?.let { add("dnd=$it") }
        }.joinToString(";")

        val isEmpty: Boolean get() = encode().isEmpty()

        companion object {
            fun decode(raw: String): Snapshot {
                if (raw.isBlank()) return Snapshot()
                val m = raw.split(';').mapNotNull {
                    val i = it.indexOf('=')
                    if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
                }.toMap()
                return Snapshot(
                    rotation = m["rot"]?.toIntOrNull(),
                    soundEffects = m["sfx"]?.toIntOrNull(),
                    hapticFeedback = m["haptic"]?.toIntOrNull(),
                    windowScale = m["anim_w"]?.toFloatOrNull(),
                    transitionScale = m["anim_t"]?.toFloatOrNull(),
                    animatorScale = m["anim_a"]?.toFloatOrNull(),
                    interruptionFilter = m["dnd"]?.toIntOrNull(),
                )
            }
        }
    }

    /* ---------------------------------------------------------- capture, apply */

    /**
     * Read down what [profile] is about to change, and change nothing.
     *
     * Split from [apply] so the caller can write this to disk **before** a single
     * setting is touched. The other order has a window — however small — in which
     * the levers are pulled and nothing on the phone records what they were, and
     * a process that dies inside that window strands the phone for good. See
     * [GameMode.pull], which is deliberately three statements long for this
     * reason.
     *
     * The guards here mirror [apply] exactly: a lever that is switched off, or
     * that has no permission behind it, changes nothing and so has nothing to
     * put back.
     */
    fun capture(context: Context, profile: GameProfile): Snapshot {
        var snap = Snapshot()

        if (Grants.canWriteSystem(context)) {
            if (profile.lockRotation) {
                snap = snap.copy(
                    rotation = readSystem(context, Settings.System.ACCELEROMETER_ROTATION),
                )
            }
            if (profile.quietTouch) {
                snap = snap.copy(
                    soundEffects = readSystem(context, Settings.System.SOUND_EFFECTS_ENABLED),
                    hapticFeedback = readSystem(context, Settings.System.HAPTIC_FEEDBACK_ENABLED),
                )
            }
        }

        if (profile.killAnimations && Grants.canWriteSecure(context)) {
            snap = snap.copy(
                windowScale = readGlobal(context, Settings.Global.WINDOW_ANIMATION_SCALE),
                transitionScale = readGlobal(context, Settings.Global.TRANSITION_ANIMATION_SCALE),
                animatorScale = readGlobal(context, Settings.Global.ANIMATOR_DURATION_SCALE),
            )
        }

        if (profile.silenceNotifications) {
            snap = snap.copy(interruptionFilter = readInterruptionFilter(context))
        }

        return snap
    }

    /**
     * Do everything [profile] asks for that this phone will allow.
     *
     * The only place in the app that changes a setting belonging to the phone
     * rather than to the app, and it is called only after [capture]'s answer is
     * safely on disk.
     */
    fun apply(context: Context, profile: GameProfile) {
        if (Grants.canWriteSystem(context)) {
            if (profile.lockRotation) {
                writeSystem(context, Settings.System.ACCELEROMETER_ROTATION, 0)
            }
            if (profile.quietTouch) {
                writeSystem(context, Settings.System.SOUND_EFFECTS_ENABLED, 0)
                writeSystem(context, Settings.System.HAPTIC_FEEDBACK_ENABLED, 0)
            }
        }

        if (profile.killAnimations && Grants.canWriteSecure(context)) {
            writeGlobal(context, Settings.Global.WINDOW_ANIMATION_SCALE, 0f)
            writeGlobal(context, Settings.Global.TRANSITION_ANIMATION_SCALE, 0f)
            writeGlobal(context, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        }

        if (profile.silenceNotifications) {
            // Priority rather than None, so an alarm and a phone call still get
            // through. A mode that swallows the alarm you set is a mode that
            // costs more than it saves.
            setInterruptionFilter(context, NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        }
    }

    /**
     * Put everything back.
     *
     * Best effort by design: this runs after a crash as often as it runs after a
     * tap, and a single setting that will not take must not stop the rest
     * being restored. Each write is on its own.
     */
    fun restore(context: Context, snap: Snapshot) {
        if (Grants.canWriteSystem(context)) {
            snap.rotation?.let { writeSystem(context, Settings.System.ACCELEROMETER_ROTATION, it) }
            snap.soundEffects?.let { writeSystem(context, Settings.System.SOUND_EFFECTS_ENABLED, it) }
            snap.hapticFeedback?.let {
                writeSystem(context, Settings.System.HAPTIC_FEEDBACK_ENABLED, it)
            }
        }
        if (Grants.canWriteSecure(context)) {
            snap.windowScale?.let { writeGlobal(context, Settings.Global.WINDOW_ANIMATION_SCALE, it) }
            snap.transitionScale?.let {
                writeGlobal(context, Settings.Global.TRANSITION_ANIMATION_SCALE, it)
            }
            snap.animatorScale?.let { writeGlobal(context, Settings.Global.ANIMATOR_DURATION_SCALE, it) }
        }
        snap.interruptionFilter?.let { setInterruptionFilter(context, it) }
    }

    /* ------------------------------------------------------- do not disturb */

    /**
     * Read the filter through the listener first.
     *
     * `NotificationManager.getCurrentInterruptionFilter` answers a caller with
     * notification-policy access; the listener's own copy of the same call
     * answers *it*, and this app has a listener long before it has policy
     * access. Unknown comes back as null, and a null is restored as
     * [NotificationManager.INTERRUPTION_FILTER_ALL] rather than left alone —
     * being unable to read the old value is not a reason to leave the phone
     * silent.
     */
    private fun readInterruptionFilter(context: Context): Int {
        runCatching { MediaObserver.instance?.currentInterruptionFilter }
            .getOrNull()
            ?.takeIf { it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
            ?.let { return it }
        runCatching { notificationManager(context).currentInterruptionFilter }
            .getOrNull()
            ?.takeIf { it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
            ?.let { return it }
        return NotificationManager.INTERRUPTION_FILTER_ALL
    }

    /**
     * Two routes, tried in order, and the return says whether either worked.
     *
     * The listener route is the one that costs nothing extra: an enabled
     * notification listener may ask for a filter, and this app has had one since
     * the island learned to show what is playing. The policy-access route is the
     * fallback for a phone where that is refused, and it is the reason the
     * settings screen offers "Do Not Disturb access" as optional rather than
     * required — it is only needed if the free route does not take.
     */
    private fun setInterruptionFilter(context: Context, filter: Int): Boolean {
        runCatching { MediaObserver.instance?.requestInterruptionFilter(filter) }
            .onFailure { Log.w(TAG, "listener refused the filter", it) }
        if (readInterruptionFilter(context) == filter) return true

        if (Grants.hasDndAccess(context)) {
            runCatching { notificationManager(context).setInterruptionFilter(filter) }
                .onFailure { Log.w(TAG, "policy route refused the filter", it) }
        }
        return readInterruptionFilter(context) == filter
    }

    private fun notificationManager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    /* ------------------------------------------------------------- the pokes */

    private fun readSystem(context: Context, key: String): Int? =
        runCatching { Settings.System.getInt(context.contentResolver, key) }.getOrNull()

    private fun writeSystem(context: Context, key: String, value: Int) {
        runCatching { Settings.System.putInt(context.contentResolver, key, value) }
            .onFailure { Log.w(TAG, "could not write $key", it) }
    }

    private fun readGlobal(context: Context, key: String): Float? =
        runCatching { Settings.Global.getFloat(context.contentResolver, key) }.getOrNull()

    private fun writeGlobal(context: Context, key: String, value: Float) {
        runCatching { Settings.Global.putFloat(context.contentResolver, key, value) }
            .onFailure { Log.w(TAG, "could not write $key", it) }
    }
}

/**
 * What the phone will currently let game mode do.
 *
 * Read by the settings screen so that a lever with nothing behind it is shown as
 * needing something, and by [Levers] so it never attempts a write it knows will
 * be refused. Both matter: an unchecked write here throws a SecurityException in
 * the middle of arming and leaves half the levers pulled.
 */
object Grants {

    /** "Modify system settings", granted from a screen only the user can reach. */
    fun canWriteSystem(context: Context): Boolean =
        runCatching { Settings.System.canWrite(context) }.getOrDefault(false)

    /**
     * WRITE_SECURE_SETTINGS, which no phone will grant to an app by tapping.
     *
     * It is held or it is not, and the only way to hold it is one adb command —
     * which is exactly how this app is installed anyway, so it is offered rather
     * than hidden. The settings screen shows the line to run.
     */
    fun canWriteSecure(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** Do Not Disturb access. Optional: only needed if the listener route fails. */
    fun hasDndAccess(context: Context): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
    }.getOrDefault(false)
}
