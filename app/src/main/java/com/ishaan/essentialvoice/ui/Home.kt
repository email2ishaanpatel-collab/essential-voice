package com.ishaan.essentialvoice.ui

import androidx.activity.compose.BackHandler
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import com.ishaan.essentialvoice.buds.Buds
import com.ishaan.essentialvoice.social.Likes
import com.ishaan.essentialvoice.buds.BudsTile
import com.ishaan.essentialvoice.buds.BudsWidget
import com.ishaan.essentialvoice.game.GameApps
import com.ishaan.essentialvoice.game.GameMode
import com.ishaan.essentialvoice.game.GameProfile
import com.ishaan.essentialvoice.game.GameTile
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ishaan.essentialvoice.PlacementActivity
import com.ishaan.essentialvoice.R
import com.ishaan.essentialvoice.Prefs
import com.ishaan.essentialvoice.Settings
import com.ishaan.essentialvoice.Setup
import com.ishaan.essentialvoice.SetupState
import com.ishaan.essentialvoice.Updater
import com.ishaan.essentialvoice.Features
import com.ishaan.essentialvoice.WhatsNew
import com.ishaan.essentialvoice.notes.NotesWidget
import com.ishaan.essentialvoice.notes.NoteStore
import com.ishaan.essentialvoice.notes.Playback
import com.ishaan.essentialvoice.notes.Transcribe
import com.ishaan.essentialvoice.notes.openNote
import androidx.compose.ui.text.style.TextDecoration
import com.ishaan.essentialvoice.sensor.BackTap
import com.ishaan.essentialvoice.sensor.TapAction
import com.ishaan.essentialvoice.voice.Dictation
import com.ishaan.essentialvoice.voice.PillStyles
import com.ishaan.essentialvoice.speech.GoogleSpeech
import com.ishaan.essentialvoice.whisper.Languages
import com.ishaan.essentialvoice.whisper.ModelCatalog
import com.ishaan.essentialvoice.whisper.WhisperEngine
import com.ishaan.essentialvoice.whisper.ModelDownloader
import com.ishaan.essentialvoice.whisper.QualityTier
import kotlin.math.roundToInt

/** The two things the app is: its settings, and everything it kept. */
private enum class Tab { Settings, Library }

/**
 * The app, in two tabs behind a floating bar.
 *
 * Notes used to be a section three quarters of the way down the settings page,
 * which is a strange place to keep the one part of the app that holds your
 * words. It is a destination now, and the bar floats over the page rather than
 * sitting under it so the page still runs to the bottom of the screen.
 */
@Composable
fun HomeScreen(
    setup: SetupState,
    settings: Settings,
    prefs: Prefs,
    download: ModelDownloader.State,
    update: Updater.State,
    onRequestMic: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestBluetooth: () -> Unit,
    onCheckUpdate: () -> Unit,
    onGetUpdate: (Updater.Release) -> Unit,
    onLearnKey: () -> Unit,
    onDownload: (QualityTier) -> Unit,
    onDeleteModel: (QualityTier) -> Unit,
    onCancelDownload: () -> Unit,
) {
    // A pager rather than a when-block, so the two pages can be swiped between
    // as well as tapped between. The horizontal scrollers inside a page (the
    // model cards, What's new) get the gesture first and only hand it on at
    // their own ends, which is the behaviour anyone who has used a phone
    // expects without being told.
    val pages = Tab.entries
    val pager = androidx.compose.foundation.pager.rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val tab = pages[pager.currentPage]

    // The page is recorded into a layer as it draws, so the bar can draw the
    // strip of it that sits behind itself, blurred. Android has no "blur what
    // is behind this view" for an ordinary view — the platform API blurs whole
    // windows — so the only honest way to do it is to keep a copy of what was
    // drawn and blur that.
    val backdrop = androidx.compose.ui.graphics.rememberGraphicsLayer()

    // What an InfoDot has asked to show, and where the page box starts in the
    // window — the dots report their position in window coordinates.
    var info by remember { mutableStateOf<InfoRequest?>(null) }
    var hostTop by remember { mutableStateOf(0f) }

    // The one thing the app asks for on the way in. Decided once, on the first
    // composition, so that answering it does not make it flicker back: the
    // permission dialog it opens pauses and resumes this screen.
    val context = LocalContext.current
    var askNotices by remember {
        mutableStateOf(!prefs.notifyPromptSeen && !Setup.hasNotificationPermission(context))
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(EV.Background)
            .onGloballyPositioned { hostTop = it.positionInRoot().y },
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalInfoOpener provides { text, y -> info = InfoRequest(text, y) },
        ) {
        // Notes are made by dictating them, so an app that cannot dictate yet has
        // an empty second tab and no way to fill it. Until then there is one
        // page and no bar over it — the bar would be offering a door into a
        // room with nothing in it, and taking 26dp off the setup steps to do it.
        androidx.compose.foundation.pager.HorizontalPager(
            state = pager,
            userScrollEnabled = setup.ready,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    backdrop.record { this@drawWithContent.drawContent() }
                    drawLayer(backdrop)
                },
            // Both pages stay composed: the settings page holds scroll
            // position and a dozen bits of open/closed state, and losing all
            // of it to a swipe across to the library and back is worse than the
            // memory it costs to keep.
            beyondViewportPageCount = 1,
        ) { page ->
            when (pages[page]) {
                Tab.Settings -> SettingsTab(
                    setup = setup,
                    settings = settings,
                    prefs = prefs,
                    download = download,
                    update = update,
                    onRequestMic = onRequestMic,
                    onRequestNotifications = onRequestNotifications,
                    onRequestBluetooth = onRequestBluetooth,
                    onCheckUpdate = onCheckUpdate,
                    onGetUpdate = onGetUpdate,
                    onLearnKey = onLearnKey,
                    onDownload = onDownload,
                    onDeleteModel = onDeleteModel,
                    onCancelDownload = onCancelDownload,
                )
                Tab.Library -> LibraryTab(setup, settings, prefs)
            }
        }
        }

        // The bar is composed before the two things that cover the page, so
        // that they cover it too. Composed after them it drew on top of their
        // scrims and kept taking taps — the tabs stayed live behind a modal,
        // and switching page under an open dialog is not something a dialog
        // should allow.
        if (setup.ready) {
            FloatingNav(pager, backdrop, Modifier.align(Alignment.BottomCenter)) { next ->
                scope.launch { pager.animateScrollToPage(pages.indexOf(next)) }
            }
        } else if (pager.currentPage != 0) {
            // Setup can finish, or come undone, while the app is open. If the
            // bar goes while the library is showing, the page has to come
            // back with it.
            LaunchedEffect(Unit) { pager.scrollToPage(0) }
        }

        info?.let { open ->
            InfoPanel(open, hostTop, backdrop) { info = null }
        }

        if (askNotices) {
            NoticesInvite(
                backdrop = backdrop,
                onAllow = {
                    prefs.notifyPromptSeen = true
                    prefs.setUpdateNotices(true)
                    askNotices = false
                    onRequestNotifications()
                },
                onDismiss = {
                    prefs.notifyPromptSeen = true
                    askNotices = false
                },
            )
        }
    }
}

/**
 * The page, in the shape the drawings gave it.
 *
 * What used to be here was one column eleven sections long: everything the app
 * can do, spelled out, in the order it was written. It read like a manual. The
 * redesign turns it into a launcher — a short stack of cards, each with a
 * picture on it — and moves the settings themselves one tap behind whichever
 * card they belong to.
 *
 * Nothing was dropped in the move. Every section below is the same code it was,
 * lifted out of the column and given a page of its own, which is why they still
 * carry their own [SectionLabel]s: a detail page has no title bar, by the
 * drawings' choice, so the label inside it is the heading.
 */
private enum class Page {
    SetUp, Game, Earbuds, Volume, Island,
}

/**
 * Everything the sections need, in one bag.
 *
 * The alternative is fourteen parameters threaded through ten composables that
 * mostly want two of them. This is passed whole and unpacked at the top of each
 * section, so a section's first lines say exactly what it touches.
 */
private class Panels(
    val setup: SetupState,
    val settings: Settings,
    val prefs: Prefs,
    val download: ModelDownloader.State,
    val update: Updater.State,
    val onRequestMic: () -> Unit,
    val onRequestNotifications: () -> Unit,
    val onRequestBluetooth: () -> Unit,
    val onCheckUpdate: () -> Unit,
    val onGetUpdate: (Updater.Release) -> Unit,
    val onLearnKey: () -> Unit,
    val onDownload: (QualityTier) -> Unit,
    val onDeleteModel: (QualityTier) -> Unit,
    val onCancelDownload: () -> Unit,
)

@Composable
private fun SettingsTab(
    setup: SetupState,
    settings: Settings,
    prefs: Prefs,
    download: ModelDownloader.State,
    update: Updater.State,
    onRequestMic: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestBluetooth: () -> Unit,
    onCheckUpdate: () -> Unit,
    onGetUpdate: (Updater.Release) -> Unit,
    onLearnKey: () -> Unit,
    onDownload: (QualityTier) -> Unit,
    onDeleteModel: (QualityTier) -> Unit,
    onCancelDownload: () -> Unit,
) {
    val panels = Panels(
        setup, settings, prefs, download, update, onRequestMic, onRequestNotifications,
        onRequestBluetooth, onCheckUpdate, onGetUpdate, onLearnKey, onDownload,
        onDeleteModel, onCancelDownload,
    )

    // Saved by name rather than held as the enum, so the open page survives a
    // rotation and a process death the same way the scroll position does.
    var open by rememberSaveable { mutableStateOf<String?>(null) }

    // Both hoisted out of the launcher, because the launcher is thrown away
    // while a detail page is up: held inside it, the scroll position would
    // reset to the top every time you came back, and the entrance stagger
    // would play again over a page you were already half way down.
    val scroll = rememberScrollState()
    var introduced by rememberSaveable { mutableStateOf(false) }
    val page = open?.let { name -> Page.entries.firstOrNull { it.name == name } }

    // The likes. Read once per visit to this tab rather than kept in step with
    // a subscription: the numbers are a rough sense of what people cared about,
    // not something anybody watches tick, and a live socket held open behind a
    // settings screen would cost more than the fact is worth.
    //
    // Provided around the AnimatedContent rather than inside the launcher, so
    // that opening a card and coming back does not fetch the board again — the
    // launcher is thrown away while a detail page is up, the same reason the
    // scroll position is hoisted out of it.
    val board by Likes.board.collectAsState()
    val likeScope = rememberCoroutineScope()
    if (Features.LIKES && Likes.configured) {
        LaunchedEffect(Unit) { Likes.refresh(prefs) }
    }
    val likes = if (Features.LIKES && Likes.configured) {
        LikeBoard(
            cards = board.cards.mapValues { (_, c) -> c.likes to c.liked },
            known = board.known,
            onToggle = { card -> Likes.toggle(likeScope, prefs, card) },
        )
    } else {
        null
    }

    // Only while this screen is the one on top. The learn screen is drawn over
    // it and registers its own handler, which wins by being composed later —
    // but a handler that is merely losing is still a handler, and the ordering
    // is not something to leave a dead Essential Key resting on.
    BackHandler(enabled = page != null && !prefs.learnMode) { open = null }

    // Sideways, and the back gesture runs the tap backwards.
    //
    // Opening a card: the page comes in from the right and the launcher leaves
    // to the left, so the two move together as one sheet of paper sliding
    // across. Back does exactly the reverse — the page returns to the right it
    // came from and the launcher comes back from the left — which is what makes
    // the pair read as one place you can move in and out of rather than as two
    // animations that happen to be opposites.
    //
    // Full width, not a fraction of it: a page that only travels part of the
    // way is a page that was somewhere else all along.
    androidx.compose.runtime.CompositionLocalProvider(LocalLikes provides likes) {
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val spec = tween<IntOffset>(320, easing = FastOutSlowInEasing)
            if (targetState != null) {
                slideInHorizontally(spec) { it } togetherWith
                    slideOutHorizontally(spec) { -it }
            } else {
                slideInHorizontally(spec) { -it } togetherWith
                    slideOutHorizontally(spec) { it }
            }.using(SizeTransform(clip = false))
        },
        label = "page",
    ) { target ->
        if (target == null) {
            Launcher(panels, scroll, intro = !introduced, onIntroduced = { introduced = true }) {
                open = it.name
            }
        } else {
            Detail(target, panels) { open = null }
        }
    }
    }
}

/**
 * The launcher: the app's name, the build it is running, and then one card per
 * thing the app does.
 *
 * Every entry is the same card. An earlier pass had tall cards for four things
 * and one-line rows for six, which sorted the app into headline features and
 * leftovers; identical cards make a longer page and a fairer one.
 *
 * There is one card per *subject*, not one per settings screen. The pill, the
 * other ways to start a dictation and the behaviour settings were three cards
 * of their own and are now all inside Essential voice, because none of them is
 * a thing the app does — they are how the one thing it does behaves.
 *
 * Order is what you are most likely to have opened the app for: whether it is
 * set up, then the four things it puts on the screen, then the parts you tune
 * once, and last the three cards that ask you for something rather than
 * offering you something.
 *
 * Updates are the exception, and they are the pill at the top rather than a
 * card — the only row on the page about the app itself rather than about
 * something the app does.
 */
@Composable
private fun Launcher(
    p: Panels,
    scroll: ScrollState,
    intro: Boolean,
    onIntroduced: () -> Unit,
    onOpen: (Page) -> Unit,
) {
    val context = LocalContext.current
    val type = LocalEvType.current
    val version = remember { Updater.installedVersionName(context) }
    val build = remember { Updater.installedVersionCode(context) }
    LaunchedEffect(Unit) { onIntroduced() }

    // Cards come and go with the feature flags, so nothing on the page can
    // count on a fixed position. Each card takes the next number as it is
    // written, and that one number is both the [01] in its corner and its place
    // in the entrance queue — which is why switching game mode off shortens the
    // list rather than leaving a hole in it.
    var slot = 0

    Column(
        Modifier
            .fillMaxSize()
            .background(EV.Background)
            .verticalScroll(scroll)
            .padding(horizontal = EV.PageGutter)
            // The floating bar sits over the page, so the page has to end above
            // it rather than behind it — and when there is no bar there is
            // nothing to end above.
            .padding(bottom = if (p.setup.ready) 128.dp else 40.dp),
    ) {
        Masthead(p.setup, p.settings, p.prefs)
        Spacer(Modifier.height(72.dp))

        // ---- the three rows above the cards --------------------------------
        //
        // Not cards, and deliberately: they are about the app itself rather than
        // about anything it does. Two of them open in place rather than going
        // anywhere, because what they hold is a paragraph to read, not a screen
        // to work in — and a paragraph behind a tap is a paragraph nobody reads.
        //
        // The plea is first. It is the only red thing on the page and the only
        // one of the three that is asking rather than offering, and it is time
        // limited, so it goes away the moment the app is in the store.
        var helpOpen by rememberSaveable { mutableStateOf(false) }

        DisclosurePill(
            label = "I need your help",
            open = helpOpen,
            onToggle = { helpOpen = !helpOpen },
            // Slot zero: the rows lead the entrance, and the cards follow them
            // from one.
            modifier = Modifier.rises(slot, intro),
            fill = EV.DangerFill,
            fillSunk = EV.DangerFillSunk,
            ink = EV.Red,
        ) {
            PlayStoreBody()
        }
        Spacer(Modifier.height(10.dp))

        UpdatesCard(p, version, build, Modifier.rises(slot, intro))

        // The one big gap on the page. Everything under it is a card, twelve
        // apart, and the gap is what says these two are not.
        Spacer(Modifier.height(72.dp))

        HeroCard(
            index = ++slot,
            title = "Essential voice",
            body = if (p.setup.ready) {
                "Hold the key and speak. The pill, the ways in, the rest."
            } else {
                "Three permissions, one key to teach it, and a model."
            },
            cta = if (p.setup.ready) "Open" else "Set up",
            intro = intro,
            likeKey = Page.SetUp.name,
            onClick = { onOpen(Page.SetUp) },
        ) { VoiceMark() }
        CardGap()

        HeroCard(
            index = ++slot,
            title = "Widgets & tiles",
            body = "Your earbuds one tap away, from the home screen or a swipe.",
            cta = "Check it out",
            intro = intro,
            likeKey = Page.Earbuds.name,
            onClick = { onOpen(Page.Earbuds) },
        ) { EarbudsArt() }
        CardGap()

        if (Features.VOLUME_SLIDER) {
            HeroCard(
                index = ++slot,
                title = "Volume ctrl",
                body = "This app draws the volume instead of the phone.",
                cta = "Set up",
                intro = intro,
                likeKey = Page.Volume.name,
                onClick = { onOpen(Page.Volume) },
            ) { VolumeArt() }
            CardGap()
        }

        HeroCard(
            index = ++slot,
            title = "Essential island",
            body = "A lozenge at the camera. Tap to start, tap again to send.",
            cta = if (Features.ISLAND) "Set up" else "Coming soon",
            intro = intro,
            // The one yellow button on the page, and it is on the one card you
            // cannot open. That is the drawings' idea and it is a good one: the
            // loud colour marks the thing that is not ready rather than
            // competing with ten buttons that are.
            kind = if (Features.ISLAND) CardCta.Dark else CardCta.Accent,
            enabled = Features.ISLAND,
            likeKey = Page.Island.name,
            onClick = { onOpen(Page.Island) },
        ) { IslandArt() }
        CardGap()

        if (Features.GAME_MODE) {
            HeroCard(
                index = ++slot,
                title = "Game mode",
                body = "Keeps the key quiet while you play, and holds back pop-ups.",
                cta = "Set up",
                intro = intro,
                likeKey = Page.Game.name,
                onClick = { onOpen(Page.Game) },
            ) {
                ArtTile(EV.Cta, caption = "Game", ink = EV.OnCta) {
                    Image(
                        painter = painterResource(R.drawable.ic_game),
                        contentDescription = null,
                        modifier = Modifier.size(42.dp),
                        colorFilter = ColorFilter.tint(EV.OnCta),
                    )
                }
            }
            CardGap()
        }

        // ---- one switch, on the way out ------------------------------------
        //
        // The only setting on the launcher, and it earns the place: it is about
        // the app rather than about dictating, which is what every card above it
        // is about, and it belongs beside the two rows at the top that are also
        // about the app. Down here rather than up there because it is a thing
        // you set once and never look at again.
        Spacer(Modifier.height(30.dp))
        Panel {
            SettingRow(
                title = "Notify me about new versions",
                sub = "Checks once a day and tells you once per release. Nothing is " +
                    "sent \u2014 it only reads a small file.",
            ) {
                EvSwitch(p.settings.updateNotices) { on ->
                    p.prefs.setUpdateNotices(on)
                    if (on && !Setup.hasNotificationPermission(context)) {
                        p.onRequestNotifications()
                    }
                }
            }
        }

        // ---- the two that were never screens -------------------------------
        //
        // A card is a door, and behind each of these there is one panel with one
        // control in it. They were cards for a while and it made you tap twice
        // to reach a text box. They read the way they did before the redesign
        // instead: a heading and the box, at the foot of the page, where the
        // asking belongs.
        SectionLabel("Ideas")
        FeedbackPanel()

        SectionLabel("Support me")
        SupportPanel()

        SectionLabel("Spread the word")
        SpreadPanel()

        // The line about whisper.cpp that used to close this page is at the
        // foot of Essential voice now — see [SetUpSection]. It is a footnote to
        // the recognition model, and it was sitting under a share sheet three
        // sections away from the only screen that mentions models at all.
    }
}

