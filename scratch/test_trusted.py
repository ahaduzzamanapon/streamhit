import asyncio
import json
from main import request_h5_api, get_random_singapore_ip

async def test():
    # Let's force a trusted IP
    trusted_ip = "103.14.215.160"
    print(f"Testing with trusted IP: {trusted_ip}")
    
    # We can't force the IP easily because request_h5_api calls get_random_singapore_ip() internally
    # So we'll patch it dynamically
    import main
    main.get_random_singapore_ip = lambda: trusted_ip
    
    try:
        res = await request_h5_api('GET', '/wefeed-h5api-bff/detail?subjectId=933231867443270976')
        print("Success! Details:")
        print(res.get('data', {}).get('subject', {}).get('title'))
    except Exception as e:
        print(f"Detail Fetch Failed: {e}")

    try:
        subject_id = "933231867443270976"
        referer = f"https://123movienow.cc/spa/videoPlayPage/movies/some-path?id={subject_id}&type=/movie/detail"
        origin = "https://123movienow.cc"
        path = f"/wefeed-h5-bff/web/subject/download?subjectId={subject_id}&se=0&ep=0&_t=1781766939"
        res2 = await main.request_h5_api("GET", path, host="https://h5.aoneroom.com", origin=origin, referer=referer)
        print("Success! Download resources:")
        print(len(res2.get('data', {}).get('downloads', [])))
    except Exception as e:
        print(f"Resource Fetch Failed: {e}")

if __name__ == "__main__":
    asyncio.run(test())
