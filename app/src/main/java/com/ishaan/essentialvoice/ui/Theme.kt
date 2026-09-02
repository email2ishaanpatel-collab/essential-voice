package com.ishaan.essentialvoice.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ishaan.essentialvoice.R

/**
 * A grey page with near-white cards on it and one loud colour, set in Geist —
 * and the same idea inverted after dark.
 *
 * Deliberately flat: no shadow and no outline anywhere. A card is told apart
 * from the page by being *lighter* than it, not by being drawn around, and that
 * rule is what the dark palette had to preserve: [Surface] stays a step up from
 * [Background] in both, so nothing needs an outline growing back at night.
 * Depth is fill, and the yellow is the only thing allowed to shout.
 *
 * The yellow does not change. It is the brand and it is legible on both pages;
 * a "dark mode yellow" would just be a second brand colour.
 */
private class Palette(
    val background: Color,
    val surface: Color,
    val surfaceSunk: Color,
    val divider: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val onInk: Color,
    val yellow: Color,
    val yellowSunk: Color,
    val cta: Color,
    val ctaSunk: Color,
    val onCta: Color,
    val red: Color,
    val green: Color,
    val dangerFill: Color,
    val dangerFillSunk: Color,
    val glass: Color,
    val glassAlpha: Float,
    val onGlass: Color,
    val glassSelected: Color,
)

/**
 * Ink for text sitting *on* the yellow.
 *
 * It does not belong to a palette: the yellow does not change between light and
 * dark, so the only colour legible on it does not change either — and the
 * palette's own [EV.Ink] goes almost white after dark, which on yellow is
 * unreadable.
 */
private val OnYellowInk = Color(0xFF1B1B1D)

private val LightPalette = Palette(
    background = Color(0xFFD5D5D5),
    surface = Color(0xFFFDFDFD),
    surfaceSunk = Color(0xFFEBEBEB),
    divider = Color(0xFFF0F0F0),
    ink = Color(0xFF1B1B1D),
    inkMuted = Color(0xFF77777C),
    inkFaint = Color(0xFFA8A8AD),
    onInk = Color(0xFFFDFDFD),
    yellow = Color(0xFFFFD900),
    yellowSunk = Color(0xFFE8C500),
    cta = Color(0xFF1B1B1D),
    ctaSunk = Color(0xFF35353A),
    onCta = Color(0xFFFDFDFD),
    red = Color(0xFFD71921),
    green = Color(0xFF19A24A),
    dangerFill = Color(0xFFFAE9EA),
    dangerFillSunk = Color(0xFFF0D2D3),
    glass = Color(0xFFFFFFFF),
    glassAlpha = 0.62f,
    onGlass = Color(0xFF1B1B1D),
    // #292929 on a white bar is a black hole rather than a highlight, so the
    // light palette gets its opposite number instead of the same value.
    glassSelected = Color(0xFFE6E6E6),
)

/**
 * Not the light palette with the numbers flipped.
 *
 * The page is true black, at Ishaan's request — it was #0E0E10 to keep the
 * near-black card above it from disappearing on an OLED, and the card is
 * #191A1C, which still reads as a step up from nothing. What it does mean is
 * that [Palette.cta], the black button, must never be put straight on the page:
 * on a card it is a button, on the background it is a hole.
 *
 * The muted inks are lifted rather than darkened: grey text that reads as quiet
 * on white reads as broken on a dark page.
 */
private val DarkPalette = Palette(
    background = Color(0xFF000000),
    surface = Color(0xFF191A1C),
    surfaceSunk = Color(0xFF232427),
    divider = Color(0xFF212226),
    ink = Color(0xFFF3F3F4),
    inkMuted = Color(0xFF98989E),
    inkFaint = Color(0xFF66666C),
    onInk = Color(0xFF0E0E10),
    yellow = Color(0xFFFFD900),
    yellowSunk = Color(0xFFE0BE00),
    cta = Color(0xFF000000),
    ctaSunk = Color(0xFF1C1D20),
    onCta = Color(0xFFFEFEFE),
    red = Color(0xFFFF6169),
    green = Color(0xFF35C46E),
    dangerFill = Color(0xFF2A1A1C),
    dangerFillSunk = Color(0xFF3A2124),
    glass = Color(0xFF0B0B0C),
    glassAlpha = 0.55f,
    onGlass = Color(0xFFF3F3F4),
    glassSelected = Color(0xFF292929),
)

