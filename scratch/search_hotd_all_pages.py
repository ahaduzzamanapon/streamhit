import asyncio
import os
import sys

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "Moviebox-API")))
import api

async def scan_pages():
    query = "House of the Dragon"
    print(f"Scanning multiple search result pages for '{query}'...")
    
    for page in range(1, 6):
        print(f"\n--- PAGE {page} ---")
        try:
            res = await api.search(query, page=page)
            items = res.get("items", [])
            print(f"Found {len(items)} items:")
            for item in items:
                print(f"  - {item.get('name')} (ID: {item.get('subject_id')}, Slug: {item.get('slug')})")
            if not items:
                break
        except Exception as e:
            print("Failed:", e)
            break

if __name__ == "__main__":
    asyncio.run(scan_pages())
