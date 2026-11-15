import os
import re
import json
import requests
import concurrent.futures
from datetime import datetime
import pytz

# Bangladesh Timezone
tz_bd = pytz.timezone("Asia/Dhaka")
current_time = datetime.now(tz_bd).strftime("%I:%M %p %d-%m-%Y BST")

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Accept": "*/*"
}

SOURCES = [
    ("Bangladesh", "https://iptv-org.github.io/iptv/countries/bd.m3u"),
    ("India Sports & Kids", "https://iptv-org.github.io/iptv/countries/in.m3u"),
    ("Sports HD", "https://iptv-org.github.io/iptv/categories/sports.m3u")
]

BAD_KEYWORDS = [
    "vod", "test", "demo", "webcam", "radio", "podcast", "preview", "sample", "timeshift"
]

SPORTS_KEYWORDS = [
    "sport", "sports", "cricket", "football", "ten sports", "sony sports", "star sports",
    "t sports", "willow", "euro sport", "golf", "racing", "espn", "bein"
]

KIDS_KEYWORDS = [
    "kids", "cartoon", "pogo", "disney", "nick", "anime", "toon", "sonic"
]

def parse_m3u(content, source_name):
    items = []
    lines = content.splitlines()
    i = 0
    while i < len(lines):
        line = lines[i].strip()
        if line.startswith("#EXTINF:"):
            info = line
            # Extract name (everything after the last comma)
            name = info.split(",")[-1].strip() if "," in info else "Channel"

            # Extract tvg-logo
            logo_match = re.search(r'tvg-logo="([^"]*)"', info)
            logo = logo_match.group(1).strip() if logo_match else ""

            # Extract group-title
            group_match = re.search(r'group-title="([^"]*)"', info)
            group = group_match.group(1).strip() if group_match else ""

            # Extract tvg-id
            id_match = re.search(r'tvg-id="([^"]*)"', info)
            tvg_id = id_match.group(1).strip() if id_match else ""

            # Next non-empty, non-comment line is the URL
            stream_url = ""
            j = i + 1
            while j < len(lines):
                next_line = lines[j].strip()
                if next_line and not next_line.startswith("#"):
                    stream_url = next_line
                    i = j
                    break
                j += 1

            if stream_url and (stream_url.startswith("http://") or stream_url.startswith("https://")):
                # Check bad words
                lower_name = name.lower()
                lower_url = stream_url.lower()
                if any(bw in lower_name or bw in lower_url for bw in BAD_KEYWORDS):
                    i += 1
                    continue

                # Category classification
                category = "General"
                if source_name == "Bangladesh":
                    category = "Bangladesh"
                elif any(sk in lower_name for sk in SPORTS_KEYWORDS) or "sport" in group.lower():
                    category = "Sports"
                elif any(kk in lower_name for kk in KIDS_KEYWORDS) or "kids" in group.lower() or "animation" in group.lower():
                    category = "Kids"
                elif source_name == "India Sports & Kids":
                    # Indian source: filter out non-sports/kids
                    i += 1
                    continue

                items.append({
                    "id": tvg_id or re.sub(r'[^a-zA-Z0-9]', '', name.lower()),
                    "name": name,
                    "category": category,
                    "logo": logo,
                    "stream_url": stream_url,
                    "headers": {
                        "User-Agent": HEADERS["User-Agent"]
                    }
                })
        i += 1
    return items

def test_stream_alive(channel):
    url = channel["stream_url"]
    try:
        # First attempt with fast HEAD request
        res = requests.head(url, headers=HEADERS, timeout=3.5, allow_redirects=True)
        if res.status_code in [200, 206, 302]:
            return channel
    except Exception:
        pass

    try:
        # Second attempt with GET stream range (just first 512 bytes)
        range_headers = dict(HEADERS)
        range_headers["Range"] = "bytes=0-512"
        res = requests.get(url, headers=range_headers, timeout=4, stream=True, allow_redirects=True)
        if res.status_code in [200, 206, 302]:
            return channel
    except Exception:
        pass

    return None

def main():
    print("=" * 60)
    print("  All-in-One Live TV & Sports Fetcher & Validator")
    print(f"  Execution Time: {current_time}")
    print("=" * 60)

    raw_channels = []
    seen_urls = set()

    for src_name, src_url in SOURCES:
        try:
            print(f"Fetching {src_name} from {src_url}...")
            r = requests.get(src_url, headers=HEADERS, timeout=15)
            r.raise_for_status()
            parsed = parse_m3u(r.text, src_name)
            print(f" -> Found {len(parsed)} candidate channels.")

            for ch in parsed:
                u = ch["stream_url"]
                if u not in seen_urls:
                    seen_urls.add(u)
                    raw_channels.append(ch)
        except Exception as e:
            print(f"Error fetching {src_name}: {e}")

    print(f"\nTotal unique channels to probe: {len(raw_channels)}")
    print("Starting multi-threaded stream live health probing (timeout: 4s)...")

    alive_channels = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=25) as executor:
        future_to_ch = {executor.submit(test_stream_alive, ch): ch for ch in raw_channels}
        for future in concurrent.futures.as_completed(future_to_ch):
            res = future.result()
            if res:
                alive_channels.append(res)
                print(f"  [ONLINE] {res['name'][:35]} ({res['category']})")

    # Sort channels by category, then by name
    alive_channels.sort(key=lambda x: (x["category"] != "Bangladesh", x["category"] != "Sports", x["category"], x["name"]))

    print(f"\n✅ Probing Complete: {len(alive_channels)} / {len(raw_channels)} channels are 100% ONLINE!")

    # Group categories
    categories = list(dict.fromkeys(ch["category"] for ch in alive_channels))

    output_data = {
        "playlist_name": "All-in-One Live TV & Sports",
        "total_channels": len(alive_channels),
        "last_updated": current_time,
        "categories": categories,
        "channels": alive_channels
    }

    # Save outputs to root and public/
    out_dirs = [".", "public"]
    for d in out_dirs:
        os.makedirs(d, exist_ok=True)
        json_path = os.path.join(d, "live_channels.json")
        m3u_path = os.path.join(d, "live_channels.m3u")

        # Save JSON
        with open(json_path, "w", encoding="utf-8") as f:
            json.dump(output_data, f, indent=2, ensure_ascii=False)

        # Save M3U
        m3u_lines = ["#EXTM3U\n"]
        m3u_lines.append(f"# Playlist Name  : All-in-One Live TV & Sports\n")
        m3u_lines.append(f"# Total Channels : {len(alive_channels)}\n")
        m3u_lines.append(f"# Last Updated   : {current_time}\n\n")

        for ch in alive_channels:
            m3u_lines.append(f'#EXTINF:-1 tvg-id="{ch["id"]}" tvg-name="{ch["name"]}" tvg-logo="{ch["logo"]}" group-title="{ch["category"]}",{ch["name"]}\n')
            m3u_lines.append(f'{ch["stream_url"]}\n')

        with open(m3u_path, "w", encoding="utf-8") as f:
            f.writelines(m3u_lines)

    print("\n🎉 Files successfully generated:")
    print(" - live_channels.json & public/live_channels.json")
    print(" - live_channels.m3u & public/live_channels.m3u")

if __name__ == "__main__":
    main()