/** Every colour of [a] moved [t] of the way towards [b]. */
private fun blend(a: Palette, b: Palette, t: Float) = Palette(
    background = lerp(a.background, b.background, t),
    surface = lerp(a.surface, b.surface, t),
    surfaceSunk = lerp(a.surfaceSunk, b.surfaceSunk, t),
    divider = lerp(a.divider, b.divider, t),
    ink = lerp(a.ink, b.ink, t),
    inkMuted = lerp(a.inkMuted, b.inkMuted, t),
    inkFaint = lerp(a.inkFaint, b.inkFaint, t),
    onInk = lerp(a.onInk, b.onInk, t),
    yellow = a.yellow,
    yellowSunk = lerp(a.yellowSunk, b.yellowSunk, t),
    cta = lerp(a.cta, b.cta, t),
    ctaSunk = lerp(a.ctaSunk, b.ctaSunk, t),
    onCta = lerp(a.onCta, b.onCta, t),
    red = lerp(a.red, b.red, t),
    green = lerp(a.green, b.green, t),
    dangerFill = lerp(a.dangerFill, b.dangerFill, t),
    dangerFillSunk = lerp(a.dangerFillSunk, b.dangerFillSunk, t),
    glass = lerp(a.glass, b.glass, t),
    glassAlpha = a.glassAlpha + (b.glassAlpha - a.glassAlpha) * t,
    onGlass = lerp(a.onGlass, b.onGlass, t),
    glassSelected = lerp(a.glassSelected, b.glassSelected, t),
)

object EV {

    /**
     * The palette in force, as snapshot state.
     *
     * Every colour below reads it, so switching palettes redraws everything
     * that shows one without a single call site changing — which is the only
     * reason a second palette could be added to a screen this size without
     * touching all hundred and thirty places a colour is used.
     */
    private var palette by mutableStateOf(LightPalette)

    /** Which palette the app is heading for, whatever it is showing right now. */
    private var target by mutableStateOf(false)

    /**
     * Move the whole palette [fraction] of the way from light to dark.
     *
     * The switch is a crossfade of every colour rather than a swap, because a
     * page that inverts between two frames reads as a glitch — everything on it
     * changes at once and nothing appears to have caused it. Sliding the same
     * colours across a third of a second makes it one movement, and the yellow
     * sits still through all of it, which gives the eye something to hold on to.
     */
    fun setBlend(fraction: Float) {
        palette = when {
            fraction <= 0f -> LightPalette
            fraction >= 1f -> DarkPalette
            else -> blend(LightPalette, DarkPalette, fraction)
        }
    }

    /** Follows the phone, or the toggle. Sets where the crossfade is going. */
    fun useDark(dark: Boolean) {
        target = dark
    }

    /** What the app is *becoming* — the toggle's glyph, not the current mix. */
    val isDark: Boolean get() = target

    val Background get() = palette.background
    val Surface get() = palette.surface

    /** Pressed states and progress tracks: a step away from either fill. */
    val SurfaceSunk get() = palette.surfaceSunk

    /** Row separators, and nothing else. Not an outline. */
    val Divider get() = palette.divider

    val Ink get() = palette.ink
    val InkMuted get() = palette.inkMuted
    val InkFaint get() = palette.inkFaint

    /** Text and glyphs that sit on top of [Ink]. */
    val OnInk get() = palette.onInk

    val Yellow get() = palette.yellow
    val YellowSunk get() = palette.yellowSunk

