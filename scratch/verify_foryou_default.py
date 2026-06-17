import asyncio
import sys

sys.stdout.reconfigure(encoding='utf-8')

import main

async def test_filter_endpoint(country, subject_type):
    payload = {
        "genre": "*",
        "country": country,
        "year": "*",
        "language": "*",
        "sort": "ForYou",
        "subjectType": subject_type,
        "page": 1,
        "perPage": 20
    }
    
    print(f"\n--- Testing {country} (subjectType: {subject_type}) ---")
    result = await main.filter_content(payload)
    items = result.get('data', {}).get('items', [])
    print(f"Total items returned: {len(items)}")
    
    for idx, item in enumerate(items[:5]):
        title = item.get('title')
        rating = float(item.get('imdbRatingValue') or 0.0)
        print(f"  {idx+1}. Rating: {rating} | Year: {item.get('releaseDate')} | Title: {title}")

async def run_all():
    await test_filter_endpoint("Bangladesh", 0)
    await test_filter_endpoint("India", 1)
    await test_filter_endpoint("United States", 1)

if __name__ == '__main__':
    asyncio.run(run_all())
