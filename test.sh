#!/usr/bin/env bash
# Build the test APK, put it on the phone, and hand it back as a fresh install.
#
#   ./test.sh                  build, install, reset every setting
#   ./test.sh --keep           build and install, leave the settings alone
#   ./test.sh --models         also delete the downloaded models
#   ./test.sh --no-build       just install what is already in test-build/
#
# Why the reset is here and not in the app: every build is meant to be looked at
# with a new user's eyes, and a settings store that survives ten builds is the
# one thing that cannot be looked at that way. `rm -rf shared_prefs` is the whole
# of it — deliberately not `pm clear`, which would take the ~150MB model down
# with it and turn every test into a download. Use --models when the download is
# what is being tested.
#
# It needs the app to be debuggable, which the debug build is, and it needs the
# app stopped, which a fresh install already leaves it.
set -euo pipefail
cd "$(dirname "$0")"

PKG=com.ishaan.essentialvoice
GRADLE=$(echo "$HOME"/.gradle/wrapper/dists/gradle-8.9-bin/*/gradle-8.9/bin/gradle)

RESET=1; BUILD=1; MODELS=0
for a in "$@"; do
  case "$a" in
    --keep) RESET=0 ;;
    --models) MODELS=1 ;;
    --no-build) BUILD=0 ;;
    *) echo "unknown option: $a"; exit 2 ;;
  esac
done

NAME=$(grep -oP '^\s*versionName = "\K[^"]+' app/build.gradle.kts)
APK=test-build/essential-voice-$NAME-test.apk

if [ "$BUILD" = 1 ]; then
  "$GRADLE" --no-daemon :app:assembleDebug
  mkdir -p test-build
  cp app/build/outputs/apk/debug/app-debug.apk "$APK"
fi

adb shell am force-stop $PKG
adb install -r "$APK"

if [ "$RESET" = 1 ]; then
  adb shell am force-stop $PKG
  # Settings, the learned key, the notes, and the record of having been asked
  # anything. Not the models.
  adb shell run-as $PKG rm -rf shared_prefs
  adb shell run-as $PKG rm -f files/notes.json files/notes.json.tmp files/notes.json.broken
  # The library's audio. It lives beside notes.json rather than in it, so a
  # reset that only removed the index would leave megabytes of orphaned clips
  # behind and no row anywhere pointing at them.
  adb shell run-as $PKG rm -rf files/recordings
  [ "$MODELS" = 1 ] && adb shell run-as $PKG rm -rf files/models
  echo "settings reset — the app will open as it does on a new phone"
fi

# Reinstalling always switches the accessibility service off, and adb cannot
# switch it back on. Say so rather than letting it be discovered.
echo
echo "next, on the phone:"
echo "  Settings → Accessibility → Essential Voice → on"
adb shell settings get secure enabled_accessibility_services
