#!/usr/bin/env bash
set -euo pipefail

ENDPOINT="${RINGA_ENDPOINT:-http://127.0.0.1:8765/message}"
RAW_INPUT="${1:-}"

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

if ! curl -sS --max-time 3 -X POST "$ENDPOINT" -H "Content-Type: application/json" -d "$PAYLOAD" >/dev/null; then
  if command -v notify-send >/dev/null 2>&1; then
    notify-send "Ringa tel handler" "Failed to send dial request to $ENDPOINT"
  fi
  exit 1
fi

exit 0
