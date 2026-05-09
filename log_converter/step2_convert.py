import argparse
import csv
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

JST = timezone(timedelta(hours=9))
STANDARD_HEADER = ["Timestamp", "Lat", "Lon", "Alt", "PresRaw", "PresQnh", "StepsDelta"]
DEFAULT_OUTPUT = "gps_pressure_full_backup_converted.csv"


def parse_args():
    parser = argparse.ArgumentParser(description="Convert extracted JSONL files into the standard backup CSV.")
    parser.add_argument("--barograph-jsonl", type=Path, default=Path("raw_extracted_barograph.jsonl"))
    parser.add_argument("--steps-jsonl", type=Path, default=Path("raw_extracted_stepwalk_steps.jsonl"))
    parser.add_argument("--gps-jsonl", type=Path, default=Path("raw_extracted_stepwalk_gps.jsonl"))
    parser.add_argument("--output-csv", type=Path, default=Path(DEFAULT_OUTPUT))
    return parser.parse_args()


def merge_record(target, incoming):
    for key, value in incoming.items():
        if key == "timestamp":
            continue
        if value is None:
            continue
        if key not in target or target[key] is None:
            target[key] = value


def read_jsonl(path: Path):
    # 上流が utf-8 / utf-8-sig どちらで書き出していても読めるよう sig 版を使う。
    with path.open("r", encoding="utf-8-sig") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            yield json.loads(line)


def stepwalk_hour_timestamp(date_id: str, hour: int) -> int:
    dt = datetime.strptime(f"{date_id} {hour:02d}:30:00", "%Y%m%d %H:%M:%S").replace(tzinfo=JST)
    return int(dt.timestamp() * 1000)


def csv_value(value):
    return "" if value is None else value


def run_conversion(barograph_jsonl: Path, steps_jsonl: Path, gps_jsonl: Path, output_csv: Path):
    timeline = {}

    print(f"Loading Barograph data: {barograph_jsonl}")
    for row in read_jsonl(barograph_jsonl):
        ts = int(row["ts"])
        entry = timeline.setdefault(ts, {"timestamp": ts, "lat": None, "lon": None, "alt": None, "pres": None, "qnh": None, "steps_delta": None})
        merge_record(entry, {
            "lat": row.get("lat"),
            "lon": row.get("lon"),
            "alt": row.get("alt"),
            "pres": row.get("pres"),
            "qnh": row.get("qnh"),
        })

    print(f"Loading StepWalk GPS data: {gps_jsonl}")
    for row in read_jsonl(gps_jsonl):
        ts = int(row["ts"])
        entry = timeline.setdefault(ts, {"timestamp": ts, "lat": None, "lon": None, "alt": None, "pres": None, "qnh": None, "steps_delta": None})
        merge_record(entry, {
            "lat": row.get("lat"),
            "lon": row.get("lon"),
        })

    print(f"Loading StepWalk step data: {steps_jsonl}")
    for row in read_jsonl(steps_jsonl):
        ts = stepwalk_hour_timestamp(str(row["date"]), int(row["hour"]))
        entry = timeline.setdefault(ts, {"timestamp": ts, "lat": None, "lon": None, "alt": None, "pres": None, "qnh": None, "steps_delta": None})
        step_delta = int(row["steps_inc"])
        if entry["steps_delta"] is None:
            entry["steps_delta"] = step_delta
        else:
            entry["steps_delta"] += step_delta

    sorted_entries = [timeline[ts] for ts in sorted(timeline)]
    print(f"Writing standard CSV: {output_csv}")
    with output_csv.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(STANDARD_HEADER)
        for entry in sorted_entries:
            writer.writerow([
                entry["timestamp"],
                csv_value(entry["lat"]),
                csv_value(entry["lon"]),
                csv_value(entry["alt"]),
                csv_value(entry["pres"]),
                csv_value(entry["qnh"]),
                csv_value(entry["steps_delta"]),
            ])

    print(f"Success. Wrote {len(sorted_entries)} records.")


def main():
    args = parse_args()
    run_conversion(args.barograph_jsonl, args.steps_jsonl, args.gps_jsonl, args.output_csv)


if __name__ == "__main__":
    main()
