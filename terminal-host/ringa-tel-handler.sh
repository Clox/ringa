#!/usr/bin/env bash
set -euo pipefail

ENDPOINT="${RINGA_ENDPOINT:-http://127.0.0.1:8765/message}"
RAW_INPUT="${1:-}"

notify_failure() {
  message="$1"
  if command -v notify-send >/dev/null 2>&1; then
    notify-send "Ringa tel handler" "$message"
  fi
}

send_failure_message() {
  error_text="$1"

  case "$error_text" in
    *"Connection refused"*|*"Failed to connect to 127.0.0.1 port 8765"*|*"Could not connect to server"*|*"No route to host"*|*"Operation timed out"*)
      notify_failure "Ringa host is not running on this computer. Start it with ./install.sh, or run the local host on $ENDPOINT."
      ;;
    *)
      notify_failure "Failed to send dial request to $ENDPOINT: $error_text"
      ;;
  esac
}

if [[ -z "$RAW_INPUT" ]]; then
  exit 1
fi

RAW_NUMBER="${RAW_INPUT#tel:}"
RAW_NUMBER="${RAW_NUMBER%%\?*}"
RAW_NUMBER="${RAW_NUMBER%%#*}"
RAW_NUMBER="${RAW_NUMBER//%20/ }"

SANITIZED_NUMBER="$(printf '%s' "$RAW_NUMBER" | tr -cd '0-9+*#,;pwPW() -')"
SANITIZED_NUMBER="$(printf '%s' "$SANITIZED_NUMBER" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"

if [[ -z "$SANITIZED_NUMBER" ]]; then
  exit 1
fi

JSON_NUMBER="$(printf '%s' "$SANITIZED_NUMBER" | sed 's/\\/\\\\/g; s/"/\\"/g')"
SENT_AT="$(date -Iseconds)"

PAYLOAD="{\"source\":\"linux-tel-handler\",\"sent_at\":\"$SENT_AT\",\"request_type\":\"dial\",\"dial_number\":\"$JSON_NUMBER\",\"trigger\":\"xdg_tel_handler\"}"

TMP_ERR_FILE="$(mktemp)"
trap 'rm -f "$TMP_ERR_FILE"' EXIT HUP INT TERM

CURL_STATUS=0
curl -sS --max-time 3 -X POST "$ENDPOINT" -H "Content-Type: application/json" -d "$PAYLOAD" >/dev/null 2>"$TMP_ERR_FILE" || CURL_STATUS=$?
if [ "$CURL_STATUS" -ne 0 ]; then
  case "$CURL_STATUS" in
    7|28)
      notify_failure "Ringa host is not running on this computer. Start it with ./install.sh, or run the local host on $ENDPOINT."
      ;;
    *)
      ERROR_TEXT="$(tr -d '\r' <"$TMP_ERR_FILE" | tail -n 1)"
      if [ -z "$ERROR_TEXT" ]; then
        ERROR_TEXT="unknown error"
      fi
      send_failure_message "$ERROR_TEXT"
      ;;
  esac
  exit 1
fi

exit 0
