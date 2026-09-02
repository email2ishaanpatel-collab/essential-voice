package com.ishaan.essentialvoice.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.ishaan.essentialvoice.R
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.random.Random

/**
 * The redesign's kit: the launcher's one kind of card, the art that goes in it,
 * and the chrome of a detail page.
 *
 * It keeps the three rules the older kit in [Components] set — no shadow, no
 * outline, and a press changes colour rather than position — and adds the two
 * the drawings are built on.
 *
 * **Every card carries a picture.** A settings app that is nothing but rows of
 * words makes you read all of them to find the one you want; a picture is what
 * you actually navigate by.
 *
 * **And there is only one card.** The previous pass had a tall card for the
 * things worth showing off and a one-line row for the rest, which quietly sorted
 * the app into important and unimportant — a sort the reader did not ask for and
 * cannot undo. Ten identical cards is a longer page and a much simpler one: the
 * picture tells them apart, and nothing on the page is whispering.
 *
 * Sizes come from the drawings rather than from taste: the card is 383dp tall on
 * a 12dp corner, its button 45dp on a 10dp corner, its art tile 141dp on a 15dp
 * corner, and the page gutter 16dp. Colours do not: the drawings are dark-only,
 * so everything here reads [EV] and works in both palettes.
 */

// ---- movement -------------------------------------------------------------

/**
 * Fade up, once, [index] places down the queue.
 *
 * The page arrives a card at a time rather than all at once. That is worth the
 * three hundred milliseconds it costs because it says which order to read in —
 * and because the alternative, a page that is simply *there*, has no way of
 * telling you that anything happened when you came back to it.
 *
 * It runs on [graphicsLayer] rather than on padding: a layer moves the pixels
 * that were already drawn, so nothing is measured or laid out again on any of
 * the frames in between.
 *
 * [enabled] is off on every arrival after the first. Coming back from a detail
 * page lands you where you were, part way down, and a stagger replayed there
 * animates the two cards you can see out of a queue whose first eight already
 * happened off screen — which reads as a stutter, not an entrance.
 */
@Composable
fun Modifier.rises(index: Int = 0, enabled: Boolean = true): Modifier {
    if (!enabled) return this
    var shown by remember { mutableStateOf(false) }
    val t by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(
            durationMillis = 420,
            delayMillis = index * 70,
            easing = FastOutSlowInEasing,
        ),
        label = "rise",
    )
    LaunchedEffect(Unit) { shown = true }
    return this.graphicsLayer {
        alpha = t
        translationY = (1f - t) * 22.dp.toPx()
    }
}

// ---- likes ----------------------------------------------------------------

/**
 * The board, and what to do when a heart is pressed.
 *
 * A composition local rather than two more parameters on [HeroCard], because
 * every card on the launcher wants exactly the same two things and none of the
 * cards on a detail page wants either. Threading them through would put a
 * `likes` and an `onLike` on a design-kit component that is otherwise about a
 * picture and some words.
 *
 * Null is the ordinary state everywhere except the launcher, and it is what
 * makes the heart not draw: an unconfigured build, a detail page, and a preview
 * all have no board, and all of them should show a card with a number in the
 * corner and nothing else.
 */
class LikeBoard(
    val cards: Map<String, Pair<Int, Boolean>>,
    val known: Boolean,
    val onToggle: (String) -> Unit,
)

val LocalLikes = staticCompositionLocalOf<LikeBoard?> { null }

/**
 * A heart and, in the brackets that used to hold the card's number, the count.
 *
 * The bracketed number in that corner was the card's position in the list —
 * `[01]`, `[02]` — and it is now how many people liked it. The two cannot both
 * be there: a corner with `[03]` and a heart and a `15` next to it is three
 * numbers in one place, and the position was always the least interesting of
 * them. It was only ever there because the drawings wanted a number in the
 * corner, and this is a better number to put in it. The position still exists
 * and is still what staggers the cards' entrance; it is simply not printed.
 *
 * `[--]` while the first fetch is out, never `[00]`. A card that reads zero is
 * making a claim — *nobody liked this* — and that claim should not be made on
 * the app's behalf before the answer has arrived. After the first ever fetch it
 * is cached, so `[--]` is close to unseen in practice.
 *
 * Colour and fill are the whole of what changes when it is pressed, in keeping
 * with the rest of this kit: the heart does not grow, bounce or spring. It is
 * outlined in the muted ink when it is not yours and solid red when it is, and
 * both cross-fade so the change reads as *yours* rather than as a repaint.
 */
