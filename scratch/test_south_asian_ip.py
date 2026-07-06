import asyncio
import os
import sys
import json
import httpx
import random

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
import main

def get_random_south_asian_ip():
    # BD/India IP ranges
    ranges = [
        ("103.242.21.5", "103.242.21."),
        ("103.95.96.15", "103.95.96."),
        ("45.127.244.12", "45.127.244."),
        ("103.108.140.5", "103.108.140.")
    ]
    base = random.choice(ranges)[1]
    return f"{base}{random.randint(2, 254)}"

async def test_ip():
    token = await main.get_guest_bearer_token()
    ip = get_random_south_asian_ip()
    print("Testing with South Asian IP:", ip)
    
    url = "https://h5-api.aoneroom.com/wefeed-h5api-bff/home?host=moviebox.ph"
    headers = {
        "X-Forwarded-For": ip,
        "CF-Connecting-IP": ip,
        "X-Real-IP": ip,
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
        "Referer": "https://moviebox.ph/",
        "Origin": "https://moviebox.ph",
        "X-Client-Info": json.dumps({"timezone": "Asia/Dhaka"}),
        "Authorization": f"Bearer {token}" if token else ""
    }
    
    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.get(url, headers=headers)
            data = resp.json()
            sections = data.get("data", {}).get("operatingList", [])
            print(f"Total sections returned: {len(sections)}")
            
            # Print Popular Series items
            for sec in sections:
                if sec.get("title") == "Popular Series":
                    print("\nPopular Series items:")
                    for sub in sec.get("subjects", [])[:5]:
                        print(f"  - {sub.get('title')} (ID: {sub.get('subjectId')})")
    except Exception as e:
        print("Failed:", e)

if __name__ == "__main__":
    asyncio.run(test_ip())
