package com.ishaan.essentialvoice.voice

import kotlin.random.Random

/**
 * What the pill says when it has finished.
 *
 * It used to say DONE, every time, in capitals. That is correct and it is also
 * the last thing you see after every single dictation, dozens of times a day —
 * the one moment in the whole app where a little variety costs nothing and is
 * the only thing you are looking at. So it says [PLAIN] about half the time and
 * something else the rest, and once in a while it says thank you to the person
 * whose phone this is.
 *
 * Two rules keep it from becoming annoying rather than fun. **The plain one
 * stays the most likely single outcome**, so the app does not read as
 * unpredictable — you always know the dictation landed, whatever word said so.
 * And **nothing here is ambiguous about success**: no "oh", no "huh", nothing
 * that could be read as the transcription having gone wrong. A joke that makes
 * you check whether your text arrived is a joke that costs more than it earns.
 */
class SignOff(val word: String, val glyph: DotGlyph? = null) {

    companion object {
        /** The one it says most. */
        val PLAIN = SignOff("Done.")

        /**
         * How often the plain one comes up. Half: often enough to be the
         * app's normal voice, rare enough that the others are not a novelty
         * you stop seeing after a week.
         */
        private const val PLAIN_CHANCE = 0.5f

        /**
         * The rest. Short on purpose — the pill is 76dp wide and a sign-off
         * that has to be shrunk to fit is a sign-off you squint at.
         */
        private val REST = listOf(
            SignOff("Donzo"),
            SignOff("Got it"),
            SignOff("Typed."),
            SignOff("Sorted"),
            SignOff("Noted"),
            SignOff("Heard"),
            SignOff("Bosh"),
            SignOff("Word."),
            SignOff("Nice one"),
            SignOff("Copy that"),
            SignOff("Roger"),
            SignOff("Aye"),
            SignOff("Ta"),
            SignOff("Boom"),
            SignOff("Neat"),
            SignOff("Yoink"),
            SignOff("Ship it"),
            SignOff("Easy"),
            SignOff("Onwards"),
            SignOff("Mic drop"),
            SignOff("Beep"),
            SignOff("10-4"),
            // The dots the pill already speaks in, arranged into something
            // other than a waveform. A glyph on its own is the quietest of
            // these and reads at a glance, which is why there are several.
            SignOff("", DotGlyph.HEART),
            SignOff("", DotGlyph.SMILE),
            SignOff("", DotGlyph.CHECK),
            SignOff("", DotGlyph.STAR),
            // The one that started all of this.
            SignOff("Carl Pei", DotGlyph.HEART),
        )

        fun pick(): SignOff =
            if (Random.nextFloat() < PLAIN_CHANCE) PLAIN else REST.random()
    }
}

/**
 * A small picture made of the pill's own dots.
 *
 * Five by five, drawn on the same grid and with the same paint as the five bars
 * and the error cross, because the pill has exactly one visual vocabulary and a
 * drawn heart in it would be a second one. Written as art rather than as
 * coordinates so that changing a glyph is a matter of moving an X.
 */
enum class DotGlyph(art: List<String>) {
    HEART(
        listOf(
            ".X.X.",
            "XXXXX",
            "XXXXX",
            ".XXX.",
            "..X..",
        ),
    ),
    SMILE(
        listOf(
            ".X.X.",
            ".X.X.",
            ".....",
            "X...X",
            ".XXX.",
        ),
    ),
    CHECK(
        listOf(
            ".....",
            "....X",
            "X..X.",
            ".XX..",
            "..X..",
        ),
    ),
    STAR(
        listOf(
            "..X..",
            "..X..",
            "XXXXX",
            ".XXX.",
            "X...X",
        ),
    ),
    ;

    private val grid: List<BooleanArray> = art.map { row ->
        BooleanArray(row.length) { row[it] == 'X' }
    }

    val rows: Int = grid.size
    val cols: Int = grid.first().size

    fun on(x: Int, y: Int): Boolean = grid[y][x]
}
