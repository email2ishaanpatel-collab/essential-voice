package com.ishaan.essentialvoice

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.ishaan.essentialvoice.ui.EV
import com.ishaan.essentialvoice.ui.EvButton
import com.ishaan.essentialvoice.ui.EvButtonKind
import com.ishaan.essentialvoice.ui.EssentialVoiceTheme
import com.ishaan.essentialvoice.ui.EvText
import com.ishaan.essentialvoice.ui.LocalEvType
import androidx.compose.runtime.mutableIntStateOf
import com.ishaan.essentialvoice.ui.EvSlider
import com.ishaan.essentialvoice.volume.VolumeSliderView
import com.ishaan.essentialvoice.voice.PillView
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Drag the real pill to wherever it should appear.
 *
 * It is the same PillView the overlay uses rather than a mock-up, so what is
 * dragged here is exactly what shows up over other apps — size, dots and all.
 */
class PlacementActivity : ComponentActivity() {

    companion object {
        /** Place the volume slider rather than the pill. */
        const val EXTRA_VOLUME = "volume"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        EV.useDark(dark)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }

        val prefs = Prefs.get(this)

        val volume = intent?.getBooleanExtra(EXTRA_VOLUME, false) == true

        setContent {
            EssentialVoiceTheme(dark = dark) {
                if (volume) {
                    VolumePlacementScreen(
                        initialLeft = prefs.now.volumeSide == Prefs.SIDE_LEFT,
                        initialY = prefs.now.volumeY,
                        initialLength = prefs.now.volumeHeightDp,
                        initialWidth = prefs.now.volumeWidthDp,
                        onSave = { left, y, len, wide ->
                            prefs.setVolumeSide(if (left) Prefs.SIDE_LEFT else Prefs.SIDE_RIGHT)
                            prefs.setVolumeY(y)
                            prefs.setVolumeHeightDp(len)
                            prefs.setVolumeWidthDp(wide)
                            finish()
                        },
                        onCancel = { finish() },
                    )
                    return@EssentialVoiceTheme
                }
                PlacementScreen(
                    initialX = prefs.now.pillX,
                    initialY = prefs.now.pillY,
                    onSave = { x, y ->
                        prefs.setPlacement(x, y)
                        finish()
                    },
                    onCancel = { finish() },
                )
            }
        }
    }
}

/**
 * How close to the bezel an edge-snapped pill sits, in dp from the screen edge
 * to the *pill*, matching the reference. The pill is narrower than its window,
 * so the window inset is smaller than this looks.
 */
private const val EDGE_MARGIN_DP = 10f

/**
 * The three columns the pill snaps to: hard against the left bezel, dead centre,
 * and hard against the right. Everything between them is free — only these three
 * pull.
 */
private fun snapColumns(widthPx: Float, density: Float): FloatArray {
    if (widthPx <= 0f) return floatArrayOf(0.5f)
    val halfPill = PillView.PILL_W_DP * density / 2f
    val edge = (EDGE_MARGIN_DP * density + halfPill) / widthPx
    return floatArrayOf(edge, 0.5f, 1f - edge)
}

private const val SNAP_RANGE = 0.05f

/**
 * The snapped position, for display and saving.
 *
 * Snapping must never be written back into the position the finger is driving.
 * Doing that throws away movement that has not yet crossed out of a column, so
 * every drag restarts from the column and the pill alternates between sticking
 * and jumping. The raw position accumulates; this is only a view of it.
 */
private fun snapX(v: Float, columns: FloatArray): Float {
    for (c in columns) if (abs(v - c) < SNAP_RANGE) return c
    return v
}