@Composable
private fun LikeTag(card: String, board: LikeBoard) {
    val type = LocalEvType.current
    val (count, liked) = board.cards[card] ?: (0 to false)

    // The tap target, not the heart. 14dp of heart is the right size on the
    // card and far too small to aim at, so the whole strip — heart, gap and
    // brackets — takes the tap, and it reaches the full height of the row.
    //
    // The 10dp of padding is what puts the heart on the card's own 18dp margin,
    // since the row this sits in gives it only 8. Pressing the strip's padding
    // counts, which is the point of putting it inside the clickable rather than
    // around it.
    Row(
        Modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(EV.CornerPill))
            // Its own clickable, so the tap stops here instead of also opening
            // the card underneath it. A child that handles a press consumes it.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { board.onToggle(card) }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        val ink by animateColorAsState(
            if (liked) EV.Red else EV.InkFaint,
            tween(180),
            label = "heart",
        )
        val fill by animateFloatAsState(
            targetValue = if (liked) 1f else 0f,
            animationSpec = tween(180, easing = FastOutSlowInEasing),
            label = "heartFill",
        )
        Heart(ink, fill)
        // Two digits, like the card numbers this replaced, so a column of cards
        // keeps its left edge whether the counts are 3 or 30. A card that gets
        // past 99 takes the third digit rather than being cut, which is a
        // problem worth having.
        EvText(if (board.known) "[%02d]".format(count) else "[--]", type.tag, maxLines = 1)
    }
}

/**
 * The heart, drawn rather than imported.
 *
 * Every mark in this app is a path — the logo, the earbuds, the dot grid — and
 * a vector drawable here would be the one glyph that is a resource, at a size
 * that has to be kept in step with a text style beside it. Two cubics from the
 * point, mirrored, which is the shortest honest heart.
 *
 * [fill] is how solid it is, 0 to 1, and it is animated rather than switched:
 * the outline stays drawn underneath at every value, so the shape never thins
 * or shifts as the inside arrives.
 */
@Composable
private fun Heart(color: Color, fill: Float) {
    Canvas(Modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            // The point at the bottom, and up each side to a lobe. The control
            // points sit outside the box on purpose — that overshoot is what
            // makes the lobes round instead of pointed — so the glyph is drawn
            // a little inside its 14dp to leave them room.
            moveTo(w * 0.5f, h * 0.94f)
            cubicTo(w * -0.16f, h * 0.52f, w * 0.10f, h * 0.00f, w * 0.5f, h * 0.30f)
            cubicTo(w * 0.90f, h * 0.00f, w * 1.16f, h * 0.52f, w * 0.5f, h * 0.94f)
            close()
        }
        if (fill > 0f) drawPath(path, color, alpha = fill)
        drawPath(path, color, style = Stroke(width = 1.4.dp.toPx()))
    }
}

// ---- cards ----------------------------------------------------------------

/** Which of the two buttons a card wears. */
enum class CardCta {
    /**
     * Black, with light ink. The ordinary one, and the reason the page is
     * calm: a column of yellow buttons is a column of things all shouting the
     * same word.
     */
    Dark,

    /** Yellow. At most one card on the page gets it, and it means "not yet". */
    Accent,
}

/**
 * The launcher's card, and the only one: a status tag, a picture, a heading, a
 * line of explanation, and a button.
 *
 * The whole card is the target, not just the button. The button is there to say
 * what happens and to be the thing a thumb aims at; making it the *only* live
 * part would mean a 383dp card with a 45dp hit area in it, which is a card that
 * ignores most of the taps aimed at it. Both press the same interaction source,
 * so pressing anywhere tints the button and nothing moves.
 *
 * [index] is both the number in the corner and the card's place in the
 * entrance queue, because they are the same fact: a card hidden by a feature
 * flag has to leave a gap in neither. Passing it twice is how the numbering
 * came to read 07, 09 with game mode switched off.
 *
 * The height is fixed and the words are pinned to the foot, so [art] takes
 * whatever is left over — which is how a one-line card and a two-line card end
 * up the same height with their buttons on the same rhythm. [body] is capped at
 * two lines for the same reason: past two, a card is a paragraph with a picture
 * stuck on it, and the picture is the part that was doing the work.
 */
