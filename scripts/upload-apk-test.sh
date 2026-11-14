#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
VERSION_CODE="$(awk '/^[[:space:]]*versionCode[[:space:]]*=/ { gsub(/[^0-9]/, "", $0); print; exit }' "$PROJECT_ROOT/app/build.gradle.kts")"
VERSION_NAME="$(awk -F'"' '/^[[:space:]]*versionName[[:space:]]*=/ { print $2; exit }' "$PROJECT_ROOT/app/build.gradle.kts")"
[[ -n "$VERSION_CODE" && -n "$VERSION_NAME" ]] || { printf '[terminal-apk] ERROR: cannot read app version\n' >&2; exit 1; }

APK="${1:-$PROJECT_ROOT/dist/RemoteDevTerminal-${VERSION_NAME}-debug.apk}"
TOKEN_FILE="${APK_RELEASE_TOKEN_FILE:-$PROJECT_ROOT/config/app-release-upload.token}"
TOKEN="${APK_RELEASE_TOKEN:-}"
UPLOAD_URL="https://rxtest.shdmu-ch.com:18801/api/app/wish_adm/app-releases/uploads"
UPDATE_LOG="${APK_RELEASE_UPDATE_LOG:-Remote Dev Terminal ${VERSION_NAME}}"

fail() { printf '[terminal-apk] ERROR: %s\n' "$*" >&2; exit 1; }
[[ -s "$APK" ]] || fail "APK not found: $APK (run scripts/build-apk.sh first)"
if [[ -z "$TOKEN" && -s "$TOKEN_FILE" ]]; then
  TOKEN="$(tr -d ' \t\r\n' < "$TOKEN_FILE")"
fi
[[ -n "$TOKEN" ]] || fail "set APK_RELEASE_TOKEN or create $TOKEN_FILE"

CURL_CONFIG="$(mktemp)"
RESPONSE="$(mktemp)"
trap 'rm -f "$CURL_CONFIG" "$RESPONSE"' EXIT
printf 'header = "X-Upload-Token: %s"\n' "$TOKEN" > "$CURL_CONFIG"
unset TOKEN

printf '[terminal-apk] Uploading %s to APP test management\n' "$APK"
printf '[terminal-apk] Version %s (code %s); status will be draft\n' "$VERSION_NAME" "$VERSION_CODE"
if ! HTTP_CODE="$(curl --config "$CURL_CONFIG" --noproxy '*' --retry 3 --retry-delay 2 -sS \
  -o "$RESPONSE" -w '%{http_code}' -X POST "$UPLOAD_URL" \
  -F "file=@$APK;type=application/vnd.android.package-archive" \
  -F "platform=android" \
  -F "update_log=$UPDATE_LOG" \
  -F "force_update=false" \
  -F "min_supported_version_code=1" \
  --max-time 600)"; then
  fail "upload request failed"
fi
if [[ "$HTTP_CODE" != "200" ]]; then
  head -c 1000 "$RESPONSE" >&2
  printf '\n[terminal-apk] ERROR: HTTP %s\n' "$HTTP_CODE" >&2
  exit 1
fi

python3 - "$RESPONSE" "$VERSION_CODE" <<'PY'
import json, sys

path, expected_version = sys.argv[1:]
with open(path, encoding="utf-8") as f:
    envelope = json.load(f)
if not isinstance(envelope, dict) or envelope.get("code") != 0:
    raise SystemExit("APP test management rejected the upload: %s" % (envelope.get("msg") if isinstance(envelope, dict) else "invalid response"))
data = envelope.get("data") or {}
if str(data.get("version_code", "")) != expected_version:
    raise SystemExit("uploaded version_code does not match this project")
print("[terminal-apk] Draft created: id=%s version_code=%s status=%s" % (
    data.get("id"), data.get("version_code"), data.get("status")))
PY
