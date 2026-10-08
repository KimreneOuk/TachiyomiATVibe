#!/usr/bin/env bash
# scripts/trace_stream.sh
set -euo pipefail

PACKAGE="${1:-app.kanade.tachiyomi.vibe.debug}"
DEVICE="${2:-192.168.100.223:36083}"
OUT_FILE="${3:-trace_capture_$(date +%Y%m%d_%H%M%S).log}"

# Locate adb executable: check PATH first, then standard Android SDK platform-tools location
if ! command -v adb &> /dev/null; then
    if [ -n "${LOCALAPPDATA:-}" ] && [ -f "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" ]; then
        ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
    elif [ -f "$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe" ]; then
        ADB="$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe"
    else
        ADB="adb"
    fi
else
    ADB="adb"
fi

ADB_CMD=("$ADB")
if [ -n "$DEVICE" ]; then
    ADB_CMD+=("-s" "$DEVICE")
fi

echo "[*] Querying UID for $PACKAGE on ${DEVICE:-default device}..."
ESCAPED_PKG=$(printf '%s\n' "$PACKAGE" | sed 's/[.[\*^$()+?{|]/\\&/g')
UID_LINE=$("${ADB_CMD[@]}" shell pm list packages -U | grep -E "package:${ESCAPED_PKG}([[:space:]]|$)" || true)

if [ -z "$UID_LINE" ]; then
    echo "[-] Error: Package $PACKAGE not found on device!" >&2
    exit 1
fi

UID=$(echo "$UID_LINE" | head -n 1 | sed -n 's/.*uid:\([0-9]*\).*/\1/p' | tr -d '\r')
if [ -z "$UID" ]; then
    echo "[-] Error: Could not parse UID from '$UID_LINE'" >&2
    exit 1
fi

echo "[+] Target UID: $UID. Starting noise-free streaming to $OUT_FILE..."

FILTER="MediaProvider|DatabaseUtils|ModernMediaScanner|OplusThumbnailUtils|ColorOS|ViewRootImpl"

"${ADB_CMD[@]}" logcat -c
"${ADB_CMD[@]}" logcat -b main -b crash --uid="$UID" -v threadtime \
  | grep --line-buffered -vE "$FILTER" \
  | tee "$OUT_FILE"
