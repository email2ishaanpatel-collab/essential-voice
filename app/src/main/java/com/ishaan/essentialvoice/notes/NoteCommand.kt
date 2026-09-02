package com.ishaan.essentialvoice.notes

/**
 * Decides whether something dictated was meant for the library, and for which
 * shelf of it.
 *
 * The rule is deliberately narrow: the **first** word has to be the command
 * word, and nothing else counts. "Note that the meeting moved" is a note; "I
 * should note that" is not, and must never be, because the whole sentence would
 * vanish into the library instead of being typed where the person was looking.
 * A command that can fire from the middle of a sentence makes the key
 * untrustworthy, which costs more than the convenience is worth.
 *
 * whisper capitalises and punctuates, so what actually arrives is "Note, buy
 * milk." or "Task: call the bank" — the separator has to be forgiven, the
 * position does not.
 *
 * The plurals and the `-ing` are there because the *model* produces them, not
 * because anybody says them. Asked for one word in isolation a small model will
 * happily return the commonest form of it, which for these three is "Notes",
 * "Tasks" and "Recording".
 */
object NoteCommand {

    /**
     * What was said, and which shelf it was said for.
     *
     * [body] is everything after the command word, trimmed. It is legitimately
     * empty — the bare word "note" means "open one and wait" — so this is a
     * pair rather than a nullable string.
     */
    data class Hit(val kind: NoteStore.Kind, val body: String)

    /**
     * Anchored at the start, with the separator whisper adds forgiven.
     *
     * Order matters only in that each is tried in turn; the three words share no
     * prefix, so no input can match two.
     */
    private val PREFIXES = listOf(
        NoteStore.Kind.NOTE to Regex("""^\s*notes?\b[\s,.:;!?—–-]*""", RegexOption.IGNORE_CASE),
        NoteStore.Kind.TASK to Regex("""^\s*tasks?\b[\s,.:;!?—–-]*""", RegexOption.IGNORE_CASE),
        NoteStore.Kind.RECORDING to
            Regex("""^\s*record(s|ing)?\b[\s,.:;!?—–-]*""", RegexOption.IGNORE_CASE),
    )

    /**
     * The command this transcript is, or null if it was ordinary dictation.
     */
    fun parse(transcript: String): Hit? {
        PREFIXES.forEach { (kind, prefix) ->
            val match = prefix.find(transcript) ?: return@forEach
            return Hit(kind, transcript.substring(match.range.last + 1).trim())
        }
        return null
    }

    /**
     * The word on the pill while the key is still down.
     *
     * "Notes" rather than "Note" is the older label and stays as it was; the
     * other two name the single thing being made, because that is what the
     * badge is announcing.
     */
    fun badgeLabel(kind: NoteStore.Kind): String = when (kind) {
        NoteStore.Kind.NOTE -> "Notes"
        NoteStore.Kind.TASK -> "Task"
        NoteStore.Kind.RECORDING -> "Recording"
    }
}
