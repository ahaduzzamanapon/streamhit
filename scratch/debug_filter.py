import asyncio
import json
import httpx
from main import _make_request, API_BASE

async def test_filter_payloads():
    # Test 1: What main.py currently does:
    # payload = {"tabId": 1, "genre": "ALL", "country": "ALL", "year": "ALL", "language": "ALL", "sort": "RECOMMEND", "page": 1, "perPage": 10}
    # Notice tabId = 1 returned subjectType = 9!
    
    # What if tabId is different? E.g. tabId = 0? tabId = 2? tabId = "movie"?
    # What if type or subjectType is in payload?
    # What if filter is a nested object: {"tabId": 1, "filter": {"genre": "ALL", ...}}?
    # What if channelId is specified?
    
    test_cases = [
        {"name": "Current main.py (tabId=1 flat)", "payload": {"tabId": 1, "genre": "ALL", "country": "ALL", "year": "ALL", "language": "ALL", "sort": "RECOMMEND", "page": 1, "perPage": 5}},
        {"name": "tabId=0 flat", "payload": {"tabId": 0, "genre": "ALL", "country": "ALL", "year": "ALL", "language": "ALL", "sort": "RECOMMEND", "page": 1, "perPage": 5}},
        {"name": "Nested filter tabId=1", "payload": {"tabId": 1, "page": 1, "perPage": 5, "filter": {"genre": "ALL", "country": "ALL", "year": "ALL", "language": "ALL", "sort": "RECOMMEND"}}},
        {"name": "Nested filter tabId=0", "payload": {"tabId": 0, "page": 1, "perPage": 5, "filter": {"genre": "ALL", "country": "ALL", "year": "ALL", "language": "ALL", "sort": "RECOMMEND"}}},
        {"name": "With subjectType=1 flat", "payload": {"subjectType": 1, "tabId": 1, "genre": "ALL", "country": "ALL", "year": "ALL", "language": "ALL", "sort": "RECOMMEND", "page": 1, "perPage": 5}},
        {"name": "With type=1 flat", "payload": {"type": 1, "tabId": 1, "genre": "ALL", "country": "ALL", "year": "ALL", "language": "ALL", "sort": "RECOMMEND", "page": 1, "perPage": 5}},
        {"name": "With channelId=1", "payload": {"channelId": 1, "tabId": 1, "genre": "ALL", "country": "ALL", "year": "ALL", "language": "ALL", "sort": "RECOMMEND", "page": 1, "perPage": 5}},
        {"name": "With channelId=2", "payload": {"channelId": 2, "tabId": 1, "genre": "ALL", "country": "ALL", "year": "ALL", "language": "ALL", "sort": "RECOMMEND", "page": 1, "perPage": 5}},
        {"name": "With channelId='movie'", "payload": {"channelId": "movie", "tabId": 1, "genre": "ALL", "country": "ALL", "year": "ALL", "language": "ALL", "sort": "RECOMMEND", "page": 1, "perPage": 5}},
        {"name": "Nested with genre=ACTION", "payload": {"tabId": 1, "page": 1, "perPage": 5, "filter": {"genre": "ACTION", "country": "ALL", "year": "ALL", "language": "ALL", "sort": "RECOMMEND"}}},
        {"name": "Flat with genre=ACTION", "payload": {"tabId": 1, "genre": "ACTION", "country": "ALL", "year": "ALL", "language": "ALL", "sort": "RECOMMEND", "page": 1, "perPage": 5}},
    ]

    for tc in test_cases:
        try:
            url = f"{API_BASE}/wefeed-h5api-bff/subject/filter"
            res = await _make_request(url, method="POST", payload=tc["payload"])
            items = res.get("data", {}).get("items") or res.get("data", {}).get("subjects") or []
            print(f"=== {tc['name']} ===")
            print(f"Items count: {len(items)}")
            for it in items[:3]:
                t = it.get("title", "")
                st = it.get("subjectType")
                g = it.get("genre")
                print(f"   [Type {st}] Title: {repr(t)[:40]} | Genre: {g}")
        except Exception as e:
            print(f"=== {tc['name']} === ERROR: {e}")

asyncio.run(test_filter_payloads())