@Composable
private fun PlacementScreen(
    initialX: Float,
    initialY: Float,
    onSave: (Float, Float) -> Unit,
    onCancel: () -> Unit,
) {
    val type = LocalEvType.current
    val density = LocalDensity.current

    // What the finger says, unsnapped. The single source of truth.
    var rawX by remember { mutableFloatStateOf(initialX) }
    var rawY by remember { mutableFloatStateOf(initialY) }
    var dragging by remember { mutableStateOf(false) }
    var pill by remember { mutableStateOf<PillView?>(null) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(EV.Background),
    ) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val boxW = with(density) { PillView.WINDOW_W_DP.dp.toPx() }
        val boxH = with(density) { PillView.WINDOW_H_DP.dp.toPx() }

        val columns = snapColumns(wPx, density.density)
        val x = snapX(rawX, columns)
        val y = rawY
        val onColumn = columns.any { abs(x - it) < 1e-4f }

        DotBackdrop(columns, dragging && onColumn)

        // Positioned by translation rather than by layout: this runs in the draw
        // phase, so dragging never re-measures anything.
        Box(
            Modifier
                .size(PillView.WINDOW_W_DP.dp, PillView.WINDOW_H_DP.dp)
                .graphicsLayer {
                    translationX =
                        (x * wPx - boxW / 2f).coerceIn(0f, (wPx - boxW).coerceAtLeast(0f))
                    translationY =
                        (y * hPx - boxH / 2f).coerceIn(0f, (hPx - boxH).coerceAtLeast(0f))
                },
        ) {
            AndroidView(
                factory = { ctx ->
                    PillView(ctx).apply {
                        reset(PillView.State.LISTENING)
                        pill = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // A synthetic waveform so the pill looks alive while being positioned.
        LaunchedEffect(pill) {
            val v = pill ?: return@LaunchedEffect
            var t = 0f
            while (true) {
                t += 0.55f
                // Already shaped, like every other caller since the engines
                // stopped disagreeing about the scale.
                v.pushLevel(0.5f + 0.5f * (1f + kotlin.math.sin(t.toDouble()).toFloat()) / 2f)
                delay(70)
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(wPx, hPx) {
                    // Offset between finger and pill centre, so the pill does not
                    // teleport under the fingertip when a drag starts on it.
                    // Grabbing outside the pill instead brings it to you.
                    var grabDx = 0f
                    var grabDy = 0f
                    detectDragGestures(
                        onDragStart = { touch ->
                            dragging = true
                            val cx = rawX * wPx
                            val cy = rawY * hPx
                            val onPill = abs(touch.x - cx) <= boxW / 2f &&
                                abs(touch.y - cy) <= boxH / 2f
                            grabDx = if (onPill) touch.x - cx else 0f
                            grabDy = if (onPill) touch.y - cy else 0f
                            if (!onPill) {
                                rawX = (touch.x / wPx).coerceIn(0f, 1f)
                                rawY = (touch.y / hPx).coerceIn(0.03f, 0.97f)
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            // Absolute, not accumulated: the pill tracks the
                            // finger exactly instead of drifting away from it.
                            rawX = ((change.position.x - grabDx) / wPx).coerceIn(0f, 1f)
                            rawY = ((change.position.y - grabDy) / hPx).coerceIn(0.03f, 0.97f)
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    )
                },
        )

        Column(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(EV.PagePadding)
                .padding(top = 34.dp),
        ) {
            EvText("Drag the pill", type.display)
            Spacer(Modifier.height(8.dp))
            EvText(
                "Anywhere you like. It pulls to the two edges and the centre \u2014 " +
                    "the three guides \u2014 and is free everywhere else.",
                type.sub,
            )
            Spacer(Modifier.height(12.dp))
            EvText(
                "%d%% ACROSS   \u00b7   %d%% DOWN".format((x * 100).toInt(), (y * 100).toInt()),
                type.label,
                color = EV.Ink,
            )
        }

        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(EV.PagePadding)
                .padding(bottom = 26.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            EvButton("Cancel", kind = EvButtonKind.Quiet, onClick = onCancel)
            EvButton("Reset", kind = EvButtonKind.Quiet) {
                rawX = columns.first()
                rawY = 0.55f
            }
            Spacer(Modifier.weight(1f))
            EvButton("Save") { onSave(x, y) }
        }
    }
}

/**
 * Faint dot grid as a stand-in for the app underneath, plus the three snap
 * columns drawn where they actually are — so "edge" is something you can see the
 * pill land on rather than a number to guess at.
 */
@Composable
private fun DotBackdrop(columns: FloatArray, snapped: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        val step = 28.dp.toPx()
        var yy = step
        while (yy < size.height) {
            var xx = step
            while (xx < size.width) {
                drawCircle(EV.Divider, 1.6f, Offset(xx, yy))
                xx += step
            }
            yy += step
        }
        columns.forEach { c ->
            drawLine(
                if (snapped) EV.Yellow else EV.InkFaint,
                Offset(size.width * c, 0f),
                Offset(size.width * c, size.height),
                strokeWidth = if (snapped) 3f else 1.5f,
            )
        }
    }
}


/**
 * Where the volume slider sits, and how long it is.
 *
 * The same idea as the pill's screen and deliberately not the same gesture: this
 * shape is *attached* to a border, so there is no x to drag. Sideways means the
 * other edge — drag past the middle and it changes sides — and up and down is
 * the only real position there is. The length is a slider rather than a stepper
 * because it spans three hundred dp and nobody arrives at the right one by
 * tapping a plus sign forty times.
 *
 * It is the real [VolumeSliderView], not a mock-up, so what is dragged here is
 * exactly what appears over other apps — which is the whole reason this screen
 * cannot sit on the app's own page colour. The slider's enclosure is pure black
 * and the dark palette's page is true black, so on this one screen the two were
 * the same colour and there was nothing to drag but a column of white dots. The
 * page is a step lighter here, and only here: it is a canvas the object is being
 * placed on rather than the app's background.
 *
 * Length and width both live on this screen. They were a slider in the settings
 * list and a constant in the code, which meant the two questions the screen
 * exists to answer — where does it go, and how big is it — were answered in two
 * different places, only one of which shows you the answer.
 */
@Composable
private fun VolumePlacementScreen(
    initialLeft: Boolean,
    initialY: Float,
    initialLength: Int,
    initialWidth: Int,
    onSave: (Boolean, Float, Int, Int) -> Unit,
    onCancel: () -> Unit,
) {
    val type = LocalEvType.current
    val density = LocalDensity.current

    var onLeft by remember { mutableStateOf(initialLeft) }
    var rawY by remember { mutableFloatStateOf(initialY) }
    var length by remember { mutableIntStateOf(initialLength) }
    var thickness by remember { mutableIntStateOf(initialWidth) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(EV.SurfaceSunk),
    ) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val boxW = with(density) { thickness.dp.toPx() }
        val boxH = with(density) {
            VolumeSliderView.windowHeightDp(length.toFloat()).dp.toPx()
        }

        DotBackdrop(floatArrayOf(), false)

        Box(
            Modifier
                .size(
                    thickness.dp,
                    VolumeSliderView.windowHeightDp(length.toFloat()).dp,
                )
                .graphicsLayer {
                    translationX = if (onLeft) 0f else (wPx - boxW).coerceAtLeast(0f)
                    translationY =
                        (rawY * hPx - boxH / 2f).coerceIn(0f, (hPx - boxH).coerceAtLeast(0f))
                },
        ) {
            AndroidView(
                factory = { ctx ->
                    VolumeSliderView(ctx).apply {
                        // A stand-in reading, so the preview shows a real
                        // slider rather than an empty enclosure.
                        columns = listOf(
                            VolumeSliderView.Col(
                                stream = 3,
                                icon = R.drawable.ic_vol_media,
                                level = 10,
                                max = 16,
                                vibrate = false,
                            ),
                        )
                        refreshAll()
                    }
                },
                update = {
                    it.onLeft = onLeft
                    it.thicknessDp = thickness.toFloat()
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(wPx, hPx) {
                    detectDragGestures(
                        onDrag = { change, _ ->
                            change.consume()
                            // Which half the finger is in *is* which edge it is
                            // on. Nothing to accumulate: there are two answers.
                            onLeft = change.position.x < wPx / 2f
                            rawY = (change.position.y / hPx).coerceIn(0.03f, 0.97f)
                        },
                    )
                },
        )

        // The page's own margin on both sides. It used to carry a further 52dp
        // of start padding to clear the slider, which is 38dp wide and only on
        // screen when it has been dragged to the top — so the words were pushed
        // a third of the way across the screen to miss something that is
        // usually nowhere near them.
        Column(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(EV.PagePadding)
                .padding(top = 34.dp),
        ) {
            EvText("Drag the slider", type.display)
            Spacer(Modifier.height(8.dp))
            EvText(
                "Up and down for where it sits. Across the middle to put it on " +
                    "the other edge.",
                type.sub,
            )
            Spacer(Modifier.height(12.dp))
            EvText(
                "%s   \u00b7   %d%% DOWN   \u00b7   %d \u00d7 %ddp".format(
                    if (onLeft) "LEFT" else "RIGHT",
                    (rawY * 100).toInt(),
                    thickness,
                    length,
                ),
                type.label,
                color = EV.Ink,
            )
        }

        // Same margin as the top, and for a sharper reason: the button row was
        // laid out inside 46dp of start padding and 12 of end, which left it
        // three dp narrower than the three buttons in it — and the three dp
        // that went missing were the right-hand end of Save.
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(EV.PagePadding)
                .padding(bottom = 26.dp)
                // On a plate of its own, and it has to be: the page is
                // [EV.SurfaceSunk] here so the black slider has something to
                // show up against, and a slider's *track* is that same colour —
                // so the two length controls below were a white fill running
                // along an invisible rail, with nothing to say how much further
                // it could go. The panel puts the track back on a different
                // colour from the page.
                .clip(RoundedCornerShape(EV.CornerCard))
                .background(EV.Surface)
                .padding(18.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                EvText("Length", type.body, Modifier.weight(1f))
                EvText("${length}dp", type.mono, color = EV.Ink)
            }
            Spacer(Modifier.height(6.dp))
            EvSlider(value = length, range = 90..400) { length = it }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                EvText("Width", type.body, Modifier.weight(1f))
                EvText("${thickness}dp", type.mono, color = EV.Ink)
            }
            Spacer(Modifier.height(6.dp))
            EvSlider(
                value = thickness,
                range = VolumeSliderView.MIN_WIDTH_DP.toInt()..
                    VolumeSliderView.MAX_WIDTH_DP.toInt(),
            ) { thickness = it }

            Spacer(Modifier.height(16.dp))
            // fillMaxWidth is load-bearing, not tidiness: without it the Row
            // wraps its content, the weighted Spacer has nothing to expand into,
            // and the three buttons overflow the screen — which drops "Save" off
            // the end entirely rather than making it look wrong.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EvButton("Cancel", kind = EvButtonKind.Quiet, onClick = onCancel)
                EvButton("Reset", kind = EvButtonKind.Quiet) {
                    onLeft = true
                    rawY = 0.55f
                    length = VolumeSliderView.HEIGHT_DP.toInt()
                    thickness = VolumeSliderView.WIDTH_DP.toInt()
                }
                Spacer(Modifier.weight(1f))
                EvButton("Save") { onSave(onLeft, rawY, length, thickness) }
            }
        }
    }
}
