import sys
import json
import time
import httpx
import random

# Singapore IP Ranges from the Go code
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
    start_long = ip_to_long(start_ip)
    end_long = ip_to_long(end_ip)
    random_long = random.randint(start_long, end_long)
    return long_to_ip(random_long)

async def test_api():
    ip = get_random_singapore_ip()
    print(f"Using Singapore IP: {ip}")

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

    cookies = ""
    # 1. Refresh cookies
    async with httpx.AsyncClient(trust_env=False, timeout=15.0) as client:
        cookie_url = "https://h5.aoneroom.com/wefeed-h5-bff/app/get-latest-app-pkgs?app_name=moviebox"
        try:
            resp = await client.get(cookie_url, headers=headers)
            print(f"Cookie request status: {resp.status_code}")
            
            cookie_headers = resp.headers.get_list("Set-Cookie")
            parsed_cookies = []
            for cookie in cookie_headers:
                part = cookie.split(";")[0]
                parsed_cookies.append(part)
            cookies = "; ".join(parsed_cookies)
            print(f"Acquired Cookies: {cookies}")
        except Exception as e:
            print(f"Cookie fetch failed: {e}")
            return

    if not cookies:
        print("Failed to get cookies!")
        return

    # Add cookies to headers
    headers["Cookie"] = cookies

    # 2. Search Wednesday
    search_url = "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/search"
    payload = {"keyword": "Wednesday", "page": 1, "perPage": 10, "subjectType": 2}
    
    async with httpx.AsyncClient(trust_env=False, timeout=15.0) as client:
        try:
            resp = await client.post(search_url, json=payload, headers=headers)
            print(f"Search request status: {resp.status_code}")
            search_data = resp.json()
            items = search_data.get("data", {}).get("items", [])
            print(f"Found {len(items)} search items.")
            if items:
                first_item = items[0]
                print(f"First Item: ID={first_item.get('subjectId')}, Title={first_item.get('title')}, Path={first_item.get('detailPath')}")
                
                # 3. Get Details
                detail_path = first_item.get('detailPath')
                detail_url = f"https://h5-api.aoneroom.com/wefeed-h5api-bff/detail?detailPath={detail_path}"
                detail_resp = await client.get(detail_url, headers=headers)
                print(f"Detail request status: {detail_resp.status_code}")
                detail_data = detail_resp.json()
                
                # Print details
                subject_info = detail_data.get("data", {}).get("subject", {})
                print(f"Subject Info Keys: {list(subject_info.keys())}")
                print(f"Dubs: {json.dumps(subject_info.get('dubs'), ensure_ascii=True)}")
                print(f"Season Info: {json.dumps(subject_info.get('season'), ensure_ascii=True)}")
                print(f"Subtitles: {json.dumps(subject_info.get('subtitles'), ensure_ascii=True)}")
                
                # 4. Test Mobile BFF season-info endpoint without signature
                subject_id = first_item.get('subjectId')
                season_url = f"https://api6.aoneroom.com/wefeed-mobile-bff/subject-api/season-info?subjectId={subject_id}"
                
                try:
                    season_resp = await client.get(season_url, headers=headers)
                    print(f"Mobile season-info status (no signature): {season_resp.status_code}")
                    print(f"Mobile season-info response: {json.dumps(season_resp.json(), ensure_ascii=True)[:500]}")
                except Exception as e:
                    print(f"Mobile season-info request failed: {e}")

                # 5. Get Download Resources
                download_url = f"https://h5.aoneroom.com/wefeed-h5-bff/web/subject/download?subjectId={subject_id}&se=1&ep=1"
                
                dl_headers = headers.copy()
                dl_headers["Referer"] = f"https://123movienow.cc/spa/videoPlayPage/movies/{detail_path}?id={subject_id}&type=/movie/detail"
                dl_headers["Origin"] = "https://123movienow.cc"
                
                dl_resp = await client.get(download_url, headers=dl_headers)
                print(f"Download request status: {dl_resp.status_code}")
                dl_data = dl_resp.json()
                inner_data = dl_data.get("data", {})
                print(f"Downloads count: {len(inner_data.get('downloads', []))}")
                print(f"Captions count: {len(inner_data.get('captions', []))}")
                if inner_data.get('downloads'):
                    print(f"Sample download resource: {json.dumps(inner_data['downloads'][0], ensure_ascii=True)}")
                if inner_data.get('captions'):
                    print(f"Sample caption: {json.dumps(inner_data['captions'][0], ensure_ascii=True)}")
        except Exception as e:
            print(f"API operation failed: {e}")

if __name__ == "__main__":
    import asyncio
    asyncio.run(test_api())
