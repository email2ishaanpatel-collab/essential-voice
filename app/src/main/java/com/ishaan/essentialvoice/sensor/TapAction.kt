package com.ishaan.essentialvoice.sensor

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ishaan.essentialvoice.media.NowPlaying
import com.ishaan.essentialvoice.trigger.EssentialKeyService
import com.ishaan.essentialvoice.voice.Bar
import com.ishaan.essentialvoice.voice.Dictation

/**
 * What a knock on the back can be set to do.
 *
 * Modelled on the two implementations that already exist. Pixel's Quick Tap
 * offers a screenshot, play/pause, the notification shade, recent apps, the
 * flashlight, Gemini, and opening any app; iPhone's Back Tap offers sixteen
 * system actions plus a Shortcut. Nearly all of it is reachable here with no new
 * permissions at all, because an accessibility service can already fire the
 * global actions and this app already holds a media session and a notification
 * listener. The list is deliberately short — six things somebody will actually
 * assign, rather than sixteen to scroll past.
 *
 * There is no Siri entry, and there cannot be: on this phone Essential Voice
 * *is* the assistant, so the equivalent of that row is [DICTATE].
 */
enum class TapAction(
    val id: String,
    val label: String,
    /** What the settings screen says under the name. */
    val detail: String,
) {
    NOTHING("none", "Nothing", "The gesture is off."),
    DICTATE("dictate", "Dictate", "Start a dictation, with the bar to stop it."),
    SCREENSHOT("screenshot", "Screenshot", "The same one the buttons take."),
    PLAY_PAUSE("play_pause", "Play or pause", "Whatever is playing, wherever it is playing."),
    FLASHLIGHT("torch", "Flashlight", "On, and off again."),
    NOTIFICATIONS("shade", "Notifications", "Pulls the shade down."),
    OPEN_APP("app", "Open an app", "Any app on the phone."),
    ;

    companion object {
        fun byId(id: String): TapAction = entries.firstOrNull { it.id == id } ?: NOTHING

        /** What the pickers offer, in the order they are offered. */
        val choices: List<TapAction> = listOf(
            DICTATE, SCREENSHOT, PLAY_PAUSE, FLASHLIGHT, NOTIFICATIONS, OPEN_APP, NOTHING,
        )
    }
}

/**
 * Running one.
 *
 * Everything here goes through the accessibility service, which is the only
 * thing in this app with the standing to fire a global action, and which is
 * guaranteed to be alive because it is what owns the detector in the first
 * place.
 */
object TapActions {

    private const val TAG = "EVTapAction"

    private val main = Handler(Looper.getMainLooper())

    fun run(action: TapAction, packageName: String) {
        val svc = EssentialKeyService.instance ?: return
        when (action) {
            TapAction.NOTHING -> Unit

            TapAction.DICTATE -> {
                if (!Dictation.isReady) return
                // The bar rather than the pill: there is no key being held, so
                // something on screen has to offer the way out.
                if (!Dictation.isBusy) Bar.claim()
                Dictation.toggle()
            }

            // API 30, and it is the *system's* screenshot — saved, with the
            // preview and the share sheet — rather than a bitmap this app would
            // then have to find somewhere to put. takeScreenshot() hands over
            // pixels; this hands over the whole feature.
            TapAction.SCREENSHOT ->
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)

            // Through the media session this app already holds, so it reaches
            // whatever is actually playing rather than guessing at a package.
            TapAction.PLAY_PAUSE -> NowPlaying.toggle()

            TapAction.FLASHLIGHT -> Torch.toggle(svc)

            TapAction.NOTIFICATIONS ->
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)

            TapAction.OPEN_APP -> openApp(svc, packageName)
        }
    }

    private fun openApp(context: Context, packageName: String) {
        if (packageName.isEmpty()) return
        val intent = runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName)
        }.getOrNull() ?: run {
            Log.w(TAG, "$packageName has no launcher activity")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        main.post {
            runCatching { context.startActivity(intent) }
                .onFailure { Log.w(TAG, "could not open $packageName", it) }
        }
    }
}

/**
 * The flashlight, and knowing whether it is already on.
 *
 * `setTorchMode` has needed no permission since API 26, so this costs the
 * manifest nothing. Keeping a local boolean and flipping it would drift the
 * moment the Quick Settings tile or the camera app touched the torch, so the
 * real state is read from a `TorchCallback`.
 *
 * Registered once, lazily, and never unregistered. It is a single callback in a
 * process that lives exactly as long as the accessibility service does, and the
 * alternative — another attach/detach pair threaded through the service — is
 * more moving parts than the thing it would be managing.
 */
private object Torch {

    private const val TAG = "EVTorch"

    private var manager: CameraManager? = null
    private var cameraId: String? = null

    @Volatile
    private var on = false

    private val callback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            if (id == cameraId) on = enabled
        }

        override fun onTorchModeUnavailable(id: String) {
            if (id == cameraId) on = false
        }
    }

    fun toggle(context: Context) {
        val cm = ensure(context) ?: return
        val id = cameraId ?: return
        runCatching { cm.setTorchMode(id, !on) }
            .onFailure { Log.w(TAG, "the torch refused", it) }
    }

    private fun ensure(context: Context): CameraManager? {
        manager?.let { return it }
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        // The first camera that has a flash, which on every phone this will run
        // on is the back one. Asked rather than assumed, because "0" is not
        // guaranteed to be the rear camera and a wrong id throws.
        cameraId = runCatching {
            cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
        if (cameraId == null) {
            Log.w(TAG, "no camera on this phone has a flash")
            return null
        }
        runCatching { cm.registerTorchCallback(callback, Handler(Looper.getMainLooper())) }
            .onFailure { Log.w(TAG, "could not follow the torch", it) }
        manager = cm
        return cm
    }
}
