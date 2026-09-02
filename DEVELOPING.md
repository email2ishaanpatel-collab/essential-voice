# Essential Voice

Hold-to-talk dictation for the CMF Phone 2 Pro. A yellow pill appears over
whatever you are in, whisper.cpp transcribes what you said on the phone, and the
text lands in the field you were already typing in. Nothing is uploaded.

The laptop equivalent is the hold-Super-to-talk pill in the Cinnamon
dynamic-island extension; this is the same idea with the Essential Key in place
of Super.

---

## Building

```bash
git clone --depth 1 https://github.com/ggml-org/whisper.cpp.git whisper-src
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`whisper-src/` is deliberately not checked in — it is a pinned upstream checkout,
and CMake fails with a readable message if it is missing. Needs NDK 27.2 and
CMake 3.22 (`sdkmanager "ndk;27.2.12479018" "cmake;3.22.1"`). Only `arm64-v8a` is
built, because only one phone is the target.

## The v3 test build, and what is not in it

`Features.kt` is a handful of `const val`s and it is what the v3 test build
actually is:

| Flag | In v3 |
|---|---|
| `ISLAND` | no — takes the island's media player with it |
| `BACK_TAP` | no |
| `GEMINI` | no |
| `GAME_MODE` | no |
| `LIKES` | yes, but only in a build with a `supabase.properties` |

They are `false`, not deleted. Everything they gate still compiles, still has its
tests-by-hand written up in this file, and comes back with one word and a
rebuild. `const val false` also means the compiler drops the branch, so a gated
feature is not merely hidden: `EssentialKeyService` never attaches the island's
window or the accelerometer, and nothing costs anything.

Two components are switched off in the manifest for the same reason, because a
flag cannot reach them: `.game.GameTile` (a Quick Settings tile the user would
otherwise still see) and `.media.MediaObserver` (a notification listener that
should not appear in the phone's notification-access list in a build with no
island to read it).

What *is* in v3: notes and note editing, both widgets, dark mode, the two-tab
page, pill styles, quality tiers, the bottom bar, the earbuds widget and tile,
the assistant role, and the walkthrough below.

### `./test.sh` — build, install, and hand it back as a new install

```bash
./test.sh              # build, install, reset every setting
./test.sh --keep       # build and install, leave the settings alone
./test.sh --models     # also delete the downloaded models
./test.sh --no-build   # install what is already in test-build/
```

The reset is the point of the script. Every build is meant to be looked at with
a new user's eyes, and a settings store that has survived ten builds is the one
thing that cannot be. It is `run-as … rm -rf shared_prefs` plus the notes file —
deliberately **not** `pm clear`, which would take the ~150MB model with it and
turn every test into a download. `--models` is for when the download is the thing
being tested.

`run-as` works because the debug build is debuggable; it is signed with the
release key, so it still drops on top of a release without an uninstall.

### The walkthrough

`ui/SetupGuide.kt`, nine steps, shown when `Prefs.setupGuideDone` is false — which
`test.sh` wipes with the rest of the store, so every test build opens on it.

The settings page can explain every permission and does, but it is forty rows and
the four that have to happen before anything works are scattered through it. The
guide is a path through the same controls, not a second set of them: each step
opens the same system screen the settings row does, and reads the same
`SetupState` back to say whether the grant actually took.

Two things about it are deliberate. The forward button says **Later**, not
nothing, when a step is outstanding — a model can be downloaded afterwards and
the key can be taught afterwards, and a wall is a worse answer than a label that
says what going on now means. And the button in a step sits on a `Panel`: a
`Quiet` button is a fill the colour of the page, and on the page itself a granted
step read as a line of text that happened to be tappable.

---

## Setting it up on the phone

These cannot be done over adb, because Android will not let a sideloaded app
grant itself any of them. **Reinstalling the app switches the accessibility
service back off** — `Bound services:{}` in `dumpsys accessibility` — so step 1
has to be repeated after every `adb install`:

1. **Settings → Accessibility → Essential Voice → on.** If the switch is greyed
   out, the app was sideloaded: Settings → Apps → Essential Voice → ⋮ → *Allow
   restricted settings*, then come back. `settings put secure
   enabled_accessibility_services …` over adb is silently reverted on Nothing OS
   4 — this has to be the real switch.
2. **Draw over other apps → on.**
3. **Microphone** — the app asks.

Then, in the app: **Teach it the key** and press the Essential Key once.

## Publishing it

```bash
./release.sh          # build the current version
./release.sh 2 1.1    # bump to versionCode 2 / versionName 1.1, then build
```

Both files land in `dist/`, and they go to **two different places**:

| File | Where | Why there |
|---|---|---|
| `essential-voice-<name>.apk` | a GitHub Release | what people download |
| `update.json` | `main` of `publish/` | installed copies read it to learn a newer build exists |

```bash
gh release create v1.1 dist/essential-voice-1.1.apk --repo <owner>/essential-voice
cp dist/update.json publish/ && (cd publish && git commit -am v1.1 && git push)
```

**It has to be an APK.** An `.aab` is a Play Store upload format — Android cannot
install one, so a bundle on a website is a dead link.

### Why the manifest is not on the Framer site

`UPDATE_MANIFEST_URL` is compiled into every build, so it needs a URL that never
moves while its *contents* change every release. Framer cannot do that: uploaded
assets get content-hashed `framerusercontent.com` URLs, so a new `update.json`
would land at a new address and every installed copy would stop finding it. A
`raw.githubusercontent.com` path on `main` is stable and mutable, which is
exactly the combination required.

The site's download button points straight at the release asset. Only the button
lives on Framer; the files do not.

### The signing key

`essential-voice-release.jks` and `keystore.properties`, both gitignored, both
**irreplaceable**. Android refuses an update signed with a different key, so
losing them means every existing install has to be uninstalled before a new
version will go on. Back them up somewhere that is not this laptop.

### Who it will run on

`arm64-v8a` only, and only on a CPU with `asimdhp` and `asimddp` — armv8.2-a, so
roughly 2018 and newer. That is what makes it fast, and it is checked at startup:
an older phone is told plainly instead of taking a SIGILL inside a matrix
multiply. A 32-bit-only phone will not see the app as compatible at all.

## The Essential Key

There is no constant to hardcode. On this phone the key arrives as:

```
/dev/input/event0   "gpio-keys"   KEY_VOLUMEDOWN, 00fa
```

`00fa` is scancode 250, and **no key layout maps it** — `gpio-keys` falls back to
`Generic.kl`, which has no entry — so Android reports it as `KEYCODE_UNKNOWN`
(0). The keycode therefore identifies nothing and the *scancode* is the only
handle on the key. `EssentialKeyService.matchesTrigger` prefers the scancode for
exactly this reason, and the learn screen stores both.

Whether a third-party accessibility service sees the key at all is a property of
this firmware, not of this app: if `NtEssentialKeyImpl` consumes it in the
window-manager policy, nothing downstream — including us — is offered it. The
learn screen answers that question in one press: a number appears, or it does
not.

Two settings on the phone are worth knowing about:

```
nt_block_essential_key               = 1
nt_essential_key_mistouch_prevention = 1
```

### "Take over the key" cannot stop Essential Space

`consumeKey` makes `onKeyEvent` return `true`, and that is genuinely useful: it
hides the key from whatever app is in the foreground, so a game or a browser
that binds it does not also react while you are dictating.

It cannot stop Essential Space, and no value it could return would. An
accessibility service with `flagRequestFilterKeyEvents` sits *above the focused
app* and *below the window-manager policy* — and `NtEssentialKeyImpl` is in the
policy. It is the same shape as `KEYCODE_POWER`: consumed before accessibility
filtering is consulted, which is exactly why a long press on power can always
force a restart. Returning `true` from a place the event has already passed
changes nothing.

So the switch stays, honestly labelled, and the Behaviour section says plainly
that switching Essential Space off is done on the phone rather than in the app.
Whether the phone offers a way to switch it off at all is still open — see the
open questions at the bottom of this file.

**This is also why the tap mode was removed in 3.0.** Tap needed the *first*
press to mean "start listening", and the first press had already opened
Essential Space before the service saw it. Hold is the only gesture on this key
that an app can actually be given.

### Triggers, in order of preference

| Trigger | Where | Notes |
|---|---|---|
| Essential Key | anywhere | needs the accessibility service; hold only |
| Swipe in from a bottom corner | anywhere | the assist gesture — see below |
| Press and hold power | anywhere | the same role, the same launch |
| Double tap the back | anywhere, screen on | accelerometer; see below |
| Quick Settings tile | anywhere | tap to start, tap to send |
| `TriggerActivity` | shortcut / key remap | launching it toggles dictation |

Only the first is *held*. Everything else is a toggle, which is why everything
else raises the bar — see **The bottom bar**.

### The power button

`KEYCODE_POWER` is in `EssentialKeyService.RESERVED` and always will be. The
window manager policy consumes it in `interceptKeyBeforeQueueing`, before
accessibility key filtering is offered anything — which is precisely why a long
press can always force a restart regardless of what an app has done. It cannot
be intercepted, and an app that could intercept it would be a serious problem.

What the system *does* offer is the assistant role. `VoiceAssistService`
registers as a `VoiceInteractionService`, so the app can be chosen as the digital
assistant and reached by "press and hold power button → Digital assistant". The
session draws nothing — `setUiEnabled(false)`, toggle, `hide()` — because the
pill is the whole interface and an assistant scrim would cover the app being
dictated into. `VoiceAssistRecognitionService` is a stub that exists only because
`<voice-interaction-service>` will not validate without a recognition service
named.

The system hands over one launch rather than a key down and up, so this behaves
like Tap mode whatever the trigger setting says. It also takes the gesture away
from Gemini.

### Holding the home bar does not work on this phone, and here is the measurement

The theory was right and the phone disagrees. Read off it, on gesture navigation
(`navigation_mode=2`):

```
assistant                      com.google.android.googlequicksearchbox/…GsaVoiceInteractionService
assist_touch_gesture_enabled   0     <- swipe in from a bottom corner
search_all_entrypoints_enabled 0     <- Circle to Search
```

Circle to Search is **not** a navigation-bar API — holding the gesture handle is
the ordinary assist invocation, and Google's version is that last flag layered on
top of the same route. That much is true, and the assist route itself works: with
the role held, `input keyevent 219` raises the bar and starts a dictation, so
`VoiceAssistSession.onShow` is reached and everything downstream of it is fine.

**But Nothing OS 4 does not route a handle long-press to the assistant at all.**
Measured, with the role held by this app:

| Gesture | Result |
|---|---|
| `input keyevent 219` (ASSIST) | dictation starts |
| long press on the handle, 700ms and 1200ms, at three heights | nothing |
| `search_all_entrypoints_enabled=1`, then long press again | nothing |
| `assist_touch_gesture_enabled=1`, then a diagonal corner swipe | dictation starts |

Injected touches are a valid test here, because the corner swipe *does* work
through the same injection — so the dead long-press is the ROM, not the harness.
Setting `search_all_entrypoints_enabled` was reverted afterwards; it changed
nothing.

So the gesture to tell people about is the **corner swipe**, and it needs
`assist_touch_gesture_enabled` on — it ships at 0 on this phone. The settings
screen says corner swipe rather than home bar for that reason.

**2026-08-31, the same question asked again with the launcher's own log open,
which is what finally names the owner.** `com.nothing.launcher` handles the hold
itself, through Google's `NavHandleLongPressHandler`, and it offers it to exactly
one thing:

```
NavHandleLongPressHandler: Contextual Search invocation: touch started
ContextualSearchInvoker:   Contextual Search invocation failed: setting disabled
NavHandleLongPressHandler: Contextual Search invocation failed: precondition not satisfied
```

With `search_all_entrypoints_enabled=1` the same hold logs *invocation
successful* and opens `com.google.android.googlequicksearchbox/…lens.ContextualSearchEntrypoint`.
There is no fall-through: when Contextual Search declines, the handler stops,
where the corner swipe and the power button both reach
`VoiceAssistSession.onShow`.

Which app Contextual Search *is* was the last hope and it is shut too.
`Settings.Secure.contextual_search_package` looks like the answer and is not —
`ContextualSearchManagerService.updateSecureSetting` **writes** that key to
mirror the resolved package, and resolves from the framework resource
`config_defaultContextualSearchPackageName`. Setting it to this app by hand
changed nothing; the hold still opened Lens. The shell command that would change
it, `cmd contextual_search set temporary-package`, throws
`SecurityException: Package android does not belong to 2000` on this build — and
is capped at a duration anyway, so it could never be a fix.

So: **the home bar is Circle to Search's, permanently, on this firmware.** Both
settings touched during this were put back (`search_all_entrypoints_enabled=0`,
`contextual_search_package` back to Google).

**And the handle itself cannot be taken, which is why there is no workaround.** SystemUI
watches navigation gestures through an input *spy* window, which is handed a copy
of every touch regardless of what is layered above it. An overlay strip along the
bottom would therefore receive the touch *and* let SystemUI receive it, so the
swipe would break and the gesture would fire twice. Confirmed in `dumpsys input`
on this phone:

```
1: name=[Gesture Monitor] edge-swipe … inputConfig=NOT_FOCUSABLE | TRUSTED_OVERLAY | SPY
2: name=… com.ishaan.essentialvoice, id=18775 … frame=[35,2184][1045,2369]
```

The bar is listed above the Taskbar and the StatusBar and takes its own taps, and
swiping up still goes home, because the spy window above never stopped seeing it.

`Setup.isAssistant` answers whether the role is held, via `RoleManager` rather
than by comparing component names out of `Settings.Secure.assistant` — the role
is what the system actually dispatches on, and `isRoleHeld` needs no permission.
There is no matching request: `ROLE_ASSISTANT` is not one an app may ask for in a
dialog, so the app can only report it and open the picker.

`Prefs.triggerMode` switches the key between **hold** (talk while down, release
sends) and **tap** (one press starts, the next sends). Match it to whatever the
Essential Key is configured to do in Nothing OS.

---

## The volume slider

`volume/VolumeSlider.kt` and `volume/VolumeSliderView.kt`. A black shape that
grows out of the left or right border when a volume key is pressed, with a
column of dots in it, in place of the panel Nothing OS would have drawn.

Off by default (`Prefs.volumeSlider`). Turning it on makes this app the thing
that decides what the volume buttons do, and that is not something to inherit by
installing a dictation app.

### The volume keys *can* be intercepted, and here is the measurement

This is the whole feature, so it was measured before anything was drawn rather
than assumed. A probe build that logged and swallowed `KEYCODE_VOLUME_UP` /
`KEYCODE_VOLUME_DOWN` in `onKeyEvent`, on the phone, with the service bound:

    PROBE volume code=25 action=0 repeat=0 src=257 dev=gpio-keys      (down)
    PROBE volume code=24 action=0 repeat=0 src=257 dev=mtk-pmic-keys  (up)

and after fifteen presses `STREAM_MUSIC` was still on 3 of 16. The service sees
the press, and returning `true` swallows it completely.

That is *not* true of the Essential Key or the power button — both are consumed
by the window manager policy above accessibility key filtering, which is why
neither can be taken over (see "Take over the key" and "The power button").
Volume is passed through to be dispatched, so a bound accessibility service
holding `flagRequestFilterKeyEvents` is first in line.

The system panel is then never asked for: SystemUI draws it in response to
`FLAG_SHOW_UI` on the volume change, so a key this app consumes and re-issues as
`adjustStreamVolume(stream, direction, 0)` moves the volume and draws nothing.

**Every path that returns `true` has moved the volume first.** A swallowed key
that changed nothing is a dead volume button, which is far worse than a system
panel, so `step()` returns false when `adjustStreamVolume` throws and the key is
let through.

### Two things that cannot be tested over adb

Both cost a round trip to find, and both will cost another one if they are
forgotten:

- **`adb shell input keyevent` never reaches an accessibility service.** Injected
  events skip the input filter entirely — a probe that logged *every* key logged
  nothing for an injected `KEYCODE_F1` either, while the volume still changed.
  Only a real press exercises this code.
- **`sendevent` cannot stand in for one.** `/dev/input/event*` is `Permission
  denied` for the shell user on this phone, so the kernel-level route is shut
  too. Testing the volume slider means asking whoever is holding the phone to
  press the button.

### Android's auto-repeat never arrives, so the hold is this app's own timer

Measured, again on the phone: a *held* volume key is delivered here as a down and
an up and **nothing in between** — every event through `onKeyEvent` carries
`repeatCount 0`. Left at that, holding the button moved the volume exactly one
notch, which is the one thing a volume button must not do.

So `VolumeSlider` runs the repeat itself: the press schedules `repeat` after
`REPEAT_DELAY_MS`, which re-posts until the release cancels it. A repeat the
system *does* deliver is ignored rather than acted on — doing both would ramp at
two speeds added together — and `REPEAT_MAX_MS` stops a hold whose release was
never delivered from ramping forever.

**The interval is not fixed, and the ramp is not linear.** It runs from
`REPEAT_START_MS` to `REPEAT_MIN_MS` over `REPEAT_RAMP_MS` of holding, on `t * t`
— so the ramp itself gets steeper the longer the key is down. That squaring is
the difference between a hold that speeds up and one that only says it does: a
linear ramp spends most of a long hold at close to the speed it started at, which
is exactly the hold that wanted to be fast.

### The stretch at either end

Landing on the top of the scale, or the bottom, runs the *enclosure* on past that
end and eases it back. Not the fill, and not a bounce: it never swings back
through its rest length, because a bounce is something hitting a wall and this is
meant to read as the shape itself refusing to go further.

**The window is `ROOM_DP` longer than the shape at each end, and that is what
makes the stretch possible at all.** A window exactly as big as the shape in it
clips the stretched end flat — the rounded cap simply disappears for the length
of the animation. `ROOM_DP` is the room it grows into, and
`VolumeSliderView.windowHeightDp` is what everything sizing a window (or a
placement preview) has to go through so the two never disagree.

**It must fire on *arriving* at an end, not on being at one.** A key held at full
volume goes on being delivered every few milliseconds, and re-firing a settle
into itself at that rate is a vibration, not a stretch. Hence `wasAtMax` /
`wasAtMin`, and hence `step` reading the volume **before** it adjusts — read
afterwards it can never tell an arrival from another press against an end it was
already sitting on.

### How long it stays

`Prefs.volumeLingerMs`, a slider from 0.4s to 5s. A setting because there is no
right answer: it is the pause between having finished pressing and having
finished looking, and those are different lengths for different people.

### The shape is the laptop's island, stood on its end

`VolumeSliderView.buildPath`. Not a rounded rectangle: the shape is attached
along its long side and flares *outward* into the screen edge at both ends of it,
so it reads as something growing out of the border rather than a pill parked next
to it. A border radius only ever curves inward, so the outline has to be a path —
two concave fillets at the edge, two convex corners on the far side.

It is the same silhouette as the OSD and the dock on the laptop (`island.js` in
the dynamic-island Cinnamon extension), and the fractions are of the *thickness*
rather than pixels, so the shape holds at whatever length it is set to.

**`FLARE_F` and `TOP_F` must add up to 1, and that is geometry rather than
taste.** The fillet ends with a vertical tangent at `flare`; the corner arc
begins with one at the same place; what lies between them is a *straight*
segment `thickness - flare - top` long. `island.js`'s 0.288/0.423 leaves nearly a
third of the thickness dead flat there, and stood on its end that flat is the
top edge — which is why the shape read "very horizontal" no matter how round the
corner was made. The flat is *between* the two curves, so rounding either one
cannot remove it.

Summing to 1 puts both arc centres on the same line, collapses the segment to
nothing, and makes the two arcs meet tangentially: one continuous sweep from the
screen edge into the long side. Verify it the cheap way — print
`thickness - top - flare` and expect 0.

The split between them *is* taste. `FLARE_F` 0.40 and `TOP_F` 0.60 — a smaller
concave flare into a larger convex corner — gives the long, gentle curve of the
design.

**Stretching the arcs into ellipses was tried, and is not what is wanted.**
Pulling both radii out along the length keeps the tangency — that is a property
of the thickness-direction radii alone, so scaling the other axis cannot break it
— and gives noticeably longer, lazier ends. Ishaan preferred the circular ones.
The dial is *gone* rather than set to 1, so the shape has one description rather
than two, but it is recorded here because "make the ends smoother" is a thing
that will be asked again and this is the answer that was already rejected.

**The corner arcs are centred on `y = tp`, and nothing else works.** The far side
is `y = 0` and the arc has to start exactly where the fillet ended, at
`(flareL, tp)`. Centre it anywhere else — the obvious-looking
`w - flare - 2*tp .. w - flare` is centred on 0 — and `arcTo` quietly draws a
straight line from the current point to wherever the arc really starts, which
puts back the flat that all of this exists to remove *and* throws the arc partly
outside the shape. It is invisible in code and obvious the moment the path is
rendered, which is the argument for rendering it: iterate the geometry in
pycairo offscreen, exactly as the laptop extension's notes say, rather than by
building an APK.

The straight run `w - flare - tp` is zero only at the collapsed thickness. The
expanded panel is far wider than its radii and *should* have long straight sides;
that is the same expression doing the right thing rather than a special case.

The path is built lying down — length along x, attached at `y = w`, exactly as
`island.js` draws it — and stood up by a matrix, rather than being re-derived
corner by corner in this orientation. Deriving it twice is how the two would stop
being the same shape. The right edge is the same path reflected.

The dots have to stay inside the part of the shape that is at full width, which
is why their padding is `(FLARE_F + TOP_F) * width` and not a number: any less
and the top and bottom dots are drawn over the flare, outside the black.

**The dots are white or grey, one size.** A dot either counts or it does not, and
a ramp between them makes the level a thing you have to read rather than see.

**The dot count is capped by the stream's notches *and* by how close dots may
sit.** One dot per notch used to be mandatory, because the lit dots *were* the
level and fourteen dots against a stream of sixteen meant some presses lit
nothing — a press that visibly did not register. Once the level became a pill,
the dots stopped having to carry it: they are the ruler, not the reading. So the
count is `min(notches, span / DOT_MIN_STEP)`, which is what makes a short slider
draw a coarser scale instead of a smear.

**The level is a pill lying over the dots, not the lower dots painted white.**
The grey dots are the scale, all of them, drawn first; the fill is one white
capsule from the bottom to the level, drawn over the ones it covers. So what
moves is a single edge sliding, and — the part the dots could never do — it moves
*continuously*, because a count of lit dots can only ever change in whole notches.
Nothing at all is drawn at zero: a stub of pill on the bottom dot reads as one
notch, which is the one thing silence must not look like.

The arrival and the exit are the laptop OSD's, on purpose: 460ms easing out on
the way in, 320ms easing in on the way out, and the fill glides over 200ms. This
is meant to be the same object appearing on the phone, and an island that arrives
on a different curve is a different island. The *window* moves, not the view — a
view cannot paint outside its own surface, the same reason the pill and the bar
slide the way they do.

### The panel

The chevron at the bottom of the enclosure opens it into the phone's other
streams — media, ring, notification, alarm, and the call when there is one, which
is the same set and the same order the system's own panel uses and the same
reason it only lists a call while one is happening.

**Expanded, the panel is modal, and that is one window doing two jobs.** Tapping
anywhere else closes it, which needs a full-screen touchable window; that window
is added *after* the slider, so it is reliably on top and takes every touch,
including the ones meant for the panel. So it **routes**: a touch inside the
slider's window rect is translated into that window's coordinates and handed to
the same `onSliderTouch` the slider's own listener uses, and anything else
collapses. Routing rather than fighting over z-order between two windows of the
same type is the only version of this with one answer. While it is up the slider
window itself is `FLAG_NOT_TOUCHABLE`, or the two would disagree about who owns a
press.

The dismiss timer does not run while the panel is open: a thing the user opened
is a thing only the user closes.

**The corner language stays the collapsed capsule's**, whatever the panel widens
to: radii that grew with the thickness would give a 180dp sheet 70dp corners,
which is a different object.

### Zero on the ringer is a mode, not a volume

This is why holding volume-down used to stop one notch short of silence, and why
a drag to the bottom of the ring column refused to arrive.
`setStreamVolume(STREAM_RING, 0)` does not silence a phone and
`adjustStreamVolume` will not make the last step for you — `ringerMode` is what
holds that state. So both paths special-case it: the step down from one, and a
drag landing on zero, call `setRingerMode(VIBRATE)`; raising from silence sets it
back to `NORMAL` first. `STREAM_NOTIFICATION` is the same stream family and gets
the same treatment; media and alarm reach zero normally.

Wrapped in `runCatching`, because on some builds the mode sits behind Do Not
Disturb access — a phone that will not go silent is a better outcome than a
crash. This app does **not** hold that access (`enabled_notification_policy_access_packages`
is empty here), so if silence ever stops working that is the first thing to look
at.

### The stretch has to wait for the pill

The volume reaches the end of the scale the instant the key is pressed, but the
*fill* takes `LEVEL_MS` to travel there. Firing the stretch on the volume made
the enclosure recoil before anything had touched its tip. `requestImpact` defers
it to the end of the glide, and fires immediately when nothing is gliding.

`onAnimationEnd` runs for a **cancelled** animator too, so the listener carries a
`cancelled` flag: without it, a second press part-way through the first press's
glide fires the stretch early — exactly what deferring it was meant to stop.

A *fresh* press against an end that was already an end still stretches, because
pushing at a wall should do something. An auto-repeat against it does not, or a
held key would shake.

### Landscape ignores the setting, on purpose

The setting says "left" or "right" of a screen that has turned ninety degrees,
and half the time that puts the slider along the phone's *top* edge, under the
camera. So in landscape both the side and the position are overridden: the slider
goes on the border **opposite the display cutout** — the phone's chin — and
centred on it.

The cutout is asked for (`currentWindowMetrics.windowInsets.displayCutout`)
rather than derived from `Display.getRotation`, whose sense is an easy thing to
be wrong about. The cutout's bounds are already in the coordinates being placed
into, so there is nothing to reason about.

### Colour, and what it means

White is a level. **Red is a state** — either end of what a stream can do. The
fill goes red at full volume, and a silenced ringer draws a short red stub with
the vibrate glyph instead of a scale, because a silenced stream has no level to
report and a column of grey dots with nothing on it says "zero" when the truth is
"off". Dragging a silenced column takes the phone out of silent mode first, so
the drag then means what it says.

Icons are ordinary vector drawables — `ic_vol_*.xml`, tinted by the view.

**Dot-matrix icons were built and thrown away.** Seven-by-seven glyphs written as
strings, drawn as dots to match the scale. Two rounds of fixing them (whole-pixel
centres, so a four-pixel dot does not antialias across five; outlines rather than
fills, because a filled region in a grid that small reads as a blob whatever it
was meant to be) made them crisp and still unmistakably homemade. At icon sizes
the resolution is the whole problem and no amount of care removes it. The
conventional icons read instantly, which is the entire job.

The chevron is one drawable pointing right, rotated by the view: **inward** when
closed — the direction the panel will grow — and back at the border when open. On
the right-hand edge that is the mirror of the left, so it is never simply
"right".

### The chevron must be decided on the press, and only there

Testing `isChevron` on every move as well is what made a drag stop partway down:
the chevron's band sits *below* the scale, so a finger heading for zero crossed
into it and every further move was ignored — the fill froze a few notches short
of the bottom and stayed there. A drag now belongs to whatever it started on.

### Dragging it, and what that costs

The slider can be dragged: a finger on it sets the volume to the notch under the
fingertip, absolutely rather than by accumulated deltas, so it cannot drift away
from the dot it started on. A finger down also outranks a key still repeating and
holds the slider open until it lifts.

**A drag draws what was asked for; it does not read the volume back.** Both
halves of that mattered, and together they were why dragging felt a frame behind
the finger: `getStreamVolume` is a binder round trip, and pushing its answer
through the level animator on every move event leaves the pill chasing where the
finger was 150ms ago. The notch under the fingertip is already known in `dragTo`,
so it is drawn immediately with `animate = false` and the write follows. The
readback happens once, when the finger lifts — which is also where a stream that
refused what was asked (Do Not Disturb, on the ringer) gets corrected.

That needs a touchable window, and **the cost is real and was weighed**: the
shape stands on the very edge of the screen, which is where the back gesture
lives, so for the second and a half it is up, a back-swipe started on the capsule
goes to the slider instead. It was `FLAG_NOT_TOUCHABLE` for exactly that reason
until dragging was asked for. `FLAG_NOT_FOCUSABLE` has to stay either way, or the
text field underneath loses focus.

One thing this quietly fixed: an overlay that passes touches through is capped at
`MAX_OBSCURING_OPACITY` (0.8) by Android — the trap that once made the pill
translucent. A window that consumes its own touches is not subject to it.

### Which stream

Decided once per showing, the way the system decides: a call wins, then anything
actually playing, then the ringer. Deciding it once is what stops the readout
drifting onto another stream halfway through a hold.

With the screen off the volume still moves and the key is still consumed, but
nothing is drawn — a window nobody sees, added and removed on every press of a
phone in a pocket, is all that would achieve.

### What it costs

Long-pressing volume-down no longer drops the phone into vibrate, because that
gesture belongs to the system panel this replaces. Silencing a ringing call still
works: the policy intercepts volume keys while the phone is ringing, above
accessibility filtering, so they never arrive here at all.

## The bottom bar

`voice/Bar.kt` and `voice/BarView.kt`. A screen-wide lozenge over the gesture
handle, shown for the length of a *toggled* dictation, with a stop control in it.

The held key needs nothing like it: the finger on the key is the interface, and
letting go is the stop. Nothing else can be held — the assistant role hands over
a single launch, and a knock is over before it is recognised — so every other
trigger needs a surface that says "still listening" and can be tapped to end it.

`Bar.claim()` is called by whatever is about to toggle, *before* it toggles.
Claiming is not inferred afterwards, because a held dictation and a toggled one
look identical from inside `Dictation` — busy and capturing, either way — so the
only honest answer is for the caller to say which it is. `claim()` also sets
`Dictation.suppressPill`; two surfaces narrating one sentence in two corners of
the screen is noise, and the bar is the one with the stop on it.

**TYPE_ACCESSIBILITY_OVERLAY, for the same reason the island uses it.** An
app overlay (layer 111000) sits *under* the navigation bar, which takes the
touches across its whole row; the accessibility layer (311000) is above both
system bars. The window is no taller than the lozenge, so the rest of the screen
belongs to the app underneath, and it only exists while a dictation is running.

The whole bar is the stop target, not just the disc. The disc says where the
affordance is, but a 44dp circle is the wrong size for something you hit at the
bottom of the screen without looking, and there is nothing else the bar could
mean. It is live only while `Dictation.isListening`: between the stop and the
transcript a tap would land on `end()`'s early return and look like a dead bar.

The keyboard does not resize an overlay window, so the bar lifts itself by the
IME inset — otherwise it would sit on the keyboard's bottom row for the whole of
a dictation into a text field, which is most of them.

Colour follows the pill style, so it is yellow unless the pill has been changed.

### `Dictation.watch`, and the single slot that used to be there

`Dictation.onActivity` and `onLevel` were one nullable callback each, because the
island was the only thing watching. Two surfaces follow a dictation now, and a
single slot is the kind of thing that works until the day both are switched on
and the second one to attach silently unhooks the first. They are keyed maps now
(`watch(key, …)` / `unwatch(key)`), keyed rather than a list so that attaching
twice — which the service does every time the system rebinds it — replaces a
registration instead of stacking another copy of it.

---

## Double tap the back

`sensor/BackTap.kt`. Two knocks on the case start a dictation, the same idea as
Back Tap on an iPhone and Quick Tap on a Pixel. None of those need special
hardware: a knock is a short, sharp transient in the accelerometer, and the whole
feature is recognising that shape without also recognising a phone being set down
on a table.

**This phone has no tap sensor, and that decides the design.** Read off the CMF
Phone 2 Pro:

```
icm4n607_acc | accelerometer | minRate=5Hz maxRate=400Hz
             | FIFO (max,reserved) = (4500, 3000) | non-wakeUp
