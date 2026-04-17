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
EVENT_PATTERN = re.compile(r"^# EVENT\s+(\d+)\s+(.*)$")
MODE_CONFIRMED_PATTERN = re.compile(r"^MODE_CONFIRMED:\s+([A-Z_]+)\s+->\s+([A-Z_]+)")
DEFAULT_SESSION_GAP_MINUTES = 20
GRAPH_INTERVAL_MS = 30_000
MAX_REASONABLE_SPEED_KMH = 300.0
SINGLE_POINT_SPIKE_MAX_DURATION_MS = 5 * 60_000
SINGLE_POINT_SPIKE_NEIGHBOR_DISTANCE_M = 80.0
SINGLE_POINT_SPIKE_DETOUR_DISTANCE_M = 150.0
SINGLE_POINT_SPIKE_DETOUR_RATIO = 4.0
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
def parse_args():
    parser = argparse.ArgumentParser(description="Visualize a standard backup CSV.")
    parser.add_argument("--csv-path", default=None)
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
    cumulative = 0
    for row in rows:
        raw_steps = row.pop("_RawSteps", None) if "_RawSteps" in row else None
        if raw_steps is not None:
            if previous_raw_steps is None:
                row["StepsDelta"] = 0
            else:
                row["StepsDelta"] = max(0, raw_steps - previous_raw_steps)
            previous_raw_steps = raw_steps
        cumulative += row["StepsDelta"]
        row["StepsCumulative"] = cumulative

    return rows, events


def clone_rows(rows):
    return [dict(row) for row in rows]


def assign_display_modes(rows, events):
    # Viewer には MotionSample が直接入らないので、Android の表示系列で使う
    # 「確定モードのタイムライン」を MODE_CONFIRMED イベントから再構成する。
    transitions = []
    for event in events:
        match = MODE_CONFIRMED_PATTERN.match(event["message"])
        if not match:
            continue
        transitions.append({
            "timestamp": event["timestamp"],
            "mode": match.group(2),
        })

    transitions.sort(key=lambda item: item["timestamp"])
    current_mode = None
    transition_index = 0
    for row in rows:
        while transition_index < len(transitions) and transitions[transition_index]["timestamp"] <= row["Timestamp"]:
            current_mode = transitions[transition_index]["mode"]
            transition_index += 1
        row["DisplayMode"] = current_mode
    return rows


