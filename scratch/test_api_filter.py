import asyncio
import json
import httpx

API_BASE = "https://h5-api.aoneroom.com"

async def main():
    async with httpx.AsyncClient(timeout=15.0) as client:
        # Get token
        r_tok = await client.get(f"{API_BASE}/wefeed-h5api-bff/auth/guest-token")
        tok_data = r_tok.json()
        token = tok_data.get("data", {}).get("token", "")
        headers = {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {token}" if token else ""
        }

        # Test A: Flat
        payload_a = {
            "tabId": 1,
            "genre": "ALL",
            "country": "ALL",
            "year": "ALL",
            "language": "ALL",
            "sort": "RECOMMEND",
            "page": 1,
            "perPage": 6
        }
        res_a = await client.post(f"{API_BASE}/wefeed-h5api-bff/subject/filter", headers=headers, json=payload_a)
        items_a = res_a.json().get("data", {}).get("items") or res_a.json().get("data", {}).get("subjects") or []
        print("=== Test A: Flat ===")
        print("Count:", len(items_a))
        for it in items_a[:4]:
            print(f"ID: {it.get('subjectId')}, Title: {repr(it.get('title'))}, Type: {it.get('subjectType')}, Genre: {it.get('genre')}")

        # Test B: Nested filter
        payload_b = {
            "tabId": 1,
            "filter": {
                "genre": "ALL",
                "country": "ALL",
                "year": "ALL",
                "language": "ALL",
                "sort": "RECOMMEND"
            },
            "page": 1,
            "perPage": 6
        }
        res_b = await client.post(f"{API_BASE}/wefeed-h5api-bff/subject/filter", headers=headers, json=payload_b)
        items_b = res_b.json().get("data", {}).get("items") or res_b.json().get("data", {}).get("subjects") or []
        print("\n=== Test B: Nested filter ===")
        print("Count:", len(items_b))
        for it in items_b[:4]:
            print(f"ID: {it.get('subjectId')}, Title: {repr(it.get('title'))}, Type: {it.get('subjectType')}, Genre: {it.get('genre')}")

        # Test C: Both tabId and channelId or type
        payload_c = {
            "channelId": 1,
            "tabId": 1,
            "page": 1,
            "perPage": 6,
            "filter": {
                "genre": "Action",
                "country": "ALL",
                "year": "ALL",
                "language": "ALL",
                "sort": "RECOMMEND"
            }
        }
        res_c = await client.post(f"{API_BASE}/wefeed-h5api-bff/subject/filter", headers=headers, json=payload_c)
        items_c = res_c.json().get("data", {}).get("items") or res_c.json().get("data", {}).get("subjects") or []
        print("\n=== Test C: Nested with Genre Action ===")
        print("Count:", len(items_c))
        for it in items_c[:4]:
            print(f"ID: {it.get('subjectId')}, Title: {repr(it.get('title'))}, Type: {it.get('subjectType')}, Genre: {it.get('genre')}")

asyncio.run(main())