```

Twenty-five hardware sensors — `significant_motion`, `tilt`,
`wrist_tilt_gesture`, Nothing's own `PocketMode`, `ScreenUpward`, `LightScene` —
and not one of them detects a tap. So there is no sensor-hub shortcut and the
detection happens on samples. Pixels use a TFLite model; that model is Google's,
lifted from Pixel firmware, and Tap Tap, which ports it, is GPL-3. Neither can go
into an Apache-2.0 repository, so this is a plain heuristic instead. A knock is
not a subtle signal.

**It cannot work with the screen off, and that is the phone, not the code.** Both
accelerometer entries are `non-wakeUp`, so the sensor never wakes the application
processor. The 4500-event FIFO keeps batching while the phone sleeps, but those
samples are only handed over when something else wakes it, and a trigger that
fires several minutes late is worse than one that does not fire at all. The only
way round it is a permanent partial wakelock, which is a feature that eats a
battery to answer a knock nobody made. So the listener is registered on screen-on
and dropped on screen-off.

**200Hz exactly.** Above 200 the framework wants `HIGH_SAMPLING_RATE_SENSORS`,
and a knock's transient is a handful of milliseconds, so 200Hz puts two or three
samples inside it — enough to see a spike, and cheaper than 400 for no gain.

**The battery cost is smaller than it looks.** The accelerometer on this phone is
*already* running continuously with three other clients on it (200ms, 200ms,
20ms — so 50Hz), all day, whatever this app does. Registering raises the rate of
a sensor that is on rather than waking one that is off, and only while the screen
is on.

Sampling runs on its own `HandlerThread`. The default is to deliver on the main
looper, and the main looper of this process belongs to the accessibility service
— the thread that has to answer a key press without a pause in it.

### The detector

Magnitude rather than the z axis, because which way "the back" points depends on
how the phone is being held. Subtracting a fast exponential average is the whole
high-pass filter: at α = 0.15 per sample the corner sits near 5Hz, so a 2Hz
stride is largely tracked out while a knock, whose energy is tens to hundreds of
Hz, passes untouched. A slower baseline leaves every footstep looking like a tap;
a faster one starts eating the knock.

Then, in order: a crossing of the threshold, a refractory window so that one
knock's ringing is not counted as two, a gap of 70–500ms to the previous
crossing, and the two peaks within a factor of 3.5 of each other.

**The false positives are the whole job.** The first version had one gate — a
resting-noise estimate — and it fired every time the phone was put down on a
table, even at the least sensitive step. Three gates were added, and the order
they are written in is the order of how much work they do:

1. **A ceiling on the peak** (`PEAK_CEILING`, 35 m/s²). This is the one that was
   missing and it is the most valuable. A fingertip on the back of a phone is a
   few m/s²; a phone meeting a table is tens to hundreds, often enough to clip
   the sensor outright. Nothing about the *shape* of the two events separates
   them. The size separates them completely.
2. **A motion gate on the low-frequency side** (`MOTION_MAX`, 1.2 m/s²). A
   stationary phone reads exactly 1g in every orientation, so the smoothed
   magnitude minus 9.81 is a direct measure of real linear acceleration — a hand
   carrying, lifting or lowering it — and it does not care which way up the phone
   is. Held as a decaying peak, because what matters at the instant of an impact
   is whether the phone was moving in the moments *before* it.
   **This is what the original stillness check got wrong**: it watched the
   high-passed signal, which is vibration, and lowering a phone onto a table is
   smooth and slow, so it sailed straight through a gate looking for the wrong
   thing.
3. **A confirmation window** (`CONFIRM_MS`, 170ms). An impact rings — contact,
   bounce, settle — and two of those look exactly like a double tap. The third is
   what gives it away, so the decision waits and is thrown away if anything else
   arrives. It costs the gesture a sixth of a second, which for a toggle is
   nothing.

The proximity sensor still gates a pocket or a hand over the screen, and the
resting-noise estimate still catches a bumpy ride.

Sensitivity 1–5 maps to 8.0…2.2 m/s², and only decides how firm a knock has to
be to count as one at all — the three gates above are what reject a table, not
this number. It is a starting point rather than a measurement: how hard a knock
reads depends on the case, and this phone has a screwed-on back plate rather than
a sealed one, which is exactly the sort of thing that shifts it.

### What a knock does, and the triple

`sensor/TapAction.kt`. Six actions plus Nothing, and the list is short on
purpose. For reference, what the two shipping implementations offer:

| | iPhone Back Tap | Pixel Quick Tap |
|---|---|---|
| Gestures | double **and** triple | double only |
| Catalogue | 16 system + 10 accessibility + 2 scroll + any Shortcut | 7 |
| Sensitivity | none | one checkbox, "Require stronger taps" |
| Screen off / locked | no | no |

Nearly all of it is reachable here with no new permissions, because an
accessibility service can fire the global actions and this app already holds a
media session and a notification listener: screenshot
(`GLOBAL_ACTION_TAKE_SCREENSHOT` — the system's own, saved and shared, rather
than a bitmap this app would have to find somewhere to put), play/pause through
`NowPlaying`, the shade, the flashlight via `setTorchMode` (no permission since
API 26, and the real state read from a `TorchCallback` rather than a local
boolean that drifts the moment the QS tile touches it), and opening any app
through the `<queries>` element the game picker already needs. There is no Siri
row and there cannot be: on this phone Essential Voice *is* the assistant, so the
equivalent of that row is Dictate.

Volume is reachable and deliberately absent — a knock is a worse volume control
than the rocker six millimetres away.

**Triple tap is the confirmation window read the other way round.** The pause
that exists to reject a bounce is exactly where a third knock would arrive, so
supporting it is a branch rather than a mechanism. The cost is real though: with
a triple assigned the window has to grow from 170ms to 320ms, because a human
third knock is not always quick, and *every double tap gets that much slower*.
That is why triple tap is off unless it is asked for. A bounce still fails it,
because a bounce arrives inside `MIN_GAP_MS` and much weaker than the knock it
followed, so it is rejected by the same gap and similarity gates as before.

**Locked phones are ignored** (`KeyguardManager.isKeyguardLocked`, asked on a
threshold crossing rather than on every sample). Both Back Tap and Quick Tap stop
at the lock screen, and they are right to: a knock in a bag should not start a
recording on a phone somebody deliberately locked.

**One calibration worth remembering.** Google shipped Quick Tap in 2021 with an
ML model on dedicated silicon and users still called it hit-or-miss until Android
16 QPR2 in December 2025 — four years. A heuristic being imperfect in week one is
the normal state of this feature, not a sign the approach is wrong.

Which is why `BackTap.last` publishes a `Reading` — the verdict plus the numbers
behind it — and the settings screen's **Try it** row renders it in words:
*"Heard it — 5.2 and 4.8, 190ms apart"*, *"Ignored — 68.4 is far too hard for a
finger"*, *"Ignored — the phone was moving (3.1)"*. Naming the *reason* is the
difference between "this does not work" and "the phone was still moving", and it
turns setting the sensitivity into reading a number off your own phone rather
than guessing at one written here.

Game mode silences it by the same switch that silences the key
(`GameMode.mutesKey`): a phone being held and tapped through a game is the worst
possible place for a knock detector.

### Background sensor access works, and here is the proof

Android 9+ cuts continuous-mode sensors off for background apps, which would have
killed this. It does not apply here, for the same reason the microphone works
without a foreground service — the accessibility binding. Measured with the app
fully in the background:

```
UID u0a341: BFGS  curProcState=5  curCapability=LCMNFUAT

