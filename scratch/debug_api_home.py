import asyncio
import os
import sys
import json

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "Moviebox-API")))
import api

async def debug_home():
    print("Requesting Moviebox-API /home...")
    try:
        res = await api.get_home()
        print("Raw Response Keys:", list(res.keys()))
        print("Data Keys:", list(res.get("data", {}).keys()) if isinstance(res.get("data"), dict) else type(res.get("data")))
        print("Full JSON:")
        print(json.dumps(res, indent=2)[:1000])
    except Exception as e:
        print("Error:", e)

if __name__ == "__main__":
    asyncio.run(debug_home())
