import asyncio
import json
import time
from main import request_h5_api

async def test():
    try:
        subject_id = "933231867443270976"
        referer = f"https://123movienow.cc/spa/videoPlayPage/movies/details?id={subject_id}&type=/movie/detail"
        origin = "https://123movienow.cc"
        path = f"/wefeed-h5-bff/web/subject/download?subjectId={subject_id}&se=0&ep=0&_t={int(time.time())}"
        
        res = await request_h5_api("GET", path, host="https://h5.aoneroom.com", origin=origin, referer=referer)
        print(json.dumps(res, indent=2))
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    asyncio.run(test())