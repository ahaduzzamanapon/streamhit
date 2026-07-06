import asyncio
import os
import sys
import json
import httpx
import random

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
import main

def get_random_south_asian_ip():
    ranges = [
        ("103.242.21.5", "103.242.21."),
        ("103.95.96.15", "103.95.96."),
        ("45.127.244.12", "45.127.244."),
        ("103.108.140.5", "103.108.140.")
    ]
    base = random.choice(ranges)[1]
    return f"{base}{random.randint(2, 254)}"

async def test_ip_print():
    token = await main.get_guest_bearer_token()
    ip = get_random_south_asian_ip()
    
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
            for idx, sec in enumerate(sections[:10]):
                title = sec.get("title", "")
                safe_title = title.encode('ascii', 'ignore').decode('ascii')
                print(f"\nSection {idx+1}: {safe_title} (type={sec.get('type')})")
                subjects = sec.get("subjects", [])
                print(f"  Count: {len(subjects)}")
                for sub in subjects[:4]:
                    sub_title = sub.get("title", "").encode('ascii', 'ignore').decode('ascii')
                    print(f"    - {sub_title} (ID: {sub.get('subjectId')})")
    except Exception as e:
        print("Failed:", e)

if __name__ == "__main__":
    asyncio.run(test_ip_print())