0x00000001) active-count = 2; sampling_period(ms) = {200.0, 5.0}, selected = 5.00 ms
  com.ishaan.essentialvoice.sensor.BackTap | uid 10341 | has sensor access: true
```

The registration history in `dumpsys sensorservice` is also the way to check the
screen gating is working — a `-` pair on screen-off and a `+` pair on screen-on.
Note that **the screen will not stay off while the phone is plugged in** if "stay
awake while charging" is on, which makes that test look broken when it is not.

---

## The Essential Island

A small dark lozenge that sits at the top of the screen and starts a dictation
when it is tapped. The Essential Key is still the fast way in, but it is one key
on one phone; the island is the way in that works with no key at all, and it is
what makes the app usable on a phone that has nothing to hold.

`island/IslandView.kt` draws it, `island/Island.kt` owns the window.

**It is hosted by the accessibility service, not by a foreground service.** That
is the whole design, and it is the same trick `Dictation` already plays: the
system binds an accessibility service with `BIND_FOREGROUND_SERVICE`, so the
process already sits at a uid state allowed to hold an overlay and open the
microphone. Written the ordinary way — a foreground service holding the window —
this feature would have added a permanent notification to an app whose entire
pitch is that it does not have one. It also means the island cannot exist while
dictation is unavailable, so there is no state where it is on screen and dead to
the touch.

**It lives in the status bar row, and getting there took the right window
type.** For a long time it could not. `TYPE_APPLICATION_OVERLAY` is layered
*below* `TYPE_STATUS_BAR`, and the status bar does not merely paint over it — it
takes the **touches** in its own row. An island parked at the punch-hole rendered
perfectly, looked exactly right, and was completely dead; dragging it pulled the
notification shade down instead of moving it. The workaround was to draw the
lozenge tall enough to hang below the inset and put everything a finger goes for
down there.

The note that used to sit here said "no window type above the status bar is
available to an ordinary app". True — but **this app is not an ordinary app**. It
has a bound accessibility service, and that is exactly what
`TYPE_ACCESSIBILITY_OVERLAY` requires. Window layers, read off the phone:

```
APPLICATION_OVERLAY     111000
STATUS_BAR              151000
ACCESSIBILITY_OVERLAY   311000
```

Sixteen layers above. It needs no `SYSTEM_ALERT_WINDOW` at all, and the input
dispatcher now lists the island *above* StatusBar with a live touchable region
inside its band. Two things it requires: the window must be added through the
**accessibility service's own `Context`** — the application context throws — and
so `Island.attach` keeps the service, not just its application context.

The whole lozenge is now reachable, camera included, so `bandTopPx` is pinned to
zero and the band machinery is dead weight awaiting removal.

**Geometry is a setting, because the trade-off is the user's to make.** Top
offset, height and length are all in the settings screen, in dp. The default —
4dp down, 30dp tall — puts a thin lozenge behind the camera, which is where it
looks right and where it *cannot be tapped*, because all of it is above 126px.
Making it tall enough or low enough to cross that line is one stepper away, and
the settings screen says so in as many words when the current geometry is
entirely inside the bar. This is deliberately not decided in code: an indicator
tucked behind the camera and a button you can press are both reasonable things
to want, and only the person looking at the phone knows which.

Two other things worth keeping:

- **The indicator cannot use the pill's fill blindly.** The lozenge is always
  black and the palette contains a black pill, so `IslandView.indicator()` falls
  back to that style's ink whenever its fill is too dark to read. Without it the
  bars are drawn black-on-black and the island looks dead while recording.
- **The island has two active looks, and which one it wears says where the
  dictation came from.** Started by tapping the island, it *is* the interface: it
  widens, the dots move, and `Dictation.suppressPill` keeps the side pill off the
  screen, because two surfaces narrating one sentence is noise. Started by the
  key or the assistant, the pill is already doing that job, so the island does
  not move at all — only its dot turns the accent colour (`State.ARMED`).
  `Island.startedHere` is what tells them apart, and `Dictation` clears
  `suppressPill` itself so a suppressed dictation cannot leak into the next one.

The position is stored in dp so a rotation keeps it, which is what
`onConfigurationChanged` → `Island.reposition()` is for.

**The window is exactly the size of the lozenge**, for two reasons already
learned by the pill: an overlay that lets touches through is capped at 0.8
opacity by the system (`MAX_OBSCURING_OPACITY`), and a window bigger than what
it draws swallows taps meant for the app underneath. `FLAG_NOT_FOCUSABLE` has to
stay for the pill's reason too — the island is tapped while another app has a
text field focused, and taking focus would empty the field the transcript is
meant to land in.

**Nothing about it moves.** State is carried by colour and glyph: near-black
with a coloured microphone at rest, the pill's own fill with the pill's five
dots while listening, a chase while whisper works. The lozenge never resizes, so
the *window* never resizes either, so a second tap lands where the finger
already is. A control that reflows under the status bar every time the
microphone opens pulls the eye off whatever the user is actually doing.

**How it follows the dictation.** `Dictation.busy` and `Dictation.capturing` are
assigned from a dozen places across `begin()`, `end()`, `cancel()`, `finish()`
and `detach()`, so they carry custom setters that call `notifyActivity()` rather
than the call sites being hooked by hand — the hand-hooked version would have
gone stale at the first new assignment. `Dictation.onActivity` and
`Dictation.onLevel` are plain nullable callbacks; null is the honest
representation of "the island is switched off".

Off by default. It is a permanent overlay, and nothing permanent should arrive
uninvited.

## Now playing on the island

The collapsed lozenge carries the album cover as a circle at its left end; tap
the cover and it becomes a player card — art, title, artist, a scrubber with
elapsed and remaining, and prev/play/next. Tap anything on the card that is not a
control and it folds back.

**The cover is the media button; the rest of the lozenge is still the dictation
button.** Splitting by region rather than by mode is deliberate: the gesture
people already have must never change meaning under them because something
started playing. `IslandView.hitTest` owns the split, because the view is what
decided where things were drawn.

### Getting at the media session cost a permission, and here is the proof

Measured on the phone (A001, Android 16) with the accessibility service bound and
no notification access:

| Route | Result |
|---|---|
| `getActiveSessions(self)` | `SecurityException: Missing permission to control media` |
| `getActiveSessions(null)` | same |
| `getMediaKeyEventSession()` | same |
| `getMediaKeyEventSessionPackageName()` | same |
| `pm grant MEDIA_CONTENT_CONTROL` | "not a changeable permission type" — signature\|privileged |

The clever route that *should* have worked, and does not: `getActiveSessions` is
gated because it **enumerates** sessions you were never given, whereas holding a
`MediaSession.Token` is a capability and `MediaController(context, token)` is
ungated. A MediaStyle notification carries its own token in
`EXTRA_MEDIA_SESSION`, and an accessibility service subscribed to
`typeNotificationStateChanged` is handed the posted `Notification`. So the player
should hand the token over the front door.

It fails twice over:

1. **The Notification is not attached any more.** The event arrives and
   `parcelableData` is null — `EVENT type=TYPE_NOTIFICATION_STATE_CHANGED
   pkg=com.android.shell data=null`. (DynamicSpot declares
   `RECEIVE_SENSITIVE_NOTIFICATIONS`, which is what this content sits behind.)
2. **A media notification does not fire the event at all.** Spotify played
   throughout; pause, play and skip-to-next produced no event of any type. Only a
   freshly posted notification did. Ongoing, silently updated notifications are
   not announced.

So `media/MediaObserver.kt` is a `NotificationListenerService` that **does not
override `onNotificationPosted`**. It exists purely so the app is an enabled
listener, which is the credential `getActiveSessions` accepts. Nothing reads,
stores or forwards a notification, and being able to say that plainly matters for
an app that already asks for accessibility, the microphone and an overlay.

`getActiveSessions` is also simply the better API: it has **no cold start**, so a
track already playing when the service binds shows up immediately rather than at
the next track change.

**This does not change the install path.** Play Protect already hard-blocks this
APK for declaring an accessibility service — see "Why installing shows a warning".
Adding notification access adds no new friction, only another toggle to find.

### Two adb facts worth knowing

- **`adb shell cmd notification allow_listener <component>` works.** Notification
  access *can* be granted over adb, unlike accessibility, which
  `settings put secure enabled_accessibility_services` silently reverts.
- **`adb shell am force-stop` disables the accessibility service** on this phone —
  it is removed from `enabled_accessibility_services` outright, not merely
  unbound, and has to be switched back on by hand in Settings. Do not reach for it
  to trigger a rebind.

## The island is not draggable, and that is deliberate

It used to be. It is not any more: the island sits over every app all day, so a
press that can move it is a press that can be **nudged** by a finger aiming at
something underneath — and the one thing a permanent control must never do is
quietly stop being where you left it.

Position is a setting now. `Prefs.setIslandX` (0..1 across the screen, shown as
"Across" on the settings screen) and `setIslandTopDp` are the only things that
move it, and both are edited in the app where changing it is deliberate. The
touch listener has no ACTION_MOVE branch at all; the only thing left of the drag
is a touch-slop check on release, so a scroll that started on the island does not
fire it on the way past.

## Calls, notifications and timers

All three come off the notification listener the media player already needed. The
expensive permission was already paid for; these are what it buys. `notify/Feed.kt`
holds them, `MediaObserver` feeds it.

**Calls are read from the dialer's notification, not from telephony.** That keeps
READ_PHONE_STATE, READ_CALL_LOG and ANSWER_PHONE_CALLS off the manifest entirely
— three more permissions on an app that already asks for a lot — and it works for
WhatsApp and Signal calls as well as cellular ones, because they all post a
`CATEGORY_CALL` notification. Answering fires the notification's own
PendingIntent: nothing here knows how to answer a call and nothing here needs to.
Android 12+ `CallStyle` puts those intents in named extras (`android.answerIntent`,
`android.declineIntent`, `android.hangUpIntent`); older and hand-rolled ones only
have the action list, so both are read and the extras win.

**The card shows only while it is ringing.** Answering is what the island is for;
once connected the dialer owns the screen, and a card of ours parked over it for
the whole conversation is something to get rid of rather than something to use.
The card also cannot be dismissed by tapping past the buttons, unlike the media
card — a stray touch that made a ringing phone disappear would be the worst bug
in the file.

**Timers need no polling.** A countdown notification carries
`EXTRA_SHOW_CHRONOMETER` with `EXTRA_CHRONOMETER_COUNT_DOWN`, and its `when` is
the instant it reaches zero — so the remaining time is arithmetic on one number
and the island just ticks its own clock against it. It is drawn on the *compact*
lozenge, in the slot the resting dot uses, because a timer is a number you glance
at and a card you have to dismiss is the wrong shape for a glance.

**Notifications are a peek, not a shade.** One line of who, one of what, ~4s, then
gone; tapping opens the app. It is suppressed while the media card is open and
while a dictation is running — the island is a button first, and four seconds of
somebody else's notification sitting on it is four seconds it cannot be pressed to
stop recording.

### Precedence

A ringing phone outranks everything, including a dictation in progress — nothing
else on this island is worth missing a call over. A dictation the user is in the
middle of outranks a timer and a peek, because it is the one thing here they are
actively *doing*. The media card is only ever opened deliberately, so nothing
below a call displaces it. `Island.syncFeed` is where that order lives.

### ⚠️ `MediaObserver`'s class name is load-bearing

Notification access is granted per **component**. Renaming that class — however
much "MediaObserver" now undersells what it does — silently revokes the grant on
every phone it is already enabled on, with no error and no prompt.

## How it fits together

```
EssentialKeyService  accessibility service — sees the key down *and up*, pastes
 (trigger/)          the text, and is what hosts everything else