    /**
     * The fill under a card's button, and the ink on it.
     *
     * The drawings put a *black* button on a near-black card and reserve the
     * yellow for the one card that cannot be tapped yet — an inversion of what
     * this app did before, and the right way round: nine yellow buttons down a
     * page is nine things shouting, and none of them is louder than the others.
     *
     * It is a palette entry rather than [Ink] because [Ink] flips: it is nearly
     * black on the light page and nearly white on the dark one, and a button
     * that turns white after dark is not the drawing. This pair stays dark with
     * light ink in both, and only the exact black moves — pure on the dark
     * page, a shade off it on the light one, where pure black is a hole.
     */
    val Cta get() = palette.cta
    val CtaSunk get() = palette.ctaSunk
    val OnCta get() = palette.onCta

    val Red get() = palette.red
    val Green get() = palette.green

    /** Text and glyphs drawn on top of [Yellow], in either palette. */
    val OnYellow get() = OnYellowInk

    /**
     * The glass the floating bar and the popups are made of. Thin on purpose —
     * most of what you see through it is the blurred page behind.
     */
    val Glass get() = palette.glass
    val GlassAlpha get() = palette.glassAlpha
    val OnGlass get() = palette.onGlass

    /** The disc under the selected tab. */
    val GlassSelected get() = palette.glassSelected

    /** The two fills behind a destructive button. */
    val DangerFill get() = palette.dangerFill
    val DangerFillSunk get() = palette.dangerFillSunk

    val CornerCard = 24.dp
    val CornerRow = 18.dp

    /**
     * The redesign's radii, taken off the drawings. The card is squarer than
     * the older [CornerCard] and its button is squarer again, so a button still
     * reads as a control sitting on a card rather than as a smaller card.
     */
    val CornerHero = 12.dp
    val CornerCta = 10.dp

    /** The straight tile a card's art sits on, and the size it is drawn at. */
    val CornerTile = 15.dp
    val ArtTile = 141.dp

    /**
     * Every launcher card is exactly this tall, whatever is written on it.
     *
     * It is the one measurement the whole page hangs off. The words at the
     * foot of a card are pinned there, so a card carrying a two-line paragraph
     * eats into its own picture rather than growing — which is what keeps the
     * buttons down the page on a single rhythm instead of drifting apart by a
     * line here and a line there.
     */
    val CardHeight = 383.dp

    /** The one pill on the page: the update row at the top of the launcher. */
    val CornerPill = 25.dp

    /** Buttons are rounded rectangles, never pills — the radius stays put as
     *  the control gets taller. */
    val CornerButton = 14.dp
    val PagePadding = 20.dp

    /**
     * The margin the redesigned pages run to — four dp tighter than
     * [PagePadding], because a card carrying a picture wants the width and a
     * card carrying a paragraph does not.
     */
    val PageGutter = 16.dp
}

// Only the two weights the type scale actually asks for. Shipping the other
// five cost 380KB to render nothing.
val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
)

val GeistMono = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
)

