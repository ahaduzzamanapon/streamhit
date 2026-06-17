import asyncio
import sys
import os
import json

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from main import request_h5_api

async def main():
    payload = {
        "keyword": "Game of Thrones",
        "page": 1,
        "perPage": 10
    }
    print("Searching for Game of Thrones...")
    try:
        data = await request_h5_api("POST", "/wefeed-h5api-bff/subject/search", payload)
        print("Search Response:")
        print(json.dumps(data, indent=2, ensure_ascii=True))
    except Exception as e:
        print("Error:", e)

if __name__ == "__main__":
    asyncio.run(main())
