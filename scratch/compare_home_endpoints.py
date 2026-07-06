import asyncio
import os
import sys
import json

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "Moviebox-API")))
import api
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
import main

async def test_comparison():
    print("=== FETCHING FROM Moviebox-API /home ===")
    try:
        home_data = await api.get_home()
        inner_data = home_data.get("data", {})
        sections = inner_data.get("operatingList", []) or inner_data.get("list", []) or []
        print(f"Found {len(sections)} sections in /home:")
        for idx, sec in enumerate(sections):
            print(f"  {idx+1}. type={sec.get('type')}, title='{sec.get('title')}'")
            # Print first 2 subjects if available
            subjects = sec.get("subjects", [])
            for s in subjects[:2]:
                print(f"    - Subject: {s.get('title')} (ID: {s.get('subjectId')})")
    except Exception as e:
        print("Failed /home:", e)

    print("\n=== FETCHING FROM main.py /tab-operating ===")
    try:
        data = await main.request_h5_api("GET", "/wefeed-h5api-bff/tab-operating?page=1&tabId=0")
        inner_data = data.get("data", {})
        sections = inner_data.get("operatingList", []) or inner_data.get("list", []) or []
        print(f"Found {len(sections)} sections in /tab-operating:")
        for idx, sec in enumerate(sections):
            print(f"  {idx+1}. type={sec.get('type')}, title='{sec.get('title')}'")
            subjects = sec.get("subjects", [])
            for s in subjects[:2]:
                print(f"    - Subject: {s.get('title')} (ID: {s.get('subjectId')})")
    except Exception as e:
        print("Failed /tab-operating:", e)

if __name__ == "__main__":
    asyncio.run(test_comparison())
