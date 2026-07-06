import asyncio
import os
import sys
import json
import httpx

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
import main

SUBJECT_ID = "4360485895745717992"
SE = 1
EP = 3

async def run():
    token = await main.get_guest_bearer_token()
    print(f"Token acquired: {bool(token)}")
    
    detail_path = ""
    pool = await main.get_db_pool()
    if pool:
        async with pool.acquire() as conn:
            async with conn.cursor() as cur:
                await cur.execute("SELECT detail_path, title FROM subjects WHERE subject_id = %s", (SUBJECT_ID,))
                row = await cur.fetchone()
                if row:
                    detail_path = row[0] or ""
                    print(f"DB detail_path: {detail_path}, title: {row[1]}")
    
    if not detail_path:
        print("No detail_path in DB — trying to get it from H5 API...")
    
    # Try all download paths
    attempts = [
        {"host": "https://h5.aoneroom.com", "path": f"/wefeed-h5-bff/web/subject/download?subjectId={SUBJECT_ID}&se={SE}&ep={EP}"},
        {"host": "https://h5-api.aoneroom.com", "path": f"/wefeed-h5api-bff/web/subject/download?subjectId={SUBJECT_ID}&se={SE}&ep={EP}"},
        {"host": "https://h5-api.aoneroom.com", "path": f"/wefeed-h5api-bff/subject/download?subjectId={SUBJECT_ID}&se={SE}&ep={EP}"},
    ]
    
    for attempt in attempts:
        full_url = f"{attempt['host']}{attempt['path']}"
        print(f"\nTrying: {full_url}")
        try:
            data = await main.request_h5_api("GET", attempt["path"], host=attempt["host"])
            inner = data.get("data", {})
            downloads = inner.get("downloads", [])
            print(f"  Status code key: {data.get('code')}, downloads count: {len(downloads)}")
            if downloads:
                for d in downloads[:3]:
                    print(f"  -> {d.get('resolution')}p: {d.get('url','')[:80]}...")
                break
            else:
                raw = json.dumps(data)
                import re
                mp4s = re.findall(r'https?://[^\s"\']+\.mp4[^\s"\']*', raw)
                if mp4s:
                    print(f"  Found MP4 via regex: {mp4s[0][:80]}")
                else:
                    print(f"  No downloads found. Raw: {raw[:200]}")
        except Exception as e:
            print(f"  FAILED: {e}")

if __name__ == "__main__":
    asyncio.run(run())
