import asyncio
import os
import sys

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
import main

async def test_home():
    print("Testing updated homepage fetch in main.py...")
    try:
        data = await main._fetch_and_cache_remote_home(1, 0, "test_home_cache")
        if data:
            print("\nFetched and processed home data successfully!")
            items = data.get("data", {}).get("items", [])
            print(f"Total sections: {len(items)}")
            for idx, sec in enumerate(items[:6]):
                title = sec.get("title") or sec.get("name") or ""
                safe_title = title.encode('ascii', 'ignore').decode('ascii')
                print(f"  {idx+1}. title='{safe_title}', type={sec.get('type')}")
                if "subjects" in sec:
                    for s in sec.get("subjects", [])[:4]:
                        sub_title = s.get("title", "").encode('ascii', 'ignore').decode('ascii')
                        print(f"    - {sub_title} (ID: {s.get('subjectId')})")
        else:
            print("Failed to fetch home data.")
    except Exception as e:
        print("Error:", e)

if __name__ == "__main__":
    asyncio.run(test_home())
