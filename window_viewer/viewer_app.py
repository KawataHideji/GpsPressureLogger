from __future__ import annotations

import argparse
from pathlib import Path

import webview

import step3_visualize
from desktop_app.api import ViewerApi
from desktop_app.shell import render_shell_html
from desktop_app.state import ViewerState


DEFAULT_WIDTH = 1480
DEFAULT_HEIGHT = 1020


def parse_args():
    parser = argparse.ArgumentParser(description="Open the GPS dashboard as a desktop app.")
    parser.add_argument("--csv-path", default=None)
    parser.add_argument("--html-output", default=step3_visualize.DEFAULT_HTML)
    parser.add_argument("--view", choices=["latest-session", "full"], default="latest-session")
    parser.add_argument("--session-gap-minutes", type=int, default=step3_visualize.DEFAULT_SESSION_GAP_MINUTES)
    parser.add_argument("--correction", choices=["corrected", "raw"], default="corrected")
    parser.add_argument("--width", type=int, default=DEFAULT_WIDTH)
    parser.add_argument("--height", type=int, default=DEFAULT_HEIGHT)
    return parser.parse_args()


def main():
    args = parse_args()
    state = ViewerState(
        csv_path_arg=args.csv_path,
        html_output=args.html_output,
        view=args.view,
        session_gap_minutes=args.session_gap_minutes,
        correction=args.correction,
    )
    api = ViewerApi(state)
    webview.create_window(
        "GpsPressureLogger Viewer",
        html=render_shell_html(),
        js_api=api,
        width=args.width,
        height=args.height,
        min_size=(1180, 800),
        text_select=True,
    )
    try:
        webview.start()
    finally:
        # webview ループ終了後に tile proxy を必ず止め、ポートとスレッドを解放する。
        state.shutdown()


if __name__ == "__main__":
    main()
