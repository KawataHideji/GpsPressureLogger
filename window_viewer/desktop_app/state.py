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
    source_cache_key: tuple | None = None
    source_cache_data: dict | None = None

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
            source_data=self._get_source_data(),
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

    def _file_signature(self, path: Path | None) -> tuple | None:
        if path is None:
            return None
        stat = path.stat()
        return (str(path.resolve()), stat.st_size, stat.st_mtime_ns)

    def _source_key(self) -> tuple:
        csv_path = step3_visualize.resolve_csv_path(self.csv_path_arg)
        if not csv_path.exists():
            raise FileNotFoundError(f"CSV not found: {csv_path}")
        motion_path = step3_visualize.resolve_motion_csv_path(csv_path, self.motion_csv_path_arg)
        return (
            self._file_signature(csv_path),
            self._file_signature(motion_path) if motion_path and motion_path.exists() else None,
        )

    def _get_source_data(self) -> dict:
        key = self._source_key()
        if key != self.source_cache_key or self.source_cache_data is None:
            self.source_cache_data = step3_visualize.load_dashboard_sources(
                self.csv_path_arg,
                self.motion_csv_path_arg,
            )
            self.source_cache_key = key
        return self.source_cache_data
