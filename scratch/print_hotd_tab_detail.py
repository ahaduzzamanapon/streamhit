import asyncio
import os
import sys
import json

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
import main

async def print_hotd():
    print("Fetching tab-operating...")
    try:
        data = await main.request_h5_api("GET", "/wefeed-h5api-bff/tab-operating?page=1&tabId=0")
        inner_data = data.get("data", {})
        sections = inner_data.get("operatingList", []) or inner_data.get("list", []) or []
        for sec in sections:
            subjects = sec.get("subjects", [])
            for s in subjects:
                if str(s.get("subjectId")) == "5373384118887662624" or "Dragon" in s.get("title", ""):
                    print("Found subject in tab-operating:")
                    print(json.dumps(s, indent=2))
    except Exception as e:
        print("Failed:", e)

if __name__ == "__main__":
    asyncio.run(print_hotd())
