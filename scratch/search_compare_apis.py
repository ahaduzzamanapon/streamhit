import asyncio
import os
import sys

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
import main

async def test_apis():
    hosts = [
        "https://h5-api.aoneroom.com",
        "https://api.inmoviebox.com",
        "https://api4.aoneroom.com",
        "https://api5.aoneroom.com"
    ]
    query = "House of the Dragon"
    
    for host in hosts:
        print(f"\n--- Testing Host: {host} ---")
        payload = {
            "keyword": query,
            "page": 1,
            "perPage": 10
        }
        try:
            # We use request_h5_api but overwrite host
            data = await main.request_h5_api("POST", "/wefeed-h5api-bff/subject/search", payload, host=host)
            results = data.get("data", {}).get("list", [])
            print(f"Found {len(results)} items:")
            for idx, item in enumerate(results):
                sub = item.get("subject", {})
                print(f"  {idx+1}. {sub.get('title')} (ID: {sub.get('subjectId')})")
        except Exception as e:
            print("Failed:", e)

if __name__ == "__main__":
    asyncio.run(test_apis())