/** Twelve dp: the gap the drawings leave between one card and the next. */
@Composable
private fun CardGap() = Spacer(Modifier.height(12.dp))

/**
 * The picture a page is known by.
 *
 * Every page gets one, and no two share a word — the tile is the only thing
 * carrying the page's identity, since there is no title bar to do it.
 */
@Composable
private fun PageArt(page: Page) {
    when (page) {
        Page.SetUp -> VoiceMark()
        Page.Earbuds -> EarbudsArt()
        Page.Volume -> VolumeArt()
        Page.Island -> IslandArt()
        Page.Game -> ArtTile(EV.Cta, caption = "Game", ink = EV.OnCta) {
            Image(
                painter = painterResource(R.drawable.ic_game),
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                colorFilter = ColorFilter.tint(EV.OnCta),
            )
        }
    }
}

@Composable
private fun Detail(page: Page, p: Panels, onBack: () -> Unit) {
    DetailPage(onBack = onBack, art = { PageArt(page) }) {
        when (page) {
            Page.SetUp -> SetUpSection(p)
            Page.Game -> GameSection(p)
            Page.Earbuds -> EarbudsSection(p)
            Page.Volume -> VolumeSection(p)
            Page.Island -> IslandSection(p)
        }
    }
}

@Composable
private fun SetUpSection(p: Panels) {
    val context = LocalContext.current
    val type = LocalEvType.current
    val setup = p.setup
    val settings = p.settings
    val prefs = p.prefs
    val download = p.download
    val onRequestMic = p.onRequestMic
    val onLearnKey = p.onLearnKey
    val onDownload = p.onDownload
    val onDeleteModel = p.onDeleteModel
    val onCancelDownload = p.onCancelDownload
    Column(Modifier.fillMaxWidth()) {
        // ---- setup ---------------------------------------------------------
        //
        // Everything needed before the app can do anything, in the order it has
        // to happen: the three permissions, the key it listens for, and the
        // model it listens with. Teaching the key and picking a model used to
        // be sections of their own further down, which meant a finished-looking
        // setup section above two more things that were also setup.
        SectionLabel("Set up")
        Panel {
            // One card, not two. The three permissions used to sit in a card of
            // their own above this one, which read as a finished thing followed
            // by a second, separate thing — when teaching it the key and picking
            // a model are the fourth and fifth steps of the same job.
            //
            // They are also the only three that go away. Once all three are
            // granted there is nothing to do on them and nothing to check, so
            // showing them for ever would be showing a completed form.
            if (!setup.ready) {
                PermissionRow(
                    "Accessibility service",
                    "Sees the Essential Key and puts text where you want it.",
                    setup.accessibility,
                ) { Setup.openAccessibilitySettings(context) }
                Hairline()
                PermissionRow(
                    "Draw over other apps",
                    "So the pill can appear on top of whatever you are in.",
                    setup.overlay,
                ) { Setup.openOverlaySettings(context) }
                Hairline()
                PermissionRow(
                    "Microphone",
                    "Recording never leaves the phone.",
                    setup.microphone,
                    onFix = onRequestMic,
                )
                Hairline()
            }

            SettingRow(
                title = if (setup.keyLearned) "Essential Key" else "Teach it the key",
                sub = if (setup.keyLearned) {
                    "Learned. Use it anywhere."
                } else {
                    "Press your Essential Key once so the app knows which key it is."
                },
                enabled = setup.accessibility,
                onClick = onLearnKey,
            ) {
                if (setup.keyLearned) {
                    EvText(
                        if (settings.triggerKeyCode > 0) "KEY ${settings.triggerKeyCode}"
                        else "SCAN ${settings.triggerScanCode}",
                        type.mono,
                        color = EV.Ink,
                    )
                } else {
                    EvText("SET", type.button, color = EV.Ink)
                }
            }

            // The ways in that are not the key live on the learn-key screen
            // now — behind the same question, one step closer to the moment
            // somebody works out they have no key to press. See LearnKeyScreen.
            Hairline()

            // Both off for 3.0, and switched off rather than deleted so that
            // turning them back on is a `true` and a rebuild. See
            // [Features.GOOGLE_SPEECH] for why, and for what [Prefs] does about
            // the installs that had already chosen Google.
            if (Features.GOOGLE_SPEECH) {
                LanguageRow(settings, prefs)
                Hairline()
                EngineRow(settings, prefs)
            }

            // Whisper's models, and only whisper's. Google downloads nothing
            // and the tier does not reach it, so on that engine this was a
            // carousel of four choices that changed nothing.
            if (settings.engine != Prefs.ENGINE_GOOGLE) {
            // Inside the setup card rather than loose on the page under a
            // heading of its own. Picking a model is the last step of setting
            // the app up, and floating it outside the card made it look like a
            // separate subject that happened to follow one.
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EvText("RECOGNITION MODEL", type.labelSans, Modifier.weight(1f), color = EV.Ink)
                InfoDot(
                    "Bigger models hear more of what you actually said, and cost a " +
                        "one-off download plus a longer wait afterwards. Every timing " +
                        "below was measured on this phone. Everything runs on it too.",
                )
            }
            Spacer(Modifier.height(12.dp))

            // Sideways, same as What's new: four cards stacked took most of a
            // screen to say one thing. IntrinsicSize.Max keeps them level,
            // which a lazy row could not do. The row runs to the card's own
            // edges, with the margin put back inside the scroller so the first
            // card still lines up with the heading above it.
            // Which files are on disk is a file read, and a file read is not a
            // state read: without this, deleting a model changed nothing on
            // screen and the card went on offering Delete for a model that was
            // already gone. Downloading never showed it, because the progress
            // flow was recomposing the card anyway.
            val modelRevision by ModelDownloader.installed.collectAsState()
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .height(IntrinsicSize.Max)
                    .padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ModelCatalog.tiers.forEach { tier ->
                    TierCard(
                        modifier = Modifier.width(260.dp).fillMaxHeight(),
                        fill = EV.Background,
                        tier = tier,
                        selected = settings.qualityTier == tier.id,
                        installed = remember(tier.id, modelRevision) {
                            tier.isInstalled(context)
                        },
                        download = download,
                        onSelect = {
                            prefs.setQualityTier(tier.id)
                            Dictation.onTierChanged()
                        },
                        onDownload = { onDownload(tier) },
                        onDelete = { onDeleteModel(tier) },
                        onCancel = onCancelDownload,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp)) {
                key(modelRevision) { StorageLine(context) }
            }
            }
        }

        // ---- and everything else about dictating -----------------------------
        //
        // The pill, the other ways in, and the behaviour settings were three
        // cards of their own until now, which split one subject four ways: all
        // three are about the same thing this page is about, and none of them
        // is a place you would go without having come here first. They keep
        // their own section labels, so the page still reads as four parts —
        // set it up, what you see, how else to start it, how it acts.
        PillSection(p)
        BehaviourSection(p)

        // The last word on the page, and the right page for it: it is the small
        // print under the model carousel above, not a footer for the launcher.
        Spacer(Modifier.height(20.dp))
        EvText(
            "Speech is transcribed by whisper.cpp on this phone. Nothing is uploaded, " +
                "no recording is kept, and the only thing the app ever downloads is the " +
                "model you pick.",
            type.heroBody,
            Modifier.padding(horizontal = 4.dp),
            color = EV.InkFaint,
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun PillSection(p: Panels) {
    val context = LocalContext.current
    val type = LocalEvType.current
    val settings = p.settings
    val prefs = p.prefs
    Column(Modifier.fillMaxWidth()) {
        // ---- the pill -------------------------------------------------------
        //
        // Where it goes and what colour it is were two sections, on the grounds
        // that they are two different questions. They are, and they are two
        // questions about the same object — which is what a person looking for
        // either of them is actually looking for.
        SectionLabel("Customize")
        Panel(padding = PaddingValues(bottom = 4.dp)) {
            PlacementPreview(settings)
            SettingRow(
                title = "Place it yourself",
                sub = "Drag it anywhere. It snaps to either edge and to the centre.",
                onClick = {
                    context.startActivity(
                        Intent(context, PlacementActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            ) { EvText("OPEN", type.button, color = EV.Ink) }
            Hairline()
            Column(Modifier.padding(18.dp)) {
                EvText("Slides in from", type.body)
                Spacer(Modifier.height(4.dp))
                EvText("Which edge the intro and outro travel through.", type.sub)
                Spacer(Modifier.height(12.dp))
                EvSegmented(
                    options = listOf(
                        "auto" to "Nearest",
                        "left" to "Left",
                        "right" to "Right",
                    ),
                    selectedId = settings.slideFrom,
                ) { prefs.setSlideFrom(it) }
            }

            Hairline()
            Column(Modifier.padding(18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    EvText(settings.pill.label, type.body, Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    InfoDot(
                        "The dots and the note card's text follow the fill, so every " +
                            "colour here comes with the one shade that stays readable " +
                            "on it. Glass has no colour of its own \u2014 it blurs " +
                            "whatever is behind the pill, which the phone switches off " +
                            "under battery saver.",
                    )
                }
                Spacer(Modifier.height(14.dp))
                PillSwatches(settings.pillStyle) { prefs.setPillStyle(it) }
            }
        }
    }
}

@Composable
private fun BehaviourSection(p: Panels) {
    val context = LocalContext.current
    val type = LocalEvType.current
    val setup = p.setup
    val settings = p.settings
    val prefs = p.prefs
    val onRequestNotifications = p.onRequestNotifications
    Column(Modifier.fillMaxWidth()) {
        // ---- behaviour -----------------------------------------------------
        //
        // The key's own settings live here now. "How the key works" was a
        // paragraph explaining a control that explains itself, and the two
        // settings under it were behaviour, which is what this section is.
        //
        // The island and the volume slider used to be here too, and are not any
        // more: they are the two settings that change what the *screen* looks
        // like, which makes them things you go looking for rather than things
        // you come across, and each has its own card now. This section itself
        // is no longer a card — it is the last part of Essential voice.
        //
        // "Hold or tap" is gone entirely. Tap could never work on this key: the
        // system acts on a tap before this app is asked about it, so the mode
        // offered a choice where there was only ever one answer.
        SectionLabel("Behaviour")
        Panel {
            StepperRow(
                title = "Hold before it listens",
                sub = "A shorter press does nothing at all.",
                value = settings.holdMs,
                suffix = "ms",
                step = 40,
                range = 100..600,
            ) { prefs.setHoldMs(it) }
            if (Features.GEMINI) {
                Hairline()
                GeminiRow(settings, prefs)
            }
            Hairline()
            // Instructions, not an explanation. The paragraph that used to be
            // behind an InfoDot here was three sentences on why an app cannot
            // intercept this key — true, and no use at all to somebody who just
            // wants Essential Space to stop opening. Where the switch is, is.
            Column(Modifier.padding(18.dp)) {
                EvText("Stop Essential Space opening", type.body)
                Spacer(Modifier.height(4.dp))
                EvText(
                    "It is switched off inside Essential Space, not in here — no app " +
                        "can take this key from it.",
                    type.sub,
                )
                Spacer(Modifier.height(10.dp))
                EvText(
                    "1.  Open Essential Space.\n" +
                        "2.  Go into its settings.\n" +
                        "3.  Open Essential Key.\n" +
                        "4.  Turn both toggles on.",
                    type.mono,
                )
                Spacer(Modifier.height(14.dp))
                EvButton("Open Essential Space", kind = EvButtonKind.Quiet) {
                    Setup.openEssentialSpace(context)
                }
            }
            Hairline()
            SettingRow(
                title = "Haptics",
                sub = "A tick when it starts listening and when the text lands.",
            ) {
                EvSwitch(settings.haptics) { on ->
                    prefs.setHaptics(on)
                    // Switching it on should be something you feel, not something
                    // you have to go and test.
                    if (on) Dictation.buzz(context, 22)
                }
            }
        }
    }
}

/**
 * The lozenge at the camera cutout.
 *
 * Its own page since the redesign, where it was four steppers near the top of
 * Behaviour. A setting that changes the shape of the screen is worth arriving
 * at deliberately.
 */
@Composable
private fun IslandSection(p: Panels) {
    val setup = p.setup
    val settings = p.settings
    val prefs = p.prefs
    Column(Modifier.fillMaxWidth()) {
        SectionLabel("Essential island")
        Panel {
            SettingRow(
                title = "Essential Island",
                sub = "A tap target at the top of the screen. Tap to start, tap " +
                    "again to send.",
                enabled = setup.accessibility && setup.overlay,
            ) {
                EvSwitch(
                    settings.island,
                    enabled = setup.accessibility && setup.overlay,
                ) { prefs.setIsland(it) }
            }
            if (settings.island) {
                Hairline()
                StepperRow(
                    title = "From the top",
                    sub = "Where the lozenge sits. 4 puts it behind the camera.",
                    value = settings.islandTopDp,
                    suffix = "dp",
                    step = 2,
                    range = 0..220,
                ) { prefs.setIslandTopDp(it) }
                Hairline()
                // Across the screen, as a percentage, because the island is not
                // draggable any more — see Prefs.setIslandX. 50 is centred, which
                // on this phone is behind the camera.
                StepperRow(
                    title = "Across",
                    sub = "50 is the middle of the screen, under the camera.",
                    value = (settings.islandX * 100f).roundToInt(),
                    suffix = "%",
                    step = 2,
                    range = 0..100,
                ) { prefs.setIslandX(it / 100f) }
                Hairline()
                StepperRow(
                    title = "Height",
                    sub = "Thin enough to hide behind the camera, or tall enough to tap.",
                    value = settings.islandHeightDp,
                    suffix = "dp",
                    step = 2,
                    range = 12..90,
                ) { prefs.setIslandHeightDp(it) }
                Hairline()
                StepperRow(
                    title = "Length",
                    sub = "How far it reaches either side of the camera.",
                    value = settings.islandWidthDp,
                    suffix = "dp",
                    step = 10,
                    range = 60..320,
                ) { prefs.setIslandWidthDp(it) }
            }
        }
    }
}

/** This app drawing the volume instead of the panel Nothing OS draws. */
@Composable
private fun VolumeSection(p: Panels) {
    val context = LocalContext.current
    val type = LocalEvType.current
    val setup = p.setup
    val settings = p.settings
    val prefs = p.prefs
    Column(Modifier.fillMaxWidth()) {
        SectionLabel("Volume ctrl")
        Panel {
            SettingRow(
                title = "Volume slider",
                sub = "This app draws the volume instead of the phone. The " +
                    "buttons work exactly as they did.",
                enabled = setup.accessibility,
            ) {
                EvSwitch(settings.volumeSlider, enabled = setup.accessibility) {
                    prefs.setVolumeSlider(it)
                }
            }
            if (settings.volumeSlider) {
                Hairline()
                SettingRow(
                    title = "Where it sits",
                    sub = "Drag it up or down either edge, and set how long and " +
                        "how thick it is, against the real thing.",
                    onClick = {
                        context.startActivity(
                            Intent(context, PlacementActivity::class.java)
                                .putExtra(PlacementActivity.EXTRA_VOLUME, true)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                ) { EvText("OPEN", type.button, color = EV.Ink) }
                // Length and width are on the placement screen now, not here.
                // Both are questions about the *shape*, and this list could only
                // ever answer them in numbers — you set a length, closed the
                // page, pressed a volume key and found out. They live where the
                // thing is drawn: see PlacementActivity.
                Hairline()
                Column(Modifier.padding(18.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EvText("Stays for", type.body, Modifier.weight(1f))
                        EvText(
                            "%.1fs".format(settings.volumeLingerMs / 1000f),
                            type.mono,
                            color = EV.Ink,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    EvSlider(value = settings.volumeLingerMs, range = 400..5000) {
                        // To the nearest tenth of a second: the slider can
                        // resolve single milliseconds and nobody wants one.
                        prefs.setVolumeLingerMs((it / 100) * 100)
                    }
                }
            }
        }
    }
}

@Composable
private fun GameSection(p: Panels) {
    val setup = p.setup
    val settings = p.settings
    val prefs = p.prefs
    Column(Modifier.fillMaxWidth()) {
        // ---- game mode -----------------------------------------------------
        //
        // Its own section rather than three more switches under Behaviour: this
        // is the one part of the app that changes settings belonging to the
        // phone, and that deserves to be somewhere it can be read as a whole
        // rather than discovered a row at a time.
        if (Features.GAME_MODE) {
            SectionLabel("Game mode")
            GameModePanel(setup, settings, prefs)
        }
    }
}

@Composable
private fun EarbudsSection(p: Panels) {
    Column(Modifier.fillMaxWidth()) {
        // The earbuds are here because of the microphone, not the speakers:
        // dictating through earbuds needs HFP up, and four taps through
        // Settings is four taps in front of a feature whose whole point is
        // being quicker than typing. No address is hardcoded anywhere —
        // whoever installs this picks their own pair out of what is already
        // bonded to their phone.
        //
        // Both of them, drawn rather than described. A widget is a picture of a
        // thing you put on your home screen, and a page about widgets that
        // opens with a Bluetooth permission row is a page about Bluetooth — so
        // the permission, and the pair it is for, live inside the card of the
        // widget that needs them rather than in a section of their own.
        SectionLabel("Widgets")
        NotesWidgetInvite(p.settings, p.prefs)
        Spacer(Modifier.height(12.dp))
        BudsWidgetInvite(p.settings, p.setup, p.prefs, p.onRequestBluetooth)

        Spacer(Modifier.height(26.dp))
    }
}


// ---- pieces --------------------------------------------------------------

/**
 * Frosted glass over the recorded page: the blur, then a thin wash of colour.
 *
 * Everything that floats over the page uses this, so the bar and the popups
 * cannot drift apart. [backdrop] is the layer the page recorded itself into as
 * it drew; this takes the part of it sitting behind whatever it is applied to.
 */
@Composable
private fun Modifier.glass(
    backdrop: androidx.compose.ui.graphics.layer.GraphicsLayer,
    corner: Dp,
): Modifier {
    var origin by remember { androidx.compose.runtime.mutableStateOf(Offset.Zero) }
    val frosted = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val fill = EV.Glass
    val alpha = EV.GlassAlpha

    return this
        .onGloballyPositioned { origin = it.positionInParent() }
        .clip(RoundedCornerShape(corner))
        .drawBehind {
            // Re-recorded per frame, because the page under it scrolls — a
            // still copy would smear one moment of the page across the rest of
            // the scroll.
            frosted.renderEffect = androidx.compose.ui.graphics.BlurEffect(
                30f, 30f, androidx.compose.ui.graphics.TileMode.Clamp,
            )
            frosted.record(
                size = androidx.compose.ui.unit.IntSize(
                    size.width.toInt().coerceAtLeast(1),
                    size.height.toInt().coerceAtLeast(1),
                ),
            ) {
                translate(-origin.x, -origin.y) { drawLayer(backdrop) }
            }
            drawLayer(frosted)
            drawRect(fill.copy(alpha = alpha))
        }
}

/** One cell of the floating bar. The disc is exactly this size. */
private val NavTabWidth = 58.dp
private val NavTabHeight = 46.dp

/**
 * The bar that floats over the page: two icons, no words.
 *
 * Translucent rather than solid, so the page is visibly still there underneath
 * it and it reads as floating rather than as a strip the content stops at. Flat,
 * like everything else here — the separation is the fill and the gap around it,
 * not a shadow.
 *
 * The disc under the selected icon is **one** disc that moves, not a fill that
 * switches off under one icon and on under the other. It takes its position
 * straight from the pager, so it travels with the page: it follows a swipe
 * finger for finger and half a page across it is genuinely halfway between the
 * two tabs. A disc that only knew which tab had won could not do that, which is
 * why the bar is given the pager rather than the current tab.
 */
@Composable
private fun FloatingNav(
    pager: androidx.compose.foundation.pager.PagerState,
    backdrop: androidx.compose.ui.graphics.layer.GraphicsLayer,
    modifier: Modifier = Modifier,
    onSelect: (Tab) -> Unit,
) {
    val tabs = Tab.entries

    // Where the disc is, measured in tabs: a whole number at rest, and every
    // value in between during a swipe or an animated tap. It is read *inside*
    // the layout and draw lambdas below rather than out here, so a swipe moves
    // the disc and shades the icons without recomposing the bar sixty times a
    // second — the position changes every frame, and none of the composition
    // depends on it.
    val position = { pager.currentPage + pager.currentPageOffsetFraction }

    Box(
        modifier
            .padding(bottom = 26.dp)
            .glass(backdrop, 34.dp)
            .padding(6.dp),
    ) {
        Box(
            Modifier
                .offset {
                    androidx.compose.ui.unit.IntOffset(
                        (position() * NavTabWidth.toPx()).toInt(), 0,
                    )
                }
                .size(NavTabWidth, NavTabHeight)
                .clip(RoundedCornerShape(28.dp))
                .background(EV.GlassSelected),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            tabs.forEachIndexed { index, tab ->
                // 1 when the disc is exactly under this tab, 0 once it has left
                // — so the icons brighten and dim across the same movement
                // rather than swapping at the halfway point.
                NavTab(
                    tab = tab,
                    selectedness = { (1f - kotlin.math.abs(position() - index)).coerceIn(0f, 1f) },
                    onSelect = { onSelect(tab) },
                )
            }
        }
    }
}

@Composable
private fun NavTab(tab: Tab, selectedness: () -> Float, onSelect: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .size(NavTabWidth, NavTabHeight)
            .clickable(interactionSource = interaction, indication = null) { onSelect() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            // The icon has no floor of its own — the disc behind it is the
            // bar's, and it is somewhere else. All this can do is get brighter.
            val ink = EV.OnGlass.copy(
                alpha = if (pressed) 1f else 0.55f + 0.45f * selectedness(),
            )
            val w = 1.9.dp.toPx()
            when (tab) {
                // A cog: eight teeth on a ring, drawn as short thick spokes
                // under a hole in the middle. Sliders read as "filters" to too
                // many people.
                Tab.Settings -> {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val ring = size.minDimension * 0.30f
                    repeat(8) { i ->
                        val a = (Math.PI / 4.0) * i + Math.PI / 8.0
                        val inner = ring * 0.92f
                        val outer = ring * 1.62f
                        drawLine(
                            ink,
                            Offset(
                                c.x + inner * kotlin.math.cos(a).toFloat(),
                                c.y + inner * kotlin.math.sin(a).toFloat(),
                            ),
                            Offset(
                                c.x + outer * kotlin.math.cos(a).toFloat(),
                                c.y + outer * kotlin.math.sin(a).toFloat(),
                            ),
                            w * 1.5f,
                            StrokeCap.Round,
                        )
                    }
                    drawCircle(
                        ink, radius = ring, center = c,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(w * 1.6f),
                    )
                }
                // A card with lines on it: a page in the library.
                Tab.Library -> {
                    drawRoundRect(
                        color = ink,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(w),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                    )
                    listOf(0.36f, 0.56f, 0.76f).forEachIndexed { i, y ->
                        drawLine(
                            ink,
                            Offset(size.width * 0.26f, size.height * y),
                            Offset(size.width * (if (i == 2) 0.58f else 0.74f), size.height * y),
                            w, StrokeCap.Round,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The library: the one page for everything the key kept.
 *
 * It was the notes tab, and the three kinds are why it is not any more. A note,
 * a task and a recording are made the same way — hold the key, say the word —
 * and they belong to the same afternoon, so they are one list in the order they
 * happened rather than three lists that have to be visited in turn. The chips
 * narrow that list; they do not split it.
 */
@Composable
private fun LibraryTab(setup: SetupState, settings: Settings, prefs: Prefs) {
    val type = LocalEvType.current
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        NoteStore.load(context)
        // Anything whose words were never found — a decode the process died in
        // the middle of, or one that was still waiting when the app was last
        // closed. Opening the library is the moment it matters that they are
        // ready, so it is the moment to check.
        Transcribe.sweep(context)
    }
    val all by NoteStore.notes.collectAsState()
    var shelf by rememberSaveable { mutableStateOf(Shelf.All) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = EV.PagePadding)
            .padding(bottom = 128.dp),
    ) {
        // No masthead on this tab. The app's name, its strap and the theme
        // toggle are the *home* page's furniture: repeating them here pushed
        // the only thing this page is — the list — a third of a screen down,
        // and said the app's name twice to somebody who is two taps in and
        // looking for a note. The toggle still lives on Home, where it always
        // did, so nothing is lost by taking it off this one.
        //
        // The 46dp is the masthead's own top padding, kept so the page still
        // clears the status bar by the same margin every other page does.
        Spacer(Modifier.height(46.dp))
        EvText("Your library", type.display)
        Spacer(Modifier.height(6.dp))
        EvText(
            "Everything the key kept — notes, tasks\nand recordings.",
            type.strap,
        )

        Spacer(Modifier.height(18.dp))
        ShelfChips(shelf, all) { shelf = it }
        Spacer(Modifier.height(14.dp))
        LibraryList(shelf, all)

        // The widget invite used to be here as well as on the widgets page.
        // One card, in the one place the app keeps its widgets — see
        // [EarbudsSection].

        Spacer(Modifier.height(24.dp))
        EvText(
            "The library is kept on this phone, in the app's own storage, and goes " +
                "nowhere else. Everything else the app hears is used and dropped.",
            type.mono,
            Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** Which part of the library is showing. */
private enum class Shelf(val label: String, val kind: NoteStore.Kind?) {
    All("All", null),
    Notes("Notes", NoteStore.Kind.NOTE),
    Tasks("Tasks", NoteStore.Kind.TASK),
    Recordings("Recordings", NoteStore.Kind.RECORDING);

    fun holds(note: NoteStore.Note) = kind == null || note.kind == kind
}

/**
 * The filter row: four chips, each carrying how many it would show.
 *
 * The counts are the reason the chips are worth the space. Without them a chip
 * is a promise — press it and find out — and three of the four are empty on a
 * phone that has only ever taken notes. With them the row is a summary of the
 * library that happens to be tappable.
 *
 * All four have to be *on screen*, not merely reachable: a row that ends in a
 * half-cut chip reads as a list that ends there, and the one being cut was
 * Recordings — the shelf somebody is least likely to guess exists. So the chip
 * is set at the page's smaller sizes and the padding pulled in until the four
 * of them fit a phone's width with room to spare.
 *
 * It still scrolls sideways rather than wrapping, which is now a safety net for
 * a narrower phone rather than the way it is meant to be read — and a fifth
 * kind, if there is ever one, still costs nothing in layout.
 */
@Composable
private fun ShelfChips(
    selected: Shelf,
    all: List<NoteStore.Note>,
    onSelect: (Shelf) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Shelf.entries.forEach { shelf ->
            ShelfChip(
                shelf = shelf,
                count = all.count { shelf.holds(it) },
                selected = shelf == selected,
                onSelect = { onSelect(shelf) },
            )
        }
    }
}

@Composable
private fun ShelfChip(shelf: Shelf, count: Int, selected: Boolean, onSelect: () -> Unit) {
    val type = LocalEvType.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(
        when {
            selected -> EV.Ink
            pressed -> EV.SurfaceSunk
            else -> EV.Surface
        },
        label = "chip-${shelf.name}",
    )
    val ink = if (selected) EV.OnInk else EV.InkMuted

    Row(
        Modifier
            .clip(RoundedCornerShape(EV.CornerPill))
            .background(fill)
            .clickable(interactionSource = interaction, indication = null, onClick = onSelect)
            .padding(horizontal = 9.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 13sp and 11dp of padding are not taste, they are the arithmetic: the
        // four chips at the page's own 16sp and 16dp came to 376dp against 352
        // of screen, which is exactly the two dozen dp of "Recordings" that was
        // hanging off the right-hand edge. Measured on the phone twice rather
        // than estimated: 13sp with 11dp of padding still overran by a hair, so
        // the padding came in again. It ends about fifteen dp short of the
        // margin now, which is the only way to know it is not being cut.
        EvText(shelf.label, type.sub.copy(fontSize = 13.sp), color = ink, maxLines = 1)
        Spacer(Modifier.width(5.dp))
        // The count in mono, so a row of chips lines up on its numbers however
        // long the words beside them are.
        EvText(
            count.toString(),
            type.stamp,
            color = if (selected) EV.OnInk.copy(alpha = 0.62f) else EV.InkFaint,
            maxLines = 1,
        )
    }
}

/**
 * The list itself, newest first, because the thing you just made is the thing
 * you are looking for; older ones are a list you scroll, not a list you read.
 */
@Composable
private fun LibraryList(shelf: Shelf, all: List<NoteStore.Note>) {
    val context = LocalContext.current
    val rows = all.filter { shelf.holds(it) }

    Panel {
        if (rows.isEmpty()) {
            EmptyShelf(shelf)
        } else {
            rows.forEachIndexed { index, note ->
                if (index > 0) Hairline()
                when (note.kind) {
                    NoteStore.Kind.TASK -> TaskRow(note) { openNote(context, note.id) }
                    NoteStore.Kind.RECORDING -> RecordingRow(note) { openNote(context, note.id) }
                    NoteStore.Kind.NOTE -> NoteRow(note) { openNote(context, note.id) }
                }
            }
        }
    }
}

/**
 * Nothing here yet — and, more usefully, the sentence that would put something
 * here.
 *
 * One per shelf rather than one for the page, because "nothing yet" under the
 * Tasks chip and "nothing yet" under the Recordings chip are answers to
 * different questions, and the word that fills each is different.
 */
@Composable
private fun EmptyShelf(shelf: Shelf) {
    val type = LocalEvType.current
    val (title, body) = when (shelf) {
        Shelf.All -> "Nothing yet" to
            "Hold the key and start with \u201cnote\u201d, \u201ctask\u201d or " +
                "\u201crecord\u201d \u2014 the pill says which one it heard, and what " +
                "you say next lands here."
        Shelf.Notes -> "No notes yet" to
            "Hold the key and start with the word \u201cnote\u201d \u2014 \u201cnote buy " +
                "milk\u201d. The pill grows into a card, and pressing save adds it here."
        Shelf.Tasks -> "No tasks yet" to
            "Hold the key and start with the word \u201ctask\u201d \u2014 \u201ctask call " +
                "the bank\u201d. Tap the circle beside one to tick it off."
        Shelf.Recordings -> "No recordings yet" to
            "Say \u201crecord\u201d and keep holding the key. The pill counts the seconds; " +
                "letting go stops it and plays it back."
    }
    Column(Modifier.padding(18.dp)) {
        EvText(title, type.body)
        Spacer(Modifier.height(4.dp))
        EvText(body, type.sub)
    }
}

/**
 * The notes widget, shown rather than described, with a + that adds it.
 *
 * The paragraph this replaced was directions: hold the wallpaper, choose
 * Widgets, find Essential Voice, drag out Notes. Four steps and a hunt through
 * a picker, to find something nobody had seen a picture of.
 *
 * The picture is the widget's own preview layout, inflated — the same file the
 * launcher's picker shows. That is deliberate: a screenshot would be a second
 * copy of the design, and it would be wrong the first time the widget changed.
 *
 * The + asks the launcher to place it. [AppWidgetManager.requestPinAppWidget] is
 * the only supported way in: no app can open the launcher's widget drawer, let
 * alone scroll it to one entry. Where the launcher supports pinning — this one
 * does — the phone puts up its own "add to home screen" prompt with the widget
 * already chosen, which is the four steps done. Where it does not, the fallback
 * is the home screen and the old instructions.
 */
@Composable
private fun NotesWidgetInvite(settings: Settings, prefs: Prefs) {
    val context = LocalContext.current
    WidgetInvite(
        title = "Notes, now on your home screen",
        body = "It shows the same notes as this page and changes the moment one does — " +
            "tapping a note there opens it for editing, and + starts an empty one.",
        provider = NotesWidget::class.java,
        art = { NotesWidgetArt() },
        // Below the button, and below a hairline, because they are not part of
        // the invitation: they are what the widget does once it is there, and
        // they go on being useful long after the button has been pressed.
        tail = {
            Hairline()
            SettingRow(
                title = "Notes",
                sub = "Plain notes, in the widget's list.",
            ) {
                EvSwitch(settings.widgetNotes) { prefs.setWidgetNotes(context, it) }
            }
            Hairline()
            SettingRow(
                title = "Task",
                sub = "Tasks, with a tick on the ones that are done.",
            ) {
                EvSwitch(settings.widgetTasks) { prefs.setWidgetTasks(context, it) }
            }
            Hairline()
            SettingRow(
                title = "Recording",
                sub = "Recordings, with how long each one runs.",
            ) {
                EvSwitch(settings.widgetRecordings) { prefs.setWidgetRecordings(context, it) }
            }
        },
    )
}

/**
 * The earbuds widget, the tile, and the pair they both point at — one card.
 *
 * They were two sections, and the split was the wrong cut: "Your earbuds, one
 * tap away" promised a tap, then the thing that decides *whose* earbuds sat
 * under its own heading further down, so the picture and the setting it needs
 * were never on screen together. Bluetooth permission, the pair, the widget and
 * the tile are one feature and they are now one card, in the order they are
 * needed: see it, allow it, choose the pair, put it somewhere.
 */
@Composable
private fun BudsWidgetInvite(
    settings: Settings,
    setup: SetupState,
    prefs: Prefs,
    onRequestBluetooth: () -> Unit,
) {
    val context = LocalContext.current
    val type = LocalEvType.current
    WidgetInvite(
        title = "Your earbuds, one tap away",
        body = "Connects the pair you choose below without opening anything. It says " +
            "which pair it will reach, and whether they are on.",
        provider = BudsWidget::class.java,
        art = { BudsWidgetArt() },
        tail = {
            Hairline()
            if (!setup.bluetooth) {
                SettingRow(
                    title = "Allow Bluetooth",
                    sub = "So the widget and the tile can connect the earbuds " +
                        "you choose. Nothing is scanned for; only devices you " +
                        "have already paired are ever touched.",
                    onClick = onRequestBluetooth,
                ) { EvText("ALLOW", type.button, color = EV.Ink) }
            } else {
                val devices = remember(setup) { Buds.bondedAudioDevices(context) }
                if (devices.isEmpty()) {
                    SettingRow(
                        title = "No paired earbuds",
                        sub = "Pair them in Bluetooth settings once, then come " +
                            "back and pick them here.",
                        onClick = { openBluetoothSettings(context) },
                    ) { EvText("OPEN", type.button, color = EV.Ink) }
                } else {
                    devices.forEach { device ->
                        val address = device.address
                        val chosen = settings.budsAddress.equals(address, ignoreCase = true)
                        SettingRow(
                            title = Buds.label(device),
                            sub = address,
                            // Tapping the chosen pair again does nothing on
                            // purpose. It used to clear the choice — "tap again
                            // to undo" — and in use that is a destructive toggle
                            // with no affordance: one stray tap in a list that
                            // has just scrolled silently unpicks the buds, and
                            // the only sign is the tile quietly going back to
                            // "Choose them in the app". Picking a different pair
                            // switches; there is nothing to undo.
                            onClick = if (chosen) {
                                null
                            } else {
                                {
                                    prefs.setBuds(address, Buds.label(device))
                                    BudsWidget.refresh(context)
                                    BudsTile.refresh(context)
                                }
                            },
                        ) {
                            if (chosen) EvText("CHOSEN", type.button, color = EV.Ink)
                        }
                        Hairline()
                    }
                    SettingRow(
                        title = "Pair another device",
                        sub = "Opens Bluetooth settings. Anything you pair there " +
                            "shows up in this list.",
                        onClick = { openBluetoothSettings(context) },
                    ) { EvText("PAIR", type.button, color = EV.Ink) }
                }
            }
        },
        extra = {
            // The tile is the same feature through a different door — the same
            // buds, the same one tap — so it sits with the widget rather than
            // in a list of earbud settings three rows down.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                Spacer(Modifier.height(10.dp))
                EvButton(
                    "Add the Quick Settings tile",
                    Modifier.fillMaxWidth(),
                    kind = EvButtonKind.Quiet,
                    enabled = settings.budsAddress.isNotEmpty(),
                ) { requestBudsTile(context) }
                if (settings.budsAddress.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    EvText("Pick a pair below first.", type.sub)
                }
            }
        },
    )
}

/**
 * A widget, drawn on a phone, with the button that puts it there.
 *
 * The picture is a drawing rather than the widget's real preview layout
 * inflated. The real one was accurate and did not read: on the dark page it had
 * no edge, so it looked like text floating on the background rather than like a
 * thing you put somewhere — and it carries its own +, which sat beside the +
 * that added the widget, two plus signs a centimetre apart meaning different
 * things.
 *
 * [AppWidgetManager.requestPinAppWidget] is the only supported way in: no app
 * can open the launcher's widget drawer, let alone scroll it to one entry.
 * Where the launcher supports pinning — this one does — the phone puts up its
 * own prompt with the widget already chosen. Where it does not, the fallback is
 * the old instructions.
 */
@Composable
private fun WidgetInvite(
    title: String,
    body: String,
    provider: Class<*>,
    art: @Composable () -> Unit,
    extra: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {},
    /**
     * Rows that belong to this widget, below the button and *outside* the
     * card's padding.
     *
     * [extra] sits in the padded column with the prose; this does not, because
     * a [SettingRow] carries its own padding and a [Hairline] is meant to reach
     * the card's edges. Inset twice they read as a second card inside the card.
     */
    tail: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {},
) {
    val type = LocalEvType.current
    val context = LocalContext.current
    var pinnable by remember { mutableStateOf(true) }

    Panel {
        Column(Modifier.padding(18.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .clip(RoundedCornerShape(EV.CornerCard))
                    .border(1.dp, EV.SurfaceSunk, RoundedCornerShape(EV.CornerCard)),
                contentAlignment = Alignment.Center,
            ) { art() }

            Spacer(Modifier.height(16.dp))
            EvText(title, type.body)
            Spacer(Modifier.height(4.dp))
            EvText(
                if (pinnable) {
                    body
                } else {
                    "This launcher will not place it for you. Press and hold the " +
                        "wallpaper, choose Widgets, and find Essential Voice."
                },
                type.sub,
            )
            Spacer(Modifier.height(14.dp))
            EvButton("Add the widget", Modifier.fillMaxWidth()) {
                pinnable = requestPinWidget(context, provider)
            }
            extra()
        }
        tail()
    }
}

/** Bluetooth settings, from wherever the earbuds card needs to send someone. */
private fun openBluetoothSettings(context: Context) {
    context.startActivity(
        Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/**
 * A phone outline with one widget on it.
 *
 * Everything is proportional to the phone, so the whole drawing scales with the
 * box it is given, and every colour comes from the palette so it follows the
 * page into the dark. [widget] draws whatever goes inside the widget's own
 * rectangle, which is handed to it in absolute coordinates.
 */
@Composable
private fun WidgetOnPhone(
    widget: androidx.compose.ui.graphics.drawscope.DrawScope.(
        Offset,
        androidx.compose.ui.geometry.Size,
    ) -> Unit,
) {
    Canvas(Modifier.fillMaxSize()) {
        val h = size.height * 0.86f
        val w = h * 0.47f
        val left = (size.width - w) / 2f
        val top = (size.height - h) / 2f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(1.4.dp.toPx())

        drawRoundRect(
            color = EV.InkFaint,
            topLeft = Offset(left, top),
            size = androidx.compose.ui.geometry.Size(w, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.13f),
            style = stroke,
        )
        // the home-bar handle
        drawRoundRect(
            color = EV.InkFaint,
            topLeft = Offset(left + w * 0.3f, top + h * 0.945f),
            size = androidx.compose.ui.geometry.Size(w * 0.4f, h * 0.008f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.004f),
        )

        val pad = w * 0.09f
        val wx = left + pad
        val wy = top + h * 0.17f
        val ww = w - pad * 2
        val wh = h * 0.3f
        drawRoundRect(
            color = EV.SurfaceSunk,
            topLeft = Offset(wx, wy),
            size = androidx.compose.ui.geometry.Size(ww, wh),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(ww * 0.1f),
        )
        widget(Offset(wx, wy), androidx.compose.ui.geometry.Size(ww, wh))

        // a row of app icons underneath, so the widget reads as being on a home
        // screen rather than floating in a frame
        val dotR = w * 0.045f
        listOf(0.25f, 0.42f, 0.58f, 0.75f).forEach { fx ->
            drawCircle(
                EV.SurfaceSunk,
                radius = dotR,
                center = Offset(left + w * fx, top + h * 0.62f),
            )
        }
    }
}

/** The notes widget: a heading, its one +, and two notes. */
@Composable
private fun NotesWidgetArt() = WidgetOnPhone { at, box ->
    val (wx, wy) = at
    val ww = box.width
    val wh = box.height
    drawRoundRect(
        color = EV.InkFaint,
        topLeft = Offset(wx + ww * 0.1f, wy + wh * 0.16f),
        size = androidx.compose.ui.geometry.Size(ww * 0.3f, wh * 0.05f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(wh * 0.025f),
    )
    val px = wx + ww * 0.84f
    val py = wy + wh * 0.185f
    val pr = ww * 0.075f
    drawCircle(EV.Yellow, radius = pr, center = Offset(px, py))
    val arm = pr * 0.5f
    drawLine(
        EV.OnYellow, Offset(px - arm, py), Offset(px + arm, py),
        strokeWidth = 1.4.dp.toPx(),
        cap = androidx.compose.ui.graphics.StrokeCap.Round,
    )
    drawLine(
        EV.OnYellow, Offset(px, py - arm), Offset(px, py + arm),
        strokeWidth = 1.4.dp.toPx(),
        cap = androidx.compose.ui.graphics.StrokeCap.Round,
    )
    listOf(0.45f, 0.7f).forEachIndexed { i, y ->
        drawRoundRect(
            color = EV.InkFaint,
            topLeft = Offset(wx + ww * 0.1f, wy + wh * y),
            size = androidx.compose.ui.geometry.Size(
                ww * (if (i == 0) 0.62f else 0.44f), wh * 0.055f,
            ),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(wh * 0.03f),
        )
    }
}

/** The buds widget: the status dot, the name, and the line under it. */
@Composable
private fun BudsWidgetArt() = WidgetOnPhone { at, box ->
    val (wx, wy) = at
    val ww = box.width
    val wh = box.height
    drawCircle(
        EV.Yellow,
        radius = ww * 0.055f,
        center = Offset(wx + ww / 2f, wy + wh * 0.3f),
    )
    listOf(0.52f to 0.42f, 0.7f to 0.6f).forEach { (y, fill) ->
        drawRoundRect(
            color = EV.InkFaint,
            topLeft = Offset(wx + ww * (1f - fill) / 2f, wy + wh * y),
            size = androidx.compose.ui.geometry.Size(ww * fill, wh * 0.06f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(wh * 0.03f),
        )
    }
}

/**
 * Ask the launcher to place one of this app's widgets.
 *
 * Returns false when it will not — an old launcher, or one that has switched
 * pinning off — which is the only case where the directions are worth printing.
 */
private fun requestPinWidget(context: Context, provider: Class<*>): Boolean {
    val manager = context.getSystemService(AppWidgetManager::class.java) ?: return false
    if (!manager.isRequestPinAppWidgetSupported) return false
    val component = ComponentName(context, provider)
    return runCatching { manager.requestPinAppWidget(component, null, null) }.getOrDefault(false)
}

/** What an open [InfoDot] is showing, and where on the page it was opened. */
private class InfoRequest(val text: String, val anchorY: Float)

private val LocalInfoOpener =
    androidx.compose.runtime.staticCompositionLocalOf<(String, Float) -> Unit> { { _, _ -> } }

/**
 * A small circled *i* that opens the paragraph nobody needs twice.
 *
 * The explanations it hides are all true and all worth reading once — and all
 * of them were sitting permanently under something they explained, making the
 * page longer every time it was scrolled past. Behind an *i* they cost one
 * glyph until asked for.
 *
 * The panel it opens is drawn by the page, not by a popup window: a popup is a
 * window of its own and cannot reach the layer the page recorded itself into,
 * so it could never be made of the same frosted glass as the bar.
 */
/**
 * Asks the system to offer the tile, rather than telling the user to go and
 * find it in the Quick Settings editor. API 33 and up; below that the editor is
 * the only route and the row is not shown at all.
 */
private fun requestBudsTile(context: Context) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
    val sbm = context.getSystemService(android.app.StatusBarManager::class.java) ?: return
    runCatching {
        sbm.requestAddTileService(
            android.content.ComponentName(context, BudsTile::class.java),
            context.getString(com.ishaan.essentialvoice.R.string.buds_tile_label),
            android.graphics.drawable.Icon.createWithResource(
                context,
                com.ishaan.essentialvoice.R.drawable.ic_logo,
            ),
            { it.run() },
            { },
        )
    }
}

/**
 * Game mode: the switch, every lever it is allowed to pull, and what each one
 * needs before it can pull it.
 *
 * The long form on purpose. A switch labelled "game mode" that silently changes
 * the brightness, the rotation and the notifications of the phone it is on would
 * be the least honest thing in this app, so the panel is a list of exactly what
 * happens, each line its own switch, and each line that needs something says so
 * *in place* rather than failing quietly when the mode is armed.
 *
 * The three grant rows appear only when a lever that wants them is switched on.
 * A permission request for a feature nobody has turned on is noise, and a
 * settings screen that opens with three things to grant reads as an app asking
 * for the phone.
 */
/**
 * The ways in that are not the Essential Key: the home bar, and two knocks on
 * the back of the phone.
 *
 * Together in one card because they share a shape. The key is *held* — the
 * finger on it is the interface, and letting go is the stop. Nothing else can be
 * held: the assistant role hands over a single launch, and a knock is over
 * before it is recognised. So every one of these is a toggle, and every toggle
 * needs the bar at the bottom to say it is still listening and to be tapped when
 * it should not be. That is why the bar's switch lives here rather than under
 * Behaviour with the pill.
 */
@Composable
internal fun OtherWaysPanel(
    setup: SetupState,
    settings: Settings,
    prefs: Prefs,
    /** Inside another card already, so it must not draw a second one. */
    bare: Boolean = false,
) {
    val context = LocalContext.current
    val type = LocalEvType.current

    // Asked here rather than through BackTap, which only knows the answer once
    // the accessibility service has been bound. The switch has to be right on a
    // screen opened before that happens.
    val hasAccelerometer = remember {
        (context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager)
            ?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) != null
    }

    val body = @Composable {
        // ---- the assistant gesture -----------------------------------------
        //
        // One heading, one line saying what it needs, one line saying how to do
        // it, one button. What was here was four paragraphs and an InfoDot
        // explaining at length why the *home bar* is not this gesture — an
        // answer to a question nobody standing on this screen had asked.
        SettingRow(
            title = "Quick gesture",
            sub = if (setup.assistant) {
                "Essential Voice is the digital assistant, so the gesture starts " +
                    "a dictation."
            } else {
                "Needs Essential Voice to be the phone's digital assistant."
            },
        ) {
            StatusPip(setup.assistant, if (setup.assistant) "Assistant" else "Not set")
        }
        Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp)) {
            EvText(
                "Swipe diagonally in from the bottom-left or bottom-right corner. " +
                    "If nothing happens, the gesture is switched off on the phone " +
                    "\u2014 look for the assistant gesture in System \u2192 Gestures.",
                type.sub,
            )
            Spacer(Modifier.height(14.dp))
            // Said before the trip, not discovered after it. Changing the
            // assistant makes the phone stop this app, and a stopped app is
            // dropped from the accessibility list outright — so the Essential
            // Key goes dead on the way back, every time, and nothing on screen
            // would otherwise connect the two.
            EvText(
                "Setting this restarts the app, and the phone switches the " +
                    "accessibility service off when it does. Turn it back on " +
                    "afterwards or the Essential Key will not work.",
                type.sub,
                color = EV.Red,
            )
            Spacer(Modifier.height(14.dp))
            EvButton(
                if (setup.assistant) "Change the assistant" else "Set it as the assistant",
                kind = EvButtonKind.Quiet,
            ) { Setup.openAssistantSettings(context) }
            if (!setup.accessibility) {
                Spacer(Modifier.height(10.dp))
                EvButton("Turn the accessibility service back on") {
                    Setup.openAccessibilitySettings(context)
                }
            }
        }

        if (Features.BACK_TAP) {
            Hairline()

            // ---- two knocks on the back ----------------------------------------
            SettingRow(
                title = "Double tap the back",
                sub = if (hasAccelerometer) {
                    "Two knocks on the back of the phone start a dictation. Screen " +
                        "on only \u2014 this phone cannot wake on a knock."
                } else {
                    "This phone has no accelerometer, so there is nothing to feel a " +
                        "knock with."
                },
                enabled = hasAccelerometer && setup.accessibility,
            ) {
                EvSwitch(
                    settings.backTap,
                    enabled = hasAccelerometer && setup.accessibility,
                ) { prefs.setBackTap(it) }
            }

            if (settings.backTap) {
                Hairline()
                StepperRow(
                    title = "How hard a knock",
                    sub = "1 wants a firm double knock. 5 will hear a tap through a case.",
                    value = settings.backTapSensitivity,
                    suffix = "",
                    step = 1,
                    range = 1..5,
                ) { prefs.setBackTapSensitivity(it) }

                Hairline()
                ActionPicker(
                    title = "Two knocks",
                    sub = "What a double tap does.",
                    selectedId = settings.backTapAction,
                    appPackage = settings.backTapApp,
                    allowNothing = false,
                    onSelect = { prefs.setBackTapAction(it) },
                    onPickApp = { prefs.setBackTapApp(it) },
                )

                Hairline()
                ActionPicker(
                    title = "Three knocks",
                    sub = if (settings.backTapTripleAction == TapAction.NOTHING.id) {
                        "Off. Turning it on makes every double tap wait a little " +
                            "longer, in case a third knock is coming."
                    } else {
                        "What a triple tap does. Doubles now wait a beat longer, in " +
                            "case a third is coming."
                    },
                    selectedId = settings.backTapTripleAction,
                    appPackage = settings.backTapTripleApp,
                    allowNothing = true,
                    onSelect = { prefs.setBackTapTripleAction(it) },
                    onPickApp = { prefs.setBackTapTripleApp(it) },
                )

                Hairline()
                BackTapTest()
                Hairline()
                Column(Modifier.padding(18.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        EvText("What it costs", type.body, Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp))
                        InfoDot(
                            "The accelerometer on this phone is already running all " +
                                "day with other things listening to it, so this raises " +
                                "the rate of a sensor that is on rather than waking one " +
                                "that is off \u2014 and only while the screen is on. It " +
                                "stops the moment the screen does, which is also why it " +
                                "cannot work in your pocket: nothing on this phone can " +
                                "wake it on a knock.",
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    EvText(
                        "Ignored while the phone is moving and while anything is over " +
                            "the top of the screen, which is what keeps it from firing " +
                            "in a pocket or on a bumpy ride.",
                        type.sub,
                    )
                }
            }
        }

        if (Features.HOME_SWIPE) {
            Hairline()

            // ---- two fingers up from the home bar ---------------------------
            SettingRow(
                title = "Two fingers up from the home bar",
                sub = "Drag up from the bottom with two fingers. The handle grows " +
                    "into the bar under them; let go at the top to start.",
                enabled = setup.accessibility,
            ) {
                EvSwitch(settings.homeSwipe, enabled = setup.accessibility) {
                    prefs.setHomeSwipe(it)
                }
            }
            Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    EvText("Why two fingers", type.body, Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    InfoDot(
                        "Measured on this phone: one finger up from the bottom edge " +
                            "goes home, and two fingers doing the same thing do " +
                            "nothing at all \u2014 the launcher cancels its own " +
                            "gesture the moment a second finger lands, and no other " +
                            "system gesture claims it. So this is the one gesture on " +
                            "the home bar an app can have, which is why it is here " +
                            "and holding the handle is not.",
                    )
                }
                Spacer(Modifier.height(4.dp))
                EvText(
                    "Swiping up with one finger still goes home, exactly as before. " +
                        "What this does cost: while it is on, a touch that lands in " +
                        "the last few millimetres of the screen goes to Essential " +
                        "Voice rather than to the app underneath.",
                    type.sub,
                )
            }
        }

    }

    if (bare) body() else Panel { body() }
}

/**
 * What one knock gesture does, as a row of chips plus, when it is needed, an app
 * to open.
 *
 * Chips rather than a list of rows with ticks: seven actions as seven rows is
 * most of a screen for one question, twice over once triple tap has its own. A
 * scrolling row keeps each gesture to a single line of the page, and the
 * selected chip is filled rather than outlined — the same idiom the rest of the
 * app uses, and nothing moves on press.
 */
@Composable
private fun ActionPicker(
    title: String,
    sub: String,
    selectedId: String,
    appPackage: String,
    allowNothing: Boolean,
    onSelect: (String) -> Unit,
    onPickApp: (String) -> Unit,
) {
    val type = LocalEvType.current
    val selected = TapAction.byId(selectedId)

    Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 4.dp)) {
        EvText(title, type.body)
        Spacer(Modifier.height(3.dp))
        EvText(sub, type.sub)
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TapAction.choices
                .filter { allowNothing || it != TapAction.NOTHING }
                .forEach { action ->
                    ActionChip(action.label, action == selected) { onSelect(action.id) }
                }
        }
        Spacer(Modifier.height(10.dp))
        EvText(selected.detail, type.sub)
    }
    if (selected == TapAction.OPEN_APP) TapAppPicker(appPackage, onPickApp)
}

@Composable
private fun ActionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(
        when {
            selected -> EV.Ink
            pressed -> EV.SurfaceSunk
            else -> EV.Background
        },
        label = "chip",
    )
    Box(
        Modifier
            .height(38.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(fill)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        EvText(
            label.uppercase(),
            LocalEvType.current.button,
            color = if (selected) EV.OnInk else EV.Ink,
            maxLines = 1,
        )
    }
}

/**
 * Which app "Open an app" opens.
 *
 * Borrows [GameApps.launchable] rather than asking the package manager again:
 * the manifest’s `<queries>` element already covers exactly this — things with a
 * launcher activity, and nothing else — and one answer to "what can be opened"
 * is better than two that could disagree.
 */
@Composable
private fun TapAppPicker(selected: String, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val type = LocalEvType.current
    var open by rememberSaveable { mutableStateOf(false) }

    Hairline()
    SettingRow(
        title = "The app it opens",
        sub = if (selected.isEmpty()) {
            "Not picked yet, so the knock does nothing."
        } else {
            GameApps.label(context, selected)
        },
        onClick = { open = !open },
    ) { EvText(if (open) "CLOSE" else "PICK", type.button, color = EV.Ink) }

    if (!open) return

    val apps by produceState(initialValue = emptyList<GameApps.Entry>()) {
        value = withContext(Dispatchers.IO) { GameApps.launchable(context) }
    }
    if (apps.isEmpty()) {
        Column(Modifier.padding(18.dp)) { EvText("Reading the app list\u2026", type.sub) }
        return
    }

    // Capped and scrolled inside the card, for the same reason the game picker
    // is: two hundred apps would otherwise become the whole settings page.
    Column(
        Modifier
            .heightIn(max = 300.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        apps.sortedBy { it.label.lowercase() }.forEach { entry ->
            Hairline()
            SettingRow(
                title = entry.label,
                sub = entry.packageName,
                onClick = {
                    onPick(entry.packageName)
                    open = false
                },
            ) {
                if (entry.packageName == selected) {
                    EvText("CHOSEN", type.button, color = EV.Ink)
                }
            }
        }
    }
}

/**
 * Somewhere to knock while the sensitivity is being set, and — more usefully —
 * what the detector actually measured.
 *
 * A slider for a physical gesture is unusable without this. How hard a knock
 * reads depends on the case as much as on the phone, and this phone has a
 * screwed-on back plate rather than a sealed one, so a number picked in advance
 * is worth much less than the reading somebody gets from their own phone. It
 * also names the *reason* a knock was ignored, which is the difference between
 * "this does not work" and "the phone was still moving".
 */
@Composable
private fun BackTapTest() {
    val reading by BackTap.last.collectAsState()
    var showing by remember { mutableStateOf<BackTap.Reading?>(null) }

    LaunchedEffect(reading) {
        val r = reading ?: return@LaunchedEffect
        showing = r
        // Long enough to read, short enough that a stale number is never mistaken
        // for the answer to the knock somebody just made.
        delay(6_000)
        if (showing === r) showing = null
    }

    val r = showing
    SettingRow(
        title = "Try it",
        sub = when (r?.verdict) {
            null -> "Knock twice on the back of the phone. A dictation will start too."
            BackTap.Verdict.HEARD ->
                "Heard ${if (r.taps >= 3) "three" else "two"} \u2014 ${num(r.first)} " +
                    "and ${num(r.second)}, ${r.gapMs}ms apart."
            BackTap.Verdict.LONE ->
                "One knock, ${num(r.first)}. Waiting for its pair."
            BackTap.Verdict.TOO_HARD ->
                "Ignored \u2014 ${num(r.first)} is far too hard for a finger. " +
                    "That is what a table reads like."
            BackTap.Verdict.MOVING ->
                "Ignored \u2014 the phone was moving (${num(r.motion)})."
            BackTap.Verdict.COVERED ->
                "Ignored \u2014 something is over the top of the screen."
            BackTap.Verdict.RINGING ->
                "Ignored \u2014 a third knock followed, so those two were a bounce."
            BackTap.Verdict.MISMATCHED ->
                "Ignored \u2014 ${num(r.first)} then ${num(r.second)} is too " +
                    "uneven to be one finger."
            BackTap.Verdict.LOCKED ->
                "Ignored \u2014 the phone is locked. Back Tap and Quick Tap both " +
                    "stop there too."
        },
    ) {
        StatusPip(
            r?.verdict == BackTap.Verdict.HEARD,
            when (r?.verdict) {
                null -> "Waiting"
                BackTap.Verdict.HEARD -> "Heard"
                BackTap.Verdict.LONE -> "One"
                else -> "Ignored"
            },
        )
    }
}

/**
 * One decimal place. These are read at a glance, not compared to three digits.
 *
 * Locale.US rather than the default, because the default is the *phone's* and
 * these numbers are laid out beside a "s" and inside sentences written in
 * English. On a phone set to a locale with its own digits the figure came back
 * in a different script from the label next to it.
 */
private fun num(v: Float) = String.format(java.util.Locale.US, "%.1f", v)

@Composable
private fun GameModePanel(setup: SetupState, settings: Settings, prefs: Prefs) {
    val context = LocalContext.current
    val type = LocalEvType.current
    val scope = rememberCoroutineScope()
    val g = settings.game
    val ready = setup.accessibility

    // Every lever writes the whole profile back. See Prefs.setGame.
    fun edit(change: GameProfile.() -> GameProfile) = prefs.setGame(g.change())

    Panel {
        SettingRow(
            title = if (settings.gameArmed) "Game mode is on" else "Game mode",
            sub = when {
                !ready -> "Needs the accessibility service, like everything else here."
                settings.gameArmed -> "Everything ticked below is in effect. Turning it " +
                    "off puts every one of them back exactly as it was."
                else -> "One switch for the phone getting out of the way. Reach it from " +
                    "the Quick Settings tile without leaving the game."
            },
            enabled = ready,
        ) {
            EvSwitch(settings.gameArmed, enabled = ready) { on ->
                if (on) GameMode.arm(context, auto = false) else GameMode.disarm(context)
                GameTile.refresh(context)
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            Hairline()
            SettingRow(
                title = "Add the Quick Settings tile",
                sub = "The only place anyone would actually reach for this: one swipe, " +
                    "from inside the game.",
                enabled = ready,
                onClick = { requestGameTile(context) },
            ) { EvText("ADD", type.button, color = EV.Ink) }
        }

        Hairline()
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EvText("WHILE IT IS ON", type.label, Modifier.weight(1f), color = EV.Ink)
            InfoDot(
                "Nothing here makes the game itself run faster — no app on an " +
                    "unrooted phone can schedule another app's threads, and since " +
                    "Android 14 none can free another app's memory either. What is " +
                    "here is real instead: fewer interruptions, nothing of this " +
                    "app's drawing over the game, and the frames the system spends " +
                    "animating between screens. The one lever that does change how " +
                    "the game renders belongs to adb, not to this app: " +
                    "`cmd game set --downscale 0.7 --fps 60 <package>`.",
            )
        }

        // ---- the free half: this app's own behaviour --------------------
        SettingRow(
            title = "Ignore the Essential Key",
            sub = "A key pressed by the hand holding the phone stops starting a " +
                "dictation over the game.",
        ) { EvSwitch(g.silenceKey) { on -> edit { copy(silenceKey = on) } } }
        Hairline()
        SettingRow(
            title = "Hide the island",
            sub = if (settings.island) {
                "It is a permanent overlay, and the game is what should be under " +
                    "your thumb. It comes back on its own afterwards."
            } else {
                "Nothing to hide while the island is switched off, but the setting " +
                    "is kept for when it is on."
            },
        ) { EvSwitch(g.hideIsland) { on -> edit { copy(hideIsland = on) } } }
        Hairline()
        SettingRow(
            title = "Silence notifications",
            sub = "Do Not Disturb, on priority — alarms and calls still come " +
                "through, because a mode that swallows your alarm costs more " +
                "than it saves.",
        ) { EvSwitch(g.silenceNotifications) { on -> edit { copy(silenceNotifications = on) } } }

        // ---- the half that belongs to the phone --------------------------
        Hairline()
        SettingRow(
            title = "Lock the rotation",
            sub = "A lean sideways stops flipping the screen.",
        ) { EvSwitch(g.lockRotation) { on -> edit { copy(lockRotation = on) } } }
        Hairline()
        SettingRow(
            title = "Quiet touches",
            sub = "Touch sounds and system haptics off, so the phone stops " +
                "answering the game's own feedback.",
        ) { EvSwitch(g.quietTouch) { on -> edit { copy(quietTouch = on) } } }
        Hairline()
        SettingRow(
            title = "No animations",
            sub = "All three animation scales to zero. Nothing while the game is " +
                "up — it buys the time either side, arming and alt-tabbing out " +
                "and back. The only one here that needs adb.",
        ) { EvSwitch(g.killAnimations) { on -> edit { copy(killAnimations = on) } } }

        // ---- what any of that still needs --------------------------------
        if (g.wantsSystemSettings && !setup.writeSystemSettings) {
            Hairline()
            PermissionRow(
                "Modify system settings",
                "The rotation and the touch sounds belong to the phone rather " +
                    "than to this app. Without this those two do nothing; " +
                    "everything above them still works.",
                false,
            ) { Setup.openWriteSettings(context) }
        }
        if (g.silenceNotifications && !setup.dndAccess) {
            Hairline()
            SettingRow(
                title = "Do Not Disturb access",
                sub = "Optional. The notification listener the island already uses " +
                    "can normally set this on its own — grant this only if " +
                    "notifications still come through with game mode on.",
                onClick = { Setup.openDndAccess(context) },
            ) { EvText("GRANT", type.button, color = EV.Ink) }
        }
        if (g.killAnimations && !setup.writeSecureSettings) {
            Hairline()
            AdbGrantRow(context)
        }
    }

    Spacer(Modifier.height(10.dp))

    // ---- turning itself on ----------------------------------------------
    Panel {
        SettingRow(
            title = "Turn on for games by itself",
            sub = "Arms when one of the apps below comes to the front, and " +
                "disarms a couple of seconds after you leave it. Off until asked " +
                "for: this is the one switch here that makes the app look at " +
                "which app is in front.",
            enabled = ready,
        ) {
            EvSwitch(g.autoArm, enabled = ready) { on ->
                if (on && !g.armForSeeded) {
                    // Seeded once, off the main thread — asking the package
                    // manager about every installed app is not a frame's work.
                    scope.launch {
                        val games = withContext(Dispatchers.IO) { GameApps.declaredGames(context) }
                        prefs.setGame(
                            g.copy(autoArm = true, armFor = games, armForSeeded = true),
                        )
                    }
                } else {
                    edit { copy(autoArm = on) }
                }
            }
        }
        if (g.autoArm) {
            Hairline()
            GameAppPicker(settings, prefs)
        }
    }
}

/**
 * The list of apps auto-arm watches for.
 *
 * Seeded from the store's own game category and then left alone — a list that
 * re-seeded itself would put back every app somebody had taken out of it. Games
 * are shown first and everything launchable is underneath them, because the
 * store gets it wrong often enough: an emulator is a tool, and half the games
 * ever published are filed as entertainment.
 *
 * The list is only read once it is opened, and then off the main thread. Asking
 * the package manager to resolve every launcher activity on the phone is tens of
 * milliseconds, which is nothing on a background thread and a visible stutter on
 * the one drawing the page.
 */
@Composable
private fun GameAppPicker(settings: Settings, prefs: Prefs) {
    val context = LocalContext.current
    val type = LocalEvType.current
    var open by rememberSaveable { mutableStateOf(false) }
    val g = settings.game

    SettingRow(
        title = "Apps it arms for",
        sub = if (g.armFor.isEmpty()) {
            "None yet. Open the list and tick what you play."
        } else {
            g.armFor.size.toString() + (if (g.armFor.size == 1) " app" else " apps") +
                ", starting from whatever the store calls a game."
        },
        onClick = { open = !open },
    ) { EvText(if (open) "CLOSE" else "EDIT", type.button, color = EV.Ink) }

    if (!open) return

    val apps by produceState(initialValue = emptyList<GameApps.Entry>()) {
        value = withContext(Dispatchers.IO) { GameApps.launchable(context) }
    }

    if (apps.isEmpty()) {
        Column(Modifier.padding(18.dp)) {
            EvText("Reading the app list…", type.sub)
        }
        return
    }

    // Capped in height and scrolled inside the card: a phone with two hundred
    // apps on it would otherwise turn this panel into the whole settings page.
    Column(
        Modifier
            .heightIn(max = 340.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        apps.forEach { entry ->
            Hairline()
            val ticked = entry.packageName in g.armFor
            SettingRow(
                title = entry.label,
                sub = if (entry.isGame) "Filed as a game" else entry.packageName,
                onClick = {
                    val next = if (ticked) g.armFor - entry.packageName
                    else g.armFor + entry.packageName
                    prefs.setGame(g.copy(armFor = next, armForSeeded = true))
                },
            ) {
                EvSwitch(ticked) { on ->
                    val next = if (on) g.armFor + entry.packageName
                    else g.armFor - entry.packageName
                    prefs.setGame(g.copy(armFor = next, armForSeeded = true))
                }
            }
        }
    }
}

/**
 * The one line of adb that turns the animation scales on.
 *
 * Shown rather than hidden, because this app is installed over adb in the first
 * place — the cable is already plugged in, and a permission that can only be
 * granted that way is not exotic here, it is one more command in the same
 * terminal. It is copyable because nobody should be typing a package name off a
 * phone screen.
 */
@Composable
private fun AdbGrantRow(context: Context) {
    val type = LocalEvType.current
    val command = "adb shell pm grant ${context.packageName} " +
        "android.permission.WRITE_SECURE_SETTINGS"

    Column(Modifier.padding(18.dp)) {
        EvText("No animations needs adb", type.body)
        Spacer(Modifier.height(4.dp))
        EvText(
            "Android will not grant this one by tapping, on any phone, for any app. " +
                "Run this once with the phone plugged in and it stays granted until " +
                "the app is uninstalled.",
            type.sub,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(EV.SurfaceSunk)
                .padding(14.dp),
        ) { EvText(command, type.mono, color = EV.InkMuted) }
        Spacer(Modifier.height(12.dp))
        EvButton("Copy the command", kind = EvButtonKind.Quiet) {
            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clip.setPrimaryClip(ClipData.newPlainText("adb", command))
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        }
    }
}

/** Same as [requestBudsTile], for the other tile. API 33 and up. */
private fun requestGameTile(context: Context) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
    val sbm = context.getSystemService(android.app.StatusBarManager::class.java) ?: return
    runCatching {
        sbm.requestAddTileService(
            android.content.ComponentName(context, GameTile::class.java),
            context.getString(com.ishaan.essentialvoice.R.string.game_tile_label),
            android.graphics.drawable.Icon.createWithResource(
                context,
                com.ishaan.essentialvoice.R.drawable.ic_game,
            ),
            { it.run() },
            { },
        )
    }
}

@Composable
private fun InfoDot(text: String) {
    val open = LocalInfoOpener.current
    val interaction = remember { MutableInteractionSource() }
    var anchor by remember { mutableStateOf(0f) }

    Box(
        Modifier
            .size(18.dp)
            .onGloballyPositioned { anchor = it.positionInRoot().y + it.size.height }
            .clip(CircleShape)
            .background(EV.SurfaceSunk)
            .clickable(interactionSource = interaction, indication = null) { open(text, anchor) },
        contentAlignment = Alignment.Center,
    ) {
        EvText("i", LocalEvType.current.label, color = EV.InkMuted)
    }
}

/**
 * The one question the app asks on the way in, once, ever.
 *
 * There is no store behind this app, so a new build has no way of announcing
 * itself except a notification — which makes this worth interrupting a first
 * launch for, and worth interrupting it exactly once. Answered either way it
 * never comes back, and the setting it turns on stays in Behaviour where it can
 * be turned off again.
 *
 * Made of the same glass as the floating bar rather than of a card, so it reads
 * as something laid over the page rather than as a page of its own. Tapping
 * outside it is a "not now" — a first-run question that traps you until you
 * answer it is not a question.
 */
@Composable
private fun NoticesInvite(
    backdrop: androidx.compose.ui.graphics.layer.GraphicsLayer,
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val type = LocalEvType.current
    val scrim = remember { MutableInteractionSource() }

    Box(
        Modifier
            .fillMaxSize()
            .background(EV.Ink.copy(alpha = 0.22f))
            // The scrim swallows taps rather than answering for you. It is a
            // yes-or-no question asked once and never asked again, and a tap
            // beside the card is not one of the two answers — it used to count
            // as "no", permanently, which is not what a stray tap means.
            .clickable(interactionSource = scrim, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        // Its own interaction source, and a click that does nothing: without it
        // a tap anywhere on the card falls through to the scrim behind.
        val swallow = remember { MutableInteractionSource() }
        Column(
            Modifier
                .padding(horizontal = EV.PagePadding)
                .glass(backdrop, EV.CornerCard)
                // A second wash over the frosted glass. The bar floats over a
                // few lines at a time; this card covers a whole paragraph, and
                // at the bar's opacity the blurred text underneath pooled into
                // grey smudges that read as drop shadows under the buttons.
                // Still translucent — the page is visible through it — just not
                // sheer enough to show what it is standing on.
                .background(EV.Glass.copy(alpha = 0.5f))
                .clickable(interactionSource = swallow, indication = null) {}
                .padding(22.dp),
        ) {
            EvText("Enable notifications?", type.title, color = EV.OnGlass)
            Spacer(Modifier.height(8.dp))
            EvText(
                "It reads one small file once per day and notifies you once per " +
                    "release.",
                type.sub,
                color = EV.OnGlass.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                EvButton("No", kind = EvButtonKind.Quiet, onClick = onDismiss)
                EvButton("Yes", Modifier.weight(1f), onClick = onAllow)
            }
        }
    }
}

/** The panel an [InfoDot] opens: the same glass as the bar, under the dot. */
@Composable
private fun InfoPanel(
    request: InfoRequest,
    hostTop: Float,
    backdrop: androidx.compose.ui.graphics.layer.GraphicsLayer,
    onDismiss: () -> Unit,
) {
    val dismiss = remember { MutableInteractionSource() }
    val density = LocalDensity.current
    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = dismiss, indication = null, onClick = onDismiss),
    ) {
        Box(
            Modifier
                .align(Alignment.TopStart)
                // The dot reports where it is in the window; this is drawn in
                // the page's box, which starts below the status bar.
                .offset {
                    androidx.compose.ui.unit.IntOffset(
                        0,
                        (request.anchorY - hostTop + with(density) { 10.dp.toPx() })
                            .toInt().coerceAtLeast(0),
                    )
                }
                .padding(horizontal = EV.PagePadding)
                .glass(backdrop, 18.dp)
                .padding(16.dp),
        ) {
            EvText(request.text, LocalEvType.current.mono, color = EV.OnGlass)
        }
    }
}

/**
 * There is no store behind this app, so checking for a new build is something
 * the app has to offer for itself.
 */
/**
 * Updates and what is in them, in one card.
 *
 * They were two things — a pill that opened a page, and a dropdown under it —
 * and they are one question: is there a newer build, and what is in it. The
 * check happens here rather than on a page of its own, because opening a screen
 * in order to press a button called "check" is a screen nobody wanted to be on.
 *
 * The top row *is* the button. It says what it is going to do until it has done
 * it, and afterwards it says what it found; when what it found is a new build,
 * the card grows a paragraph and a real button rather than sending you
 * somewhere. Everything below the hairline is the same What's new carousel as
 * before, one tap behind a heading instead of two.
 */
@Composable
private fun UpdatesCard(p: Panels, version: String, build: Int, modifier: Modifier = Modifier) {
    val type = LocalEvType.current
    val state = p.update
    val available = state as? Updater.State.Available

    var newsOpen by rememberSaveable { mutableStateOf(false) }
    val turn by animateFloatAsState(if (newsOpen) 90f else 0f, label = "whatsnew")

    var nextOpen by rememberSaveable { mutableStateOf(false) }
    val nextTurn by animateFloatAsState(if (nextOpen) 90f else 0f, label = "whatsnext")

    val checking = state is Updater.State.Checking
    val label = when (state) {
        is Updater.State.Available -> "Version ${state.release.versionName} is out"
        is Updater.State.Checking -> "Checking…"
        is Updater.State.UpToDate -> "You are on the newest build"
        is Updater.State.Failed -> "Could not check"
        Updater.State.Idle -> "Check for updates"
    }
    val ink = if (state is Updater.State.Failed) EV.Red else EV.Ink

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(
        if (pressed && !checking) EV.SurfaceSunk else EV.Surface,
        label = "updates",
    )

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(EV.CornerPill))
            .background(fill),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = !checking,
                    onClick = p.onCheckUpdate,
                )
                .padding(start = 20.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The weight is on the label rather than on the stamp. Both cannot
            // have it, and of the two the stamp is the one that must not be cut:
            // a clipped label still says "check for updates", while a clipped
            // build number says a different build.
            EvText(label, type.cardTitle, Modifier.weight(1f), color = ink, maxLines = 1)
            Spacer(Modifier.width(12.dp))
            EvText("v$version  ·  $build", type.stamp, maxLines = 1)
        }

        // Only a new build earns the height. Up to date, failed and idle all say
        // what they have to say in the row above.
        AnimatedVisibility(visible = available != null) {
            val release = available?.release
            Column(Modifier.padding(start = 20.dp, end = 18.dp, bottom = 18.dp)) {
                EvText(
                    release?.notes?.trim().orEmpty().ifBlank {
                        "A newer build is on the releases page. The download and the " +
                            "install are yours to make — this app cannot install itself."
                    },
                    type.sub,
                )
                Spacer(Modifier.height(14.dp))
                EvButton("Get the update", Modifier.fillMaxWidth()) {
                    release?.let(p.onGetUpdate)
                }
            }
        }

        if (state is Updater.State.Failed) {
            Column(Modifier.padding(start = 20.dp, end = 18.dp, bottom = 18.dp)) {
                EvText(state.message, type.sub, color = EV.Red)
            }
        }

        Hairline()

        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { newsOpen = !newsOpen }
                .padding(start = 20.dp, end = 18.dp, top = 15.dp, bottom = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EvText("What's new", type.cardTitle, Modifier.weight(1f), maxLines = 1)
            Spacer(Modifier.width(12.dp))
            Chevron(turn)
        }
        AnimatedVisibility(visible = newsOpen) {
            Column(Modifier.fillMaxWidth()) { WhatsNewCarousel(state) }
        }

        Hairline()

        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { nextOpen = !nextOpen }
                .padding(start = 20.dp, end = 18.dp, top = 15.dp, bottom = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EvText("What's next?", type.cardTitle, Modifier.weight(1f), maxLines = 1)
            Spacer(Modifier.width(12.dp))
            Chevron(nextTurn)
        }
        AnimatedVisibility(visible = nextOpen) { WhatsNextList() }
    }
}

/**
 * What is being built, under the card that says what has already shipped.
 *
 * It sits in the updates card rather than anywhere else because it is the same
 * question one line further on: this build, the next build, and then the rest
 * of it. Somebody who has just read what changed is exactly the person who
 * wants to know what is coming.
 *
 * A plain numbered list, and no dates on any of it — the list says what is
 * being built, and a date on any line of it would be a promise this app is in
 * no position to make.
 */
@Composable
private fun WhatsNextList() {
    val type = LocalEvType.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 18.dp, bottom = 18.dp),
    ) {
        EvText("BEING BUILT", type.labelSans)
        Spacer(Modifier.height(14.dp))
        WHATS_NEXT.forEachIndexed { i, item ->
            if (i > 0) Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The number in mono and the thing in Geist, which is the same
                // pairing the launcher's cards use for a count and a heading.
                EvText("%02d".format(i + 1), type.tag, Modifier.width(32.dp), maxLines = 1)
                EvText(item, type.body, maxLines = 1)
            }
        }
        Spacer(Modifier.height(16.dp))
        EvText("…and so much more.", type.body)
    }
}

/**
 * The list itself. Order is roughly how close each one is rather than how big
 * it is, so the top of the list is the part that is nearly here.
 */
private val WHATS_NEXT = listOf(
    "Language integration",
    "Game mode",
    "Dynamic island",
    "Gemini integration",
    "Phone back tap gesture",
)

/**
 * What changed, in a handful of lines.
 *
 * Which build it describes depends on what the check found. If there is a newer
 * one, this is a look at what you would be getting; if there is not, it is the
 * receipt for what you already have. The heading always names the version, so
 * the two are never mistaken for each other.
 *
 * Pictures only ever come from the manifest — see [WhatsNew] for why the built-in
 * list cannot have them.
 */
@Composable
private fun WhatsNewCarousel(state: Updater.State) {
    val type = LocalEvType.current
    val context = LocalContext.current
    val installed = remember { Updater.installedVersionName(context) }

    val release = when (state) {
        is Updater.State.Available -> state.release
        is Updater.State.UpToDate -> state.release
        else -> null
    }

    // An update that says nothing about itself still has its one-line `notes`,
    // which is better than an empty section.
    val remote = release?.whatsNew.orEmpty().ifEmpty {
        val notes = release?.notes?.trim().orEmpty()
        if (notes.isBlank()) emptyList()
        else listOf(WhatsNew.Item("In this release", notes))
    }

    val available = state is Updater.State.Available
    val items = when {
        available -> remote
        // Up to date: the manifest is describing the build already installed,
        // so prefer it — it is the only version of the list with pictures.
        remote.isNotEmpty() && release?.versionName == installed -> remote
        else -> WhatsNew.local
    }

    // The heading is a line inside the carousel now rather than the dropdown's
    // own title: the row above already says "What's new", and which build it is
    // describing is the part that was doing the work.
    val version = if (available) release?.versionName ?: "?" else installed
    Column(Modifier.fillMaxWidth()) {
        EvText(
            if (available) "IN VERSION $version" else "VERSION $version",
            type.labelSans,
            Modifier.padding(start = 20.dp, end = 18.dp),
        )
        Spacer(Modifier.height(12.dp))

        if (items.isEmpty()) {
            Column(Modifier.padding(start = 20.dp, end = 18.dp, bottom = 18.dp)) {
                EvText("Nothing recorded for this build.", type.sub)
            }
            return@Column
        }

        // One card per entry, side by side.
        //
        // Not a lazy row: there are only ever a handful of these, and a lazy
        // layout cannot measure intrinsics — which is exactly what makes every
        // card here the height of the tallest one instead of a ragged edge.
        //
        // The inset is padding *inside* the scroller, so the first and last
        // cards sit level with the heading and the row still scrolls edge to
        // edge rather than stopping short of it.
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .height(IntrinsicSize.Max)
                .padding(start = 20.dp, end = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items.forEach { item ->
                WhatsNewCard(item, Modifier.fillMaxHeight())
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

/**
 * A heading that opens to show what is under it.
 *
 * Used for the things that are only relevant to some people: shown closed, they
 * cost one line instead of a screen, and nobody who does not need them has to
 * scroll past them.
 */
@Composable
internal fun Disclosure(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val type = LocalEvType.current
    var open by rememberSaveable(title) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val rotation by animateFloatAsState(if (open) 90f else 0f, label = "chevron")

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (pressed) EV.SurfaceSunk else Color.Transparent)
                .clickable(interactionSource = interaction, indication = null) { open = !open }
                .padding(horizontal = 18.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EvText(title, type.body, Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Chevron(rotation)
        }
        if (open) content()
    }
}

/** Points right when closed and down when open. The one in the app. */
@Composable
private fun Chevron(rotation: Float) {
    Canvas(Modifier.size(10.dp).rotate(rotation)) {
        val w = 1.6.dp.toPx()
        drawLine(
            EV.InkMuted, Offset(size.width * 0.25f, 0f),
            Offset(size.width * 0.75f, size.height / 2f), w, StrokeCap.Round,
        )
        drawLine(
            EV.InkMuted, Offset(size.width * 0.75f, size.height / 2f),
            Offset(size.width * 0.25f, size.height), w, StrokeCap.Round,
        )
    }
}

@Composable
private fun WhatsNewCard(item: WhatsNew.Item, modifier: Modifier = Modifier) {
    val type = LocalEvType.current

    Column(
        modifier
            // Narrow enough that the next card shows at the edge of the screen,
            // which is the only thing telling anyone the row scrolls.
            .width(248.dp)
            .clip(RoundedCornerShape(EV.CornerRow))
            .background(EV.Surface)
            .padding(14.dp),
    ) {
        if (item.image != null) {
            NetImageBox(
                item.image,
                Modifier
                    .fillMaxWidth()
                    .height(132.dp),
            )
            Spacer(Modifier.height(12.dp))
        }
        if (item.title.isNotBlank()) {
            EvText(item.title, type.body)
        }
        if (item.body.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            EvText(item.body, type.sub)
        }
    }
}

/**
 * One note in the list. Tapping it opens it.
 *
 * It used to expand in place and grow a Delete button, which was two things a
 * tap could mean and no way to fix a typo. Now a tap always does the one
 * obvious thing — the same thing a tap on the home screen widget does — and
 * reading, editing and deleting all happen on the note's own screen.
 */
@Composable
private fun NoteRow(note: NoteStore.Note, onOpen: () -> Unit) {
    val type = LocalEvType.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Column(
        Modifier
            .fillMaxWidth()
            .background(if (pressed) EV.SurfaceSunk else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .padding(horizontal = 18.dp, vertical = 15.dp),
    ) {
        EvText(note.title.ifBlank { "Empty note" }, type.body, maxLines = 2)
        Spacer(Modifier.height(4.dp))
        EvText(NoteStore.whenLabel(note.createdAt), type.label, color = EV.InkFaint)
    }
}

/**
 * A task: the same row as a note, with a circle in front of it.
 *
 * The circle is its own target rather than the row being a toggle. A task is
 * still a thing you wrote down and might want to correct, so the row has to keep
 * meaning "open this" — and a list where tapping anywhere ticks something off is
 * a list you cannot read without changing.
 *
 * Ticked-off tasks stay where they are. Sinking them to the bottom would be a
 * second ordering laid over the one the list already has, and the point of
 * ticking one off is to see it done in the place you remember putting it.
 */
@Composable
private fun TaskRow(note: NoteStore.Note, onOpen: () -> Unit) {
    val type = LocalEvType.current
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Row(
        Modifier
            .fillMaxWidth()
            .background(if (pressed) EV.SurfaceSunk else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TaskTick(note.done) { NoteStore.setDone(context, note.id, !note.done) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            EvText(
                note.title.ifBlank { "Empty task" },
                if (note.done) {
                    type.body.copy(
                        color = EV.InkFaint,
                        textDecoration = TextDecoration.LineThrough,
                    )
                } else {
                    type.body
                },
                maxLines = 2,
            )
            Spacer(Modifier.height(4.dp))
            EvText(NoteStore.whenLabel(note.createdAt), type.label, color = EV.InkFaint)
        }
    }
}

/**
 * The circle you tap to tick a task off.
 *
 * Filled when done, with the fill's own colour punched out of it as a check.
 * Drawn rather than assembled from a border and an icon, because an outlined
 * ring is the one thing this app's kit does not do — nothing here is outlined —
 * so the empty state is a ring of ink at low alpha, which is a fill.
 */
@Composable
private fun TaskTick(done: Boolean, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(
        when {
            done -> EV.Ink
            pressed -> EV.InkFaint
            else -> EV.SurfaceSunk
        },
        label = "tick",
    )
    Box(
        Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(fill)
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            Canvas(Modifier.size(13.dp)) {
                val w = 2.dp.toPx()
                val ink = EV.OnInk
                drawLine(
                    ink,
                    Offset(size.width * 0.08f, size.height * 0.54f),
                    Offset(size.width * 0.40f, size.height * 0.84f),
                    w, StrokeCap.Round,
                )
                drawLine(
                    ink,
                    Offset(size.width * 0.40f, size.height * 0.84f),
                    Offset(size.width * 0.94f, size.height * 0.18f),
                    w, StrokeCap.Round,
                )
            }
        }
    }
}

/**
 * A recording: a disc to press, the clip's own picture, and its words once they
 * have been found.
 *
 * The waveform doubles as the progress bar, exactly as it does on the card the
 * pill grows into — bars behind the playhead at full strength, bars ahead of it
 * faded. Two surfaces drawing the same clip the same way is the point; the row
 * and the card are the same object seen from two places, and [Playback] is
 * shared between them, so a clip started here pauses from there.
 */
@Composable
private fun RecordingRow(note: NoteStore.Note, onOpen: () -> Unit) {
    val type = LocalEvType.current
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val playback by Playback.state.collectAsState()
    val mine = playback?.takeIf { it.id == note.id }
    val working by Transcribe.working.collectAsState()
    val needsModel by Transcribe.needsModel.collectAsState()

    Row(
        Modifier
            .fillMaxWidth()
            .background(if (pressed) EV.SurfaceSunk else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayDisc(playing = mine?.playing == true) { Playback.toggle(context, note) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            WaveBars(
                wave = note.wave,
                progress = mine?.fraction ?: 0f,
                modifier = Modifier.fillMaxWidth().height(28.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                EvText(
                    NoteStore.clock(mine?.positionMs?.toLong() ?: note.durationMs),
                    type.label,
                    color = EV.InkMuted,
                )
                Spacer(Modifier.width(10.dp))
                EvText(
                    NoteStore.whenLabel(note.createdAt),
                    type.label,
                    color = EV.InkFaint,
                    maxLines = 1,
                )
            }
            // The words, once there are any. A clip still waiting says so
            // rather than showing a blank line, because an empty second line
            // under a waveform reads as a recording that failed.
            val words = note.text.trim()
            if (words.isNotEmpty() || !note.transcribed) {
                Spacer(Modifier.height(7.dp))
                EvText(
                    when {
                        words.isNotEmpty() -> words
                        working == note.id -> "Finding the words\u2026"
                        // Reading a clip needs the offline model whatever the
                        // dictation engine is set to, so this is worth naming
                        // rather than leaving as an indefinite wait.
                        needsModel -> "Download a model to read this"
                        else -> "Waiting to be read"
                    },
                    if (words.isNotEmpty()) type.sub else type.sub.copy(color = EV.InkFaint),
                    maxLines = 2,
                )
            }
        }
    }
}

/** Play, or pause if this is the clip that is running. */
@Composable
private fun PlayDisc(playing: Boolean, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(if (pressed) EV.CtaSunk else EV.Cta, label = "play")
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(fill)
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(15.dp)) {
            val ink = EV.OnCta
            if (playing) {
                val w = 3.5.dp.toPx()
                val gap = 3.5.dp.toPx()
                val h = size.height * 0.92f
                val top = (size.height - h) / 2f
                drawRoundRect(
                    ink,
                    topLeft = Offset(size.width / 2f - gap / 2f - w, top),
                    size = androidx.compose.ui.geometry.Size(w, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2f),
                )
                drawRoundRect(
                    ink,
                    topLeft = Offset(size.width / 2f + gap / 2f, top),
                    size = androidx.compose.ui.geometry.Size(w, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2f),
                )
            } else {
                // Nudged right, because a triangle centred on its bounding box
                // always looks left of centre inside a circle.
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * 0.14f, 0f)
                    lineTo(size.width, size.height / 2f)
                    lineTo(size.width * 0.14f, size.height)
                    close()
                }
                drawPath(path, ink)
            }
        }
    }
}

/**
 * A clip's picture: one bar per stored peak, or a stride over them when the row
 * is narrower than the picture is detailed.
 */
@Composable
private fun WaveBars(
    wave: List<Int>,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val ink = EV.Ink
    Canvas(modifier) {
        if (wave.isEmpty()) return@Canvas
        val step = 4.dp.toPx()
        val barW = 2.5.dp.toPx()
        val minH = 2.dp.toPx()
        val bars = (size.width / step).toInt().coerceAtLeast(1)
        val head = size.width * progress.coerceIn(0f, 1f)
        for (i in 0 until bars) {
            val peak = wave[(i.toFloat() / bars * wave.size).toInt().coerceIn(0, wave.size - 1)]
            val h = (minH + (size.height - minH) * (peak / 100f)).coerceAtLeast(minH)
            val x = i * step
            drawRoundRect(
                color = ink.copy(alpha = if (x + barW / 2f <= head) 0.95f else 0.30f),
                topLeft = Offset(x, (size.height - h) / 2f),
                size = androidx.compose.ui.geometry.Size(barW, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f),
            )
        }
    }
}

/**
 * The plea itself, without the card around it.
 *
 * Split out so the launcher's "I need your help" row can show exactly the same
 * words: the row draws its own red fill, and a [Panel] inside it would have been
 * a second red rectangle inside the first.
 */
@Composable
private fun PlayStoreBody() {
    val type = LocalEvType.current
    val context = LocalContext.current

    Column(Modifier.padding(18.dp)) {
            EvText("Twelve people, fourteen days", type.title, color = EV.Red)
            Spacer(Modifier.height(6.dp))
            EvText(
                "I want this app on the Play Store, so that installing it is a " +
                    "button rather than a trip to GitHub and a fight with Play " +
                    "Protect. Google will not publish a new developer's app until " +
                    "twelve people have kept it installed and opened it every day " +
                    "for fourteen days straight. That is the whole hold-up.",
                type.sub,
            )
            Spacer(Modifier.height(10.dp))
            EvText(
                "If you are going to use this anyway, that is all it takes — " +
                    "email me and I will add you to the test.",
                type.sub,
            )
            Spacer(Modifier.height(14.dp))
            EvButton("Count me in", Modifier.fillMaxWidth()) {
                composeEmail(
                    context,
                    "Essential Voice — I'll test for 14 days",
                    "I'll keep Essential Voice installed and open it every day for " +
                        "fourteen days. My Google account is:",
                )
            }

            Spacer(Modifier.height(18.dp))
            Hairline(inset = 0.dp)
            Spacer(Modifier.height(18.dp))

            EvText("Or, the shortcut", type.title, color = EV.Red)
            Spacer(Modifier.height(6.dp))
            EvText(
                "The fourteen-day rule only applies to Play Console accounts made " +
                    "after November 2023. If you have one from before then and are " +
                    "willing to hand it over, that skips the whole thing and the app " +
                    "goes up in days. Email me about that too.",
                type.sub,
            )
            Spacer(Modifier.height(14.dp))
            EvButton("I have an older Console", Modifier.fillMaxWidth(), kind = EvButtonKind.Quiet) {
                composeEmail(
                    context,
                    "Essential Voice — Play Console account",
                    "I have a Google Play Console account created before November " +
                        "2023. It was made on:",
                )
            }
    }
}

/**
 * A box to type an idea into, and a button that hands it to a mail app.
 *
 * There is no server behind this app and adding one for a suggestion box would
 * mean running something, paying for it, and holding other people's messages.
 * So this composes an email instead: the text goes into a draft addressed to
 * me, in whatever mail app the phone already has, and the person sending it can
 * see exactly what is being sent and press send themselves. Nothing leaves the
 * phone until they do.
 *
 * Which also means this cannot fail silently in the way a form post can — if no
 * mail app answers, the text goes to the clipboard rather than nowhere.
 */
@Composable
private fun FeedbackPanel() {
    val type = LocalEvType.current
    val context = LocalContext.current
    var idea by rememberSaveable { mutableStateOf("") }
    val canSend = idea.isNotBlank()

    Panel {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                EvText("Got any ideas or improvements?", type.title, Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                InfoDot(
                    "The build number and phone model are added at the bottom of the " +
                        "draft so I know what I am looking at — delete them if you " +
                        "would rather not.",
                )
            }
            Spacer(Modifier.height(4.dp))
            EvText(
                "I can make them possible. Write it here and it opens an email to " +
                    "me — you send it, so you can see exactly what goes.",
                type.sub,
            )
            Spacer(Modifier.height(14.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 116.dp)
                    .clip(RoundedCornerShape(EV.CornerRow))
                    .background(EV.Background)
                    .padding(14.dp),
            ) {
                if (idea.isEmpty()) {
                    EvText("Something that would make this better…", type.body, color = EV.InkFaint)
                }
                BasicTextField(
                    value = idea,
                    onValueChange = { idea = it },
                    // Has to be as tall as the box it is drawn in. Sized to its
                    // own one line of text it looked like a text area and
                    // behaved like a single line: a tap anywhere below the
                    // first line missed it and nothing focused.
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 88.dp),
                    textStyle = type.body,
                    cursorBrush = SolidColor(EV.Ink),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            }

            Spacer(Modifier.height(14.dp))
            EvButton("Send it to me", Modifier.fillMaxWidth(), enabled = canSend) {
                if (composeEmail(context, "Essential Voice \u2014 an idea", idea)) idea = ""
            }
        }
    }
}

/**
 * The Gemini key, and the one sentence explaining what saying "Gemini" does.
 *
 * This is the only setting in the app that turns on a **network** path for
 * something the user said, so it says so in plain words rather than being a
 * switch labelled "Gemini". Everything else here runs on the phone, and that is
 * worth not blurring.
 *
 * The field is masked and typed as a password: not because a shoulder is the
 * real threat, but because a password keyboard is the one that will not
 * autocapitalise or autocorrect, and either of those silently corrupts an API
 * key into something that fails with a rejection the user cannot explain.
 */
@Composable
private fun GeminiRow(settings: Settings, prefs: Prefs) {
    val type = LocalEvType.current
    // Seeded from the saved key and owned by the field afterwards. Keyed on the
    // saved value so that clearing it elsewhere is reflected here.
    var draft by remember(settings.geminiKey) { mutableStateOf(settings.geminiKey) }
    val dirty = draft.trim() != settings.geminiKey

    Column(Modifier.padding(18.dp)) {
        EvText("Ask Gemini", type.body)
        Spacer(Modifier.height(4.dp))
        EvText(
            if (settings.geminiKey.isBlank()) {
                "Start a dictation with the word \u201cGemini\u201d and the rest is a " +
                    "question. Needs a key from Google AI Studio \u2014 it is the only " +
                    "thing in this app that sends what you said off the phone."
            } else {
                "Say \u201cGemini\u201d and then the question. The answer appears on " +
                    "the island; tap it to paste. Nothing else here leaves the phone."
            },
            type.sub,
        )
        Spacer(Modifier.height(14.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(EV.CornerRow))
                .background(EV.Background)
                .padding(14.dp),
        ) {
            if (draft.isEmpty()) {
                EvText("Paste your Gemini API key\u2026", type.body, color = EV.InkFaint)
            }
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = type.body,
                singleLine = true,
                cursorBrush = SolidColor(EV.Ink),
                visualTransformation = PasswordVisualTransformation(),
                // Password, so the keyboard will not "correct" the key.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            EvButton(
                if (settings.geminiKey.isBlank()) "Save key" else "Update key",
                Modifier.weight(1f),
                enabled = dirty && draft.isNotBlank(),
            ) { prefs.setGeminiKey(draft) }
            if (settings.geminiKey.isNotBlank()) {
                Spacer(Modifier.width(10.dp))
                EvButton("Remove", kind = EvButtonKind.Danger) {
                    draft = ""
                    prefs.setGeminiKey("")
                }
            }
        }
    }
}

private const val FEEDBACK_EMAIL = "email2ishaanpatel@gmail.com"

/**
 * Opens a mail draft with [lead] already in it. Returns true if something took it.
 *
 * The subject and body go **in the mailto URI**, not in EXTRA_SUBJECT and
 * EXTRA_TEXT. With ACTION_SENDTO the extras are advisory and Gmail drops them,
 * which is why the first version of this opened a correctly addressed but
 * completely empty draft. The query string is the part every mail client reads.
 * The extras are set as well, for the ones that only read those.
 *
 * `resolveActivity` is not used to check first: that needs a `<queries>` entry
 * for package visibility, and trying and catching answers the same question
 * without one.
 *
 * The build and the phone are appended to every one of these. Three different
 * things in the app open a draft now — an idea, and the two Play Store asks —
 * and none of them is worth answering without knowing which build is talking.
 */
private fun composeEmail(context: Context, subject: String, lead: String): Boolean {
    val version = runCatching { Updater.installedVersionName(context) }.getOrDefault("?")
    val code = runCatching { Updater.installedVersionCode(context) }.getOrDefault(0)
    val body = buildString {
        append(lead.trim())
        append("\n\n—\n")
        append("Essential Voice $version (build $code)\n")
        append("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, ")
        append("Android ${android.os.Build.VERSION.RELEASE}")
    }

    // Uri.encode, not Uri.Builder: the builder would encode the "?" and "&" of
    // the query itself and hand the mail app one long address.
    val uri = Uri.parse(
        "mailto:" + Uri.encode(FEEDBACK_EMAIL) +
            "?subject=" + Uri.encode(subject) +
            "&body=" + Uri.encode(body),
    )

    val mail = Intent(Intent.ACTION_SENDTO, uri)
        .putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, body)

    return runCatching {
        context.startActivity(mail)
        true
    }.getOrElse {
        // No mail app. Losing what someone just typed is the one unacceptable
        // outcome, so it goes somewhere they can paste it from.
        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText(subject, body))
        Toast.makeText(
            context,
            "No email app here — it is on your clipboard. Send it to $FEEDBACK_EMAIL.",
            Toast.LENGTH_LONG,
        ).show()
        false
    }
}

/**
 * The only link in the app that asks for anything.
 *
 * It sits at the bottom, it is a link rather than a purchase, and nothing in the
 * app is behind it — the whole thing works the same whether or not anyone ever
 * presses it. That is the deal, so it is worth saying out loud here.
 */
@Composable
private fun SupportPanel() {
    val type = LocalEvType.current
    val context = LocalContext.current
    val open = { openLink(context, PAYPAL_URL) }

    Panel {
        Column(Modifier.padding(18.dp)) {
            EvText(
                "This is free, has no account, no ads and no analytics, and it is " +
                    "going to stay that way.",
                type.body,
            )
            Spacer(Modifier.height(4.dp))
            EvText(
                "If it has saved you some typing, you can send something over. " +
                    "Nothing changes in the app either way.",
                type.sub,
            )
            Spacer(Modifier.height(14.dp))
            EvButton("Support me on PayPal", Modifier.fillMaxWidth(), onClick = open)
            Spacer(Modifier.height(8.dp))
            EvText("paypal.me/ishaanpatel19", type.mono, Modifier.padding(start = 4.dp))
        }

        UpiBlock()

        Hairline()

        Column(Modifier.padding(18.dp)) {
            EvText("THE FUND", type.label, color = EV.Ink)
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(230.dp)
                    // The card's own fill, not a sunk tile: the photo has a
                    // near-white background of its own, and anything darker
                    // behind it shows as a rectangle around the headphones.
                    .background(EV.Surface),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.headphone_one),
                    contentDescription = "Nothing Headphone (1)",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            Spacer(Modifier.height(14.dp))
            EvText("Help me buy the Nothing Headphone (1)", type.body)
            Spacer(Modifier.height(4.dp))
            EvText(
                "This is what the tip jar is actually for. I have been listening " +
                    "to my own voice through the phone speaker for weeks.",
                type.sub,
            )

            Spacer(Modifier.height(16.dp))
            // The bar is the end of it. There was a second button under this —
            // the same PayPal link as the one at the top of the card, under a
            // different name — so the card asked twice and the fund read as a
            // separate thing to buy rather than as what the tip jar is for.
            FundProgress(raised = FUND_RAISED, target = FUND_TARGET)
        }
    }
}

/**
 * The same offer, for a phone in India.
 *
 * PayPal is the only other way in and it is the wrong one here: everybody this
 * app is actually shown to has three UPI apps on the phone already, and none of
 * them takes a PayPal link.
 *
 * One word and one button. The pass before this one had a heading, two lines
 * explaining what the button would do, and a QR code under them — all of it
 * arguing a case the paragraph three lines above had already made, for a button
 * whose label says the whole thing. The QR went with the words for a reason of
 * its own: a phone cannot scan its own screen, so it was only ever for somebody
 * reading this page on one device and paying from another, which is not what
 * this page is.
 *
 * The intent carries no amount, so the UPI app opens on its own "how much?"
 * screen rather than on a number this app picked.
 */
@Composable
private fun UpiBlock() {
    val type = LocalEvType.current
    val context = LocalContext.current

    Hairline()
    Column(Modifier.padding(18.dp)) {
        // Not a heading for a section — a hinge between two buttons that do the
        // same thing. It is the only word here because it is the only word
        // needed: everything above it applies to this button too.
        EvText("OR", type.label, color = EV.Ink)
        Spacer(Modifier.height(12.dp))
        EvButton("Support me by UPI", Modifier.fillMaxWidth()) { openUpi(context) }
        Spacer(Modifier.height(8.dp))
        EvText(UPI_ID, type.mono, Modifier.padding(start = 4.dp))
    }
}

/**
 * Hands the payment to whatever UPI app is installed.
 *
 * A chooser rather than a plain view: several of these apps claim the scheme and
 * the phone's default is rarely the one somebody wants to pay from. No amount
 * and no note — this app does not get to decide either.
 */
private fun openUpi(context: Context) {
    val uri = Uri.parse(
        "upi://pay?pa=" + Uri.encode(UPI_ID) +
            "&pn=" + Uri.encode(UPI_NAME) +
            "&cu=INR",
    )
    val ok = runCatching {
        context.startActivity(
            Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), "Pay with")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
    if (ok) return
    // No UPI app on this phone. The id is the whole of what is needed, so it
    // goes on the clipboard rather than nowhere.
    runCatching {
        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("UPI id", UPI_ID))
    }
    Toast.makeText(context, "No UPI app here — the id is on your clipboard.", Toast.LENGTH_LONG)
        .show()
}

/**
 * Where the money goes, and the name it goes to.
 *
 * Both are Ishaan's own, given directly rather than read off the QR code that
 * used to sit under them — which is also why removing that picture cost nothing:
 * the id was never derived from it.
 */
private const val UPI_ID = "email2ishaanpatel@okicici"
private const val UPI_NAME = "Ishaan Patel"

/** What has come in, against what the headphones cost. */
@Composable
private fun FundProgress(raised: Int, target: Int) {
    val type = LocalEvType.current
    val fraction = (raised.toFloat() / target).coerceIn(0f, 1f)

    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            EvText(rupees(raised), type.title, Modifier.weight(1f), color = EV.Ink)
            EvText("of ${rupees(target)}", type.mono)
        }
        Spacer(Modifier.height(10.dp))
        // A track and a fill, both flat, both the same shape. The yellow is
        // doing the only job it ever does here: saying which part is the
        // answer.
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(EV.SurfaceSunk),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(5.dp))
                    .background(EV.Yellow),
            )
        }
        Spacer(Modifier.height(8.dp))
        EvText("${(fraction * 100).toInt()}% OF THE WAY THERE", type.label)
    }
}

