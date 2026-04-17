from __future__ import annotations

from pathlib import Path

import webview

from .state import ViewerState


class ViewerApi:
    def __init__(self, state: ViewerState):
        self.state = state

    def _build_result_payload(self, result: dict) -> dict:
        summary = result["summary"]
        dashboard_path = Path(result["html_path"])
        return {
            "dashboard_url": self.state.dashboard_url(result),
            "dashboard_html": dashboard_path.read_text(encoding="utf-8"),
            "csv_name": Path(result["csv_path"]).name,
            "range_text": f"{summary['first']} -> {summary['last']}",
            "view": self.state.view,
            "correction": self.state.correction,
        }

    def _rebuild(self, *, csv_path_arg: str | None, view: str, correction: str) -> dict:
        self.state.csv_path_arg = csv_path_arg
        self.state.view = view
        self.state.correction = correction
        result = self.state.build()
        return self._build_result_payload(result)

    def get_initial_state(self) -> dict:
        result = self.state.build()
        return self._build_result_payload(result)

    def reload_latest(self, view: str, correction: str) -> dict:
        return self._rebuild(csv_path_arg=None, view=view, correction=correction)

    def open_csv(self, view: str, correction: str):
        window = webview.active_window() or (webview.windows[0] if webview.windows else None)
        if window is None:
            raise RuntimeError("Viewer window is not ready.")
        file_dialog_open = getattr(getattr(webview, "FileDialog", None), "OPEN", None)
        dialog_kind = file_dialog_open if file_dialog_open is not None else webview.OPEN_DIALOG
        selected = window.create_file_dialog(
            dialog_kind,
            allow_multiple=False,
            file_types=("CSV files (*.csv)",),
        )
        if not selected:
            return None
        return self._rebuild(csv_path_arg=selected[0], view=view, correction=correction)
