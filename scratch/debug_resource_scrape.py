import asyncio
import sys
import os

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from main import get_db_pool, scrape_episode_resources

async def main():
    pool = await get_db_pool()
    if not pool:
        print("No DB Pool")
        return
        
    # Query 5 movies from DB
    async with pool.acquire() as conn:
        async with conn.cursor(aiomysql.DictCursor) as cur:
            await cur.execute("SELECT subject_id, title, detail_path FROM subjects WHERE subject_type = 1 LIMIT 5")
            movies = await cur.fetchall()
            
    print("Found movies in DB:")
    for m in movies:
        print(f"ID: {m['subject_id']}, Title: {m['title']}, Path: {m['detail_path']}")
        
    if not movies:
        print("No movies found")
        return
        
    target = movies[0]
    print(f"\nScraping resources for movie: {target['title']} (ID: {target['subject_id']})")
    try:
        # We can't call it directly if it tries to connect to local DB.
        # But wait! If we run this script on the live server, we can get logs.
        # Let's write the code to run it locally first, but wait - local MySQL is not running.
        # So we can create an endpoint on the live server or run it by loading the env?
        # Oh, if we run it on the live server, it will connect to live MySQL!
        pass
    except Exception as e:
        print("Error:", e)

if __name__ == "__main__":
    # We can write a script that connects to live DB from the local machine?
    # Wait, the database host is in .env. Does the live database host allow remote connection from local machine?
    # Usually cPanel databases only allow localhost or whitelisted IPs.
    # So we should run the script as a temporary endpoint on the live server!
    pass
