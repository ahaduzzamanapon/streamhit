import asyncio
import json
from main import request_h5_api

async def test():
    subject_id = "9028867555875774472"
    path = f"/wefeed-h5-bff/web/subject/download?subjectId={subject_id}&se=1&ep=1&_t=1781766939"
    res = await request_h5_api('GET', path, host='https://h5.aoneroom.com', origin='https://123movienow.cc', referer=f'https://123movienow.cc/spa/videoPlayPage/movies/details?id={subject_id}&type=/movie/detail')
    print(len(res.get('data',{}).get('downloads',[])))

if __name__ == "__main__":
    asyncio.run(test())