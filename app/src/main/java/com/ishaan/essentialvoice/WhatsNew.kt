package com.ishaan.essentialvoice

/**
 * The short list of what changed, shown under Updates.
 *
 * There are two sources and they answer different questions.
 *
 * The **local** list below travels inside the APK, so the build can always say
 * what it itself brought, with no network and no waiting. It is the one people
 * see straight after installing — which is exactly the moment they want it.
 *
 * The **remote** list comes from `update.json` and describes the build that is
 * out, not the one installed. That is the only one that can carry pictures,
 * because a picture has to be uploaded somewhere after the APK is already built.
 *
 * Adding an entry for a release means two edits: [local] here, and `whatsNew`
 * in `update.json` at the root of the repository — `publish/` was a second
 * checkout of this same repository and is gone. Keep both short: this is a
 * changelog someone reads standing up.
 */
object WhatsNew {

    /**
     * One thing that changed.
     *
     * [image] is an absolute https URL to a picture; anything the release page
     * can host works. It is optional, and an entry without one is a normal
     * line of text — the panel does not leave a gap where a picture would be.
     */
    data class Item(
        val title: String,
        val body: String,
        val image: String? = null,
    )

    /**
     * What *this* build brought. Text only, by construction: a drawable would
     * have to ship in the APK, and pictures of a feature are usually made after
     * the build that contains it.
     */
    val local: List<Item> = listOf(
        Item(
            "Your library",
            "The Notes tab is a library now. Everything the key kept is in one " +
                "list \u2014 notes, tasks and recordings \u2014 with a row of chips " +
                "at the top to show one kind at a time or all of them together.",
        ),
        Item(
            "Tasks",
            "A second thing the key can keep, alongside notes rather than in " +
                "place of them. Start the sentence with \"task\" and you get one " +
                "with a circle you can tick off \u2014 ticking it strikes it " +
                "through and leaves it in the list.",
        ),
        Item(
            "Recordings",
            "And a third. Start with \"record\" and it keeps the audio instead " +
                "of the words \u2014 the card is up the moment you let go, " +
                "waveform already drawn, nothing waiting on a decode. Tap the " +
                "disc to play it back. The words are found in the background and " +
                "appear under the clip when they are ready.",
        ),
        Item(
            "Notes on the home screen",
            "A widget showing the same list as the library. It changes the " +
                "moment a note does, tapping one opens it for editing, and the " +
                "+ starts an empty one.",
        ),
        Item(
            "Notes can be edited",
            "Tap the card while it is still on screen and the keyboard opens " +
                "with the caret after the last word \u2014 or tap a note anywhere " +
                "else, in the app or on the home screen. It saves itself when " +
                "you leave.",
        ),
        Item(
            "Buds, one tap away",
            "Pick a pair you have already paired, then connect or disconnect " +
                "them from a home screen widget or a Quick Settings tile. " +
                "Nothing is scanned for and no address is hardcoded.",
        ),
        Item(
            "The app can draw the volume",
            "A capsule down the side of the screen instead of the panel the " +
                "phone draws, with the side, height, thickness and how long it " +
                "lingers all yours. Off until you turn it on \u2014 it changes " +
                "what your volume buttons do.",
        ),
        Item(
            "A bar for a toggled dictation",
            "Started one without holding the key? A wide bar sits over the " +
                "gesture handle so there is something to stop it with. A held " +
                "key does not get one, because letting go is the stop control.",
        ),
        Item(
            "Fewer permissions, and a card for each feature",
            "Three permissions this build could never use are gone from the " +
                "manifest. The front page is a card per feature now, each " +
                "opening its own page instead of one long scroll.",
        ),
    )
}
