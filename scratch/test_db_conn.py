import os
import pymysql
from dotenv import load_dotenv

load_dotenv(".env")

DB_HOST = os.getenv("DB_HOST", "127.0.0.1")
DB_PORT = int(os.getenv("DB_PORT", "3306"))
DB_USER = os.getenv("DB_USER", "root")
DB_PASSWORD = os.getenv("DB_PASSWORD", "")
DB_NAME = os.getenv("DB_NAME", "moviebox")

print(f"Connecting to DB: {DB_HOST}:{DB_PORT} / {DB_NAME} as {DB_USER}...")
try:
    conn = pymysql.connect(
        host=DB_HOST,
        port=DB_PORT,
        user=DB_USER,
        password=DB_PASSWORD,
        database=DB_NAME,
        connect_timeout=5
    )
    print("Success! Pymysql connected.")
    with conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) FROM subjects")
        print("Subjects count:", cur.fetchone())
    conn.close()
except Exception as e:
    print("Failed to connect to DB:", e)
