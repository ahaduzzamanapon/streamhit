import asyncio
import os
import sys
import json

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "Moviebox-API")))
import api

async def scan_all():
    print("Searching for 'House of the Dragon'...")
    res = await api.search("House of the Dragon")
    items = res.get("items", [])
    print(f"Found {len(items)} items in search results.")
    
    for item in items:
        name = item.get("name")
        sub_id = item.get("subject_id")
        slug = item.get("slug")
        print(f"\n--- Checking detail for: {name} (ID: {sub_id}, Slug: {slug}) ---")
        try:
            detail = await api.get_movie_detail(slug)
            subject = detail.get("data", {}).get("subject", {})
            resource = detail.get("data", {}).get("resource", {})
            
            print("  Title:", subject.get("title"))
            print("  Dubs in Detail:")
            dubs = subject.get("dubs", [])
            for d in dubs:
                print(f"    - {d.get('lanName')} (ID: {d.get('subjectId')}, Slug: {d.get('detailPath')})")
                
            print("  Seasons:")
            seasons = resource.get("seasons", [])
            for s in seasons:
                print(f"    - Season {s.get('se')}: {s.get('maxEp')} episodes")
        except Exception as e:
            print("  Failed to check details:", e)

if __name__ == "__main__":
    asyncio.run(scan_all())