@Composable
fun HeroCard(
    index: Int,
    title: String,
    body: String,
    cta: String,
    modifier: Modifier = Modifier,
    intro: Boolean = true,
    kind: CardCta = CardCta.Dark,
    enabled: Boolean = true,
    /**
     * What this card is called on the server, if it can be liked: the [Page]
     * name, never the [index]. The number in the corner moves when a feature
     * flag hides a card above it, and a like that followed the number would
     * follow it onto a different card.
     */
    likeKey: String? = null,
    onClick: () -> Unit,
    art: @Composable BoxScope.() -> Unit,
) {
    val type = LocalEvType.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val resting = if (kind == CardCta.Accent) EV.Yellow else EV.Cta
    val sunk = if (kind == CardCta.Accent) EV.YellowSunk else EV.CtaSunk
    val button by animateColorAsState(
        if (pressed && enabled) sunk else resting,
        label = "cta",
    )
    val ink = if (kind == CardCta.Accent) EV.OnYellow else EV.OnCta

    Column(
        modifier
            .rises(index, intro)
            .fillMaxWidth()
            .height(EV.CardHeight)
            .clip(RoundedCornerShape(EV.CornerHero))
            .background(EV.Surface)
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        Spacer(Modifier.height(6.dp))
        // 32dp rather than the 24 this row used to be, and the spacer above it
        // is 6 rather than 8. The heart has to be aimable, and a 24dp-tall
        // target is not; the six dp this borrows come out of the art, which has
        // ~150 of them and does not miss them.
        //
        // 8dp of start padding rather than 18: the strip inside carries its own
        // 10, so that the *padding* is pressable and the heart still lands on
        // the card's real margin. The fallback below makes the 10 up itself.
        Row(
            Modifier.fillMaxWidth().height(32.dp).padding(start = 8.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A number in the corner, because the drawings put one there and
            // because nothing else up there would be true of every card. A
            // status word was tried and only two of eleven cards had a status
            // worth reporting; the other nine would have read "OPEN", which is
            // a label that tells you a card is a card.
            //
            // Which number it is has changed. It was the card's place in the
            // list; it is now how many people liked the card, which is the same
            // shape in the same brackets and says something nobody knew. The
            // place is still what [rises] staggers the entrance by — it just is
            // not printed any more.
            //
            // The old number is the fallback, and it has to be: a build with no
            // `supabase.properties` has no counts to show, and an empty corner
            // is not a design, it is a hole. See [LikeTag].
            val board = LocalLikes.current
            if (likeKey != null && board != null) {
                LikeTag(likeKey, board)
            } else {
                EvText(
                    "[%02d]".format(index),
                    type.tag,
                    Modifier.padding(start = 10.dp),
                    maxLines = 1,
                )
            }
        }
        // Clipped, so a drawing that overruns its share is cropped by the card
        // instead of pushing the words off the bottom of it. The art in the
        // drawings does overrun — that is what makes it look placed rather
        // than pasted.
        Box(
            Modifier.fillMaxWidth().weight(1f).clipToBounds(),
            contentAlignment = Alignment.Center,
            content = art,
        )
        Column(Modifier.padding(horizontal = 18.dp)) {
            EvText(title, type.cardTitle, maxLines = 1)
            Spacer(Modifier.height(5.dp))
            EvText(body, type.heroBody, maxLines = 2)
        }
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .height(45.dp)
                .clip(RoundedCornerShape(EV.CornerCta))
                .background(button),
            contentAlignment = Alignment.Center,
        ) {
            EvText(cta.uppercase(), type.cta, color = ink, maxLines = 1)
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * The one row on the page that is not a card: a wide pill with a label on the
 * left and the build it is checking against on the right.
 *
 * It sits above the cards rather than among them because it is the only thing
 * up there that is about the app itself rather than about something the app
 * does — and being a different shape from everything below it is how you can
 * tell that at a glance, without reading it.
 */
@Composable
fun UpdatePill(
    label: String,
    stamp: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val type = LocalEvType.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(
        if (pressed) EV.SurfaceSunk else EV.Surface,
        label = "pill",
    )

    Row(
        modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(EV.CornerPill))
            .background(fill)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = 20.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The weight is on the label rather than on the stamp. Both cannot
        // have it, and of the two the stamp is the one that must not be cut: a
        // clipped label still says "check for updates", while a clipped build
        // number says a different build.
        EvText(label, type.cardTitle, Modifier.weight(1f), maxLines = 1)
        Spacer(Modifier.width(12.dp))
        EvText(stamp, type.stamp, maxLines = 1)
    }
}

/**
 * A pill that opens.
 *
 * The same 50dp row as [UpdatePill] — so the three of them stack into one group
 * at the top of the launcher — with a chevron instead of a stamp, and whatever
 * is put in [content] revealed underneath it. Closed, it is indistinguishable
 * from the pill next to it; open, it is a card, which is the point: the two
 * things above the cards are the two things that have something to say and
 * usually nothing to say it about.
 *
 * The header keeps its height when the body is showing, so the title does not
 * move under the finger that just tapped it.
 */
@Composable
fun DisclosurePill(
    label: String,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = EV.Surface,
    fillSunk: Color = EV.SurfaceSunk,
    ink: Color = EV.Ink,
    content: @Composable ColumnScope.() -> Unit,
) {
    val type = LocalEvType.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(if (pressed) fillSunk else fill, label = "disclosure")
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "chevron")

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(EV.CornerPill))
            .background(background),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clickable(interactionSource = interaction, indication = null, onClick = onToggle)
                .padding(start = 20.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EvText(label, type.cardTitle, Modifier.weight(1f), color = ink, maxLines = 1)
            Spacer(Modifier.width(12.dp))
            Chevron(Modifier.rotate(turn), color = ink)
        }
        AnimatedVisibility(visible = open) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

/** The "v" in a [DisclosurePill]. Two strokes, like [BackChevron]. */
@Composable
fun Chevron(modifier: Modifier = Modifier, color: Color = EV.Ink) {
    Canvas(modifier.size(14.dp)) {
        val w = 1.6.dp.toPx()
        val y = size.height * 0.62f
        val tip = size.height * 0.34f
        drawLine(
            color, Offset(size.width * 0.12f, tip), Offset(size.width / 2f, y),
            strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawLine(
            color, Offset(size.width * 0.88f, tip), Offset(size.width / 2f, y),
            strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

// ---- marks ----------------------------------------------------------------

/**
 * The app's mark: five bars in a yellow lozenge, and the bars keep time.
 *
 * **It arrives folded up.** The lozenge starts as a circle with all five bars
 * stacked on the same column in the middle of it, and opens: the shape stretches
 * to its full 141 while the bars walk out to their own columns and grow from
 * dots into their own heights. Outer bars leave a little later than inner ones,
 * so the row unpacks rather than sliding as a block.
 *
 * The geometry underneath is the drawing's, to the pixel — a 141x83 lozenge,
 * five 8dp bars on a 12dp pitch, all five centred on the same line. Only the
 * first three keep moving once it has opened. The last two are dots in the
 * drawing, and a dot that grows into a bar reads as a bug rather than as a
 * level meter.
 *
 * One phase drives all of them, offset per bar, so the whole thing costs a
 * single animation and a redraw of one Canvas.
 */
@Composable
fun VoiceMark(modifier: Modifier = Modifier, width: Dp = 141.dp, live: Boolean = true) {
    var arrived by remember { mutableStateOf(false) }
    val open by animateFloatAsState(
        targetValue = if (arrived) 1f else 0f,
        animationSpec = tween(720, delayMillis = 140, easing = FastOutSlowInEasing),
        label = "open",
    )
    LaunchedEffect(Unit) { arrived = true }

    val phase by rememberInfiniteTransition(label = "wave").animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    val fill = EV.Yellow
    val ink = EV.OnYellow
    Canvas(modifier.width(width).height(width * 83f / 141f)) {
        // Everything below is in the drawing's own 141x83 units, scaled once.
        val k = size.width / 141f
        val tall = 83f * k

        // Closed, the lozenge is exactly as wide as it is high — a circle, not
        // a short pill, which is what makes the opening read as a stretch.
        val w = (83f + (141f - 83f) * open) * k
        drawRoundRect(
            color = fill,
            topLeft = Offset((size.width - w) / 2f, 0f),
            size = Size(w, tall),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(tall / 2f),
        )

        val xs = floatArrayOf(43f, 55f, 67f, 79f, 91f)
        val hs = floatArrayOf(18f, 39f, 23f, 10f, 8f)
        val mid = tall / 2f
        val stacked = size.width / 2f - 4f * k
        xs.forEachIndexed { i, x ->
            // Each bar spends 80% of the opening travelling, starting 5% later
            // than the one before it.
            val o = ((open - i * 0.05f) / 0.8f).coerceIn(0f, 1f)
            val swingFull =
                if (live && i < 3) 0.72f + 0.34f * (1f + sin(phase + i * 1.1f)) else 1f
            // The wave fades in with the opening, so nothing is jittering while
            // it is still folded up.
            val swing = 1f + (swingFull - 1f) * open
            val h = (8f + (hs[i] - 8f) * o) * k * swing
            drawRoundRect(
                color = ink,
                topLeft = Offset(stacked + (x * k - stacked) * o, mid - h / 2f),
                size = Size(8f * k, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f * k),
            )
        }
    }
}

/**
 * A square tile, sitting straight, with a glyph on it and a word underneath.
 *
 * The pass before this one tipped it fifteen degrees, on the argument that a
 * square sitting straight is a swatch and a square on the tilt is an object
 * somebody put down. The drawings put it back upright, and they are right: one
 * tilted tile is an object, and nine of them down a page is a page that has
 * come off its hinges. Upright, the tiles line up with the cards holding them
 * and the eye can run straight down the column.
 *
 * [caption] is the tile's own word rather than the card's heading. The two say
 * different things on purpose — the heading names the setting, the caption says
 * what the picture is showing.
 */
@Composable
fun ArtTile(
    fill: Color,
    modifier: Modifier = Modifier,
    caption: String? = null,
    ink: Color = Color(0xFFFEFEFE),
    size: Dp = EV.ArtTile,
    glyph: @Composable () -> Unit = {},
) {
    val type = LocalEvType.current
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(EV.CornerTile))
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            glyph()
            if (caption != null) {
                Spacer(Modifier.height(16.dp))
                // Crossfaded rather than swapped, because one tile's caption
                // does change while you are looking at it — see [EarbudsArt],
                // where the word underneath is half of what the picture is
                // saying. A caption that never changes never animates.
                Crossfade(caption, label = "caption") {
                    EvText(it, type.tileLabel, color = ink, maxLines = 1)
                }
            }
        }
    }
}

/**
 * The earbuds case, open, at the 42dp the drawings set.
 *
 * Shipped as the exported vector rather than drawn, because it is a real icon
 * with a real outline and the other marks in this file are not — they are
 * shapes the app already owns.
 *
 * [level] fills it from the bottom in [fill], nought to one. It is the same
 * glyph drawn twice with the top one clipped, rather than a gradient: the icon
 * is a single path with a hole through the middle of it, and a sweep that
 * respects that hole is the only one that reads as the case filling up.
 */
@Composable
fun EarbudsGlyph(
    modifier: Modifier = Modifier,
    color: Color = EV.InkMuted,
    size: Dp = 42.dp,
    fill: Color = color,
    level: Float = 0f,
) {
    val painter = painterResource(R.drawable.ic_earbuds_case)
    Box(modifier.size(size)) {
        Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            colorFilter = ColorFilter.tint(color),
        )
        if (level > 0f) {
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        clipRect(top = this.size.height * (1f - level.coerceIn(0f, 1f))) {
                            this@drawWithContent.drawContent()
                        }
                    },
                colorFilter = ColorFilter.tint(fill),
            )
        }
    }
}

