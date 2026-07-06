import asyncio
import os
import sys
import json

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
import main

async def test_search():
    query = "House of the Dragon"
    print(f"Testing updated search logic in main.py for '{query}'...")
    try:
        payload = {
            "keyword": query,
            "page": 1,
            "perPage": 20,
            "subjectType": 0
        }
        # Call the updated request_h5_api
        data = await main.request_h5_api("POST", "/wefeed-h5api-bff/subject/search", payload)
        items = data.get("data", {}).get("items") or data.get("data", {}).get("list") or []
        print(f"Found {len(items)} items:")
        for idx, item in enumerate(items):
            sub = item.get("subject") or item
            print(f"  {idx+1}. {sub.get('title')} (ID: {sub.get('subjectId')}, Slug: {sub.get('detailPath')})")
    except Exception as e:
        print("Search Failed:", e)

if __name__ == "__main__":
    asyncio.run(test_search())
