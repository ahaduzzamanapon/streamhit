import asyncio
import os
import sys
import aiomysql

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
import main

async def get_subject():
    pool = await main.get_db_pool()
    async with pool.acquire() as conn:
        async with conn.cursor(aiomysql.DictCursor) as cur:
            await cur.execute("SELECT title, detail_path, subject_type FROM subjects WHERE subject_id = %s", ('4360485895745717992',))
            row = await cur.fetchone()
            print("Subject record details:", row)

if __name__ == "__main__":
    asyncio.run(get_subject())
