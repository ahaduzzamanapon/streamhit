import asyncio
import sys
import os
import time

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from main import request_h5_api

async def main():
    subject_id = "2036860087691609120"
    se = 1
    ep = 2
    
    print("Testing download for S1E2:")
    try:
        path = f"/wefeed-h5-bff/web/subject/download?subjectId={subject_id}&se={se}&ep={ep}&_t={int(time.time())}"
        download_data = await request_h5_api(
            "GET", 
            path, 
            host="https://h5.aoneroom.com", 
            origin="https://123movienow.cc", 
            referer=f"https://123movienow.cc/spa/videoPlayPage/movies/game-of-thrones-AQP5Q48Qsq2?id={subject_id}&type=/movie/detail"
        )
        print("Response Code:", download_data.get("code"))
        downloads = download_data.get("data", {}).get("downloads", [])
        print("Number of downloads:", len(downloads))
        for d in downloads:
            print(f"  Resolution: {d.get('resolution')}p, Size: {d.get('size')} bytes")
    except Exception as e:
        print("Fetch Error:", e)

if __name__ == "__main__":
    asyncio.run(main())
