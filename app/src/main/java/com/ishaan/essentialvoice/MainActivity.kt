package com.ishaan.essentialvoice

import android.Manifest
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import com.ishaan.essentialvoice.ui.EV
import com.ishaan.essentialvoice.ui.EssentialVoiceTheme
import com.ishaan.essentialvoice.ui.HomeScreen
import com.ishaan.essentialvoice.ui.LearnKeyScreen
import com.ishaan.essentialvoice.whisper.ModelDownloader
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    /** Permissions live outside the settings store, so they get their own state. */
    private var setupState by mutableStateOf<SetupState?>(null)

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private val bluetoothPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The palette is set before the first frame rather than inside
        // composition, so the window and the system bars agree with the page
        // from the very first pixel. A change of system theme recreates the
        // activity, which runs this again.
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        prefs = Prefs.get(this)
        prefs.learnMode = false
        refresh()

        setContent {
            val settings by prefs.state.collectAsState()
            val dark = when (settings.theme) {
                Prefs.THEME_LIGHT -> false
                Prefs.THEME_DARK -> true
                else -> systemDark
            }
            // The bars are told about the palette from inside composition, so
            // the toggle in the masthead moves them at the same moment it moves
            // the page — an activity recreate would flash.
            LaunchedEffect(dark) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }

            EssentialVoiceTheme(dark = dark) {
                val context = LocalContext.current
                val download by ModelDownloader.state.collectAsState()
                val update by Updater.state.collectAsState()
                val scope = rememberCoroutineScope()
                var learning by remember { mutableStateOf(false) }
                val setup = setupState ?: Setup.read(context)

                // What's new needs the manifest to have been read, and nobody
                // opens an app in order to press a button called "check". Only
                // once, and only if nothing has looked yet this run.
                LaunchedEffect(Unit) {
                    if (Updater.state.value is Updater.State.Idle) Updater.check(context)
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(EV.Background)
                        .windowInsetsPadding(WindowInsets.systemBars),
                ) {
                    // The home screen is always composed, and learn mode is drawn
                    // *over* it rather than instead of it.
                    //
                    // Which page is open, where it is scrolled to and what is
                    // expanded on it all live inside HomeScreen. Swapping it out
                    // for the learn screen threw every one of them away, so
                    // teaching the app the key — which is a row on the Essential
                    // voice page — put you back on the launcher afterwards.
                    // LearnKeyScreen fills the box and paints its own background,
                    // so covering is as complete as replacing was.
                    //
                    // No walkthrough on the way in either. It was nine steps in
                    // front of a first run, and the home screen already says what
                    // is outstanding — see the Set up section, which is the first
                    // thing on the Essential voice card and which puts the three
                    // permissions away once they are granted.
                    HomeScreen(
                        setup = setup,
                        settings = settings,
                        prefs = prefs,
                        download = download,
                        update = update,
                        onRequestMic = {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onRequestNotifications = {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        onRequestBluetooth = {
                            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        },
                        onCheckUpdate = { scope.launch { Updater.check(context) } },
                        onGetUpdate = { release -> Updater.openRelease(context, release) },
                        onLearnKey = {
                            prefs.clearSeenKey()
                            prefs.learnMode = true
                            learning = true
                        },
                        onDownload = { tier ->
                            scope.launch {
                                ModelDownloader.download(context, tier)
                                refresh()
                            }
                        },
                        onDeleteModel = { tier ->
                            ModelDownloader.delete(context, tier)
                            refresh()
                        },
                        onCancelDownload = { ModelDownloader.cancel() },
                    )
                    if (learning) {
                        LearnKeyScreen(
                            setup = setup,
                            settings = settings,
                            prefs = prefs,
                            accessibilityOn = setup.accessibility,
                            onDone = {
                                prefs.learnMode = false
                                learning = false
                                refresh()
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        // Learn mode swallows keys; never leave it armed behind the user's back.
        prefs.learnMode = false
        super.onPause()
    }

    private fun refresh() {
        setupState = Setup.read(this)
    }
}