/**
 * The earbuds tile, connecting itself.
 *
 * The card it sits on is about a widget whose whole job is one tap and a pair
 * of earbuds that connect — and a still picture of a case with the word
 * "Connect earphones" under it shows the tap and not the thing the tap is for.
 * So the tile does the whole of it, on a loop: the case fills with green from
 * the bottom while the caption says it is connecting, and when the fill reaches
 * the top the tile itself turns green and says [Connected].
 *
 * The ink flips dark at that moment rather than staying light. White on this
 * green is legible on the light palette and marginal on the dark one, and the
 * caption is the half of the picture carrying the actual news.
 *
 * [live] is what the whole loop hangs off, so a screen that wants the drawing
 * and not the performance can have it.
 */
@Composable
fun EarbudsArt(modifier: Modifier = Modifier, size: Dp = EV.ArtTile, live: Boolean = true) {
    // Three states rather than a single fraction: "connecting" and "connected"
    // are not two points on one scale — the fill is over before the tile turns,
    // and the pause afterwards is as much a part of it as the movement.
    var phase by remember { mutableStateOf(if (live) Buds.Idle else Buds.Connected) }
    val level = remember { Animatable(if (live) 0f else 1f) }
    LaunchedEffect(live) {
        if (!live) return@LaunchedEffect
        while (true) {
            phase = Buds.Idle
            level.snapTo(0f)
            delay(1500)
            phase = Buds.Connecting
            level.animateTo(1f, tween(1150, easing = FastOutSlowInEasing))
            phase = Buds.Connected
            delay(2400)
        }
    }

    val done = phase == Buds.Connected
    val tile by animateColorAsState(
        targetValue = if (done) EV.Green else EV.SurfaceSunk,
        animationSpec = tween(420),
        label = "budsTile",
    )
    val ink by animateColorAsState(
        targetValue = if (done) EV.OnYellow else EV.InkMuted,
        animationSpec = tween(420),
        label = "budsInk",
    )
    // The sweep is green on the sunk tile and then, once the tile is green
    // underneath it, the same dark ink as everything else on it.
    val sweep by animateColorAsState(
        targetValue = if (done) EV.OnYellow else EV.Green,
        animationSpec = tween(320),
        label = "budsSweep",
    )

    ArtTile(
        fill = tile,
        modifier = modifier,
        caption = when (phase) {
            Buds.Idle -> "Connect earphones"
            Buds.Connecting -> "Connecting…"
            Buds.Connected -> "Connected"
        },
        ink = ink,
        size = size,
    ) {
        EarbudsGlyph(color = ink, fill = sweep, level = level.value)
    }
}