/** 19999 as "Rs 19,999" — Indian grouping, no library. */
private fun rupees(amount: Int): String {
    val digits = amount.toString()
    if (digits.length <= 3) return "Rs $digits"
    val head = digits.dropLast(3)
    val tail = digits.takeLast(3)
    val grouped = head.reversed().chunked(2).joinToString(",").reversed()
    return "Rs $grouped,$tail"
}

/** What the headphones cost, and what has come in so far. */
private const val FUND_TARGET = 19_999
private const val FUND_RAISED = 1_290

private const val PAYPAL_URL = "https://paypal.me/ishaanpatel19"

/** Hands a URL to a browser. Nothing in this app opens a payment page itself. */
/**
 * The last thing on the page, and the only one that costs nothing to say yes to.
 *
 * Three names and a share sheet, and nothing else on the card. The pass before
 * this one argued the case in two paragraphs above the buttons and printed the
 * link under them; at the foot of a page that has already asked for a rating,
 * an idea and a tip, a fourth paragraph is the one nobody reads. The section
 * heading says what the buttons are for, which is as much as the buttons need.
 *
 * The logos went with the words. They were somebody else's marks drawn in this
 * app's ink, and three of them stacked over three names said the same thing
 * twice — the name is the half that is unambiguous.
 *
 * Reddit and Twitter both take a link and a title in the URL, so those two open
 * a post that is already written. Instagram takes nothing — there is no web
 * intent for it — so that one copies the link and says so, which is the honest
 * version of the same button.
 */
