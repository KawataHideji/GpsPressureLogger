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
            "motion_csv_name": Path(summary["motion_source_file"]).name if summary.get("motion_source_file") and summary["motion_source_file"] != "none" else "none",
            "range_text": f"{summary['first']} -> {summary['last']}",
            "view": self.state.view,
            "correction": self.state.correction,
            "date_keys": result.get("available_date_keys", result.get("date_keys", [])),
            "selected_date_key": result.get("initial_date_key"),
        }

    def _rebuild(self, *, csv_path_arg: str | None, motion_csv_path_arg: str | None, view: str, correction: str) -> dict:
        self.state.csv_path_arg = csv_path_arg
        self.state.motion_csv_path_arg = motion_csv_path_arg
        self.state.view = view
        self.state.correction = correction
        result = self.state.build()
        return self._build_result_payload(result)

    def get_initial_state(self) -> dict:
        result = self.state.build()
        return self._build_result_payload(result)

    def reload_latest(self, view: str, correction: str) -> dict:
        return self._rebuild(csv_path_arg=None, motion_csv_path_arg=None, view=view, correction=correction)

    def set_date(self, date_key: str, view: str, correction: str) -> dict:
        self.state.view = view
        self.state.correction = correction
        self.state.selected_date_key = date_key or None
        result = self.state.build()
        return self._build_result_payload(result)

    def open_csv_file(self) -> dict | None:
        paths = webview.windows[0].create_file_dialog(
            webview.OPEN_DIALOG,
            file_types=("CSV Files (*.csv)", "All Files (*.*)"),
        )
        if not paths:
            return None
        self.state.csv_path_arg = paths[0]
        self.state.motion_csv_path_arg = None
        self.state.selected_date_key = None
        result = self.state.build()
        return self._build_result_payload(result)

    def open_motion_csv_file(self) -> dict | None:
        paths = webview.windows[0].create_file_dialog(
            webview.OPEN_DIALOG,
            file_types=("CSV Files (*.csv)", "All Files (*.*)"),
        )
        if not paths:
            return None
        self.state.motion_csv_path_arg = paths[0]
        result = self.state.build()
        return self._build_result_payload(result)
