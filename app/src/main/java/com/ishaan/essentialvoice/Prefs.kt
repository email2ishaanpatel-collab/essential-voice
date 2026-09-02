package com.ishaan.essentialvoice

import android.content.Context
import android.content.SharedPreferences
import com.ishaan.essentialvoice.game.GameProfile
import com.ishaan.essentialvoice.notes.NotesWidget
import com.ishaan.essentialvoice.voice.PillStyles
import com.ishaan.essentialvoice.volume.VolumeSliderView
import com.ishaan.essentialvoice.whisper.Languages
import com.ishaan.essentialvoice.whisper.ModelCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * An immutable read of every setting.
 *
 * The UI reads *this*, never the store. Reading SharedPreferences straight from
 * a composable looks like it works and does not: a plain getter is not a state
 * read, so nothing recomposes when the value changes and the screen only catches
 * up when the app is reopened. A snapshot published on a StateFlow is a real
 * state read, so a toggle moves the moment it is tapped.
 */
data class Settings(
    val triggerKeyCode: Int,
    val triggerScanCode: Int,
    val consumeKey: Boolean,
    val holdMs: Int,
    val pillX: Float,
    val pillY: Float,
    /** Whether the island is on screen at all. Off until asked for: it is a
     *  permanent overlay, and nothing permanent should arrive uninvited. */
    val island: Boolean,
    val islandX: Float,
    /**
     * The lozenge's geometry, all in dp and all editable.
     *
     * Kept in dp rather than as fractions of the screen because these are things
     * someone sets by eye against the camera, and a number that means the same
     * on any density is the one worth storing.
     */
    val islandTopDp: Int,
    val islandHeightDp: Int,
    val islandWidthDp: Int,
    /**
     * The volume slider: the capsule this app draws when a volume key is
     * pressed, in place of the panel Nothing OS would have drawn.
     *
     * Off until asked for. Turning it on makes this app the thing that decides
     * what the volume buttons do, which is not something to inherit by
     * installing a dictation app — see [com.ishaan.essentialvoice.volume.VolumeSlider].
     */
    val volumeSlider: Boolean,
    /** [Prefs.SIDE_LEFT] or [Prefs.SIDE_RIGHT]: which edge it stands on. */
    val volumeSide: String,
    /**
     * How far down that edge its *centre* sits, 0 at the top and 1 at the
     * bottom. A fraction rather than dp, because unlike the island this is not
     * lined up against anything on the phone — it is put where a thumb is, and
     * a thumb is in the same place on a screen of any height.
     */
    val volumeY: Float,
    val volumeHeightDp: Int,
    /** How thick the slider is drawn. See [Prefs.setVolumeWidthDp]. */
    val volumeWidthDp: Int,
    /**
     * How long it stays after the last press, in milliseconds.
     *
     * A setting because there is no right answer to it: it is the length of the
     * pause between "I have finished pressing" and "I have finished looking",
     * and those are different lengths for different people.
     */
    val volumeLingerMs: Int,
    /** The earbuds the widget and the tile connect. Empty until one is picked;
     *  there is no default and no hardcoded address anywhere in this app. */
    /**
     * The Gemini API key, or empty.
     *
     * Typed on the phone and never leaves it except as the `x-goog-api-key`
     * header on the request it authorises — deliberately a header and not a
     * query parameter, because a URL is the one part of a request that gets
     * written into logs and proxies as a matter of course.
     *
     * Empty is the normal state and is not an error: saying "gemini" without a
     * key set says so and does nothing else. Nothing about dictation depends on
     * this, and no request is ever made unless the word is spoken.
     */
    val geminiKey: String,
    val budsAddress: String,
    val budsName: String,
    val slideFrom: String,
    /**
     * Two knocks on the back of the phone start a dictation, and how hard they
     * have to be (1 firmest, 5 lightest).
     *
     * Off until asked for. It is a detector that runs whenever the screen is on,
     * and nothing that costs power arrives uninvited — the same rule the island
     * follows. See [com.ishaan.essentialvoice.sensor.BackTap].
     */
    val backTap: Boolean,
    val backTapSensitivity: Int,
    /**
     * What each knock gesture does, as a [com.ishaan.essentialvoice.sensor.TapAction]
     * id, plus the package for the one that opens an app.
     *
     * Triple tap defaults to nothing. Both Pixel and iPhone ship a double tap;
     * only iPhone offers a triple, and it is not worth slowing every double down
     * to wait for a third knock nobody has asked for — see the confirmation
     * window in `BackTap`.
     */
    val backTapAction: String,
    val backTapApp: String,
    val backTapTripleAction: String,
    val backTapTripleApp: String,
    /**
     * Whether a *toggled* dictation draws the wide bar over the gesture handle.
     *
     * Only toggled ones: a held key needs no stop control, because letting go is
     * the stop control. See [com.ishaan.essentialvoice.voice.Bar].
     */
    val bottomBar: Boolean,
    /**
     * Two fingers up from the home bar start a dictation.
     *
     * Off until asked for, like the island and the volume slider, and for a
     * sharper reason than either: turning it on puts a strip the height of the
     * navigation bar along the bottom of the screen that takes touches landing
     * in it away from the app underneath. The home gesture is unaffected. See
     * [com.ishaan.essentialvoice.voice.HomeSwipe].
     */
    val homeSwipe: Boolean,
    /** Which [com.ishaan.essentialvoice.voice.PillStyles] entry the pill wears. */
    val pillStyle: String,
    val qualityTier: String,
    /**
     * The language dictation is spoken in, as a whisper language code.
     *
     * "en" is not merely one of a hundred values: it selects the English-only
     * models, which are better at English than the multilingual ones of the
     * same size. See [com.ishaan.essentialvoice.whisper.Languages].
     */
    val language: String,
    /**
     * Which recogniser does the listening: [Prefs.ENGINE_WHISPER] or
     * [Prefs.ENGINE_GOOGLE]. See [com.ishaan.essentialvoice.speech.GoogleSpeech].
     */
    val engine: String,
    /**
     * Whether Google's recogniser may use the network.
     *
     * **On by default**, and this doc used to say the opposite — it still
     * described the original choice long after the default at the read site had
     * been flipped, which is the worst possible place for a stale comment: it is
     * the one setting that decides whether audio can leave the phone.
     *
     * The reasoning for the flip is at the read site. What has to be said here
     * is the consequence: with this on, a language whose pack is not on the
     * phone is recognised *over the network*. That is only ever reachable on
     * [ENGINE_GOOGLE], which [com.ishaan.essentialvoice.Features.GOOGLE_SPEECH]
     * currently forces off — so no build that ships with that flag false can
     * upload anything. Turning the flag on makes this a claim the README has to
     * match.
     */
    val googleOnline: Boolean,
    val idleUnloadSeconds: Int,
    val typeIntoField: Boolean,
    val copyToClipboard: Boolean,
    val haptics: Boolean,
    val updateNotices: Boolean,
    /**
     * Which kinds the home-screen widget carries.
     *
     * Three switches rather than one "show everything", because the widget is a
     * glance and what a glance is *for* differs per person: a home screen with
     * the day's tasks on it is a different thing from one with the notes on it,
     * and the app has no way of guessing which. All three on by default — the
     * widget's whole promise is that it shows what the library shows.
     *
     * They are here, in the one snapshot, rather than read straight out of
     * SharedPreferences by the widget: the switch in the app and the list on the
     * home screen have to agree, and they only can if they read the same value.
     * See [com.ishaan.essentialvoice.notes.NotesWidget].
     */
    val widgetNotes: Boolean,
    val widgetTasks: Boolean,
    val widgetRecordings: Boolean,
    val dismissedWhatsNewFor: Int,
    /** [Prefs.THEME_SYSTEM], [Prefs.THEME_LIGHT] or [Prefs.THEME_DARK]. */
    val theme: String,
    /**
     * Whether game mode is holding the phone right now.
     *
     * State rather than a preference, and it sits here anyway, because three
     * surfaces can turn it on — the switch in the app, the Quick Settings tile,
     * and a game coming to the front — and only one of them is ever on screen.
     * A value every surface reads out of the same snapshot is a value they
     * cannot disagree about; see [com.ishaan.essentialvoice.game.GameMode],
     * where turning game mode on *is* writing this.
     */
    val gameArmed: Boolean,
    /** What game mode is allowed to change while it is armed. */
    val game: GameProfile,
) {
    val hasTrigger: Boolean get() = triggerKeyCode > 0 || triggerScanCode > 0

    /**
     * Whether the island should actually be on screen.
     *
     * Game mode takes it away without touching the setting, so that leaving game
     * mode brings back whatever the user had rather than whatever game mode left
     * behind. The settings screen still reads [island]: the switch has to show
     * the preference, not the current state of the overlay.
     */
    val islandVisible: Boolean get() = island && !(gameArmed && game.hideIsland)
    val tier get() = ModelCatalog.byId(qualityTier)
    /** The chosen language's entry, for anything that wants to show its name. */
    val languageName get() = Languages.nameOf(language)
    val isEnglish get() = Languages.isEnglish(language)
    val pill get() = PillStyles.byId(pillStyle)
}

