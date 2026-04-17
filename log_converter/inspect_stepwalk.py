import sqlite3
import base64
import gzip
import io

db_path = r'M:\マイドライブ\android\StepWalk_20260409.db'

def inspect_blob():
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()
    cursor.execute("SELECT value FROM location_dat LIMIT 1")
    row = cursor.fetchone()
    if row:
        blob = row[0]
        try:
            # Decode Base64 and then decompress GZIP
            decoded = base64.b64decode(blob)
            with gzip.GzipFile(fileobj=io.BytesIO(decoded)) as f:
                content = f.read().decode('utf-8')
                print("--- DECODED CONTENT PREVIEW ---")
                print(content[:1000])
        except Exception as e:
            print(f"Error: {e}")
            try:
                content = gzip.decompress(blob).decode('utf-8')
                print("--- RAW DECOMPRESSED CONTENT PREVIEW ---")
                print(content[:1000])
            except Exception as e2:
                print(f"Error 2: {e2}")
                print(f"Raw blob start (hex): {blob[:50].hex()}")
    conn.close()

if __name__ == "__main__":
    inspect_blob()
