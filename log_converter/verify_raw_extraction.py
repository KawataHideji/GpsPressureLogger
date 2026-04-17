import sqlite3
import csv
import base64
import gzip
import io
import json
from datetime import datetime

# --- 入力ファイル ---
BAROGRAPH_CSV = r'M:\マイドライブ\android\barograph_202104092317-202604092314.csv'
STEPWALK_DB = r'M:\マイドライブ\android\StepWalk_20260409.db'

def test_extract_barograph():
    print("\n[1/2] Testing Barograph Extraction...")
    try:
        with open(BAROGRAPH_CSV, mode='r', encoding='utf-8-sig') as f:
            reader = csv.DictReader(f)
            rows = list(reader)
            print(f"Total rows found: {len(rows)}")
            
            # 先頭のデータを確認
            first = rows[0]
            print(f"First Record: Time={first['DateTime(+0900)']}, Pa={first['Pa']}, Alt={first['Elevation(m)']}, Lat={first['Latitude']}, Lon={first['Longitude']}")
            
            # 変換チェック
            pa_hpa = float(first['Pa']) / 100.0
            dt = datetime.strptime(first['DateTime(+0900)'], '%Y/%m/%d %H:%M:%S')
            print(f"  -> Converted: {pa_hpa:.2f} hPa at {int(dt.timestamp() * 1000)} (ms)")
    except Exception as e:
        print(f"Barograph Error: {e}")

def test_extract_stepwalk():
    print("\n[2/2] Testing StepWalk Extraction...")
    try:
        conn = sqlite3.connect(STEPWALK_DB)
        cursor = conn.cursor()
        
        # 歩数チェック
        cursor.execute("SELECT * FROM walk_dat LIMIT 1")
        row = cursor.fetchone()
        if row:
            # カラム名を取得
            cols = [d[0] for d in cursor.description]
            row_dict = dict(zip(cols, row))
            print(f"Steps Record (Day={row_dict['_id']}): h00={row_dict['count_h00']}, h12={row_dict['count_h12']}, h23={row_dict['count_h23']}")

        # GPSチェック (BLOB)
        cursor.execute("SELECT value FROM location_dat LIMIT 1")
        row = cursor.fetchone()
        if row:
            blob_text = row[0]
            # StepWalk特有の Base64 -> GZIP -> JSON ルートを検証
            decoded = base64.b64decode(blob_text)
            with gzip.GzipFile(fileobj=io.BytesIO(decoded)) as f:
                json_data = json.loads(f.read().decode('utf-8'))
                print(f"GPS Points in BLOB: {len(json_data)}")
                if len(json_data) > 0:
                    pt = json_data[0]
                    print(f"  First GPS: Lat={pt['la']}, Lon={pt['lo']}, Time={pt['t']}")
        conn.close()
    except Exception as e:
        print(f"StepWalk Error: {e}")

if __name__ == "__main__":
    test_extract_barograph()
    test_extract_stepwalk()
