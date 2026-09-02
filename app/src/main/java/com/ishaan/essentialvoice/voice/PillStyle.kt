package com.ishaan.essentialvoice.voice

import androidx.core.graphics.ColorUtils

/**
 * What the pill is made of: one fill, and the one colour that is legible on it.
 *
 * [ink] is not decoration — it is the dots, the words and the card's buttons,
 * so it has to be chosen against [fill] rather than picked to taste. That is
 * why the two travel together instead of being two settings: a pill where you
 * can choose black dots on black is a pill someone will end up with.
 */
data class PillStyle(
    val id: String,
    val label: String,
    val fill: Int,
    val ink: Int,
    /**
     * Frosted rather than filled: the window blurs what is behind it and [fill]
     * is a scrim laid over the result, not an opaque colour.
     */
    val blurred: Boolean = false,
)

/**
 * The colours the pill can be.
 *
 * Deliberately short and deliberately far apart. A picker with thirty swatches
 * is a picker where half the choices are the same colour twice, and the point
 * of this list is that you can tell at a glance which one is selected — so
 * there is one yellow, not the three near-identical yellows Nothing's own
 * palettes contain between them.
 */
object PillStyles {

    const val DEFAULT_ID = "yellow"

    val all = listOf(
        // The brand, and the one every previous build shipped. Unchanged, so
        // that choosing nothing leaves the pill exactly as it was.
        PillStyle("yellow", "Yellow", 0xFFFFD900.toInt(), 0xFF1B1B1D.toInt()),
        // Nothing OS's own accent — the glyph lights and the widget red.
        PillStyle("red", "Red", 0xFFD71921.toInt(), 0xFFFFFFFF.toInt()),
        // CMF Phone 2 Pro's orange, which is the phone this was written on.
        PillStyle("orange", "Orange", 0xFFFF6B2B.toInt(), 0xFF1B1B1D.toInt()),
        PillStyle("pink", "Pink", 0xFFF2C4C7.toInt(), 0xFF1B1B1D.toInt()),
        PillStyle("blue", "Blue", 0xFF002F6C.toInt(), 0xFFFFFFFF.toInt()),
        PillStyle("black", "Black", 0xFF000000.toInt(), 0xFFFFFFFF.toInt()),
        PillStyle("white", "White", 0xFFFFFFFF.toInt(), 0xFF1B1B1D.toInt()),
        // A scrim over a blurred window rather than a colour. Dark and thin:
        // white ink has to stay readable over whatever it is standing on, and
        // the only thing that guarantees that over both a white page and a
        // photograph is darkening what shows through.
        PillStyle("glass", "Glass", 0x8C15161A.toInt(), 0xFFFFFFFF.toInt(), blurred = true),
    )

    fun byId(id: String): PillStyle = all.firstOrNull { it.id == id } ?: all[0]

    /**
     * A step of [fill] towards [ink], for the card's quieter button.
     *
     * Derived rather than listed, because a hand-picked "sunk" colour per style
     * is eight more numbers to keep in step with eight fills, and every one of
     * them would only ever be "the fill, slightly towards the text".
     */
    fun sunk(style: PillStyle): Int = ColorUtils.blendARGB(style.fill, style.ink, 0.14f)
}
