import asyncio
import os
import sys

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
import main

async def check_schema():
    pool = await main.get_db_pool()
    if not pool:
        print("Could not connect to database pool.")
        return
    
    async with pool.acquire() as conn:
        async with conn.cursor() as cur:
            tables = ["subjects", "seasons", "play_resources", "captions"]
            for table in tables:
                print(f"\n--- Structure of table: {table} ---")
                try:
                    await cur.execute(f"DESCRIBE {table}")
                    rows = await cur.fetchall()
                    for r in rows:
                        print(r)
                except Exception as e:
                    print(f"Error describing {table}: {e}")

if __name__ == "__main__":
    asyncio.run(check_schema())
