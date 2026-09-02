package com.ishaan.essentialvoice.notes

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.ishaan.essentialvoice.Prefs
import com.ishaan.essentialvoice.ui.EV
import com.ishaan.essentialvoice.ui.EssentialVoiceTheme
import com.ishaan.essentialvoice.ui.EvButton
import com.ishaan.essentialvoice.ui.EvButtonKind
import com.ishaan.essentialvoice.ui.EvText
import com.ishaan.essentialvoice.ui.LocalEvType
import com.ishaan.essentialvoice.ui.Panel
import androidx.compose.foundation.layout.PaddingValues
import com.ishaan.essentialvoice.ui.SectionLabel

/**
 * One note, open for editing.
 *
 * This exists because of the widget: a note on the home screen that cannot be
 * corrected is a picture of a note. It is a whole screen rather than a dialog
 * because a note is a page of text and the keyboard takes half the phone.
 *
 * Saving is not a button you can forget. The text is written back whenever the
 * screen goes away — pressing Done, pressing back, going home — because
 * "leaving without saving" is not a thing a notes app should be able to do to
 * you. Done is there anyway, since a screen with no way out but the back
 * gesture looks unfinished.
 */
class NoteEditActivity : ComponentActivity() {

    /** The note being edited, or -1 for one that does not exist yet. */
    private var noteId by mutableStateOf(-1L)

    /** What is in the field right now, kept out here so [onPause] can save it
     *  without composition having to hand it over. */
    private var draft = ""

    /** Set by Delete. The screen is on its way out and the note it was showing
     *  no longer exists, so the save that [onPause] is about to do has to be
     *  skipped — otherwise leaving would write the deleted note straight back. */
    private var deleted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        val prefs = Prefs.get(this)
        NoteStore.load(this)
        noteId = readId(intent)
        draft = NoteStore.notes.value.firstOrNull { it.id == noteId }?.text.orEmpty()

        setContent {
            val settings by prefs.state.collectAsState()
            val dark = when (settings.theme) {
                Prefs.THEME_LIGHT -> false
                Prefs.THEME_DARK -> true
                else -> systemDark
            }
            LaunchedEffect(dark) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }

            EssentialVoiceTheme(dark = dark) {
                val notes by NoteStore.notes.collectAsState()
                val note = notes.firstOrNull { it.id == noteId }

                NoteEditor(
                    note = note,
                    // A note that does not exist yet has no time to show, and
                    // its own id keys the field so tapping a second note from
                    // the widget replaces the text rather than keeping the first.
                    key = noteId,
                    onChange = { draft = it },
                    onDelete = {
                        deleted = true
                        if (noteId > 0) NoteStore.delete(this@NoteEditActivity, noteId)
                        finish()
                    },
                    onDone = {
                        save()
                        finish()
                    },
                )
            }
        }
    }

    /** Tapping a second note while this screen is open. Same activity, new
     *  note: save the one on screen before it is replaced. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        save()
        setIntent(intent)
        deleted = false
        noteId = readId(intent)
        draft = NoteStore.notes.value.firstOrNull { it.id == noteId }?.text.orEmpty()
    }

    override fun onPause() {
        save()
        // Audio does not follow you out of the screen you started it from.
        // Leaving it running would be this app playing a recording over
        // whatever you opened next, with nothing on screen to stop it.
        Playback.stop()
        super.onPause()
    }

    private fun readId(intent: Intent?): Long =
        intent?.getLongExtra(NotesWidget.EXTRA_NOTE_ID, -1L) ?: -1L

    /**
     * Write the draft back.
     *
     * A blank new note is not created at all, which is what makes the widget's
     * + button free to press: opening it and changing your mind leaves nothing
     * behind. A blank *existing* note is deleted, by [NoteStore.update].
     */
    private fun save() {
        if (deleted) return
        val text = draft.trim()
        if (noteId > 0) {
            NoteStore.update(this, noteId, text)
        } else if (text.isNotEmpty()) {
            noteId = NoteStore.start(this, text).id
        }
    }
}

