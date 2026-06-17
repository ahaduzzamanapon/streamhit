import httpx
import time
import sys

sys.stdout.reconfigure(encoding='utf-8')

def test_api(name, payload):
    start = time.time()
    try:
        resp = httpx.post("https://streamhit.lc-synergy.ltd/api/filter", json=payload, timeout=25.0)
        duration = time.time() - start
        items = resp.json().get('data', {}).get('items', [])
        print(f"{name} | Status: {resp.status_code} | Time: {duration:.2f}s | Items: {len(items)}")
        for idx, item in enumerate(items[:5]):
            print(f"  {idx+1}. Rating: {item.get('imdbRatingValue')} | Year: {item.get('releaseDate')} | Title: {item.get('title')}")
    except Exception as e:
        print(f"{name} failed: {e}")

if __name__ == '__main__':
    print("Testing production filter API...")
    test_api("Bangladesh", {"genre": "*", "country": "Bangladesh", "year": "*", "language": "*", "sort": "ForYou", "subjectType": 0, "page": 1, "perPage": 20})
    test_api("US Movies", {"genre": "*", "country": "United States", "year": "*", "language": "*", "sort": "ForYou", "subjectType": 1, "page": 1, "perPage": 20})
