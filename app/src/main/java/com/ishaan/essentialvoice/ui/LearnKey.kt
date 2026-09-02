package com.ishaan.essentialvoice.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ishaan.essentialvoice.Prefs
import com.ishaan.essentialvoice.Setup

/**
 * Teaches the app which key the Essential Key is.
 *
 * The keycode is not a constant anyone can look up — it is whatever this build
 * of Nothing OS reports — so the only honest way to know it is to have the user
 * press the key once and read what arrives.
 */
@Composable
fun LearnKeyScreen(
    setup: com.ishaan.essentialvoice.SetupState,
    settings: com.ishaan.essentialvoice.Settings,
    prefs: Prefs,
    accessibilityOn: Boolean,
    onDone: () -> Unit,
) {
    val type = LocalEvType.current
    val context = LocalContext.current
    val (seenKey, seenScan) = prefs.seenKey.collectAsState().value
    val seen = seenKey > 0 || seenScan > 0
    // The Essential Key has no key-layout entry, so it arrives as
    // KEYCODE_UNKNOWN and only the scancode identifies it. Show whichever
    // one actually names the key.
    val ident = if (seenKey > 0) "KEYCODE" else "SCANCODE"
    val value = if (seenKey > 0) seenKey else seenScan

    // Back means "stop learning", and it has to say so out loud.
    //
    // This screen is drawn over the home screen rather than instead of it, so
    // without a handler here the back press went to the *page underneath* —
    // closing a detail page nobody could see and leaving learn mode on, which
    // leaves the Essential Key swallowed and doing nothing. Registered after
    // the home screen's own, so it is the one that wins.
    BackHandler { onDone() }

    // While this screen is up, learn mode is alive. The service stops honouring
    // the flag two minutes after this stops saying so, which is what makes a
    // learn session that ends badly cost a couple of minutes instead of a dead
    // key — see Prefs.learnModeLive.
    LaunchedEffect(Unit) {
        while (true) {
            prefs.keepLearnModeAlive()
            kotlinx.coroutines.delay(20_000)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(EV.Background)
            .padding(EV.PagePadding),
    ) {
        Spacer(Modifier.height(28.dp))
        EvText("Press the", type.display, color = EV.InkMuted)
        EvText("Essential Key", type.display)
        Spacer(Modifier.height(14.dp))
        EvText("One short press is enough.", type.sub)

        Spacer(Modifier.height(34.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(190.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(if (seen) EV.Yellow else EV.Surface),
            contentAlignment = Alignment.Center,
        ) {
            if (seen) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EvText(ident, type.label, color = EV.Ink)
                    Spacer(Modifier.height(8.dp))
                    EvText("$value", type.display)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    repeat(3) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(EV.InkFaint),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Directly under the box that is waiting for a press: the moment
        // someone works out they have no key to press is the moment they are
        // staring at it.
        Panel(fill = EV.Surface) {
            Disclosure("Don't have Essential Key?") {
                OtherWaysPanel(setup, settings, prefs, bare = true)
            }
        }

        Spacer(Modifier.height(16.dp))

        if (!accessibilityOn) {
            EvText(
                "The accessibility service is off, so no key will arrive. " +
                    "Turn it on first.",
                type.sub,
                color = EV.Red,
            )
            Spacer(Modifier.height(14.dp))
            EvButton("Open accessibility settings") { Setup.openAccessibilitySettings(context) }
        } else if (seen && seenKey > 0) {
            // Only ever said once something has actually arrived, and only when
            // there is something to act on: that the key you just pressed might
            // be the wrong one. The box itself says it is waiting, so it does
            // not need a line underneath saying so as well.
            EvText(
                "If that is not the Essential Key, press again — the last key " +
                    "you press is the one that gets saved.",
                type.sub,
            )
        }

        Spacer(Modifier.weight(1f))

        Spacer(Modifier.height(18.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EvButton("Cancel", kind = EvButtonKind.Quiet, onClick = onDone)
            EvButton(
                "Save this key",
                enabled = seen,
            ) {
                prefs.setTrigger(seenKey, seenScan)
                onDone()
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}
