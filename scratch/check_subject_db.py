import asyncio
import os
import sys
import aiomysql

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
import main

async def get_subject():
    pool = await main.get_db_pool()
    if not pool:
        print("Could not connect to database pool.")
        return
    
    async with pool.acquire() as conn:
        async with conn.cursor(aiomysql.DictCursor) as cur:
            await cur.execute("SELECT * FROM subjects WHERE subject_id = %s", ('4360485895745717992',))
            row = await cur.fetchone()
            print("Subject record:", row)
            
            await cur.execute("SELECT * FROM play_resources WHERE subject_id = %s", ('4360485895745717992',))
            rows = await cur.fetchall()
            print("\nPlay resources:")
            for r in rows:
                print(r)

if __name__ == "__main__":
    asyncio.run(get_subject())
