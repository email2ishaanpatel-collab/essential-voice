package com.ishaan.essentialvoice.notes

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The library: everything the key kept, on this phone and nowhere else.
 *
 * A single JSON file rewritten in full on every change, not an append log:
 * entries get *added to* after they are created, so an append-only file would
 * need line surgery to update one, and the whole store is a few kilobytes. The
 * write goes to a temporary file and is renamed over the real one, so a crash
 * halfway through leaves the previous version intact rather than half a file
 * that will not parse.
 *
 * This is the first thing the app keeps. Everything else — the audio of a
 * dictation, its transcript — is used and dropped, which is what lets the app
 * say nothing is stored. That claim has exactly one exception and it is this:
 * app-private storage, deletable from the Library, still never leaving the
 * phone. A recording is the same promise with a bigger file behind it.
 *
 * **The class is still called NoteStore and the file is still `notes.json`.**
 * Neither is worth renaming: the file name is what every install already has on
 * disk, and the class is named in [NotesWidget], whose *component* name is the
 * launcher's key for every widget anyone has already placed. What changed is
 * what a row can be — see [Kind].
 */
object NoteStore {

    private const val TAG = "EVNotes"
    private const val FILE = "notes.json"

    /** Where a recording's audio lives, under the app's own files directory. */
    private const val AUDIO_DIR = "recordings"

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * What a row is.
     *
     * Three kinds rather than three stores, because they are one list sorted by
     * time and the Library filters it. A separate file per kind would mean three
     * loads, three writes and three chances for the widget to be looking at a
     * stale one.
     */
    enum class Kind {
        /** Something said, kept as words. */
        NOTE,

        /** Something to do. The only kind with a [Note.done]. */
        TASK,

        /** Something said, kept as audio. The only kind with a [Note.audio]. */
        RECORDING;

        /** What the store writes. Lower case so the file reads like prose. */
        val tag: String get() = name.lowercase()

        companion object {
            /**
             * A row written before there were kinds is a note. That is the whole
             * migration: no version field, no rewrite pass, and a store from v3
             * opens in a build that has never heard of tasks.
             */
            fun of(tag: String?): Kind =
                entries.firstOrNull { it.tag == tag } ?: NOTE
        }
    }

    data class Note(
        val id: Long,
        val createdAt: Long,
        val text: String,
        val kind: Kind = Kind.NOTE,
        /** Ticked off. Meaningless on anything but a [Kind.TASK]. */
        val done: Boolean = false,
        /** File name under `recordings/`, for a [Kind.RECORDING] only. */
        val audio: String? = null,
        val durationMs: Long = 0L,
        /**
         * The waveform, as 0..100 peaks, one per bucket.
         *
         * Measured once when the clip is saved and kept, rather than recomputed
         * from the audio every time a row scrolls past. A recording's file is
         * megabytes; its picture is a hundred small numbers.
         */
        val wave: List<Int> = emptyList(),
        /**
         * Whether the transcript in [text] is finished.
         *
         * False on a recording that has been saved but not yet decoded, which is
         * the state the background pass in [Transcribe] exists to clear. Always
         * true for a note or a task, whose text was never anything else.
         */
        val transcribed: Boolean = true,
    ) {
        /** First line, for the list. Falls back to the whole thing. */
        val title: String
            get() = text.lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { text.trim() }

        val isRecording: Boolean get() = kind == Kind.RECORDING
    }

    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes

    private var loaded = false

    private fun file(context: Context) = File(context.filesDir, FILE)

    /** The directory recordings live in, created on the way past. */
    fun audioDir(context: Context): File =
        File(context.filesDir, AUDIO_DIR).also { if (!it.exists()) it.mkdirs() }

