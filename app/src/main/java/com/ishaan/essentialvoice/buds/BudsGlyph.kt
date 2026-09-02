package com.ishaan.essentialvoice.buds

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/**
 * The buds' status light: one dot, and nothing else.
 *
 * It used to be the app's six-dot mark, turning while a pair connected and
 * gathering into a circle when it was up. That said more than the card needs to
 * — the title and the status line already say which pair and what it is doing —
 * so the glyph is now the plainest thing that can carry a state: a single dot
 * that blinks while connecting and sits solid the rest of the time.
 *
 * Its radius is the one the six dots used to collapse into, so a card that was
 * already connected looks unchanged.
 *
 * Rendered to a Bitmap rather than drawn, because this ends up inside a widget:
 * a RemoteViews tree is inflated in the launcher's process, and the only way to
 * put arbitrary drawing in one is to hand it finished pixels. It is also the
 * only way to animate one — see [BudsGlyphAnim].
 */
object BudsGlyph {

    /** As a fraction of the box, from the mark this replaced. */
    private const val DOT = 15.9f / 108f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * @param alpha 1 for the solid dot, lower for a frame of the blink
     */
    fun render(sizePx: Int, color: Int, alpha: Float = 1f): Bitmap {
        val size = sizePx.coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val c = size / 2f

        paint.color = color
        // Alpha after colour: setColor carries the colour's own alpha with it,
        // so setting it first would be overwritten every call.
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        canvas.drawCircle(c, c, DOT * size, paint)
        return bmp
    }
}