@Composable
private fun SpreadPanel() {
    val context = LocalContext.current

    Panel {
        Column(Modifier.padding(18.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SpreadTile(
                    label = "Reddit",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        openLink(
                            context,
                            "https://www.reddit.com/submit?title=" + Uri.encode(SHARE_TITLE) +
                                "&url=" + Uri.encode(SHARE_URL),
                        )
                    },
                )
                SpreadTile(
                    label = "Twitter",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        openLink(
                            context,
                            "https://twitter.com/intent/tweet?text=" + Uri.encode(SHARE_TEXT) +
                                "&url=" + Uri.encode(SHARE_URL),
                        )
                    },
                )
                SpreadTile(
                    label = "Instagram",
                    modifier = Modifier.weight(1f),
                    onClick = { openInstagram(context) },
                )
            }

            Spacer(Modifier.height(10.dp))
            EvButton("Share the app", Modifier.fillMaxWidth()) { shareApp(context) }
        }
    }
}

/**
 * One of the three: a word, and a fill that changes on press and does not move
 * — the same deal every other control in the app makes.
 *
 * Shorter than it was now that the mark above the word has gone: a 78dp tile
 * holding one line of text is a tile with a hole in it.
 */
@Composable
private fun SpreadTile(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val type = LocalEvType.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(
        if (pressed) EV.SurfaceSunk else EV.Background,
        label = "spread",
    )

    Box(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(EV.CornerButton))
            .background(fill)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Tighter than [EvTypography.label] on both counts, because this one
        // has to hold "INSTAGRAM" inside a third of the card. At the page's own
        // 11sp and 1.6 of tracking it fills the tile edge to edge on this
        // phone, which means it is over the edge on a narrower one.
        EvText(
            label.uppercase(),
            type.label.copy(fontSize = 10.sp, letterSpacing = 0.9.sp),
            maxLines = 1,
        )
    }
}