    /** The audio behind a recording, or null if this row has none. */
    fun audioFile(context: Context, note: Note): File? =
        note.audio?.let { File(audioDir(context), it) }

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val f = file(context)
        if (!f.exists()) return
        runCatching {
            val array = JSONArray(f.readText())
            val out = ArrayList<Note>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                out += Note(
                    id = o.optLong("id"),
                    createdAt = o.optLong("createdAt"),
                    text = o.optString("text"),
                    kind = Kind.of(o.optString("kind").ifBlank { null }),
                    done = o.optBoolean("done", false),
                    audio = o.optString("audio").ifBlank { null },
                    durationMs = o.optLong("durationMs"),
                    wave = o.optJSONArray("wave")?.let { w ->
                        List(w.length()) { k -> w.optInt(k) }
                    } ?: emptyList(),
                    // Absent means an entry written before recordings existed,
                    // and those are all notes, whose text was never pending.
                    transcribed = o.optBoolean("transcribed", true),
                )
            }
            _notes.value = out.sortedByDescending { it.createdAt }
        }.onFailure {
            // A store that will not parse is not worth crashing over, and not
            // worth deleting either — it is set aside so it can be recovered.
            Log.w(TAG, "notes.json did not parse; setting it aside", it)
            runCatching { f.renameTo(File(context.filesDir, "$FILE.broken")) }
            _notes.value = emptyList()
        }
    }

    @Synchronized
    private fun persist(context: Context) {
        val array = JSONArray()
        _notes.value.forEach { n ->
            val o = JSONObject()
                .put("id", n.id)
                .put("createdAt", n.createdAt)
                .put("text", n.text)
            // Only what this row actually is. A note carries three fields the
            // way it always did, so the file does not double in size the day
            // tasks arrive.
            if (n.kind != Kind.NOTE) o.put("kind", n.kind.tag)
            if (n.done) o.put("done", true)
            if (n.audio != null) {
                o.put("audio", n.audio)
                o.put("durationMs", n.durationMs)
                o.put("wave", JSONArray().also { w -> n.wave.forEach(w::put) })
                o.put("transcribed", n.transcribed)
            }
            array.put(o)
        }
        val target = file(context)
        val tmp = File(context.filesDir, "$FILE.tmp")
        runCatching {
            tmp.writeText(array.toString())
            if (!tmp.renameTo(target)) {
                // renameTo can fail on some filesystems; a copy still beats
                // losing the note.
                target.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { Log.e(TAG, "could not write notes", it) }

        // The list on the home screen is the same list. Every write goes
        // through here, so this is the one place it has to be told — a widget
        // that only caught up on a timer would show a note that had already
        // been changed, which is worse than not having a widget.
        //
        // Posted rather than called. This runs on whatever thread wrote the
        // note, which during a dictation is the main thread, and it runs
        // holding this object's monitor. A refresh rebuilds every row of every
        // placed widget and ships them over binder — doing that here would put
        // an IPC round trip inside the lock and inside the frame the pill is
        // animating in. Posting gets it off both and still lands within a
        // frame or two.
        val ctx = context.applicationContext
        main.post { NotesWidget.refresh(ctx) }
    }

    /**
     * An id no existing note has.
     *
     * The clock alone is not enough: two notes made inside the same millisecond
     * get the same number, and since every lookup here is
     * `firstOrNull { it.id == id }`, a collision means editing one rewrites the
     * other and deleting one leaves a twin nothing can reach. The widget makes
     * it worse — its rows are built with stable ids, so duplicates recycle one
     * note's view onto another. Stepping past the highest id in use keeps the
     * value time-like, which is what the sort and the timestamp want, while
     * making it unique, which is what everything else wants.
     */
    private fun freshId(now: Long): Long {
        val highest = _notes.value.maxOfOrNull { it.id } ?: 0L
        return if (now > highest) now else highest + 1
    }

    /** Start a note or a task. [text] may be blank — the row exists to be added to. */
    @Synchronized
    fun start(context: Context, text: String, kind: Kind = Kind.NOTE): Note {
        load(context)
        val now = System.currentTimeMillis()
        val note = Note(id = freshId(now), createdAt = now, text = text.trim(), kind = kind)
        _notes.value = listOf(note) + _notes.value
        persist(context)
        return note
    }

    /**
     * Put a finished clip in the library.
     *
     * Created already saved rather than after a confirmation, exactly like a
     * note: the card that comes up over it is a chance to *delete* it, not a
     * gate it has to get through. That is what makes a recording survive the
     * card being swiped away, the process being killed, or the phone dying with
     * the card still up — none of which should cost you the audio.
     */
    @Synchronized
    fun startRecording(
        context: Context,
        audio: String,
        durationMs: Long,
        wave: List<Int>,
    ): Note {
        load(context)
        val now = System.currentTimeMillis()
        val note = Note(
            id = freshId(now),
            createdAt = now,
            text = "",
            kind = Kind.RECORDING,
            audio = audio,
            durationMs = durationMs,
            wave = wave,
            transcribed = false,
        )
        _notes.value = listOf(note) + _notes.value
        persist(context)
        return note
    }

    /** Add a line to an existing note, keeping it at the top of the list. */
    @Synchronized
    fun append(context: Context, id: Long, line: String): Note? {
        load(context)
        val existing = _notes.value.firstOrNull { it.id == id } ?: return null
        val joined =
            if (existing.text.isBlank()) line.trim()
            else existing.text.trimEnd() + "\n" + line.trim()
        val updated = existing.copy(text = joined)
        _notes.value = listOf(updated) + _notes.value.filterNot { it.id == id }
        persist(context)
        return updated
    }

    /**
     * Replace a note's text outright, from the editor.
     *
     * Unlike [append] this leaves the note where it is in the list. Fixing a
     * typo is not the same event as saying something new into it, and having a
     * note jump to the top because it was opened and read would make the order
     * meaningless.
     *
     * A note edited down to nothing is deleted: an empty row that cannot be
     * tapped into anything is not worth a line on the home screen. **A
     * recording is not**, because its words are not what it is — clearing the
     * transcript of a clip must never throw the clip away.
     */
    @Synchronized
    fun update(context: Context, id: Long, text: String): Note? {
        load(context)
        val existing = _notes.value.firstOrNull { it.id == id } ?: return null
        val trimmed = text.trim()
        if (trimmed == existing.text) return existing
        if (trimmed.isBlank() && !existing.isRecording) {
            delete(context, id)
            return null
        }
        // Words somebody typed are the last word. A clip whose transcript is
        // still pending would otherwise have the background pass land on top of
        // an edit made while it was running, silently undoing it.
        val updated = existing.copy(text = trimmed, transcribed = true)
        _notes.value = _notes.value.map { if (it.id == id) updated else it }
        persist(context)
        return updated
    }

    /** Tick a task off, or put it back. */
    @Synchronized
    fun setDone(context: Context, id: Long, done: Boolean): Note? {
        load(context)
        val existing = _notes.value.firstOrNull { it.id == id } ?: return null
        if (existing.done == done) return existing
        val updated = existing.copy(done = done)
        _notes.value = _notes.value.map { if (it.id == id) updated else it }
        persist(context)
        return updated
    }

    /**
     * The transcript of a recording has landed.
     *
     * Marked finished whether or not there were any words: a clip of somebody
     * clearing their throat decodes to nothing, and leaving that one pending
     * for ever would have [Transcribe] pick it up again on every launch.
     */
    @Synchronized
    fun setTranscript(context: Context, id: Long, text: String): Note? {
        load(context)
        val existing = _notes.value.firstOrNull { it.id == id } ?: return null
        val updated = existing.copy(text = text.trim(), transcribed = true)
        _notes.value = _notes.value.map { if (it.id == id) updated else it }
        persist(context)
        return updated
    }

    /**
     * Take a row out of the library, and its audio off the disk with it.
     *
     * The file has to go here rather than being swept later. It is the one
     * thing this app stores that is measured in megabytes, and a deleted
     * recording that quietly left its audio behind would be the app filling the
     * phone with clips the user believes they threw away.
     */
    @Synchronized
    fun delete(context: Context, id: Long) {
        load(context)
        _notes.value.firstOrNull { it.id == id }?.let { note ->
            audioFile(context, note)?.let { f -> runCatching { f.delete() } }
        }
        _notes.value = _notes.value.filterNot { it.id == id }
        persist(context)
    }

    /**
     * When a note was taken, short enough for a widget row.
     *
     * Here rather than in the UI because two different things draw it — the
     * list in the app and the list on the home screen — and they have to agree.
     */
    fun whenLabel(millis: Long): String =
        android.text.format.DateFormat.format("d MMM  ·  HH:mm", millis).toString()

    /** A length, as m:ss. The one clock format the app uses for audio. */
    fun clock(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    /**
     * A row nobody ever put anything into is not worth keeping.
     *
     * A recording is never empty in this sense — it has audio whether or not it
     * has words — so this only ever looks at the text of a note or a task.
     */
    @Synchronized
    fun discardIfEmpty(context: Context, id: Long) {
        val n = _notes.value.firstOrNull { it.id == id } ?: return
        if (!n.isRecording && n.text.isBlank()) delete(context, id)
    }
}
