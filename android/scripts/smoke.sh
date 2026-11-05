#!/usr/bin/env bash
# Runs on the CI emulator. Uses the app the way a person would: logs a session
# with a new symptom, completes a check-in, and confirms a real notification.
set -euo pipefail

apk="$1"
out="$2"
package="io.github.thegingerbehindu.rehabtracker"
mkdir -p "$out"

shot() { adb exec-out screencap -p > "$out/$1.png"; }

tap_bounds() {
  local left top right bottom
  read -r left top right bottom <<< "$(echo "$1" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/')"
  adb shell input tap $(( (left + right) / 2 )) $(( (top + bottom) / 2 ))
}

# A slow emulator can show a "isn't responding" popup for its own system apps,
# which covers the screen. A crash in this app still fails the run further down.
dump_ui() {
  local attempt wait_bounds
  for attempt in 1 2 3 4 5; do
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null
    adb shell cat /sdcard/ui.xml > "$out/ui.xml"
    if ! grep -q "responding" "$out/ui.xml"; then
      return 0
    fi
    if grep -q "$package" "$out/ui.xml" && ! grep -q 'package="android"' "$out/ui.xml"; then
      return 0
    fi
    echo "Dismissing a system \"not responding\" popup"
    wait_bounds=$(grep -o 'text="Wait"[^>]*bounds="[^"]*"' "$out/ui.xml" | sed -E 's/.*bounds="([^"]*)".*/\1/' || true)
    if [ -n "$wait_bounds" ]; then
      tap_bounds "$wait_bounds"
    fi
    sleep 5
  done
}

# Taps the nth element showing exactly this text.
tap_text() {
  local text="$1" nth="${2:-1}" bounds
  dump_ui
  bounds=$(grep -o "text=\"$text\"[^>]*bounds=\"[^\"]*\"" "$out/ui.xml" | sed -n "${nth}p" | sed -E 's/.*bounds="([^"]*)".*/\1/' || true)
  if [ -z "$bounds" ]; then
    echo "Could not find \"$text\" (occurrence $nth) on screen"
    shot "failed-to-find"
    exit 1
  fi
  tap_bounds "$bounds"
  sleep 2
}

expect_text() {
  dump_ui
  if ! grep -q "$1" "$out/ui.xml"; then
    echo "Expected to see \"$1\" on screen"
    shot "failed-to-see"
    exit 1
  fi
}

adb install -r "$apk"
adb shell pm grant "$package" android.permission.POST_NOTIFICATIONS
adb shell appops set "$package" SCHEDULE_EXACT_ALARM allow
adb logcat -c

adb shell am start -W -n "$package/.MainActivity"
sleep 5
shot "01-today-empty"
expect_text "Nothing logged yet today"

# Log a rehab session with a new symptom.
tap_text "Log Rehab"
shot "02-rehab-form"
tap_text "New symptom"
tap_text "Symptom name"
adb shell input text "Knee"
sleep 1
tap_text "Add"
adb shell input keyevent KEYCODE_ESCAPE || true
sleep 1
shot "03-symptom-added"
expect_text "Knee"
# Occurrence 1 is the effort row, and occurrence 2 is the symptom's "before" row.
tap_text "3" 2
shot "04-scored"
tap_text "Save"
shot "05-today-logged"
expect_text "Knee 3"

# Answer a check-in, which should cover that session.
tap_text "Check in now"
shot "06-check-in"
expect_text "Since your last check-in"
tap_text "Better"
tap_text "Save check-in"
shot "07-today-checked-in"
expect_text "Better"

# Leaving straight after entering something must keep it.
tap_text "Symptom event"
tap_text "Walking"
shot "08-event-form"
adb shell input keyevent KEYCODE_HOME
sleep 3
adb shell am start -W -n "$package/.MainActivity"
sleep 3
adb shell input keyevent KEYCODE_BACK
sleep 2
tap_text "History"
shot "09-history"
expect_text "Symptom event: Walking"

tap_text "Settings"
shot "10-settings"

# A real notification, with the app in the background.
tap_text "Setup tests"
shot "11-setup-tests"
tap_text "Test in 1 min"
adb shell input keyevent KEYCODE_HOME
sleep 75

adb shell dumpsys notification --noredact > "$out/notifications.txt"
adb shell cmd statusbar expand-notifications
sleep 3
shot "12-notification"
adb shell cmd statusbar collapse

adb logcat -d > "$out/logcat.txt"

if grep -A3 "FATAL EXCEPTION" "$out/logcat.txt" | grep -q "$package"; then
  echo "The app crashed"
  grep -A25 "FATAL EXCEPTION" "$out/logcat.txt" | head -60
  exit 1
fi

if ! grep -q "pkg=$package" "$out/notifications.txt"; then
  echo "No notification appeared after the alarm"
  exit 1
fi

echo "Smoke test passed"
