#!/usr/bin/env python3
"""
Sports Aggregator & Live Updater
- Fetches real-time sports from SonyLIV, Willow, Tapmad, World Sports, and FanCode.
- Groups multiple server streams under each match.
- Auto-removes completed matches.
- Keeps LIVE and UPCOMING matches.
- Merges with manual matches from MySQL live_sports table.
- Caches for 10 minutes (600s).
"""

import time
import json
import re
import urllib.request
import logging

logger = logging.getLogger("sports_updater")

_cached_sports = {
    "data": None,
    "timestamp": 0
}

KNOWN_PAIRS = [
    ('india', 'new zealand', 'India vs New Zealand', 'Cricket'),
    ('south africa', 'bangladesh', 'South Africa vs Bangladesh', 'Cricket'),
    ('bangladesh', 'south africa', 'Bangladesh vs South Africa', 'Cricket'),
    ('australia', 'england', 'Australia vs England', 'Cricket'),
    ('afghanistan', 'zimbabwe', 'Afghanistan vs Zimbabwe', 'Cricket'),
    ('pakistan', 'australia', 'Pakistan vs Australia', 'Cricket'),
    ('real madrid', 'barcelona', 'Real Madrid vs Barcelona', 'Football'),
    ('manchester city', 'liverpool', 'Manchester City vs Liverpool', 'Football'),
    ('arsenal', 'chelsea', 'Arsenal vs Chelsea', 'Football'),
]

def _fetch_url(url: str, headers: dict = None, timeout: int = 8) -> str:
    default_headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }
    if headers:
        default_headers.update(headers)
    req = urllib.request.Request(url, headers=default_headers)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read().decode("utf-8", errors="ignore")

def extract_teams_and_key(title: str):
    if not title:
        return "unknown", "Live Event", "", "", "Sports"
    t_lower = title.lower()
    for team_a, team_b, clean_title, sport in KNOWN_PAIRS:
        if team_a in t_lower and team_b in t_lower:
            key = f"{min(team_a, team_b)}_vs_{max(team_a, team_b)}"
            t1, t2 = clean_title.split(" vs ")
            return key, clean_title, t1.strip(), t2.strip(), sport

    # Check for "vs" pattern
    vs_parts = re.split(r'\s+vs\.?\s+|\s+v\s+', title, flags=re.IGNORECASE)
    if len(vs_parts) >= 2:
        t1 = re.sub(r'\[.*?\]|\(.*?\)', '', vs_parts[0]).strip()
        t2 = re.sub(r'\[.*?\]|\(.*?\)|(\d+(?:st|nd|rd|th)\s+[a-z]+.*)', '', vs_parts[1], flags=re.IGNORECASE).strip()
        clean_title = f"{t1} vs {t2}"
        key = re.sub(r'[^a-z0-9]', '', f"{t1.lower()}_{t2.lower()}")
        return key, clean_title, t1, t2, "Sports"

    clean_title = re.sub(r'\[.*?\]', '', title).strip()
    key = re.sub(r'[^a-z0-9]', '', clean_title.lower())[:25]
    return key, clean_title, clean_title, "", "Sports"

