package com.ishaan.essentialvoice.game

/**
 * What game mode is allowed to change, and what it changes it to.
 *
 * Every one of these is a switch rather than a fact of the feature, because
 * "peak efficiency" is not one setting on a phone — it is a handful of small
 * ones, and which of them are worth the trade is a question only the person
 * holding the phone can answer. A game mode that silently pins the rotation of
 * somebody who wanted only the notifications gone is a game mode that gets
 * switched off.
 *
 * The levers split into three groups by what they cost to have:
 *
 *  - **Free.** [silenceKey] and [hideIsland] are this app's own behaviour, and
 *    need nothing granted.
 *  - **Already paid for.** [silenceNotifications] rides on the notification
 *    listener the island already needed.
 *  - **Asked for.** [lockRotation] and [quietTouch] need "Modify system
 *    settings", and [killAnimations] needs a permission only adb can grant.
 *
 * Anything not granted is shown as not granted rather than failing quietly —
 * see the settings screen. Nothing here pretends to have worked.
 *
 * **Four levers used to be here and were measured off the phone.** Dropping the
 * speech model claimed "a few hundred megabytes" for a 78 MB `tiny.en` that
 * [com.ishaan.essentialvoice.whisper.WhisperEngine.unloadIfIdle] has already
 * unloaded by the time anyone reaches a game. Keeping the screen awake pushed
 * out a timeout that every real game already holds a `SCREEN_BRIGHT_WAKE_LOCK`
 * against. Pinning the brightness at 80% raised panel power on a phone measured
 * *already* reporting `THROTTLING_LIGHT`, buying glare at the cost of the
 * sustained frame rate it was supposed to protect. The edge guard put two more
 * overlay windows on the one screen that wants fewest, to do something its own
 * documentation admitted was never confirmed to work. None of the four survived
 * contact with `dumpsys`.
 */
data class GameProfile(
    /** Ignore the Essential Key entirely, so a mistouch mid-game types nothing. */
    val silenceKey: Boolean,
    /** Take the island off the screen: it is a permanent overlay, over the game. */
    val hideIsland: Boolean,
    /** Do Not Disturb, through the notification listener. */
    val silenceNotifications: Boolean,
    /** Auto-rotate off, so a lean sideways does not flip a landscape game. */
    val lockRotation: Boolean,
    /** Touch sounds and system haptics off. */
    val quietTouch: Boolean,
    /**
     * All three animation scales to zero. Needs WRITE_SECURE_SETTINGS.
     *
     * Worth having and worth being honest about: the scales cost nothing at all
     * while a fullscreen game is in front, because nothing is transitioning
     * between windows. What they buy is the time either side — arming, alt-tabs
     * out to a message and back, disarming.
     */
    val killAnimations: Boolean,
    /**
     * Arm and disarm on its own as games come and go.
     *
     * Off until asked for, and deliberately so: this is the one switch here that
     * makes the app look at *which app is in front*, and that is not something to
     * start doing on somebody's behalf. Switching it on subscribes the
     * accessibility service to window changes; switching it off unsubscribes
     * again, so the cost is only paid while the feature is wanted.
     */
    val autoArm: Boolean,
    /**
     * The packages [autoArm] arms for.
     *
     * Seeded once, from the games the store already labelled as games, and then
     * owned by the user — a list that silently re-seeded itself would undo every
     * app they took out of it.
     */
    val armFor: Set<String>,
    /** Whether [armFor] has been seeded, so an emptied list stays empty. */
    val armForSeeded: Boolean,
) {
    /** The levers that need "Modify system settings" before they can work. */
    val wantsSystemSettings: Boolean
        get() = lockRotation || quietTouch

    companion object {
        /**
         * Everything on except [autoArm].
         *
         * The default is deliberately maximal: this is a mode you turn on when
         * you want the phone to get out of the way, and a default that only did
         * half of it would leave everyone to discover the rest. Each one is still
         * one tap from off.
         */
        val DEFAULT = GameProfile(
            silenceKey = true,
            hideIsland = true,
            silenceNotifications = true,
            lockRotation = true,
            quietTouch = true,
            killAnimations = true,
            autoArm = false,
            armFor = emptySet(),
            armForSeeded = false,
        )
    }
}
