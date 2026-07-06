import asyncio
import os
import sys

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
import main

async def search_api():
    query = "House of the Dragon"
    print(f"Searching H5 API via POST for: '{query}'...")
    try:
        payload = {
            "keyword": query,
            "page": 1,
            "perPage": 20
        }
        data = await main.request_h5_api("POST", "/wefeed-h5api-bff/subject/search", payload)
        results = data.get("data", {}).get("list", [])
        print(f"Found {len(results)} results:")
        for idx, item in enumerate(results):
            sub = item.get("subject", {})
            print(f"\nResult {idx+1}:")
            print("  Title:", sub.get("title"))
            print("  Subject ID:", sub.get("subjectId"))
            print("  Detail Path:", sub.get("detailPath"))
            print("  Subject Type:", sub.get("subjectType"))
            print("  Cam:", sub.get("isCam"))
    except Exception as e:
        print("Search Failed:", e)

if __name__ == "__main__":
    asyncio.run(search_api())
