#!/usr/bin/env python3
import argparse
import datetime as dt
import json
import threading
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


def discover_forward_url(
    service_type: str,
    timeout_seconds: float,
    name_substring: Optional[str],
) -> Tuple[Optional[str], Optional[str]]:
    if Zeroconf is None or ServiceBrowser is None:
        return None, "python package 'zeroconf' is not installed; run: pip install zeroconf"

    if not service_type.endswith("."):
        service_type = service_type + "."

    found_event = threading.Event()
    result: Dict[str, str] = {}

    class FirstServiceListener(ServiceListener):  # type: ignore[misc, valid-type]
        def _capture(self, zc: Zeroconf, service_name: str) -> None:
            if name_substring and name_substring.lower() not in service_name.lower():
                return
            info = zc.get_service_info(service_type, service_name, timeout=int(timeout_seconds * 1000))
            if info is None:
                return
            addresses = info.parsed_addresses()
            if not addresses:
                return
            ipv4_addresses = [addr for addr in addresses if "." in addr]
            selected_addr = ipv4_addresses[0] if ipv4_addresses else addresses[0]
            result["url"] = f"http://{selected_addr}:{info.port}/message"
            result["service_name"] = service_name
            found_event.set()

        def add_service(self, zc: Zeroconf, discovered_type: str, service_name: str) -> None:
            del discovered_type
            self._capture(zc, service_name)

        def update_service(self, zc: Zeroconf, discovered_type: str, service_name: str) -> None:
            del discovered_type
            self._capture(zc, service_name)

        def remove_service(self, zc: Zeroconf, discovered_type: str, service_name: str) -> None:
            del zc, discovered_type, service_name
            return

    zc = Zeroconf()
    try:
        ServiceBrowser(zc, service_type, FirstServiceListener())
        found_event.wait(timeout_seconds)
    finally:
        zc.close()

    if "url" in result:
        return result["url"], None
    return None, f"no matching service found for {service_type} in {timeout_seconds:.1f}s"


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
        body = self.rfile.read(content_length)

        try:
            payload: Dict[str, Any] = json.loads(body.decode("utf-8") or "{}")
        except json.JSONDecodeError:
            self._set_headers(400)
            self.wfile.write(b'{"ok":false,"error":"invalid json"}')
            return

        print(f"[{now_iso()}] Received message")
        print(json.dumps(payload, indent=2, ensure_ascii=True))
        forward_result = self._forward_to_android(payload)
        if forward_result is not None:
            forwarded_ok, detail = forward_result
            if forwarded_ok:
                print(f"Forwarded to Android: {detail}")
            else:
                print(f"Forward to Android failed: {detail}")
        print("-" * 40, flush=True)

        response = json.dumps(
            {
                "ok": True,
                "received_at": now_iso(),
                "echo": payload,
                "forwarded": (forward_result[0] if forward_result is not None else None),
                "forward_detail": (forward_result[1] if forward_result is not None else None),
            },
            ensure_ascii=True,
        ).encode("utf-8")
        self._set_headers(200)
        self.wfile.write(response)

    def _forward_to_android(self, payload: Dict[str, Any]) -> Optional[Tuple[bool, str]]:
        target_url, discover_error = self._resolve_forward_url()
        if target_url is None:
            if self.auto_discover:
                return False, f"auto-discovery failed: {discover_error or 'unknown error'}"
            return None

        forwarded_payload: Dict[str, Any] = {
            "source": "terminal-host",
            "forwarded_at": now_iso(),
            "message": payload.get("message"),
            "browser_payload": payload,
        }

        ok, detail = self._send_forward_payload(target_url, forwarded_payload)
        if ok:
            return True, f"{target_url} -> {detail}"

        # Auto-discover mode retries once with a fresh discovery in case phone IP changed.
        if self.auto_discover and self.forward_url is None:
            retry_url, retry_error = self._resolve_forward_url(force_refresh=True)
            if retry_url and retry_url != target_url:
                retry_ok, retry_detail = self._send_forward_payload(retry_url, forwarded_payload)
                if retry_ok:
                    return True, f"{retry_url} -> {retry_detail}"
                return False, f"{target_url} -> {detail}; retry {retry_url} -> {retry_detail}"
            if retry_error:
                return False, f"{target_url} -> {detail}; rediscovery failed: {retry_error}"

        return False, f"{target_url} -> {detail}"

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

    def _send_forward_payload(self, url: str, payload: Dict[str, Any]) -> Tuple[bool, str]:
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
                return True, f"HTTP {resp.status} {short_body}"
        except error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            short_body = (body[:160] + "...") if len(body) > 160 else body
            return False, f"HTTP {exc.code} {short_body}"
        except Exception as exc:  # pylint: disable=broad-except
            return False, str(exc)

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
