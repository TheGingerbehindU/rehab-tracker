#!/usr/bin/env bash
# Runs on the CI emulator: installs the app, opens it, and checks that a test
# alarm produces a real notification without the app crashing.
set -euo pipefail

apk="$1"
out="$2"
package="io.github.thegingerbehindu.rehabtracker"
mkdir -p "$out"

adb install -r "$apk"
adb shell pm grant "$package" android.permission.POST_NOTIFICATIONS
adb shell appops set "$package" SCHEDULE_EXACT_ALARM allow
adb logcat -c

adb shell am start -W -n "$package/.MainActivity"
sleep 5
adb exec-out screencap -p > "$out/01-opened.png"

# Find the button on screen by its text, then tap its centre.
adb shell uiautomator dump /sdcard/ui.xml
adb shell cat /sdcard/ui.xml > "$out/ui.xml"
bounds=$(grep -o 'text="Test in 1 min"[^>]*bounds="[^"]*"' "$out/ui.xml" | sed -E 's/.*bounds="([^"]*)".*/\1/')
if [ -z "$bounds" ]; then
  echo "Could not find the test alarm button"
  exit 1
fi
read -r left top right bottom <<< "$(echo "$bounds" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/')"
adb shell input tap $(( (left + right) / 2 )) $(( (top + bottom) / 2 ))
sleep 3
adb exec-out screencap -p > "$out/02-alarm-set.png"

# Leave the app, as a real user would, and wait for the alarm.
adb shell input keyevent KEYCODE_HOME
sleep 75

adb shell dumpsys notification --noredact > "$out/notifications.txt"
adb shell cmd statusbar expand-notifications
sleep 3
adb exec-out screencap -p > "$out/03-notification.png"
adb shell cmd statusbar collapse

adb logcat -d > "$out/logcat.txt"

if grep -q "FATAL EXCEPTION" "$out/logcat.txt" && grep -A3 "FATAL EXCEPTION" "$out/logcat.txt" | grep -q "$package"; then
  echo "The app crashed"
  grep -A25 "FATAL EXCEPTION" "$out/logcat.txt" | head -60
  exit 1
fi

if ! grep -q "pkg=$package" "$out/notifications.txt"; then
  echo "No notification appeared after the alarm"
  exit 1
fi

echo "Smoke test passed"
