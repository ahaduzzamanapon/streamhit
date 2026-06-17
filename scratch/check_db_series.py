import asyncio
import sys
import os

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from main import get_db_pool

async def main():
    pool = await get_db_pool()
    if not pool:
        print("No DB Pool")
        return
    async with pool.acquire() as conn:
        async with conn.cursor() as cur:
            await cur.execute("SELECT subject_id, title, subject_type, detail_path FROM subjects WHERE subject_type = 2 LIMIT 20")
            rows = await cur.fetchall()
            print("--- Series in DB ---")
            for r in rows:
                print(f"ID: {r[0]}, Title: {r[1]}, Type: {r[2]}, Path: {r[3]}")

if __name__ == "__main__":
    asyncio.run(main())
