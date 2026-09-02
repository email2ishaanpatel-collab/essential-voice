package com.ishaan.essentialvoice.trigger

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.util.Log
import com.ishaan.essentialvoice.voice.Bar
import com.ishaan.essentialvoice.voice.Dictation

/**
 * Lets the phone's *assistant* gestures start a dictation — which is the only
 * way to reach the power button, and the only way to reach the home bar.
 *
 * The power key never arrives at an accessibility service: the window manager
 * policy consumes it in `interceptKeyBeforeQueueing`, before anything else is
 * offered it. That is deliberate on Android's part, and it is why a long press
 * can always force a restart no matter what an app has done. It cannot be
 * intercepted, and an app that could would be a serious problem.
 *
 * What the system does offer is the assistant role. "Press and hold power button
 * → Digital assistant" launches whichever app holds that role, so registering as
 * an assistant turns the power button into a trigger by the front door.
 *
 * **The gesture-navigation home bar arrives here too**, and it is worth being
 * clear about why, because it looks like it ought to need something special.
 * Circle to Search is not a navigation-bar API: holding the handle is the
 * ordinary assist invocation, and Google's version of it is gated behind a flag
 * (`search_all_entrypoints_enabled`) on top of the same route. There is no way
 * to take the handle directly — SystemUI watches it through a spy window that
 * sees touches whatever is layered above it, so an overlay there would both
 * break the swipe and double-fire — and there is no need to, because the
 * assistant role *is* the extension point. Whichever gestures this phone routes
 * to the assistant, holding the handle or swiping in from a bottom corner, land
 * in [VoiceAssistSession.onShow] below.
 *
 * The system hands over a single launch, not a key down and up, so this cannot
 * be push-to-talk: one invocation starts, and something on screen has to offer
 * the way out. That is [Bar].
 */
class VoiceAssistService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Log.i("EVAssist", "assistant role active")
    }
}

class VoiceAssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        VoiceAssistSession(this)
}

/**
 * A session with no interface of its own. The pill is the entire UI, so this
 * toggles the dictation and immediately gets out of the way — leaving a blank
 * assistant panel on screen would cover the app the user is dictating into.
 */
private class VoiceAssistSession(service: VoiceAssistSessionService) :
    VoiceInteractionSession(service) {

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        // Never draw the system's assistant scrim over the app underneath.
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (Dictation.isReady) {
            // The bar, not the pill: there is no key being held, so the only
            // thing that can end this is something on screen saying so. isBusy
            // rather than isListening, because between a stop and the transcript
            // the dictation is still running and this invocation is the *end* of
            // one, not the start of another to claim.
            if (!Dictation.isBusy) Bar.claim()
            Dictation.toggle()
        } else {
            Log.w("EVAssist", "accessibility service is off; nothing to toggle")
        }
        hide()
    }
}

/**
 * A stub, because `<voice-interaction-service>` requires a recognition service to
 * be named and the system will not accept the app as an assistant without one.
 * Nothing calls it — recognition happens in whisper, not through this API.
 */
class VoiceAssistRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback?) {
        runCatching { listener?.error(android.speech.SpeechRecognizer.ERROR_CLIENT) }
    }
    override fun onCancel(listener: Callback?) = Unit
    override fun onStopListening(listener: Callback?) = Unit
}
