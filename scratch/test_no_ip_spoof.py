import asyncio
import os
import sys
import json
import httpx
import urllib.parse

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
import main

async def test_no_spoof():
    token = await main.get_guest_bearer_token()
    print("Testing detail page without IP spoofing...")
    
    slug = "house-of-the-dragon-hindi-6tfZtJy27t7"
    url = f"https://h5-api.aoneroom.com/wefeed-h5api-bff/detail?detailPath={urllib.parse.quote(slug)}"
    
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
        "Referer": "https://moviebox.ph/",
        "Origin": "https://moviebox.ph",
        "X-Client-Info": json.dumps({"timezone": "Asia/Dhaka"}),
        "Authorization": f"Bearer {token}" if token else ""
    }
    
    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.get(url, headers=headers)
            print("Status:", resp.status_code)
            data = resp.json()
            subject = data.get("data", {}).get("subject", {})
            resource = data.get("data", {}).get("resource", {})
            print("Title:", subject.get("title"))
            print("Dubs:")
            for d in subject.get("dubs", []):
                print(f"  - {d.get('lanName')} (ID: {d.get('subjectId')})")
            print("Seasons:")
            for s in resource.get("seasons", []):
                print(f"  - Season {s.get('se')}: {s.get('maxEp')} episodes")
    except Exception as e:
        print("Failed:", e)

if __name__ == "__main__":
    asyncio.run(test_no_spoof())
