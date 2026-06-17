import asyncio
import sys
import os
import time
import json

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from main import request_h5_api

async def main():
    subject_id = "2036860087691609120"
    se = 1
    ep = 1
    
    print("Fetching download links...")
    try:
        path = f"/wefeed-h5-bff/web/subject/download?subjectId={subject_id}&se={se}&ep={ep}&_t={int(time.time())}"
        download_data = await request_h5_api(
            "GET", 
            path, 
            host="https://h5.aoneroom.com", 
            origin="https://123movienow.cc", 
            referer=f"https://123movienow.cc/spa/videoPlayPage/movies/game-of-thrones-AQP5Q48Qsq2?id={subject_id}&type=/movie/detail"
        )
        print("Download Response Code:", download_data.get("code"))
        
        output_file = "scratch/got_download.json"
        with open(output_file, "w", encoding="utf-8") as f:
            json.dump(download_data, f, indent=2, ensure_ascii=False)
        print("Successfully wrote response to", output_file)
    except Exception as e:
        print("Download Fetch Error:", str(e))

if __name__ == "__main__":
    asyncio.run(main())
