import asyncio
import os
import sys

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
import main

async def check_dubs():
    ids = ["6269208467765429776", "6642953700069752848", "6699502897346265280"]
    for sub_id in ids:
        print(f"\nChecking H5 details for subject ID: {sub_id}...")
        try:
            data = await main.request_h5_api("GET", f"/wefeed-h5api-bff/detail?subjectId={sub_id}")
            subject_info = data.get("data", {}).get("subject", {})
            resource_info = data.get("data", {}).get("resource", {})
            print("  Title:", subject_info.get("title"))
            print("  Detail Path:", subject_info.get("detailPath"))
            print("  Seasons:")
            seasons = resource_info.get("seasons", [])
            for s in seasons:
                print(f"    se: {s.get('se')}, maxEp: {s.get('maxEp')}")
        except Exception as e:
            print("  Failed:", e)

if __name__ == "__main__":
    asyncio.run(check_dubs())
