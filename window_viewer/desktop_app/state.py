from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from time import time_ns

try:
    from .. import step3_visualize
    from .tile_proxy import TileProxyServer
except ImportError:
    import step3_visualize
    from desktop_app.tile_proxy import TileProxyServer


@dataclass
class ViewerState:
    csv_path_arg: str | None = None
    motion_csv_path_arg: str | None = None
    html_output: str = step3_visualize.DEFAULT_HTML
    view: str = "latest-session"
    session_gap_minutes: int = step3_visualize.DEFAULT_SESSION_GAP_MINUTES
    correction: str = "corrected"
    selected_date_key: str | None = None
    tile_proxy: TileProxyServer | None = None

    def build(self) -> dict:
        html_output = self._next_html_output()
        tile_url_template = self.ensure_tile_proxy().url_template
        return step3_visualize.build_dashboard(
            csv_path_arg=self.csv_path_arg,
            motion_csv_path_arg=self.motion_csv_path_arg,
            html_output=html_output,
            view=self.view,
            session_gap_minutes=self.session_gap_minutes,
            correction=self.correction,
            selected_date_key=self.selected_date_key,
            tile_url_template=tile_url_template,
            summary_only=False,
            open_browser=False,
        )

    def use_latest_csv(self) -> dict:
        self.csv_path_arg = None
        self.motion_csv_path_arg = None
        self.selected_date_key = None
        return self.build()

    def use_csv_path(self, csv_path: str) -> dict:
        self.csv_path_arg = csv_path
        self.motion_csv_path_arg = None
        self.selected_date_key = None
        return self.build()

    def use_motion_csv_path(self, motion_csv_path: str) -> dict:
        self.motion_csv_path_arg = motion_csv_path
        return self.build()

    def _next_html_output(self) -> str:
        base_path = Path(self.html_output)
        cache_dir = base_path.parent / "desktop_cache"
        cache_dir.mkdir(parents=True, exist_ok=True)
        return str(cache_dir / f"{base_path.stem}_{time_ns()}.html")

    def dashboard_url(self, result: dict) -> str:
        return Path(result["html_path"]).as_uri()

    def ensure_tile_proxy(self) -> TileProxyServer:
        if self.tile_proxy is None:
            base_path = Path(self.html_output)
            cache_dir = base_path.parent / "desktop_cache" / "tile_cache"
            self.tile_proxy = TileProxyServer(cache_dir=cache_dir)
        return self.tile_proxy
