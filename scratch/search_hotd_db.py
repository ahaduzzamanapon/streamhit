import asyncio
import os
import sys
import aiomysql

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
import main

async def search_db():
    pool = await main.get_db_pool()
    if not pool:
        print("Could not connect to database pool.")
        return
        
    print("=== SEARCHING DATABASE FOR 'House of the Dragon' ===")
    async with pool.acquire() as conn:
        async with conn.cursor(aiomysql.DictCursor) as cur:
            await cur.execute("SELECT subject_id, title, detail_path, genre, release_date FROM subjects WHERE title LIKE %s", ("%House of the Dragon%",))
            rows = await cur.fetchall()
            for r in rows:
                print(r)

if __name__ == "__main__":
    asyncio.run(search_db())
