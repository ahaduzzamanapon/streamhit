import sys
import json
import httpx
import random

SINGAPORE_IP_RANGES = [
    ((1, 21, 224, 0), (1, 21, 255, 255)),
    ((1, 32, 128, 0), (1, 32, 191, 255)),
    ((101, 100, 160, 0), (101, 100, 255, 255)),
    ((101, 127, 0, 0), (101, 127, 255, 255)),
    ((101, 32, 104, 0), (101, 32, 175, 255)),
    ((103, 1, 136, 0), (103, 1, 139, 255)),
    ((103, 10, 100, 0), (103, 10, 103, 255)),
    ((103, 11, 188, 0), (103, 11, 191, 255)),
    ((103, 14, 212, 0), (103, 14, 215, 255)),
    ((103, 15, 100, 0), (103, 15, 103, 255)),
]

def ip_to_long(ip):
    return (ip[0] << 24) | (ip[1] << 16) | (ip[2] << 8) | ip[3]

def long_to_ip(long):
    return f"{(long >> 24) & 255}.{(long >> 16) & 255}.{(long >> 8) & 255}.{long & 255}"

def get_random_singapore_ip():
    start_ip, end_ip = random.choice(SINGAPORE_IP_RANGES)
    random_long = random.randint(ip_to_long(start_ip), ip_to_long(end_ip))
    return long_to_ip(random_long)

async def main():
    ip = get_random_singapore_ip()
    headers = {
        "X-Forwarded-For": ip,
        "CF-Connecting-IP": ip,
        "X-Real-IP": ip,
        "X-Client-Info": json.dumps({"timezone": "Africa/Nairobi"}),
        "Accept-Language": "en-US,en;q=0.5",
        "Accept": "application/json",
        "User-Agent": "okhttp/4.12.0",
        "Referer": "https://h5.aoneroom.com"
    }
    
    async with httpx.AsyncClient(trust_env=False, timeout=15.0) as client:
        # Get cookies
        cookie_resp = await client.get("https://h5.aoneroom.com/wefeed-h5-bff/app/get-latest-app-pkgs?app_name=moviebox", headers=headers)
        cookies = "; ".join([c.split(";")[0] for c in cookie_resp.headers.get_list("Set-Cookie")])
        headers["Cookie"] = cookies
        
        # Search for "Wednesday" (Season 1)
        search_resp = await client.post("https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/search", json={
            "keyword": "Wednesday", "page": 1, "perPage": 10, "subjectType": 2
        }, headers=headers)
        items = search_resp.json().get("data", {}).get("items", [])
        
        # Test tab-operating on H5 BFF
        try:
            tab_resp = await client.get("https://h5.aoneroom.com/wefeed-h5-bff/tab-operating?page=1&tabId=0", headers=headers)
            print(f"H5 BFF tab-operating status: {tab_resp.status_code}")
            if tab_resp.status_code == 200:
                print(f"H5 BFF tab-operating success! Keys: {list(tab_resp.json().get('data', {}).keys())}")
        except Exception as e:
            print(f"H5 BFF tab-operating request failed: {e}")

        # Test tab-operating on H5api BFF
        try:
            tab_resp2 = await client.get("https://h5-api.aoneroom.com/wefeed-h5api-bff/tab-operating?page=1&tabId=0", headers=headers)
            print(f"H5api BFF tab-operating status: {tab_resp2.status_code}")
            if tab_resp2.status_code == 200:
                tab_json = tab_resp2.json()
                print(f"H5api BFF tab-operating success! Keys: {list(tab_json.get('data', {}).keys())}")
                with open("scratch_tab_operating.json", "w", encoding="utf-8") as f:
                    json.dump(tab_json, f, indent=2)
                print("Wrote tab_operating JSON to scratch_tab_operating.json")
        except Exception as e:
            print(f"H5api BFF tab-operating request failed: {e}")
        
        # We look for "Wednesday" (not "Wednesday S2")
        wednesday_item = None
        for item in items:
            if item.get("title") == "Wednesday":
                wednesday_item = item
                break
        if not wednesday_item and items:
            wednesday_item = items[0]
            
        if wednesday_item:
            print(f"Targeting: {wednesday_item.get('title')} (Path={wednesday_item.get('detailPath')})")
            
            # Test detail by detailPath
            detail_resp = await client.get(f"https://h5-api.aoneroom.com/wefeed-h5api-bff/detail?detailPath={wednesday_item.get('detailPath')}", headers=headers)
            print(f"Detail by path status: {detail_resp.status_code}")

            # Test detail by subjectId
            subject_id = wednesday_item.get('subjectId')
            detail_by_id_resp = await client.get(f"https://h5-api.aoneroom.com/wefeed-h5api-bff/detail?subjectId={subject_id}", headers=headers)
            print(f"Detail by ID status: {detail_by_id_resp.status_code}")
            if detail_by_id_resp.status_code == 200:
                print(f"Detail by ID success! Keys: {list(detail_by_id_resp.json().get('data', {}).get('subject', {}).keys())}")
            
            detail_data = detail_resp.json()
            with open("scratch_detail.json", "w", encoding="utf-8") as f:
                json.dump(detail_data, f, indent=2)
            print("Successfully wrote detail data to scratch_detail.json")
        else:
            print("No items found.")

if __name__ == "__main__":
    import asyncio
    asyncio.run(main())