/**
 * Instagram, which cannot be handed a link.
 *
 * Nothing on Instagram accepts a URL from outside — not the app, not the web
 * site — so the button does the two halves by hand: the link goes on the
 * clipboard, the toast says that it has, and then Instagram opens for the
 * paste. The clipboard write happens first, so a phone with no Instagram on it
 * still leaves you holding the link.
 */
private fun openInstagram(context: Context) {
    runCatching {
        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("Essential Voice", SHARE_URL))
    }
    Toast.makeText(context, "Link copied — paste it into your story.", Toast.LENGTH_LONG).show()
    openLink(context, "https://www.instagram.com/")
}

/** The system share sheet, with the link and one line about what it is. */
private fun shareApp(context: Context) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, SHARE_TITLE)
        .putExtra(Intent.EXTRA_TEXT, "$SHARE_TEXT\n$SHARE_URL")
    runCatching {
        context.startActivity(
            Intent.createChooser(send, "Share Essential Voice")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Where the app lives, and what to say about it.
 *
 * The repository rather than any other page, because it is the one address that
 * has never moved and the one that carries both the download and the source.
 */
private const val SHARE_URL = "https://github.com/email2ishaanpatel-collab/essential-voice"
private const val SHARE_TITLE = "Essential Voice — hold the key, talk, and your phone types it"
private const val SHARE_TEXT =
    "Hold the Essential Key and talk, and your phone types what you said. " +
        "It runs on the phone, it is free, and there is no account."

private fun openLink(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
private fun Masthead(setup: SetupState, settings: Settings, prefs: Prefs) {
    val type = LocalEvType.current
    Column(Modifier.fillMaxWidth().padding(top = 46.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // The mark sits on the page now rather than on a tile of its own.
            // The tile was there to give a 28dp glyph some presence; at 64dp
            // in a 64dp box the dots have their own, and the disc behind them
            // was reading as a button you could press.
            //
            // Held to the height of the *name*, not of the name and the strap
            // together. Centred against the whole two-line column the 64dp box
            // put the dots ten dp below the word they belong to, which is
            // exactly far enough to read as a mistake. The box is squeezed to
            // the masthead's own line height, so the glyph inside it centres on
            // that line by construction rather than by a number that has to be
            // re-guessed whenever the type changes.
            val nameLine = with(LocalDensity.current) { type.masthead.lineHeight.toDp() }
            Box(Modifier.height(nameLine), contentAlignment = Alignment.Center) {
                AppMark(spin = true)
            }
            // No gap. The mark is 26dp of dots centred in its box, so the
            // nineteen dp of air the drawings leave beside it is already inside
            // the box — adding more would push the name off on its own.
            Spacer(Modifier.width(0.dp))
            Column(Modifier.weight(1f)) {
                EvText("Essential", type.masthead, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                EvText("Software for all", type.strap)
            }
            Spacer(Modifier.width(10.dp))
            ThemeToggle(prefs)
        }
    }
}

/**
 * Light or dark, in one tap, with no words on it.
 *
 * It shows the palette you are *in* — a sun while the page is light — rather
 * than the one you would get, which is the older and more common convention and
 * the one people read correctly without thinking about it.
 */
@Composable
private fun ThemeToggle(prefs: Prefs) {
    // What the page is actually showing, not what the setting says: with the
    // setting still on "system" the toggle has to reflect the phone.
    val dark = EV.isDark
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill = if (pressed) EV.SurfaceSunk else EV.Surface

    Box(
        Modifier
            .size(38.dp)
            // Square, with the app's button radius rather than a circle: it is
            // a control, and every other control here is a rounded rectangle.
            .clip(RoundedCornerShape(EV.CornerButton))
            .background(fill)
            .clickable(interactionSource = interaction, indication = null) {
                prefs.setTheme(if (dark) Prefs.THEME_LIGHT else Prefs.THEME_DARK)
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(17.dp)) {
            val ink = EV.Ink
            val r = size.minDimension / 2f
            if (dark) {
                // A crescent, cut by drawing the tile's own colour back over a
                // full circle — the same trick a paper cut-out uses, and the
                // only way to get a crescent without a path library.
                drawCircle(ink, radius = r * 0.95f, center = Offset(r, r))
                drawCircle(fill, radius = r * 0.82f, center = Offset(r * 1.5f, r * 0.62f))
            } else {
                drawCircle(ink, radius = r * 0.55f, center = Offset(r, r))
                repeat(8) { i ->
                    val a = (Math.PI / 4.0) * i
                    val ix = r + (r * 0.78f) * kotlin.math.cos(a).toFloat()
                    val iy = r + (r * 0.78f) * kotlin.math.sin(a).toFloat()
                    val ox = r + (r * 1.02f) * kotlin.math.cos(a).toFloat()
                    val oy = r + (r * 1.02f) * kotlin.math.sin(a).toFloat()
                    drawLine(ink, Offset(ix, iy), Offset(ox, oy), 1.6.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, sub: String, granted: Boolean, onFix: () -> Unit) {
    SettingRow(title = title, sub = sub, onClick = if (granted) null else onFix) {
        if (granted) StatusPip(true, "On")
        else EvText("GRANT", LocalEvType.current.button, color = EV.Ink)
    }
}

@Composable
private fun StepperRow(
    title: String,
    sub: String,
    value: Int,
    suffix: String,
    step: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    val type = LocalEvType.current
    SettingRow(title = title, sub = sub) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("–", enabled = value - step >= range.first) {
                onChange((value - step).coerceIn(range.first, range.last))
            }
            Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
                EvText(
                    if (value == 0 && suffix == "s") "NEVER" else "$value$suffix",
                    type.mono,
                    color = EV.Ink,
                    maxLines = 1,
                )
            }
            StepButton("+", enabled = value + step <= range.last) {
                onChange((value + step).coerceIn(range.first, range.last))
            }
        }
    }
}

@Composable
private fun StepButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill = when {
        !enabled -> EV.SurfaceSunk
        pressed -> EV.Ink
        else -> EV.Background
    }
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(fill)
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = interaction, indication = null, onClick = onClick,
                    )
                } else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        EvText(
            glyph,
            LocalEvType.current.body,
            color = when {
                !enabled -> EV.InkFaint
                pressed -> EV.OnInk
                else -> EV.Ink
            },
        )
    }
}

/** A miniature of the screen showing where the pill will land. */
/**
 * The colours the pill can be, as the colours themselves.
 *
 * No names under them and no hex: a swatch that has to be labelled is a swatch
 * you cannot see. The selected one gets a floor under it — the same disc idiom
 * as the nav bar — because an outline is the one thing this app does not draw,
 * and a tick sitting on top of the colour would hide the half of it you are
 * choosing between.
 */
@Composable
private fun PillSwatches(selectedId: String, onSelect: (String) -> Unit) {
    // Sixteen dp between swatches rather than six, and the number is doing a
    // job: at six the eighth colour landed a hair off the edge and the row read
    // as a finished set of seven. At sixteen the fifth is cut down its middle,
    // which is the only thing that says out loud that the row moves.
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PillStyles.all.forEach { style ->
            val selected = style.id == selectedId
            val interaction = remember(style.id) { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val floor by animateColorAsState(
                when {
                    selected -> EV.SurfaceSunk
                    pressed -> EV.SurfaceSunk.copy(alpha = 0.5f)
                    else -> Color.Transparent
                },
                label = "swatch-${style.id}",
            )
            Box(
                Modifier
                    .size(52.dp, 56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(floor)
                    .clickable(interactionSource = interaction, indication = null) {
                        onSelect(style.id)
                        Dictation.onStyleChanged()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Swatch(style)
            }
        }
    }
}

/**
 * One colour, drawn as the lozenge it will become — five dots on a fill, at a
 * fifth of the size. A circle would say what the colour is; this says what the
 * pill will look like, which is the actual question.
 */
@Composable
private fun Swatch(style: com.ishaan.essentialvoice.voice.PillStyle) {
    val fill = Color(style.fill)
    val ink = Color(style.ink)
    Canvas(Modifier.size(38.dp, 27.dp)) {
        val r = size.height / 2f
        if (style.blurred) {
            // Nothing is behind a swatch to blur, so the frosted one is shown
            // as what it is made of: a dark scrim, over a chequer that says
            // "what is under this shows through".
            val sq = size.height / 3f
            var y = 0f
            var row = 0
            while (y < size.height) {
                var x = 0f
                var col = 0
                while (x < size.width) {
                    if ((row + col) % 2 == 0) {
                        drawRect(
                            EV.InkFaint.copy(alpha = 0.45f),
                            topLeft = Offset(x, y),
                            size = androidx.compose.ui.geometry.Size(
                                minOf(sq, size.width - x), minOf(sq, size.height - y),
                            ),
                        )
                    }
                    x += sq; col++
                }
                y += sq; row++
            }
        }
        drawRoundRect(fill, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
        val step = size.width / 7f
        repeat(5) { i ->
            drawCircle(
                ink,
                radius = 1.6.dp.toPx(),
                center = Offset(size.width / 2f + (i - 2) * step, size.height / 2f),
            )
        }
    }
}

@Composable
private fun PlacementPreview(settings: Settings) {
    val type = LocalEvType.current
    // Shaped like the phone it is describing, not like the panel it sits in. A
    // square preview cannot tell you where a pill is: "half way down" means
    // something different on a 20:9 screen, and the corner it snaps to is in
    // the wrong place entirely.
    Column(
        Modifier.fillMaxWidth().padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .height(300.dp)
                .aspectRatio(1080f / 2392f)
                .clip(RoundedCornerShape(26.dp))
                .background(EV.SurfaceSunk),
        ) {
            // The gesture bar, so the bottom of the frame reads as the bottom
            // of a phone rather than as the edge of a box.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 7.dp)
                    .size(46.dp, 3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(EV.InkFaint.copy(alpha = 0.5f)),
            )
            Box(Modifier.fillMaxSize().padding(6.dp)) {
                Box(
                    Modifier
                        .offsetFraction(settings.pillX, settings.pillY)
                        .width(38.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(9.dp))
                        // The colour it will actually be. The frosted style has
                        // nothing behind it to blur in here, so it shows as its
                        // scrim, which is what it looks like with blur off.
                        .background(Color(settings.pill.fill)),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        EvText(
            "%d%% ACROSS · %d%% DOWN".format(
                (settings.pillX * 100).toInt(), (settings.pillY * 100).toInt(),
            ),
            type.label,
        )
    }
}

/**
 * Places a child at a fraction of the parent, centred on that point.
 *
 * `this.layout { }`, not `this.then(layout { })`: inside an extension on
 * Modifier the bare `layout` factory resolves against the same receiver, so
 * `then` was appending `this` to itself and every modifier already in the chain
 * was applied twice.
 */
private fun Modifier.offsetFraction(fx: Float, fy: Float): Modifier =
    this.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(constraints.maxWidth, constraints.maxHeight) {
                val px = (constraints.maxWidth * fx - placeable.width / 2f).toInt()
                    .coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
                val py = (constraints.maxHeight * fy - placeable.height / 2f).toInt()
                    .coerceIn(0, (constraints.maxHeight - placeable.height).coerceAtLeast(0))
                placeable.place(px, py)
            }
        }

@Composable
private fun StorageLine(context: Context) {
    val used = ModelCatalog.installedBytes(context) / 1_000_000
    EvText(
        if (used == 0L) "No models downloaded yet." else "$used MB of models on this phone.",
        LocalEvType.current.mono,
        Modifier.padding(start = 4.dp),
    )
}

/**
 * Which recogniser does the listening.
 *
 * whisper is the default and the reason the app can promise nothing is
 * uploaded. It is also, honestly, the weaker of the two outside English — the
 * multilingual models split one budget across a hundred languages — and the
 * phone already carries a recogniser that is much better at those. So this is
 * an option rather than a replacement, and the trade it makes is stated on the
 * row instead of in a changelog.
 */
@Composable
private fun EngineRow(settings: Settings, prefs: Prefs) {
    val context = LocalContext.current
    val type = LocalEvType.current
    val available = remember { GoogleSpeech.isAvailable(context) }
    val onGoogle = settings.engine == Prefs.ENGINE_GOOGLE

    Column(Modifier.padding(18.dp)) {
        EvText("Recognition model", type.body)
        Spacer(Modifier.height(4.dp))
        EvText(
            if (!available) {
                "This phone has no speech recogniser, so whisper is the only option."
            } else if (onGoogle) {
                "Android's own recogniser — much better at languages other than " +
                    "English, and it needs no model download."
            } else {
                // Not "the best of the two at English" any more, which was only
                // ever true of the larger models. tiny.en is not better than
                // Google at anything except never leaving the phone, and that is
                // the claim worth making, because it is the one that is always
                // true.
                "whisper, on this phone. Nothing is uploaded. How well it hears " +
                    "English depends on the quality below."
            },
            type.sub,
        )
        Spacer(Modifier.height(12.dp))
        EvSegmented(
            options = listOf(
                Prefs.ENGINE_WHISPER to "Whisper",
                Prefs.ENGINE_GOOGLE to "Google",
            ),
            selectedId = settings.engine,
        ) { id ->
            if (!available && id == Prefs.ENGINE_GOOGLE) return@EvSegmented
            prefs.setEngine(id)
            // Whichever way it went, the model that is resident is not the one
            // the next dictation wants.
            Dictation.onTierChanged()
        }

    }

    if (!onGoogle) return

    Hairline()
    SettingRow(
        title = "Let it use the network",
        sub = if (settings.googleOnline) {
            "On. A language with a pack on the phone still stays on the phone — " +
                "Android reaches for that first. Only a language with no pack " +
                "goes to Google."
        } else {
            "Off, so nothing leaves the phone. A language with no pack downloaded " +
                "fails instead of being sent anywhere."
        },
    ) {
        EvSwitch(settings.googleOnline) { prefs.setGoogleOnline(it) }
    }

    // The way to have both: install the pack, and that language works offline
    // for ever after. The picker offers the download per language when the
    // recogniser says it can be had; this is for anyone who would rather go and
    // look at what their phone already keeps.
    Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 18.dp)) {
        EvText(
            "A language downloaded on the phone needs no network at all. The list " +
                "under Language says which ones this phone has, and offers the " +
                "download for the ones it can get.",
            type.sub,
        )
        Spacer(Modifier.height(14.dp))
        EvButton("Phone's voice input settings", kind = EvButtonKind.Quiet) {
            Setup.openVoiceInputSettings(context)
        }
    }
}

/**
 * Which language dictation is spoken in.
 *
 * A list of a hundred rather than a segmented control, and a *stated* language
 * rather than automatic detection. whisper can detect one, and it decides from
 * the opening seconds — which here are two seconds of somebody starting a
 * sentence. A wrong guess is not a slightly worse transcript, it is a different
 * script entirely, so the language is something the phone is told once.
 *
 * The row says the size of the download the choice implies before it is made.
 * Changing language is a several-hundred-megabyte decision on the Accurate
 * tiers and the picker should not spring that afterwards.
 */
@Composable
private fun LanguageRow(settings: Settings, prefs: Prefs) {
    val context = LocalContext.current
    val type = LocalEvType.current
    var open by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    val tier = settings.tier
    val installed = tier.isInstalled(context)
    val onGoogle = settings.engine == Prefs.ENGINE_GOOGLE

    // Google's whole language list arrives in one answer, so the picker can say
    // what it will do with each of a hundred languages for the cost of a single
    // query. Asked on arrival, and again whenever the engine changes.
    var catalogue by remember { mutableStateOf(GoogleSpeech.catalogue()) }
    var downloadState by remember(settings.language) { mutableStateOf<String?>(null) }
    LaunchedEffect(onGoogle) {
        if (onGoogle) GoogleSpeech.refreshCatalogue(context) { catalogue = it }
    }

    SettingRow(
        title = "Language",
        sub = if (onGoogle) {
            when (catalogue?.state(settings.language)) {
                GoogleSpeech.State.Installed ->
                    "${settings.languageName}. Downloaded, and it needs no network."
                GoogleSpeech.State.Downloadable ->
                    "${settings.languageName}. Google has it; the pack downloads on " +
                        "first use."
                GoogleSpeech.State.Online ->
                    "${settings.languageName}. Over the network only."
                GoogleSpeech.State.None ->
                    "${settings.languageName}. No pack on this phone, so it goes " +
                        "over the network."
                null -> "${settings.languageName}. Asking the recogniser…"
            }
        } else {
            // On whisper there is only ever one answer, because the engine and
            // the language are kept in step by Prefs — picking another language
            // here moves the engine to Google rather than leaving a pair on
            // screen that cannot work.
            "English. whisper only listens in English."
        },
        onClick = { open = !open },
    ) { EvText(if (open) "CLOSE" else "CHANGE", type.button, color = EV.Ink) }

    // The one thing the state above cannot do for you. Google downloads the
    // pack itself on first use, so this is only ever a way to get the wait over
    // with while there is Wi-Fi rather than while you are holding the key.
    if (onGoogle && catalogue?.state(settings.language) == GoogleSpeech.State.Downloadable) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp)) {
            downloadState?.let {
                EvText(it, type.mono, color = EV.Ink)
                Spacer(Modifier.height(10.dp))
            }
            EvButton("Download it now", kind = EvButtonKind.Quiet) {
                downloadState = "Asking…"
                GoogleSpeech.triggerDownload(context, settings.language) { line ->
                    downloadState = line
                    if (line == "Downloaded") {
                        GoogleSpeech.refreshCatalogue(context) { catalogue = it }
                    }
                }
            }
        }
    }

    if (!open) return

    val matches = remember(query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) Languages.alphabetical
        else Languages.alphabetical.filter { it.name.lowercase().contains(q) || it.code == q }
    }

    Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(EV.CornerRow))
                .background(EV.Background)
                .padding(horizontal = 14.dp, vertical = 13.dp),
        ) {
            if (query.isEmpty()) {
                EvText("Search a hundred languages…", type.body, color = EV.InkFaint)
            }
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = type.body,
                singleLine = true,
                cursorBrush = SolidColor(EV.Ink),
            )
        }
    }

    if (matches.isEmpty()) {
        Column(Modifier.padding(18.dp)) {
            EvText("Nothing matches “${query.trim()}”.", type.sub)
        }
        return
    }

    // Capped and scrolled inside the card, like the app pickers: a hundred rows
    // laid out in full would be the whole settings page.
    Column(
        Modifier
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        matches.forEach { lang ->
            Hairline()
            // On Google every row can say what it will actually do, because the
            // catalogue already knows. On whisper every row but English says
            // the one thing that matters — that choosing it moves the engine —
            // because a setting that quietly changes another setting is a bug
            // unless it says so before the tap, not after.
            SettingRow(
                title = lang.name,
                sub = if (onGoogle) {
                    when (catalogue?.state(lang.code)) {
                        GoogleSpeech.State.Installed -> "On the phone."
                        GoogleSpeech.State.Downloadable -> "Downloads on first use."
                        GoogleSpeech.State.Online -> "Needs the network."
                        GoogleSpeech.State.None -> "Needs the network."
                        null -> null
                    }
                } else if (Languages.isEnglish(lang.code)) {
                    "The one language whisper listens in."
                } else {
                    "Switches to Google's recogniser."
                },
                onClick = {
                    if (lang.code != settings.language) {
                        prefs.setLanguage(lang.code)
                        // The resident model is the wrong one from here on.
                        Dictation.onTierChanged()
                    }
                    query = ""
                    open = false
                },
            ) {
                if (lang.code == settings.language) {
                    EvText("CHOSEN", type.button, color = EV.Ink)
                }
            }
        }
    }
    Hairline()
}

