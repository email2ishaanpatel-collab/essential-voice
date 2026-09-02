package com.ishaan.essentialvoice

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.ishaan.essentialvoice.game.Grants
import com.ishaan.essentialvoice.trigger.EssentialKeyService
import com.ishaan.essentialvoice.voice.Dictation

/** Everything the app needs granted before a held key can become text. */
data class SetupState(
    val accessibility: Boolean,
    val overlay: Boolean,
    val microphone: Boolean,
    /**
     * Not required to dictate — it is only for the earbuds widget and tile — but
     * it belongs here rather than being read straight from the composable. A
     * permission read is not a state read: with it absent from this snapshot,
     * granting it produced an identical SetupState, nothing recomposed, and the
     * row went on saying "Allow" until the screen was left and reopened.
     */
    val bluetooth: Boolean,
    val keyLearned: Boolean,
    /**
     * Whether this app is the phone's digital assistant.
     *
     * Not a permission and not required to dictate, but it is what decides
     * whether holding the home bar and holding the power button reach the app at
     * all, so the settings screen has to be able to say which it is. Here rather
     * than read from the composable for the same reason [bluetooth] is: it
     * changes in the phone's settings, and a screen that reads it directly would
     * still be saying "not set" after it had been.
     */
    val assistant: Boolean,
    /**
     * The three grants game mode can use, none of them required to dictate.
     *
     * Here rather than read from the composable for the same reason [bluetooth]
     * is: a permission read is not a state read, so a screen that read them
     * directly would go on saying "Allow" after they had been granted, until it
     * was left and reopened. These are re-read on every resume, which is exactly
     * when somebody comes back from granting one.
     */
    val writeSystemSettings: Boolean,
    val writeSecureSettings: Boolean,
    val dndAccess: Boolean,
) {
    /** Enough to run a dictation, whatever the trigger. */
    val canDictate: Boolean get() = accessibility && overlay && microphone

    val ready: Boolean get() = canDictate && keyLearned
}

object Setup {

    fun read(context: Context): SetupState = SetupState(
        accessibility = isAccessibilityEnabled(context),
        overlay = Settings.canDrawOverlays(context),
        microphone = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED,
        bluetooth = ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED,
        keyLearned = Prefs.get(context).now.hasTrigger,
        assistant = isAssistant(context),
        writeSystemSettings = Grants.canWriteSystem(context),
        writeSecureSettings = Grants.canWriteSecure(context),
        dndAccess = Grants.hasDndAccess(context),
    )

    /**
     * Read from Settings.Secure rather than trusting the service's own static
     * instance: the instance is only set once the system has actually bound us,
     * and the settings screen needs to reflect the switch, not the binding race.
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, EssentialKeyService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    /**
     * Whether this app holds the assistant role.
     *
     * RoleManager rather than reading `Settings.Secure.assistant` and comparing
     * component names: the role is the thing the system actually dispatches on,
     * and `isRoleHeld` answers for the calling app with no permission at all.
     * There is no matching *request* — ROLE_ASSISTANT is not one an app may ask
     * for in a dialog — so the app can only report it and open the picker.
     */
    fun isAssistant(context: Context): Boolean {
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return runCatching { rm.isRoleHeld(RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)
    }

    fun hasNotificationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openOverlaySettings(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * The assistant picker. Android has no direct action for it, so this opens
     * the default-apps screen the picker lives on, falling back to the app's own
     * settings page if the OEM has moved it.
     */
    fun openAssistantSettings(context: Context) {
        val candidates = listOf(
            Intent("android.settings.VOICE_INPUT_SETTINGS"),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                runCatching { context.startActivity(intent) }.onSuccess { return }
            }
        }
    }

    /**
     * Wherever this phone keeps the Essential Key.
     *
     * There is no standard action for it — it is a Nothing OS feature, not an
     * Android one — so this tries the OEM screens that are known to exist and
     * falls back to the top of Settings, where it can be searched for. It
     * deliberately does not hard-code a package or a class: guessing a
     * component that has been renamed lands the user on an error rather than on
     * the settings app.
     */
    /**
     * Open Essential Space itself, which is where its Essential Key toggles are.
     *
     * The phone's own Settings has no screen for them — they live inside the
     * app, two levels down — so the honest thing an app can do is land the user
     * in the right *app* and print the two taps that follow. The package name is
     * looked up rather than hard-coded: it is Nothing's, it is not documented,
     * and a wrong guess here would be a button that silently does nothing.
     */
    /**
     * The phone's voice-input screen, where the recogniser and its downloaded
     * language packs are managed.
     *
     * `VOICE_INPUT_SETTINGS` is the documented action and lands on the assist
     * and voice-input screen; the packs themselves are one tap further in,
     * behind the recogniser's own gear. There is no deeper public action, so
     * this is as close as an app can put somebody — which is why the language
     * picker offers the download directly instead of relying on this.
     */
    fun openVoiceInputSettings(context: Context) {
        val candidates = listOf(
            Intent("android.settings.VOICE_INPUT_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                runCatching { context.startActivity(intent) }.onSuccess { return }
            }
        }
    }

    fun openEssentialSpace(context: Context) {
        val pm = context.packageManager
        val candidates = listOf(
            // Read off this phone with `pm list packages`, not guessed.
            "com.nothing.ntessentialspace",
            "com.nothing.experience",
        )
        for (pkg in candidates) {
            val launch = pm.getLaunchIntentForPackage(pkg) ?: continue
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(launch) }.onSuccess { return }
        }
        // Nothing answered. The key's own settings screen, or the phone's, is
        // still closer than where they were standing.
        openEssentialKeySettings(context)
    }

    fun openEssentialKeySettings(context: Context) {
        val candidates = listOf(
            Intent("android.settings.ESSENTIAL_KEY_SETTINGS"),
            Intent("com.nothing.settings.ESSENTIAL_KEY_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                runCatching { context.startActivity(intent) }.onSuccess { return }
            }
        }
    }

    /**
     * "Modify system settings" — the special access behind the four levers that
     * change something belonging to the phone rather than to the app.
     *
     * A per-app screen, so it opens straight at this app rather than at a list
     * to find it in.
     */
    fun openWriteSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { openAppSettings(context) }
    }

    /**
     * Do Not Disturb access. Optional: the notification listener can usually set
     * the interruption filter on its own, and this is the fallback for a build
     * where it cannot. There is no per-app form of this screen.
     */
    fun openDndAccess(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { openAppSettings(context) }
    }

    fun openAppSettings(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
