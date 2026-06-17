import asyncio
import sys
import os
import urllib.parse
import json

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from main import request_h5_api

async def main():
    path_to_use = "game-of-thrones-AQP5Q48Qsq2"
    api_path = f"/wefeed-h5api-bff/detail?detailPath={urllib.parse.quote(path_to_use)}"
    
    print("Querying API path:", api_path)
    try:
        api_data = await request_h5_api("GET", api_path)
        print("API Response:")
        print(json.dumps(api_data, indent=2, ensure_ascii=True))
    except Exception as e:
        print("Error:", e)

if __name__ == "__main__":
    asyncio.run(main())