class Prefs private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("essential_voice", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state

    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _state.value = read() }

    init { sp.registerOnSharedPreferenceChangeListener(listener) }

    private fun read() = Settings(
        triggerKeyCode = sp.getInt(K_KEYCODE, -1),
        triggerScanCode = sp.getInt(K_SCANCODE, -1),
        consumeKey = sp.getBoolean(K_CONSUME, true),
        holdMs = sp.getInt(K_HOLD_MS, 220),
        pillX = sp.getFloat(K_PILL_X, 0.12f),
        pillY = sp.getFloat(K_PILL_Y, 0.55f),
        island = sp.getBoolean(K_ISLAND, false),
        // Centred, and flush with the top, so the lozenge sits behind the
        // camera. It is allowed up there because it is tall enough to reach back
        // down past the status bar, which is the only part of it a finger can
        // touch — see Island.
        //
        // A new key rather than the old island_y: that one meant "top, but never
        // above the status bar", and carrying a value across a change of meaning
        // would strand anyone who had already dragged it.
        islandX = sp.getFloat(K_ISLAND_CX, 0.5f),
        // 4dp down and 30dp tall puts the lozenge behind the camera at the size
        // it was when it looked right. Everything here is adjustable in the
        // settings screen, because "how big should the thing round the camera
        // be" is a question only the person looking at the phone can answer.
        islandTopDp = sp.getInt(K_ISLAND_TOP_DP, 4),
        islandHeightDp = sp.getInt(K_ISLAND_H_DP, 30),
        islandWidthDp = sp.getInt(K_ISLAND_W_DP, 120),
        volumeSlider = sp.getBoolean(K_VOL, false),
        // Left and just below halfway: where a left thumb rests, and clear of
        // the volume keys themselves, which are on the right of this phone —
        // the slider should not come up under the finger pressing for it.
        volumeSide = sp.getString(K_VOL_SIDE, SIDE_LEFT) ?: SIDE_LEFT,
        volumeY = sp.getFloat(K_VOL_Y, 0.55f),
        volumeHeightDp = sp.getInt(K_VOL_H_DP, VolumeSliderView.HEIGHT_DP.toInt()),
        volumeWidthDp = sp.getInt(K_VOL_W_DP, VolumeSliderView.WIDTH_DP.toInt()),
        volumeLingerMs = sp.getInt(K_VOL_LINGER, 1500),
        geminiKey = sp.getString(K_GEMINI_KEY, "") ?: "",
        budsAddress = sp.getString(K_BUDS_ADDR, "") ?: "",
        budsName = sp.getString(K_BUDS_NAME, "") ?: "",
        slideFrom = sp.getString(K_SLIDE_FROM, "auto") ?: "auto",
        backTap = sp.getBoolean(K_BACK_TAP, false),
        // The middle of the scale. Where the right step is depends on the back
        // plate as much as on the phone, so this is a starting point to move
        // from rather than a measured default.
        backTapSensitivity = sp.getInt(K_BACK_TAP_SENS, 3),
        backTapAction = sp.getString(K_BACK_TAP_ACTION, "dictate") ?: "dictate",
        backTapApp = sp.getString(K_BACK_TAP_APP, "") ?: "",
        backTapTripleAction = sp.getString(K_BACK_TAP3_ACTION, "none") ?: "none",
        backTapTripleApp = sp.getString(K_BACK_TAP3_APP, "") ?: "",
        bottomBar = sp.getBoolean(K_BOTTOM_BAR, true),
        homeSwipe = sp.getBoolean(K_HOME_SWIPE, false),
        pillStyle = sp.getString(K_PILL_STYLE, PillStyles.DEFAULT_ID) ?: PillStyles.DEFAULT_ID,
        qualityTier = sp.getString(K_TIER, ModelCatalog.DEFAULT_TIER_ID)
            ?: ModelCatalog.DEFAULT_TIER_ID,
        // Read rather than written when [Features.GOOGLE_SPEECH] is off, so the
        // language somebody picked is still on disk and comes back the day the
        // flag does. whisper only hears English, and it is the only engine in
        // this build.
        language = if (!Features.GOOGLE_SPEECH) Languages.DEFAULT
        else sp.getString(K_LANGUAGE, Languages.DEFAULT) ?: Languages.DEFAULT,
        // Google by default since 2026-09-02. It needs no download, it is far
        // better outside English, and it is only worse than whisper at English
        // on the larger whisper models — which are a several-hundred-megabyte
        // decision nobody has made yet on a fresh install.
        // Read through the same rule the setters keep: whisper is English-only.
        // The setters cannot cover an install that was already on whisper with
        // another language chosen when the multilingual models were removed —
        // nothing writes either preference on an upgrade, so that pair would
        // survive untouched and ask an English-only model for Hindi. Coerced
        // here, where every read passes, and it keeps the language the user
        // picked rather than the engine: they chose to speak Hindi, and Google
        // is now the only thing on the phone that can hear it.
        engine = if (!Features.GOOGLE_SPEECH) ENGINE_WHISPER else {
            (sp.getString(K_ENGINE, ENGINE_GOOGLE) ?: ENGINE_GOOGLE).let { e ->
                val lang = sp.getString(K_LANGUAGE, Languages.DEFAULT) ?: Languages.DEFAULT
                if (e == ENGINE_WHISPER && !Languages.isEnglish(lang)) ENGINE_GOOGLE else e
            }
        },
        // On by default, and that is a deliberate reversal. It used to default
        // off, which forced offline recognition — and forcing offline is what
        // made most of Google's hundred languages fail rather than work. On
        // costs nothing to anyone whose language has a pack on the phone:
        // Android reaches for the local pack first of its own accord, and only
        // goes to the network when there is nothing local to use.
        googleOnline = sp.getBoolean(K_GOOGLE_ONLINE, true),
        idleUnloadSeconds = sp.getInt(K_IDLE_UNLOAD, 300),
        // Both fixed, and no longer settings. Typing the words into the field
        // you were in *is* the app, and it copied them as well anyway — so the
        // two switches offered were "do the thing this app is for" and "keep
        // doing what it was already doing". Read as constants rather than
        // deleted, so nobody's stored `false` from an older build can turn the
        // app off from the inside.
        typeIntoField = true,
        copyToClipboard = true,
        haptics = sp.getBoolean(K_HAPTICS, true),
        updateNotices = sp.getBoolean(K_UPDATE_NOTICES, true),
        widgetNotes = sp.getBoolean(K_W_NOTES, true),
        widgetTasks = sp.getBoolean(K_W_TASKS, true),
        widgetRecordings = sp.getBoolean(K_W_RECORDINGS, true),
        dismissedWhatsNewFor = sp.getInt(K_WHATSNEW_DISMISSED, 0),
        theme = sp.getString(K_THEME, THEME_SYSTEM) ?: THEME_SYSTEM,
        gameArmed = sp.getBoolean(K_GAME_ARMED, false),
        game = readGame(),
    )

    private fun readGame(): GameProfile {
        val d = GameProfile.DEFAULT
        return GameProfile(
            silenceKey = sp.getBoolean(K_G_KEY, d.silenceKey),
            hideIsland = sp.getBoolean(K_G_ISLAND, d.hideIsland),
            silenceNotifications = sp.getBoolean(K_G_DND, d.silenceNotifications),
            lockRotation = sp.getBoolean(K_G_ROTATION, d.lockRotation),
            quietTouch = sp.getBoolean(K_G_TOUCH, d.quietTouch),
            killAnimations = sp.getBoolean(K_G_ANIM, d.killAnimations),
            autoArm = sp.getBoolean(K_G_AUTO, d.autoArm),
            armFor = sp.getStringSet(K_G_ARM_FOR, emptySet())?.toSet() ?: emptySet(),
            armForSeeded = sp.getBoolean(K_G_SEEDED, d.armForSeeded),
        )
    }

    /** The current values, for the services, which are not composing anything. */
    val now: Settings get() = _state.value

    // ---- writes ------------------------------------------------------------

    fun setTrigger(keyCode: Int, scanCode: Int) =
        sp.edit().putInt(K_KEYCODE, keyCode).putInt(K_SCANCODE, scanCode).apply()

    fun setHoldMs(v: Int) = sp.edit().putInt(K_HOLD_MS, v).apply()
    fun setPlacement(x: Float, y: Float) =
        sp.edit().putFloat(K_PILL_X, x).putFloat(K_PILL_Y, y).apply()
    fun setSlideFrom(v: String) = sp.edit().putString(K_SLIDE_FROM, v).apply()
    fun setBackTap(v: Boolean) = sp.edit().putBoolean(K_BACK_TAP, v).apply()
    fun setBackTapSensitivity(v: Int) =
        sp.edit().putInt(K_BACK_TAP_SENS, v.coerceIn(1, 5)).apply()
    fun setHomeSwipe(v: Boolean) = sp.edit().putBoolean(K_HOME_SWIPE, v).apply()
    fun setBackTapAction(v: String) = sp.edit().putString(K_BACK_TAP_ACTION, v).apply()
    fun setBackTapApp(v: String) = sp.edit().putString(K_BACK_TAP_APP, v).apply()
    fun setBackTapTripleAction(v: String) = sp.edit().putString(K_BACK_TAP3_ACTION, v).apply()
    fun setBackTapTripleApp(v: String) = sp.edit().putString(K_BACK_TAP3_APP, v).apply()
    fun setIsland(v: Boolean) = sp.edit().putBoolean(K_ISLAND, v).apply()
    fun setBuds(address: String, name: String) =
        sp.edit().putString(K_BUDS_ADDR, address).putString(K_BUDS_NAME, name).apply()
    /**
     * Where the island sits across the screen, 0..1 of the width.
     *
     * A setting rather than a drag: the island is over every app all day, and a
     * control that can be nudged by a finger aiming at something underneath it
     * does not stay where it was left. Moving it is deliberate, and happens here.
     */
    fun setIslandX(v: Float) =
        sp.edit().putFloat(K_ISLAND_CX, v.coerceIn(0f, 1f)).apply()

    fun setIslandTopDp(v: Int) = sp.edit().putInt(K_ISLAND_TOP_DP, v).apply()
    fun setIslandHeightDp(v: Int) = sp.edit().putInt(K_ISLAND_H_DP, v).apply()
    fun setIslandWidthDp(v: Int) = sp.edit().putInt(K_ISLAND_W_DP, v).apply()
    fun setVolumeSlider(v: Boolean) = sp.edit().putBoolean(K_VOL, v).apply()
    fun setVolumeSide(v: String) = sp.edit().putString(K_VOL_SIDE, v).apply()
    fun setVolumeY(v: Float) = sp.edit().putFloat(K_VOL_Y, v.coerceIn(0f, 1f)).apply()
    fun setVolumeHeightDp(v: Int) = sp.edit().putInt(K_VOL_H_DP, v).apply()

    /**
     * How thick the slider is drawn.
     *
     * Clamped at the door rather than at the slider that sets it, like every
     * other rule in this file: the window is sized from this number, and a
     * window three dp wide is one nobody can get a finger on to fix.
     */
    fun setVolumeWidthDp(v: Int) = sp.edit().putInt(
        K_VOL_W_DP,
        v.coerceIn(
            VolumeSliderView.MIN_WIDTH_DP.toInt(),
            VolumeSliderView.MAX_WIDTH_DP.toInt(),
        ),
    ).apply()
    fun setVolumeLingerMs(v: Int) = sp.edit().putInt(K_VOL_LINGER, v).apply()
    fun setGeminiKey(v: String) = sp.edit().putString(K_GEMINI_KEY, v.trim()).apply()
    fun setPillStyle(v: String) = sp.edit().putString(K_PILL_STYLE, v).apply()
    fun setQualityTier(v: String) = sp.edit().putString(K_TIER, v).apply()

    /**
     * Only ever a code from [Languages]; an unknown one would leave the app
     * asking whisper for a language it has no token for, so it is refused here
     * rather than at the decoder.
     */
    /**
     * The engine, and the language it implies.
     *
     * whisper listens in English and only English — the multilingual models it
     * would need are unusable at the sizes this phone can run, and the reasons
     * are written out in [com.ishaan.essentialvoice.whisper.ModelCatalog]. So
     * the two settings are really one, and they are kept in step here rather
     * than in the screen that draws them: this is the only door either of them
     * comes through, and a rule enforced at the door cannot be walked around by
     * a widget, a shortcut, or the next surface somebody adds.
     *
     * Choosing whisper while set to another language moves the language back to
     * English; choosing another language while on whisper moves the engine to
     * Google. Either way the pair on screen is one that works.
     */
    fun setEngine(v: String) {
        sp.edit().putString(K_ENGINE, v).apply()
        if (v == ENGINE_WHISPER && !Languages.isEnglish(now.language)) {
            setLanguage(Languages.DEFAULT)
        }
    }

    fun setGoogleOnline(v: Boolean) = sp.edit().putBoolean(K_GOOGLE_ONLINE, v).apply()

    fun setLanguage(v: String) {
        if (Languages.all.none { it.code == v }) return
        sp.edit().putString(K_LANGUAGE, v).apply()
        if (!Languages.isEnglish(v) && now.engine == ENGINE_WHISPER) {
            setEngine(ENGINE_GOOGLE)
        }
    }
    fun setHaptics(v: Boolean) = sp.edit().putBoolean(K_HAPTICS, v).apply()
    fun setUpdateNotices(v: Boolean) = sp.edit().putBoolean(K_UPDATE_NOTICES, v).apply()

    /**
     * The widget's three switches.
     *
     * Each one redraws the placed widgets itself rather than leaving that to
     * the screen that flipped it. It is the same rule [NoteStore] follows on
     * every write, and for the same reason: the home screen is a second copy of
     * this list, and a copy that is only refreshed by whoever remembers to is a
     * copy that is wrong.
     */
    fun setWidgetNotes(context: Context, v: Boolean) = setWidgetKind(context, K_W_NOTES, v)

    fun setWidgetTasks(context: Context, v: Boolean) = setWidgetKind(context, K_W_TASKS, v)

    fun setWidgetRecordings(context: Context, v: Boolean) =
        setWidgetKind(context, K_W_RECORDINGS, v)

    private fun setWidgetKind(context: Context, key: String, v: Boolean) {
        // The snapshot re-reads itself off the store's own change listener, so
        // there is nothing to publish here — only the copy on the home screen,
        // which has no way of hearing about it.
        sp.edit().putBoolean(key, v).apply()
        NotesWidget.refresh(context)
    }

    /** Turning game mode on and off. Nothing else writes this; see [GameProfile]. */
    fun setGameArmed(v: Boolean) = sp.edit().putBoolean(K_GAME_ARMED, v).apply()

    /**
     * Every game-mode lever in one write.
     *
     * One setter rather than nine, because the caller always has the whole
     * profile in hand (`settings.game.copy(...)`) and nine setters is
     * nine chances for the UI and the store to name the same thing
     * differently. A single edit also means one round of listeners, not one per
     * switch that happened to change.
     */
    fun setGame(v: GameProfile) = sp.edit()
        .putBoolean(K_G_KEY, v.silenceKey)
        .putBoolean(K_G_ISLAND, v.hideIsland)
        .putBoolean(K_G_DND, v.silenceNotifications)
        .putBoolean(K_G_ROTATION, v.lockRotation)
        .putBoolean(K_G_TOUCH, v.quietTouch)
        .putBoolean(K_G_ANIM, v.killAnimations)
        .putBoolean(K_G_AUTO, v.autoArm)
        .putStringSet(K_G_ARM_FOR, v.armFor)
        .putBoolean(K_G_SEEDED, v.armForSeeded)
        .apply()

    /**
     * What the phone's settings were before game mode changed them.
     *
     * Written with commit(), which is the one place in this app that is worth
     * blocking for: apply() queues the write, and a process killed before it
     * lands loses the only record of the brightness, the timeout and the
     * rotation the phone had. See
     * [com.ishaan.essentialvoice.game.Levers.Snapshot].
     */
    @Suppress("ApplySharedPref")
    var gameSnapshot: String
        get() = sp.getString(K_G_SNAPSHOT, "") ?: ""
        set(v) { sp.edit().putString(K_G_SNAPSHOT, v).commit() }

    /**
     * Whether the session now running was started by the phone rather than by a
     * person. Only an automatic session ends automatically.
     */
    var gameAutoArmed: Boolean
        get() = sp.getBoolean(K_G_AUTO_ARMED, false)
        set(v) = sp.edit().putBoolean(K_G_AUTO_ARMED, v).apply()

    /**
     * Which palette to use. [THEME_SYSTEM] until someone touches the toggle —
     * after that the choice is theirs and the phone stops deciding, because a
     * switch that the system silently overrides at sunset is not a switch.
     */
    fun setTheme(v: String) = sp.edit().putString(K_THEME, v).apply()

    /**
     * Whether the one first-run question has been asked.
     *
     * Asked once, ever, and remembered whichever way it was answered — an
     * invitation that comes back every launch until it is accepted is not an
     * invitation. It is deliberately not the same thing as [Settings.updateNotices]:
     * that is the setting, this is the record of having asked about it.
     */
    var notifyPromptSeen: Boolean
        get() = sp.getBoolean(K_NOTIFY_PROMPT, false)
        set(v) = sp.edit().putBoolean(K_NOTIFY_PROMPT, v).apply()

    // ---- likes -------------------------------------------------------------

    /**
     * A random id for this install, made the first time a heart needs one.
     *
     * The only thing that ever leaves the phone alongside a card's name — see
     * [com.ishaan.essentialvoice.social.Likes]. It is a "have I already liked
     * this" token and nothing else: not an account, not the hardware, not the
     * advertising id, and not derived from anything that identifies the phone or
     * the person holding it. `ANDROID_ID` would have been one line and is
     * exactly the wrong choice — it is stable across an app's whole life on a
     * device and shared with every other app the same developer signs, which
     * turns a like count into a way of recognising somebody.
     *
     * Not part of [Settings]: it is not a setting, and nothing on screen reads
     * it. Cleared with the rest of the store, which is the right behaviour —
     * clearing the app's data should forget which cards you liked.
     */
    val installId: String
        get() = sp.getString(K_INSTALL_ID, null)
            ?: java.util.UUID.randomUUID().toString().also {
                sp.edit().putString(K_INSTALL_ID, it).apply()
            }

    /**
     * The last like counts this install saw, as JSON.
     *
     * So the hearts have numbers under them the instant the launcher draws,
     * instead of every card sitting blank for as long as the request takes — on
     * every open, including the ones with no network at all.
     */
    var likeCache: String
        get() = sp.getString(K_LIKE_CACHE, "") ?: ""
        set(v) = sp.edit().putString(K_LIKE_CACHE, v).apply()

    /**
     * The buds' last known connection state, and whether the direct connect was
     * refused.
     *
     * Not part of [Settings]: neither is a setting. The first is a cache so the
     * widget can paint synchronously — it drifts, which is why nothing acts on
     * it; see [com.ishaan.essentialvoice.buds.Buds]. The second is a record of
     * the platform having refused, which turns the next tap into a trip to
     * Bluetooth settings.
     */
    var budsConnected: Boolean
        get() = sp.getBoolean(K_BUDS_CONNECTED, false)
        set(v) = sp.edit().putBoolean(K_BUDS_CONNECTED, v).apply()

    var budsBlocked: Boolean
        get() = sp.getBoolean(K_BUDS_BLOCKED, false)
        set(v) = sp.edit().putBoolean(K_BUDS_BLOCKED, v).apply()

    /** When the background check last ran, and the version it last mentioned. */
    var lastUpdateCheckAt: Long
        get() = sp.getLong(K_LAST_CHECK, 0L)
        set(v) = sp.edit().putLong(K_LAST_CHECK, v).apply()

    var notifiedVersionCode: Int
        get() = sp.getInt(K_NOTIFIED, 0)
        set(v) = sp.edit().putInt(K_NOTIFIED, v).apply()

    /**
     * The build whose What's new panel has been closed.
     *
     * Held as a version rather than a flag so that closing it means "I have
     * read this one", not "never show me this again" — the next release brings
     * the panel back on its own.
     *
     * A StateFlow read like the rest of Settings, because the panel has to
     * disappear the moment the cross is pressed. See the note on [Settings].
     */
    var dismissedWhatsNewFor: Int
        get() = sp.getInt(K_WHATSNEW_DISMISSED, 0)
        set(v) = sp.edit().putInt(K_WHATSNEW_DISMISSED, v).apply()

    // ---- learn mode --------------------------------------------------------
    //
    // Not part of Settings: it is a transient conversation between the learn
    // screen and the accessibility service, not something the user configures.

    var learnMode: Boolean
        get() = sp.getBoolean(K_LEARN, false)
        set(v) = sp.edit().putBoolean(K_LEARN, v).putLong(K_LEARN_AT, now()).apply()

    /**
     * Learn mode, but only while something is still saying it is alive.
     *
     * This is what the accessibility service asks, and the distinction is the
     * difference between a key that does nothing for a moment and a key that
     * does nothing for ever. Learn mode makes the service swallow the Essential
     * Key and report it instead of dictating; the flag is in [sp] because it is
     * read from the service's process, and anything in [sp] survives the app
     * being killed. So a learn session interrupted at the wrong moment used to
     * leave a phone whose Essential Key was simply dead, with nothing on screen
     * to say why and no way back except opening the app again.
     *
     * [com.ishaan.essentialvoice.ui.LearnKeyScreen] re-stamps the flag while it
     * is on screen, so this reads true for as long as somebody is actually
     * looking at the learn screen and for [LEARN_TTL_MS] after they stop.
     */
    val learnModeLive: Boolean
        get() = learnMode && now() - sp.getLong(K_LEARN_AT, 0L) < LEARN_TTL_MS

    /** Say the learn screen is still up, so [learnModeLive] stays true. */
    fun keepLearnModeAlive() {
        if (learnMode) sp.edit().putLong(K_LEARN_AT, now()).apply()
    }

    private fun now() = System.currentTimeMillis()

    private val _seen = MutableStateFlow(-1 to -1)

    /** (keyCode, scanCode) of the last key seen while learning. */
    val seenKey: StateFlow<Pair<Int, Int>> = _seen

    fun reportKey(keyCode: Int, scanCode: Int) { _seen.value = keyCode to scanCode }

    fun clearSeenKey() { _seen.value = -1 to -1 }

    companion object {
        /**
         * How long after the learn screen last spoke up the service goes on
         * swallowing the key. Two minutes: long enough that a screen the phone
         * has paused for a moment does not lose its place, short enough that the
         * very worst case is a key that is dead for two minutes rather than
         * until somebody thinks to open the app.
         */
        private const val LEARN_TTL_MS = 120_000L

        const val SIDE_LEFT = "left"
        const val SIDE_RIGHT = "right"

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        private const val K_KEYCODE = "trigger_keycode"
        private const val K_SCANCODE = "trigger_scancode"
        private const val K_CONSUME = "consume_key"
        private const val K_HOLD_MS = "hold_ms"
        private const val K_PILL_X = "pill_x"
        private const val K_PILL_Y = "pill_y"
        private const val K_SLIDE_FROM = "slide_from"
        private const val K_BACK_TAP = "back_tap"
        private const val K_BACK_TAP_SENS = "back_tap_sensitivity"
        private const val K_BOTTOM_BAR = "bottom_bar"
        private const val K_HOME_SWIPE = "home_swipe"
        private const val K_BACK_TAP_ACTION = "back_tap_action"
        private const val K_BACK_TAP_APP = "back_tap_app"
        private const val K_BACK_TAP3_ACTION = "back_tap_triple_action"
        private const val K_BACK_TAP3_APP = "back_tap_triple_app"
        private const val K_ISLAND = "island"
        private const val K_ISLAND_CX = "island_cx"
        private const val K_ISLAND_TOP_DP = "island_top_dp"
        private const val K_ISLAND_H_DP = "island_h_dp"
        private const val K_ISLAND_W_DP = "island_w_dp"
        private const val K_VOL = "volume_slider"
        private const val K_VOL_SIDE = "volume_side"
        private const val K_VOL_Y = "volume_y"
        private const val K_VOL_H_DP = "volume_h_dp"
        private const val K_VOL_W_DP = "volume_w_dp"
        private const val K_VOL_LINGER = "volume_linger_ms"
        private const val K_GEMINI_KEY = "gemini_key"
        private const val K_BUDS_ADDR = "buds_address"
        private const val K_BUDS_NAME = "buds_name"
        private const val K_BUDS_CONNECTED = "buds_connected"
        private const val K_BUDS_BLOCKED = "buds_blocked"
        private const val K_PILL_STYLE = "pill_style"
        private const val K_TIER = "quality_tier"
        private const val K_LANGUAGE = "spoken_language"
        private const val K_ENGINE = "engine"
        private const val K_GOOGLE_ONLINE = "google_online"

        const val ENGINE_WHISPER = "whisper"
        const val ENGINE_GOOGLE = "google"
        private const val K_IDLE_UNLOAD = "idle_unload_s"
        private const val K_HAPTICS = "haptics"
        private const val K_UPDATE_NOTICES = "update_notices"
        private const val K_W_NOTES = "widget_notes"
        private const val K_W_TASKS = "widget_tasks"
        private const val K_W_RECORDINGS = "widget_recordings"
        private const val K_LAST_CHECK = "last_update_check"
        private const val K_NOTIFIED = "notified_version"
        private const val K_WHATSNEW_DISMISSED = "whatsnew_dismissed"
        private const val K_NOTIFY_PROMPT = "notify_prompt_seen"
        private const val K_INSTALL_ID = "install_id"
        private const val K_LIKE_CACHE = "like_cache"
        private const val K_LEARN = "learn_mode"
        private const val K_LEARN_AT = "learn_mode_at"
        private const val K_THEME = "theme"
        private const val K_GAME_ARMED = "game_armed"
        private const val K_G_KEY = "game_silence_key"
        private const val K_G_ISLAND = "game_hide_island"
        private const val K_G_DND = "game_silence_notifications"
        private const val K_G_ROTATION = "game_lock_rotation"
        private const val K_G_TOUCH = "game_quiet_touch"
        private const val K_G_ANIM = "game_kill_animations"
        private const val K_G_AUTO = "game_auto_arm"
        private const val K_G_ARM_FOR = "game_arm_for"
        private const val K_G_SEEDED = "game_arm_for_seeded"
        private const val K_G_SNAPSHOT = "game_snapshot"
        private const val K_G_AUTO_ARMED = "game_auto_armed"

        @Volatile private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context).also { instance = it }
            }
    }
}