@Composable
private fun NoteEditor(
    note: NoteStore.Note?,
    key: Long,
    onChange: (String) -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    val initial = note?.text.orEmpty()
    val stamp = note?.let { NoteStore.whenLabel(it.createdAt) }
    val kind = note?.kind ?: NoteStore.Kind.NOTE
    val type = LocalEvType.current
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Keyed on the note, so a different note replaces the text; not keyed on
    // [initial], or every keystroke would reset the caret to the end.
    var value by remember(key) {
        mutableStateOf(TextFieldValue(initial, androidx.compose.ui.text.TextRange(initial.length)))
    }

    LaunchedEffect(key) {
        onChange(value.text)
        // A new note opens straight into the keyboard: there is nothing else
        // to do on this screen and one extra tap to start typing is one too
        // many. An existing one waits to be tapped, so it can be read.
        if (initial.isEmpty()) {
            focus.requestFocus()
            keyboard?.show()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(EV.Background)
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
            .padding(horizontal = EV.PagePadding),
    ) {
        SectionLabel(
            when {
                stamp == null -> "New note"
                kind == NoteStore.Kind.TASK -> "Task"
                kind == NoteStore.Kind.RECORDING -> "Recording"
                else -> "Note"
            },
        )
        if (stamp != null) {
            EvText(stamp, type.label, Modifier.padding(start = 4.dp), color = EV.InkFaint)
            Spacer(Modifier.height(12.dp))
        }

        // A task's one control. On its own row above the words rather than
        // beside them, because ticking something off and correcting its wording
        // are separate acts and a tick next to a caret invites a mis-tap.
        if (note != null && kind == NoteStore.Kind.TASK) {
            TaskDoneRow(note)
            Spacer(Modifier.height(12.dp))
        }

        // A clip, and under it whatever was said in it. The text field stays —
        // the transcript is words like any other, and being able to fix one is
        // the difference between a searchable recording and an audio file.
        if (note != null && note.isRecording) {
            ClipPanel(note)
            Spacer(Modifier.height(12.dp))
        }

        Panel(Modifier.weight(1f)) {
            Box(Modifier.fillMaxSize().padding(18.dp)) {
                if (value.text.isEmpty()) {
                    EvText(
                        when {
                            note?.isRecording == true && !note.transcribed ->
                                "Reading this one\u2026 the words will appear here."
                            note?.isRecording == true -> "Nothing was said in this one."
                            else -> "Nothing in this one yet\u2026"
                        },
                        type.body,
                        color = EV.InkFaint,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        onChange(it.text)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focus),
                    textStyle = type.body,
                    cursorBrush = SolidColor(EV.Ink),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth().padding(bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            EvButton("Delete", kind = EvButtonKind.Danger, onClick = onDelete)
            EvButton("Done", Modifier.weight(1f), onClick = onDone)
        }
    }
}

/**
 * A task's tick, with the word beside it.
 *
 * A whole row rather than the bare circle the library list uses, because here
 * there is room to say what pressing it means — and because this screen is
 * where somebody comes to deal with one task rather than to scan a list of
 * them.
 */
@Composable
private fun TaskDoneRow(note: NoteStore.Note) {
    val type = LocalEvType.current
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(
        if (pressed) EV.SurfaceSunk else EV.Surface, label = "done-row",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(EV.CornerRow))
            .background(fill)
            .clickable(interactionSource = interaction, indication = null) {
                NoteStore.setDone(context, note.id, !note.done)
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(if (note.done) EV.Ink else EV.SurfaceSunk),
            contentAlignment = Alignment.Center,
        ) {
            if (note.done) {
                Canvas(Modifier.size(13.dp)) {
                    val w = 2.dp.toPx()
                    drawLine(
                        EV.OnInk,
                        Offset(size.width * 0.08f, size.height * 0.54f),
                        Offset(size.width * 0.40f, size.height * 0.84f),
                        w, StrokeCap.Round,
                    )
                    drawLine(
                        EV.OnInk,
                        Offset(size.width * 0.40f, size.height * 0.84f),
                        Offset(size.width * 0.94f, size.height * 0.18f),
                        w, StrokeCap.Round,
                    )
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        EvText(
            if (note.done) "Done" else "Mark as done",
            if (note.done) {
                type.body.copy(color = EV.InkFaint, textDecoration = TextDecoration.LineThrough)
            } else {
                type.body
            },
        )
    }
}

/**
 * The clip: a disc, its picture, and the clock.
 *
 * The waveform is draggable here as it is on the pill's card — a recording you
 * can only play from the beginning is one you have to sit through to check one
 * thing in the middle of.
 */
@Composable
private fun ClipPanel(note: NoteStore.Note) {
    val type = LocalEvType.current
    val context = LocalContext.current
    val playback by Playback.state.collectAsState()
    val mine = playback?.takeIf { it.id == note.id }

    Panel(padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ClipDisc(playing = mine?.playing == true) { Playback.toggle(context, note) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                ClipWave(
                    wave = note.wave,
                    progress = mine?.fraction ?: 0f,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                )
                Spacer(Modifier.height(8.dp))
                EvText(
                    NoteStore.clock(mine?.positionMs?.toLong() ?: 0L) + "  /  " +
                        NoteStore.clock(note.durationMs),
                    type.label,
                    color = EV.InkMuted,
                )
            }
        }
    }
}

@Composable
private fun ClipDisc(playing: Boolean, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(if (pressed) EV.CtaSunk else EV.Cta, label = "clip-play")
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(fill)
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(19.dp)) {
            val ink = EV.OnCta
            if (playing) {
                val w = 4.5.dp.toPx()
                val gap = 4.dp.toPx()
                drawRoundRect(
                    ink,
                    topLeft = Offset(size.width / 2f - gap / 2f - w, 0f),
                    size = Size(w, size.height),
                    cornerRadius = CornerRadius(w / 2f),
                )
                drawRoundRect(
                    ink,
                    topLeft = Offset(size.width / 2f + gap / 2f, 0f),
                    size = Size(w, size.height),
                    cornerRadius = CornerRadius(w / 2f),
                )
            } else {
                drawPath(
                    Path().apply {
                        moveTo(size.width * 0.14f, 0f)
                        lineTo(size.width, size.height / 2f)
                        lineTo(size.width * 0.14f, size.height)
                        close()
                    },
                    ink,
                )
            }
        }
    }
}

/** The picture, and the scrubber it doubles as. */
@Composable
private fun ClipWave(wave: List<Int>, progress: Float, modifier: Modifier = Modifier) {
    val ink = EV.Ink
    Canvas(modifier.scrubbable { fraction -> Playback.seekTo(fraction) }) {
        if (wave.isEmpty()) return@Canvas
        val step = 5.dp.toPx()
        val barW = 3.dp.toPx()
        val minH = 2.5.dp.toPx()
        val bars = (size.width / step).toInt().coerceAtLeast(1)
        val head = size.width * progress.coerceIn(0f, 1f)
        for (i in 0 until bars) {
            val peak = wave[(i.toFloat() / bars * wave.size).toInt().coerceIn(0, wave.size - 1)]
            val h = (minH + (size.height - minH) * (peak / 100f)).coerceAtLeast(minH)
            val x = i * step
            drawRoundRect(
                color = ink.copy(alpha = if (x + barW / 2f <= head) 0.95f else 0.30f),
                topLeft = Offset(x, (size.height - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
    }
}

/**
 * A drag anywhere along the bar seeks to it.
 *
 * Its own modifier rather than a gesture inside the Canvas, because the Canvas
 * is redrawn on every position tick and a pointer handler declared inside it
 * would be torn down and rebuilt sixteen times a second, losing whichever drag
 * was in progress.
 */
private fun Modifier.scrubbable(onSeek: (Float) -> Unit): Modifier = this
    .pointerInput(Unit) {
        detectTapGestures { at ->
            onSeek((at.x / size.width.toFloat()).coerceIn(0f, 1f))
        }
    }
    .pointerInput(Unit) {
        // A separate block from the tap above: one pointerInput can host one
        // detector, and a tap and a drag on the same bar are two.
        detectHorizontalDragGestures(
            onDragStart = { at -> onSeek((at.x / size.width.toFloat()).coerceIn(0f, 1f)) },
            onHorizontalDrag = { change, _ ->
                onSeek((change.position.x / size.width.toFloat()).coerceIn(0f, 1f))
            },
        )
    }

/** Open a note from inside the app. The widget uses a PendingIntent for the
 *  same screen; this is the version for a tap on a row in the library. */
fun openNote(context: Context, id: Long) {
    context.startActivity(
        Intent(context, NoteEditActivity::class.java)
            .putExtra(NotesWidget.EXTRA_NOTE_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
