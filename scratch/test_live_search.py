import asyncio
import os
import sys
import json

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "Moviebox-API")))
import api

async def run_test():
    print("Testing Moviebox-API search logic for 'House of the Dragon'...")
    try:
        res = await api.search("House of the Dragon")
        print("Search Results:")
        print(json.dumps(res, indent=2))
    except Exception as e:
        import traceback
        traceback.print_exc()

if __name__ == "__main__":
    asyncio.run(run_test())