Dictation            one dictation start to finish: window, mic, transcript.
 (voice/)            A singleton, not a service, so there is no notification
PillView             the pill. Canvas, not views: it repaints per audio buffer
Recorder             AudioRecord at 16kHz straight into a float buffer
WhisperEngine        one whisper context, loaded on the hold, dropped when idle
ModelCatalog         the four tiers and what each costs
MainActivity         the settings app (Compose, white, Geist)
PlacementActivity    drag the real PillView to where it should appear
NoteStore            the notes, one JSON file, and the only thing kept
 (notes/)            at all. Every write also refreshes the widget
NoteEditActivity     one note, open for editing. Saves itself on the way out
NotesWidget          the home screen list. Rows pushed, never polled
Prefs / Settings     the store, and an immutable snapshot of it on a StateFlow
```

### What the pill is made of

`voice/PillStyle.kt` holds the eight colours the pill can be. Two rules keep it
honest:

- **Fill and ink travel together.** `ink` is the dots, the badge word and the
  note card's text, so it is chosen against the fill rather than picked to
  taste — a picker where black dots on black is reachable is a picker that will
  produce it. The card's quieter button colour is *derived* (`PillStyles.sunk`,
  the fill blended 14% towards the ink) rather than listed, so there are not
  eight more numbers to keep in step with eight fills.
- **The list is short on purpose.** Nothing's own palettes contain three
  near-identical yellows between them; this has one. The point of a swatch row
  is that you can tell at a glance which is selected.

**`glass` is not a colour, it is a window flag.** `Dictation.applyStyle` sets
`FLAG_BLUR_BEHIND` and `blurBehindRadius` on the overlay's `LayoutParams`, and
the style's colour is a scrim laid over whatever the compositor blurred. Two
things follow. The blur is a *privilege*: the system withdraws it under battery
saver, on hardware that cannot afford it, and behind a developer option, so
`WindowManager.isCrossWindowBlurEnabled` is checked every time and the scrim
alone has to still read as a lozenge when the answer is no. And the blur region
is the **window rect**, which is rectangular — a `Window` can mask background
blur with a rounded background drawable, but an overlay added through
`WindowManager.addView` has no `Window`, only `LayoutParams`, and
`View.setRenderEffect` is not available to it either.

### Typing into the note card

The overlay's root used to be the `PillView` itself. It is a `FrameLayout` now
(`Dictation.ensureHost`) holding the card and an `EditText` that is `GONE` until
the note's text is tapped — a Canvas cannot host a caret, an IME connection or
selection handles, and reimplementing those on a custom view is how you get a
text field that is subtly wrong in nine ways. Every `addView` /
`updateViewLayout` / `removeViewImmediate` therefore names the group, not the
pill.

Two window flags have to come off while editing, and both for their own reason:

- **`FLAG_NOT_FOCUSABLE`** — without dropping it there is no input connection
  and so no keyboard at all. It goes straight back on afterwards; the note in
  [Dictation.buildParams] about it having to stay is still true for every other
  state, because the field being dictated into must keep focus.
- **`FLAG_LAYOUT_NO_LIMITS`** — with it set the window is laid out ignoring the
  system's insets, so the IME inset never arrives and there is no way to know
  how far to lift the card.

The keyboard does **not** resize an overlay — `SOFT_INPUT_ADJUST_RESIZE` only
applies to ordinary activity windows — so the window asks for `ADJUST_NOTHING`
and `liftForIme` moves it up by exactly the inset the IME reported.

The caret opens after the last word rather than where the tap landed: a tap on a
note almost always means "let me add to this", and starting at the end is what
makes the first keystroke do the expected thing.

### The notes widget

`NotesWidget` pushes finished rows to the launcher as
`RemoteViews.RemoteCollectionItems` (API 31, and `minSdk` is 31). The older
route — a `RemoteViewsService` the launcher binds and pulls from — was written
first and thrown away for two reasons: it is deprecated, and it means exporting
a service whose whole job is to hand over the notes. Pushing needs nothing
exported.

The cost of pushing is that every row crosses a binder transaction with a hard
size cap, so `MAX_ROWS` caps it at 50. That is not a display limit — the
launcher scrolls — it is the transaction limit. The app is where the whole list
lives.

There is no `updatePeriodMillis`. Nothing but this app changes the notes, so
`NoteStore.persist` calls `NotesWidget.refresh` on every write and the home
screen changes at the same moment the app does. A period would wake the phone
on a timer to redraw a list that had not moved.

**A RemoteViews layout may only contain classes on the framework's allow-list,
and a bare `<View>` is not one of them.** Using one for a 6dp dot cost an
afternoon: the launcher shows a flat *"Can't load widget"* with nothing in the
app's own logcat, and the real message is in the launcher's —
`Class not allowed to be inflated android.view.View`, from
`LayoutInflater.failNotAllowed`. `ImageView` with a `background` is the
substitute for a coloured rectangle. `adb logcat | grep AppWidgetHostView` is
the only way to see any of this.

The widget's colours are in `res/values/widget_colors.xml` and its `-night`
copy, mirroring `LightPalette` and `DarkPalette` in `ui/Theme.kt`. They have to
be resources: the launcher inflates the layout in its own process, so nothing in
Kotlin is reachable. One consequence is that **the widget follows the phone's
dark mode, not the app's theme switch** — the launcher's configuration is what
picks the qualifier, and it has never heard of this app's preference.

### Why there is no notification

There is no foreground service, and so nothing in the shade.

The usual route to the microphone is a foreground service of type `microphone`,
which costs a permanent notification and cannot be started from the background
anyway. This app skips it: the system binds an accessibility service with
`BIND_FOREGROUND_SERVICE`, which puts the process at a uid state already allowed
to record. `Dictation` is therefore hosted by `EssentialKeyService`, and the
accessibility service is a hard requirement rather than a nicety — it is also
what pastes the text, so it was required regardless.

The failure mode this trades for is a quiet one: **Android hands a blocked
recorder digital silence rather than an error.** So `Dictation.end` treats a clip
whose peak is exactly zero as a blocked microphone and says so, instead of
shrugging and producing an empty transcript.

### Why the overlay window is small

An overlay that passes touches through has its opacity **capped at 0.8 by the
system** (`MAX_OBSCURING_OPACITY`), which showed up in logcat as:

```
has a system alert window (type = 2038) with FLAG_NOT_TOUCHABLE and
LayoutParams.alpha = 1.00 > 0.80, setting alpha to 0.80 to let touches
pass through
```

A washed-out pill is not the design. So the window is only as big as the pill,
keeps `FLAG_NOT_FOCUSABLE` — the field being typed into must keep input focus or
the text has nowhere to land — and drops `FLAG_NOT_TOUCHABLE`. The intro and
outro are the *window* moving (`slideTo` → `updateViewLayout` per frame), not the
view drawing itself somewhere else, because a view cannot paint outside its own
surface.

---

## The earbuds widget and tile

`buds/` — a home screen card and a Quick Settings tile that connect a chosen
pair of earbuds in one tap. It is in a dictation app because of the
**microphone**: dictating through earbuds means HFP has to be up, and four taps
through Settings → Connected devices → the buds → wait is four taps in front of
a feature whose entire pitch is that it is faster than typing.

**There is no public API for connecting a bonded device, and this works anyway.**
`BluetoothA2dp` in the public SDK exposes only `getConnectedDevices`,
`getDevicesMatchingConnectionStates`, `getConnectionState`, `isA2dpPlaying` and
`getSupportedCodecTypes`. The documented `connect()` is `@SystemApi` behind
`BLUETOOTH_PRIVILEGED`, which is `signature|privileged` and can never be held by
an app anyone installs. Reached reflectively it goes straight through — measured
2026-08-29 on Nothing OS 4.1 / Android 16, from a package targeting SDK 35
holding nothing but `BLUETOOTH_CONNECT`:

    connect() on BluetoothA2dp -> true
    bluetooth-a2dp: connectA2dpNative: xx:xx:xx:xx:3c:98
    btif_av: BTA_AV_OPEN_EVT(0x2) status=0(SUCCESS)
    HeadsetStateMachine state=Connected

No `SecurityException` and no hidden-API block: the call reaches `A2dpService` in
the Bluetooth server process and the native stack runs the whole connection. HFP
follows on its own, which is the half that matters here.

**None of that is promised by anything**, so every caller handles the refusal.
`Buds.invoke` returns false instead of throwing — on a stricter ROM the failure
is a `NoSuchMethodException` or an `InvocationTargetException` wrapping a
`SecurityException`, and neither is exceptional, both just mean "use Settings".

Three things are worth knowing before touching this code:

- **A widget cannot open Bluetooth settings at the moment it needs to.** An
  `AppWidgetProvider` is a BroadcastReceiver, and a receiver's `startActivity` is
  refused with `Background activity launch blocked!`. Worse, the toast that would
  explain it is suppressed whenever the app's notifications are off, so the naive
  version fails in complete silence. `BudsWidget` therefore records the refusal,
  repaints to say "Tap for Settings", and lets `tapIntent()` hand the *next* tap
  an activity PendingIntent — which the launcher sends, and which is allowed. The
  tile has no such problem: `startActivityAndCollapse` is the sanctioned route
  out of the panel, so `BudsTile` goes straight there.

- **The cached state drifts, so nothing acts on it.** The cache exists only so a
  widget can paint synchronously; it is written by an ACL broadcast, and one is
  missed whenever the phone reboots or the buds leave range while the package is
  in the stopped state. A drifted cache makes the first tap do the exact opposite
  of what the widget says, which reads as a dead widget. `BudsAction` asks the
  A2DP proxy what is really connected and corrects the cache on the way past; it
  is already holding that proxy, so the check is free.

- **Battery levels are not available.** The system has them — `dumpsys` shows
  `untethered_left_battery` and friends — but they come from
  `BluetoothDevice.getMetadata()`, which is `@SystemApi` too. Connected or not is
  all a normal app can show.

Both surfaces share `BudsAction` so they cannot drift apart, and neither holds a
profile proxy any longer than the call needs: holding one keeps the app process
warm, which would undo the point of having no resident service.

**The mark is the status light.** The widget shows the Essential logo rather than
a picture of earbuds, and being six separate circles is what lets it say
something: `BudsGlyph` renders them on a hexagon, turns them while a pair is
connecting, and collapses them into a single larger circle when it connects. The
merged radius is area-preserving (six dots at r=6.5 carry the same ink as one at
r≈15.9), so it reads as the same mark gathered up rather than a heavier one that
appeared.

Animating it at all takes a trick. A widget cannot animate itself — `RemoteViews`
only calls `@RemotableViewMethod` methods, which rules out starting an animator,
and a `ViewFlipper` throws at apply time for the same reason. So `BudsGlyphAnim`
computes the frames in *this* process and pushes each one with
`partiallyUpdateAppWidget`. It is bounded on purpose: ~11fps, stopped the moment
`BudsStateReceiver` hears the result, and abandoned after 4.5s whatever happens,
because every frame is a binder transaction into the launcher and a spinner that
never stops is worse than none.

**The tile has two states and only two.** It used to grey itself out
(`STATE_UNAVAILABLE`) for "no pair chosen", "no permission" and "radio off", and
a greyed tile reads as broken rather than as informative — the tap has somewhere
sensible to go in all three cases and the subtitle already says which it is. It
also used to be left asserting whatever it believed *before* the tap, because
`connect() -> true` only means the stack accepted the request; the ACL broadcast
is what says it happened, and a missed one froze the tile. It now re-asks the
stack at 400ms, 1.2s and 2.6s after a tap and paints the answer.

## Game mode

`game/` — one switch that gets the phone, and this app, out of the way, reachable
from a Quick Settings tile because the only place anyone wants it is inside the
game.

**What it cannot do, stated first.** It does not make the game run faster.
Nothing a sideloaded app can reach schedules another app's threads, raises its
priority or pins it to a core, and `ActivityManager.killBackgroundProcesses` has
only been able to kill the *caller's own* processes since Android 14 — so the
"free up RAM" button every game booster ships would do nothing here and is not
there. `GameManager.setGameMode` is the platform's own answer and needs
`MANAGE_GAME_ACTIVITY`, which is signature|privileged. `Window.setSustainedPerformanceMode`
only applies to the app's own window. All three were looked at and none of them
is reachable.

What is left is real, and it is what the switch does:

| Lever | How | Needs |
|---|---|---|
| Ignore the Essential Key | `GameMode.mutesKey`, read in `onKeyEvent` | — |
| Hide the island | `Settings.islandVisible` | — |
| Drop the speech model | `WhisperEngine.unload()` | — |
| Guard the screen edges | `View.setSystemGestureExclusionRects` | — |
| Silence notifications | interruption filter | the notification listener |
| Rotation, brightness, timeout, touch sounds | `Settings.System` | Modify system settings |
| Animation scales to zero | `Settings.Global` | `WRITE_SECURE_SETTINGS`, adb only |

### The switch is a preference, not a method call

Three surfaces can turn game mode on — the toggle in the app, the tile, and a
game coming to the front — and only one of them is ever on screen. So all three
do exactly one thing: write `gameArmed`. `GameMode.apply` watches the settings
flow like `Island.apply` does and brings the phone into line with whatever it
says, which is why it is idempotent and is called on every settings change rather
than on the one that concerns it. Two surfaces cannot disagree about a value they
both read out of the same snapshot.

### Nothing is changed that cannot be put back

Every lever reads the current value first, and **that reading is written to disk
before the change is made** — with `commit()`, not `apply()`. The process that
armed game mode is not necessarily the one that gets to disarm it: an
accessibility service is restarted after a crash, after an app update and after
its own switch is toggled in Settings, and every one of those can happen with the
brightness pinned and the notifications off.

`GameMode.attach` is the safety net. A snapshot on disk with `gameArmed` false
means an earlier process died holding the phone, and it is restored before
anything else happens. A snapshot on disk with `gameArmed` *true* means the
session is being resumed, and the levers must **not** be captured again — a
second capture would record game mode's own values as the ones to restore and the
phone would never find its way back. That single rule is why `applied` is set
from whether a snapshot exists rather than from the switch.

`detach` restores rather than leaving the levers pulled, because being torn down
is the one moment the object can be sure it will not get another chance. It
leaves `gameArmed` alone, so a service that is coming back arms again on the way
in.

### Do Not Disturb rides on a permission already paid for

An enabled notification listener may set the interruption filter, and this app has
had one since the island learned to show what is playing — so the quietest half of
game mode costs nothing extra. `Levers.setInterruptionFilter` asks through
`MediaObserver.instance` first, reads the filter back to see whether it took, and
only then falls back to `NotificationManager` with policy access. That is why "Do
Not Disturb access" is offered as optional in the settings screen rather than
required: it is the fallback for a build where the free route is refused.

The filter is `INTERRUPTION_FILTER_PRIORITY`, not `NONE`. A mode that swallows the
alarm you set costs more than it saves.

### Auto-arm pays for its own event subscription

`onServiceConnected` sets `eventTypes = 0` deliberately — every type the service
stays subscribed to costs the *system* an event built, marshalled and delivered
for every app on the phone, all day. Auto-arm needs to know which app is in
front, so switching it on subscribes to `TYPE_WINDOW_STATE_CHANGED` and switching
it off unsubscribes (`EssentialKeyService.watchWindows`). That is the whole reason
auto-arm is a switch that defaults off rather than something the app just does.

A game is left for a moment constantly — a share sheet, a permission dialog, the
shade, an ad that opens the browser. The obvious transients are filtered by name
(`GameApps.isTransient`) and everything else gets a 2.5s grace period before an
automatic session ends. **Only an automatic session ends automatically**: somebody
who turned game mode on by hand meant it, and does not expect opening their
messages to switch it off.

The list of apps is seeded once from `ApplicationInfo.CATEGORY_GAME` — the
store's answer, not a heuristic on the name — and then owned by the user. A list
that re-seeded itself would put back every app they had taken out of it. The
manifest carries a `<queries>` element for the launcher intent rather than
`QUERY_ALL_PACKAGES`: the difference is between seeing the apps somebody could
open and being able to enumerate everything on their phone.

### The edge guard is safe by construction, and unverified

`EdgeGuard` puts a 200dp × 24dp invisible window down each side and calls
`setSystemGestureExclusionRects` on it — the only sanctioned way an app has of
saying "a swipe here is meant for me", capped by the platform at 200dp per edge so
that no app can take the back gesture away altogether. The rects are re-set on
every layout, because the framework drops them when the view is re-laid out.

**The windows do not take touches.** `FLAG_NOT_TOUCHABLE` is on, and that is a
correctness decision: a strip down each edge that swallowed touches would swallow
them in the game as well. The worst case here is that the guard does nothing, and
it cannot be the thing that makes a game unplayable.

Whether the system honours an exclusion coming from an overlay window rather than
from the focused app is a property of the build, and it has not been confirmed on
this phone. If the back gesture still fires at the edges, this is the file that
did not work and the switch in the app is how it gets turned off.

### WRITE_SECURE_SETTINGS is offered, not hidden

```bash
adb shell pm grant com.ishaan.essentialvoice android.permission.WRITE_SECURE_SETTINGS
```

The app is installed over adb in the first place, so the cable is already plugged
in and a permission that can only be granted that way is one more command in the
same terminal. It survives an in-place upgrade and is lost on uninstall. Without
it the animation switch is shown with the command next to it and everything else
still works.

Declaring the permission changes nothing about the install path: Play Protect
already blocks this APK for declaring an accessibility service.

## The build traps, both of which cost ~17x

Measured, not guessed: `whisper-cli` was cross-compiled for the phone and run on
the app's own recording to separate "the app is slow" from "whisper is slow here".

**1. Architecture flags must be on `CMAKE_C_FLAGS`, not on the `ggml` target.**
ggml builds its kernels in a separate target (`ggml-cpu`) and picks vector paths
from the compiler's view of the architecture. `target_compile_options(ggml …)`
leaves that untouched, and the build reports:

```
CPU : NEON = 1 | ARM_FMA = 1 | FP16_VA = 0 | DOTPROD = 0
```

**2. Gradle's debug variant appends `-O0` after any per-target `-O3`.**
So ggml — where all the arithmetic happens — was unoptimised. `CMAKE_C_FLAGS_DEBUG`
is overwritten to `-O3 -DNDEBUG` in `cpp/CMakeLists.txt`.

Same 4-second clip, same model, same phone: **26.4s → 13.9s → 0.83s.**

## Measured tiers

`whisper-cli`, 11 seconds of clear speech (`samples/jfk.wav`), 4 threads:

| Tier | Model | Download | Wall time |
|---|---|---|---|
| Fast | `tiny.en` | 78 MB | 1.5 s |
| Balanced | `base.en` | 148 MB | 2.2 s |
| Accurate | `small.en` | 488 MB | 5.8 s |
| Maximum | `small.en`, beam 5 | — | 7.8 s |

Rejected, with numbers:

- `medium.en-q5_0` (539 MB) — **19.6 s in the encoder alone**, 22.0 s total.
- `large-v3-turbo-q5_0` (574 MB) — **32.6 s in the encoder**, 33.9 s total. Neither
  beam size nor 8 threads moved it; the encoder cost is fixed and independent of
  clip length.
- `small.en-q5_1` (190 MB) — *slower* than fp16 `small.en` (7.1 s vs 5.8 s). This
  chip does fp16 natively, so dequantising costs time and only saves disk.

So `small.en` is the practical ceiling here, and "Maximum" is that same model
searched harder — which is also why it needs no extra download.

## Languages

Dictation is English by default and can be set to any of the **hundred**
languages whisper was trained on, from one row in Set up. The list in
`whisper/Languages.kt` is copied from `g_lang` in whisper.cpp rather than taken
from Android's locale table, because it has to be exactly what the *decoder* has
tokens for — the codes are whisper's own spellings (`jw` for Javanese, `yue` for
Cantonese) and would be wrong as BCP 47.

### English is a different model, not a different string

This is the whole shape of the feature. OpenAI trained the small checkpoints
twice, once on English alone and once on all hundred languages in the same
parameter budget, and the `.en` builds are meaningfully better at English for
it. So every `QualityTier` names **two** `ModelVariant`s and choosing a language
chooses a download:

| Tier | English | Multilingual |
|---|---|---|
| Fast | `ggml-tiny.en.bin`, 78 MB | `ggml-tiny.bin`, 78 MB |
| Balanced | `ggml-base.en.bin`, 148 MB | `ggml-base.bin`, 148 MB |
| Accurate / Maximum | `ggml-small.en.bin`, 488 MB | `ggml-small.bin`, 488 MB |

They are separate files, so somebody who dictates in two languages really does
keep two models on the phone. The settings row states the size before the choice
is made, and `ModelCatalog.installedBytes` counts both variants so the storage
line stays honest.

`ModelVariant.bytes` is the *exact* content length, checked against the finished
file — a wrong number there is a model that can never finish installing. The
multilingual sizes were read off `huggingface.co` with a `HEAD`, not estimated.

### Google's recogniser as a second engine

Set up → **Heard by** switches between whisper and Android's own recogniser
(`speech/GoogleSpeech.kt`). whisper stays the default; Google exists because the
multilingual whisper models are the weak point of this app and the phone already
carries something much better at those languages. On this phone:

```
Settings.Secure.voice_recognition_service
  = com.google.android.tts/…GoogleTTSRecognitionService   ← Speech Services by Google
