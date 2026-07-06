import asyncio
import os
import sys
import json
import httpx

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

SUBJECT_ID = "4360485895745717992"
DETAIL_PATH = "naruto-hindi-8iXhwtr47c5"
SE = 1
EP = 3

DEFAULT_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
    "Referer": "https://moviebox.ph/",
    "Origin": "https://moviebox.ph",
    "X-Client-Info": '{"timezone":"Asia/Dhaka"}',
    "Accept": "application/json",
    "Content-Type": "application/json",
}

PLAYER_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
    "Accept": "application/json",
    "X-Client-Info": '{"timezone":"Asia/Dhaka"}',
    "sec-fetch-dest": "empty",
    "sec-fetch-mode": "cors",
}

async def run():
    import main
    token = await main.get_guest_bearer_token()
    
    async with httpx.AsyncClient(follow_redirects=True, timeout=25) as client:
        # Step 1: Get player domain
        print("Getting player domain...")
        dom_resp = await client.get(
            "https://h5-api.aoneroom.com/wefeed-h5api-bff/media-player/get-domain",
            headers={**DEFAULT_HEADERS, "Authorization": f"Bearer {token}" if token else ""}
        )
        print(f"  Domain resp: {dom_resp.status_code}")
        dom_data = dom_resp.json()
        domain = dom_data.get("data", "https://netfilm.world")
        if isinstance(domain, dict):
            domain = domain.get("url", "https://netfilm.world")
        domain = domain.rstrip("/")
        print(f"  Player domain: {domain}")
        
        # Step 2: Fetch streams via play endpoint on the player domain
        player_referer = f"{domain}/spa/videoPlayPage/movies/{DETAIL_PATH}?id={SUBJECT_ID}&type=/movie/detail&detailSe={SE}&detailEp={EP}&lang=en"
        play_url = f"{domain}/wefeed-h5api-bff/subject/play?subjectId={SUBJECT_ID}&se={SE}&ep={EP}&detailPath={DETAIL_PATH}"
        
        print(f"\nFetching play streams from: {play_url}")
        play_resp = await client.get(play_url, headers={**PLAYER_HEADERS, "Referer": player_referer})
        print(f"  Play resp: {play_resp.status_code}")
        play_data = play_resp.json().get("data", {})
        
        streams = play_data.get("streams", [])
        hls = play_data.get("hls", [])
        has_resource = play_data.get("hasResource", False)
        
        print(f"  has_resource: {has_resource}")
        print(f"  streams count: {len(streams)}")
        print(f"  hls count: {len(hls)}")
        
        if streams:
            for s in streams:
                print(f"  -> {s.get('resolutions')}p {s.get('format')}: {s.get('url', '')[:80]}")
        if hls:
            for h in hls[:2]:
                print(f"  -> HLS: {h.get('url', '')[:80]}")
        
        if not streams and not hls:
            print("\n  Raw response:", json.dumps(play_data)[:400])

if __name__ == "__main__":
    asyncio.run(run())