class EvTypography {
    val display = TextStyle(
        fontFamily = Geist, fontWeight = FontWeight.Medium,
        fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-0.8).sp, color = EV.Ink,
    )
    val title = TextStyle(
        fontFamily = Geist, fontWeight = FontWeight.Medium,
        fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.2).sp, color = EV.Ink,
    )
    val body = TextStyle(
        fontFamily = Geist, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 21.sp, color = EV.Ink,
    )
    /** The line under the name. Body size, and a shade quieter than body. */
    val strap = TextStyle(
        fontFamily = Geist, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 18.sp, color = EV.InkFaint,
    )
    val sub = TextStyle(
        fontFamily = Geist, fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp, lineHeight = 19.sp, color = EV.InkMuted,
    )
    /** Uppercase mono, used for every section heading and every hard number. */
    val label = TextStyle(
        fontFamily = GeistMono, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.6.sp, color = EV.InkMuted,
    )
    val mono = TextStyle(
        fontFamily = GeistMono, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp, color = EV.InkMuted,
    )
    /**
     * [label] set in Geist rather than Geist Mono.
     *
     * For a heading that sits *inside* a card, among sentences: the mono face
     * earns its keep on a section heading over the page, where it is the only
     * thing on the line, and reads as a different app's typeface three lines
     * under a paragraph of Geist.
     */
    val labelSans = TextStyle(
        fontFamily = Geist, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.2.sp, color = EV.InkMuted,
    )
    /**
     * The launcher's voice, and the whole of it.
     *
     * The drawings set five sizes for that page — 35, 16, 14, 12, 10 — and the
     * five below are them. Listing them in one place is the point: a page built
     * from five sizes reads as one page, and every size added after the fifth
     * is another decision the reader has to make sense of.
     *
     * Two of the five are mono. [cardTitle] is Geist Mono rather than Geist
     * because a card's heading and the button under it are the card's two fixed
     * points, and setting both in the same face ties them together across the
     * picture that sits between them. Sentence case, not upper: upper-case mono
     * at 16 is a sign, not a title.
     */
    val cardTitle = TextStyle(
        fontFamily = GeistMono, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 23.sp, color = EV.Ink,
    )
    val heroBody = TextStyle(
        fontFamily = Geist, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 23.sp, color = EV.InkMuted,
    )
    /**
     * The bracketed word in a card's top corner. Small mono, and never the
     * brightest thing on the card — it says what state the card is in, which
     * you want to be able to ignore.
     */
    val tag = TextStyle(
        fontFamily = GeistMono, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp, color = EV.InkMuted,
    )
    /** The caption under an art tile. Small, so the tile stays the picture. */
    val tileLabel = TextStyle(
        fontFamily = Geist, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp, color = EV.InkMuted,
    )
    /**
     * A button's label: mono, upper case, and wide. It carries no colour of its
     * own — the two kinds of button set their own ink, and there is no sensible
     * default sitting between white-on-black and black-on-yellow.
     */
    val cta = TextStyle(
        fontFamily = GeistMono, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, letterSpacing = 1.2.sp,
        textDecoration = TextDecoration.None,
    )
    /** The smallest thing on the page: a version, a build, a count. */
    val stamp = TextStyle(
        fontFamily = Geist, fontWeight = FontWeight.Normal,
        fontSize = 10.sp, lineHeight = 14.sp, color = EV.InkMuted,
    )
    /** The app's name at the top of the launcher. */
    val masthead = TextStyle(
        fontFamily = Geist, fontWeight = FontWeight.Normal,
        fontSize = 35.sp, lineHeight = 38.5.sp, letterSpacing = (-0.9).sp, color = EV.Ink,
    )

    val button = TextStyle(
        fontFamily = GeistMono, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, letterSpacing = 1.4.sp, color = EV.Ink,
        textDecoration = TextDecoration.None,
    )
}

val LocalEvType = staticCompositionLocalOf { EvTypography() }

/**
 * [dark] defaults to whatever the phone is set to, and there is no switch in the
 * app for it: an app with its own light/dark toggle is an app that disagrees
 * with the phone at least some of the time.
 */
@Composable
fun EssentialVoiceTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    EV.useDark(dark)
    // The crossfade. Snap on the very first composition — an app that fades in
    // from the wrong palette on launch is not a transition, it is a flash.
    var settled by remember { mutableStateOf(false) }
    val fraction by animateFloatAsState(
        targetValue = if (dark) 1f else 0f,
        animationSpec = if (settled) tween(300, easing = FastOutSlowInEasing) else snap(),
        label = "palette",
    )
    EV.setBlend(fraction)
    LaunchedEffect(Unit) { settled = true }
    // The type scale bakes colours into its styles, and it is built here, in
    // composition — so its reads of EV are tracked and the whole scale is
    // rebuilt the moment the palette changes.
    CompositionLocalProvider(LocalEvType provides EvTypography(), content = content)
}
