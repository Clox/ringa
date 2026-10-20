#!/usr/bin/env python3
import argparse
import datetime as dt
import json
import shutil
import subprocess
import threading
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Dict, Optional, Tuple
from urllib import error, request

try:
    from zeroconf import ServiceBrowser, ServiceListener, Zeroconf
except ImportError:
    ServiceBrowser = None
    ServiceListener = object  # type: ignore[assignment]
    Zeroconf = None


def now_iso() -> str:
    return dt.datetime.now(dt.timezone.utc).astimezone().isoformat(timespec="seconds")


def redact_clipboard_value(key: str, value: Any) -> str:
    if not isinstance(value, str):
        return "<redacted>"
    if key in {"clipboard_text_b64", "image_data_b64"}:
        return f"<base64 {len(value)} chars redacted>"
    return f"<{len(value)} chars redacted>"


def sanitize_payload_for_log(value: Any) -> Any:
    if isinstance(value, dict):
        sanitized: Dict[str, Any] = {}
        for key, nested_value in value.items():
            if key in {"clipboard_text", "clipboard_text_b64", "image_data_b64"}:
                sanitized[key] = redact_clipboard_value(key, nested_value)
            else:
                sanitized[key] = sanitize_payload_for_log(nested_value)
        return sanitized
    if isinstance(value, list):
        return [sanitize_payload_for_log(item) for item in value]
    return value


@dataclass(frozen=True)
class ForwardResult:
    ok: bool
    detail: str
    response_status: Optional[int] = None
    response_body: Optional[str] = None


def discover_forward_url(
    service_type: str,
    timeout_seconds: float,
    name_substring: Optional[str],
) -> Tuple[Optional[str], Optional[str]]:
    if Zeroconf is None or ServiceBrowser is None:
        avahi_url, avahi_error = discover_forward_url_avahi(service_type, timeout_seconds, name_substring)
        if avahi_url:
            return avahi_url, None
        return None, (
            "python package 'zeroconf' is not installed; run: pip install zeroconf"
            f"; avahi fallback: {avahi_error}"
        )

    normalized = service_type if service_type.endswith(".") else service_type + "."
    if normalized.endswith(".local."):
        service_types = [normalized]
    else:
        service_types = [normalized[:-1] + ".local."]

    found_event = threading.Event()
    result: Dict[str, str] = {}

    class FirstServiceListener(ServiceListener):  # type: ignore[misc, valid-type]
        def _capture(self, zc: Zeroconf, discovered_type: str, service_name: str) -> None:
            if name_substring and name_substring.lower() not in service_name.lower():
                return
            info = zc.get_service_info(discovered_type, service_name, timeout=int(timeout_seconds * 1000))
            if info is None:
                return
            addresses = info.parsed_addresses()
            if not addresses:
                return
            ipv4_addresses = [addr for addr in addresses if "." in addr]
            selected_addr = ipv4_addresses[0] if ipv4_addresses else addresses[0]
            result["url"] = f"http://{selected_addr}:{info.port}/message"
            result["service_name"] = service_name
            result["service_type"] = discovered_type
            found_event.set()

        def add_service(self, zc: Zeroconf, discovered_type: str, service_name: str) -> None:
            self._capture(zc, discovered_type, service_name)

        def update_service(self, zc: Zeroconf, discovered_type: str, service_name: str) -> None:
            self._capture(zc, discovered_type, service_name)

        def remove_service(self, zc: Zeroconf, discovered_type: str, service_name: str) -> None:
            del zc, discovered_type, service_name
            return

    zc = Zeroconf()
    try:
        for candidate in service_types:
            ServiceBrowser(zc, candidate, FirstServiceListener())
        found_event.wait(timeout_seconds)
    finally:
        zc.close()

    if "url" in result:
        return result["url"], None
    avahi_url, avahi_error = discover_forward_url_avahi(service_type, timeout_seconds, name_substring)
    if avahi_url:
        return avahi_url, None
    return None, (
        f"no matching service found for {service_types} in {timeout_seconds:.1f}s"
        f"; avahi fallback: {avahi_error}"
    )


def discover_forward_url_avahi(
    service_type: str,
    timeout_seconds: float,
    name_substring: Optional[str],
) -> Tuple[Optional[str], Optional[str]]:
    if shutil.which("avahi-browse") is None:
        return None, "avahi-browse not found"

    normalized = service_type if service_type.endswith(".") else service_type + "."
    if normalized.endswith(".local."):
        browse_type = normalized[: -len(".local.")]
    else:
        browse_type = normalized[:-1]

    cmd = ["avahi-browse", "-prt", browse_type]
    try:
        proc = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=max(1.0, timeout_seconds + 0.5),
            check=False,
        )
    except subprocess.TimeoutExpired:
        return None, "avahi-browse timed out"
    except Exception as exc:  # pylint: disable=broad-except
        return None, str(exc)

    if proc.returncode != 0:
        stderr = (proc.stderr or "").strip()
        return None, stderr or f"avahi-browse failed with exit code {proc.returncode}"

    ipv4_match: Optional[Tuple[str, str]] = None
    any_match: Optional[Tuple[str, str]] = None
    for line in (proc.stdout or "").splitlines():
        if not line.startswith("="):
            continue
        parts = line.split(";")
        if len(parts) < 9:
            continue
        proto = parts[2]
        service_name = parts[3]
        address = parts[7]
        port = parts[8]
        if name_substring and name_substring.lower() not in service_name.lower():
            continue
        if not address or not port.isdigit():
            continue
        candidate = (address, port)
        if proto == "IPv4":
            ipv4_match = candidate
            break
        if any_match is None:
            any_match = candidate

    selected = ipv4_match or any_match
    if selected is None:
        return None, f"no matching avahi service for {browse_type}"

    return f"http://{selected[0]}:{selected[1]}/message", None


