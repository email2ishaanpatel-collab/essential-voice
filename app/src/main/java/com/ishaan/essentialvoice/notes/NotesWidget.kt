package com.ishaan.essentialvoice.notes

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.ishaan.essentialvoice.Prefs
import com.ishaan.essentialvoice.R
import com.ishaan.essentialvoice.Settings

/**
 * The notes list, on the home screen.
 *
 * A list rather than a picture of the newest note: notes are a list, and a
 * widget showing one of them would be a shortcut with extra steps.
 *
 * The rows are **pushed** to the launcher as [RemoteViews.RemoteCollectionItems]
 * rather than pulled from a RemoteViewsService. The service route is the older
 * one and costs a service any launcher can bind to in order to read the notes;
 * this one hands over a finished list, so nothing outside this app ever asks
 * for it. The price is that every row travels over a binder transaction, which
 * is why [MAX_ROWS] exists — a widget is a glance, and the app is where the
 * whole list lives.
 *
 * It is never polled. `updatePeriodMillis` is 0 and [refresh] is called by
 * [NoteStore] the moment anything is written, so the list on the home screen
 * changes at the same time as the list in the app, and the phone is not woken
 * on a timer to redraw something that has not moved.
 */
class NotesWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { bind(context, appWidgetManager, it) }
    }

    companion object {

        /** Which note a tap was on. Absent means "a new one". */
        const val EXTRA_NOTE_ID = "com.ishaan.essentialvoice.note_id"

        /**
         * How many notes the widget carries.
         *
         * Not a display limit — the launcher scrolls — but a transaction one:
         * the whole widget update crosses a binder with a hard size cap, and a
         * few hundred long notes would reach it and the update would simply
         * fail. Fifty is far more than fits on a home screen and nowhere near
         * the cap.
         */
        private const val MAX_ROWS = 50

        /**
         * Redraw every placed widget. Safe to call when none is placed.
         *
         * Called from [NoteStore] on every write, which is the only time the
         * list can have changed.
         */
        fun refresh(context: Context) {
            val manager = runCatching { AppWidgetManager.getInstance(context) }.getOrNull() ?: return
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(context, NotesWidget::class.java))
            }.getOrNull() ?: return
            ids.forEach { bind(context, manager, it) }
        }

        private fun bind(context: Context, manager: AppWidgetManager, id: Int) {
            NoteStore.load(context)
            val settings = Prefs.get(context).now
            val kinds = kinds(settings)
            val notes = NoteStore.notes.value
                .filter { it.kind in kinds }
                .take(MAX_ROWS)
            val views = RemoteViews(context.packageName, R.layout.widget_notes)

            // The heading names what is actually in the list. It said NOTES
            // whatever the switches were, which on a widget showing only
            // recordings is a label describing a different widget.
            views.setTextViewText(R.id.widget_title, heading(kinds))

            // An AdapterView's own empty-view mechanism needs the older adapter;
            // with items pushed in, the two views are simply shown and hidden.
            // Deterministic, and one less thing the launcher decides.
            views.setViewVisibility(
                R.id.widget_list, if (notes.isEmpty()) View.GONE else View.VISIBLE,
            )
            views.setViewVisibility(
                R.id.widget_empty, if (notes.isEmpty()) View.VISIBLE else View.GONE,
            )
            // Three different empties, and the difference matters: nothing said
            // yet, nothing *of this kind* said yet, and a widget that has been
            // switched off. Only the first is fixed by talking into the phone,
            // and the stock line told all three to do that — "No notes yet" on
            // a widget set to recordings, with a library full of tasks.
            views.setTextViewText(
                R.id.widget_empty,
                when {
                    kinds.isEmpty() -> context.getString(R.string.widget_empty_off)
                    kinds.size == 3 -> context.getString(R.string.widget_empty)
                    else -> "No " +
                        heading(kinds).lowercase().replace("  ·  ", " or ") + " yet."
                },
            )

            val items = RemoteViews.RemoteCollectionItems.Builder().apply {
                setHasStableIds(true)
                setViewTypeCount(1)
                notes.forEach { note -> addItem(note.id, row(context, note)) }
            }.build()
            views.setRemoteAdapter(R.id.widget_list, items)

            // One template for the whole list; each row fills in its own note
            // id. Mutable, because a fill-in intent is a mutation — an
            // immutable template would open the same note every time.
            views.setPendingIntentTemplate(
                R.id.widget_list,
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, NoteEditActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                ),
            )

            views.setOnClickPendingIntent(
                R.id.widget_add,
                PendingIntent.getActivity(
                    context,
                    1,
                    Intent(context, NoteEditActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )

            // The heading opens the app itself, which is where the key is set
            // up and where the whole list is.
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                views.setOnClickPendingIntent(
                    R.id.widget_title,
                    PendingIntent.getActivity(
                        context, 2, launch,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }

            runCatching { manager.updateAppWidget(id, views) }
        }

        /**
         * One row, whatever kind it is.
         *
         * Three kinds share one layout on purpose: the widget is a glance, and
         * a home screen is not the place to grow a second column of controls
         * you cannot see the state of. What a row says is enough to tell them
         * apart — a done task is struck through with a tick, a recording says
         * how long it is — and tapping any of them opens the same screen, which
         * is where the tick and the play button actually live.
         */
        /** The kinds the three switches leave on. */
        private fun kinds(settings: Settings): Set<NoteStore.Kind> = buildSet {
            if (settings.widgetNotes) add(NoteStore.Kind.NOTE)
            if (settings.widgetTasks) add(NoteStore.Kind.TASK)
            if (settings.widgetRecordings) add(NoteStore.Kind.RECORDING)
        }

        /**
         * What the widget calls itself, given what it is carrying.
         *
         * All three on is "LIBRARY" rather than the three words joined up: a
         * heading listing everything it holds is a heading that has stopped
         * being a name. One or two on, and naming them is the whole point.
         */
        private fun heading(kinds: Set<NoteStore.Kind>): String = when {
            kinds.size == 3 -> "LIBRARY"
            kinds.isEmpty() -> "LIBRARY"
            else -> kinds.joinToString("  ·  ") {
                when (it) {
                    NoteStore.Kind.NOTE -> "NOTES"
                    NoteStore.Kind.TASK -> "TASKS"
                    NoteStore.Kind.RECORDING -> "RECORDINGS"
                }
            }
        }

        private fun row(context: Context, note: NoteStore.Note) =
            RemoteViews(context.packageName, R.layout.widget_note_item).apply {
                val title = note.title.ifBlank {
                    when (note.kind) {
                        NoteStore.Kind.TASK -> "Empty task"
                        NoteStore.Kind.RECORDING ->
                            if (note.transcribed) "Nothing was said"
                            else "Reading this one\u2026"
                        NoteStore.Kind.NOTE -> "Empty note"
                    }
                }
                setTextViewText(
                    R.id.widget_item_title,
                    if (note.kind == NoteStore.Kind.TASK && note.done) "\u2713  $title" else title,
                )
                val stamp = NoteStore.whenLabel(note.createdAt)
                setTextViewText(
                    R.id.widget_item_when,
                    when (note.kind) {
                        NoteStore.Kind.TASK -> "Task  \u00b7  $stamp"
                        NoteStore.Kind.RECORDING ->
                            NoteStore.clock(note.durationMs) + "  \u00b7  " + stamp
                        NoteStore.Kind.NOTE -> stamp
                    },
                )
                // The template lives on the list; this is the row's half of it.
                setOnClickFillInIntent(
                    R.id.widget_item,
                    Intent().putExtra(EXTRA_NOTE_ID, note.id),
                )
            }
    }
}
