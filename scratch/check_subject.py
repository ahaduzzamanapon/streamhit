import asyncio
import httpx
import json

async def check():
    subject_id = "2675630074824959928"
    url = f"https://h5-api.aoneroom.com/wefeed-h5api-bff/detail?subjectId={subject_id}"
    headers = {
        "User-Agent": "com.community.oneroom/50020046 (Linux; U; Android 11; en_US; Redmi Note 10; Build/RP1A.200720.011; Cronet/135.0.7012.3)",
        "Accept": "application/json",
        "Accept-Language": "en-US,en;q=0.5",
        "X-Forwarded-For": "103.10.100.1",
        "CF-Connecting-IP": "103.10.100.1",
    }
    async with httpx.AsyncClient(trust_env=False, timeout=15.0) as client:
        resp = await client.get(url, headers=headers)
        print("Status:", resp.status_code)
        if resp.status_code == 200:
            data = resp.json()
            subject = data.get("data", {}).get("subject", {})
            resource = data.get("data", {}).get("resource", {})
            seasons = resource.get("seasons", [])
            print("Title:", subject.get("title"))
            print("Seasons count:", len(seasons))
            for s in seasons:
                se = s.get("se")
                max_ep = s.get("maxEp")
                print(f"  Season {se}: maxEp={max_ep}")
        else:
            print("Error body:", resp.text[:500])

asyncio.run(check())
