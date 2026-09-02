package com.ishaan.essentialvoice

/**
 * What this build ships.
 *
 * The v3 test build is deliberately narrower than the tree it is cut from: the
 * island, back tap, game mode and the Gemini question are all written and all
 * working, and none of them is in this build. They are switched off here rather
 * than deleted so that turning one back on is a `true` and a rebuild, not a
 * revert — and so the code goes on compiling, which is what keeps it honest
 * while it waits.
 *
 * A `const val false` is also what lets the compiler drop the branch entirely:
 * a gated feature costs nothing at runtime and, for the ones that own a window
 * or a sensor, is never attached at all. See [com.ishaan.essentialvoice.trigger.EssentialKeyService].
 */
object Features {
    /** The lozenge at the camera cutout, and with it the media player on it. */
    const val ISLAND = false

    /** Two knocks on the back of the phone. */
    const val BACK_TAP = false

    /** "gemini …" as a spoken question. */
    const val GEMINI = false

    /**
     * Android's own recogniser, and with it the hundred-language picker.
     *
     * Off for 3.0. Both are written, both work, and both are more app than 3.0
     * is trying to be: the release is about one engine that runs on the phone,
     * and a language list of a hundred in front of a whisper build that only
     * hears English is a setting that mostly says no.
     *
     * Switching it off is not only the two rows — [Prefs] forces the engine to
     * whisper and the language to English while it is false, because the engine
     * defaulted to Google and an install that had already chosen it would
     * otherwise be left on an engine with no way back to the other one.
     */
    const val GOOGLE_SPEECH = false

    /** The whole game-mode section, its tile and its levers. */
    const val GAME_MODE = false

    /**
     * The heart on every launcher card.
     *
     * On, but it needs a `supabase.properties` to do anything: without one the
     * build carries no backend and the hearts do not draw. This flag is the
     * other switch — the one that turns the whole idea off in a build that *is*
     * configured, without having to pull the config out from under it.
     *
     * It is also the one feature in this app that talks to a server about
     * anything other than the user's own question. See
     * [com.ishaan.essentialvoice.social.Likes] for exactly what leaves the
     * phone, which is a card's name and a random number.
     */
    const val LIKES = true

    /**
     * This app drawing the volume, instead of the panel Nothing OS draws.
     *
     * On, unlike the four above it, because it is a setting rather than a
     * section: switched off in Prefs by default, so the build ships it and
     * nobody's volume buttons change until they ask.
     */
    const val VOLUME_SLIDER = true

    /**
     * Two fingers up from the home bar.
     *
     * **Off, because the launcher owns this gesture on this ROM** — which is the
     * opposite of what [com.ishaan.essentialvoice.voice.HomeSwipe] was built on.
     * Measured 2026-09-02 with the strip's own trace and `logcat`: every attempt
     * pairs, tracks, and is then pilfered 28-68px in, well short of the 287px
     * commit, by
     *
     *     Channel [Gesture Monitor] swipe-up is stealing input gesture
     *       from [com.ishaan.essentialvoice, [Gesture Monitor] edge-swipe]
     *     NT-Recent: mInteractionHandler = NTLauncherSwipeHandlerV2
     *     onSwipeInteractionCompleted targetState = Overview
     *
     * `NTLauncherSwipeHandlerV2` tracks two pointers perfectly happily and takes
     * the swipe to Overview. So firing earlier is not a fix either: the
     * dictation would start *and* the phone would go to recents.
     *
     * Left switched off rather than deleted, like the island. If it is ever
     * picked up again the two untried ideas are three fingers (Quickstep may
     * stop at two) and starting the drag above the navigation bar, where
     * Quickstep does not begin a home swipe — which costs touches in a part of
     * the screen people actually use.
     */
    const val HOME_SWIPE = false
}
