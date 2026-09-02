package com.ishaan.essentialvoice.voice

import android.view.View
import android.view.WindowInsets

/**
 * Where the keyboard's top edge is on screen, for a window that gets out of the
 * keyboard's way by moving itself.
 *
 * **The trap this exists to close.** `WindowInsets.ime.bottom` is measured
 * against *this window's own frame*. An overlay that reads it, moves up by it,
 * and is then asked again gets a smaller number — because the move worked — so
 * it moves back down; asked again it gets the full number and moves back up.
 * Two states, one relayout apart, forever. On screen that is the note card
 * shaking at frame rate for as long as the keyboard is up, which is exactly
 * what it looks like: the phone becomes unusable rather than merely ugly. The
 * `if (bottom == lastBottom) return` guard both callers had cannot catch it,
 * because the value genuinely alternates.
 *
 * **The fix** is to stop storing the inset and store something the window's own
 * position cannot change. `frameBottom - bottom` is the top of the keyboard in
 * screen coordinates, and it is the same number whether the window has been
 * lifted yet or not — so reading it twice can no longer disagree with itself.
 *
 * Two consequences worth stating, because both look like bugs otherwise:
 *
 * - **A zero inset is not news.** It means the window is already clear of the
 *   keyboard, which is the state the lift was trying to reach. Treating it as
 *   "the keyboard went away" is the second half of the loop. [update] keeps the
 *   last known top instead.
 * - **[WindowInsets.isVisible] is what says the keyboard has gone**, and unlike
 *   the inset it does not depend on where the window sits.
 */
class ImeTop {

    /** Screen Y of the top of the keyboard, or 0 while there is no keyboard. */
    var y = 0
        private set

    /**
     * Take a fresh set of insets. Returns true when [y] moved and the caller
     * therefore has a window to reposition — false is the common case and must
     * stay cheap, since this runs on every relayout of the window.
     */
    fun update(insets: WindowInsets, view: View): Boolean {
        val next = if (!insets.isVisible(WindowInsets.Type.ime())) {
            0
        } else {
            val bottom = insets.getInsets(WindowInsets.Type.ime()).bottom
            // The requested `lp.y` is not always the frame the system gave the
            // window; the view knows where it actually landed.
            if (bottom > 0) frameBottom(view) - bottom else y
        }
        if (next == y) return false
        y = next
        return true
    }

    /** The keyboard is gone as far as this window is concerned. */
    fun clear() {
        y = 0
    }

    private fun frameBottom(view: View): Int {
        val at = IntArray(2)
        view.getLocationOnScreen(at)
        return at[1] + view.height
    }
}
