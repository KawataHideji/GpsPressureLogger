from __future__ import annotations

from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread
from typing import Callable
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


OSM_TILE_TEMPLATE = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
USER_AGENT = "GpsPressureLoggerViewer/1.0 (+local tile proxy)"
CLIENT_DISCONNECT_ERRORS = (BrokenPipeError, ConnectionAbortedError, ConnectionResetError)


@dataclass
class TileProxyServer:
    cache_dir: Path
    host: str = "127.0.0.1"
    port: int = 0

    def __post_init__(self) -> None:
        self.cache_dir.mkdir(parents=True, exist_ok=True)

        cache_dir = self.cache_dir

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:  # noqa: N802
                parts = self.path.strip("/").split("/")
                if len(parts) != 4 or parts[0] != "tiles" or not parts[3].endswith(".png"):
                    self.send_error(404)
                    return

                _, z_text, x_text, y_png = parts
                y_text = y_png[:-4]
                if not (z_text.isdigit() and x_text.isdigit() and y_text.isdigit()):
                    self.send_error(400)
                    return

                z = int(z_text)
                x = int(x_text)
                y = int(y_text)

                tile_path = cache_dir / str(z) / str(x) / f"{y}.png"
                if tile_path.exists():
                    try:
                        self._serve_file(tile_path)
                    except CLIENT_DISCONNECT_ERRORS:
                        return
                    return

                tile_path.parent.mkdir(parents=True, exist_ok=True)
                tile_url = OSM_TILE_TEMPLATE.format(z=z, x=x, y=y)
                request = Request(tile_url, headers={"User-Agent": USER_AGENT})
                try:
                    with urlopen(request, timeout=15) as response:
                        data = response.read()
                except (HTTPError, URLError, TimeoutError):
                    self.send_error(502)
                    return

                tile_path.write_bytes(data)
                try:
                    self._serve_file(tile_path)
                except CLIENT_DISCONNECT_ERRORS:
                    return

            def log_message(self, format: str, *args) -> None:  # noqa: A003
                return

            def _serve_file(self, tile_path: Path) -> None:
                data = tile_path.read_bytes()
                self.send_response(200)
                self.send_header("Content-Type", "image/png")
                self.send_header("Content-Length", str(len(data)))
                self.send_header("Cache-Control", "public, max-age=86400")
                self.end_headers()
                self.wfile.write(data)

        self._httpd = ThreadingHTTPServer((self.host, self.port), Handler)
        self._thread = Thread(target=self._httpd.serve_forever, daemon=True)
        self._thread.start()

    @property
    def url_template(self) -> str:
        server_host, server_port = self._httpd.server_address
        return f"http://{server_host}:{server_port}/tiles/{{z}}/{{x}}/{{y}}.png"

    def shutdown(self) -> None:
        self._httpd.shutdown()
        self._httpd.server_close()
        self._thread.join(timeout=2)