also present: com.google.android.as/…AiAiSpeechRecognitionService
```

Free, no key, and the same engine as Gboard's voice typing.

**It owns the microphone, so this is a fork and not a swap.** There is no handing
it a buffer we recorded — `EXTRA_AUDIO_SOURCE` is optional and not implemented
everywhere — so `Recorder` does not run on this engine, and the two must never
be open at once. `Dictation.begin()` branches before the recorder is touched.
What comes back instead is `onRmsChanged` for the pill's level and **partial
results** for the note card, which is strictly better than what it replaces:
`startNoteProbe` exists only because whisper cannot say anything until it has
finished, and it costs a whole extra decode to guess at the opening of the clip.
On this engine the card opens on words that were actually heard.

**`EXTRA_PREFER_OFFLINE` is on unless the user turns the network on.** With it
set the recogniser *refuses* rather than reaching for the network, and a refusal
is something the app can explain — "download this language under Voice input" —
instead of a silent upload. Falling back to the network on its own would quietly
turn "nothing is uploaded" into a lie, so it is a switch, never a fallback.

**Everything in `Session` is main-thread.** `SpeechRecognizer` is a bound-service
client and throws otherwise.

**An early `onResults` is not an error.** The silence timeouts are hints and the
recogniser may decide a sentence ended while the key is still held. The words
are still the words, so `onGoogleResult` closes the dictation out with them; the
release that follows falls out of `end()` on `!capturing`.

**Language tags.** whisper says `hi`, the recogniser says `hi-IN`.
`ACTION_GET_LANGUAGE_DETAILS` is an ordered broadcast the recogniser answers
with what it actually supports, so the mapping is its answer rather than a table
here that would go stale. It cannot be waited on during a dictation — that round
trip would sit between the key going down and the microphone opening — so
`tagFor` returns the bare code immediately and caches the real tag for every
dictation after the first. `<queries><intent><action
android:name="android.speech.RecognitionService"/>` is required for both the
bind and the broadcast; without it the recogniser is invisible to package
visibility.

#### Language tags, and the failure that found all of this

The first build sent whisper's bare code (`hi`) and got
`ERROR_LANGUAGE_NOT_SUPPORTED` **69 ms later, before any audio** — and got the
same for `en`, which is what made it obvious the language was never the problem.
**The recognisers deal in full tags**: `hi-IN`, `en-IN`, `cmn-Hans-CN`. A bare
primary subtag is refused.

`ACTION_GET_LANGUAGE_DETAILS`, the documented ordered broadcast for asking which
languages exist, **answers with nothing on this phone** — the log line to look
for is `recogniser listed no languages`. Treat that route as dead on Android
13+. The working API is `SpeechRecognizer.checkRecognitionSupport()`, which
returns four lists and is where every tag above came from.

#### There are two recognisers and they are not interchangeable

Measured on this phone, asking both about Hindi:

| | installed | supports on-device |
|---|---|---|
| `createSpeechRecognizer` (Speech Services by Google) | `en-IN` | 30 langs incl. `hi-IN` |
| `createOnDeviceSpeechRecognizer` (Android System Intelligence) | *(none)* | 31 langs incl. `hi-IN` |

So "which recogniser" is a real question with a measured answer, and `better()`
resolves it: an installed pack beats a pending one beats a downloadable one, and
ties go to the default recogniser because it is the one that actually had a pack
on it. Whichever wins is remembered in `Support.onDevice`, and both the dictation
and the download then aim at that same one — aiming a download at the other is
how you get a pack that nothing uses.

#### The app can install the language itself

`triggerModelDownload` — and it works: Hindi went `supported → pending →
installed` from the button in Set up, with no trip into the phone's settings.
Use the **three-argument overload with a `ModelDownloadListener`**. The
fire-and-forget one was tried first and reported nothing whatsoever, which is
useless for a button somebody is waiting on.

#### ⚠️ The main-thread rule reaches further than it looks

`SpeechRecognizer` throws `RuntimeException: SpeechRecognizer should be used only
from the application's main thread` — and `checkRecognitionSupport` answers on
**the executor you handed it**, not the main thread. So `destroy()`, and any
second query the answer leads to, have to hop back. That mistake cost a silent
`no default recogniser` in the log and half the support picture.

