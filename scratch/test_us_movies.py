import httpx
import urllib.parse
import sys

sys.stdout.reconfigure(encoding='utf-8')

url = "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/filter"

sorts = ["ForYou", "Latest", "Rating"]

for sort in sorts:
    payload = {
        "page": 1,
        "perPage": 20,
        "genre": "",
        "country": "United States",
        "year": "",
        "language": "",
        "sort": sort,
        "subjectType": 1 # Movie
    }

    proxied_url = f"http://194.127.178.223/?url={urllib.parse.quote(url)}"

    try:
        resp = httpx.post(proxied_url, json=payload, timeout=15.0)
        data = resp.json()
        items = data.get("data", {}).get("items", [])
        if not items and data.get("data", {}).get("results"):
            items = data.get("data", [{}])["results"][0].get("subjects", [])
        
        movie_count = sum(1 for item in items if int(item.get("subjectType", 0)) == 1)
        tv_count = sum(1 for item in items if int(item.get("subjectType", 0)) == 2)
        print(f"\nSort: {sort} | Total: {len(items)} | Movies: {movie_count} | TV Shows: {tv_count}")
        for idx, item in enumerate(items[:5]):
            print(f"  {idx+1}. Type: {item.get('subjectType')} | Title: {item.get('title')}")
    except Exception as e:
        print(f"Error for {sort}: {e}")
