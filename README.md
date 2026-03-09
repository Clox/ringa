# Chrome -> Terminal -> Android PoC

This repo contains three pieces:

- `chrome-extension/`: Chrome extension popup that sends messages.
- `terminal-host/`: Python terminal app that receives browser messages and forwards them.
- `android-receiver/`: Android app that listens on LAN, displays messages, and advertises itself via mDNS.

## Message flow

`Chrome extension` -> `terminal-host/server.py` -> `Android app endpoint`

## 1) Start the Android receiver app

1. Open `/home/oscar/projects/ringa/android-receiver` in Android Studio.
2. Let Gradle sync and install the app on your Android device.
3. Launch the app and tap `Start server` (if not already running).

Important:

- Computer and phone must be on the same network.
- Keep the Android app open while testing.
- The app automatically advertises mDNS service type: `_ringa-receiver._tcp`.

## 2) Terminal host forwarding modes

### Option A (recommended): Auto-discover Android app (no phone IP needed)

Create a local venv and install dependency once:

```bash
cd /home/oscar/projects/ringa
python3 -m venv .venv
.venv/bin/pip install -r terminal-host/requirements.txt
```

Run host:

```bash
cd /home/oscar/projects/ringa
.venv/bin/python terminal-host/server.py \
  --host 127.0.0.1 \
  --port 8765 \
  --auto-discover
```

### Option B: Manual endpoint (kept for compatibility)

```bash
cd /home/oscar/projects/ringa
python3 terminal-host/server.py \
  --host 127.0.0.1 \
  --port 8765 \
  --forward-url http://192.168.1.50:9090/message
```

## 3) Load the Chrome extension

1. Open `chrome://extensions`
2. Enable `Developer mode`
3. Click `Load unpacked`
4. Select `/home/oscar/projects/ringa/chrome-extension`

## 4) Send from Chrome

1. Click the extension icon in Chrome.
2. For a regular text event, enter a message and click `Send to terminal`.
3. For a dial event, enter a phone number and click `Send dial request`.

You will see:

- Terminal app prints the incoming browser payload and forwarding status.
- Android app appends the forwarded message to its message log.
- For dial events, Android app opens the default dialer with the provided number.

## JSON payloads

Regular message payload:

```json
{
  "source": "chrome-extension",
  "sent_at": "2026-03-09T18:00:00.000Z",
  "message": "hello from browser"
}
```

Dial request payload:

```json
{
  "source": "chrome-extension",
  "sent_at": "2026-03-09T18:00:00.000Z",
  "request_type": "dial",
  "dial_number": "+15551234567"
}
```

## Terminal host flags

- `--host` and `--port`: where the local Chrome-facing endpoint runs.
- `--forward-url`: Android endpoint URL to forward each message (manual mode).
- `--auto-discover`: discover Android endpoint automatically via mDNS.
- `--forward-timeout`: seconds before forwarding request times out.
- `--discover-service-type`: mDNS service type (default: `_ringa-receiver._tcp.local.`).
- `--discover-timeout`: mDNS lookup timeout seconds.
- `--discover-name`: substring filter for service names (default: `RingaReceiver`).

## Quick test without Chrome

```bash
curl -X POST http://127.0.0.1:8765/message \
  -H "Content-Type: application/json" \
  -d '{"message":"hello from curl","source":"cli"}'
```
