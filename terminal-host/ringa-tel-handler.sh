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
TMP_RESPONSE_FILE="$(mktemp)"
trap 'rm -f "$TMP_ERR_FILE" "$TMP_RESPONSE_FILE"' EXIT HUP INT TERM

CURL_STATUS=0
curl -sS \
  --connect-timeout 2 \
  --max-time 12 \
  -X POST "$ENDPOINT" \
  -H "Content-Type: application/json" \
  -d "$PAYLOAD" \
  -o "$TMP_RESPONSE_FILE" \
  2>"$TMP_ERR_FILE" || CURL_STATUS=$?

if [ "$CURL_STATUS" -ne 0 ]; then
  case "$CURL_STATUS" in
    7)
      notify_failure "Ringa host is not running on this computer. Start it with ./install.sh, or run the local host on $ENDPOINT."
      ;;
    28)
      notify_failure "Ringa host did not respond in time. The phone may be offline or missing from Wi-Fi."
      ;;
    *)
      ERROR_TEXT="$(tr -d '\r' <"$TMP_ERR_FILE" | tail -n 1)"
      if [ -z "$ERROR_TEXT" ]; then
        ERROR_TEXT="unknown error"
      fi
      notify_failure "Failed to contact the local Ringa host at $ENDPOINT: $ERROR_TEXT"
      ;;
  esac
  exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
  notify_failure "Ringa host replied, but python3 is unavailable to verify whether the phone received the request."
  exit 1
fi

FORWARD_RESULT="$(
  python3 - "$TMP_RESPONSE_FILE" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as response_file:
    try:
        response = json.load(response_file)
    except (OSError, json.JSONDecodeError):
        print("invalid_response")
        raise SystemExit

forwarded = response.get("forwarded")
detail = str(response.get("forward_detail") or "")

if forwarded is True:
    print("forwarded")
elif (
    "auto-discovery failed" in detail
    or "no matching service found" in detail
    or "no matching avahi service" in detail
):
    print("phone_not_found")
elif forwarded is False:
    print("forward_failed")
else:
    print("forwarding_unavailable")
PY
)"

case "$FORWARD_RESULT" in
  forwarded)
    exit 0
    ;;
  phone_not_found)
    notify_failure "Ringa host is running, but the phone could not be found. Make sure the phone's Wi-Fi and Ringa listener are on."
    ;;
  forward_failed)
    notify_failure "Ringa host is running, but it could not send the number to the phone. Check the phone's Wi-Fi and Ringa listener."
    ;;
  forwarding_unavailable)
    notify_failure "Ringa host is running, but Android forwarding is not configured."
    ;;
  *)
    notify_failure "Ringa host returned an invalid response."
    ;;
esac

exit 1