#### ⚠️ Still unverified: the microphone from the background

Whether Google's recogniser gets audio when the caller is a backgrounded
accessibility service is **still unmeasured** — the first attempt never got that
far, because it died on the language tag before opening the microphone. Our own
mic works backgrounded through the accessibility binding; Google's recogniser
records in *its* process, and third-party apps are widely reported to get
`ERROR_AUDIO` or `ERROR_RECOGNIZER_BUSY` from a background caller on Android
12+. The test is: hold the key **inside another app** and read
`adb logcat -s EVGoogle:* EVDictation:*`. Do not reason about this one.

### The language is stated, never detected

whisper will detect a language (`p.detect_language`, which `jni.cpp` still wires
up for `"auto"`) and it is the wrong tool here. Detection decides from the first
window, and this app's windows are two seconds of somebody halfway through a
sentence; a wrong guess is not a slightly worse transcript, it is a different
script. The picker therefore has no automatic option.

### The vocab prompt is English-only

`VOCAB_PROMPT` (`"Gemini. Note."`) primes the decoder so the command words come
back spelled right. It must not be sent to a multilingual model: an English
initial prompt biases the decoder toward *replying in English*, which is the one
thing a language setting cannot be allowed to do. Non-English dictation runs
unprimed, so the commands are heard less reliably there — the settings row says
so, and the commands stay English words on purpose.

