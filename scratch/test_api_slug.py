import asyncio
import os
import sys
import json

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "Moviebox-API")))
import api

async def run_test():
    slugs = [
        "house-of-the-dragon-G7LGtmOdqc9",
        "house-of-the-dragon-hindi-6tfZtJy27t7"
    ]
    for slug in slugs:
        print(f"\n--- Fetching from Moviebox-API for slug: {slug} ---")
        try:
            res = await api.get_movie_detail(slug)
            print("Status Code / Output:", res.get("status") or "success")
            subject = res.get("data", {}).get("subject", {})
            resource = res.get("data", {}).get("resource", {})
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
    asyncio.run(run_test())