def fetch_aggregated_sports_sync() -> dict:
    grouped = {}

    def ensure_item(key, title, t1, t2, sport, thumbnail="", league="", status="live", match_time="LIVE NOW"):
        if key not in grouped:
            grouped[key] = {
                "id": len(grouped) + 1,
                "key": key,
                "title": title,
                "match_title": title,
                "team1_name": t1,
                "team2_name": t2,
                "team1_logo": "",
                "team2_logo": "",
                "league": league or "International Sports",
                "sport": sport,
                "status": status.lower(),
                "status_display": "LIVE" if status.lower() == "live" else "UPCOMING",
                "match_time": match_time,
                "thumbnail": thumbnail or "https://images.unsplash.com/photo-1540747913346-19e32dc3e97e?w=800&q=80",
                "logo": thumbnail or "https://images.unsplash.com/photo-1540747913346-19e32dc3e97e?w=800&q=80",
                "stream_links": [],
                "server_urls": []
            }
        return grouped[key]

    # 1. World Sports JSON (Primary curated live sports feed)
    try:
        ws_url = "https://raw.githubusercontent.com/sportlive18/Sportlink-Live/main/api/world-sports.json"
        content = _fetch_url(ws_url, timeout=7)
        data = json.loads(content)
        for m in data.get("matches", []):
            st = m.get("status", "live").lower()
            if st in ["completed", "ended", "finished"]:
                continue
            title = m.get("title", "")
            key, clean_title, t1, t2, sport = extract_teams_and_key(title)
            league = m.get("league", "")
            thumb = m.get("thumbnail", "")
            match_time = "LIVE NOW" if st == "live" else (m.get("time") or "UPCOMING")
            
            item = ensure_item(key, clean_title, t1, t2, sport, thumb, league, st, match_time)
            for idx, s_url in enumerate(m.get("server_urls", [])):
                if s_url and s_url not in item["server_urls"]:
                    item["server_urls"].append(s_url)
                    s_label = f"Server {len(item['stream_links']) + 1} (HD Stream)"
                    item["stream_links"].append({"label": s_label, "url": s_url})
    except Exception as e:
        logger.debug(f"World Sports error: {e}")

    # 2. SonyLIV Live Events (drmlive)
    try:
        sonyliv_url = "https://raw.githubusercontent.com/drmlive/sliv-live-events/refs/heads/main/sonyliv.m3u"
        content = _fetch_url(sonyliv_url, timeout=7)
        cur_title = None
        cur_logo = None
        for line in content.splitlines():
            line = line.strip()
            if line.startswith("#EXTINF"):
                m_logo = re.search(r'tvg-logo="([^"]*)"', line)
                cur_logo = m_logo.group(1) if m_logo else ""
                cur_title = line.split(",")[-1].strip()
            elif line.startswith("http"):
                if cur_title:
                    key, clean_title, t1, t2, sport = extract_teams_and_key(cur_title)
                    lang = "HD"
                    for l in ["ENG", "HIN", "TAM", "TEL", "KAN", "BEN"]:
                        if f"[{l}]" in cur_title or f"/{l}/" in line:
                            lang = l
                            break
                    item = ensure_item(key, clean_title, t1, t2, sport, cur_logo, "SonyLIV Sports", "live")
                    if line not in item["server_urls"]:
                        item["server_urls"].append(line)
                        item["stream_links"].append({
                            "label": f"Server {len(item['stream_links']) + 1} (SonyLIV {lang})",
                            "url": line
                        })
                cur_title = None
    except Exception as e:
        logger.debug(f"SonyLIV error: {e}")

    # 3. Willow Cricket (ClearKey & Dash)
    try:
        willow_url = "https://raw.githubusercontent.com/srhady/willow-event/refs/heads/main/live_sports.m3u"
        content = _fetch_url(willow_url, timeout=7)
        cur_title = None
        cur_logo = None
        cur_key = None
        for line in content.splitlines():
            line = line.strip()
            if line.startswith("#EXTINF"):
                m_logo = re.search(r'tvg-logo="([^"]*)"', line)
                cur_logo = m_logo.group(1) if m_logo else ""
                cur_title = line.split(",")[-1].strip()
            elif "license_key=" in line:
                cur_key = line.split("license_key=")[-1].strip()
            elif line.startswith("http"):
                if cur_title:
                    key, clean_title, t1, t2, sport = extract_teams_and_key(cur_title)
                    item = ensure_item(key, clean_title, t1, t2, "Cricket", cur_logo, "Willow Cricket HD", "live")
                    if line not in item["server_urls"]:
                        item["server_urls"].append(line)
                        s_entry = {
                            "label": f"Server {len(item['stream_links']) + 1} (Willow HD)",
                            "url": line
                        }
                        if cur_key:
                            s_entry["license_key"] = cur_key
                        item["stream_links"].append(s_entry)
                cur_title = None
                cur_key = None
    except Exception as e:
        logger.debug(f"Willow error: {e}")

    # 4. Tapmad Live Sports
    try:
        tapmad_url = "https://raw.githubusercontent.com/sm-monirulislam/Tapmad_Auto_Update_Playlist/main/tapmad_data.json"
        content = _fetch_url(tapmad_url, timeout=7)
        t_data = json.loads(content)
        for m in t_data.get("matches", []):
            st = m.get("status", "").upper()
            if st in ["COMPLETED", "ENDED"]:
                continue
            stream_url = m.get("stream_url", "")
            title = m.get("title", "")
            if not title:
                continue
            key, clean_title, t1, t2, sport = extract_teams_and_key(title)
            is_live = (st == "LIVE")
            status_val = "live" if is_live else "upcoming"
            match_time_val = "LIVE NOW" if is_live else "UPCOMING"
            
            item = ensure_item(key, clean_title, t1, t2, sport, m.get("thumbnail"), "Tapmad Sports", status_val, match_time_val)
            if stream_url and stream_url not in item["server_urls"]:
                item["server_urls"].append(stream_url)
                item["stream_links"].append({
                    "label": f"Server {len(item['stream_links']) + 1} (Tapmad {m.get('quality', 'Live')})",
                    "url": stream_url,
                    "headers": {"Referer": "https://www.tapmad.com/"}
                })
    except Exception as e:
        logger.debug(f"Tapmad error: {e}")

    # 5. FanCode Live GraphQL
    try:
        fc_url = "https://www.fancode.com/graphql"
        query = {
            "query": """
            query LiveStreamingMatches {
              matchesHasLivestream {
                id
                name
                streamingStatus
                startTime
                sport { name }
              }
            }
            """,
            "variables": {}
        }
        req_data = json.dumps(query).encode("utf-8")
        req = urllib.request.Request(
            fc_url,
            data=req_data,
            headers={
                "Content-Type": "application/json",
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                "source": "fancodetv"
            }
        )
        with urllib.request.urlopen(req, timeout=7) as resp:
            fc_resp = json.loads(resp.read().decode())
            fc_matches = fc_resp.get("data", {}).get("matchesHasLivestream", [])
            for m in fc_matches:
                st = m.get("streamingStatus", "").upper()
                if st in ["ENDED", "COMPLETED"]:
                    continue
                name = m.get("name", "")
                if not name:
                    continue
                key, clean_title, t1, t2, sport = extract_teams_and_key(name)
                sport_name = m.get("sport", {}).get("name", "Cricket") if m.get("sport") else "Cricket"
                is_live = (st == "STARTED")
                status_val = "live" if is_live else "upcoming"
                match_time_val = "LIVE NOW" if is_live else "UPCOMING"
                
                ensure_item(key, clean_title, t1, t2, sport_name, "", "FanCode Live", status_val, match_time_val)
    except Exception as e:
        logger.debug(f"FanCode error: {e}")

    # Convert to list and re-index
    events = list(grouped.values())
    
    # Sort: LIVE matches first, then UPCOMING
    events.sort(key=lambda x: (0 if x["status"] == "live" else 1, -len(x["stream_links"])))

    for i, ev in enumerate(events):
        ev["id"] = i + 1

    live_count = sum(1 for e in events if e["status"] == "live")
    upcoming_count = sum(1 for e in events if e["status"] == "upcoming")

    # Format output for both WorldSports and LiveSports schemas
    result = {
        "code": 0,
        "total_matches": len(events),
        "live_count": live_count,
        "upcoming_count": upcoming_count,
        "matches": [
            {
                "match_id": str(e["id"]),
                "title": e["title"],
                "teams": f"{e['team1_name']} vs {e['team2_name']}" if e['team2_name'] else e['title'],
                "league": e["league"],
                "sport": e["sport"],
                "status": e["status"],
                "status_display": e["status_display"],
                "match_time": e["match_time"],
                "thumbnail": e["thumbnail"],
                "server_urls": e["server_urls"],
                "stream_links": e["stream_links"]
            }
            for e in events
        ],
        "list": events
    }

    try:
        import os
        pub_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "public", "live_events.json")
        with open(pub_path, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2)
    except Exception as e:
        logger.debug(f"Save live_events.json error: {e}")

    return result