### What keys the resident model

`WhisperEngine.loadedFile` is the model's **file name**, not the tier id. One
tier now names two files and two tiers (Accurate, Maximum) share one, so the
file name is the only key that reloads when it must and does not when it need
not. Changing the language calls `Dictation.onTierChanged()`, which drops the
resident model.

### Timings

The table above under "Measured tiers" was measured on the English models. The
multilingual ones are the same architecture at the same size and cost about the
same; that has **not** been re-measured per language, and the tier cards show
the English numbers.

### Why settings are read through a snapshot

`Prefs` publishes an immutable `Settings` on a `StateFlow`, and the UI reads only
that. Reading SharedPreferences straight from a composable looks like it works
and does not: a plain getter is not a state read, so nothing recomposes when a
value changes and the screen only catches up when the app is reopened. Every
toggle in this app was silently doing that.

### Size

The release build is **3.4 MB**. It was 21.8 MB before three changes, in order of
how much they were worth:

| | Saved |
|---|---|
| R8 (`isMinifyEnabled`) — the dex was 17.7 MB of unreached Compose | ~16 MB |
| Linking ggml statically instead of shipping `libggml-base.so` + `libggml-cpu.so` | ~0.9 MB |
| Dropping five Geist weights the type scale never asks for | ~0.4 MB |

