import os
import sys
import json
import update_playlist

# Mock datasets
mock_tapmad = [
    {
        "EntityId": "1001",
        "VideoName": "Bangladesh vs Sri Lanka 1st ODI",
        "CategoryName": "Cricket",
        "StageName": "Live Match",
        "ThumbnailTV": "https://img.tapmad.com/bd_sl.jpg",
        "StreamUrl": "https://live.tapmad.com/live/stream1/master.m3u8",
        "Status": "Live",
        "EventStartDate": "12/11/2026 02:30 PM IST"
    }
]

mock_sonyliv = [
    {
        "contentId": "2001",
        "video_info": {
            "title": "Real Madrid vs Barcelona",
            "episodeTitle": "El Clasico 2026",
            "longDescription": "Live Spanish La Liga Clash"
        },
        "stream_info": {
            "dai_url": "https://live.sonyliv.com/live/ch1/master.mpd"
        },
        "air_dates": {
            "start_date": "2026-11-12T19:00:00Z"
        },
        "drm_key": "1122334455667788:aabbccddeeff0011"
    }
]

mock_bingstream = [
    {
        "id": "3001",
        "localteam_name": "Arsenal",
        "visitorteam_name": "Chelsea",
        "league_name": "Premier League",
        "cover_image": "https://cdn.bing.com/arsenal_chelsea.png",
        "referer": "https://iframe.rumsport11.site/",
        "link_live": [
            {
                "stream_name": "English 1080p",
                "stream_link": "https://stream.bing.com/hls/live1.m3u8"
            },
            {
                "stream_name": "Bangla 720p",
                "stream_link": "https://stream.bing.com/hls/live2.m3u8"
            }
        ],
        "is_playing": True,
        "bd_time": "12-11-2026 08:00 PM"
    }
]

mock_custom = [
    {
        "id": "4001",
        "event_name": "IPL 2026",
        "team_a": {"name": "CSK", "logo": "https://img.csk.png"},
        "team_b": {"name": "RCB", "logo": "https://img.rcb.png"},
        "streams": [
            {"name": "Server 1 (FHD)", "link": "https://stream.example.com/live/master.m3u8"},
            {"name": "Server 2 (HD)", "link": "https://stream2.example.com/live/master.mpd"}
        ],
        "schedule_time": "12/11/2026 07:30 PM IST"
    }
]

def mock_fetch(url):
    if "tapmad" in url:
        return mock_tapmad
    elif "sonyliv" in url:
        return mock_sonyliv
    elif "bing" in url:
        return mock_bingstream
    elif "custom" in url:
        return mock_custom
    return []

# Monkeypatch fetch_data
update_playlist.fetch_data = mock_fetch

# Set up test environment
os.environ["JSON_PLAYLIST_URLS"] = """
Tapmad Sports | https://dummy.test/tapmad.json
Sony LIV Sports | https://dummy.test/sonyliv.json
Bing Sports | https://dummy.test/bing.json
Custom Sports | https://dummy.test/custom.json
"""

update_playlist.raw_urls = os.environ["JSON_PLAYLIST_URLS"]
update_playlist.lines = [u.strip() for u in update_playlist.raw_urls.splitlines() if u.strip()]

print("=== Running update_playlist test ===")
update_playlist.run()

# Verify output files
out_dir = os.path.dirname(os.path.abspath(__file__))
slugs = ["tapmad_sports", "sony_liv_sports", "bing_sports", "custom_sports"]

all_passed = True
for slug in slugs:
    json_path = os.path.join(out_dir, f"{slug}.json")
    m3u_path = os.path.join(out_dir, f"{slug}.m3u")
    
    if not os.path.exists(json_path):
        print(f"❌ FAIL: {slug}.json not created!")
        all_passed = False
        continue
    if not os.path.exists(m3u_path):
        print(f"❌ FAIL: {slug}.m3u not created!")
        all_passed = False
        continue

    with open(json_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    with open(m3u_path, "r", encoding="utf-8") as f:
        m3u_content = f.read()

    # Verify Promo is absent
    has_promo_json = any(e.get("match_id") == "ireentv_promo" or "IreenTV Promo" in e.get("title", "") for e in data.get("events", []))
    has_promo_m3u = "IreenTV Promo" in m3u_content or "ireentvlive.pages.dev" in m3u_content

    if has_promo_json:
        print(f"❌ FAIL: {slug}.json contains promo!")
        all_passed = False
    if has_promo_m3u:
        print(f"❌ FAIL: {slug}.m3u contains promo!")
        all_passed = False

    print(f"✅ {slug}: {len(data.get('events', []))} events parsed, Live: {data.get('live_count')}. Promo successfully excluded!")

if all_passed:
    print("\n🎉 ALL TESTS PASSED! Script works 100% perfectly without promo.")
else:
    print("\n❌ SOME TESTS FAILED!")
    sys.exit(1)

