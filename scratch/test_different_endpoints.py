import asyncio
import sys
import os
import urllib.parse
import json

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from main import request_h5_api

async def main():
    subject_id = "2036860087691609120"
    detail_path = "game-of-thrones-AQP5Q48Qsq2"
    
    endpoints = [
        ("GET", f"/wefeed-h5api-bff/detail?subjectId={subject_id}", "https://h5-api.aoneroom.com"),
        ("GET", f"/wefeed-h5api-bff/detail?detailPath={urllib.parse.quote(detail_path)}", "https://h5-api.aoneroom.com"),
        ("GET", f"/wefeed-h5-bff/web/subject/detail?subjectId={subject_id}", "https://h5.aoneroom.com"),
        ("GET", f"/wefeed-h5-bff/detail?subjectId={subject_id}", "https://h5.aoneroom.com"),
        ("GET", f"/wefeed-h5-bff/detail?detailPath={urllib.parse.quote(detail_path)}", "https://h5.aoneroom.com"),
    ]
    
    for method, path, host in endpoints:
        print(f"\n--- Testing {host}{path} ---")
        try:
            data = await request_h5_api(method, path, host=host)
            print("Status: Success")
            print("Title:", data.get("data", {}).get("subject", {}).get("title") or data.get("data", {}).get("title"))
            print("Keys:", list(data.get("data", {}).keys()))
        except Exception as e:
            print("Status: Failed -", e)

if __name__ == "__main__":
    asyncio.run(main())
