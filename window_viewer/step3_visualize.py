import argparse
import csv
import json
import os
import re
import webbrowser
from datetime import datetime, timedelta, timezone
from pathlib import Path
from math import acos, asin, cos, degrees, hypot, radians, sin, sqrt

JST = timezone(timedelta(hours=9))
SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_DIR = SCRIPT_DIR.parent
DEFAULT_CSV = "gps_pressure_full_backup_converted.csv"
DEFAULT_HTML = str(SCRIPT_DIR / "merged_dashboard.html")
DEFAULT_BACKUP_GLOB = "gps_pressure_full_backup_*.csv"
DEFAULT_CONVERTED_GLOB = "gps_pressure_full_backup_converted*.csv"
DEFAULT_ANDROID_DIR = Path(r"C:\MyDrive\android")
DEFAULT_CONVERTER_DIR = PROJECT_DIR / "log_converter"
MOTION_BACKUP_PREFIX = "gps_pressure_motion_metrics_backup_"
MOTION_EVENT_BACKUP_PREFIX = "gps_pressure_motion_events_backup_"
MOTION_BACKUP_PREFIX_LEGACY = "gps_pressure_motion_metrics_"
MOTION_DAILY_PREFIX = "motion_metrics_"
MOTION_EVENT_DAILY_PREFIX = "motion_events_"
EVENT_PATTERN = re.compile(r"^# EVENT\s+(\d+)\s+(.*)$")
MODE_CONFIRMED_PATTERN = re.compile(r"^MODE_CONFIRMED:\s+([A-Z_]+)\s+->\s+([A-Z_]+)")
DEFAULT_SESSION_GAP_MINUTES = 20
GRAPH_INTERVAL_MS = 60_000
MAX_REASONABLE_SPEED_KMH = 300.0
MAX_REASONABLE_ACCURACY_M = 100.0
SINGLE_POINT_SPIKE_MAX_DURATION_MS = 5 * 60_000
SINGLE_POINT_SPIKE_DEVIATION_SPEED_KMH = 100.0
GPS_GAP_INTERPOLATION_MIN_MS = 60_000
GPS_GAP_INTERPOLATION_MAX_MS = 10 * 60_000
GPS_GAP_INTERPOLATION_INTERVAL_MS = 30_000
GPS_FREEZE_SAME_POINT_RADIUS_M = 3.0
GPS_FREEZE_MIN_DURATION_MS = 2 * 60_000
GPS_FREEZE_RECOVERY_MIN_JUMP_M = 200.0
GPS_FREEZE_RECOVERY_MIN_SPEED_KMH = 300.0
TRANSIENT_DETOUR_MAX_DURATION_MS = 8 * 60_000
TRANSIENT_DETOUR_MIN_NEIGHBOR_DISTANCE_M = 120.0
TRANSIENT_DETOUR_MIN_LATERAL_DISTANCE_M = 120.0
TRANSIENT_DETOUR_MIN_EXTRA_DISTANCE_M = 250.0
TRANSIENT_DETOUR_MIN_EXTRA_RATIO = 1.8
TRANSIENT_DETOUR_MIN_TURN_ANGLE_DEG = 120.0
CLUSTER_BOUNDARY_MAX_DURATION_MS = 2 * 60_000
CLUSTER_BOUNDARY_MIN_DISTANCE_M = 1_000.0
CLUSTER_BOUNDARY_MIN_SPEED_KMH = 160.0
ISOLATED_CLUSTER_MAX_DURATION_MS = 4 * 60_000
ISOLATED_CLUSTER_MAX_POINTS = 24
PRESSURE_OUTLIER_THRESHOLD = 10.0
ALTITUDE_OUTLIER_THRESHOLD = 300.0
VALID_MODES = {"DEVICE_STILL", "STOPPED", "WALKING", "VEHICLE", "UNKNOWN"}
STATE_LABEL_COLOR_STK1 = "#555566"
STATE_LABEL_COLOR_STK2 = "#BB6600"
STATE_LABEL_COLOR_STK4 = "#CC2200"
STATE_LABEL_COLOR_W1 = "#1E66E8"
STATE_LABEL_COLOR_W2 = "#7799BB"
STATE_LABEL_COLOR_STAY = "#1E9944"
STATE_LABEL_COLOR_CMOVE = "#8822CC"
STATE_LABEL_COLOR_TRK_ON = "#D84315"
STATE_LABEL_COLOR_TRK_OFF = "#607D8B"


def normalize_mode(value):
    if value is None:
        return None
    text = str(value).strip().upper()
    return text if text in VALID_MODES else None
def parse_args():
    parser = argparse.ArgumentParser(description="Visualize a standard backup CSV.")
    parser.add_argument("--csv-path", default=None)
    parser.add_argument("--motion-csv-path", default=None)
    parser.add_argument("--html-output", default=DEFAULT_HTML)
    parser.add_argument("--no-browser", action="store_true")
    parser.add_argument("--summary-only", action="store_true")
    parser.add_argument("--view", choices=["latest-session", "full"], default="latest-session")
    parser.add_argument("--session-gap-minutes", type=int, default=DEFAULT_SESSION_GAP_MINUTES)
    parser.add_argument("--correction", choices=["corrected", "raw"], default="corrected")
    return parser.parse_args()


def resolve_csv_path(csv_path_arg: str | None) -> Path:
    if csv_path_arg:
        return Path(csv_path_arg)

    if DEFAULT_ANDROID_DIR.exists():
        backups = sorted(
            DEFAULT_ANDROID_DIR.glob(DEFAULT_BACKUP_GLOB),
            key=lambda path: path.stat().st_mtime,
            reverse=True,
        )
        if backups:
            return backups[0]

    local_default = SCRIPT_DIR / DEFAULT_CSV
    if local_default.exists():
        return local_default

    if DEFAULT_CONVERTER_DIR.exists():
        converted = sorted(
            DEFAULT_CONVERTER_DIR.glob(DEFAULT_CONVERTED_GLOB),
            key=lambda path: path.stat().st_mtime,
            reverse=True,
        )
        if converted:
            return converted[0]

    return local_default


def to_float(value: str):
    if value == "" or value.lower() == "null":
        return None
    return float(value)


def to_int(value: str):
    if value == "" or value.lower() == "null":
        return None
    return int(float(value))


def parse_timestamp_value(value: str):
    if value == "":
        return None
    try:
        return int(float(value))
    except ValueError:
        pass

    text = value.strip()
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y/%m/%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S"):
        try:
            dt = datetime.strptime(text, fmt).replace(tzinfo=JST)
            return int(dt.timestamp() * 1000)
        except ValueError:
            continue
    raise ValueError(f"Unsupported timestamp format: {value}")


def get_header_value(values, index_by_name, aliases):
    for alias in aliases:
        index = index_by_name.get(alias)
        if index is not None and index < len(values):
            return values[index]
    return ""


def get_header_bool(values, index_by_name, aliases):
    value = get_header_value(values, index_by_name, aliases).strip().lower()
    if value in ("1", "true", "yes", "y"):
        return True
    if value in ("0", "false", "no", "n"):
        return False
    return None


def timestamp_to_jst_text(timestamp_ms: int) -> str:
    return datetime.fromtimestamp(timestamp_ms / 1000, tz=timezone.utc).astimezone(JST).strftime("%Y-%m-%d %H:%M:%S")


def timestamp_to_jst_date_key(timestamp_ms: int) -> str:
    return datetime.fromtimestamp(timestamp_ms / 1000, tz=timezone.utc).astimezone(JST).strftime("%Y-%m-%d")


def load_backup(csv_path: Path):
    events = []
    rows = []

    with csv_path.open("r", encoding="utf-8", newline="") as handle:
        header = None
        index_by_name = {}
        for raw_line in handle:
            line = raw_line.strip()
            if not line:
                continue
            match = EVENT_PATTERN.match(line)
            if match:
                timestamp_ms = int(match.group(1))
                events.append({
                    "timestamp": timestamp_ms,
                    "dt": timestamp_to_jst_text(timestamp_ms),
                    "message": match.group(2),
                })
                continue
            if line.startswith("#"):
                continue
            values = next(csv.reader([line]))
            if header is None:
                header = values
                index_by_name = {name.strip(): idx for idx, name in enumerate(header)}
                continue
            if len(values) < 2:
                continue
            timestamp_text = get_header_value(values, index_by_name, ["Timestamp", "DateTime(+0900)", "TimeStr"])
            timestamp_ms = parse_timestamp_value(timestamp_text)
            if timestamp_ms is None:
                continue
            row = {
                "Timestamp": timestamp_ms,
                "dt": timestamp_to_jst_text(timestamp_ms),
                "Lat": to_float(get_header_value(values, index_by_name, ["Lat", "Latitude"])),
                "Lon": to_float(get_header_value(values, index_by_name, ["Lon", "Longitude"])),
                "Alt": to_float(get_header_value(values, index_by_name, ["Alt", "Elevation(m)"])),
                "PresRaw": to_float(get_header_value(values, index_by_name, ["PresRaw", "Pa"])),
                "PresQnh": to_float(get_header_value(values, index_by_name, ["PresQnh", "MSLP(Pa)"])),
                "GpsAccuracy": to_float(get_header_value(values, index_by_name, ["GpsAccuracy", "Accuracy", "Accuracy(m)"])),
                "StepsDelta": 0,
            }
            if row["PresRaw"] is not None and row["PresRaw"] > 2000:
                row["PresRaw"] = row["PresRaw"] / 100.0
            if row["PresQnh"] is not None and row["PresQnh"] > 2000:
                row["PresQnh"] = row["PresQnh"] / 100.0
            steps_delta_text = get_header_value(values, index_by_name, ["StepsDelta"])
            if steps_delta_text != "":
                row["StepsDelta"] = to_int(steps_delta_text) or 0
            else:
                raw_steps = to_int(get_header_value(values, index_by_name, ["Steps"]))
                row["_RawSteps"] = raw_steps
            row["GpsValid"] = (
                row["Lat"] is not None
                and row["Lon"] is not None
                and not (row["Lat"] == 0 and row["Lon"] == 0)
            )
            rows.append(row)

    rows.sort(key=lambda row: row["Timestamp"])
    previous_raw_steps = None
    for row in rows:
        raw_steps = row.pop("_RawSteps", None) if "_RawSteps" in row else None
        if raw_steps is not None:
            if previous_raw_steps is None:
                row["StepsDelta"] = 0
            else:
                row["StepsDelta"] = max(0, raw_steps - previous_raw_steps)
            previous_raw_steps = raw_steps
    recompute_steps_cumulative(rows)

    return rows, events


def clone_rows(rows):
    return [dict(row) for row in rows]


def resolve_motion_csv_path(csv_path: Path, motion_csv_path_arg: str | None = None) -> Path | None:
    if motion_csv_path_arg:
        candidate = Path(motion_csv_path_arg)
        return candidate if candidate.exists() else candidate

    def usable_motion_file(path: Path) -> bool:
        return path.exists() and path.resolve() != csv_path.resolve() and path.stat().st_size > 0

    parent = csv_path.parent
    stem = csv_path.stem
    candidates = []
    search_dirs = [parent, parent / "metrics"]

    backup_match = re.match(r"gps_pressure_full_backup_(\d{8}_\d{6})$", stem)
    if backup_match:
        for search_dir in search_dirs:
            candidates.append(search_dir / f"{MOTION_EVENT_BACKUP_PREFIX}{backup_match.group(1)}.csv")
            candidates.append(search_dir / f"{MOTION_BACKUP_PREFIX}{backup_match.group(1)}.csv")
            candidates.append(search_dir / f"{MOTION_BACKUP_PREFIX_LEGACY}{backup_match.group(1)}.csv")

    daily_match = re.match(r"(?:gps_log|gps_pressure)_(\d{8})$", stem)
    if daily_match:
        candidates.extend(search_dir / f"{MOTION_EVENT_DAILY_PREFIX}{daily_match.group(1)}.csv" for search_dir in search_dirs)
        candidates.extend(search_dir / f"{MOTION_DAILY_PREFIX}{daily_match.group(1)}.csv" for search_dir in search_dirs)

    date_match = re.search(r"(\d{8})", stem)
    if date_match:
        date_text = date_match.group(1)
        for search_dir in search_dirs:
            if search_dir.exists():
                candidates.extend(sorted(search_dir.glob(f"{MOTION_EVENT_BACKUP_PREFIX}{date_text}_*.csv"), reverse=True))
                candidates.extend(sorted(search_dir.glob(f"{MOTION_BACKUP_PREFIX}{date_text}_*.csv"), reverse=True))
                candidates.extend(sorted(search_dir.glob(f"{MOTION_BACKUP_PREFIX_LEGACY}{date_text}_*.csv"), reverse=True))
            candidates.append(search_dir / f"{MOTION_EVENT_DAILY_PREFIX}{date_text}.csv")
            candidates.append(search_dir / f"{MOTION_DAILY_PREFIX}{date_text}.csv")

    for candidate in candidates:
        if usable_motion_file(candidate):
            return candidate

    latest_candidates = []
    for search_dir in search_dirs:
        if not search_dir.exists():
            continue
        latest_candidates.extend(search_dir.glob(f"{MOTION_EVENT_BACKUP_PREFIX}*.csv"))
        latest_candidates.extend(search_dir.glob(f"{MOTION_BACKUP_PREFIX}*.csv"))
        latest_candidates.extend(search_dir.glob(f"{MOTION_BACKUP_PREFIX_LEGACY}*.csv"))
    latest_candidates = sorted(
        (path for path in latest_candidates if usable_motion_file(path)),
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )
    if latest_candidates:
        return latest_candidates[0]
    return None


def load_motion_samples(motion_csv_path: Path | None):
    if motion_csv_path is None or not motion_csv_path.exists():
        return [], None

    samples = []
    with motion_csv_path.open("r", encoding="utf-8", newline="") as handle:
        header = None
        index_by_name = {}
        for raw_line in handle:
            line = raw_line.strip()
            if not line or line.startswith("#"):
                continue
            values = next(csv.reader([line]))
            if header is None:
                header = values
                index_by_name = {name.strip(): idx for idx, name in enumerate(header)}
                continue
            timestamp_text = get_header_value(values, index_by_name, ["Timestamp", "DateTime(+0900)", "TimeStr"])
            timestamp_ms = parse_timestamp_value(timestamp_text)
            if timestamp_ms is None:
                continue
            samples.append({
                "Timestamp": timestamp_ms,
                "StepDelta3s": to_int(get_header_value(values, index_by_name, ["StepDelta3s"])),
                "ConfirmedMode": normalize_mode(get_header_value(values, index_by_name, ["ConfirmedMode"])),
                "ConstantRegionKind": (get_header_value(values, index_by_name, ["ConstantRegionKind"]) or None),
                "ConstantRegionSpeedKmh": to_float(get_header_value(values, index_by_name, ["ConstantRegionSpeedKmh"])),
                "ConstantRegionStartLat": to_float(get_header_value(values, index_by_name, ["ConstantRegionStartLat"])),
                "ConstantRegionStartLon": to_float(get_header_value(values, index_by_name, ["ConstantRegionStartLon"])),
                "ConstantRegionEndLat": to_float(get_header_value(values, index_by_name, ["ConstantRegionEndLat"])),
                "ConstantRegionEndLon": to_float(get_header_value(values, index_by_name, ["ConstantRegionEndLon"])),
                "ConstantRegionStayLat": to_float(get_header_value(values, index_by_name, ["ConstantRegionStayLat"])),
                "ConstantRegionStayLon": to_float(get_header_value(values, index_by_name, ["ConstantRegionStayLon"])),
                "StepDeltaWindow": to_int(get_header_value(values, index_by_name, ["StepDeltaWindow"])),
                "GpsIntervalMs": to_int(get_header_value(values, index_by_name, ["GpsIntervalMs"])),
                "GpsImmediate": get_header_bool(values, index_by_name, ["GpsImmediate"]),
                "KStatus": get_header_value(values, index_by_name, ["KStatus"]) or None,
                "StKAvg": to_float(get_header_value(values, index_by_name, ["StKAvg", "KAvg"])),
                "StKRatio": to_float(get_header_value(values, index_by_name, ["StKRatio", "KDirectionalityRatio"])),
                "KAccelSource": get_header_value(values, index_by_name, ["KAccelSource"]) or None,
                "TrKStatus": get_header_value(values, index_by_name, ["TrKStatus"]) or None,
                "TrKRawStatus": get_header_value(values, index_by_name, ["TrKRawStatus"]) or None,
                "TrKAvg": to_float(get_header_value(values, index_by_name, ["TrKAvg"])),
                "TrKRatio": to_float(get_header_value(values, index_by_name, ["TrKRatio", "TrKDirectionalityRatio"])),
                "WStatus": get_header_value(values, index_by_name, ["WStatus"]) or None,
            })
    samples.sort(key=lambda sample: sample["Timestamp"])
    return samples, motion_csv_path