def get_live_sports_data(force_refresh: bool = False) -> dict:
    global _cached_sports
    now = time.time()
    if not force_refresh and _cached_sports["data"] and (now - _cached_sports["timestamp"] < 600):
        return dict(_cached_sports["data"])

    try:
        fresh_data = fetch_aggregated_sports_sync()
        if fresh_data and fresh_data.get("total_matches", 0) > 0:
            _cached_sports = {
                "data": fresh_data,
                "timestamp": now
            }
            return fresh_data
    except Exception as e:
        logger.error(f"Error fetching aggregated live sports: {e}")

    if _cached_sports["data"]:
        return _cached_sports["data"]
    return {"code": 0, "total_matches": 0, "live_count": 0, "upcoming_count": 0, "matches": [], "list": []}

async def get_live_sports_data_async(pool=None, force_refresh: bool = False) -> dict:
    global _cached_sports
    now = time.time()
    
    # Check 10-minute cache TTL (600 seconds)
    if not force_refresh and _cached_sports["data"] and (now - _cached_sports["timestamp"] < 600):
        data = dict(_cached_sports["data"])
    else:
        try:
            import asyncio
            fresh_data = await asyncio.to_thread(fetch_aggregated_sports_sync)
            if fresh_data and fresh_data.get("total_matches", 0) > 0:
                _cached_sports = {
                    "data": fresh_data,
                    "timestamp": now
                }
                data = dict(fresh_data)
            else:
                data = _cached_sports.get("data") or {"code": 0, "total_matches": 0, "matches": [], "list": []}
        except Exception as e:
            logger.error(f"Error fetching aggregated live sports async: {e}")
            data = _cached_sports.get("data") or {"code": 0, "total_matches": 0, "matches": [], "list": []}

    # Merge with manual DB entries if pool provided
    if pool:
        try:
            import aiomysql
            async with pool.acquire() as conn:
                async with conn.cursor(aiomysql.DictCursor) as cur:
                    await cur.execute("SELECT * FROM live_sports ORDER BY created_at DESC")
                    rows = await cur.fetchall()
                    if rows:
                        custom_items = []
                        for r in rows:
                            try:
                                links = json.loads(r["stream_links"]) if isinstance(r["stream_links"], str) else (r["stream_links"] or [])
                            except Exception:
                                links = []
                            custom_items.append({
                                "id": r["id"],
                                "title": r["title"],
                                "match_title": r["title"],
                                "team1_name": r["team1_name"] or "",
                                "team2_name": r["team2_name"] or "",
                                "team1_logo": r["team1_logo"] or "",
                                "team2_logo": r["team2_logo"] or "",
                                "league": "Custom Sports",
                                "sport": "Sports",
                                "status": "live",
                                "status_display": "LIVE",
                                "match_time": "LIVE NOW",
                                "thumbnail": r["logo"] or "",
                                "logo": r["logo"] or "",
                                "stream_links": links,
                                "server_urls": [l.get("url") for l in links if isinstance(l, dict) and l.get("url")]
                            })
                        # Prepend custom items to the list
                        merged_list = custom_items + [it for it in data.get("list", []) if it.get("title") not in [c["title"] for c in custom_items]]
                        merged_matches = custom_items + [it for it in data.get("matches", []) if it.get("title") not in [c["title"] for c in custom_items]]
                        data["list"] = merged_list
                        data["matches"] = merged_matches
                        data["total_matches"] = len(merged_list)
        except Exception as e:
            logger.debug(f"DB merge sports error: {e}")

    return data
