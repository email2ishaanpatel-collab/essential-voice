package com.ishaan.essentialvoice.ai

import kotlin.math.min

/**
 * Decides whether something dictated was meant as a question for Gemini.
 *
 * Deliberately the same shape as [com.ishaan.essentialvoice.notes.NoteCommand],
 * and for the same reason: the **first** word has to be the trigger and nothing
 * else counts. "Gemini, how far is the moon" is a question; "I asked Gemini
 * about it" is dictation and must stay dictation, because a command that can
 * fire from the middle of a sentence would send whatever someone was typing to
 * a server. That is a much worse failure here than it is for notes — a note
 * that eats a sentence is annoying, a question that eats one is a sentence
 * leaving the phone — so the rule does not get relaxed later.
 *
 * ------------------------------------------------------------- mishearing
 *
 * The real difficulty is not parsing, it is that whisper cannot reliably hear
 * the word. Measured on this phone with `tiny.en`, "Gemini, how tall is Mount
 * Everest" came back as "Jabinai", "Jibni", "JEBNE", "J.B.I. Hello", "Jim and
 * I", "Germany" and — about half the time — correctly.
 *
 * The primary fix is at the decoder, not here: `WhisperEngine.VOCAB_PROMPT`
 * primes whisper with the word so it produces the right spelling in the first
 * place. This is the safety net under that, and it is deliberately a net with
 * holes:
 *
 *  * [NEARLY] carries the spellings that are **not words** — nobody dictates
 *    "Jabinai", so matching it costs nothing.
 *  * A small edit distance from "gemini" catches the family of near-misses
 *    without enumerating it.
 *  * "Germany" and "Jim and I" are deliberately **absent**. Both are things a
 *    person really might say at the start of a sentence, and silently posting
 *    someone's dictation to an API because they began with "Germany" is a far
 *    worse failure than making them repeat themselves.
 */
object AskCommand {

    /** Heard on this phone, and none of them a word anyone would dictate. */
    private val NEARLY = setOf(
        "gemini", "jemini", "gemeni", "geminy", "jeminy", "jiminy",
        "jabinai", "jibni", "jebne", "jbi", "gemni", "gemin",
    )

    /** Anything that is not a letter: strips "J.B.I." to "jbi". */
    private val LETTERS = Regex("[^\\p{L}]")

    /** What may sit between the trigger and the question. */
    private val SEPARATOR = Regex("^[\\s,.:;!?—–-]*")

    /**
     * The question if this was one, or null if it was ordinary dictation.
     *
     * A blank result is *not* a question — saying only the word "Gemini" asks
     * nothing, and firing a request off an empty prompt would be a round trip
     * to be told nothing was asked. It returns null so the word is simply typed
     * like any other.
     */
    fun parse(transcript: String): String? {
        val trimmed = transcript.trimStart()
        // The first run of characters up to whitespace — "Gemini," and "J.B.I."
        // are both one token, which is what makes the letters-only comparison
        // below work on either.
        val first = trimmed.takeWhile { !it.isWhitespace() }
        if (first.isEmpty()) return null
        if (!isTrigger(LETTERS.replace(first, "").lowercase())) return null

        val rest = trimmed.substring(first.length)
        return SEPARATOR.replace(rest, "").trim().ifBlank { null }
    }

    private fun isTrigger(word: String): Boolean {
        if (word.isEmpty()) return false
        if (word in NEARLY) return true
        // Two edits covers jemini/gemeni/geminii and stops well short of
        // ordinary English. Length-gated first so short words cannot qualify.
        return word.length >= 5 && editDistance(word, "gemini") <= 2
    }

    /** Levenshtein, two rows. Words this short make anything cleverer waste. */
    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val swap = prev; prev = cur; cur = swap
        }
        return prev[b.length]
    }
}
