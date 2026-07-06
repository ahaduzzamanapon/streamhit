import asyncio
import os
import sys

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
import main

async def fix_schema():
    pool = await main.get_db_pool()
    if not pool:
        print("Could not connect to database pool.")
        return
    
    async with pool.acquire() as conn:
        async with conn.cursor() as cur:
            print("Applying ALTER TABLE statements to fix AUTO_INCREMENT...")
            try:
                await cur.execute("ALTER TABLE seasons MODIFY COLUMN id INT AUTO_INCREMENT")
                print("Successfully altered table 'seasons'")
            except Exception as e:
                print(f"Error altering table 'seasons': {e}")
                
            try:
                await cur.execute("ALTER TABLE play_resources MODIFY COLUMN id INT AUTO_INCREMENT")
                print("Successfully altered table 'play_resources'")
            except Exception as e:
                print(f"Error altering table 'play_resources': {e}")

if __name__ == "__main__":
    asyncio.run(fix_schema())
