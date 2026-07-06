import asyncio
import os
import sys
import json
import time
import httpx
import urllib.parse

guest_bearer_token = ""
guest_token_expiry = 0.0

async def get_guest_bearer_token() -> str:
    global guest_bearer_token, guest_token_expiry
    now = time.time()
    if guest_bearer_token and now < guest_token_expiry:
        return guest_bearer_token

    url = "https://h5-api.aoneroom.com/wefeed-h5api-bff/home?host=moviebox.ph"
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
        "Referer": "https://moviebox.ph/",
        "Origin": "https://moviebox.ph",
        "X-Client-Info": json.dumps({"timezone": "Asia/Dhaka"}),
        "X-Source": "",
        "sec-ch-ua": '"Chromium";v="148", "Google Chrome";v="148", "Not/A)Brand";v="99"',
        "sec-ch-ua-mobile": "?0",
        "sec-ch-ua-platform": '"Windows"',
        "sec-fetch-dest": "empty",
        "sec-fetch-mode": "cors",
        "sec-fetch-site": "same-origin",
    }

    try:
        async with httpx.AsyncClient(follow_redirects=True, timeout=15.0) as client:
            resp = await client.get(url, headers=headers)
            x_user = resp.headers.get("x-user")
            token = None
            if x_user:
                try:
                    token = json.loads(x_user).get("token")
                except Exception:
                    pass
            if not token:
                cookie = resp.headers.get("set-cookie", "")
                import re as _re
                m = _re.search(r"token=([^;]+)", cookie)
                if m:
                    token = m.group(1)
            
            if token:
                guest_bearer_token = token
                guest_token_expiry = now + 3600.0
                print(f"[API Auth] Acquired guest Bearer token successfully.")
                return guest_bearer_token
    except Exception as e:
        print(f"[API Auth] Failed to acquire token: {e}")
    return ""

async def test_slug_details():
    token = await get_guest_bearer_token()
    slug = "house-of-the-dragon-uTatZHG9Wo6"
    url = f"https://h5-api.aoneroom.com/wefeed-h5api-bff/detail?detailPath={urllib.parse.quote(slug)}"
    
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
        "Referer": "https://moviebox.ph/",
        "Origin": "https://moviebox.ph",
        "X-Client-Info": json.dumps({"timezone": "Asia/Dhaka"}),
        "X-Source": "",
        "sec-ch-ua": '"Chromium";v="148", "Google Chrome";v="148", "Not/A)Brand";v="99"',
        "sec-ch-ua-mobile": "?0",
        "sec-ch-ua-platform": '"Windows"',
        "sec-fetch-dest": "empty",
        "sec-fetch-mode": "cors",
        "sec-fetch-site": "same-origin",
        "Authorization": f"Bearer {token}" if token else ""
    }
    
    print("\nRequesting detail with guest bearer token...")
    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.get(url, headers=headers)
            print("Status Code:", resp.status_code)
            data = resp.json()
            subject = data.get("data", {}).get("subject", {})
            resource = data.get("data", {}).get("resource", {})
            print("Title:", subject.get("title"))
            print("Dubs:")
            for d in subject.get("dubs", []):
                print(f"  - {d.get('lanName')} (ID: {d.get('subjectId')})")
            print("Seasons:")
            for s in resource.get("seasons", []):
                print(f"  - Season {s.get('se')}: {s.get('maxEp')} episodes")
    except Exception as e:
        print("Failed details:", e)

if __name__ == "__main__":
    asyncio.run(test_slug_details())
