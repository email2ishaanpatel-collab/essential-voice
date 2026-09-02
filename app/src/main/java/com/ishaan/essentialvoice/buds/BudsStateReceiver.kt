package com.ishaan.essentialvoice.buds

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ishaan.essentialvoice.Prefs

/**
 * Keeps the buds card in step with reality, and costs nothing to do it.
 *
 * It runs for a fraction of a millisecond when the system reports a Bluetooth
 * change and is then gone: no polling, no service, no alarm. ACL_CONNECTED and
 * ACL_DISCONNECTED are on Android's implicit-broadcast exemption list, so a
 * manifest-declared receiver is still delivered them without the app keeping a
 * process alive.
 */
class BudsStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val chosen = Prefs.get(context).now.budsAddress
        if (chosen.isEmpty()) return

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothDevice.ACTION_ACL_DISCONNECTED,
            -> {
                val dev = deviceOf(intent) ?: return
                if (!dev.address.equals(chosen, ignoreCase = true)) return
                val connected = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED
                // The question the spinner was asking has been answered.
                BudsGlyphAnim.stop()
                Buds.setConnectedCached(context, connected)
                // A link that came up is proof the direct route works after all,
                // whatever the last tap concluded.
                if (connected) Buds.setNeedsSettings(context, false)
            }

            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                if (state == BluetoothAdapter.STATE_OFF ||
                    state == BluetoothAdapter.STATE_TURNING_OFF
                ) {
                    // The radio being off means they cannot still be connected.
                    Buds.setConnectedCached(context, false)
                } else if (state != BluetoothAdapter.STATE_ON) {
                    return
                }
            }

            else -> return
        }

        BudsWidget.refresh(context)
        BudsTile.refresh(context)
    }

    /**
     * The device the broadcast is about.
     *
     * The typed `getParcelableExtra(String, Class)` is API 33. `minSdk` is 31,
     * so on Android 12 and 12L calling it throws `NoSuchMethodError` — inside a
     * BroadcastReceiver, which the system turns straight into a crash dialog
     * every time the buds connect. The deprecated overload is the one that
     * exists on every version this app installs on.
     */
    @Suppress("DEPRECATION")
    private fun deviceOf(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
}
