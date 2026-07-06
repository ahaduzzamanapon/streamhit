import asyncio
import os
import sys
import json
import aiomysql

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
import main

async def check_hotd():
    subject_id = "7721864815710718808"
    pool = await main.get_db_pool()
    if not pool:
        print("Could not connect to database pool.")
        return
        
    print("=== DB SUBJECT INFO ===")
    async with pool.acquire() as conn:
        async with conn.cursor(aiomysql.DictCursor) as cur:
            await cur.execute("SELECT * FROM subjects WHERE subject_id = %s", (subject_id,))
            row = await cur.fetchone()
            print("Subject Row:", row)
            
            await cur.execute("SELECT * FROM seasons WHERE subject_id = %s", (subject_id,))
            se_rows = await cur.fetchall()
            print("\nSeasons in DB:")
            for s in se_rows:
                print(s)
                
    print("\n=== H5 API INFO ===")
    try:
        data = await main.request_h5_api("GET", f"/wefeed-h5api-bff/detail?subjectId={subject_id}")
        subject_info = data.get("data", {}).get("subject", {})
        resource_info = data.get("data", {}).get("resource", {})
        print("H5 Title:", subject_info.get("title"))
        print("H5 Dubs:", subject_info.get("dubs"))
        print("H5 Seasons:")
        seasons = resource_info.get("seasons", [])
        for s in seasons:
            print(f"  se: {s.get('se')}, maxEp: {s.get('maxEp')}")
    except Exception as e:
        print("H5 API Fetch Failed:", e)

if __name__ == "__main__":
    asyncio.run(check_hotd())
