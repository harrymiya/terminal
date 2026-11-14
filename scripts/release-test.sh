#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
VERSION_NAME="$(awk -F'"' '/^[[:space:]]*versionName[[:space:]]*=/ { print $2; exit }' "$PROJECT_ROOT/app/build.gradle.kts")"
[[ -n "$VERSION_NAME" ]] || { printf '[terminal-apk] ERROR: cannot read versionName\n' >&2; exit 1; }
bash "$SCRIPT_DIR/build-apk.sh"
APK_PATH="$PROJECT_ROOT/dist/RemoteDevTerminal-${VERSION_NAME}-debug.apk"
bash "$SCRIPT_DIR/upload-apk-test.sh" "$APK_PATH"
