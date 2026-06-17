import asyncio
import sys
import os
import json

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from main import request_h5_api

async def test_filter(genre, country, year, language):
    payload = {
        "page": 1,
        "perPage": 20,
        "genre": genre,
        "country": country,
        "year": year,
        "language": language,
        "sort": "Latest",
        "subjectType": 1
    }
    print(f"Testing with genre='{genre}', country='{country}', year='{year}'")
    try:
        data = await request_h5_api("POST", "/wefeed-h5api-bff/subject/filter", payload)
        items = data.get("data", {}).get("items", [])
        if not items and data.get("data", {}).get("results"):
            results = data.get("data", {}).get("results", [])
            if results:
                items = results[0].get("subjects", [])
        print("  Status: Success")
        print("  Items count:", len(items))
        if items:
            print("  First item:", items[0].get("title"))
    except Exception as e:
        print("  Status: Failed -", e)

async def main():
    # Test empty strings (what scraper uses)
    await test_filter("", "", "", "")
    # Test asterisks
    await test_filter("*", "*", "*", "*")

if __name__ == "__main__":
    asyncio.run(main())
