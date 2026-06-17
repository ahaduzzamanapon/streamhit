import os
import pymysql
from dotenv import load_dotenv

load_dotenv(".env")

DB_HOST = os.getenv("DB_HOST", "127.0.0.1")
DB_PORT = int(os.getenv("DB_PORT", "3306"))
DB_USER = os.getenv("DB_USER", "root")
DB_PASSWORD = os.getenv("DB_PASSWORD", "")
DB_NAME = os.getenv("DB_NAME", "moviebox")

conn = pymysql.connect(
    host=DB_HOST,
    port=DB_PORT,
    user=DB_USER,
    password=DB_PASSWORD,
    database=DB_NAME
)
with conn.cursor(pymysql.cursors.DictCursor) as cur:
    cur.execute("SELECT * FROM subjects WHERE subject_id = '2987820995479752632'")
    row = cur.fetchone()
    print("Subject row:", row)
conn.close()