def recompute_steps_cumulative(rows):
    cumulative = 0
    for row in rows:
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
    times, steps = create_interpolated_series(raw_times, raw_steps, GRAPH_INTERVAL_MS)
    altitude = moving_average(create_metric_series(distinct, times, "Alt"), 40)
    pressure_raw = moving_average(create_metric_series(distinct, times, "PresRaw"), 40)
    pressure_qnh = moving_average(create_metric_series(distinct, times, "PresQnh"), 40)
    labels = [timestamp_to_jst_text(timestamp)[5:] for timestamp in times]
    corrected_map_rows = map_filter_outliers(rows)
    return {
        "labels": labels,
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
    location_rows = [dict(row) for row in rows if row["GpsValid"]]
    if len(location_rows) <= 2:
        return location_rows

    pass1 = []
    for entry in location_rows:
        previous = pass1[-1] if pass1 else None
        if previous is not None:
            dt_sec = (entry["Timestamp"] - previous["Timestamp"]) / 1000.0
            if dt_sec > 0:
                speed_kmh = haversine_m(previous["Lat"], previous["Lon"], entry["Lat"], entry["Lon"]) / dt_sec * 3.6
                if speed_kmh > MAX_REASONABLE_SPEED_KMH:
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
        if not is_single_point_spike(previous, current, following):
            result.append(current)
    result.append(rows[-1])
    return result


def is_single_point_spike(previous, current, following):
    previous_to_next = haversine_m(previous["Lat"], previous["Lon"], following["Lat"], following["Lon"])
    if previous_to_next > SINGLE_POINT_SPIKE_NEIGHBOR_DISTANCE_M:
        return False

    previous_to_current = haversine_m(previous["Lat"], previous["Lon"], current["Lat"], current["Lon"])
    current_to_next = haversine_m(current["Lat"], current["Lon"], following["Lat"], following["Lon"])
    detour = min(previous_to_current, current_to_next)
    if detour < SINGLE_POINT_SPIKE_DETOUR_DISTANCE_M:
        return False

    dt_prev_ms = current["Timestamp"] - previous["Timestamp"]
    dt_next_ms = following["Timestamp"] - current["Timestamp"]
    if not (1 <= dt_prev_ms <= SINGLE_POINT_SPIKE_MAX_DURATION_MS):
        return False
    if not (1 <= dt_next_ms <= SINGLE_POINT_SPIKE_MAX_DURATION_MS):
        return False

    return detour >= previous_to_next * SINGLE_POINT_SPIKE_DETOUR_RATIO


def remove_transient_detours(rows):
    if len(rows) <= 2:
        return rows
    result = [rows[0]]
    for index in range(1, len(rows) - 1):
        previous = result[-1]
        current = rows[index]
        following = rows[index + 1]
        if not is_transient_detour(previous, current, following):
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


def build_mode_data(rows):
    graph_rows = clone_rows(rows)
    map_rows = [dict(row) for row in rows if row["GpsValid"]]
    return {
        "labels": [row["dt"][5:] for row in graph_rows],
        "pressureRaw": [row["PresRaw"] for row in graph_rows],
        "pressureQnh": [row["PresQnh"] for row in graph_rows],
        "altitude": [row["Alt"] for row in graph_rows],
        "stepsCumulative": [row["StepsCumulative"] for row in graph_rows],
        "stepModes": [row.get("DisplayMode") for row in graph_rows],
        "gpsPoints": build_gps_points(map_rows),
        "summary": summarize_mode_rows(graph_rows, map_rows, graph_point_count=len(graph_rows)),
    }


def build_corrected_mode_data(rows):
    return get_processed_graph_mode(rows)


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
        }
        for row in rows
    ]


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

    cumulative = 0
    for row in session_rows:
        cumulative += row["StepsDelta"]
        row["StepsCumulative"] = cumulative

    session_info = {
        "mode": "latest-session",
        "gap_minutes": session_gap_minutes,
        "start": session_rows[0]["dt"],
        "end": session_rows[-1]["dt"],
    }
    return session_rows, session_events, session_info


