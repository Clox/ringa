# Chrome -> Terminal -> Android PoC

This repo contains three pieces:

- `chrome-extension/`: Chrome extension that sends dial requests, selected text, and images to the phone clipboard.
- `terminal-host/`: Python terminal app that receives browser messages and forwards them.
- `android-receiver/`: Android app that runs a foreground listener service on LAN, displays events, and advertises itself via mDNS.

The suite also supports clipboard sync from the PC to the Android device over the same terminal-host -> mDNS -> Android path.

## Message flow

`Chrome extension` -> `terminal-host/server.py` -> `Android app endpoint`

## 1) Start the Android receiver app

1. Open `/home/oscar/projects/ringa/android-receiver` in Android Studio.
2. Let Gradle sync and install the app on your Android device.
3. Launch the app once. The listener auto-starts.

Important:

- Computer and phone must be on the same network.
- The app automatically advertises mDNS service type: `_ringa-receiver._tcp`.
- The listener runs in a foreground service and keeps working when the app UI is backgrounded.
- The app also attempts auto-start on device boot and on app update.
- You will see a persistent notification while the listener is running.
- On first launch, accept notification/battery prompts to reduce OEM background killing.

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
2. Enter a phone number and click `Send dial request`.
3. You can also highlight a phone number in a web page, right-click, and choose `Dial via Ringa`.
4. To send selected text to the phone clipboard, right-click the selection and choose `Send selection to phone clipboard`.
5. To send other text, click the extension icon, paste the text into `Text for phone clipboard`, and click `Send to phone clipboard`.
6. To copy an image to the phone, right-click the image and choose `Send image to phone clipboard`. Chrome asks for access to that image's site on first use. Open the Ringa popup to see whether the transfer succeeded.

Chrome groups the two text-selection actions under a `Ringa` submenu when both are enabled. With only one enabled, that action appears directly in the right-click menu. The image action appears when right-clicking an image. After updating the unpacked extension, click its reload button on `chrome://extensions`; restarting Chrome is not required.
The popup has a separate switch for each action. Turn a switch off to hide that action from the right-click menu, or on to show it again. The setting is saved locally and takes effect immediately; the popup buttons remain available.
Image copying supports PNG, JPEG, GIF, and WebP images up to 4 MB from HTTP, HTTPS, or data URLs. Blob URLs and images that require access Chrome cannot grant are not supported. The Android receiver must be updated to use the image clipboard feature.

You will see:

- Terminal app prints the incoming browser payload and forwarding status.
- Android app appends the forwarded event to its log.
- For dial events:
  - if app UI is visible, Android app opens the dialer directly.
  - if app UI is backgrounded, Android posts a high-priority "Tap to dial" notification (Android background-launch policy).

## 5) Send clipboard text from PC to Android

Use the dedicated sender script:

```bash
cd /home/oscar/projects/ringa
./terminal-host/ringa-send-clipboard.sh
```

Behavior:

- Reads from stdin if data is piped in.
- Otherwise reads from the local clipboard (`pbpaste`, `wl-paste`, `xclip`, `xsel`, or `powershell.exe`).
- Sends the clipboard payload to the local Ringa host on `http://127.0.0.1:8765/message` by default.
- The terminal host then forwards it to the Android app using the existing LAN auto-discovery flow.

Examples:

```bash
printf 'hello from pc' | ./terminal-host/ringa-send-clipboard.sh
./terminal-host/ringa-send-clipboard.sh --text "hello from pc"
RINGA_ENDPOINT=http://127.0.0.1:8765/message ./terminal-host/ringa-send-clipboard.sh
```

This script is intended to be safe to bind to a desktop keyboard shortcut as long as the local Ringa host service is running.

### Send a local image from Nemo

Right-click one local PNG, JPEG, GIF, or WebP file and choose `Skicka bild till telefonens urklipp`. The file must be no larger than 4 MB. Ringa shows a desktop notification with the result.

`install.sh` adds this Nemo action when Nemo is installed. To add or refresh just the action, run:

```bash
bash terminal-host/install-nemo-action.sh
```

You can also send an image from the terminal with `python3 terminal-host/ringa-send-image.py /path/to/image.png`.

## 6) Fetch clipboard text from Android to PC

Use the fetch script:

```bash
cd /home/oscar/projects/ringa
./terminal-host/ringa-fetch-clipboard.sh
```

Behavior:

- Sends a clipboard fetch request to the local Ringa host on `http://127.0.0.1:8765/message`.
- The host forwards that request to the Android app over the existing LAN auto-discovery path.
- On success, the returned text is written into the local clipboard.
- Use `--print` if you want the fetched text on stdout instead.

Examples:

```bash
./terminal-host/ringa-fetch-clipboard.sh
./terminal-host/ringa-fetch-clipboard.sh --print
RINGA_ENDPOINT=http://127.0.0.1:8765/message ./terminal-host/ringa-fetch-clipboard.sh
```

Important limitation:

- On non-rooted phones, the `Ringa` app must stay open on the phone while fetching. Android clipboard privacy restrictions prevent background clipboard reads.
- On rooted phones, enable `Use root for clipboard export (background)` in the Android app to allow background fetches.

## 7) Optional: Linux system `tel:` handler

If you want desktop `tel:` links to route into the local terminal host:

```bash
cd /home/oscar/projects/ringa
./install.sh
```

`install.sh` will:

- register a custom `x-scheme-handler/tel` desktop entry in `~/.local/share/applications`
- ask whether to set it as your default `tel:` handler
- ask whether to install/update a `systemd --user` background service for the Ringa host (`terminal-host/server.py`)
- ask whether to build the Android debug APK
- ask whether to install that APK to a connected device via adb

The handler forwards `tel:` links to `http://127.0.0.1:8765/message` as a dial payload.

If you choose the service option, `install.sh` will also:

- create/update `.venv` and install dependencies from `terminal-host/requirements.txt`
- install/update `~/.config/systemd/user/ringa-host.service`
- enable, start, and restart the service so code changes are applied
- optionally try to enable `loginctl` lingering for reboot auto-start before login

If you build Android from `install.sh`, the APK path is:

- `/home/oscar/projects/ringa/android-receiver/app/build/outputs/apk/debug/app-debug.apk`

To apply updates after changing terminal-host code, rerun:

```bash
cd /home/oscar/projects/ringa
./install.sh
```

## JSON payloads

Dial request payload:

```json
{
  "source": "chrome-extension",
  "sent_at": "2026-03-09T18:00:00.000Z",
  "request_type": "dial",
  "dial_number": "+15551234567"
}
```

Dial request payload from context-menu selection:

```json
{
  "source": "chrome-extension",
  "sent_at": "2026-03-09T18:00:00.000Z",
  "request_type": "dial",
  "dial_number": "0462769200",
  "trigger": "selection_context_menu",
  "selection_raw": "046-276 92 00",
  "page_url": "https://example.com/contact"
}
```

Dial request payload from Linux `tel:` handler (`install.sh` option):

```json
{
  "source": "linux-tel-handler",
  "sent_at": "2026-03-09T18:00:00.000Z",
  "request_type": "dial",
  "dial_number": "0462769200",
  "trigger": "xdg_tel_handler"
}
```

Clipboard request payload from the shell sender:

```json
{
  "source": "ringa-clipboard-script",
  "sent_at": "2026-03-22T18:00:00+01:00",
  "request_type": "clipboard",
  "clipboard_text_b64": "aGVsbG8gZnJvbSBwYw==",
  "trigger": "shell_clipboard_sender"
}
```

Clipboard fetch request payload from the shell fetcher:

```json
{
  "source": "ringa-clipboard-fetch-script",
  "sent_at": "2026-03-22T18:00:00+01:00",
  "request_type": "clipboard_fetch",
  "trigger": "shell_clipboard_fetch"
}
```

## Terminal host flags

- `--host` and `--port`: where the local Chrome-facing endpoint runs.
- `--forward-url`: Android endpoint URL to forward each message (manual mode).
- `--auto-discover`: discover Android endpoint automatically via mDNS.
- `--forward-timeout`: seconds before forwarding request times out.
- `--discover-service-type`: mDNS service type (default: `_ringa-receiver._tcp.local.`; short form without `.local.` is accepted).
- `--discover-timeout`: mDNS lookup timeout seconds.
- `--discover-name`: substring filter for service names (default: `RingaReceiver`).

## Quick test without Chrome

```bash
curl -X POST http://127.0.0.1:8765/message \
  -H "Content-Type: application/json" \
  -d '{"source":"cli","request_type":"dial","dial_number":"0462769200"}'

curl -X POST http://127.0.0.1:8765/message \
  -H "Content-Type: application/json" \
  -d '{"source":"cli","request_type":"clipboard","clipboard_text_b64":"aGVsbG8="}'
```