@Composable
internal fun TierCard(
    modifier: Modifier = Modifier,
    /** What an unselected card is filled with. It sits on a panel now, so it
     *  cannot be the same colour as one. */
    fill: Color = EV.Surface,
    tier: QualityTier,
    selected: Boolean,
    installed: Boolean,
    download: ModelDownloader.State,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val type = LocalEvType.current
    val context = LocalContext.current
    val variant = tier.model
    val running = download as? ModelDownloader.State.Running
    val failed = download as? ModelDownloader.State.Failed
    val isDownloading = running?.tierId == tier.id
    val thisFailed = failed?.tierId == tier.id

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val selectable = installed && !selected

    Panel(
        modifier = modifier.then(
            // The card is the control. There was a radio dot in the corner
            // doing this job, which meant every card carried a grey circle that
            // was only ever meaningful on one of them — and the yellow fill
            // already says which one is chosen, louder than a dot can.
            if (selectable) {
                Modifier.clickable(
                    interactionSource = interaction, indication = null, onClick = onSelect,
                )
            } else Modifier,
        ),
        fill = when {
            selected -> EV.Yellow
            // Pressing it changes its colour and nothing else, like everything
            // else here. Without this a tap on a card that *is* the control
            // would look like a tap on nothing.
            selectable && pressed -> EV.SurfaceSunk
            else -> fill
        },
    ) {
        // fillMaxHeight, because the row of cards is measured at
        // IntrinsicSize.Max: every card is as tall as the tallest, and this is
        // what makes each one's *contents* use that height instead of sitting
        // in the top of it.
        Column(Modifier.fillMaxHeight().padding(18.dp)) {
            EvText(
                tier.label,
                type.title,
                color = if (selected) EV.OnYellow else EV.Ink,
            )
            Spacer(Modifier.height(5.dp))
            EvText(
                tier.sub,
                type.sub,
                color = if (selected) EV.OnYellow.copy(alpha = 0.72f) else EV.InkMuted,
            )

            Spacer(Modifier.height(14.dp))
            EvText(
                // Nothing here says whether the model is downloaded: the
                // buttons at the bottom already do, and an extra clause wrapped
                // this line onto two at the width the cards are.
                "${variant.sizeMb} MB   ·   ~${tier.waitLabel} FOR 10s OF SPEECH",
                type.label,
                color = if (selected) EV.OnYellow.copy(alpha = 0.66f) else EV.InkMuted,
            )

            if (thisFailed && !isDownloading) {
                Spacer(Modifier.height(10.dp))
                EvText(failed.message, type.mono, color = EV.Red)
            }

            // Everything above is as long as its own words; everything below
            // is pinned to the bottom of the card. That is what puts every
            // card's buttons on one line across the whole row, whatever the
            // description above them did.
            Spacer(Modifier.height(14.dp))
            Spacer(Modifier.weight(1f))

            if (isDownloading) {
                EvProgress(running.fraction)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EvText(
                        "${running.done / 1_000_000} / ${variant.sizeMb} MB",
                        type.mono,
                        Modifier.weight(1f),
                    )
                    EvButton("Cancel", kind = EvButtonKind.Quiet, onClick = onCancel)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when {
                        !installed -> EvButton(
                            if (thisFailed) "Retry" else "Download",
                            kind = if (selected) EvButtonKind.Quiet else EvButtonKind.Primary,
                            onClick = onDownload,
                        )
                        selected -> EvButton("Delete", kind = EvButtonKind.Danger, onClick = onDelete)
                        else -> {
                            EvButton("Use this", onClick = onSelect)
                            EvButton("Delete", kind = EvButtonKind.Danger, onClick = onDelete)
                        }
                    }
                }
            }
        }
    }
}
