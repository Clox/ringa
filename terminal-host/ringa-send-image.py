#!/usr/bin/env python3
"""Send one local image file to the Android clipboard through the Ringa host."""

import argparse
import base64
import datetime as dt
import json
import shutil
import subprocess
import sys
from pathlib import Path
from urllib import error, request


MAX_IMAGE_BYTES = 4 * 1024 * 1024
DEFAULT_ENDPOINT = "http://127.0.0.1:8765/message"


def image_mime_type(data: bytes) -> str:
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if data.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if data.startswith((b"GIF87a", b"GIF89a")):
        return "image/gif"
    if data.startswith(b"RIFF") and data[8:12] == b"WEBP":
        return "image/webp"
    raise ValueError("Unsupported image type. Use PNG, JPEG, GIF, or WebP.")


def notify(message: str, success: bool) -> None:
    if shutil.which("notify-send"):
        try:
            subprocess.run(
                ["notify-send", "-a", "Ringa", "Ringa", message],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=3,
                check=False,
            )
        except subprocess.TimeoutExpired:
            pass
    print(message, file=sys.stdout if success else sys.stderr)


def send_image(path: Path, endpoint: str) -> None:
    if not path.is_file():
        raise ValueError("Select one local image file.")
    size = path.stat().st_size
    if not 0 < size <= MAX_IMAGE_BYTES:
        raise ValueError("Image must be between 1 byte and 4 MB.")

    data = path.read_bytes()
    if len(data) > MAX_IMAGE_BYTES:
        raise ValueError("Image exceeds the 4 MB limit.")
    mime_type = image_mime_type(data)
    payload = {
        "source": "ringa-nemo-action",
        "sent_at": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        "request_type": "clipboard_image",
        "image_mime_type": mime_type,
        "image_data_b64": base64.b64encode(data).decode("ascii"),
        "trigger": "file_manager_context_menu",
    }
    http_request = request.Request(
        endpoint,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with request.urlopen(http_request, timeout=20) as response:
            result = json.load(response)
    except error.HTTPError as exc:
        raise RuntimeError(f"Ringa host returned HTTP {exc.code}.") from exc
    except error.URLError as exc:
        raise RuntimeError(f"Could not reach Ringa host: {exc.reason}") from exc
    if result.get("forwarded") is not True:
        raise RuntimeError(result.get("forward_detail") or "The phone did not receive the image.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", type=Path, help="one local PNG, JPEG, GIF, or WebP file")
    parser.add_argument("--endpoint", default=DEFAULT_ENDPOINT)
    args = parser.parse_args()
    try:
        send_image(args.image, args.endpoint)
    except (OSError, ValueError, RuntimeError, json.JSONDecodeError) as exc:
        notify(f"Could not send image: {exc}", success=False)
        return 1
    notify(f"Image sent to phone clipboard: {args.image.name}", success=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