class MessageHandler(BaseHTTPRequestHandler):
    server_version = "TerminalPoC/0.1"
    forward_url: Optional[str] = None
    forward_timeout: float = 3.0
    auto_discover: bool = False
    discover_service_type: str = "_ringa-receiver._tcp.local."
    discover_timeout: float = 2.5
    discover_name: Optional[str] = "RingaReceiver"
    discovered_forward_url: Optional[str] = None
    discover_error: Optional[str] = None
    discover_lock = threading.Lock()

    def _set_headers(self, status_code: int = 200) -> None:
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_OPTIONS(self) -> None:  # noqa: N802
        self._set_headers(204)

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/message":
            self._set_headers(404)
            self.wfile.write(b'{"ok":false,"error":"not found"}')
            return

        content_length = int(self.headers.get("Content-Length", "0"))
        if content_length > 8 * 1024 * 1024:
            self._set_headers(413)
            self.wfile.write(b'{"ok":false,"error":"payload too large"}')
            return
        body = self.rfile.read(content_length)

        try:
            payload: Dict[str, Any] = json.loads(body.decode("utf-8") or "{}")
        except json.JSONDecodeError:
            self._set_headers(400)
            self.wfile.write(b'{"ok":false,"error":"invalid json"}')
            return

        request_type = str(payload.get("request_type") or "").strip()
        print(f"[{now_iso()}] Received message")
        print(json.dumps(sanitize_payload_for_log(payload), indent=2, ensure_ascii=True))
        forward_result = self._forward_to_android(payload)
        if forward_result is not None:
            if forward_result.ok:
                print(f"Forwarded to Android: {forward_result.detail}")
            else:
                print(f"Forward to Android failed: {forward_result.detail}")
        print("-" * 40, flush=True)

        if request_type == "clipboard_fetch":
            self._respond_with_clipboard_fetch(forward_result)
            return

        response = json.dumps(
            {
                "ok": True,
                "received_at": now_iso(),
                "echo": sanitize_payload_for_log(payload),
                "forwarded": (forward_result.ok if forward_result is not None else None),
                "forward_detail": (forward_result.detail if forward_result is not None else None),
            },
            ensure_ascii=True,
        ).encode("utf-8")
        self._set_headers(200)
        self.wfile.write(response)

    def _respond_with_clipboard_fetch(self, forward_result: Optional[ForwardResult]) -> None:
        if forward_result is None:
            self._set_headers(503)
            self.wfile.write(
                b'{"ok":false,"error":"Android forwarding is disabled; set --forward-url or --auto-discover"}',
            )
            return

        status_code = forward_result.response_status or (200 if forward_result.ok else 502)
        body = forward_result.response_body
        if body is None:
            body = json.dumps(
                {
                    "ok": forward_result.ok,
                    "error": forward_result.detail,
                },
                ensure_ascii=True,
            )
        self._set_headers(status_code)
        self.wfile.write(body.encode("utf-8"))

    def _forward_to_android(self, payload: Dict[str, Any]) -> Optional[ForwardResult]:
        target_url, discover_error = self._resolve_forward_url()
        if target_url is None:
            if self.auto_discover:
                return ForwardResult(False, f"auto-discovery failed: {discover_error or 'unknown error'}")
            return None

        forwarded_payload: Dict[str, Any] = dict(payload)
        if not forwarded_payload.get("source"):
            forwarded_payload["source"] = "terminal-host"
        forwarded_payload["forwarded_by"] = "terminal-host"
        forwarded_payload["forwarded_at"] = now_iso()
        forwarded_payload["browser_payload"] = (
            {key: value for key, value in payload.items() if key != "image_data_b64"}
            if payload.get("request_type") == "clipboard_image"
            else payload
        )

        send_result = self._send_forward_payload(target_url, forwarded_payload)
        if send_result.ok:
            return self._with_target_detail(target_url, send_result)

        # Auto-discover mode retries once with a fresh discovery in case phone IP changed.
        if self.auto_discover and self.forward_url is None and send_result.response_status is None:
            retry_url, retry_error = self._resolve_forward_url(force_refresh=True)
            if retry_url and retry_url != target_url:
                retry_result = self._send_forward_payload(retry_url, forwarded_payload)
                if retry_result.ok:
                    return self._with_target_detail(retry_url, retry_result)
                return ForwardResult(
                    False,
                    f"{target_url} -> {send_result.detail}; retry {retry_url} -> {retry_result.detail}",
                    response_status=(retry_result.response_status or send_result.response_status),
                    response_body=(retry_result.response_body or send_result.response_body),
                )
            if retry_error:
                return ForwardResult(
                    False,
                    f"{target_url} -> {send_result.detail}; rediscovery failed: {retry_error}",
                    response_status=send_result.response_status,
                    response_body=send_result.response_body,
                )

        return self._with_target_detail(target_url, send_result)

    def _with_target_detail(self, target_url: str, result: ForwardResult) -> ForwardResult:
        return ForwardResult(
            ok=result.ok,
            detail=f"{target_url} -> {result.detail}",
            response_status=result.response_status,
            response_body=result.response_body,
        )

    def _resolve_forward_url(self, force_refresh: bool = False) -> Tuple[Optional[str], Optional[str]]:
        if self.forward_url:
            return self.forward_url, None
        if not self.auto_discover:
            return None, None

        if force_refresh:
            self.discovered_forward_url = None

        if self.discovered_forward_url:
            return self.discovered_forward_url, None

        with self.discover_lock:
            if self.discovered_forward_url:
                return self.discovered_forward_url, None
            url, discover_error = discover_forward_url(
                service_type=self.discover_service_type,
                timeout_seconds=self.discover_timeout,
                name_substring=self.discover_name,
            )
            if url:
                self.discovered_forward_url = url
                self.discover_error = None
                print(f"[{now_iso()}] Auto-discovered Android endpoint: {url}", flush=True)
                return url, None
            self.discover_error = discover_error
            return None, discover_error

    def _send_forward_payload(self, url: str, payload: Dict[str, Any]) -> ForwardResult:
        req = request.Request(
            url=url,
            data=json.dumps(payload, ensure_ascii=True).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with request.urlopen(req, timeout=self.forward_timeout) as resp:
                body = resp.read().decode("utf-8", errors="replace")
                short_body = (body[:160] + "...") if len(body) > 160 else body
                return ForwardResult(
                    True,
                    f"HTTP {resp.status} {short_body}",
                    response_status=resp.status,
                    response_body=body,
                )
        except error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            short_body = (body[:160] + "...") if len(body) > 160 else body
            return ForwardResult(
                False,
                f"HTTP {exc.code} {short_body}",
                response_status=exc.code,
                response_body=body,
            )
        except Exception as exc:  # pylint: disable=broad-except
            return ForwardResult(False, str(exc))

    def log_message(self, fmt: str, *args: Any) -> None:
        # Keep terminal output focused on incoming messages.
        return


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Small localhost server that prints messages from a Chrome extension."
    )
    parser.add_argument("--host", default="127.0.0.1", help="Bind host (default: 127.0.0.1)")
    parser.add_argument("--port", type=int, default=8765, help="Bind port (default: 8765)")
    parser.add_argument(
        "--forward-url",
        default=None,
        help="Optional Android endpoint URL, e.g. http://192.168.1.23:9090/message",
    )
    parser.add_argument(
        "--forward-timeout",
        type=float,
        default=3.0,
        help="Forwarding timeout seconds (default: 3.0)",
    )
    parser.add_argument(
        "--auto-discover",
        action="store_true",
        help="Auto-discover Android receiver via mDNS when --forward-url is not provided.",
    )
    parser.add_argument(
        "--discover-service-type",
        default="_ringa-receiver._tcp.local.",
        help="mDNS service type to discover (default: _ringa-receiver._tcp.local.)",
    )
    parser.add_argument(
        "--discover-timeout",
        type=float,
        default=2.5,
        help="Seconds to wait for mDNS discovery (default: 2.5)",
    )
    parser.add_argument(
        "--discover-name",
        default="RingaReceiver",
        help="Optional substring filter for mDNS service names (default: RingaReceiver)",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    MessageHandler.forward_url = args.forward_url
    MessageHandler.forward_timeout = args.forward_timeout
    MessageHandler.auto_discover = args.auto_discover
    MessageHandler.discover_service_type = args.discover_service_type
    MessageHandler.discover_timeout = args.discover_timeout
    MessageHandler.discover_name = args.discover_name
    MessageHandler.discovered_forward_url = None
    MessageHandler.discover_error = None

    httpd = ThreadingHTTPServer((args.host, args.port), MessageHandler)
    print(f"Listening on http://{args.host}:{args.port}")
    if args.forward_url:
        print(f"Forwarding enabled -> {args.forward_url} (timeout={args.forward_timeout}s)")
    elif args.auto_discover:
        print("Forwarding enabled via mDNS discovery.")
        print(
            "Discovery config:"
            f" type={args.discover_service_type}"
            f" name~={args.discover_name}"
            f" timeout={args.discover_timeout}s"
        )
        if Zeroconf is None:
            print("Warning: zeroconf dependency missing. Install with: pip install zeroconf")
    else:
        print("Forwarding disabled (set --forward-url or --auto-discover).")
    print("Press Ctrl+C to stop.")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down.")
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()
