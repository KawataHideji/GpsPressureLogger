from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from time import time_ns

try:
    from .. import step3_visualize
except ImportError:
    import step3_visualize


@dataclass
class ViewerState:
    csv_path_arg: str | None = None
    html_output: str = step3_visualize.DEFAULT_HTML
    view: str = "latest-session"
    session_gap_minutes: int = step3_visualize.DEFAULT_SESSION_GAP_MINUTES
    correction: str = "corrected"

    def build(self) -> dict:
        html_output = self._next_html_output()
        return step3_visualize.build_dashboard(
            csv_path_arg=self.csv_path_arg,
            html_output=html_output,
            view=self.view,
            session_gap_minutes=self.session_gap_minutes,
            correction=self.correction,
            summary_only=False,
            open_browser=False,
        )

    def use_latest_csv(self) -> dict:
        self.csv_path_arg = None
        return self.build()

    def use_csv_path(self, csv_path: str) -> dict:
        self.csv_path_arg = csv_path
        return self.build()

    def _next_html_output(self) -> str:
        output_path = Path(self.html_output)
        cache_dir = output_path.parent / "desktop_cache"
        cache_dir.mkdir(parents=True, exist_ok=True)
        return str(cache_dir / f"{output_path.stem}_{time_ns()}.html")

    @staticmethod
    def dashboard_url(result: dict) -> str:
        return Path(result["html_path"]).resolve().as_uri()
