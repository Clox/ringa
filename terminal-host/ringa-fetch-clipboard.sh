#!/usr/bin/env sh

set -eu

PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:${PATH:-}"

ENDPOINT="${RINGA_ENDPOINT:-http://127.0.0.1:8765/message}"
PRINT_ONLY=0

usage() {
    cat <<'EOF'
Usage:
  ./terminal-host/ringa-fetch-clipboard.sh
  ./terminal-host/ringa-fetch-clipboard.sh --print
  ./terminal-host/ringa-fetch-clipboard.sh --endpoint http://127.0.0.1:8765/message

Fetches clipboard text from the Android Ringa app and writes it to the local clipboard.

Notes:
  - The local Ringa host must be running.
  - On non-rooted phones, the Ringa app must stay open on the phone while fetching.
  - On rooted phones, enable "Use root for clipboard export (background)" in the Android app to allow background fetches.
  - Use --print to write the fetched text to stdout instead of the local clipboard.
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        -h|--help)
            usage
            exit 0
            ;;
        --print|--stdout)
            PRINT_ONLY=1
            shift
            ;;
        --endpoint)
            if [ "$#" -lt 2 ]; then
                echo "Missing value for $1" >&2
                exit 1
            fi
            ENDPOINT=$2
            shift 2
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage >&2
            exit 1
            ;;
    esac
done

resolve_curl() {
    if command -v curl >/dev/null 2>&1; then
        command -v curl
        return
    fi

    if [ -x /usr/bin/curl ]; then
        printf '%s\n' /usr/bin/curl
        return
    fi

    if [ -x /bin/curl ]; then
        printf '%s\n' /bin/curl
        return
    fi

    echo "curl not found in PATH" >&2
    exit 1
}

resolve_python() {
    SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
    ROOT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

    if [ -x "$ROOT_DIR/.venv/bin/python" ]; then
        printf '%s\n' "$ROOT_DIR/.venv/bin/python"
        return
    fi

    if command -v python3 >/dev/null 2>&1; then
        command -v python3
        return
    fi

    if command -v python >/dev/null 2>&1; then
        command -v python
        return
    fi

    echo "python3 not found in PATH" >&2
    exit 1
}

write_local_clipboard() {
    text_file=$1

    if command -v pbcopy >/dev/null 2>&1; then
        pbcopy <"$text_file"
        return
    fi

    if [ -n "${DISPLAY:-}" ] && command -v xclip >/dev/null 2>&1; then
        xclip -selection clipboard <"$text_file"
        return
    fi

    if [ -n "${DISPLAY:-}" ] && command -v xsel >/dev/null 2>&1; then
        xsel --clipboard --input <"$text_file"
        return
    fi

    if [ -n "${WAYLAND_DISPLAY:-}" ] && command -v wl-copy >/dev/null 2>&1; then
        wl-copy <"$text_file"
        return
    fi

    if command -v clip.exe >/dev/null 2>&1; then
        clip.exe <"$text_file"
        return
    fi

    if command -v powershell.exe >/dev/null 2>&1; then
        powershell.exe -NoProfile -Command Set-Clipboard <"$text_file"
        return
    fi

    echo "Could not write to the local clipboard. Use --print or install/configure a supported clipboard backend." >&2
    exit 1
}

CURL_BIN=$(resolve_curl)
PYTHON_BIN=$(resolve_python)
RESPONSE_FILE=$(mktemp)
TEXT_FILE=$(mktemp)
trap 'rm -f "$RESPONSE_FILE" "$TEXT_FILE"' EXIT HUP INT TERM
SENT_AT=$(date -Iseconds)

PAYLOAD=$(cat <<EOF
{"source":"ringa-clipboard-fetch-script","sent_at":"$SENT_AT","request_type":"clipboard_fetch","trigger":"shell_clipboard_fetch"}
EOF
)

HTTP_CODE=$(
    "$CURL_BIN" -sS --max-time 8 \
        -o "$RESPONSE_FILE" \
        -w "%{http_code}" \
        -X POST "$ENDPOINT" \
        -H "Content-Type: application/json" \
        -d "$PAYLOAD"
) || {
    if command -v notify-send >/dev/null 2>&1; then
        notify-send "Ringa clipboard fetch" "Failed to contact $ENDPOINT"
    fi
    exit 1
}

if ! "$PYTHON_BIN" - "$RESPONSE_FILE" "$HTTP_CODE" >"$TEXT_FILE" <<'PY'
import base64
import json
import sys

response_path = sys.argv[1]
http_code = int(sys.argv[2])

with open(response_path, "r", encoding="utf-8") as handle:
    raw = handle.read()

try:
    payload = json.loads(raw or "{}")
except json.JSONDecodeError as exc:
    print(f"Invalid response from local Ringa host: {exc}", file=sys.stderr)
    sys.exit(1)

if http_code != 200 or not payload.get("ok"):
    error_text = payload.get("error") or f"HTTP {http_code}"
    print(error_text, file=sys.stderr)
    sys.exit(1)

clipboard_b64 = payload.get("clipboard_text_b64")
if clipboard_b64 is None:
    print("Android response did not include clipboard_text_b64", file=sys.stderr)
    sys.exit(1)

try:
    clipboard_text = base64.b64decode(clipboard_b64).decode("utf-8")
except Exception as exc:  # noqa: BLE001
    print(f"Failed to decode Android clipboard payload: {exc}", file=sys.stderr)
    sys.exit(1)

sys.stdout.write(clipboard_text)
PY
then
    if command -v notify-send >/dev/null 2>&1; then
        notify-send "Ringa clipboard fetch" "Clipboard fetch failed"
    fi
    exit 1
fi

if [ "$PRINT_ONLY" -eq 1 ]; then
    cat "$TEXT_FILE"
    exit 0
fi

write_local_clipboard "$TEXT_FILE"
exit 0
