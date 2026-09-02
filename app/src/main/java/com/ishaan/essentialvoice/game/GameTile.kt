package com.ishaan.essentialvoice.game

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.ishaan.essentialvoice.MainActivity
import com.ishaan.essentialvoice.Prefs
import com.ishaan.essentialvoice.R
import com.ishaan.essentialvoice.Setup

/**
 * Game mode, one swipe from inside the game.
 *
 * Which is the only place it is ever wanted: nobody opens a settings app to turn
 * on the thing whose purpose is not leaving the game. The tile and the switch in
 * the app write the same preference and read the same one back, so they cannot
 * drift — see [GameMode].
 *
 * The tile is greyed and sends you to the app when the accessibility service is
 * off, because that is the one state where a tap would write a setting nothing
 * is listening to. Every other state has somewhere sensible to go.
 */
class GameTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        paint()
    }

    override fun onClick() {
        super.onClick()

        if (!Setup.isAccessibilityEnabled(this)) {
            unlockAndRun { openApp() }
            return
        }
        GameMode.toggle(this)
        paint()
    }

    private fun paint() {
        val tile = qsTile ?: return
        val armed = Prefs.get(this).now.gameArmed
        val ready = Setup.isAccessibilityEnabled(this)

        tile.label = getString(R.string.game_tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_game)
        tile.state = when {
            !ready -> Tile.STATE_UNAVAILABLE
            armed -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = getString(
            when {
                !ready -> R.string.game_tile_needs_setup
                armed -> R.string.game_tile_on
                else -> R.string.game_tile_off
            },
        )
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Android 14 made the Intent form throw rather than deprecating it
        // quietly, so both are needed while minSdk is below 34. Same as BudsTile.
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
                requestListeningState(context, ComponentName(context, GameTile::class.java))
            }
        }
    }
}