/** Where [EarbudsArt] is in its loop. */
private enum class Buds { Idle, Connecting, Connected }

/**
 * The phone seen edge-on, with the volume running down it.
 *
 * The silhouette is the exported vector — it is one long bezier with a waist in
 * it and there is no honest way to redraw that. Everything on top of it is
 * drawn here instead, in [EV.OnCta], so the scale and the bar stay legible on
 * the black body in either palette: shipped at the drawing's own greys they
 * would have been two hardcoded colours that only ever worked after dark.
 *
 * [level] is where the bar stops, nought to one, and where the loop starts
 * from. It is drawn from a number rather than pasted, so a screen that wants to
 * show the real volume can.
 *
 * [live] runs the bar up and down the scale on its own. The card is about the
 * volume keys, and a bar sitting at 62% is a picture of a phone nobody is
 * touching; a bar that keeps moving is somebody pressing the keys. The walk is
 * random rather than a loop — a fixed up-down-up cycle is visibly a loop by the
 * second time round, and a thumb on a volume key does not do the same thing
 * twice. Each leg is a random distance in a random direction over a random
 * length of time, with a random pause after it; the two ends bounce it back
 * rather than clamping it, so it never sits still at the top or the bottom.
 */
@Composable
fun VolumeArt(
    modifier: Modifier = Modifier,
    level: Float = 0.62f,
    height: Dp = 177.dp,
    live: Boolean = true,
) {
    val walk = remember { Animatable(level) }
    LaunchedEffect(live) {
        if (!live) return@LaunchedEffect
        while (true) {
            val here = walk.value
            val up = when {
                here < 0.22f -> true
                here > 0.86f -> false
                else -> Random.nextBoolean()
            }
            val step = 0.16f + Random.nextFloat() * 0.42f
            val next = (if (up) here + step else here - step).coerceIn(0.06f, 1f)
            walk.animateTo(next, tween(300 + Random.nextInt(480), easing = FastOutSlowInEasing))
            delay(80L + Random.nextInt(620))
        }
    }
    val body = EV.Cta
    val on = EV.OnCta
    Box(modifier.height(height).width(height * 32.264f / 177f)) {
        Image(
            painter = painterResource(R.drawable.ic_phone_side),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            colorFilter = ColorFilter.tint(body),
        )
        // All of the below is in the drawing's own 32.264x177 units, scaled once.
        Canvas(Modifier.fillMaxSize()) {
            val k = size.height / 177f
            val x = 17.11f * k
            // Seventeen dots at the drawing's pitch, top to bottom, and then
            // the bar laid over the ones it has reached.
            repeat(17) { i ->
                drawCircle(
                    color = on.copy(alpha = 0.3f),
                    radius = 1.77f * k,
                    center = Offset(x, (47.2f + i * 5.7525f) * k),
                )
            }
            // Read here rather than in the composable body on purpose: the
            // walk never stops, and a value read during composition would
            // recompose this card sixty times a second for as long as the page
            // is open. Read inside the draw, it invalidates the drawing only.
            val filled = 60f * (if (live) walk.value else level).coerceIn(0f, 1f)
            drawRoundRect(
                color = on,
                topLeft = Offset(x - 2f * k, (141f - filled) * k),
                size = Size(4f * k, filled * k),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * k),
            )
        }
    }
}

