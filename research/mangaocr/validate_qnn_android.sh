#!/usr/bin/env bash
# Capture the strict QNN/HTP gate for the already-built debug APK.
# This does not infer HTP use from addQnn() registration; the app's
# QnnDiagnostics must create a strict session and execute a real inference.
set -euo pipefail

PACKAGE="${1:-eu.kanade.tachiyomi.debug}"
LOG="${2:-qnn-device.log}"
APK="${APK:-app/build/outputs/apk/debug/app-debug.apk}"

command -v adb >/dev/null || { echo "adb not found" >&2; exit 2; }
adb wait-for-device
ABI="$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
PLATFORM="$(adb shell getprop ro.board.platform | tr -d '\r')"
SDK="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
case "$ABI" in
  arm64-v8a) ;;
  *) echo "QNN requires an arm64-v8a device; got $ABI" >&2; exit 3 ;;
esac
echo "device platform=$PLATFORM abi=$ABI sdk=$SDK"

if [[ -f "$APK" ]]; then
  adb install -r "$APK" >/dev/null
else
  echo "APK not found at $APK; using the installed package" >&2
fi

adb logcat -c
adb shell am force-stop "$PACKAGE" || true
adb shell monkey -p "$PACKAGE" 1 >/dev/null
echo "Capture started in $LOG. Open a MangaOCR page now. Ctrl-C after all three models run."
adb logcat -v threadtime -s onnxruntime QnnDiagnostics HardwareDiscoveryEngine MangaOcrEngine >"$LOG"
