import asyncio
import os
import sys
import json

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "Moviebox-API")))
import api

async def run_test():
    slug = "house-of-the-dragon-uTatZHG9Wo6"
    print(f"Fetching details from Moviebox-API for slug: {slug}...")
    try:
        res = await api.get_movie_detail(slug)
        print("Details:")
        print(json.dumps(res, indent=2))
    except Exception as e:
        print("Failed:", e)

if __name__ == "__main__":
    asyncio.run(run_test())
