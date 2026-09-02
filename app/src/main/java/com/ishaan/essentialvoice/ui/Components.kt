package com.ishaan.essentialvoice.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/**
 * The kit the whole app is built from. Three rules run through all of it:
 * nothing casts a shadow, nothing is outlined, and nothing moves when you press
 * it — a press is a change of colour, never a change of position. Anything that
 * needs to stand off its background does it by being a different fill.
 */

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(start = 4.dp, top = 30.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EvText(text.uppercase(), LocalEvType.current.label)
    }
}

@Composable
fun EvText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    androidx.compose.foundation.text.BasicText(
        text = AnnotatedString(text),
        modifier = modifier,
        style = if (color == Color.Unspecified) style else style.copy(color = color),
        maxLines = maxLines,
    )
}

/** A flat card: a fill and nothing else. No elevation and no outline. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    fill: Color = EV.Surface,
    corner: androidx.compose.ui.unit.Dp = EV.CornerCard,
    padding: PaddingValues = PaddingValues(0.dp),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corner))
            .background(fill)
            .padding(padding),
        content = content,
    )
}

/**
 * Separates two rows inside a card. Kept when the outlines went, because a
 * settings list with nothing between the rows runs together — it is a
 * separator, not a border, and it is faint enough to read as one.
 */
@Composable
fun Hairline(inset: androidx.compose.ui.unit.Dp = 18.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = inset)
            .height(1.dp)
            .background(EV.Divider),
    )
}

/**
 * One line of settings: a title, an optional explanation, and something on the
 * right. The whole row is the target when [onClick] is given.
 */
@Composable
fun SettingRow(
    title: String,
    sub: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val type = LocalEvType.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(
        if (pressed && onClick != null) EV.SurfaceSunk else Color.Transparent,
        label = "rowbg",
    )

    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickableNoRipple(interaction, onClick)
                } else Modifier,
            )
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            EvText(title, type.body, color = if (enabled) EV.Ink else EV.InkFaint)
            if (sub != null) {
                Spacer(Modifier.height(3.dp))
                EvText(sub, type.sub, color = if (enabled) EV.InkMuted else EV.InkFaint)
            }
        }
        Spacer(Modifier.width(14.dp))
        trailing()
    }
}

@Composable
private fun Modifier.clickableNoRipple(
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = clickable(
    interactionSource = interaction,
    indication = null,
    onClick = onClick,
)

/** Track fills ink, knob goes pale. The knob is the only thing that travels. */
@Composable
fun EvSwitch(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val track by animateColorAsState(
        when {
            !enabled -> EV.SurfaceSunk
            checked -> EV.Ink
            else -> EV.SurfaceSunk
        },
        label = "track",
    )
    val knobColor by animateColorAsState(
        when {
            !enabled -> EV.InkFaint
            checked -> EV.OnInk
            else -> EV.InkFaint
        },
        label = "knob",
    )
    val offset by animateDpAsState(if (checked) 22.dp else 2.dp, label = "knob-x")
    val interaction = remember { MutableInteractionSource() }

    Box(
        Modifier
            .width(48.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(track)
            .then(
                if (enabled) Modifier.clickableNoRipple(interaction) { onChange(!checked) }
                else Modifier,
            ),
    ) {
        Box(
            Modifier
                .padding(start = offset)
                .align(Alignment.CenterStart)
                .size(24.dp)
                .clip(CircleShape)
                .background(knobColor),
        )
    }
}

enum class EvButtonKind { Primary, Quiet, Danger }

/** Press changes colour and nothing else — no lift, no scale, no travel. */
@Composable
fun EvButton(
    label: String,
    modifier: Modifier = Modifier,
    kind: EvButtonKind = EvButtonKind.Primary,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // Primary is ink, not yellow. Quiet used to be an outline around nothing,
    // so with outlines gone it has to be a fill or it stops looking like a
    // button at all. Danger keeps its red, which is the whole point of it.
    val fill by animateColorAsState(
        when {
            !enabled -> EV.SurfaceSunk
            kind == EvButtonKind.Primary && pressed -> EV.InkMuted
            kind == EvButtonKind.Primary -> EV.Ink
            kind == EvButtonKind.Danger && pressed -> EV.DangerFillSunk
            kind == EvButtonKind.Danger -> EV.DangerFill
            pressed -> EV.SurfaceSunk
            else -> EV.Background
        },
        label = "fill",
    )
    val content = when {
        !enabled -> EV.InkFaint
        kind == EvButtonKind.Danger -> EV.Red
        kind == EvButtonKind.Primary -> EV.OnInk
        else -> EV.Ink
    }

    Box(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(EV.CornerButton))
            .background(fill)
            .then(if (enabled) Modifier.clickableNoRipple(interaction, onClick) else Modifier)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        EvText(label.uppercase(), LocalEvType.current.button, color = content, maxLines = 1)
    }
}

/** Squared-off progress track, drawn as a filled bar rather than a sweep. */
@Composable
fun EvProgress(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(EV.SurfaceSunk),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(EV.Yellow),
        )
    }
}

/**
 * A value dragged rather than stepped.
 *
 * Here for the settings that are answers to "how big should it be" — questions
 * only the person looking at the phone can answer, and ones a pair of ± buttons
 * turns into twenty taps. Stepped values keep their steppers; this is for the
 * ones with a range wide enough that arriving at a number is the work.
 *
 * The knob is a fill on the track, not a floating handle: nothing in this kit
 * casts a shadow or sits above its own surface.
 */
@Composable
fun EvSlider(
    value: Int,
    range: IntRange,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit,
) {
    val span = (range.last - range.first).coerceAtLeast(1)
    val fraction = ((value - range.first).toFloat() / span).coerceIn(0f, 1f)
    var widthPx by remember { mutableFloatStateOf(0f) }

    fun emit(x: Float) {
        if (widthPx <= 0f) return
        val f = (x / widthPx).coerceIn(0f, 1f)
        onChange((range.first + Math.round(f * span)).coerceIn(range.first, range.last))
    }

    Box(
        modifier
            .fillMaxWidth()
            // A thin track is hard to catch; the row is tall enough to grab and
            // the track is drawn inside it.
            .height(38.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(range) {
                detectDragGestures(
                    onDragStart = { emit(it.x) },
                    onDrag = { change, _ ->
                        change.consume()
                        emit(change.position.x)
                    },
                )
            }
            .pointerInput(range) {
                detectTapGestures { emit(it.x) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(EV.SurfaceSunk),
        )
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(EV.Ink),
        )
    }
}

/** A small status pip: yellow when live, hollow when not. */
@Composable
fun StatusPip(on: Boolean, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (on) EV.Yellow else EV.InkFaint),
        )
        Spacer(Modifier.width(8.dp))
        EvText(label.uppercase(), LocalEvType.current.label, color = if (on) EV.Ink else EV.InkMuted)
    }
}

/** Segmented picker. The selected cell fills; the others are only outlined. */
@Composable
fun EvSegmented(
    options: List<Pair<String, String>>,
    selectedId: String,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(EV.SurfaceSunk)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (id, text) ->
            val selected = id == selectedId
            val interaction = remember(id) { MutableInteractionSource() }
            val fill by animateColorAsState(
                if (selected) EV.Ink else Color.Transparent, label = "seg-$id",
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(fill)
                    .clickableNoRipple(interaction) { onSelect(id) },
                contentAlignment = Alignment.Center,
            ) {
                EvText(
                    text.uppercase(),
                    LocalEvType.current.button,
                    color = if (selected) EV.OnInk else EV.InkMuted,
                    maxLines = 1,
                )
            }
        }
    }
}
