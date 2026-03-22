#!/usr/bin/env sh

set -eu

PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:${PATH:-}"

ENDPOINT="${RINGA_ENDPOINT:-http://127.0.0.1:8765/message}"
TEXT_OVERRIDE=""

usage() {
    cat <<'EOF'
Usage:
  ./terminal-host/ringa-send-clipboard.sh
  ./terminal-host/ringa-send-clipboard.sh --text "hello"
  printf 'hello' | ./terminal-host/ringa-send-clipboard.sh

Reads text from the local clipboard and sends it to the local Ringa host.

If stdin is piped in, stdin is used instead of the local clipboard.
Override the local host endpoint with:
  RINGA_ENDPOINT=http://127.0.0.1:8765/message
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        -h|--help)
            usage
            exit 0
            ;;
        --text)
            if [ "$#" -lt 2 ]; then
                echo "Missing value for $1" >&2
                exit 1
            fi
            TEXT_OVERRIDE=$2
            shift 2
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

read_input() {
    if [ -n "$TEXT_OVERRIDE" ]; then
        printf '%s' "$TEXT_OVERRIDE"
        return
    fi

    if [ -p /dev/stdin ]; then
        cat
        return
    fi

    if command -v pbpaste >/dev/null 2>&1; then
        if CLIP_TEXT=$(pbpaste 2>/dev/null); then
            printf '%s' "$CLIP_TEXT"
            return
        fi
    fi

    if command -v wl-paste >/dev/null 2>&1; then
        if CLIP_TEXT=$(wl-paste --no-newline 2>/dev/null); then
            printf '%s' "$CLIP_TEXT"
            return
        fi
    fi

    if command -v xclip >/dev/null 2>&1; then
        if CLIP_TEXT=$(xclip -selection clipboard -o 2>/dev/null); then
            printf '%s' "$CLIP_TEXT"
            return
        fi
    fi

    if command -v xsel >/dev/null 2>&1; then
        if CLIP_TEXT=$(xsel --clipboard --output 2>/dev/null); then
            printf '%s' "$CLIP_TEXT"
            return
        fi
    fi

    if command -v powershell.exe >/dev/null 2>&1; then
        if CLIP_TEXT=$(powershell.exe -NoProfile -Command Get-Clipboard 2>/dev/null | tr -d '\r'); then
            printf '%s' "$CLIP_TEXT"
            return
        fi
    fi

    echo "Could not read the local clipboard. Use stdin, --text, or install/configure a supported clipboard backend." >&2
    exit 1
}

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

encode_base64() {
    if command -v base64 >/dev/null 2>&1; then
        base64 | tr -d '\n'
        return
    fi

    if command -v openssl >/dev/null 2>&1; then
        openssl base64 -A
        return
    fi

    echo "Neither base64 nor openssl is available" >&2
    exit 1
}

CLIP_TEXT=$(read_input)

if [ -z "$CLIP_TEXT" ]; then
    echo "Clipboard is empty" >&2
    exit 1
fi

TEXT_B64=$(printf '%s' "$CLIP_TEXT" | encode_base64)
SENT_AT=$(date -Iseconds)
CURL_BIN=$(resolve_curl)

PAYLOAD=$(cat <<EOF
{"source":"ringa-clipboard-script","sent_at":"$SENT_AT","request_type":"clipboard","clipboard_text_b64":"$TEXT_B64","trigger":"shell_clipboard_sender"}
EOF
)

if ! "$CURL_BIN" -fsS --max-time 5 -X POST "$ENDPOINT" -H "Content-Type: application/json" -d "$PAYLOAD" >/dev/null; then
    if command -v notify-send >/dev/null 2>&1; then
        notify-send "Ringa clipboard sender" "Failed to send clipboard request to $ENDPOINT"
    fi
    exit 1
fi

exit 0