def assign_display_modes(rows, events, motion_samples=None):
    # Android GpsUtil.kt DISPLAY_MOTION_PARAMS (= MotionStateParams デフォルト値) に準拠
    WALKING_SPEED_WINDOW_MS = 9_000
    WALKING_VEHICLE_SPEED_THRESHOLD_KMH = 10.0
    WALKING_STEP_LENGTH_M = 0.60
    WALKING_GPS_STEP_MISMATCH_THRESHOLD_KMH = 5.0

    # 初期化: すべての行に基本フィールドをセットする
    for row in rows:
        row["DisplayMode"] = "UNKNOWN"
        row["ConstantRegionKind"] = None
        row["ConstantRegionStartLat"] = None
        row["ConstantRegionStartLon"] = None
        row["ConstantRegionEndLat"] = None
        row["ConstantRegionEndLon"] = None
        row["ConstantRegionStayLat"] = None
        row["ConstantRegionStayLon"] = None

    motion_samples = motion_samples or []
    if not motion_samples:
        # Android アプリは MotionSample が無い場合に UNKNOWN のままにする
        return rows

    # GPS 速度計算用に位置情報のある行を昇順に抽出（Android locatedEntries 相当）
    located_rows = sorted(
        [r for r in rows if r.get("Lat") is not None and r.get("Lon") is not None],
        key=lambda r: r["Timestamp"]
    )

    def gps_speed_kmh_at(timestamp_ms):
        """Android gpsSpeedKmhAt の Python 版。
        [timestamp - window, timestamp) 内の最初と最後の GPS 点から変位速度を計算する。"""
        if len(located_rows) < 2:
            return None
        t_start = timestamp_ms - WALKING_SPEED_WINDOW_MS
        # lower_bound(t_start)
        lo, hi = 0, len(located_rows)
        while lo < hi:
            mid = (lo + hi) // 2
            if located_rows[mid]["Timestamp"] < t_start:
                lo = mid + 1
            else:
                hi = mid
        start_idx = lo
        # upper_bound(timestamp_ms)
        lo, hi = 0, len(located_rows)
        while lo < hi:
            mid = (lo + hi) // 2
            if located_rows[mid]["Timestamp"] <= timestamp_ms:
                lo = mid + 1
            else:
                hi = mid
        end_exclusive = lo
        if end_exclusive - start_idx < 2:
            return None
        first = located_rows[start_idx]
        last = located_rows[end_exclusive - 1]
        dt_ms = last["Timestamp"] - first["Timestamp"]
        if dt_ms <= 0:
            return None
        dist_m = haversine_m(first["Lat"], first["Lon"], last["Lat"], last["Lon"])
        return dist_m / (dt_ms / 1000.0) * 3.6

    def display_step_speed_kmh(sample):
        """Android displayStepSpeedKmh の Python 版。
        stepDeltaWindow × 歩幅 / 窓時間（秒）× 3.6 で km/h に変換。"""
        steps = sample.get("StepDeltaWindow")
        if steps is None:
            return None
        steps = max(0, int(steps))
        window_sec = WALKING_SPEED_WINDOW_MS / 1000.0
        return steps * WALKING_STEP_LENGTH_M / window_sec * 3.6

    def resolve_display_walking_mode(sample, region_kind):
        """Android resolveDisplayWalkingMode の Python 版。
        GPS速度 or 定速領域速度で WALKING を VEHICLE に上書きするかを判定する。"""
        gps_speed = gps_speed_kmh_at(sample["Timestamp"])
        # Android withConstantMoveFallback: GPS 速度が無ければ定速領域速度をフォールバックに使う
        if gps_speed is None and region_kind == "CONSTANT_MOVE":
            speed_raw = sample.get("ConstantRegionSpeedKmh")
            if speed_raw is not None:
                gps_speed = float(speed_raw)
        step_speed = display_step_speed_kmh(sample)
        if gps_speed is not None and gps_speed >= WALKING_VEHICLE_SPEED_THRESHOLD_KMH:
            return "VEHICLE"
        if (gps_speed is not None and step_speed is not None
                and abs(gps_speed - step_speed) >= WALKING_GPS_STEP_MISMATCH_THRESHOLD_KMH):
            return "VEHICLE"
        return "WALKING"

    def parse_region_kind(raw):
        """ConstantRegionKind を正規化し NONE は null 扱い（Android parseConstantRegionKind 相当）。"""
        if not raw:
            return None
        normalized = str(raw).strip().upper()
        return None if normalized in ("NONE", "NULL", "") else normalized

    def event_int_metric(message, name):
        match = re.search(rf"\b{re.escape(name)}=(-?\d+)", message)
        if not match:
            return None
        try:
            return int(match.group(1))
        except ValueError:
            return None

    def provisional_mode_from_new_state(sample, region_kind):
        """Android provisionalModeFromNewState の Python 版（allowOpenRegionFallback=True）。
        lastConfirmedIndex 以降の未確定サンプルから暫定モードを推定する。"""
        k_status = sample.get("KStatus")
        w_status = sample.get("WStatus")
        if k_status in ("STK4", "K4"):
            return "VEHICLE"
        if w_status == "W1":
            return resolve_display_walking_mode(sample, region_kind)
        if region_kind == "CONSTANT_MOVE":
            return "VEHICLE"
        if region_kind == "STAY":
            return "DEVICE_STILL" if k_status in ("STK1", "K1") else "STOPPED"
        # allowOpenRegionFallback: stK が有効かつ W2 → 暫定停止として扱う
        if k_status is not None and w_status == "W2":
            return "DEVICE_STILL" if k_status in ("STK1", "K1") else "STOPPED"
        return None

    # lastConfirmedIndex: confirmedMode が設定されている最後のサンプルのインデックス
    # Android inferModeStatesFromConfirmedCache の lastConfirmedIndex 相当
    last_confirmed_index = -1
    for i, s in enumerate(motion_samples):
        if s.get("ConfirmedMode") is not None:
            last_confirmed_index = i

    # ── ModeState リストを構築 ────────────────────────────────────────────────
    # Android inferModeStatesFromConfirmedCache に完全準拠:
    #   1. confirmedMode があるサンプル → displayModeFromConfirmedSample（WALKING のみ再評価）
    #   2. lastConfirmedIndex 以降の unconfirmed サンプル → provisionalModeFromNewState
    mode_states = []
    for i, sample in enumerate(motion_samples):
        region_kind = parse_region_kind(sample.get("ConstantRegionKind"))
        confirmed_mode = sample.get("ConfirmedMode")

        if confirmed_mode is not None:
            # Android displayModeFromConfirmedSample: WALKING のみ GPS 速度で再評価
            if confirmed_mode == "WALKING":
                display_mode = resolve_display_walking_mode(sample, region_kind)
            else:
                display_mode = confirmed_mode
            mode_states.append({
                "timestamp": sample["Timestamp"],
                "mode": display_mode,
                "recheck_walking": False,
                "region_kind": region_kind,
                "start_lat": sample.get("ConstantRegionStartLat"),
                "start_lon": sample.get("ConstantRegionStartLon"),
                "end_lat": sample.get("ConstantRegionEndLat"),
                "end_lon": sample.get("ConstantRegionEndLon"),
                "stay_lat": sample.get("ConstantRegionStayLat"),
                "stay_lon": sample.get("ConstantRegionStayLon"),
            })
        elif i > last_confirmed_index:
            # lastConfirmedIndex 以降の未確定区間のみ暫定モードを推定
            prov = provisional_mode_from_new_state(sample, region_kind)
            if prov is not None:
                mode_states.append({
                    "timestamp": sample["Timestamp"],
                    "mode": prov,
                    "recheck_walking": False,
                    "region_kind": region_kind,
                    "start_lat": sample.get("ConstantRegionStartLat"),
                    "start_lon": sample.get("ConstantRegionStartLon"),
                    "end_lat": sample.get("ConstantRegionEndLat"),
                    "end_lon": sample.get("ConstantRegionEndLon"),
                    "stay_lat": sample.get("ConstantRegionStayLat"),
                    "stay_lon": sample.get("ConstantRegionStayLon"),
                })

    motion_tail_timestamp = max((sample["Timestamp"] for sample in motion_samples), default=None)
    for event in events:
        if motion_tail_timestamp is not None and event["timestamp"] <= motion_tail_timestamp:
            continue
        match = MODE_CONFIRMED_PATTERN.match(event["message"])
        if not match:
            continue
        confirmed_mode = normalize_mode(match.group(2)) or "UNKNOWN"
        display_mode = confirmed_mode
        if confirmed_mode == "WALKING":
            display_mode = resolve_display_walking_mode({
                "Timestamp": event["timestamp"],
                "StepDeltaWindow": event_int_metric(event["message"], "stepDelta9s"),
            }, None)
        mode_states.append({
            "timestamp": event["timestamp"],
            "mode": display_mode,
            "recheck_walking": confirmed_mode == "WALKING",
            "region_kind": None,
            "start_lat": None,
            "start_lon": None,
            "end_lat": None,
            "end_lon": None,
            "stay_lat": None,
            "stay_lon": None,
        })
    mode_states.sort(key=lambda state: state["timestamp"])

    # ── ModeState を rows にキャリーフォワードで適用 ────────────────────────────
    # Android buildDisplayPoints の while ループキャリーフォワード相当
    state_index = 0
    current_mode = "UNKNOWN"
    current_region_kind = None
    current_start_lat = None
    current_start_lon = None
    current_end_lat = None
    current_end_lon = None
    current_stay_lat = None
    current_stay_lon = None
    current_recheck_walking = False

    for row in rows:
        while state_index < len(mode_states) and mode_states[state_index]["timestamp"] <= row["Timestamp"]:
            st = mode_states[state_index]
            current_mode = st["mode"]
            current_recheck_walking = st.get("recheck_walking", False)
            current_region_kind = st["region_kind"]
            current_start_lat = st["start_lat"]
            current_start_lon = st["start_lon"]
            current_end_lat = st["end_lat"]
            current_end_lon = st["end_lon"]
            current_stay_lat = st["stay_lat"]
            current_stay_lon = st["stay_lon"]
            state_index += 1
        if current_mode == "WALKING" and current_recheck_walking:
            row["DisplayMode"] = resolve_display_walking_mode({
                "Timestamp": row["Timestamp"],
                "StepDeltaWindow": None,
            }, current_region_kind)
        else:
            row["DisplayMode"] = current_mode
        row["ConstantRegionKind"] = current_region_kind
        row["ConstantRegionStartLat"] = current_start_lat
        row["ConstantRegionStartLon"] = current_start_lon
        row["ConstantRegionEndLat"] = current_end_lat
        row["ConstantRegionEndLon"] = current_end_lon
        row["ConstantRegionStayLat"] = current_stay_lat
        row["ConstantRegionStayLon"] = current_stay_lon

    return rows


def recompute_steps_cumulative(rows):
    cumulative = 0
    current_day_start = None
    for row in rows:
        day_start = get_logging_start(row["Timestamp"])
        if current_day_start != day_start:
            current_day_start = day_start
            cumulative = 0
        cumulative += row["StepsDelta"]
        row["StepsCumulative"] = cumulative
    return rows


def get_logging_start(timestamp_ms: int) -> int:
    dt = datetime.fromtimestamp(timestamp_ms / 1000, tz=JST)
    if dt.hour < 3:
        dt = dt - timedelta(days=1)
    return int(dt.replace(hour=3, minute=0, second=0, microsecond=0).timestamp() * 1000)


def graph_filter_outliers(rows):
    if len(rows) <= 2:
        return recompute_steps_cumulative(clone_rows(rows))
    result = []
    for index, current in enumerate(rows):
        current_pressure = current["PresRaw"]
        previous_pressure = rows[index - 1]["PresRaw"] if index > 0 else current_pressure
        next_pressure = rows[index + 1]["PresRaw"] if index < len(rows) - 1 else current_pressure
        current_alt = current["Alt"]
        previous_alt = rows[index - 1]["Alt"] if index > 0 else current_alt
        next_alt = rows[index + 1]["Alt"] if index < len(rows) - 1 else current_alt
        pressure_outlier = (
            current_pressure is not None
            and previous_pressure is not None
            and next_pressure is not None
            and abs(current_pressure - previous_pressure) > PRESSURE_OUTLIER_THRESHOLD
            and abs(current_pressure - next_pressure) > PRESSURE_OUTLIER_THRESHOLD
        )
        altitude_outlier = (
            current_alt is not None
            and previous_alt is not None
            and next_alt is not None
            and abs(current_alt - previous_alt) > ALTITUDE_OUTLIER_THRESHOLD
            and abs(current_alt - next_alt) > ALTITUDE_OUTLIER_THRESHOLD
        )
        if not pressure_outlier and not altitude_outlier:
            result.append(dict(current))
    return recompute_steps_cumulative(result)


def create_interpolated_series(times, values, interval_ms):
    if len(times) < 2:
        return times[:], values[:]
    start_time = times[0]
    end_time = times[-1]
    count = int((end_time - start_time) / interval_ms) + 1
    if count <= 1:
        return times[:], values[:]
    new_times = [start_time + index * interval_ms for index in range(count)]
    new_values = [interpolate_linear(times, values, target_time) for target_time in new_times]
    return new_times, new_values


def create_step_series(rows, target_times):
    if not rows:
        return [None] * len(target_times)
    resolved_rows = sorted(rows, key=lambda row: row["Timestamp"])
    steps = []
    row_index = 0
    cumulative_steps = 0
    current_day_start = get_logging_start(target_times[0]) if target_times else None
    has_accepted_row = False
    for target_time in target_times:
        target_day_start = get_logging_start(target_time)
        if target_day_start != current_day_start:
            current_day_start = target_day_start
            cumulative_steps = 0
        while row_index < len(resolved_rows) and resolved_rows[row_index]["Timestamp"] <= target_time:
            row = resolved_rows[row_index]
            row_day_start = get_logging_start(row["Timestamp"])
            if row_day_start != current_day_start:
                current_day_start = row_day_start
                cumulative_steps = 0
            cumulative_steps += max(0, row["StepsDelta"])
            has_accepted_row = True
            row_index += 1
        steps.append(cumulative_steps if has_accepted_row or target_time >= resolved_rows[0]["Timestamp"] else None)
    return steps


def interpolate_linear(x_values, y_values, x_target):
    if x_target <= x_values[0]:
        return y_values[0]
    if x_target >= x_values[-1]:
        return y_values[-1]
    low = 0
    high = len(x_values) - 1
    while high - low > 1:
        mid = (low + high) // 2
        if x_values[mid] > x_target:
            high = mid
        else:
            low = mid
    x0 = x_values[low]
    x1 = x_values[low + 1]
    y0 = y_values[low]
    y1 = y_values[low + 1]
    if x1 == x0:
        return y0
    return y0 + (y1 - y0) * (x_target - x0) / (x1 - x0)


