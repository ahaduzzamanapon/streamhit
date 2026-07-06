import asyncio
import os
import sys

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
import main

async def test_searches():
    queries = ["House", "Naruto", "Dragon"]
    for q in queries:
        print(f"\n--- Searching for: '{q}' ---")
        payload = {
            "keyword": q,
            "page": 1,
            "perPage": 20
        }
        try:
            data = await main.request_h5_api("POST", "/wefeed-h5api-bff/subject/search", payload)
            results = data.get("data", {}).get("list", [])
            print(f"Found {len(results)} results.")
            for idx, item in enumerate(results[:3]):
                sub = item.get("subject", {})
                print(f"  {idx+1}. {sub.get('title')} (ID: {sub.get('subjectId')})")
        except Exception as e:
            print("Failed:", e)

if __name__ == "__main__":
    asyncio.run(test_searches())
