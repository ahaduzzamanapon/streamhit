import asyncio
import json
from main import request_h5_api

async def search_and_dump(title):
    print(f"\n--- Searching for: {title} ---")
    try:
        payload = {
            "keyword": title,
            "page": 1,
            "perPage": 5,
            "subjectType": 0
        }
        res = await request_h5_api("POST", "/wefeed-h5api-bff/subject/search", payload)
        items = res.get("data", {}).get("items", [])
        if not items:
            print("No items found.")
            return
            
        item = items[0]
        sub_id = item.get("subjectId")
        print(f"Found ID: {sub_id}, Title: {item.get('title')}")
        
        detail_res = await request_h5_api("GET", f"/wefeed-h5api-bff/detail?subjectId={sub_id}")
        subject = detail_res.get("data", {}).get("subject", {})
        
        # Print relevant metadata for comparison
        print(json.dumps({
            "subjectType": subject.get("subjectType"),
            "genre": subject.get("genre"),
            "staffList": subject.get("staffList", []),
            "duration": subject.get("duration"),
            "isCam": subject.get("isCam"),
            "countryName": subject.get("countryName"),
            "postTitle": subject.get("postTitle"),
            "dubs": [d.get("lanName") for d in subject.get("dubs", [])]
        }, indent=2))
        
    except Exception as e:
        print(f"Error: {e}")

async def main():
    await search_and_dump("Challenger Coaches the WORST PLAYER in League of Legends")
    await search_and_dump("Bloodhounds")
    await search_and_dump("Haunted")

if __name__ == "__main__":
    asyncio.run(main())