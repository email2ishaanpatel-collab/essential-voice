package com.ishaan.essentialvoice.buds

import android.bluetooth.BluetoothProfile
import android.content.Context

/**
 * The tap, shared by the home screen widget and the Quick Settings tile.
 *
 * Both surfaces do exactly the same thing and neither should own it. The result
 * comes back through [done] rather than being returned, because the profile
 * proxies arrive asynchronously — the widget holds its broadcast open across
 * that wait, and the tile holds its own.
 */
object BudsAction {

    enum class Outcome {
        /** The platform took it. The real change arrives later as an ACL broadcast. */
        CONNECTING,
        DISCONNECTING,

        /** No buds chosen yet. */
        NONE_CHOSEN,

        /** No permission, or the radio is off. */
        NEEDS_SETUP,

        /** The direct route was refused; offer Bluetooth settings instead. */
        BLOCKED,
    }

    fun toggle(context: Context, done: (Outcome) -> Unit) {
        val app = context.applicationContext

        if (!Buds.hasPermission(app) || !Buds.isBluetoothOn(app)) {
            done(Outcome.NEEDS_SETUP)
            return
        }
        val device = Buds.device(app)
        if (device == null) {
            done(Outcome.NONE_CHOSEN)
            return
        }

        // A2DP carries the music, HFP carries the microphone. Ask both; either
        // one taking is a success, because the stack brings the other up itself.
        Buds.withProfile(app, BluetoothProfile.A2DP) { a2dp ->
            // Ask the stack what is actually connected rather than trusting the
            // cache. An ACL broadcast can be missed — the phone rebooting, the
            // buds walking out of range while the package is stopped — and a
            // drifted cache makes the first tap do the exact opposite of what
            // the widget says, which reads as a dead tap. The proxy is already
            // in hand here, so the check is free.
            val connected = if (a2dp != null) {
                Buds.proxySaysConnected(a2dp, app, device.address)
            } else {
                Buds.isConnectedCached(app)
            }
            if (connected != Buds.isConnectedCached(app)) {
                Buds.setConnectedCached(app, connected)
                BudsWidget.refresh(app)
            }

            val connect = !connected
            val a2dpOk = Buds.invoke(a2dp, device, connect)

            // A2DP is the one that carries the request on every device this has
            // been measured on, and asking for it is already done by the line
            // above. Getting the HEADSET proxy costs a second bind to the
            // Bluetooth service, and waiting for it before saying anything is
            // what made a tap feel like it had been ignored: nothing at all
            // happened on screen until two service binds had completed, and only
            // then did the link itself start taking its second or two.
            //
            // So when A2DP takes the request the answer is already known and is
            // given now; HFP is still asked, because on some ROM it may be the
            // one that works, but nothing waits on it. Only when A2DP refuses
            // does the outcome genuinely depend on the headset's answer, and
            // only then is it waited for.
            val settle = { ok: Boolean ->
                Buds.setNeedsSettings(app, !ok)
                if (ok && connect) BudsGlyphAnim.start(app)
                BudsWidget.refresh(app)
                BudsTile.refresh(app)
            }

            if (a2dpOk) {
                settle(true)
                done(if (connect) Outcome.CONNECTING else Outcome.DISCONNECTING)
                Buds.withProfile(app, BluetoothProfile.HEADSET) { headset ->
                    Buds.invoke(headset, device, connect)
                }
            } else {
                Buds.withProfile(app, BluetoothProfile.HEADSET) { headset ->
                    val ok = Buds.invoke(headset, device, connect)
                    settle(ok)
                    done(
                        when {
                            !ok -> Outcome.BLOCKED
                            connect -> Outcome.CONNECTING
                            else -> Outcome.DISCONNECTING
                        },
                    )
                }
            }
        }
    }
}