def moving_average(values, factor):
    count = len(values)
    if count < 3:
        return values[:]
    half_window = min(100, max(2, count // factor))
    result = []
    for index in range(count):
        start = max(0, index - half_window)
        end = min(count - 1, index + half_window)
        finite_values = [value for value in values[start:end + 1] if value is not None]
        result.append(sum(finite_values) / len(finite_values) if finite_values else None)
    return result


def create_metric_series(rows, target_times, key):
    points = [(row["Timestamp"], row[key]) for row in rows if row[key] is not None]
    if len(points) < 2:
        return [None] * len(target_times)
    point_times = [point[0] for point in points]
    point_values = [point[1] for point in points]
    return [interpolate_linear(point_times, point_values, target_time) for target_time in target_times]


def create_step_mode_series(target_times, rows):
    timeline = [
        (row["Timestamp"], row.get("DisplayMode"))
        for row in rows
        if row.get("DisplayMode") is not None
    ]
    if not timeline:
        return ["UNKNOWN"] * len(target_times)

    step_modes = []
    current_mode = "UNKNOWN"
    timeline_index = 0
    for target_time in target_times:
        while timeline_index < len(timeline) and timeline[timeline_index][0] <= target_time:
            current_mode = timeline[timeline_index][1] or "UNKNOWN"
            timeline_index += 1
        step_modes.append(current_mode)
    return step_modes


def get_processed_graph_mode(rows):
    if not rows:
        return build_mode_data(rows)
    filtered = graph_filter_outliers(rows)
    distinct = []
    seen_timestamps = set()
    for row in filtered:
        timestamp = row["Timestamp"]
        if timestamp in seen_timestamps:
            continue
        seen_timestamps.add(timestamp)
        distinct.append(row)
    if len(distinct) < 2:
        return build_mode_data(rows)

    raw_times = [row["Timestamp"] for row in distinct]
    raw_steps = []
    cumulative_steps = 0.0
    current_day_start = get_logging_start(distinct[0]["Timestamp"])
    for row in distinct:
        day_start = get_logging_start(row["Timestamp"])
        if day_start != current_day_start:
            current_day_start = day_start
            cumulative_steps = 0.0
        cumulative_steps += row["StepsDelta"]
        raw_steps.append(cumulative_steps)
    times, _ = create_interpolated_series(raw_times, raw_steps, GRAPH_INTERVAL_MS)
    steps = create_step_series(distinct, times)
    altitude = moving_average(create_metric_series(distinct, times, "Alt"), 40)
    pressure_raw = moving_average(create_metric_series(distinct, times, "PresRaw"), 40)
    pressure_qnh = moving_average(create_metric_series(distinct, times, "PresQnh"), 40)
    labels = [timestamp_to_jst_text(timestamp)[5:] for timestamp in times]
    corrected_map_rows = map_filter_outliers(rows)
    return {
        "labels": labels,
        "timestamps": times,
        "pressureRaw": pressure_raw,
        "pressureQnh": pressure_qnh,
        "altitude": altitude,
        "stepsCumulative": steps,
        "stepModes": create_step_mode_series(times, distinct),
        "gpsPoints": build_gps_points(corrected_map_rows),
        "summary": summarize_mode_rows(
            graph_rows=distinct,
            map_rows=corrected_map_rows,
            graph_point_count=len(times),
        ),
    }


def haversine_m(lat1, lon1, lat2, lon2):
    earth_radius = 6_371_000.0
    d_lat = radians(lat2 - lat1)
    d_lon = radians(lon2 - lon1)
    a = sin(d_lat / 2) ** 2 + cos(radians(lat1)) * cos(radians(lat2)) * sin(d_lon / 2) ** 2
    return earth_radius * 2 * asin(sqrt(a))


def map_filter_outliers(rows):
    location_rows = mark_gps_freeze_breaks([
        dict(row)
        for row in rows
        if row["GpsValid"] and (row.get("GpsAccuracy") is None or row.get("GpsAccuracy") <= MAX_REASONABLE_ACCURACY_M)
    ])
    if len(location_rows) <= 2:
        return location_rows

    pass1 = []
    for entry in location_rows:
        previous = pass1[-1] if pass1 else None
        if previous is not None:
            dt_sec = (entry["Timestamp"] - previous["Timestamp"]) / 1000.0
            if dt_sec > 0:
                speed_kmh = haversine_m(previous["Lat"], previous["Lon"], entry["Lat"], entry["Lon"]) / dt_sec * 3.6
                if speed_kmh > MAX_REASONABLE_SPEED_KMH and not entry.get("GpsGapBreak"):
                    continue
        pass1.append(entry)

    return remove_isolated_jump_clusters(remove_transient_detours(remove_single_point_spikes(pass1)))


def remove_single_point_spikes(rows):
    if len(rows) <= 2:
        return rows
    result = [rows[0]]
    for index in range(1, len(rows) - 1):
        previous = result[-1]
        current = rows[index]
        following = rows[index + 1]
        if current.get("GpsGapBreak") or not is_single_point_spike(previous, current, following):
            result.append(current)
    result.append(rows[-1])
    return result


def is_single_point_spike(previous, current, following):
    dt_prev_ms = current["Timestamp"] - previous["Timestamp"]
    dt_next_ms = following["Timestamp"] - current["Timestamp"]
    if not (1 <= dt_prev_ms <= SINGLE_POINT_SPIKE_MAX_DURATION_MS):
        return False
    if not (1 <= dt_next_ms <= SINGLE_POINT_SPIKE_MAX_DURATION_MS):
        return False

    dt_total_ms = following["Timestamp"] - previous["Timestamp"]
    if dt_total_ms <= 0:
        return False
    fraction = max(0.0, min(1.0, (current["Timestamp"] - previous["Timestamp"]) / dt_total_ms))
    expected_lat = previous["Lat"] + (following["Lat"] - previous["Lat"]) * fraction
    expected_lon = previous["Lon"] + (following["Lon"] - previous["Lon"]) * fraction
    deviation_m = haversine_m(expected_lat, expected_lon, current["Lat"], current["Lon"])
    deviation_speed_kmh = (2.0 * deviation_m) / (dt_total_ms / 1000.0) * 3.6
    return deviation_speed_kmh >= SINGLE_POINT_SPIKE_DEVIATION_SPEED_KMH


def remove_transient_detours(rows):
    if len(rows) <= 2:
        return rows
    result = [rows[0]]
    for index in range(1, len(rows) - 1):
        previous = result[-1]
        current = rows[index]
        following = rows[index + 1]
        if current.get("GpsGapBreak") or not is_transient_detour(previous, current, following):
            result.append(current)
    result.append(rows[-1])
    return result


def is_transient_detour(previous, current, following):
    dt_prev_ms = current["Timestamp"] - previous["Timestamp"]
    dt_next_ms = following["Timestamp"] - current["Timestamp"]
    if not (1 <= dt_prev_ms <= TRANSIENT_DETOUR_MAX_DURATION_MS):
        return False
    if not (1 <= dt_next_ms <= TRANSIENT_DETOUR_MAX_DURATION_MS):
        return False

    previous_to_next = haversine_m(previous["Lat"], previous["Lon"], following["Lat"], following["Lon"])
    if previous_to_next < TRANSIENT_DETOUR_MIN_NEIGHBOR_DISTANCE_M:
        return False

    previous_to_current = haversine_m(previous["Lat"], previous["Lon"], current["Lat"], current["Lon"])
    current_to_next = haversine_m(current["Lat"], current["Lon"], following["Lat"], following["Lon"])
    extra_distance = previous_to_current + current_to_next - previous_to_next
    if extra_distance < TRANSIENT_DETOUR_MIN_EXTRA_DISTANCE_M:
        return False
    if extra_distance < previous_to_next * (TRANSIENT_DETOUR_MIN_EXTRA_RATIO - 1.0):
        return False

    lateral_distance = distance_point_to_segment_m(
        current["Lat"],
        current["Lon"],
        previous["Lat"],
        previous["Lon"],
        following["Lat"],
        following["Lon"],
    )
    if lateral_distance < TRANSIENT_DETOUR_MIN_LATERAL_DISTANCE_M:
        return False

    turn_angle = abs(turn_angle_deg(previous["Lat"], previous["Lon"], current["Lat"], current["Lon"], following["Lat"], following["Lon"]))
    return turn_angle >= TRANSIENT_DETOUR_MIN_TURN_ANGLE_DEG


def distance_point_to_segment_m(point_lat, point_lon, start_lat, start_lon, end_lat, end_lon):
    reference_lat = radians((start_lat + end_lat + point_lat) / 3.0)

    def to_local_xy(lat, lon):
        x = radians(lon) * cos(reference_lat) * 6_371_000.0
        y = radians(lat) * 6_371_000.0
        return x, y

    point_x, point_y = to_local_xy(point_lat, point_lon)
    start_x, start_y = to_local_xy(start_lat, start_lon)
    end_x, end_y = to_local_xy(end_lat, end_lon)
    abx = end_x - start_x
    aby = end_y - start_y
    apx = point_x - start_x
    apy = point_y - start_y
    length_sq = abx * abx + aby * aby
    if length_sq <= 1e-6:
        return hypot(point_x - start_x, point_y - start_y)
    t = min(1.0, max(0.0, (apx * abx + apy * aby) / length_sq))
    closest_x = start_x + abx * t
    closest_y = start_y + aby * t
    return hypot(point_x - closest_x, point_y - closest_y)


def turn_angle_deg(previous_lat, previous_lon, current_lat, current_lon, next_lat, next_lon):
    reference_lat = radians((previous_lat + current_lat + next_lat) / 3.0)

    def to_vector(from_lat, from_lon, to_lat, to_lon):
        x = radians(to_lon - from_lon) * cos(reference_lat) * 6_371_000.0
        y = radians(to_lat - from_lat) * 6_371_000.0
        return x, y

    v1x, v1y = to_vector(previous_lat, previous_lon, current_lat, current_lon)
    v2x, v2y = to_vector(current_lat, current_lon, next_lat, next_lon)
    len1 = hypot(v1x, v1y)
    len2 = hypot(v2x, v2y)
    if len1 <= 1e-6 or len2 <= 1e-6:
        return 0.0
    dot = max(-1.0, min(1.0, (v1x * v2x + v1y * v2y) / (len1 * len2)))
    return degrees(acos(dot))


def remove_isolated_jump_clusters(rows):
    if len(rows) <= 3:
        return rows
    keep = [True] * len(rows)
    index = 0
    while index < len(rows) - 2:
        if not is_suspicious_boundary(rows[index], rows[index + 1]):
            index += 1
            continue

        end_boundary = -1
        for boundary_index in range(index + 1, len(rows) - 1):
            if is_suspicious_boundary(rows[boundary_index], rows[boundary_index + 1]):
                end_boundary = boundary_index
                break
        if end_boundary == -1:
            break

        cluster_start = index + 1
        cluster_end = end_boundary
        cluster_point_count = cluster_end - cluster_start + 1
        cluster_duration = rows[cluster_end]["Timestamp"] - rows[cluster_start]["Timestamp"]
        if (
            1 <= cluster_point_count <= ISOLATED_CLUSTER_MAX_POINTS
            and 0 <= cluster_duration <= ISOLATED_CLUSTER_MAX_DURATION_MS
        ):
            for keep_index in range(cluster_start, cluster_end + 1):
                if not rows[keep_index].get("GpsGapBreak"):
                    keep[keep_index] = False
            index = end_boundary + 1
        else:
            index += 1
    return [row for row, should_keep in zip(rows, keep) if should_keep]


def is_suspicious_boundary(from_row, to_row):
    dt_ms = to_row["Timestamp"] - from_row["Timestamp"]
    if not (1 <= dt_ms <= CLUSTER_BOUNDARY_MAX_DURATION_MS):
        return False
    distance = haversine_m(from_row["Lat"], from_row["Lon"], to_row["Lat"], to_row["Lon"])
    if distance < CLUSTER_BOUNDARY_MIN_DISTANCE_M:
        return False
    speed_kmh = distance / (dt_ms / 1000.0) * 3.6
    return speed_kmh >= CLUSTER_BOUNDARY_MIN_SPEED_KMH


def build_mode_data(rows, events=None, motion_samples=None):
    graph_rows = clone_rows(rows)
    map_rows = [dict(row) for row in rows if row["GpsValid"]]
    state_labels = build_state_labels(map_rows, motion_samples or [])
    trk_labels = build_trk_labels(map_rows, motion_samples or [], events or [])
    return {
        "labels": [row["dt"][5:] for row in graph_rows],
        "timestamps": [row["Timestamp"] for row in graph_rows],
        "pressureRaw": [row["PresRaw"] for row in graph_rows],
        "pressureQnh": [row["PresQnh"] for row in graph_rows],
        "altitude": [row["Alt"] for row in graph_rows],
        "stepsCumulative": [row["StepsCumulative"] for row in graph_rows],
        "stepModes": [row.get("DisplayMode") for row in graph_rows],
        "gpsPoints": build_gps_points(map_rows),
        "stateLabels": state_labels,
        "trkLabels": trk_labels,
        "summary": summarize_mode_rows(graph_rows, map_rows, graph_point_count=len(graph_rows)),
    }


def build_corrected_mode_data(rows, events=None, motion_samples=None):
    corrected = get_processed_graph_mode(rows)
    corrected_map_rows = map_filter_outliers(rows)
    corrected["stateLabels"] = build_state_labels(corrected_map_rows, motion_samples or [])
    corrected["trkLabels"] = build_trk_labels(corrected_map_rows, motion_samples or [], events or [])
    return corrected


def build_gps_points(rows):
    return [
        {
            "lat": row["Lat"],
            "lon": row["Lon"],
            "dt": row["dt"],
            "stepsDelta": row["StepsDelta"],
            "alt": row["Alt"],
            "timestamp": row["Timestamp"],
            "displayMode": row.get("DisplayMode"),
            "constantRegionKind": row.get("ConstantRegionKind"),
            "constantRegionStartLat": row.get("ConstantRegionStartLat"),
            "constantRegionStartLon": row.get("ConstantRegionStartLon"),
            "constantRegionEndLat": row.get("ConstantRegionEndLat"),
            "constantRegionEndLon": row.get("ConstantRegionEndLon"),
            "constantRegionStayLat": row.get("ConstantRegionStayLat"),
            "constantRegionStayLon": row.get("ConstantRegionStayLon"),
            "gpsGapBreak": row.get("GpsGapBreak", False),
        }
        for row in interpolate_gps_gaps_for_display(mark_gps_freeze_breaks(rows))
    ]


def mark_gps_freeze_breaks(rows):
    if len(rows) < 3:
        return rows
    result = [dict(row) for row in rows]
    index = 0
    while index < len(result) - 1:
        start = result[index]
        end = index + 1
        while (
            end < len(result)
            and haversine_m(start["Lat"], start["Lon"], result[end]["Lat"], result[end]["Lon"])
            <= GPS_FREEZE_SAME_POINT_RADIUS_M
        ):
            end += 1

        last_frozen_index = end - 1
        recovery_index = end
        if last_frozen_index > index and recovery_index < len(result):
            last_frozen = result[last_frozen_index]
            recovery = result[recovery_index]
            duration_ms = last_frozen["Timestamp"] - start["Timestamp"]
            jump_distance_m = haversine_m(last_frozen["Lat"], last_frozen["Lon"], recovery["Lat"], recovery["Lon"])
            jump_dt_sec = max(1, recovery["Timestamp"] - last_frozen["Timestamp"]) / 1000.0
            jump_speed_kmh = jump_distance_m / jump_dt_sec * 3.6
            if (
                duration_ms >= GPS_FREEZE_MIN_DURATION_MS
                and jump_distance_m >= GPS_FREEZE_RECOVERY_MIN_JUMP_M
                and jump_speed_kmh >= GPS_FREEZE_RECOVERY_MIN_SPEED_KMH
            ):
                result[recovery_index]["GpsGapBreak"] = True
        index = max(end, index + 1)
    return result


def interpolate_gps_gaps_for_display(rows):
    if len(rows) < 2:
        return rows
    result = []
    for index in range(len(rows) - 1):
        start = rows[index]
        end = rows[index + 1]
        result.append(start)
        gap_ms = end["Timestamp"] - start["Timestamp"]
        if not (GPS_GAP_INTERPOLATION_MIN_MS <= gap_ms <= GPS_GAP_INTERPOLATION_MAX_MS):
            continue
        if start.get("isStayAggregate") or end.get("isStayAggregate") or end.get("GpsGapBreak"):
            continue
        ts = start["Timestamp"] + GPS_GAP_INTERPOLATION_INTERVAL_MS
        while ts < end["Timestamp"]:
            ratio = (ts - start["Timestamp"]) / gap_ms
            point = dict(start)
            point["Timestamp"] = ts
            point["Lat"] = start["Lat"] + (end["Lat"] - start["Lat"]) * ratio
            point["Lon"] = start["Lon"] + (end["Lon"] - start["Lon"]) * ratio
            point["dt"] = datetime.fromtimestamp(ts / 1000.0).strftime("%Y-%m-%d %H:%M:%S")
            point["StepsDelta"] = 0
            point["interpolated"] = True
            point["isStayAggregate"] = False
            if start.get("DisplayMode") != end.get("DisplayMode") and ratio >= 0.5:
                point["DisplayMode"] = end.get("DisplayMode")
            result.append(point)
            ts += GPS_GAP_INTERPOLATION_INTERVAL_MS
    result.append(rows[-1])
    return result


def nearest_located_row(located_rows, timestamp_ms: int):
    if not located_rows:
        return None
    low = 0
    high = len(located_rows)
    while low < high:
        mid = (low + high) // 2
        if located_rows[mid]["Timestamp"] < timestamp_ms:
            low = mid + 1
        else:
            high = mid
    if low <= 0:
        return located_rows[0]
    if low >= len(located_rows):
        return located_rows[-1]
    before = located_rows[low - 1]
    after = located_rows[low]
    if (timestamp_ms - before["Timestamp"]) <= (after["Timestamp"] - timestamp_ms):
        return before
    return after


def display_location_at(located_rows, timestamp_ms: int):
    if not located_rows:
        return None
    low = 0
    high = len(located_rows)
    while low < high:
        mid = (low + high) // 2
        if located_rows[mid]["Timestamp"] < timestamp_ms:
            low = mid + 1
        else:
            high = mid
    if 0 < low < len(located_rows):
        before = located_rows[low - 1]
        after = located_rows[low]
        gap_ms = after["Timestamp"] - before["Timestamp"]
        if GPS_GAP_INTERPOLATION_MIN_MS <= gap_ms <= GPS_GAP_INTERPOLATION_MAX_MS:
            ratio = (timestamp_ms - before["Timestamp"]) / gap_ms
            return {
                "Lat": before["Lat"] + (after["Lat"] - before["Lat"]) * ratio,
                "Lon": before["Lon"] + (after["Lon"] - before["Lon"]) * ratio,
            }
    return nearest_located_row(located_rows, timestamp_ms)


def parse_constant_region_kind(value):
    text = (value or "").strip().upper()
    if text == "STAY":
        return "STAY"
    if text == "CONSTANT_MOVE":
        return "CONSTANT_MOVE"
    return None


def stk_label_color(value):
    return {
        "STK1": STATE_LABEL_COLOR_STK1,
        "K1": STATE_LABEL_COLOR_STK1,
        "STK2": STATE_LABEL_COLOR_STK2,
        "K2_K3": STATE_LABEL_COLOR_STK2,
        "STK4": STATE_LABEL_COLOR_STK4,
        "K4": STATE_LABEL_COLOR_STK4,
    }.get((value or "").strip().upper())


def w_label_color(value):
    return STATE_LABEL_COLOR_W1 if (value or "").strip().upper() == "W1" else STATE_LABEL_COLOR_W2


def build_state_labels(rows, motion_samples):
    if not rows or not motion_samples:
        return []
    located_rows = [row for row in rows if row["GpsValid"]]
    if not located_rows:
        return []
    labels = []
    prev_stk = None
    prev_region = None
    for sample in sorted(motion_samples, key=lambda item: item["Timestamp"]):
        stk = (sample.get("KStatus") or "").strip().upper() or None
        region = parse_constant_region_kind(sample.get("ConstantRegionKind"))
        stk_changed = stk is not None and stk != prev_stk
        region_changed = region != prev_region
        if stk_changed or region_changed:
            nearest = display_location_at(located_rows, sample["Timestamp"])
            if nearest is not None:
                lat = nearest.get("Lat")
                lon = nearest.get("Lon")
                if lat is not None and lon is not None:
                    if stk_changed and stk in ("STK4", "K4"):
                        labels.append({"lat": lat, "lon": lon, "text": "AC", "bgColor": STATE_LABEL_COLOR_STK4})
                    if region_changed:
                        if region == "STAY":
                            labels.append({"lat": lat, "lon": lon, "text": "STAY", "bgColor": STATE_LABEL_COLOR_STAY})
                        elif region == "CONSTANT_MOVE":
                            labels.append({"lat": lat, "lon": lon, "text": "CMOV", "bgColor": STATE_LABEL_COLOR_CMOVE})
        if stk is not None:
            prev_stk = stk
        prev_region = region
    return labels


def build_trk_labels(rows, motion_samples, events=None):
    if not rows:
        return []
    located_rows = [row for row in rows if row["GpsValid"]]
    if not located_rows:
        return []
    labels = []
    prev_trk = None
    sorted_motion_samples = sorted(motion_samples, key=lambda item: item["Timestamp"])

    def normalized_stk(value):
        text = (value or "").strip().upper()
        if text in ("STK4", "K4"):
            return "STK4"
        if text in ("STK2", "K2", "K2_K3"):
            return "STK2"
        if text in ("STK1", "K1"):
            return "STK1"
        return None

    def nearest_motion_stk(timestamp_ms):
        if not sorted_motion_samples:
            return None
        nearest = min(sorted_motion_samples, key=lambda sample: abs(sample["Timestamp"] - timestamp_ms))
        return normalized_stk(nearest.get("KStatus"))

    def event_stk(message, timestamp_ms):
        match = re.search(r"\bstK=([A-Za-z0-9_]+)", message)
        if match:
            return normalized_stk(match.group(1))
        return nearest_motion_stk(timestamp_ms)

    def is_trk4(value):
        text = (value or "").strip().upper()
        return text in ("TRK4", "ON")

    for sample in sorted_motion_samples:
        trk = (sample.get("TrKStatus") or "").strip().upper() or None
        stk = normalized_stk(sample.get("KStatus"))
        if trk is not None and trk != prev_trk:
            nearest = display_location_at(located_rows, sample["Timestamp"])
            if nearest is not None:
                lat = nearest.get("Lat")
                lon = nearest.get("Lon")
                if lat is not None and lon is not None and is_trk4(trk) and stk != "STK4":
                    labels.append({
                        "lat": lat,
                        "lon": lon,
                        "timestamp": sample["Timestamp"],
                        "text": "tON",
                        "bgColor": STATE_LABEL_COLOR_TRK_ON,
                    })
        if trk is not None:
            prev_trk = trk
    for event in events or []:
        message = event.get("message") or ""
        if "TRK_GPS_IMMEDIATE" not in message or not re.search(r"\btrK=(ON|TRK4)\b", message):
            continue
        timestamp = event.get("timestamp")
        ts_match = re.search(r"\bts=(\d+)", message)
        if ts_match:
            timestamp = int(ts_match.group(1))
        stk = event_stk(message, timestamp)
        if stk == "STK4":
            continue
        nearest = display_location_at(located_rows, timestamp)
        if nearest is None:
            continue
        lat = nearest.get("Lat")
        lon = nearest.get("Lon")
        if lat is None or lon is None:
            continue
        labels.append({
            "lat": lat,
            "lon": lon,
            "timestamp": timestamp,
            "text": "tON",
            "bgColor": STATE_LABEL_COLOR_TRK_ON,
        })
    return dedupe_nearby_labels(labels, min_distance_m=35.0, min_time_ms=20 * 60_000)


def dedupe_nearby_labels(labels, min_distance_m: float, min_time_ms: int):
    kept = []
    for label in labels:
        timestamp = label.get("timestamp")
        duplicate = False
        for previous in reversed(kept):
            previous_ts = previous.get("timestamp")
            if timestamp is not None and previous_ts is not None and abs(timestamp - previous_ts) > min_time_ms:
                continue
            if haversine_m(label["lat"], label["lon"], previous["lat"], previous["lon"]) <= min_distance_m:
                duplicate = True
                break
        if not duplicate:
            kept.append(label)
    return kept


def summarize_mode_rows(graph_rows, map_rows, graph_point_count):
    zero_gps_rows = sum(1 for row in graph_rows if row["Lat"] == 0 and row["Lon"] == 0)
    summary = {
        "rows": len(graph_rows),
        "graph_points": graph_point_count,
        "gps_rows": len(map_rows),
        "zero_gps_rows": zero_gps_rows,
        "total_steps_delta": sum(row["StepsDelta"] for row in graph_rows),
        "step_positive_rows": sum(1 for row in graph_rows if row["StepsDelta"] > 0),
    }
    if map_rows:
        latest_ts = map_rows[-1]["Timestamp"]
        recent_threshold = latest_ts - 2 * 60 * 60 * 1000
        recent_rows = [row for row in map_rows if row["Timestamp"] >= recent_threshold]
        summary["recent_gps_rows"] = len(recent_rows)
        if recent_rows:
            summary["recent_bounds_lat"] = f"{min(row['Lat'] for row in recent_rows):.7f}..{max(row['Lat'] for row in recent_rows):.7f}"
            summary["recent_bounds_lon"] = f"{min(row['Lon'] for row in recent_rows):.7f}..{max(row['Lon'] for row in recent_rows):.7f}"
    return summary


def filter_latest_session(rows, events, session_gap_minutes: int):
    if not rows:
        return rows, events, None

    threshold_ms = session_gap_minutes * 60 * 1000
    session_start_index = 0
    for index in range(len(rows) - 1, 0, -1):
        gap_ms = rows[index]["Timestamp"] - rows[index - 1]["Timestamp"]
        if gap_ms >= threshold_ms:
            session_start_index = index
            break

    session_rows = rows[session_start_index:]
    session_start_ts = session_rows[0]["Timestamp"]
    session_events = [event for event in events if event["timestamp"] >= session_start_ts]

    recompute_steps_cumulative(session_rows)

    session_info = {
        "mode": "latest-session",
        "gap_minutes": session_gap_minutes,
        "start": session_rows[0]["dt"],
        "end": session_rows[-1]["dt"],
    }
    return session_rows, session_events, session_info


def summarize_rows(rows, events, csv_path: Path, motion_csv_path: Path | None = None, session_info=None):
    first_row = rows[0]
    last_row = rows[-1]
    summary = {
        "raw_rows": len(rows),
        "first": first_row["dt"],
        "last": last_row["dt"],
        "event_count": len(events),
        "source_file": str(csv_path),
        "motion_source_file": str(motion_csv_path) if motion_csv_path else "none",
    }
    if session_info:
        summary["view_mode"] = session_info["mode"]
        summary["session_gap_minutes"] = session_info["gap_minutes"]
        summary["session_start"] = session_info["start"]
        summary["session_end"] = session_info["end"]
    return summary


def print_summary(summary, events, mode_data, selected_mode, selected_date_key):
    if "view_mode" in summary:
        print(f"View mode: {summary['view_mode']} (gap >= {summary['session_gap_minutes']} min)")
        print(f"Session range: {summary['session_start']} -> {summary['session_end']}")
    print(f"Rows (raw/session): {summary['raw_rows']}")
    print(f"Range: {summary['first']} -> {summary['last']}")
    print(f"Event comments: {summary['event_count']}")
    selected_summary = mode_data[selected_date_key][selected_mode]["summary"]
    print(f"Correction mode: {selected_mode}")
    print(f"Selected date: {selected_date_key}")
    print(f"Displayed rows: {selected_summary['rows']}")
    print(f"Displayed graph points: {selected_summary['graph_points']}")
    print(f"Displayed GPS rows: {selected_summary['gps_rows']}")
    print(f"Zero GPS rows skipped on map: {selected_summary['zero_gps_rows']}")
    print(f"Displayed total steps delta: {selected_summary['total_steps_delta']}")
    print(f"Displayed positive step rows: {selected_summary['step_positive_rows']}")
    if "recent_bounds_lat" in selected_summary:
        print(f"Recent GPS bounds lat: {selected_summary['recent_bounds_lat']}")
        print(f"Recent GPS bounds lon: {selected_summary['recent_bounds_lon']}")
    if events:
        print("Recent events:")
        for event in events[-8:]:
            print(f"  {event['dt']} {event['message']}")
    print(f"Source file: {summary['source_file']}")
    if summary.get("motion_source_file") and summary["motion_source_file"] != "none":
        print(f"Motion source file: {summary['motion_source_file']}")


def build_date_key_options(rows):
    keys = []
    seen = set()
    for row in rows:
        key = timestamp_to_jst_date_key(row["Timestamp"])
        if key in seen:
            continue
        seen.add(key)
        keys.append(key)
    return keys


def filter_rows_by_date(rows, date_key):
    return [dict(row) for row in rows if timestamp_to_jst_date_key(row["Timestamp"]) == date_key]


def filter_events_by_date(events, date_key):
    return [dict(event) for event in events if event["dt"].startswith(date_key)]


def filter_motion_samples_by_date(motion_samples, date_key):
    return [dict(sample) for sample in motion_samples if timestamp_to_jst_date_key(sample["Timestamp"]) == date_key]


def choose_initial_date_key(date_keys, selected_date_key):
    if selected_date_key and selected_date_key in date_keys:
        return selected_date_key
    if len(date_keys) > 1:
        # date_keys = ["all", "2025-xx-xx", ...] — 末尾が最新日
        return date_keys[-1]
    return date_keys[0] if date_keys else "all"


def build_mode_data_for_date(rows, events, motion_samples, initial_date_key):
    motion_samples = motion_samples or []
    if initial_date_key == "all":
        selected_rows = rows
        selected_events = events
        selected_motion_samples = motion_samples
    else:
        selected_rows = filter_rows_by_date(rows, initial_date_key)
        selected_events = filter_events_by_date(events, initial_date_key)
        selected_motion_samples = filter_motion_samples_by_date(motion_samples, initial_date_key)
    return (
        {
            initial_date_key: {
                "raw": build_mode_data(selected_rows, selected_events, selected_motion_samples),
                "corrected": build_corrected_mode_data(selected_rows, selected_events, selected_motion_samples),
            }
        },
        {initial_date_key: selected_events[-50:]},
    )


def build_dashboard_payload(mode_data, events_by_date, summary, initial_correction, date_keys, initial_date_key):
    payload_mode_data = mode_data
    payload_events = events_by_date
    if initial_date_key in mode_data:
        payload_mode_data = {initial_date_key: mode_data[initial_date_key]}
    if initial_date_key in events_by_date:
        payload_events = {initial_date_key: events_by_date[initial_date_key]}
    payload = {
        "modesByDate": payload_mode_data,
        "eventsByDate": payload_events,
        "commonSummary": summary,
        "initialCorrection": initial_correction,
        "dateKeys": date_keys,
        "initialDateKey": initial_date_key,
    }
    return json.dumps(payload, ensure_ascii=False)


def render_dashboard_html(payload_json: str, title: str, tile_url_template: str) -> str:
    return f"""<!DOCTYPE html>
<html lang="ja">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{title}</title>
  <link
    rel="stylesheet"
    href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"
    integrity="sha256-p4NxAoJBhIIN+hmNHrzRCf9tD/miZyoHS5obTRR9BMY="
    crossorigin=""
  >
  <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
  <script
    src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"
    integrity="sha256-20nQCchB9co0qIjJZRGuk2/Z9VM+kNiyxNV1lvTlZBo="
    crossorigin=""
  ></script>
  <style>
    :root {{
      color-scheme: dark;
      --bg: #0a1020;
      --panel: rgba(13, 24, 45, 0.84);
      --line: rgba(255, 255, 255, 0.12);
      --text: #eef4ff;
      --muted: #9eb2d0;
      --green: #58d26a;
      --cyan: #4cd5ff;
      --yellow: #ffdd57;
      --white: #f4f7ff;
    }}
    * {{
      box-sizing: border-box;
    }}
    body {{
      margin: 0;
      font-family: "Segoe UI", "Yu Gothic UI", sans-serif;
      color: var(--text);
      background:
        radial-gradient(circle at top left, rgba(76, 213, 255, 0.16), transparent 32%),
        radial-gradient(circle at top right, rgba(88, 210, 106, 0.14), transparent 28%),
        linear-gradient(180deg, #09111f 0%, #0f1d36 100%);
    }}
    .page {{
      max-width: 2450px;
      margin: 0 auto;
      padding: 24px;
    }}
    .header {{
      margin-bottom: 20px;
    }}
    .header h1 {{
      margin: 0 0 8px;
      font-size: 28px;
    }}
    .header p {{
      margin: 0;
      color: var(--muted);
    }}
    .toolbar {{
      margin-top: 16px;
      display: flex;
      gap: 12px;
      align-items: center;
      flex-wrap: wrap;
    }}
    .toolbar label {{
      color: var(--muted);
      font-size: 13px;
    }}
    .toolbar select {{
      color: var(--text);
      background: rgba(255, 255, 255, 0.06);
      border: 1px solid rgba(255, 255, 255, 0.14);
      border-radius: 10px;
      padding: 8px 10px;
    }}
    .summary-grid {{
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      gap: 12px;
      margin-bottom: 20px;
    }}
    .card {{
      background: var(--panel);
      border: 1px solid var(--line);
      border-radius: 18px;
      padding: 14px 16px;
      backdrop-filter: blur(10px);
      box-shadow: 0 10px 30px rgba(0, 0, 0, 0.20);
    }}
    .card .label {{
      font-size: 12px;
      color: var(--muted);
      margin-bottom: 8px;
      text-transform: uppercase;
      letter-spacing: 0.08em;
    }}
    .card .value {{
      font-size: 22px;
      font-weight: 700;
    }}
    .direction-arrow {{
      box-sizing: border-box;
      width: 16px;
      height: 16px;
      color: var(--arrow-color, rgba(49, 120, 255, 0.92));
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 16px;
      font-weight: 800;
      line-height: 1;
      text-shadow:
        -1px -1px 0 rgba(255,255,255,0.96),
        1px -1px 0 rgba(255,255,255,0.96),
        -1px 1px 0 rgba(255,255,255,0.96),
        1px 1px 0 rgba(255,255,255,0.96);
      transform-origin: center center;
      pointer-events: none;
      user-select: none;
    }}
    .map-time-tooltip {{
      background: rgba(15, 23, 42, 0.92);
      border: 1px solid rgba(148, 163, 184, 0.55);
      border-radius: 6px;
      color: #e5e7eb;
      font-size: 12px;
      font-weight: 700;
      line-height: 1.2;
      padding: 4px 7px;
      box-shadow: 0 2px 8px rgba(0,0,0,0.32);
    }}
    .map-time-tooltip::before {{
      border-top-color: rgba(15, 23, 42, 0.92);
    }}
    .state-label-stack {{
      pointer-events: none;
      user-select: none;
      white-space: nowrap;
    }}
    .state-label-badge {{
      min-width: 28px;
      padding: 2px 6px;
      border-radius: 7px;
      color: #ffffff;
      font-size: 9px;
      font-weight: 700;
      line-height: 1.15;
      text-align: center;
      box-shadow: 0 1px 5px rgba(0, 0, 0, 0.35);
      pointer-events: none;
      user-select: none;
      white-space: nowrap;
      border: 1px solid rgba(255,255,255,0.34);
    }}
    .ring-marker {{
      box-sizing: border-box;
      width: 12px;
      height: 12px;
      border-radius: 50%;
      background: #ffffff;
      box-shadow: 0 1px 3px rgba(0, 0, 0, 0.32);
    }}
    .ring-marker.start {{
      border: 2px solid #e53935;
    }}
    .ring-marker.current {{
      border: 2px solid #3178ff;
    }}
    .layout {{
      display: grid;
      grid-template-columns: 1.3fr 2.4fr;
      gap: 20px;
    }}
    .stack {{
      display: grid;
      gap: 20px;
    }}
    .chart-panel {{
      padding: 18px;
    }}
    .chart-panel h2,
    .map-panel h2,
    .event-panel h2 {{
      margin: 0 0 14px;
      font-size: 18px;
    }}
    .canvas-wrap {{
      height: 240px;
    }}
    #map {{
      height: 760px;
      border-radius: 14px;
      overflow: hidden;
    }}
    .event-panel {{
      margin-top: 20px;
    }}
    .event-list {{
      list-style: none;
      padding: 0;
      margin: 0;
      display: grid;
      gap: 8px;
      max-height: 260px;
      overflow: auto;
    }}
    .event-list li {{
      padding: 10px 12px;
      border-radius: 12px;
      background: rgba(255, 255, 255, 0.04);
      border: 1px solid rgba(255, 255, 255, 0.08);
      color: var(--muted);
      line-height: 1.45;
    }}
    .event-list strong {{
      display: block;
      color: var(--text);
      margin-bottom: 2px;
      font-size: 13px;
    }}
    .status-line {{
      margin-top: 10px;
      color: var(--muted);
      font-size: 13px;
      line-height: 1.5;
    }}
    @media (max-width: 980px) {{
      .layout {{
        grid-template-columns: 1fr;
      }}
      #map {{
        height: 420px;
      }}
    }}
  </style>
</head>
<body>
  <div class="page">
    <div class="header">
      <h1>{title}</h1>
      <p>コメント付きバックアップ CSV をそのまま読み込み、地図とグラフをまとめて確認します。</p>
      <div class="toolbar">
        <label for="dateKey">日付</label>
        <select id="dateKey"></select>
        <label for="correctionMode">補正処理</label>
        <select id="correctionMode">
          <option value="corrected">アプリ補正あり</option>
          <option value="raw">補正なし</option>
        </select>
      </div>
      <div class="status-line" id="stopNormalizationStatus"></div>
    </div>

    <div class="summary-grid" id="summaryGrid"></div>

    <div class="layout">
      <div class="stack">
        <section class="card chart-panel">
          <h2>気圧</h2>
          <div class="canvas-wrap"><canvas id="pressureChart"></canvas></div>
        </section>
        <section class="card chart-panel">
          <h2>高度</h2>
          <div class="canvas-wrap"><canvas id="altitudeChart"></canvas></div>
        </section>
        <section class="card chart-panel">
          <h2>歩数累積</h2>
          <div class="canvas-wrap"><canvas id="stepsChart"></canvas></div>
        </section>
        <section class="card chart-panel">
          <h2>停止偏差</h2>
          <div class="toolbar">
            <label for="stopDeviationFocus">偏差フォーカス</label>
            <select id="stopDeviationFocus"></select>
            <label for="stopDeviationWindowMinutes">前後分</label>
            <select id="stopDeviationWindowMinutes">
              <option value="3">3</option>
              <option value="5" selected>5</option>
              <option value="10">10</option>
              <option value="20">20</option>
            </select>
          </div>
          <div class="canvas-wrap"><canvas id="stopDeviationChart"></canvas></div>
        </section>
      </div>

      <div>
        <section class="card map-panel">
          <h2>地図</h2>
          <div class="toolbar">
            <label for="mapTimeFocus">地図時間</label>
            <select id="mapTimeFocus">
              <option value="all" selected>全日</option>
              <option value="focus">偏差フォーカス連動</option>
            </select>
            <label><input type="checkbox" id="showStateLabels"> K/W</label>
            <label><input type="checkbox" id="showTrkLabels"> trK</label>
          </div>
          <div id="map"></div>
        </section>
        <section class="card event-panel">
          <h2>最近のイベント</h2>
          <ul class="event-list" id="eventList"></ul>
        </section>
      </div>
    </div>
  </div>

  <script>
    const payload = {payload_json};
    const commonSummaryOrder = [
      ['raw_rows', 'Session Rows'],
      ['first', 'First'],
      ['last', 'Last'],
      ['event_count', 'Events'],
      ['source_file', 'Source'],
      ['motion_source_file', 'Motion Source']
    ];
    const modeSummaryOrder = [
      ['rows', 'Display Rows'],
      ['graph_points', 'Graph Points'],
      ['gps_rows', 'Map GPS Rows'],
      ['zero_gps_rows', 'Zero GPS'],
      ['total_steps_delta', 'Steps Sum'],
      ['step_positive_rows', 'Step Rows'],
      ['recent_gps_rows', 'Recent GPS Rows'],
      ['recent_bounds_lat', 'Recent Lat'],
      ['recent_bounds_lon', 'Recent Lon']
    ];
    const summaryGrid = document.getElementById('summaryGrid');
    const stopNormalizationStatus = document.getElementById('stopNormalizationStatus');
    const dateKeySelect = document.getElementById('dateKey');
    const correctionSelect = document.getElementById('correctionMode');
    const stopDeviationFocusSelect = document.getElementById('stopDeviationFocus');
    const stopDeviationWindowMinutesSelect = document.getElementById('stopDeviationWindowMinutes');
    const mapTimeFocusSelect = document.getElementById('mapTimeFocus');
    const showStateLabelsCheckbox = document.getElementById('showStateLabels');
    const showTrkLabelsCheckbox = document.getElementById('showTrkLabels');
    const APP_DEVICE_STILL_WINDOW_COUNT = 5;
    const APP_STOPPED_WINDOW_COUNT = 3;
    payload.dateKeys.forEach((dateKey) => {{
      const option = document.createElement('option');
      option.value = dateKey;
      option.textContent = dateKey === 'all' ? '全期間' : dateKey;
      dateKeySelect.appendChild(option);
    }});
    dateKeySelect.value = payload.initialDateKey;
    correctionSelect.value = payload.initialCorrection;
    stopDeviationWindowMinutesSelect.value = '5';
    mapTimeFocusSelect.value = 'all';
    showStateLabelsCheckbox.checked = false;
    showTrkLabelsCheckbox.checked = false;

    const stopNormalizationParams = {{
      DEVICE_STILL: {{
        minDurationMs: 2 * 60 * 1000,
        minPointCount: 8,
        clusterHopRadiusM: 18.0,
        clusterHopMinPoints: 3,
        clusterHopMaxPoints: 30,
        clusterHopMaxDurationMs: 8 * 60 * 1000,
        clusterHopDistanceM: 180.0,
        clusterHopReturnDistanceM: 90.0,
        clusterHopAnchorWindow: 4,
        residualSigma: 3.0,
        noiseRadiusM: 3.0,
        hardRadiusM: 14.0,
        burstHighM: 18.0,
        burstLowM: 8.0,
        burstReturnPoints: 2,
        maxBurstPoints: 12,
        radialSoftStartM: 2.0,
        radialKeepRatio: 0.28,
        radialHardCapM: 2.0
      }},
      STOPPED: {{
        minDurationMs: 2 * 60 * 1000,
        minPointCount: 8,
        clusterHopRadiusM: 22.0,
        clusterHopMinPoints: 3,
        clusterHopMaxPoints: 24,
        clusterHopMaxDurationMs: 8 * 60 * 1000,
        clusterHopDistanceM: 180.0,
        clusterHopReturnDistanceM: 90.0,
        clusterHopAnchorWindow: 4,
        residualSigma: 3.5,
        noiseRadiusM: 5.0,
        hardRadiusM: 20.0,
        burstHighM: 24.0,
        burstLowM: 10.0,
        burstReturnPoints: 2,
        maxBurstPoints: 10,
        radialSoftStartM: 10.0,
        radialKeepRatio: 0.45,
        radialHardCapM: 24.0
      }}
    }};

    function getCurrentModeData(modeKey) {{
      return payload.modesByDate[dateKeySelect.value][modeKey];
    }}

    function getCurrentEvents() {{
      return payload.eventsByDate[dateKeySelect.value] || [];
    }}

    function renderSummary(modeKey) {{
      const modeSummary = getCurrentModeData(modeKey).summary;
      summaryGrid.innerHTML = '';
      commonSummaryOrder.forEach(([key, label]) => {{
        if (!(key in payload.commonSummary)) {{
          return;
        }}
        const card = document.createElement('div');
        card.className = 'card';
        card.innerHTML = `<div class="label">${{label}}</div><div class="value">${{payload.commonSummary[key]}}</div>`;
        summaryGrid.appendChild(card);
      }});
      const modeCard = document.createElement('div');
      modeCard.className = 'card';
      modeCard.innerHTML = `<div class="label">Correction</div><div class="value">${{modeKey === 'corrected' ? 'App Corrected' : 'Raw'}}</div>`;
      summaryGrid.appendChild(modeCard);
      const dateCard = document.createElement('div');
      dateCard.className = 'card';
      dateCard.innerHTML = `<div class="label">Date</div><div class="value">${{dateKeySelect.value === 'all' ? 'All' : dateKeySelect.value}}</div>`;
      summaryGrid.appendChild(dateCard);
      modeSummaryOrder.forEach(([key, label]) => {{
        if (!(key in modeSummary)) {{
          return;
        }}
        const card = document.createElement('div');
        card.className = 'card';
        card.innerHTML = `<div class="label">${{label}}</div><div class="value">${{modeSummary[key]}}</div>`;
        summaryGrid.appendChild(card);
      }});
    }}

    function makeLineChart(canvasId, datasets) {{
      return new Chart(document.getElementById(canvasId), {{
        type: 'line',
        data: {{
          labels: [],
          datasets
        }},
        options: {{
          animation: false,
          responsive: true,
          maintainAspectRatio: false,
          interaction: {{
            intersect: false,
            mode: 'index'
          }},
          plugins: {{
            legend: {{
              labels: {{
                color: '#eef4ff'
              }}
            }}
          }},
          scales: {{
            x: {{
              ticks: {{
                color: '#9eb2d0',
                maxTicksLimit: 8
              }},
              grid: {{
                color: 'rgba(255,255,255,0.07)'
              }}
            }},
            y: {{
              ticks: {{
                color: '#9eb2d0'
              }},
              grid: {{
                color: 'rgba(255,255,255,0.07)'
              }}
            }}
          }}
        }}
      }});
    }}

    const pressureChart = makeLineChart('pressureChart', [
      {{
        label: 'Pressure (Raw)',
        data: [],
        borderColor: '#4caf50',
        backgroundColor: 'rgba(88,210,106,0.15)',
        pointRadius: 0,
        borderWidth: 1.4,
        spanGaps: true
      }},
      {{
        label: 'Pressure (QNH)',
        data: [],
        borderColor: '#ffffff',
        backgroundColor: 'rgba(244,247,255,0.10)',
        pointRadius: 0,
        borderWidth: 1.4,
        spanGaps: true
      }}
    ]);

    const altitudeChart = makeLineChart('altitudeChart', [
      {{
        label: 'Altitude',
        data: [],
        borderColor: '#ffeb3b',
        backgroundColor: 'rgba(255,221,87,0.14)',
        pointRadius: 0,
        borderWidth: 1.6,
        spanGaps: true
      }}
    ]);

    const modeColors = {{
      DEVICE_STILL: '#000000',
      STOPPED: '#8e96a8',
      WALKING: '#3178ff',
      VEHICLE: '#ff4d4f',
      UNKNOWN: '#ff4d4f'
    }};

    const modeLabels = {{
      DEVICE_STILL: 'Complete Stop',
      STOPPED: 'Stopped',
      WALKING: 'Walking',
      VEHICLE: 'Vehicle',
      UNKNOWN: 'Unknown'
    }};

    const stepChartModeOrder = ['DEVICE_STILL', 'STOPPED', 'WALKING', 'VEHICLE'];
    const stepsChart = makeLineChart('stepsChart', stepChartModeOrder.map((modeKey) => ({{
      label: modeLabels[modeKey],
      data: [],
      borderColor: modeColors[modeKey],
      backgroundColor: 'transparent',
      pointRadius: 0,
      borderWidth: modeKey === 'WALKING' ? 1.8 : 1.6,
      fill: false,
      stepped: true,
      spanGaps: false
    }})));

    const stopDeviationChart = makeLineChart('stopDeviationChart', [
      {{
        label: 'Deviation (Raw)',
        data: [],
        borderColor: '#ff8a65',
        backgroundColor: 'rgba(255,138,101,0.14)',
        pointRadius: 0,
        borderWidth: 1.2,
        spanGaps: true
      }},
      {{
        label: 'Correction Shift',
        data: [],
        borderColor: '#7ee081',
        backgroundColor: 'rgba(126,224,129,0.14)',
        pointRadius: 0,
        borderWidth: 1.4,
        spanGaps: true
      }}
    ]);
    let currentDeviationContext = null;

    const map = L.map('map');
    L.tileLayer('{tile_url_template}', {{
      maxZoom: 19,
      attribution: '&copy; OpenStreetMap'
    }}).addTo(map);
    let mapPolylineLayer = L.layerGroup().addTo(map);
    let mapStartMarker = null;
    let mapLatestMarker = null;
    let mapDirectionLayer = L.layerGroup().addTo(map);
    let mapStateLabelLayer = L.layerGroup().addTo(map);
    let mapTrkLabelLayer = L.layerGroup().addTo(map);
    let mapTimeTooltip = null;
    const mapTrackStrokeWidthPx = 4.0;
    const directionArrowParams = {{
      minSpacingPx: mapTrackStrokeWidthPx * 9.0,
      minSegmentPx: mapTrackStrokeWidthPx * 3.0,
      startEndSkipPx: mapTrackStrokeWidthPx * 4.5,
      localBearingWindow: 2
    }};

    function renderLabelLayer(layer, labels) {{
      const stackedCounts = new Map();
      labels.forEach((label) => {{
        const stackKey = `${{label.lat.toFixed(6)}},${{label.lon.toFixed(6)}}`;
        const stackIndex = stackedCounts.get(stackKey) || 0;
        stackedCounts.set(stackKey, stackIndex + 1);
        const offsetY = -10 - (stackIndex * 14);
        L.marker([label.lat, label.lon], {{
          interactive: false,
          keyboard: false,
          icon: L.divIcon({{
            className: '',
            html: `<div class="state-label-stack" style="transform: translateY(${{offsetY}}px);"><div class="state-label-badge" style="background:${{label.bgColor}};">${{label.text}}</div></div>`,
            iconSize: [56, 22 + stackIndex * 14],
            iconAnchor: [28, 11]
          }})
        }})
          .setZIndexOffset(1000 + stackIndex)
          .addTo(layer);
      }});
    }}

    function smoothGpsPoints(points) {{
      if (points.length < 5) return points;
      const windowRadius = 2;
      let skipCount = 0;
      const result = points.map((p, i) => {{
        // Android smoothPolylineTrack に合わせた除外条件:
        //   停止集約点 / VEHICLE モード / 窓内に停止点またはモード違いがある場合はスキップ
        if (p.isStayAggregate || p.gpsGapBreak) {{ skipCount++; return p; }}
        const mode = p.displayMode || 'UNKNOWN';
        if (mode === 'VEHICLE') {{ skipCount++; return p; }}
        for (let off = -windowRadius; off <= windowRadius; off++) {{
          const neighbor = points[i + off];
          if (!neighbor) continue;
          if (neighbor.isStayAggregate || neighbor.gpsGapBreak) {{ skipCount++; return p; }}
          if ((neighbor.displayMode || 'UNKNOWN') !== mode) {{ skipCount++; return p; }}
        }}
        let latSum = 0, lonSum = 0, count = 0;
        for (let off = -windowRadius; off <= windowRadius; off++) {{
          const target = points[i + off];
          if (target) {{ latSum += target.lat; lonSum += target.lon; count++; }}
        }}
        return {{ ...p, lat: latSum / count, lon: lonSum / count }};
      }});
      return result;
    }}

    // ── Catmull-Rom スプライン（Android splineMovingTrack 相当） ──────────────

    function projectToLocalMeters(lat, lon, originLat, originLon) {{
      const mpd = 111320.0;
      return {{
        x: (lon - originLon) * mpd * Math.cos(originLat * Math.PI / 180.0),
        y: (lat - originLat) * mpd
      }};
    }}

    function unprojectFromLocalMeters(x, y, originLat, originLon) {{
      const mpd = 111320.0;
      const mpdLon = mpd * Math.cos(originLat * Math.PI / 180.0);
      return {{
        lat: originLat + y / mpd,
        lon: originLon + x / Math.max(1e-9, mpdLon)
      }};
    }}

    function formatJstTimestamp(timestampMs) {{
      if (!Number.isFinite(timestampMs)) return '';
      const date = new Date(timestampMs);
      const pad = (value) => String(value).padStart(2, '0');
      return `${{date.getFullYear()}}-${{pad(date.getMonth() + 1)}}-${{pad(date.getDate())}} `
        + `${{pad(date.getHours())}}:${{pad(date.getMinutes())}}:${{pad(date.getSeconds())}}`;
    }}

    function catmullRomLocal(p0, p1, p2, p3, t, tension) {{
      const t2 = t * t, t3 = t2 * t;
      const tangentScale = Math.max(0.0, Math.min(1.0, 1.0 - tension));
      const m1x = tangentScale * (p2.x - p0.x);
      const m1y = tangentScale * (p2.y - p0.y);
      const m2x = tangentScale * (p3.x - p1.x);
      const m2y = tangentScale * (p3.y - p1.y);
      return {{
        x:
          (2*t3 - 3*t2 + 1)*p1.x +
          (t3 - 2*t2 + t)*m1x +
          (-2*t3 + 3*t2)*p2.x +
          (t3 - t2)*m2x,
        y:
          (2*t3 - 3*t2 + 1)*p1.y +
          (t3 - 2*t2 + t)*m1y +
          (-2*t3 + 3*t2)*p2.y +
          (t3 - t2)*m2y
      }};
    }}

    function splineSegmentPoints(points, samplesPerSegment) {{
      // Android: MAP_SPLINE_SAMPLES_PER_SEGMENT=5, MAP_SPLINE_EDGE_LINEAR_SEGMENTS=0, tension=0.55
      if (points.length < 3 || samplesPerSegment <= 0) return points;
      const origin = points[0];
      const proj = points.map(p => projectToLocalMeters(p.lat, p.lon, origin.lat, origin.lon));
      const result = [points[0]];
      const edgeLinear = 0;
      const tension = 0.55;
      const splineStart = edgeLinear;
      const splineEnd = Math.max(splineStart, points.length - 1 - edgeLinear);
      for (let i = 0; i < points.length - 1; i++) {{
        const p0 = proj[Math.max(0, i - 1)];
        const p1 = proj[i];
        const p2 = proj[i + 1];
        const p3 = proj[Math.min(points.length - 1, i + 2)];
        if (i >= splineStart && i < splineEnd) {{
          for (let s = 1; s <= samplesPerSegment; s++) {{
            const t = s / (samplesPerSegment + 1);
            const local = catmullRomLocal(p0, p1, p2, p3, t, tension);
            const coord = unprojectFromLocalMeters(local.x, local.y, origin.lat, origin.lon);
            const startTs = points[i].timestamp;
            const endTs = points[i + 1].timestamp;
            const timestamp = Number.isFinite(startTs) && Number.isFinite(endTs)
              ? startTs + (endTs - startTs) * t
              : startTs;
            result.push({{
              ...points[i],
              lat: coord.lat,
              lon: coord.lon,
              timestamp,
              dt: formatJstTimestamp(timestamp)
            }});
          }}
        }}
        result.push(points[i + 1]);
      }}
      return result;
    }}

    function haversineMeters(lat1, lon1, lat2, lon2) {{
      const earthRadius = 6371000.0;
      const toRad = (value) => value * Math.PI / 180.0;
      const dLat = toRad(lat2 - lat1);
      const dLon = toRad(lon2 - lon1);
      const a = Math.sin(dLat / 2) ** 2
        + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
      return earthRadius * 2 * Math.asin(Math.sqrt(a));
    }}

    function bearingDegrees(lat1, lon1, lat2, lon2) {{
      const toRad = (value) => value * Math.PI / 180.0;
      const toDeg = (value) => value * 180.0 / Math.PI;
      const phi1 = toRad(lat1);
      const phi2 = toRad(lat2);
      const lambda1 = toRad(lon1);
      const lambda2 = toRad(lon2);
      const y = Math.sin(lambda2 - lambda1) * Math.cos(phi2);
      const x =
        Math.cos(phi1) * Math.sin(phi2) -
        Math.sin(phi1) * Math.cos(phi2) * Math.cos(lambda2 - lambda1);
      return (toDeg(Math.atan2(y, x)) + 360.0) % 360.0;
    }}

    function smallestAngleDiffDeg(a, b) {{
      return Math.abs((a - b + 540.0) % 360.0 - 180.0);
    }}

    function circularMeanDegrees(angles) {{
      if (!angles.length) {{
        return 0.0;
      }}
      let sinSum = 0.0;
      let cosSum = 0.0;
      angles.forEach((angle) => {{
        const angleRad = angle * Math.PI / 180.0;
        sinSum += Math.sin(angleRad);
        cosSum += Math.cos(angleRad);
      }});
      if (Math.abs(sinSum) < 1e-9 && Math.abs(cosSum) < 1e-9) {{
        return angles[0];
      }}
      return (Math.atan2(sinSum, cosSum) * 180.0 / Math.PI + 360.0) % 360.0;
    }}

    function splitPointsForGapBreak(points) {{
      const chunks = [];
      let current = [];
      points.forEach((point) => {{
        if (point.isStayAggregate || point.gpsGapBreak) {{
          if (current.length >= 2) chunks.push(current);
          current = [];
        }} else {{
          current.push(point);
        }}
      }});
      if (current.length >= 2) chunks.push(current);
      return chunks;
    }}

    function computeDirectionArrowMarkersOnScreen(points) {{
      if (points.some((point) => point.isStayAggregate || point.gpsGapBreak)) {{
        return splitPointsForGapBreak(points)
          .flatMap((chunk) => computeDirectionArrowMarkersOnScreen(chunk));
      }}
      if (points.length < 2) {{
        return [];
      }}
      const screenPoints = points.map((point) => map.latLngToContainerPoint([point.lat, point.lon]));
      const cumulativeDistances = [0.0];
      for (let index = 1; index < points.length; index += 1) {{
        cumulativeDistances.push(
          cumulativeDistances[index - 1] + Math.hypot(
            screenPoints[index].x - screenPoints[index - 1].x,
            screenPoints[index].y - screenPoints[index - 1].y
          )
        );
      }}
      const totalDistance = cumulativeDistances[cumulativeDistances.length - 1];
      if (totalDistance < directionArrowParams.startEndSkipPx * 2) {{
        return [];
      }}

      function sampleAtDistance(targetDistance) {{
        const clampedDistance = Math.max(0.0, Math.min(totalDistance, targetDistance));
        let index = 1;
        while (index < cumulativeDistances.length && cumulativeDistances[index] < clampedDistance) {{
          index += 1;
        }}
        if (index >= cumulativeDistances.length) {{
          const lastPoint = points[points.length - 1];
          const lastScreen = screenPoints[screenPoints.length - 1];
          return {{
            x: lastScreen.x,
            y: lastScreen.y,
            lat: lastPoint.lat,
            lon: lastPoint.lon,
            displayMode: lastPoint.displayMode || 'UNKNOWN'
          }};
        }}
        const segmentStartDistance = cumulativeDistances[index - 1];
        const segmentEndDistance = cumulativeDistances[index];
        const segmentDistance = segmentEndDistance - segmentStartDistance;
        if (segmentDistance <= 1e-6) {{
          const point = points[index];
          const screen = screenPoints[index];
          return {{
            x: screen.x,
            y: screen.y,
            lat: point.lat,
            lon: point.lon,
            displayMode: point.displayMode || 'UNKNOWN'
          }};
        }}
        const ratio = (clampedDistance - segmentStartDistance) / segmentDistance;
        const startScreen = screenPoints[index - 1];
        const endScreen = screenPoints[index];
        const startPoint = points[index - 1];
        const endPoint = points[index];
        return {{
          x: startScreen.x + (endScreen.x - startScreen.x) * ratio,
          y: startScreen.y + (endScreen.y - startScreen.y) * ratio,
          lat: startPoint.lat + (endPoint.lat - startPoint.lat) * ratio,
          lon: startPoint.lon + (endPoint.lon - startPoint.lon) * ratio,
          displayMode: ratio < 0.5 ? (startPoint.displayMode || 'UNKNOWN') : (endPoint.displayMode || 'UNKNOWN')
        }};
      }}

      const markers = [];
      const firstDistance = directionArrowParams.startEndSkipPx;
      const lastDistance = totalDistance - directionArrowParams.startEndSkipPx;
      for (
        let distanceAlong = firstDistance;
        distanceAlong <= lastDistance;
        distanceAlong += directionArrowParams.minSpacingPx
      ) {{
        const center = sampleAtDistance(distanceAlong);
        const before = sampleAtDistance(distanceAlong - directionArrowParams.minSegmentPx);
        const after = sampleAtDistance(distanceAlong + directionArrowParams.minSegmentPx);
        const tangentDistance = Math.hypot(after.x - before.x, after.y - before.y);
        if (tangentDistance < 3.0) {{
          continue;
        }}
        const angle = Math.atan2(after.y - before.y, after.x - before.x) * 180.0 / Math.PI;
        markers.push({{
          lat: center.lat,
          lon: center.lon,
          displayMode: center.displayMode || 'UNKNOWN',
          angle
        }});
      }}
      return markers;
    }}

    function nearestDisplayPointToContainerPoint(points, containerPoint) {{
      let bestPoint = null;
      let bestDistanceSq = Infinity;
      points.forEach((point) => {{
        const screenPoint = map.latLngToContainerPoint([point.lat, point.lon]);
        const dx = screenPoint.x - containerPoint.x;
        const dy = screenPoint.y - containerPoint.y;
        const distanceSq = dx * dx + dy * dy;
        if (distanceSq < bestDistanceSq) {{
          bestDistanceSq = distanceSq;
          bestPoint = point;
        }}
      }});
      return bestPoint;
    }}

    function showMapTimeTooltip(latlng, point) {{
      const text = point?.dt || formatJstTimestamp(point?.timestamp);
      if (!text) return;
      if (!mapTimeTooltip) {{
        mapTimeTooltip = L.tooltip({{
          className: 'map-time-tooltip',
          direction: 'top',
          offset: [0, -8],
          opacity: 1.0,
          permanent: false,
          sticky: true
        }});
      }}
      mapTimeTooltip
        .setLatLng(latlng)
        .setContent(text)
        .addTo(map);
    }}

    function hideMapTimeTooltip() {{
      if (mapTimeTooltip) {{
        map.closeTooltip(mapTimeTooltip);
      }}
    }}

    function attachMapTimeTooltip(polyline, points) {{
      if (!points || points.length === 0) return;
      polyline.on('mousemove', (event) => {{
        const nearest = nearestDisplayPointToContainerPoint(points, event.containerPoint);
        showMapTimeTooltip(event.latlng, nearest);
      }});
      polyline.on('mouseout', hideMapTimeTooltip);
    }}

    function buildGpsGapBreakSegments(points) {{
      const segments = [];
      for (let index = 1; index < points.length; index += 1) {{
        const recovery = points[index];
        if (!recovery.gpsGapBreak) continue;
        let startIndex = index - 1;
        const frozenAnchor = points[startIndex];
        while (
          startIndex > 0 &&
          !points[startIndex - 1].gpsGapBreak &&
          haversineMeters(
            frozenAnchor.lat,
            frozenAnchor.lon,
            points[startIndex - 1].lat,
            points[startIndex - 1].lon
          ) <= 3.0
        ) {{
          startIndex -= 1;
        }}
        segments.push({{
          mode: recovery.displayMode || 'UNKNOWN',
          points: [points[startIndex], recovery]
        }});
      }}
      return segments;
    }}

    function linearTooltipPoints(start, end, intervalMs) {{
      const points = [start];
      const gapMs = end.timestamp - start.timestamp;
      if (Number.isFinite(gapMs) && gapMs > intervalMs) {{
        for (let ts = start.timestamp + intervalMs; ts < end.timestamp; ts += intervalMs) {{
          const ratio = (ts - start.timestamp) / gapMs;
          points.push({{
            ...start,
            lat: start.lat + (end.lat - start.lat) * ratio,
            lon: start.lon + (end.lon - start.lon) * ratio,
            timestamp: ts,
            dt: formatJstTimestamp(ts)
          }});
        }}
      }}
      points.push(end);
      return points;
    }}

    function buildModeSegments(points) {{
      // Android splitTrackByMode 相当。
      // isStayAggregate 点でセグメントを切り、滞在点自体はポリラインに含めない。
      if (points.length === 0) return [];
      const segments = [];
      let currentMode = null;
      let currentSegment = [];

      function flush() {{
        if (currentSegment.length >= 2) {{
          segments.push({{ mode: currentMode, points: currentSegment }});
        }}
        currentSegment = [];
        currentMode = null;
      }}

      for (let index = 0; index < points.length; index++) {{
        const point = points[index];
        if (point.isStayAggregate || point.gpsGapBreak) {{
          flush();
          if (!point.isStayAggregate) {{
            currentMode = point.displayMode || 'UNKNOWN';
            currentSegment = [point];
          }}
          continue; // 滞在点はポリラインに含めない。GPS凍結復帰点は新セグメントの始点にする。
        }}
        const pointMode = point.displayMode || 'UNKNOWN';
        if (currentMode === null) {{
          currentMode = pointMode;
          currentSegment = [point];
        }} else if (pointMode !== currentMode) {{
          // モード切り替え：Android 同様に前セグメント末尾点をブリッジとして新セグメントへ
          const bridge = currentSegment[currentSegment.length - 1];
          flush();
          currentMode = pointMode;
          currentSegment = [bridge, point];
        }} else {{
          currentSegment.push(point);
        }}
      }}
      flush();
      return segments;
    }}

    function calculateSegmentCenter(points) {{
      const latSum = points.reduce((sum, point) => sum + point.lat, 0);
      const lonSum = points.reduce((sum, point) => sum + point.lon, 0);
      return {{
        lat: latSum / points.length,
        lon: lonSum / points.length
      }};
    }}

    function collapseConstantRegions(points) {{
      if (points.length === 0) return [];

      const isStationaryMode = (m) => {{
        const sm = (m || '').toUpperCase().trim();
        return sm === 'DEVICE_STILL' || sm === 'STOPPED';
      }};
      const isStayKind = (k) => {{
        const sk = (k || '').toUpperCase().trim();
        return sk.includes('STAY');
      }};
      const isNoneKind = (k) => {{
        const sk = (k || '').toUpperCase().trim();
        return !sk || sk === 'NONE' || sk === 'NULL';
      }};

      const collapsed = [];
      let index = 0;
      let deviceStillSegments = 0;
      let stoppedSegments = 0;

      while (index < points.length) {{
        const point = points[index];
        const kind = point.constantRegionKind;
        const mode = point.displayMode;

        // Android collapseConstantRegions に準拠:
        // DEVICE_STILL / STOPPED が連続する区間は、交互でも停止領域として 1 点へ集約する。
        // 旧ログ互換として ConstantRegionKind=STAY だけの区間も同じ代表点へ集約する。
        const shouldCollapseAsStay = isStationaryMode(mode) || isStayKind(kind);

        if (!shouldCollapseAsStay && isNoneKind(kind)) {{
          collapsed.push(point);
          index += 1;
          continue;
        }}

        let endExclusive = index + 1;
        if (shouldCollapseAsStay) {{
          while (endExclusive < points.length) {{
            const nextP = points[endExclusive];
            const nextKind = nextP.constantRegionKind;
            const nextMode = nextP.displayMode;
            if (!isStationaryMode(nextMode) && !isStayKind(nextKind)) break;
            endExclusive += 1;
          }}

          const segment = points.slice(index, endExclusive);
          const lastPoint = segment[segment.length - 1];

          let latSum = 0, lonSum = 0;
          segment.forEach(p => {{ latSum += p.lat; lonSum += p.lon; }});
          const lat = lastPoint.constantRegionStayLat ?? (latSum / segment.length);
          const lon = lastPoint.constantRegionStayLon ?? (lonSum / segment.length);

          collapsed.push({{
            ...lastPoint,
            lat, lon,
            isStayAggregate: true,
            displayMode: segment.every(p => p.displayMode === 'DEVICE_STILL') ? 'DEVICE_STILL' : 'STOPPED'
          }});

          if (segment.some(p => p.displayMode === 'DEVICE_STILL')) deviceStillSegments++;
          else stoppedSegments++;
        }} else if (kind === 'CONSTANT_MOVE') {{
          while (endExclusive < points.length && points[endExclusive].constantRegionKind === kind) {{
            endExclusive += 1;
          }}
          const segment = points.slice(index, endExclusive);
          collapsed.push(...segment);
        }} else {{
          collapsed.push(point);
          endExclusive = index + 1;
        }}
        index = endExclusive;
      }}

      window._latestStats = {{ deviceStillSegments, stoppedSegments }};
      return collapsed;
    }}

    function median(values) {{
      if (values.length === 0) {{
        return 0;
      }}
      const sorted = [...values].sort((a, b) => a - b);
      const middle = Math.floor(sorted.length / 2);
      if (sorted.length % 2 === 0) {{
        return (sorted[middle - 1] + sorted[middle]) / 2;
      }}
      return sorted[middle];
    }}

    function clampWindowCount(windowPointCount) {{
      return Math.max(3, Math.floor(windowPointCount));
    }}

    function runningMedian(values, windowPointCount) {{
      if (values.length === 0) {{
        return [];
      }}
      const windowCount = clampWindowCount(windowPointCount);
      const halfSpan = Math.floor(windowCount / 2);
      return values.map((_, index) => {{
        const start = Math.max(0, index - halfSpan);
        const end = Math.min(values.length - 1, index + halfSpan);
        return median(values.slice(start, end + 1));
      }});
    }}

    function calculateSegmentMedianCenter(points) {{
      return {{
        lat: median(points.map((point) => point.lat)),
        lon: median(points.map((point) => point.lon))
      }};
    }}

    function segmentSpatialCenter(points) {{
      return {{
        lat: median(points.map((point) => point.lat)),
        lon: median(points.map((point) => point.lon))
      }};
    }}

    function isClusterHopEligible(point) {{
      return point.stepsDelta === 0 && point.displayMode !== 'VEHICLE' && !point.returnBurstFixed && !point.clusterHopFixed;
    }}

    function isPreFixedPoint(point) {{
      return !!point.returnBurstFixed || !!point.clusterHopFixed;
    }}

    function detectReturnJumpBursts(points, params) {{
      const corrected = points.map((point) => ({{ ...point }}));
      let returnBurstPoints = 0;
      let returnBurstSegments = 0;
      let index = 1;
      while (index < corrected.length - 1) {{
        const previous = corrected[index - 1];
        const current = corrected[index];
        if (
          !isClusterHopEligible(previous) ||
          !isClusterHopEligible(current) ||
          isPreFixedPoint(previous) ||
          isPreFixedPoint(current)
        ) {{
          index += 1;
          continue;
        }}
        const jumpDistance = haversineMeters(previous.lat, previous.lon, current.lat, current.lon);
        if (jumpDistance < params.enterDistanceM) {{
          index += 1;
          continue;
        }}

        let foundIndex = -1;
        let peakDistance = jumpDistance;
        for (let pointIndex = index + 1; pointIndex < Math.min(corrected.length, index + params.maxPoints); pointIndex += 1) {{
          const candidate = corrected[pointIndex];
          if (!isClusterHopEligible(candidate) || isPreFixedPoint(candidate)) {{
            break;
          }}
          if ((candidate.timestamp - current.timestamp) > params.maxDurationMs) {{
            break;
          }}
          const distanceFromPrevious = haversineMeters(previous.lat, previous.lon, candidate.lat, candidate.lon);
          peakDistance = Math.max(peakDistance, distanceFromPrevious);
          if (distanceFromPrevious <= params.returnDistanceM) {{
            foundIndex = pointIndex;
            break;
          }}
        }}

        if (foundIndex === -1 || peakDistance < params.peakDistanceM) {{
          index += 1;
          continue;
        }}

        const returnAnchor = corrected[foundIndex];
        const span = Math.max(1, foundIndex - index);
        for (let pointIndex = index; pointIndex < foundIndex; pointIndex += 1) {{
          const progress = (pointIndex - index + 1) / (span + 1);
          corrected[pointIndex] = {{
            ...corrected[pointIndex],
            lat: previous.lat + (returnAnchor.lat - previous.lat) * progress,
            lon: previous.lon + (returnAnchor.lon - previous.lon) * progress,
            returnBurstFixed: true
          }};
        }}
        returnBurstPoints += foundIndex - index;
        returnBurstSegments += 1;
        index = foundIndex + 1;
      }}
      return {{
        points: corrected,
        returnBurstPoints,
        returnBurstSegments
      }};
    }}

    function detectClusterHopStays(points, params) {{
      const corrected = points.map((point) => ({{ ...point }}));
      let clusterHopPoints = 0;
      let clusterHopSegments = 0;
      let index = params.clusterHopAnchorWindow;
      while (index < points.length - params.clusterHopAnchorWindow) {{
        const seed = points[index];
        if (!isClusterHopEligible(seed)) {{
          index += 1;
          continue;
        }}

        let runEnd = index;
        while (runEnd + 1 < points.length) {{
          const candidate = points[runEnd + 1];
          if (!isClusterHopEligible(candidate)) {{
            break;
          }}
          if ((runEnd - index + 2) > params.clusterHopMaxPoints) {{
            break;
          }}
          if ((points[runEnd + 1].timestamp - points[index].timestamp) > params.clusterHopMaxDurationMs) {{
            break;
          }}
          if (haversineMeters(seed.lat, seed.lon, candidate.lat, candidate.lon) > params.clusterHopRadiusM) {{
            break;
          }}
          runEnd += 1;
        }}

        const runPointCount = runEnd - index + 1;
        if (runPointCount < params.clusterHopMinPoints) {{
          index += 1;
          continue;
        }}

        const leftPoints = points.slice(index - params.clusterHopAnchorWindow, index);
        const rightPoints = points.slice(runEnd + 1, runEnd + 1 + params.clusterHopAnchorWindow);
        if (leftPoints.length < params.clusterHopAnchorWindow || rightPoints.length < params.clusterHopAnchorWindow) {{
          index = runEnd + 1;
          continue;
        }}
        if (leftPoints.some((point) => !isClusterHopEligible(point)) || rightPoints.some((point) => !isClusterHopEligible(point))) {{
          index = runEnd + 1;
          continue;
        }}

        const leftCenter = segmentSpatialCenter(leftPoints);
        const rightCenter = segmentSpatialCenter(rightPoints);
        const runCenter = segmentSpatialCenter(points.slice(index, runEnd + 1));
        const anchorDistance = haversineMeters(leftCenter.lat, leftCenter.lon, rightCenter.lat, rightCenter.lon);
        const runToLeftDistance = haversineMeters(runCenter.lat, runCenter.lon, leftCenter.lat, leftCenter.lon);
        const runToRightDistance = haversineMeters(runCenter.lat, runCenter.lon, rightCenter.lat, rightCenter.lon);
        if (
          anchorDistance <= params.clusterHopReturnDistanceM &&
          runToLeftDistance >= params.clusterHopDistanceM &&
          runToRightDistance >= params.clusterHopDistanceM
        ) {{
          const anchorLat = (leftCenter.lat + rightCenter.lat) / 2;
          const anchorLon = (leftCenter.lon + rightCenter.lon) / 2;
          for (let pointIndex = index; pointIndex <= runEnd; pointIndex += 1) {{
            corrected[pointIndex] = {{
              ...corrected[pointIndex],
              lat: anchorLat,
              lon: anchorLon,
              clusterHopFixed: true
            }};
          }}
          clusterHopPoints += runPointCount;
          clusterHopSegments += 1;
        }}
        index = runEnd + 1;
      }}
      return {{
        points: corrected,
        clusterHopPoints,
        clusterHopSegments
      }};
    }}

    function getClusterHopDetectionParams() {{
      return {{
        clusterHopRadiusM: 20.0,
        clusterHopMinPoints: 3,
        clusterHopMaxPoints: 30,
        clusterHopMaxDurationMs: 8 * 60 * 1000,
        clusterHopDistanceM: 180.0,
        clusterHopReturnDistanceM: 90.0,
        clusterHopAnchorWindow: 4
      }};
    }}

    function getReturnBurstDetectionParams() {{
      return {{
        enterDistanceM: 180.0,
        returnDistanceM: 90.0,
        peakDistanceM: 250.0,
        maxPoints: 30,
        maxDurationMs: 8 * 60 * 1000
      }};
    }}

    function buildStopDeviationSeries(rawPoints, correctedPoints) {{
      const labels = rawPoints.map((point) => point.dt);
      const timestamps = rawPoints.map((point) => point.timestamp);
      const rawDeviation = new Array(rawPoints.length).fill(null);
      const correctionShift = new Array(rawPoints.length).fill(null);
      let index = 0;
      while (index < rawPoints.length) {{
        const mode = rawPoints[index].displayMode;
        if (mode !== 'DEVICE_STILL' && mode !== 'STOPPED') {{
          index += 1;
          continue;
        }}
        let segmentEnd = index + 1;
        while (segmentEnd < rawPoints.length && rawPoints[segmentEnd].displayMode === mode) {{
          segmentEnd += 1;
        }}
        const segment = rawPoints.slice(index, segmentEnd);
        const center = calculateSegmentMedianCenter(segment);
        for (let pointIndex = index; pointIndex < segmentEnd; pointIndex += 1) {{
          rawDeviation[pointIndex] = haversineMeters(
            rawPoints[pointIndex].lat,
            rawPoints[pointIndex].lon,
            center.lat,
            center.lon
          );
          correctionShift[pointIndex] = haversineMeters(
            rawPoints[pointIndex].lat,
            rawPoints[pointIndex].lon,
            correctedPoints[pointIndex].lat,
            correctedPoints[pointIndex].lon
          );
        }}
        index = segmentEnd;
      }}
      return {{
        labels,
        timestamps,
        rawDeviation,
        correctionShift
      }};
    }}

    function buildDeviationFocusOptions(series) {{
      const options = [{{
        value: 'all',
        label: '全期間',
        centerIndex: null
      }}];
      const candidates = [];
      series.rawDeviation.forEach((value, index) => {{
        if (value === null || value < 8.0) {{
          return;
        }}
        candidates.push({{ index, value }});
      }});
      candidates.sort((a, b) => b.value - a.value);
      const selected = [];
      candidates.forEach((candidate) => {{
        const tooClose = selected.some((existing) => Math.abs(existing.index - candidate.index) < 60);
        if (tooClose || selected.length >= 8) {{
          return;
        }}
        selected.push(candidate);
      }});
      selected
        .sort((a, b) => a.index - b.index)
        .forEach((candidate, order) => {{
          options.push({{
            value: `peak-${{order}}`,
            label: `${{series.labels[candidate.index]}} / ${{candidate.value.toFixed(1)}}m`,
            centerIndex: candidate.index
          }});
        }});
      return options;
    }}

    function refreshDeviationFocusOptions(series) {{
      const previousValue = stopDeviationFocusSelect.value || 'all';
      const options = buildDeviationFocusOptions(series);
      stopDeviationFocusSelect.innerHTML = '';
      options.forEach((option) => {{
        const element = document.createElement('option');
        element.value = option.value;
        element.textContent = option.label;
        stopDeviationFocusSelect.appendChild(element);
      }});
      const chosen = options.some((option) => option.value === previousValue) ? previousValue : 'all';
      stopDeviationFocusSelect.value = chosen;
      return options;
    }}

    function applyDeviationFocus() {{
      if (!currentDeviationContext) {{
        return;
      }}
      const selectedValue = stopDeviationFocusSelect.value || 'all';
      const selectedOption = currentDeviationContext.focusOptions.find((option) => option.value === selectedValue);
      if (!selectedOption || selectedOption.centerIndex === null) {{
        stopDeviationChart.data.labels = currentDeviationContext.series.labels;
        stopDeviationChart.data.datasets[0].data = currentDeviationContext.series.rawDeviation;
        stopDeviationChart.data.datasets[1].data = currentDeviationContext.series.correctionShift;
        stopDeviationChart.update();
        return;
      }}
      const halfWindowPoints = Math.max(1, Math.floor(Number(stopDeviationWindowMinutesSelect.value || '5') * 60 / 3));
      const start = Math.max(0, selectedOption.centerIndex - halfWindowPoints);
      const end = Math.min(currentDeviationContext.series.labels.length, selectedOption.centerIndex + halfWindowPoints + 1);
      stopDeviationChart.data.labels = currentDeviationContext.series.labels.slice(start, end);
      stopDeviationChart.data.datasets[0].data = currentDeviationContext.series.rawDeviation.slice(start, end);
      stopDeviationChart.data.datasets[1].data = currentDeviationContext.series.correctionShift.slice(start, end);
      stopDeviationChart.update();
    }}

    function getCurrentFocusTimestampRange() {{
      if (!currentDeviationContext) {{
        return null;
      }}
      const selectedValue = stopDeviationFocusSelect.value || 'all';
      const selectedOption = currentDeviationContext.focusOptions.find((option) => option.value === selectedValue);
      if (!selectedOption || selectedOption.centerIndex === null) {{
        return null;
      }}
      const halfWindowMs = Math.max(1, Number(stopDeviationWindowMinutesSelect.value || '5')) * 60 * 1000;
      const centerTimestamp = currentDeviationContext.series.timestamps[selectedOption.centerIndex];
      return {{
        start: centerTimestamp - halfWindowMs,
        end: centerTimestamp + halfWindowMs
      }};
    }}

    function filterMapPointsByFocus(points) {{
      if (mapTimeFocusSelect.value !== 'focus') {{
        return points;
      }}
      const range = getCurrentFocusTimestampRange();
      if (!range) {{
        return points;
      }}
      const filtered = points.filter((point) => point.timestamp >= range.start && point.timestamp <= range.end);
      return filtered.length >= 2 ? filtered : points;
    }}

    function repairBurstClusters(xs, ys, baseX, baseY, residuals, params) {{
      const correctedX = [...xs];
      const correctedY = [...ys];
      let burstPoints = 0;
      let burstSegments = 0;
      let index = 0;
      while (index < residuals.length) {{
        if (residuals[index] <= params.burstHighM) {{
          index += 1;
          continue;
        }}
        let burstEnd = index;
        let lowStreak = 0;
        while (burstEnd + 1 < residuals.length && (burstEnd - index + 1) < params.maxBurstPoints) {{
          burstEnd += 1;
          if (residuals[burstEnd] <= params.burstLowM) {{
            lowStreak += 1;
            if (lowStreak >= params.burstReturnPoints) {{
              burstEnd -= params.burstReturnPoints;
              break;
            }}
          }} else {{
            lowStreak = 0;
          }}
        }}
        if (burstEnd >= index) {{
          const left = index - 1;
          const right = burstEnd + 1;
          for (let pointIndex = index; pointIndex <= burstEnd; pointIndex += 1) {{
            if (left >= 0 && right < residuals.length) {{
              const progress = (pointIndex - index + 1) / (burstEnd - index + 2);
              correctedX[pointIndex] = correctedX[left] + (correctedX[right] - correctedX[left]) * progress;
              correctedY[pointIndex] = correctedY[left] + (correctedY[right] - correctedY[left]) * progress;
            }} else if (left >= 0) {{
              correctedX[pointIndex] = correctedX[left] + (baseX[pointIndex] - correctedX[left]) * 0.5;
              correctedY[pointIndex] = correctedY[left] + (baseY[pointIndex] - correctedY[left]) * 0.5;
            }} else if (right < residuals.length) {{
              correctedX[pointIndex] = correctedX[right] + (baseX[pointIndex] - correctedX[right]) * 0.5;
              correctedY[pointIndex] = correctedY[right] + (baseY[pointIndex] - correctedY[right]) * 0.5;
            }} else {{
              correctedX[pointIndex] = baseX[pointIndex];
              correctedY[pointIndex] = baseY[pointIndex];
            }}
          }}
          burstPoints += burstEnd - index + 1;
          burstSegments += 1;
        }}
        index = Math.max(burstEnd + 1, index + 1);
      }}
      return {{
        xs: correctedX,
        ys: correctedY,
        burstPoints,
        burstSegments
      }};
    }}

    function compressRadialDeviation(xs, ys, params) {{
      const correctedX = [...xs];
      const correctedY = [...ys];
      let compressedPoints = 0;
      for (let index = 0; index < xs.length; index += 1) {{
        const radius = Math.hypot(correctedX[index], correctedY[index]);
        if (radius <= params.radialSoftStartM) {{
          continue;
        }}
        let targetRadius = params.radialSoftStartM + (radius - params.radialSoftStartM) * params.radialKeepRatio;
        targetRadius = Math.min(targetRadius, params.radialHardCapM);
        if (targetRadius >= radius) {{
          continue;
        }}
        const scale = targetRadius / Math.max(1e-9, radius);
        correctedX[index] *= scale;
        correctedY[index] *= scale;
        compressedPoints += 1;
      }}
      return {{
        xs: correctedX,
        ys: correctedY,
        compressedPoints
      }};
    }}

    function deviationSeriesStopCorrection(points, windowPointCount, params) {{
      if (points.length < 3) {{
        return {{
          points: points.map((point) => ({{ ...point }})),
          burstPoints: 0,
          burstSegments: 0,
          compressedPoints: 0,
          outlierPoints: 0,
          softenedPoints: 0
        }};
      }}
      const center = calculateSegmentMedianCenter(points);
      const latScale = 111320.0;
      const lonScale = 111320.0 * Math.cos(center.lat * Math.PI / 180.0);
      const xs = points.map((point) => (point.lon - center.lon) * lonScale);
      const ys = points.map((point) => (point.lat - center.lat) * latScale);
      const baseX = runningMedian(xs, windowPointCount);
      const baseY = runningMedian(ys, windowPointCount);
      const residuals = xs.map((x, index) => Math.hypot(x - baseX[index], ys[index] - baseY[index]));
      const burstRepair = repairBurstClusters(xs, ys, baseX, baseY, residuals, params);
      const repairedResiduals = burstRepair.xs.map((x, index) => Math.hypot(x - baseX[index], burstRepair.ys[index] - baseY[index]));
      const medianResidual = median(repairedResiduals);
      const madResidual = median(repairedResiduals.map((value) => Math.abs(value - medianResidual)));
      const robustSigma = Math.max(0.5, 1.4826 * madResidual);
      const outlierThreshold = medianResidual + params.residualSigma * robustSigma;

      let outlierPoints = 0;
      let softenedPoints = 0;
      const correctedResidualPoints = points.map((point, index) => {{
        const residual = repairedResiduals[index];
        const rawX = burstRepair.xs[index];
        const rawY = burstRepair.ys[index];
        let correctedX = rawX;
        let correctedY = rawY;

        if (residual > outlierThreshold || residual > params.hardRadiusM) {{
          correctedX = baseX[index];
          correctedY = baseY[index];
          outlierPoints += 1;
        }} else if (residual > params.noiseRadiusM) {{
          correctedX = rawX * 0.35 + baseX[index] * 0.65;
          correctedY = rawY * 0.35 + baseY[index] * 0.65;
          softenedPoints += 1;
        }}
        return {{
          x: correctedX,
          y: correctedY
        }};
      }});

      const radialCompression = compressRadialDeviation(
        correctedResidualPoints.map((point) => point.x),
        correctedResidualPoints.map((point) => point.y),
        params
      );

      const corrected = points.map((point, index) => {{
        const correctedX = radialCompression.xs[index];
        const correctedY = radialCompression.ys[index];

        return {{
          ...point,
          lat: center.lat + correctedY / latScale,
          lon: center.lon + correctedX / Math.max(1e-9, lonScale)
        }};
      }});

      return {{
        points: corrected,
        burstPoints: burstRepair.burstPoints,
        burstSegments: burstRepair.burstSegments,
        compressedPoints: radialCompression.compressedPoints,
        outlierPoints,
        softenedPoints
      }};
    }}

    function normalizeStopsForDisplay(points, deviceStillWindowCount, stoppedWindowCount) {{
      const minimumPointCount = Math.min(
        stopNormalizationParams.DEVICE_STILL.minPointCount,
        stopNormalizationParams.STOPPED.minPointCount
      );
      if (points.length < minimumPointCount) {{
        return {{
          points,
          stats: {{
            deviceStillSegments: 0,
            stoppedSegments: 0,
            returnBurstPoints: 0,
            returnBurstSegments: 0,
            clusterHopPoints: 0,
            clusterHopSegments: 0,
            burstPoints: 0,
            burstSegments: 0,
            compressedPoints: 0,
            outlierPoints: 0,
            softenedPoints: 0,
            changedPoints: 0,
            averageShiftM: 0,
            maxShiftM: 0
          }}
        }};
      }}

      const returnBurstRepair = detectReturnJumpBursts(points, getReturnBurstDetectionParams());
      const clusterHopRepair = detectClusterHopStays(returnBurstRepair.points, getClusterHopDetectionParams());
      const sourcePoints = clusterHopRepair.points.map((point) => ({{ ...point }}));
      const normalized = sourcePoints.map((point) => ({{ ...point }}));
      let deviceStillSegments = 0;
      let stoppedSegments = 0;
      let statsReturnBurstPoints = returnBurstRepair.returnBurstPoints;
      let statsReturnBurstSegments = returnBurstRepair.returnBurstSegments;
      let statsClusterHopPoints = clusterHopRepair.clusterHopPoints;
      let statsClusterHopSegments = clusterHopRepair.clusterHopSegments;
      let statsBurstPoints = 0;
      let statsBurstSegments = 0;
      let statsCompressedPoints = 0;
      let statsOutlierPoints = 0;
      let statsSoftenedPoints = 0;
      let index = 0;
      while (index < sourcePoints.length) {{
        const mode = sourcePoints[index].displayMode;
        const params = stopNormalizationParams[mode];
        if (!params || isPreFixedPoint(sourcePoints[index])) {{
          index += 1;
          continue;
        }}

        let segmentEnd = index + 1;
        while (
          segmentEnd < sourcePoints.length &&
          sourcePoints[segmentEnd].displayMode === mode &&
          !isPreFixedPoint(sourcePoints[segmentEnd])
        ) {{
          segmentEnd += 1;
        }}

        const segment = sourcePoints.slice(index, segmentEnd);
        const durationMs = segment[segment.length - 1].timestamp - segment[0].timestamp;
        const qualifies = segment.length >= params.minPointCount && durationMs >= params.minDurationMs;
        if (qualifies) {{
          const windowCount = mode === 'DEVICE_STILL' ? deviceStillWindowCount : stoppedWindowCount;
          const correctionResult = deviationSeriesStopCorrection(segment, windowCount, params);
          correctionResult.points.forEach((point, offset) => {{
            normalized[index + offset] = point;
          }});
          statsBurstPoints += correctionResult.burstPoints;
          statsBurstSegments += correctionResult.burstSegments;
          statsCompressedPoints += correctionResult.compressedPoints;
          statsOutlierPoints += correctionResult.outlierPoints;
          statsSoftenedPoints += correctionResult.softenedPoints;
          if (mode === 'DEVICE_STILL') {{
            deviceStillSegments += 1;
          }} else if (mode === 'STOPPED') {{
            stoppedSegments += 1;
          }}
          index = segmentEnd;
        }} else {{
          index += 1;
        }}
      }}
      let changedPoints = 0;
      let totalShiftM = 0;
      let maxShiftM = 0;
      for (let pointIndex = 0; pointIndex < points.length; pointIndex += 1) {{
        const shiftM = haversineMeters(
          points[pointIndex].lat,
          points[pointIndex].lon,
          normalized[pointIndex].lat,
          normalized[pointIndex].lon
        );
        if (shiftM > 0.01) {{
          changedPoints += 1;
          totalShiftM += shiftM;
          maxShiftM = Math.max(maxShiftM, shiftM);
        }}
      }}
      return {{
        points: collapseConstantRegions(normalized),
        deviationPoints: normalized,

        stats: {{
          deviceStillSegments,
          stoppedSegments,
          returnBurstPoints: statsReturnBurstPoints,
          returnBurstSegments: statsReturnBurstSegments,
          clusterHopPoints: statsClusterHopPoints,
          clusterHopSegments: statsClusterHopSegments,
          burstPoints: statsBurstPoints,
          burstSegments: statsBurstSegments,
          compressedPoints: statsCompressedPoints,
          outlierPoints: statsOutlierPoints,
          softenedPoints: statsSoftenedPoints,
          changedPoints,
          averageShiftM: changedPoints > 0 ? totalShiftM / changedPoints : 0,
          maxShiftM
        }}
      }};
    }}

    function renderStopNormalizationStatus(modeKey, stats) {{
      if (modeKey !== 'corrected') {{
        stopNormalizationStatus.textContent = '停止標準化: OFF（補正なし表示）';
        return;
      }}
      stopNormalizationStatus.textContent =
        `停止標準化: ON（Android 固定描画） | 完全停止区間=${{stats.deviceStillSegments}} | 停止区間=${{stats.stoppedSegments}} | ` +
        `復帰バースト=${{stats.returnBurstSegments}}/${{stats.returnBurstPoints}}点 | クラスタホップ=${{stats.clusterHopSegments}}/${{stats.clusterHopPoints}}点 | バースト=${{stats.burstSegments}}/${{stats.burstPoints}}点 | 半径圧縮=${{stats.compressedPoints}}点 | 外れ=${{stats.outlierPoints}} | 弱補正=${{stats.softenedPoints}} | 変化点=${{stats.changedPoints}} | ` +
        `平均移動=${{stats.averageShiftM.toFixed(2)}}m | 最大移動=${{stats.maxShiftM.toFixed(2)}}m`;
    }}

    function updateCharts(modeKey) {{
      const mode = getCurrentModeData(modeKey);
      pressureChart.data.labels = mode.labels;
      pressureChart.data.datasets[0].data = mode.pressureRaw;
      pressureChart.data.datasets[1].data = mode.pressureQnh;
      pressureChart.update();

      altitudeChart.data.labels = mode.labels;
      altitudeChart.data.datasets[0].data = mode.altitude;
      altitudeChart.update();

      stepsChart.data.labels = mode.labels;
      stepChartModeOrder.forEach((segmentMode, datasetIndex) => {{
        stepsChart.data.datasets[datasetIndex].data = mode.stepsCumulative.map((value, index) => {{
          const pointMode = mode.stepModes[index] || 'UNKNOWN';
          return pointMode === segmentMode ? value : null;
        }});
      }});
      stepsChart.update();
    }}

    function updateStopDeviationChart(rawPoints, correctedPoints) {{
      const series = buildStopDeviationSeries(rawPoints, correctedPoints);
      const focusOptions = refreshDeviationFocusOptions(series);
      currentDeviationContext = {{
        series,
        focusOptions
      }};
      applyDeviationFocus();
    }}

    function updateMap(modeKey) {{
      const mode = getCurrentModeData(modeKey);
      mapPolylineLayer.clearLayers();
      if (mapStartMarker) map.removeLayer(mapStartMarker);
      if (mapLatestMarker) map.removeLayer(mapLatestMarker);
      mapDirectionLayer.clearLayers();
      mapStateLabelLayer.clearLayers();
      mapTrkLabelLayer.clearLayers();

      if (modeKey === 'corrected') {{
        stopNormalizationStatus.textContent = '停止標準化: ON（Android地図準拠表示）';
      }} else {{
        stopNormalizationStatus.textContent = '停止標準化: OFF（補正なし表示）';
      }}

      // Android アプリ地図 — モード別カラー・方向矢印・ラベル
      const rawGpsPoints = mode.gpsPoints;
      if (!rawGpsPoints || rawGpsPoints.length === 0) {{
        renderStopNormalizationStatus(modeKey, null);
        return;
      }}

      let displayPoints;
      let deviationPoints = rawGpsPoints;
      if (modeKey === 'corrected') {{
        const normResult = normalizeStopsForDisplay(
          rawGpsPoints,
          APP_DEVICE_STILL_WINDOW_COUNT,
          APP_STOPPED_WINDOW_COUNT
        );
        renderStopNormalizationStatus(modeKey, normResult.stats);
        displayPoints = normResult.points;
        deviationPoints = normResult.deviationPoints;
      }} else {{
        displayPoints = collapseConstantRegions(rawGpsPoints);
        renderStopNormalizationStatus(modeKey, null);
      }}

      updateStopDeviationChart(rawGpsPoints, deviationPoints);

      const focusedPoints = filterMapPointsByFocus(displayPoints);
      if (focusedPoints.length === 0) {{
        return;
      }}

      // 地図境界フィット
      const allLats = focusedPoints.map((p) => p.lat);
      const allLons = focusedPoints.map((p) => p.lon);
      map.invalidateSize();
      map.fitBounds(
        L.latLngBounds(
          [Math.min(...allLats), Math.min(...allLons)],
          [Math.max(...allLats), Math.max(...allLons)]
        ),
        {{ padding: [32, 32], animate: false }}
      );
      map.invalidateSize();

      const smoothed = smoothGpsPoints(focusedPoints);
      // モード別ポリライン（Android splineMovingTrack + splitTrackByMode 相当）
      const segments = buildModeSegments(smoothed);
      const gpsGapSegments = buildGpsGapBreakSegments(smoothed);
      segments.forEach((segment) => {{
        const color = modeColors[segment.mode] || modeColors.UNKNOWN;
        const weight = mapTrackStrokeWidthPx;
        const opacity = segment.mode === 'DEVICE_STILL' ? 0.65 : 0.88;
        const drawPoints = splineSegmentPoints(segment.points, 5);
        const latLngs = drawPoints.map((p) => [p.lat, p.lon]);
        L.polyline(latLngs, {{
          color, weight, opacity
        }}).addTo(mapPolylineLayer);
        const hitPolyline = L.polyline(latLngs, {{
          color: '#ffffff',
          weight: Math.max(18, weight * 4.5),
          opacity: 0.01,
          interactive: true
        }}).addTo(mapPolylineLayer);
        attachMapTimeTooltip(hitPolyline, drawPoints);
      }});
      gpsGapSegments.forEach((segment) => {{
        const weight = mapTrackStrokeWidthPx;
        const latLngs = segment.points.map((p) => [p.lat, p.lon]);
        L.polyline(latLngs, {{
          color: '#f59e0b',
          weight: weight * 1.35,
          opacity: 0.95,
          dashArray: `${{Math.max(10, weight * 4.0)}} ${{Math.max(7, weight * 2.4)}}`,
          lineCap: 'butt',
          lineJoin: 'round',
          className: 'gps-gap-break-line'
        }}).addTo(mapPolylineLayer);
        const hitPolyline = L.polyline(latLngs, {{
          color: '#ffffff',
          weight: Math.max(18, weight * 4.5),
          opacity: 0.01,
          interactive: true
        }}).addTo(mapPolylineLayer);
        attachMapTimeTooltip(
          hitPolyline,
          linearTooltipPoints(segment.points[0], segment.points[segment.points.length - 1], 30 * 1000)
        );
      }});

      // 滞在点マーカー（isStayAggregate 点をグレー丸で表示）
      smoothed.forEach((p) => {{
        if (!p.isStayAggregate) return;
        L.circleMarker([p.lat, p.lon], {{
          radius: 3.6,
          color: '#888888',
          fillColor: '#888888',
          fillOpacity: 0.55,
          weight: 1.2,
          opacity: 0.85
        }}).addTo(mapPolylineLayer);
      }});

      // 方向矢印マーカー（Android computeDirectionArrowMarkersOnScreen() に対応）
      let arrowMarkers = [];
      try {{
        arrowMarkers = computeDirectionArrowMarkersOnScreen(smoothed);
      }} catch (error) {{
        arrowMarkers = [];
      }}
      arrowMarkers.forEach((marker) => {{
        const color = modeColors[marker.displayMode] || modeColors.UNKNOWN;
        L.marker([marker.lat, marker.lon], {{
          interactive: false,
          keyboard: false,
          zIndexOffset: 500,
          icon: L.divIcon({{
            className: '',
            html: `<div class="direction-arrow" style="--arrow-color:${{color}};transform:rotate(${{marker.angle}}deg);">&gt;</div>`,
            iconSize: [16, 16],
            iconAnchor: [8, 8]
          }})
        }}).addTo(mapDirectionLayer);
      }});

      // スタートマーカー（赤中空丸）
      const firstPt = focusedPoints[0];
      mapStartMarker = L.marker([firstPt.lat, firstPt.lon], {{
        icon: L.divIcon({{
          className: '',
          html: '<div class="ring-marker start"></div>',
          iconSize: [12, 12],
          iconAnchor: [6, 6]
        }})
      }}).addTo(map);

      // 最新点マーカー（青中空丸）
      const lastPt = focusedPoints[focusedPoints.length - 1];
      mapLatestMarker = L.marker([lastPt.lat, lastPt.lon], {{
        icon: L.divIcon({{
          className: '',
          html: '<div class="ring-marker current"></div>',
          iconSize: [12, 12],
          iconAnchor: [6, 6]
        }})
      }}).addTo(map);

      // K/W ラベルレイヤー（チェックボックスで制御）
      if (showStateLabelsCheckbox.checked) {{
        renderLabelLayer(mapStateLabelLayer, mode.stateLabels || []);
      }}

      // trK ラベルレイヤー（チェックボックスで制御）
      if (showTrkLabelsCheckbox.checked) {{
        renderLabelLayer(mapTrkLabelLayer, mode.trkLabels || []);
      }}

    }}

    function updateEventList() {{
      const events = getCurrentEvents();
      const eventList = document.getElementById('eventList');
      eventList.innerHTML = '';
      [...events].reverse().forEach((event) => {{
        const li = document.createElement('li');
        li.innerHTML = `<strong>${{event.dt}}</strong> ${{event.message}}`;
        eventList.appendChild(li);
      }});
    }}

    function renderAll() {{
      const modeKey = correctionSelect.value;
      renderSummary(modeKey);
      updateCharts(modeKey);
      updateMap(modeKey);
      updateEventList();
    }}

    function handleDateKeyChange() {{
      const dateKey = dateKeySelect.value;
      if (payload.modesByDate[dateKey]) {{
        renderAll();
        return;
      }}
      if (window.parent && window.parent !== window) {{
        window.parent.postMessage({{ type: 'gpspl-date-change', dateKey }}, '*');
      }}
    }}

    // ---- イベントリスナー ----
    dateKeySelect.addEventListener('change', handleDateKeyChange);
    correctionSelect.addEventListener('change', renderAll);

    mapTimeFocusSelect.addEventListener('change', () => {{
      updateMap(correctionSelect.value);
    }});

    stopDeviationFocusSelect.addEventListener('change', applyDeviationFocus);

    stopDeviationWindowMinutesSelect.addEventListener('change', () => {{
      applyDeviationFocus();
      if (mapTimeFocusSelect.value === 'focus') {{
        updateMap(correctionSelect.value);
      }}
    }});

    showStateLabelsCheckbox.addEventListener('change', () => {{
      const modeData = getCurrentModeData(correctionSelect.value);
      mapStateLabelLayer.clearLayers();
      if (showStateLabelsCheckbox.checked) {{
        renderLabelLayer(mapStateLabelLayer, modeData.stateLabels || []);
      }}
    }});

    showTrkLabelsCheckbox.addEventListener('change', () => {{
      const modeData = getCurrentModeData(correctionSelect.value);
      mapTrkLabelLayer.clearLayers();
      if (showTrkLabelsCheckbox.checked) {{
        renderLabelLayer(mapTrkLabelLayer, modeData.trkLabels || []);
      }}
    }});

    // 初期描画
    renderAll();
  </script>
</body>
</html>
"""


def load_dashboard_sources(csv_path_arg: str | None = None, motion_csv_path_arg: str | None = None) -> dict:
    csv_path = resolve_csv_path(csv_path_arg)
    if not csv_path.exists():
        raise FileNotFoundError(f"CSV not found: {csv_path}")

    rows, events = load_backup(csv_path)
    motion_csv_path_resolved = resolve_motion_csv_path(csv_path, motion_csv_path_arg)
    motion_samples, used_motion_path = load_motion_samples(motion_csv_path_resolved)
    rows = assign_display_modes(rows, events, motion_samples)
    return {
        "csv_path": csv_path,
        "motion_csv_path": used_motion_path,
        "rows": rows,
        "events": events,
        "motion_samples": motion_samples,
    }


def build_dashboard(
    *,
    csv_path_arg: str | None = None,
    motion_csv_path_arg: str | None = None,
    html_output: str = DEFAULT_HTML,
    view: str = "latest-session",
    session_gap_minutes: int = DEFAULT_SESSION_GAP_MINUTES,
    correction: str = "corrected",
    selected_date_key: str | None = None,
    tile_url_template: str = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
    source_data: dict | None = None,
    summary_only: bool = False,
    open_browser: bool = False,
) -> dict:
    """デスクトップアプリおよびコマンドライン共通のダッシュボード生成エントリポイント。

    Returns:
        html_path: 書き出した HTML ファイルのパス文字列
        csv_path: 使用した CSV ファイルのパス文字列
        summary: summarize_rows() の返り値（first / last / motion_source_file を含む）
        date_keys: 日付キーのリスト（"all" を先頭に含む）
        available_date_keys: date_keys の別名（互換性）
        initial_date_key: 初期選択の日付キー
    """
    if source_data is None:
        source_data = load_dashboard_sources(csv_path_arg, motion_csv_path_arg)
    csv_path = source_data["csv_path"]
    used_motion_path = source_data["motion_csv_path"]
    rows = clone_rows(source_data["rows"])
    events = [dict(event) for event in source_data["events"]]
    motion_samples = [dict(sample) for sample in source_data["motion_samples"]]

    session_info = None
    if view == "latest-session":
        rows, events, session_info = filter_latest_session(rows, events, session_gap_minutes)

    summary = summarize_rows(rows, events, csv_path, used_motion_path, session_info)

    date_keys = ["all", *build_date_key_options(rows)]
    initial_date_key = choose_initial_date_key(date_keys, selected_date_key)
    mode_data, events_by_date = build_mode_data_for_date(rows, events, motion_samples, initial_date_key)

    payload_json = build_dashboard_payload(
        mode_data, events_by_date, summary, correction, date_keys, initial_date_key
    )

    title = f"GPS Pressure Logger \u2013 {csv_path.name}"
    html = render_dashboard_html(payload_json, title, tile_url_template)

    output_path = Path(html_output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(html, encoding="utf-8")

    if open_browser:
        webbrowser.open(output_path.as_uri())

    return {
        "html_path": str(output_path),
        "csv_path": str(csv_path),
        "summary": summary,
        "date_keys": date_keys,
        "available_date_keys": date_keys,
        "initial_date_key": initial_date_key,
    }


def main():
    args = parse_args()
    try:
        result = build_dashboard(
            csv_path_arg=args.csv_path,
            motion_csv_path_arg=args.motion_csv_path,
            html_output=args.html_output,
            view=args.view,
            session_gap_minutes=args.session_gap_minutes,
            correction=args.correction,
            summary_only=args.summary_only,
            open_browser=not args.no_browser,
        )
        print(f"Output: {result['html_path']}")
    except FileNotFoundError as exc:
        print(exc)


if __name__ == "__main__":
    main()
