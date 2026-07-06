import asyncio
import os
import sys
import json
import httpx

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
import main

async def test_search_all():
    token = await main.get_guest_bearer_token()
    query = "House of the Dragon"
    print(f"Searching API for '{query}'...")
    
    url = "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/search"
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
        "Referer": "https://moviebox.ph/",
        "Origin": "https://moviebox.ph",
        "X-Client-Info": json.dumps({"timezone": "Asia/Dhaka"}),
        "Authorization": f"Bearer {token}" if token else ""
    }
    
    payload = {
        "keyword": query,
        "page": 1,
        "perPage": 50,
        "subjectType": 0
    }
    
    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.post(url, headers=headers, json=payload)
            data = resp.json()
            items = data.get("data", {}).get("items") or data.get("data", {}).get("list") or []
            print(f"Found {len(items)} items:")
            for idx, item in enumerate(items):
                sub = item.get("subject") or item
                title = sub.get("title", "").encode('ascii', 'ignore').decode('ascii')
                print(f"  {idx+1}. {title} (ID: {sub.get('subjectId')}, Slug: {sub.get('detailPath')})")
    except Exception as e:
        print("Failed:", e)

if __name__ == "__main__":
    asyncio.run(test_search_all())