/**
 * The island: a black lozenge the width of the app's own mark, with the camera
 * showing through it — and, on a loop, the whole of what it is for.
 *
 * It is the only mark here drawn at the size the *phone* has rather than the
 * size the card wants, because that is the whole of what it is: a shape that
 * has to sit exactly over a hole in a screen.
 *
 * **The loop is the feature.** A lozenge on its own is a shape; nothing about
 * it says a tap opens a player, which is the one thing anybody needs to know
 * before they turn the island on. So it plays it out: a song running in the
 * closed island, a tap landing on it, the lozenge growing into the player, and
 * the player closing again on the next track.
 *
 * Both halves are laid out at their *own* size with [requiredSize] inside a box
 * that clips them, rather than being measured against whatever the lozenge is
 * mid-way through growing. Anything else re-lays-out the player on every frame
 * of the expansion, at forty widths it was never designed for, and the text
 * inside it visibly reflows on the way open.
 *
 * [live] leaves it closed and quiet, for anywhere the drawing is wanted and the
 * performance is not.
 */
@Composable
fun IslandArt(modifier: Modifier = Modifier, width: Dp = 141.dp, live: Boolean = true) {
    val shut = width * 38f / 141f
    val open = remember { Animatable(0f) }
    val tap = remember { Animatable(0f) }
    val played = remember { Animatable(IslandStart) }
    // Where it starts is random, so two launches of the app are not the same
    // performance; where it goes next is the following track, so a single sit
    // in front of it is not the same one twice either.
    var track by remember { mutableStateOf(Random.nextInt(IslandTracks.size)) }

    LaunchedEffect(live) {
        if (!live) return@LaunchedEffect
        while (true) {
            delay(2400)
            // The tap lands before anything moves. A shape that opens with no
            // cause reads as a notification; a ring under a finger is what says
            // somebody did this.
            tap.snapTo(0f)
            tap.animateTo(1f, tween(400, easing = LinearOutSlowInEasing))
            open.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
            tap.snapTo(0f)
            played.animateTo(0.72f, tween(3400, easing = LinearEasing))
            open.animateTo(0f, tween(420, easing = FastOutSlowInEasing))
            track = (track + 1) % IslandTracks.size
            played.snapTo(IslandStart)
        }
    }

    val t = open.value
    val song = IslandTracks[track]
    Box(
        modifier
            .width(lerp(width, IslandOpenWidth, t))
            .height(lerp(shut, IslandOpenHeight, t))
            .clip(RoundedCornerShape(lerp(shut / 2f, 26.dp, t)))
            .background(EV.Cta),
        contentAlignment = Alignment.Center,
    ) {
        // The two contents cross over rather than swapping: the closed one is
        // gone by the time the box is a third of the way open, and the player
        // only starts to show once there is a box big enough to hold it.
        if (t < 1f) {
            IslandShut(
                song, width, shut, tap.value, live,
                Modifier.alpha(((1f - t) * 3f).coerceIn(0f, 1f)),
            )
        }
        if (t > 0f) {
            IslandPlayer(
                song, { played.value },
                Modifier.alpha(((t - 0.45f) / 0.35f).coerceIn(0f, 1f)),
            )
        }
    }
}

/**
 * The island with a song running in it: the cover on one side, a level meter on
 * the other, and the camera between them.
 *
 * The meter stops short of the lens rather than running to the edge, because
 * the lens is a hole in a real screen and nothing is allowed to sit under it.
 */
@Composable
private fun IslandShut(
    song: IslandTrack,
    width: Dp,
    height: Dp,
    tap: Float,
    live: Boolean,
    modifier: Modifier = Modifier,
) {
    val ink = EV.OnCta
    val phase by rememberInfiniteTransition(label = "island").animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "meter",
    )
    Canvas(modifier.requiredSize(width, height)) {
        // The drawing's own 141x38 units, scaled once, like every other mark
        // in this file.
        val k = size.width / 141f
        val mid = size.height / 2f

        val cover = 22f * k
        drawRoundRect(
            color = song.cover,
            topLeft = Offset(9f * k, mid - cover / 2f),
            size = Size(cover, cover),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(7f * k),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.45f),
            radius = 3.4f * k,
            center = Offset(9f * k + cover / 2f, mid),
        )

        repeat(4) { i ->
            val swing = if (live) 1f + sin(phase + i * 0.95f) else 1f
            val h = (4.5f + 5.2f * swing) * k
            drawRoundRect(
                color = ink,
                topLeft = Offset((87f + i * 6.6f) * k, mid - h / 2f),
                size = Size(3.2f * k, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.6f * k),
            )
        }

        drawCircle(
            color = ink.copy(alpha = 0.35f),
            radius = 2.5f * k,
            center = Offset(118.5f * k, mid),
        )

        if (tap > 0f) {
            drawCircle(
                color = ink.copy(alpha = 0.26f * (1f - tap)),
                radius = (5f + 24f * tap) * k,
                center = Offset(size.width / 2f, mid),
            )
        }
    }
}

