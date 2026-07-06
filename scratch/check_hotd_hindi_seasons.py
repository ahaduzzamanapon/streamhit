import asyncio
import os
import sys
import json

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
import main

async def check_hotd():
    subject_id = "6269208467765429776"
    print(f"Checking H5 details for subject ID: {subject_id}...")
    try:
        data = await main.request_h5_api("GET", f"/wefeed-h5api-bff/detail?subjectId={subject_id}")
        subject_info = data.get("data", {}).get("subject", {})
        resource_info = data.get("data", {}).get("resource", {})
        
        print("\nTitle:", subject_info.get("title"))
        print("Dubs:")
        dubs = subject_info.get("dubs", [])
        for d in dubs:
            print(f"  - {d.get('lanName')} (ID: {d.get('subjectId')}, Slug: {d.get('detailPath')})")
        print("\nSeasons:")
        seasons = resource_info.get("seasons", [])
        for s in seasons:
            print(f"  - Season {s.get('se')}: {s.get('maxEp')} episodes")
    except Exception as e:
        print("Failed:", e)

if __name__ == "__main__":
    asyncio.run(check_hotd())