def summarize_rows(rows, events, csv_path: Path, session_info=None):
    first_row = rows[0]
    last_row = rows[-1]
    summary = {
        "raw_rows": len(rows),
        "first": first_row["dt"],
        "last": last_row["dt"],
        "event_count": len(events),
        "source_file": str(csv_path),
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


def build_mode_data_by_date(rows, events):
    date_keys = build_date_key_options(rows)
    mode_data = {}
    events_by_date = {"all": events[-50:]}
    for date_key in date_keys:
        date_rows = filter_rows_by_date(rows, date_key)
        mode_data[date_key] = {
            "raw": build_mode_data(date_rows),
            "corrected": build_corrected_mode_data(date_rows),
        }
        events_by_date[date_key] = filter_events_by_date(events, date_key)[-50:]
    mode_data["all"] = {
        "raw": build_mode_data(rows),
        "corrected": build_corrected_mode_data(rows),
    }
    return mode_data, events_by_date, ["all", *date_keys]


def build_dashboard_payload(mode_data, events_by_date, summary, initial_correction, date_keys, initial_date_key):
    payload = {
        "modesByDate": mode_data,
        "eventsByDate": events_by_date,
        "commonSummary": summary,
        "initialCorrection": initial_correction,
        "dateKeys": date_keys,
        "initialDateKey": initial_date_key,
    }
    return json.dumps(payload, ensure_ascii=False)


def render_dashboard_html(payload_json: str, title: str) -> str:
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
      max-width: 1400px;
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
      color: rgba(49, 120, 255, 0.92);
      font-size: 18px;
      font-weight: 800;
      line-height: 1;
      text-shadow:
        0 0 2px rgba(9, 17, 31, 0.95),
        0 0 6px rgba(9, 17, 31, 0.85);
      transform-origin: center center;
      pointer-events: none;
      user-select: none;
    }}
    .layout {{
      display: grid;
      grid-template-columns: 1.3fr 0.8fr;
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
              <option value="all">全日</option>
              <option value="focus" selected>偏差フォーカス連動</option>
            </select>
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
      ['source_file', 'Source']
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
    mapTimeFocusSelect.value = 'focus';

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
        borderColor: '#58d26a',
        backgroundColor: 'rgba(88,210,106,0.15)',
        pointRadius: 0,
        borderWidth: 1.4,
        spanGaps: true
      }},
      {{
        label: 'Pressure (QNH)',
        data: [],
        borderColor: '#f4f7ff',
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
        borderColor: '#ffdd57',
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
    L.tileLayer('https://tile.openstreetmap.org/{{z}}/{{x}}/{{y}}.png', {{
      maxZoom: 19,
      attribution: '&copy; OpenStreetMap'
    }}).addTo(map);
    let mapPolylineLayer = L.layerGroup().addTo(map);
    let mapStartMarker = null;
    let mapLatestMarker = null;
    let mapDirectionLayer = L.layerGroup().addTo(map);
    const directionArrowParams = {{
      minSpacingM: 360.0,
      minSegmentM: 18.0,
      startEndSkipM: 40.0,
      maxTurnDeg: 22.0,
      localBearingWindow: 2
    }};

    function smoothGpsPoints(points) {{
      if (points.length < 5) {{
        return points;
      }}
      const windowRadius = 2;
      return points.map((point, index) => {{
        if (index < windowRadius || index >= points.length - windowRadius) {{
          return point;
        }}
        let latSum = 0;
        let lonSum = 0;
        let count = 0;
        for (let offset = -windowRadius; offset <= windowRadius; offset += 1) {{
          const candidate = points[index + offset];
          latSum += candidate.lat;
          lonSum += candidate.lon;
          count += 1;
        }}
        return {{
          ...point,
          lat: latSum / count,
          lon: lonSum / count
        }};
      }});
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

    function computeDirectionArrowMarkers(points) {{
      if (points.length < 4) {{
        return [];
      }}
      const cumulativeDistances = [0.0];
      for (let index = 1; index < points.length; index += 1) {{
        cumulativeDistances.push(
          cumulativeDistances[index - 1] + haversineMeters(
            points[index - 1].lat,
            points[index - 1].lon,
            points[index].lat,
            points[index].lon
          )
        );
      }}
      const totalDistance = cumulativeDistances[cumulativeDistances.length - 1];
      if (totalDistance < directionArrowParams.startEndSkipM * 2) {{
        return [];
      }}

      const markers = [];
      let lastPlacedDistance = -directionArrowParams.minSpacingM;
      for (let index = 1; index < points.length - 1; index += 1) {{
        const distanceAlong = cumulativeDistances[index];
        if (distanceAlong < directionArrowParams.startEndSkipM) {{
          continue;
        }}
        if ((totalDistance - distanceAlong) < directionArrowParams.startEndSkipM) {{
          continue;
        }}
        if ((distanceAlong - lastPlacedDistance) < directionArrowParams.minSpacingM) {{
          continue;
        }}

        const previous = points[index - 1];
        const current = points[index];
        const following = points[index + 1];
        const beforeDistance = haversineMeters(previous.lat, previous.lon, current.lat, current.lon);
        const afterDistance = haversineMeters(current.lat, current.lon, following.lat, following.lon);
        if (beforeDistance < directionArrowParams.minSegmentM || afterDistance < directionArrowParams.minSegmentM) {{
          continue;
        }}

        const localAngles = [];
        const localStart = Math.max(0, index - directionArrowParams.localBearingWindow);
        const localEnd = Math.min(points.length - 2, index + directionArrowParams.localBearingWindow);
        for (let segmentIndex = localStart; segmentIndex <= localEnd; segmentIndex += 1) {{
          const startPoint = points[segmentIndex];
          const endPoint = points[segmentIndex + 1];
          const segmentDistance = haversineMeters(startPoint.lat, startPoint.lon, endPoint.lat, endPoint.lon);
          if (segmentDistance < directionArrowParams.minSegmentM) {{
            continue;
          }}
          localAngles.push(bearingDegrees(startPoint.lat, startPoint.lon, endPoint.lat, endPoint.lon));
        }}
        if (localAngles.length < 2) {{
          continue;
        }}

        const meanAngle = circularMeanDegrees(localAngles);
        const maxTurn = Math.max(...localAngles.map((angle) => smallestAngleDiffDeg(angle, meanAngle)));
        if (maxTurn > directionArrowParams.maxTurnDeg) {{
          continue;
        }}

        markers.push({{
          lat: current.lat,
          lon: current.lon,
          displayMode: current.displayMode || 'UNKNOWN',
          // Bearing is north=0, clockwise. The ">" glyph points right at 0deg,
          // so shift by -90deg to align the glyph with map direction.
          angle: meanAngle - 90.0
        }});
        lastPlacedDistance = distanceAlong;
      }}
      return markers;
    }}

    function buildModeSegments(points) {{
      if (points.length === 0) {{
        return [];
      }}
      const segments = [];
      let currentMode = points[0].displayMode || 'UNKNOWN';
      let currentSegment = [points[0]];
      for (let index = 1; index < points.length; index += 1) {{
        const point = points[index];
        const pointMode = point.displayMode || 'UNKNOWN';
        if (pointMode !== currentMode) {{
          if (currentSegment.length >= 2) {{
            segments.push({{
              mode: currentMode,
              points: currentSegment
            }});
          }}
          currentSegment = [points[index - 1], point];
          currentMode = pointMode;
        }} else {{
          currentSegment.push(point);
        }}
      }}
      if (currentSegment.length >= 2) {{
        segments.push({{
          mode: currentMode,
          points: currentSegment
        }});
      }}
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
        points: normalized,
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

      const stopNormalizationResult = modeKey === 'corrected'
        ? normalizeStopsForDisplay(
            mode.gpsPoints,
            APP_DEVICE_STILL_WINDOW_COUNT,
            APP_STOPPED_WINDOW_COUNT
          )
        : {{
            points: mode.gpsPoints,
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
      renderStopNormalizationStatus(modeKey, stopNormalizationResult.stats);
      const stopNormalizedPoints = stopNormalizationResult.points;
      updateStopDeviationChart(mode.gpsPoints, stopNormalizedPoints);
      const displayPointsSource = modeKey === 'corrected'
        ? smoothGpsPoints(stopNormalizedPoints)
        : stopNormalizedPoints;
      const mapPointsSource = filterMapPointsByFocus(displayPointsSource);
      const points = mapPointsSource.map((point) => [point.lat, point.lon]);
      if (points.length > 0) {{
        const latLngBounds = L.latLngBounds(points);
        buildModeSegments(mapPointsSource).forEach((segment) => {{
          L.polyline(
            segment.points.map((point) => [point.lat, point.lon]),
            {{
              color: modeColors[segment.mode] || modeColors.UNKNOWN,
              weight: 4,
              opacity: 0.82
            }}
          ).addTo(mapPolylineLayer);
        }});
        map.fitBounds(latLngBounds, {{ padding: [18, 18] }});

        computeDirectionArrowMarkers(mapPointsSource).forEach((marker) => {{
          L.marker([marker.lat, marker.lon], {{
            interactive: false,
            keyboard: false,
            icon: L.divIcon({{
              className: '',
              html: `<div class="direction-arrow" style="color: ${{modeColors[marker.displayMode] || modeColors.UNKNOWN}}; transform: rotate(${{marker.angle}}deg);">&gt;</div>`,
              iconSize: [18, 18],
              iconAnchor: [9, 9]
            }})
          }}).addTo(mapDirectionLayer);
        }});

        const first = mapPointsSource[0];
        const last = mapPointsSource[mapPointsSource.length - 1];
        mapStartMarker = L.marker([first.lat, first.lon]).addTo(map).bindPopup(`Start<br>${{first.dt}}`);
        mapLatestMarker = L.marker([last.lat, last.lon]).addTo(map).bindPopup(`Latest<br>${{last.dt}}`);
      }} else {{
        map.setView([35.0, 135.0], 5);
      }}
    }}

    function applyMode(modeKey) {{
      renderSummary(modeKey);
      updateCharts(modeKey);
      updateMap(modeKey);
      renderEvents();
    }}

    function renderEvents() {{
      const events = getCurrentEvents();
      eventList.innerHTML = '';
      if (events.length === 0) {{
        const item = document.createElement('li');
        item.textContent = 'この日付のイベントコメントはありません。';
        eventList.appendChild(item);
        return;
      }}
      events.slice().reverse().forEach((event) => {{
        const item = document.createElement('li');
        item.innerHTML = `<strong>${{event.dt}}</strong>${{event.message}}`;
        eventList.appendChild(item);
      }});
    }}

    dateKeySelect.addEventListener('change', () => {{
      applyMode(correctionSelect.value);
    }});
    correctionSelect.addEventListener('change', (event) => {{
      applyMode(event.target.value);
    }});
    stopDeviationFocusSelect.addEventListener('change', () => {{
      applyDeviationFocus();
      updateMap(correctionSelect.value);
    }});
    stopDeviationWindowMinutesSelect.addEventListener('change', () => {{
      applyDeviationFocus();
      updateMap(correctionSelect.value);
    }});
    mapTimeFocusSelect.addEventListener('change', () => {{
      updateMap(correctionSelect.value);
    }});

    const eventList = document.getElementById('eventList');
    applyMode(payload.initialCorrection);
  </script>
</body>
</html>
"""


def write_dashboard(mode_data, events_by_date, summary, html_output: Path, open_browser: bool, initial_correction: str, date_keys, initial_date_key):
    payload_json = build_dashboard_payload(
        mode_data,
        events_by_date,
        summary,
        initial_correction,
        date_keys,
        initial_date_key,
    )
    html = render_dashboard_html(payload_json, f"GpsPressureLogger Dashboard: {html_output.stem}")
    html_output.write_text(html, encoding="utf-8")
    print(f"Dashboard saved to {html_output}")
    if open_browser:
        webbrowser.open("file://" + os.path.realpath(html_output))


def build_dashboard(
    csv_path_arg: str | None = None,
    html_output: str = DEFAULT_HTML,
    *,
    view: str = "latest-session",
    session_gap_minutes: int = DEFAULT_SESSION_GAP_MINUTES,
    correction: str = "corrected",
    summary_only: bool = False,
    open_browser: bool = False,
):
    csv_path = resolve_csv_path(csv_path_arg)
    print(f"Loading {csv_path}...")
    if not csv_path.exists():
        raise FileNotFoundError(f"CSV file not found: {csv_path}")

    rows, events = load_backup(csv_path)
    if not rows:
        raise ValueError("No data rows found in CSV.")
    rows = assign_display_modes(rows, events)

    session_info = None
    if view == "latest-session":
        rows, events, session_info = filter_latest_session(rows, events, session_gap_minutes)

    summary = summarize_rows(rows, events, csv_path, session_info=session_info)
    mode_data, events_by_date, date_keys = build_mode_data_by_date(rows, events)
    initial_date_key = date_keys[-1] if date_keys else "all"
    print_summary(summary, events_by_date.get(initial_date_key, []), mode_data, correction, initial_date_key)

    html_path = Path(html_output)
    if not summary_only:
        write_dashboard(
            mode_data,
            events_by_date,
            summary,
            html_path,
            open_browser=open_browser,
            initial_correction=correction,
            date_keys=date_keys,
            initial_date_key=initial_date_key,
        )

    return {
        "csv_path": csv_path,
        "html_path": html_path,
        "summary": summary,
        "mode_data": mode_data,
        "events_by_date": events_by_date,
        "date_keys": date_keys,
        "initial_date_key": initial_date_key,
    }


def main():
    args = parse_args()
    try:
        build_dashboard(
            csv_path_arg=args.csv_path,
            html_output=args.html_output,
            view=args.view,
            session_gap_minutes=args.session_gap_minutes,
            correction=args.correction,
            summary_only=args.summary_only,
            open_browser=not args.no_browser,
        )
    except (FileNotFoundError, ValueError) as exc:
        print(f"Error: {exc}")
        return


if __name__ == "__main__":
    main()