/**
 * What the tap opens: cover, track, a line for how far in it is, and the three
 * controls.
 *
 * The transport is drawn rather than shipped as icons, and drawn to the same
 * arithmetic the real island uses — see `IslandView.drawSkip`. Two files
 * drawing the same three glyphs is worth it here: this one is a picture on a
 * card in a themed app, and that one is a Canvas on a window that has no theme
 * to look anything up in.
 */
@Composable
private fun IslandPlayer(
    song: IslandTrack,
    /** How far in, read at draw time — see the note in [VolumeArt]. */
    played: () -> Float,
    modifier: Modifier = Modifier,
) {
    val type = LocalEvType.current
    val ink = EV.OnCta
    Column(
        modifier
            .requiredSize(IslandOpenWidth, IslandOpenHeight)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(40.dp)) {
                drawRoundRect(
                    color = song.cover,
                    size = size,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.45f),
                    radius = size.minDimension * 0.14f,
                    center = Offset(size.width / 2f, size.height / 2f),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                EvText(
                    song.title,
                    type.tileLabel.copy(fontSize = 13.sp),
                    color = ink,
                    maxLines = 1,
                )
                EvText(song.artist, type.stamp, color = ink.copy(alpha = 0.55f), maxLines = 1)
            }
        }

        Spacer(Modifier.height(11.dp))
        Canvas(Modifier.fillMaxWidth().height(3.dp)) {
            val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
            drawRoundRect(color = ink.copy(alpha = 0.2f), size = size, cornerRadius = r)
            drawRoundRect(
                color = ink,
                size = Size(size.width * played().coerceIn(0f, 1f), size.height),
                cornerRadius = r,
            )
        }

        Spacer(Modifier.height(10.dp))
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val g = 7.dp.toPx()
            val spread = 30.dp.toPx()
            skipGlyph(cx - spread, cy, g, back = true, color = ink)
            // Playing, so it is a pause: two bars, the same height as the
            // triangles either side of it are tall.
            val bw = g * 0.5f
            val gap = g * 0.34f
            drawRoundRect(
                color = ink,
                topLeft = Offset(cx - gap - bw, cy - g * 0.82f),
                size = Size(bw, g * 1.64f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw * 0.3f),
            )
            drawRoundRect(
                color = ink,
                topLeft = Offset(cx + gap, cy - g * 0.82f),
                size = Size(bw, g * 1.64f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw * 0.3f),
            )
            skipGlyph(cx + spread, cy, g, back = false, color = ink)
        }
    }
}

/** Two stacked triangles, pointing the way they skip. */
private fun DrawScope.skipGlyph(cx: Float, cy: Float, g: Float, back: Boolean, color: Color) {
    val dir = if (back) -1f else 1f
    repeat(2) { i ->
        val ox = cx + dir * (i * g * 0.98f - g * 0.49f)
        val path = Path().apply {
            moveTo(ox + dir * g * 0.5f, cy)
            lineTo(ox - dir * g * 0.5f, cy - g * 0.82f)
            lineTo(ox - dir * g * 0.5f, cy + g * 0.82f)
            close()
        }
        drawPath(path, color)
    }
}

/** What is playing in the island, for the length of one loop. */
private class IslandTrack(val title: String, val artist: String, val cover: Color)

/**
 * Four of them, so the card is not showing the same song every time anybody
 * looks at it. The covers are flat colours rather than artwork: this is a
 * drawing of a player, and a real album cover on it would be the one thing on
 * the page claiming to be a photograph.
 */
private val IslandTracks = listOf(
    IslandTrack("Midnight City", "M83", Color(0xFF6C5CE7)),
    IslandTrack("Nightcall", "Kavinsky", Color(0xFFE8467C)),
    IslandTrack("Weightless", "Marconi Union", Color(0xFF12B886)),
    IslandTrack("Sunset Lover", "Petit Biscuit", Color(0xFFFF8A3D)),
)

/** How far into the track the player opens. */
private const val IslandStart = 0.16f

/** The player's own size. The lozenge grows into exactly this. */
private val IslandOpenWidth = 232.dp
private val IslandOpenHeight = 112.dp

