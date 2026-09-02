package com.ishaan.essentialvoice.buds

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.RemoteViews
import com.ishaan.essentialvoice.MainActivity
import com.ishaan.essentialvoice.Prefs
import com.ishaan.essentialvoice.R

/**
 * The buds, on the home screen.
 *
 * An `AppWidgetProvider` is a BroadcastReceiver, so this is alive only for the
 * few milliseconds it takes to repaint or to handle a tap — there is no service
 * behind it and nothing polls. The tap is a **broadcast back into this class**
 * rather than an activity launch: an activity would build and tear down a task
 * for something with no UI, and the launcher animates that as a flash of window
 * over the home screen.
 *
 * An activity PendingIntent is used only when the tap genuinely needs one, and
 * that distinction is load-bearing rather than cosmetic. A receiver may not
 * start an activity from the background — Android answers "Background activity
 * launch blocked!" — so the trip to Bluetooth settings cannot be made at the
 * moment the connect is refused. It is made by the *next* tap, which the
 * launcher sends, and which is therefore allowed. See [tapIntent].
 */
class BudsWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val rv = build(context)
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, rv) }

        // Then correct it against the stack, for the same reason the tile does —
        // see Buds.queryConnected. goAsync because the proxy arrives on a
        // callback and a receiver is otherwise finished before it lands.
        val pending = goAsync()
        var done = false
        val finish = {
            if (!done) {
                done = true
                pending.finish()
            }
        }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val giveUp = Runnable { finish() }
        handler.postDelayed(giveUp, PROXY_TIMEOUT_MS)
        Buds.queryConnected(context) {
            refresh(context)
            handler.removeCallbacks(giveUp)
            finish()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TOGGLE) {
            super.onReceive(context, intent)
            return
        }

        val pending = goAsync()
        var finished = false
        val release = {
            if (!finished) {
                finished = true
                pending.finish()
            }
        }
        // The proxies can simply never arrive. Give up rather than hold the
        // broadcast — and so the process — open indefinitely.
        val timeout = android.os.Handler(android.os.Looper.getMainLooper())
        val giveUp = Runnable { release() }
        timeout.postDelayed(giveUp, PROXY_TIMEOUT_MS)

        BudsAction.toggle(context) {
            refresh(context)
            timeout.removeCallbacks(giveUp)
            // The connect has been accepted but the link is not up yet, and the
            // frames of the blinking dot are pushed from this process. Hold the
            // broadcast open until it stops — BudsGlyphAnim caps itself, so this
            // cannot run away.
            val spin = BudsGlyphAnim.current
            if (spin != null && spin.isActive) {
                spin.invokeOnCompletion { release() }
            } else {
                // Not released immediately. BudsAction reports as soon as A2DP
                // has taken the request and asks HFP afterwards without waiting,
                // which is what makes the tap feel instant — but this is a
                // BroadcastReceiver, and finishing here lets the process be
                // killed before that second profile call lands. On a disconnect
                // that would leave the headset profile up after the music had
                // stopped. A short grace is enough for a bind that is already in
                // flight.
                timeout.postDelayed(release, HEADSET_GRACE_MS)
            }
        }
    }

    companion object {

        const val ACTION_TOGGLE = "com.ishaan.essentialvoice.buds.TOGGLE"

        /** Distinct request codes, or the two PendingIntents overwrite each other. */
        private const val RC_BROADCAST = 11
        private const val RC_ACTIVITY = 12

        private const val PROXY_TIMEOUT_MS = 4_000L

        /** Long enough for an already-issued HFP bind to come back. */
        private const val HEADSET_GRACE_MS = 900L

        /** The dot's box in the layout, in dp. */
        private const val GLYPH_DP = 44f

        private fun glyphPx(context: Context): Int =
            (GLYPH_DP * context.resources.displayMetrics.density).toInt()

        /**
         * One frame of the blinking dot, and nothing else.
         *
         * `partiallyUpdateAppWidget` rather than a full update: the rest of the
         * card has not changed, and a whole RemoteViews tree per frame would be
         * a much larger binder transaction fourteen times a second.
         */
        fun pushGlyph(context: Context, alpha: Float) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(context, BudsWidget::class.java))
            if (ids == null || ids.isEmpty()) return
            val rv = RemoteViews(context.packageName, R.layout.widget_buds)
            rv.setImageViewBitmap(
                R.id.buds_glyph,
                BudsGlyph.render(
                    glyphPx(context),
                    context.getColor(R.color.widget_ink),
                    alpha,
                ),
            )
            runCatching { mgr.partiallyUpdateAppWidget(ids, rv) }
        }

        /** Redraw every placed widget. Safe to call when none is placed. */
        fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(context, BudsWidget::class.java))
            if (ids == null || ids.isEmpty()) return
            val rv = build(context)
            ids.forEach { mgr.updateAppWidget(it, rv) }
        }

        private fun build(context: Context): RemoteViews {
            val connected = Buds.isConnectedCached(context)
            val settings = Prefs.get(context).now
            val rv = RemoteViews(context.packageName, R.layout.widget_buds)

            val ink = context.getColor(
                if (connected) R.color.widget_on_connected else R.color.widget_ink,
            )
            val dim = context.getColor(
                if (connected) R.color.widget_on_connected_dim else R.color.widget_ink_muted,
            )

            rv.setInt(
                R.id.buds_root,
                "setBackgroundResource",
                if (connected) R.drawable.widget_buds_on else R.drawable.widget_surface,
            )
            // The dot, solid. It only moves while a connect is in flight, and
            // those frames come from BudsGlyphAnim rather than from here.
            rv.setImageViewBitmap(
                R.id.buds_glyph,
                BudsGlyph.render(glyphPx(context), ink),
            )

            rv.setTextViewText(
                R.id.buds_title,
                Buds.liveLabel(context).ifEmpty { context.getString(R.string.buds_widget_label) },
            )
            rv.setTextColor(R.id.buds_title, ink)
            rv.setTextViewText(R.id.buds_status, statusText(context, connected))
            rv.setTextColor(R.id.buds_status, dim)

            rv.setOnClickPendingIntent(R.id.buds_root, tapIntent(context))
            return rv
        }

        private fun statusText(context: Context, connected: Boolean): String = context.getString(
            when {
                Prefs.get(context).now.budsAddress.isEmpty() -> R.string.buds_none_chosen
                connected -> R.string.buds_connected
                !Buds.hasPermission(context) -> R.string.buds_none_chosen
                !Buds.isBluetoothOn(context) -> R.string.buds_bluetooth_off
                Buds.needsSettings(context) -> R.string.buds_open_settings
                else -> R.string.buds_tap_to_connect
            },
        )

        private fun tapIntent(context: Context): PendingIntent {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val chosen = Prefs.get(context).now.budsAddress.isNotEmpty()

            // Nothing to connect, or nothing to connect it with: the app is
            // where both are fixed.
            if (!chosen || !Buds.hasPermission(context)) {
                val i = Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return PendingIntent.getActivity(context, RC_ACTIVITY, i, flags)
            }

            // The radio is off, or the last tap's direct connect was refused.
            // Sent by the launcher, so this is allowed to start an activity
            // where the receiver would not be.
            if (!Buds.isBluetoothOn(context) || Buds.needsSettings(context)) {
                val i = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return PendingIntent.getActivity(context, RC_ACTIVITY, i, flags)
            }

            val i = Intent(context, BudsWidget::class.java).setAction(ACTION_TOGGLE)
            return PendingIntent.getBroadcast(context, RC_BROADCAST, i, flags)
        }
    }
}
