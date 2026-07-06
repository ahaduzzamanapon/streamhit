import asyncio
import os
import sys
import json

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
import main

async def compare_raw():
    try:
        print("=== Raw /home?host=moviebox.ph ===")
        # Get from H5 API
        h_data = await main.request_h5_api("GET", "/wefeed-h5api-bff/home?host=moviebox.ph")
        h_list = h_data.get("data", {}).get("operatingList", [])
        print(f"Total sections: {len(h_list)}")
        for idx, sec in enumerate(h_list[:5]):
            title = sec.get("title")
            sub_count = len(sec.get("subjects", []))
            print(f"  {idx+1}. title={title}, subjects count={sub_count}")
            for sub in sec.get("subjects", [])[:3]:
                print(f"    - {sub.get('title')} (ID: {sub.get('subjectId')})")
    except Exception as e:
        print("Failed /home:", e)

    try:
        print("\n=== Raw /tab-operating?page=1&tabId=0 ===")
        t_data = await main.request_h5_api("GET", "/wefeed-h5api-bff/tab-operating?page=1&tabId=0")
        t_list = t_data.get("data", {}).get("operatingList", [])
        print(f"Total sections: {len(t_list)}")
        for idx, sec in enumerate(t_list[:5]):
            title = sec.get("title")
            sub_count = len(sec.get("subjects", []))
            print(f"  {idx+1}. title={title}, subjects count={sub_count}")
            for sub in sec.get("subjects", [])[:3]:
                print(f"    - {sub.get('title')} (ID: {sub.get('subjectId')})")
    except Exception as e:
        print("Failed /tab-operating:", e)

if __name__ == "__main__":
    asyncio.run(compare_raw())