/** The logo, bare, at the size the drawings set it at the top of the page. */
@Composable
fun AppMark(modifier: Modifier = Modifier, tile: Dp = 64.dp, spin: Boolean = false) {
    // Fast, then settled, then never quite still.
    //
    // Two animations rather than one curve, because they are two different
    // things: an entrance, which is over, and an idle, which is not. The
    // entrance runs four turns in a second and a half; the idle picks up from
    // wherever that finished and keeps going at a turn every seven seconds.
    //
    // The entrance must not decelerate to a *stop*, which is what every
    // ready-made ease-out does — and it showed: the mark visibly parked for
    // about a second before the idle picked it up again. [SpinIn] is a curve
    // whose slope at the end is the idle's own speed, so the hand-off is
    // invisible. The arithmetic: the idle turns 360° in 7s, so 51.4°/s; over the
    // entrance's 1.5s that is 77° of its 1440°, and a final slope of
    // 77/1440 = 0.054 in the curve's own normalised units, which is
    // (1 - y2) / (1 - x2) for a cubic bezier.
    val angle = remember { Animatable(0f) }
    LaunchedEffect(spin) {
        if (!spin) return@LaunchedEffect
        angle.animateTo(targetValue = 1440f, animationSpec = tween(1500, easing = SpinIn))
        // A fresh animateTo each turn rather than one enormous target: a float
        // angle that only ever grows loses precision, and an infinite spec
        // cannot start from the value the entrance left behind.
        while (true) {
            angle.animateTo(
                targetValue = angle.value + 360f,
                animationSpec = tween(7_000, easing = LinearEasing),
            )
        }
    }

    Box(modifier.size(tile), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(R.drawable.ic_logo),
            contentDescription = null,
            modifier = Modifier.size(tile * 26f / 64f).rotate(angle.value),
            colorFilter = ColorFilter.tint(EV.Ink),
        )
    }
}

// ---- detail chrome --------------------------------------------------------

/** A round button, 36dp, holding whatever glyph is put in it. */
@Composable
fun CircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(
        if (pressed) EV.SurfaceSunk else EV.Surface,
        label = "circle",
    )
    Box(
        modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(fill)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** The "<" in the back button. Two strokes, drawn rather than shipped. */
@Composable
fun BackChevron(modifier: Modifier = Modifier, color: Color = EV.Ink) {
    Canvas(modifier.size(16.dp)) {
        val w = 1.6.dp.toPx()
        val x = size.width * 0.62f
        val tip = size.width * 0.34f
        drawLine(
            color, Offset(x, size.height * 0.16f), Offset(tip, size.height / 2f),
            strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawLine(
            color, Offset(tip, size.height / 2f), Offset(x, size.height * 0.84f),
            strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

/** The 36x4 grab handle from the drawings. It marks where the page starts. */
@Composable
fun SheetHandle(modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(36.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(EV.SurfaceSunk),
    )
}

/**
 * A page behind a card: a way back, the card's own art at full size, and then
 * the settings themselves under a handle.
 *
 * The art is repeated on purpose. It is the only thing tying this page to the
 * card that opened it — there is no title bar in the drawings, and adding one
 * would say twice what the picture already says once.
 */
@Composable
fun DetailPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    art: @Composable BoxScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // The art lands after the page has arrived rather than with it, which is
    // what makes the two read as one movement instead of a cut.
    var landed by remember { mutableStateOf(false) }
    val t by animateFloatAsState(
        targetValue = if (landed) 1f else 0f,
        animationSpec = tween(460, delayMillis = 60, easing = FastOutSlowInEasing),
        label = "art",
    )
    LaunchedEffect(Unit) { landed = true }

    // The art does not scroll, and it does not grow either — that was tried and
    // it was too much. The page scrolls, and the settings ride up over the
    // picture like a sheet — which is why everything below the handle carries
    // its own opaque background and its own gutter, rather than the scroller
    // carrying them: a sheet with the page showing through its edges is not a
    // sheet, it is a column with a gap either side of it.
    val scroll = rememberScrollState()

    Box(modifier.fillMaxSize().background(EV.Background)) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = ArtTop)
                .height(ArtHeight)
                .graphicsLayer {
                    alpha = t
                    scaleX = 0.86f + 0.14f * t
                    scaleY = 0.86f + 0.14f * t
                },
            contentAlignment = Alignment.Center,
            content = art,
        )

        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Spacer(Modifier.height(46.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = EV.PageGutter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleButton(onBack) { BackChevron() }
                Spacer(Modifier.weight(1f))
            }
            // The window the art shows through. Exactly its height, so the two
            // line up without either knowing where the other is.
            Spacer(Modifier.height(ArtHeight))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(EV.Background)
                    .padding(horizontal = EV.PageGutter)
                    .padding(bottom = 128.dp),
            ) {
                SheetHandle(Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(22.dp))
                content()
            }
        }
    }
}

/**
 * The mark's entrance: fast off the mark, and ending at exactly the speed the
 * idle carries on at rather than at a standstill. See [AppMark].
 */
private val SpinIn = androidx.compose.animation.core.CubicBezierEasing(0f, 0.4f, 0.6f, 0.9786f)

/** How tall the picture at the top of a detail page is. */
private val ArtHeight = 230.dp

/**
 * Where it starts: under the 46dp of air at the top of the page and the 36dp
 * back button. The art is pinned rather than laid out in the column, so this is
 * the one number that has to agree with two places at once.
 */
private val ArtTop = 82.dp