R8 renames aggressively, and JNI resolves by symbol name, so
`proguard-rules.pro` keeps `WhisperLib` and every `native` method verbatim. That
this still holds is checkable rather than hopeable:

```bash
apkanalyzer dex packages --defined-only dist/essential-voice-1.0.apk | grep WhisperLib
llvm-nm -D --defined-only libessentialwhisper.so | grep essentialvoice
```

The two lists have to agree.

### Placement

The pill goes anywhere. Only three columns pull on it — hard against the left
bezel, dead centre, hard against the right — and `snapColumns` derives the edge
ones from the real pill width plus a 10dp margin, so an edge snap sits where the
reference photo puts it rather than at an arbitrary fraction. The placement
screen draws all three, and they light yellow when the pill is on one.

## Carried over from the laptop version

- **Normalise before transcribing.** A phone mic lands speech near a tenth of
  full scale, quiet enough that whisper's own gating returns an empty transcript.
  `Audio.normalise` lifts the peak to 0.55 with the gain capped at 12x, so a
  silent room does not become amplified hiss.
- **Load the model on the hold, not on the release**, so the ~250ms load overlaps
  the sentence instead of being felt after it.
- **Trim the heap after unloading.** bionic keeps freed arenas on its own free
  lists exactly as glibc does; `WhisperLib.nativeTrimHeap` calls
  `mallopt(M_PURGE, 0)` or the memory stays charged to the process.
Deliberately *not* carried over: nothing is written to disk. There is no saved
recording and no transcript history — the text goes to the field it was meant for
and the audio is dropped.

## Why installing shows a warning, and why that is permanent

Play Protect blocks sideloaded apps that declare an accessibility service. That
is a [documented rule](https://developers.google.com/android/play-protect/warning-dev-guidance),
not a heuristic that can be tuned around, and this app cannot drop the service:
it is the only way to see the Essential Key, and it is also what types the text
back. Samsung's Auto Blocker refuses all sideloading separately and has to be
switched off once.

The permission set reads exactly like spyware, for entirely ordinary reasons:

| Permission | Why | How it scans |
|---|---|---|
| accessibility + `flagRequestFilterKeyEvents` | see the key | keylogger |
| `RECORD_AUDIO` | dictation | mic tap |
| `SYSTEM_ALERT_WINDOW` | the pill | overlay phishing |

What *was* removed is `REQUEST_INSTALL_PACKAGES`. An app with the three above
that can also install software is the complete banking-trojan fingerprint, and
self-updating was not worth handing a scanner a fourth reason to object. The
Updates panel now only checks and links out; the install is the browser's job, or
[Obtainium](https://github.com/ImranR98/Obtainium)'s.

`isAccessibilityTool` is deliberately **not** declared. Claiming it while not
serving disabled users is itself a flagged behaviour.

If the block ever looks like a genuine misclassification rather than the policy
working as intended, there is a
[Play Protect appeal](https://support.google.com/googleplay/android-developer/contact/protectappeals).
Note that Play's own policy restricts the Accessibility API to accessibility
purposes, so an appeal is not a formality.

## Card likes

Every card on the launcher opens with a heart and a bracketed number. Tapping the
heart likes the card; tapping again takes it back. The number is everyone's
total, which is the whole point — it is the cheapest possible way of asking
"which of these eleven things do people actually care about" without writing a
survey nobody fills in.

It is the **one feature in this app that talks to a server about anything other
than the user's own question**. What leaves the phone is a card's name and a
random UUID this install made up for itself on first tap, and nothing else: no
account, no `ANDROID_ID`, no advertising id, no text, and never anything anybody
said. Clearing the app's data throws the id away and makes a new one. That is
worth keeping true — the privacy claim in the README is the reason people install
this thing.

### The pieces

| Where | What |
|---|---|
| `supabase/likes.sql` | The whole backend. Run once, whole, in the SQL editor. |
| `supabase.properties` | Project URL and anon key. Not in the repo; `.example` is. |
| `social/Likes.kt` | Two `HttpURLConnection` calls and a `StateFlow`. |
| `Prefs.installId` | The random per-install UUID. |
| `Prefs.likeCache` | Last known counts, so the hearts are not blank on open. |
| `Features.LIKES` | The off switch. |
| `ui/Design.kt` | `LocalLikes`, `LikeTag`, and the `Heart` path. |

### Setting it up

1. Make a Supabase project. Free tier is far more than this needs — the table is
   one row per person per card.
2. SQL editor → paste `supabase/likes.sql` → run. It is idempotent; running it
   again is safe.
3. `cp supabase.properties.example supabase.properties` and fill in the Project
   URL and the **anon** key from Project Settings → API. Not the service_role
   key, which bypasses every protection below and must never be in an APK.
4. Rebuild. `BuildConfig.SUPABASE_URL` is empty without that file, and empty is
   what makes the hearts not draw at all — an app that cannot count likes should
   not show a button that silently does nothing.

### Why the schema looks like that

The anon key ships inside the APK, so it is public, so nothing server-side trusts
the client. `card_likes` has RLS on and **no policies**, which means anon cannot
read, write or count it. The only two things anon may do are execute
`like_counts(uuid)` and `set_like(text, uuid, boolean)`, both `security definer`
with a pinned `search_path`, and `set_like` checks the card name against a
regex before it writes. One like per card per install is the primary key's job,
not the app's — the client is the part that cannot be trusted to remember.

What is still possible is someone minting device ids and liking a card twice.
That is the price of having no accounts, and having no accounts is the point:
this is a heart on a settings screen, not a ballot. The foot of `likes.sql` says
what to do if it ever actually happens.

### Testing it by hand

- **The heart fills instantly, offline.** Aeroplane mode, tap a heart: it fills
  and the count moves. Turn the network back on and reopen the app — the count
  is back to the real one, because the tap never reached the server and the
  optimistic change was put back. This is the case worth checking; an
  optimistic UI that does not roll back lies until the next launch.
- **Two hearts fast.** Both stay where you put them. `Likes` holds a mutex so the
  older answer cannot land after the newer one.
- **A second install.** `test.sh` wipes the store, so the next launch has a new
  `installId` and every heart is empty again while the counts stay put. That is
  also how to prove the counts are shared rather than local.
- **No `supabase.properties`.** No hearts anywhere, and no gap where they were.

### What it took out of the corner

`[01]`, `[02]` — the card's place in the list — **is gone from the screen**, and
the brackets now hold the like count instead. Both cannot be up there: a corner
reading `[03] ♥ 15` is three numbers in one place, and the position was the least
interesting of them. It only existed because the drawings wanted a number in the
corner, and this is a better number to put in it. `index` still does its other
job — it is what staggers the cards' entrance in `rises` — it is just not
printed. The count is `%02d` like the positions were, so a column of cards keeps
its left edge at 3 likes or 30.

`[--]` is shown while the very first fetch is out, never `[00]`: a card reading
zero is making a claim, and it should not be made on the app's behalf before the
answer lands. After that first fetch the cache covers it.

The old card number is still the **fallback**, and has to be — a build with no
`supabase.properties` has no counts, and an empty corner is a hole rather than a
design.

Two layout notes. The tag row was 24dp tall with 8dp above it; a 24dp target is
not aimable, so the row is 32 and the spacer is 6, six dp borrowed from the art
that has about 150 and does not miss them. And the row's start padding is 8
rather than 18, because the heart strip carries its own 10 — that way the strip's
padding is *inside* its clickable and therefore pressable, while the heart still
lands on the card's real 18dp margin.

The heart follows the kit's rules: no shadow, no outline, and the press changes
colour and fill rather than position. It does not pop or spring.

## Updating installed copies

`Updater` reads `UPDATE_MANIFEST_URL` on demand, compares `versionCode` against
the installed one, and opens the release page. It cannot install anything, on
purpose — see the section above. Anyone wanting updates to happen on their own
points Obtainium at the releases page.

The check runs once when the app is opened, so the What's new panel has
something to show without anyone pressing a button, and once a day from the
accessibility service, which is already long-lived — that one notifies at most
once per release. There is no work manager and no polling beyond those two.

### What's new

Two lists, answering different questions.

`WhatsNew.local` ships inside the APK and describes the build it is part of, so
a fresh install can say what it brought with no network. It cannot have
pictures: a picture of a feature is made after the build containing it has been
signed.

`whatsNew` in `update.json` describes the build that is *out*. Entries are
`{ title, body, image }`, all optional but for needing one of `title`/`body`,
and `image` is an https URL — a release asset is the easiest place to put one,
since uploading a picture to a release does not touch the APK. See
`publish/update.json.example`. A malformed entry is skipped rather than failing
the check; a typo in a changelog must not be able to stop the app noticing an
update.

The panel shows the remote list when an update is available (what you would be
getting), the remote list when it matches the installed version (the only way
the installed build's pictures can appear), and `WhatsNew.local` otherwise.
Pictures are fetched by `NetImage`, which caches them in `cacheDir/whatsnew`
and decodes no larger than the screen — an image library would have been
several hundred kilobytes to draw a handful of pictures that never change.

Releasing therefore means two edits: `WhatsNew.local` and `publish/update.json`.

### The debug manifest

There used to be a fifth: a hold-to-talk button inside the app, for checking
the setup without reaching for the key. Its composable outlived its call site
and sat unreachable in `Home.kt` for two releases; it was deleted in 3.0 rather
than left there looking like a feature. `git show v1.5:app/src/main/java/com/ishaan/essentialvoice/ui/Home.kt`
has it if it is ever wanted back.

`app/src/debug/assets/update-debug.json` is read *instead of* the network by
debug builds only, and its pictures may be `asset:name.png` — files bundled
alongside it. It exists because the panel is otherwise unlookable-at until a
release is cut and pictures are uploaded to it. Release builds never read it;
the file is in the debug source set and is not in the release APK. Delete it to
make a debug build check the real manifest.

## Debugging

```bash
adb logcat -s EVDictation:* EVKey:* EVEngine:* EVWhisper:* EVRecorder:*
```

Note that `sendevent` cannot be used to fake the Essential Key: SELinux denies
the shell user write access to `/dev/input/event0`, so the key has to be pressed
by a finger.

## Open questions

**Can Essential Space be switched off entirely?** Unanswered. The app cannot do
it (see above), so the question is whether Nothing OS 4 exposes a switch, or
whether the Essential Space package can be disabled with
`pm disable-user --user 0 <package>` without taking anything else with it.

Read off the phone rather than guessed, `pm list packages | grep -i essential`:
`com.nothing.ntessentialspace`, with `com.nothing.essentialintelligence` and
`com.nothing.ntessentialrecorder` alongside it. Nothing has been disabled and
nothing in the app names any of them — knowing the package is not the same as
knowing which one owns the key press, and disabling the wrong one takes the
Essential Space *recordings* with it.
`Setup.openEssentialKeySettings` tries two plausible intent actions and falls
back to the top of Settings, which is honest but not helpful; if a real screen
exists, name it there.
