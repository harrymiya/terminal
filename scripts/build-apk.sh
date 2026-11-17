#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

VERSION_CODE="$(awk '/^[[:space:]]*versionCode[[:space:]]*=/ { gsub(/[^0-9]/, "", $0); print; exit }' "$PROJECT_ROOT/android/app/build.gradle.kts")"
VERSION_NAME="$(awk -F'"' '/^[[:space:]]*versionName[[:space:]]*=/ { print $2; exit }' "$PROJECT_ROOT/android/app/build.gradle.kts")"
[[ -n "$VERSION_CODE" && -n "$VERSION_NAME" ]] || { printf '[terminal-apk] ERROR: cannot read versionCode/versionName\n' >&2; exit 1; }

cd "$PROJECT_ROOT/android"
if [[ "${APK_BUILD_OFFLINE:-true}" == "true" ]]; then
  ./gradlew --offline :app:assembleDebug
else
  ./gradlew :app:assembleDebug
fi

SOURCE_APK="$PROJECT_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
OUTPUT_APK="$PROJECT_ROOT/dist/RemoteDevTerminal-${VERSION_NAME}-debug.apk"
[[ -s "$SOURCE_APK" ]] || { printf '[terminal-apk] ERROR: build output is missing: %s\n' "$SOURCE_APK" >&2; exit 1; }
mkdir -p "$PROJECT_ROOT/dist"
cp "$SOURCE_APK" "$OUTPUT_APK"

printf '[terminal-apk] Built version %s (code %s)\n' "$VERSION_NAME" "$VERSION_CODE"
printf '[terminal-apk] APK: %s\n' "$OUTPUT_APK"
sha256sum "$OUTPUT_APK"
