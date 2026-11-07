#!/usr/bin/env bash
# Start the ÆTHER test emulator in the background and wait for boot.
#
# Uses emulator 35.2.10 (installed side by side at $SDK/emulator35x) because the SDK's
# current emulator (37.x) crashes before the guest kernel starts on this host's
# Ivy Bridge CPU. Usage: tools/emulator.sh [AVD]   (default Pixel_8_API_34)
set -euo pipefail
SDK="${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}"
AVD="${1:-Pixel_8_API_34}"
EMU="$SDK/emulator35x/emulator/emulator.exe"
ADB="$SDK/platform-tools/adb.exe"
LOG="$(dirname "$0")/../.toolcache/emulator-$AVD.log"
mkdir -p "$(dirname "$LOG")"

export ANDROID_SDK_ROOT="$SDK" ANDROID_HOME="$SDK"
nohup "$EMU" -avd "$AVD" -no-snapshot -no-boot-anim -gpu swiftshader_indirect -no-audio >"$LOG" 2>&1 &
echo "Booting $AVD (log: $LOG)"

for _ in $(seq 1 100); do
  if [ "$("$ADB" -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    echo "emulator-5554 ready"; exit 0
  fi
  sleep 3
done
echo "Emulator did not finish booting; see $LOG" >&2; exit 1
