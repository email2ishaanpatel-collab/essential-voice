package com.ishaan.essentialvoice.buds

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.ishaan.essentialvoice.Prefs
import com.ishaan.essentialvoice.R

/**
 * The buds, in the Quick Settings panel.
 *
 * The same tap as the widget, one swipe from anywhere — including over another
 * app, which is where it is actually wanted. [BudsAction] holds the behaviour so
 * that the two surfaces cannot drift apart.
 *
 * A tile may start an activity where the widget's receiver may not: the system
 * gives it [startActivityAndCollapse], which is the sanctioned route out of the
 * panel. That is why the refused-connect case can go straight to Bluetooth
 * settings here, while on the home screen it has to wait for the next tap.
 */
class BudsTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        // Paint from the cache so the tile is never blank, then ask the stack and
        // paint again. Without the second pass the tile can offer "Tap to
        // connect" over an already-connected pair, and the tap then disconnects
        // them — which reads as the tile being broken.
        paint()
        Buds.queryConnected(this) { paint() }
    }

    override fun onClick() {
        super.onClick()

        val chosen = Prefs.get(this).now.budsAddress.isNotEmpty()
        if (!chosen || !Buds.hasPermission(this)) {
            // Nothing to connect, or no permission to connect it: the app is
            // where both are fixed. unlockAndRun because a tile can be tapped
            // on the lock screen, and an activity started from there would sit
            // behind the keyguard doing nothing visible.
            unlockAndRun { openApp() }
            return
        }
        if (!Buds.isBluetoothOn(this)) {
            unlockAndRun { openBluetoothSettings() }
            return
        }

        // Say something the instant it is tapped. Everything after this waits
        // first on a bind to the Bluetooth service and then on the link itself,
        // and together those are long enough that a tile which says nothing at
        // all reads as one that missed the tap. The *state* is deliberately not
        // touched — there are still two, connected and not — this is only the
        // subtitle acknowledging that the request went in.
        qsTile?.let { tile ->
            tile.subtitle = getString(
                if (Buds.isConnectedCached(this)) R.string.buds_disconnecting
                else R.string.buds_connecting,
            )
            tile.updateTile()
        }

        BudsAction.toggle(this) { outcome ->
            if (outcome == BudsAction.Outcome.BLOCKED) {
                unlockAndRun { openBluetoothSettings() }
            }
            paint()
            // The profile call returns long before the link actually changes —
            // `connect() -> true` only means the stack accepted the request. The
            // ACL broadcast is what says it happened, and if that is missed the
            // tile would sit there asserting the state it had *before* the tap,
            // which is what made it look stuck. So ask the stack again a few
            // times over the next couple of seconds and paint the answer.
            recheck(400)
            recheck(1_200)
            recheck(2_600)
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Ask the stack again in a moment, and paint whatever it says. */
    private fun recheck(delayMs: Long) {
        handler.postDelayed({ Buds.queryConnected(this) { paint() } }, delayMs)
    }

    override fun onStopListening() {
        handler.removeCallbacksAndMessages(null)
        super.onStopListening()
    }

    private fun paint() {
        val tile = qsTile ?: return
        val settings = Prefs.get(this).now
        val connected = Buds.isConnectedCached(this)

        tile.label = Buds.liveLabel(this).ifEmpty { getString(R.string.buds_tile_label) }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_logo)
        // Two states, and only two: connected or not. The tile used to grey
        // itself out for "no pair chosen", "no permission" and "radio off", and
        // a greyed-out tile reads as broken rather than as informative — the tap
        // has somewhere sensible to go in every one of those cases anyway, and
        // the subtitle already says which it is.
        tile.state = if (connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = getString(
            when {
                settings.budsAddress.isEmpty() -> R.string.buds_none_chosen
                connected -> R.string.buds_connected
                !Buds.isBluetoothOn(this) -> R.string.buds_bluetooth_off
                else -> R.string.buds_tap_to_connect
            },
        )
        tile.updateTile()
    }

    private fun openApp() = launch(
        Intent(this, com.ishaan.essentialvoice.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    private fun openBluetoothSettings() = launch(
        Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    /**
     * Android 14 replaced `startActivityAndCollapse(Intent)` with the
     * PendingIntent form and made the old one throw rather than deprecate
     * quietly, so both are needed while minSdk is below 34.
     */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launch(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** Ask the system to call [onStartListening] again, if anyone is looking. */
        fun refresh(context: Context) {
            runCatching {
                requestListeningState(
                    context,
                    ComponentName(context, BudsTile::class.java),
                )
            }
        }
    }
}
