import argparse
import base64
import csv
import gzip
import io
import json
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path

JST = timezone(timedelta(hours=9))


def parse_args():
    parser = argparse.ArgumentParser(description="Extract raw records from Barograph CSV and StepWalk DB.")
    parser.add_argument("--barograph-csv", type=Path, required=True, help="Path to the source Barograph CSV.")
    parser.add_argument("--stepwalk-db", type=Path, required=True, help="Path to the source StepWalk SQLite DB.")
    parser.add_argument("--barograph-out", type=Path, default=Path("raw_extracted_barograph.jsonl"))
    parser.add_argument("--steps-out", type=Path, default=Path("raw_extracted_stepwalk_steps.jsonl"))
    parser.add_argument("--gps-out", type=Path, default=Path("raw_extracted_stepwalk_gps.jsonl"))
    return parser.parse_args()


def parse_float(value):
    if value in (None, ""):
        return None
    return float(value)


def extract_barograph(source_csv: Path, output_jsonl: Path):
    print(f"Extracting Barograph: {source_csv}")
    count = 0
    with source_csv.open(mode="r", encoding="utf-8-sig", newline="") as f_in, output_jsonl.open(mode="w", encoding="utf-8") as f_out:
        reader = csv.DictReader(f_in)
        for row in reader:
            try:
                dt = datetime.strptime(row["DateTime(+0900)"], "%Y/%m/%d %H:%M:%S").replace(tzinfo=JST)
                data = {
                    "ts": int(dt.timestamp() * 1000),
                    "pres": parse_float(row["Pa"]) / 100.0,
                    "qnh": parse_float(row["MSLP(Pa)"]) / 100.0,
                    "alt": parse_float(row["Elevation(m)"]),
                    "lat": parse_float(row["Latitude"]),
                    "lon": parse_float(row["Longitude"]),
                }
                f_out.write(json.dumps(data, ensure_ascii=False) + "\n")
                count += 1
            except Exception:
                continue
    print(f"Done. Extracted {count} records from Barograph.")


def extract_stepwalk(source_db: Path, steps_output: Path, gps_output: Path):
    print(f"Extracting StepWalk: {source_db}")
    step_count = 0
    gps_count = 0

    conn = sqlite3.connect(source_db)
    cursor = conn.cursor()

    with steps_output.open(mode="w", encoding="utf-8") as f_steps:
        cursor.execute("SELECT * FROM walk_dat")
        cols = [d[0] for d in cursor.description]
        for row in cursor.fetchall():
            record = dict(zip(cols, row))
            date_id = str(record["_id"])
            for hour in range(24):
                value = record.get(f"count_h{hour:02d}", 0)
                if value is None or value < 0:
                    continue
                f_steps.write(json.dumps({"date": date_id, "hour": hour, "steps_inc": int(value)}, ensure_ascii=False) + "\n")
                step_count += 1

    with gps_output.open(mode="w", encoding="utf-8") as f_gps:
        cursor.execute("SELECT value FROM location_dat")
        for (blob_text,) in cursor.fetchall():
            try:
                decoded = base64.b64decode(blob_text)
                with gzip.GzipFile(fileobj=io.BytesIO(decoded)) as f:
                    points = json.loads(f.read().decode("utf-8"))
                    for point in points:
                        data = {
                            "ts": int(point["t"]),
                            "lat": float(point["la"]),
                            "lon": float(point["lo"]),
                            "acc": float(point.get("ac", 0.0)),
                        }
                        f_gps.write(json.dumps(data, ensure_ascii=False) + "\n")
                        gps_count += 1
            except Exception:
                continue

    conn.close()
    print(f"Done. Extracted {step_count} step-hours and {gps_count} GPS points from StepWalk.")


def main():
    args = parse_args()
    extract_barograph(args.barograph_csv, args.barograph_out)
    extract_stepwalk(args.stepwalk_db, args.steps_out, args.gps_out)


if __name__ == "__main__":
    main()
