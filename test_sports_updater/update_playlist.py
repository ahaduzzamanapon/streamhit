import os
import re
import json
import requests
import urllib3
from datetime import datetime
import pytz

urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

tz_bd = pytz.timezone("Asia/Dhaka")
current_time = datetime.now(tz_bd).strftime("%I:%M %p %d-%m-%Y BST")

raw_urls = os.environ.get("JSON_PLAYLIST_URLS", "")
lines = [u.strip() for u in raw_urls.splitlines() if u.strip() and not u.strip().startswith("#")]

# ================= TIMEZONE CONVERTER TO BST =================
def convert_to_bangladesh_time(time_str):
    """যেকোনো ফরম্যাটের IST, UTC বা সাধারণ সময়কে নিখুঁত বাংলাদেশ টাইমে (BST) রূপান্তর করে"""
    if not time_str or not isinstance(time_str, (str, int, float)):
        return ""
    
    t_str = str(time_str).strip()
    if not t_str or t_str.lower() in ["24/7", "live", "n/a", "none", "tbd"]:
        return t_str

    bd_tz = pytz.timezone("Asia/Dhaka")
    ist_tz = pytz.timezone("Asia/Kolkata")
    utc_tz = pytz.timezone("UTC")

    # যদি Epoch Timestamp হয়
    if re.match(r'^\d{10,13}$', t_str):
        ts = int(t_str)
        if len(t_str) == 13:
            ts = ts / 1000.0
        dt = datetime.fromtimestamp(ts, tz=bd_tz)
        return dt.strftime("%d/%m/%Y %I:%M %p BST")

    is_ist = bool(re.search(r'\bIST\b', t_str, re.IGNORECASE))
    is_utc = bool(re.search(r'\b(UTC|GMT|Z)\b', t_str, re.IGNORECASE))

    clean_t = re.sub(r'\s*(IST|UTC|GMT|BST|Z)\s*$', '', t_str, flags=re.IGNORECASE).strip()

    formats = [
        "%d/%m/%Y %I:%M %p",
        "%d-%m-%Y %I:%M %p",
        "%Y-%m-%d %I:%M %p",
        "%d/%m/%Y %H:%M:%S",
        "%d/%m/%Y %H:%M",
        "%d-%m-%Y %H:%M:%S",
        "%d-%m-%Y %H:%M",
        "%Y-%m-%d %H:%M:%S",
        "%Y-%m-%d %H:%M",
        "%Y-%m-%dT%H:%M:%SZ",
        "%Y-%m-%dT%H:%M:%S%z",
        "%Y-%m-%dT%H:%M:%S.%fZ",
        "%Y-%m-%dT%H:%M:%S",
        "%I:%M %p %d-%m-%Y",
        "%I:%M %p %d/%m/%Y",
        "%b %d, %Y %I:%M %p",
        "%d %b %Y %I:%M %p",
        "%d %B %Y %I:%M %p"
    ]

    parsed_dt = None
    for fmt in formats:
        try:
            parsed_dt = datetime.strptime(clean_t, fmt)
            break
        except ValueError:
            continue

    if parsed_dt:
        if parsed_dt.tzinfo is not None:
            bd_dt = parsed_dt.astimezone(bd_tz)
        elif is_ist:
            bd_dt = ist_tz.localize(parsed_dt).astimezone(bd_tz)
        elif is_utc or "T" in clean_t:
            bd_dt = utc_tz.localize(parsed_dt).astimezone(bd_tz)
        else:
            bd_dt = ist_tz.localize(parsed_dt).astimezone(bd_tz)

        return bd_dt.strftime("%d/%m/%Y %I:%M %p BST")

    return t_str

def fetch_data(url):
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Accept": "application/json, text/plain, */*"
    }
    res = requests.get(url, headers=headers, timeout=30, verify=False)
    res.raise_for_status()
    clean_text = res.content.decode('utf-8-sig', errors='ignore').strip()
    return json.loads(clean_text)

# ================= STRICT MPD & M3U8 CHECKER =================
def is_mpd_or_m3u8(url):
    if not url or not isinstance(url, str):
        return False
    
    u_str = url.strip()
    if not (u_str.startswith("http://") or u_str.startswith("https://")):
        return False

    clean_path = u_str.split("|")[0].split("?")[0].lower()
    full_url = u_str.lower()

    if ".m3u8" in clean_path or ".mpd" in clean_path or ".m3u8" in full_url or ".mpd" in full_url:
        if any(clean_path.endswith(ext) for ext in [".html", ".htm", ".php"]):
            return False
        return True
    
    return False

# ================= SOFASCORE / MULTI-STREAM CUSTOM FORMAT =================
def is_custom_streams_item(item):
    if not isinstance(item, dict):
        return False
    if "streams" in item and isinstance(item["streams"], list):
        return True
    if any(k in item for k in ["team_a", "team_b", "event_name", "schedule_time", "event_logo"]):
        return True
    return False

def transform_custom_streams_item(item):
    team_a = item.get("team_a", {}) if isinstance(item.get("team_a"), dict) else {}
    team_b = item.get("team_b", {}) if isinstance(item.get("team_b"), dict) else {}
    
    team_a_name = team_a.get("name", "").strip()
    team_b_name = team_b.get("name", "").strip()
    event_name = item.get("event_name", "").strip()
    category = item.get("category", "").strip()

    if team_a_name and team_b_name:
        match_title = f"{team_a_name} vs {team_b_name}"
        title = f"{event_name}: {match_title}" if event_name else match_title
    else:
        title = item.get("title") or event_name or item.get("name") or "Sports Match"

    synopsis = item.get("synopsis") or item.get("description") or (f"{category} | {title}" if category else title)

    cover_image = (
        item.get("event_logo") or 
        item.get("cover_image") or 
        team_a.get("logo") or 
        team_b.get("logo") or 
        item.get("thumbnail") or 
        item.get("logo") or ""
    )

    stream_urls = {}
    raw_streams = item.get("streams", [])
    if isinstance(raw_streams, list):
        for idx, s in enumerate(raw_streams, 1):
            if isinstance(s, dict):
                s_name = s.get("name") or f"Server {idx}"
                s_link = s.get("link") or s.get("url") or s.get("stream_url") or ""
                if s_link and is_mpd_or_m3u8(str(s_link)):
                    stream_urls[s_name.strip()] = str(s_link).strip()
            elif isinstance(s, str) and is_mpd_or_m3u8(s):
                stream_urls[f"Server {idx}"] = s.strip()

    if not stream_urls:
        stream_urls = extract_all_mpd_m3u8_streams(item)

    first_url = next(iter(stream_urls.values())) if stream_urls else ""
    match_url = item.get("match_url") or first_url or ""
    
    raw_time = item.get("schedule_time") or item.get("time") or item.get("start_time") or ""
    time_val = convert_to_bangladesh_time(raw_time)

    status = "LIVE" if (is_live_event(item) or len(stream_urls) > 0) else (item.get("status") or "UPCOMING")

    return {
        "match_id": str(item.get("id") or item.get("match_id", "")),
        "title": title,
        "synopsis": synopsis,
        "status": status,
        "time": time_val,
        "cover_image": cover_image,
        "match_url": match_url,
        "is_free": False,
        "stream_url": stream_urls,
        "streams": raw_streams
    }

# ================= SONY LIV =================
def is_sonyliv_item(item):
    if not isinstance(item, dict):
        return False
    if "multiple_audio_links" in item:
        return True
    if "video_info" in item and ("stream_info" in item or "image_cdn" in item or "air_dates" in item):
        return True
    if "contentId" in item and ("video_info" in item or "air_dates" in item or "stream_info" in item):
        return True
    return False

def extract_sonyliv_streams(item):
    servers = {}
    stream_info = item.get("stream_info", {}) if isinstance(item.get("stream_info"), dict) else {}
    
    dai = (
        stream_info.get("dai_asset_key") or 
        stream_info.get("dai_url") or 
        item.get("dai_asset_key") or 
        item.get("dai_url")
    )
    
    if dai and str(dai).startswith("http") and is_mpd_or_m3u8(str(dai)):
        servers["Original Server"] = str(dai).strip()
        
    return servers

def transform_sonyliv_item(item):
    v_info = item.get("video_info", {}) if isinstance(item.get("video_info"), dict) else {}
    img_cdn = item.get("image_cdn", {}) if isinstance(item.get("image_cdn"), dict) else {}
    air_dates = item.get("air_dates", {}) if isinstance(item.get("air_dates"), dict) else {}

    title = v_info.get("episodeTitle") or v_info.get("title") or item.get("title") or "Sports Match"
    synopsis = v_info.get("longDescription") or v_info.get("shortDescription") or item.get("synopsis") or title

    cover_image = (
        img_cdn.get("landscape_thumb") or 
        img_cdn.get("thumbnail") or 
        img_cdn.get("portrait_thumb") or 
        item.get("cover_image") or ""
    )

    stream_urls = extract_sonyliv_streams(item)
    dai_url = stream_urls.get("Original Server")
    
    if dai_url:
        match_url = dai_url
        status = "LIVE"
    else:
        content_id = item.get('contentId') or item.get('match_id', '')
        match_url = f"https://www.sonyliv.com/custom-live/{content_id}" if content_id else ""
        status = "LIVE" if is_live_event(item) else "UPCOMING"

    raw_time = (
        air_dates.get("originalAirDate") or 
        air_dates.get("start_date") or 
        item.get("time") or 
        item.get("start_time") or ""
    )
    time_val = convert_to_bangladesh_time(raw_time)

    return {
        "match_id": str(item.get("contentId") or item.get("match_id", "")),
        "title": title,
        "synopsis": synopsis,
        "status": status,
        "time": time_val,
        "cover_image": cover_image,
        "match_url": match_url,
        "is_free": False,
        "stream_url": stream_urls
    }

# ================= TAPMAD =================
def is_tapmad_item(item):
    if not isinstance(item, dict):
        return False
    return any(k in item for k in ["EntityId", "VideoName", "CategoryName", "ThumbnailStandard", "ThumbnailTV", "CategoryId", "UrlSlug"])

def transform_tapmad_item(item):
    video_name = item.get("VideoName") or ""
    category_name = item.get("CategoryName") or ""
    stage = item.get("StageName") or ""

    if category_name and video_name and category_name.lower() not in video_name.lower():
        title = f"{category_name} - {video_name}"
    else:
        title = video_name or category_name or item.get("title") or "Sports Match"

    if stage and stage not in title:
        title = f"{title} ({stage})"

    synopsis = item.get("Description") or item.get("synopsis") or title
    cover_image = item.get("ThumbnailTV") or item.get("ThumbnailStandard") or item.get("Thumbnail") or item.get("cover_image") or item.get("logo") or ""

    stream_urls = {}
    raw_stream = item.get("stream_url") or item.get("StreamUrl") or item.get("videoURL") or item.get("video_url") or ""
    if isinstance(raw_stream, str) and raw_stream.startswith("http") and is_mpd_or_m3u8(raw_stream):
        stream_urls["Server 1"] = raw_stream.strip()
    elif isinstance(raw_stream, dict):
        stream_urls = {k: v for k, v in raw_stream.items() if is_mpd_or_m3u8(v)}
    elif isinstance(raw_stream, list):
        for idx, u in enumerate(raw_stream, 1):
            if isinstance(u, str) and u.startswith("http") and is_mpd_or_m3u8(u):
                stream_urls[f"Server {idx}"] = u.strip()

    first_url = next(iter(stream_urls.values())) if stream_urls else ""
    match_url = item.get("match_url") or first_url or ""

    status_val = str(item.get("Status") or item.get("status") or "")
    is_live = True if "live" in status_val.lower() or is_live_event(item) else False
    status = "LIVE" if is_live else (status_val.upper() if status_val else "UPCOMING")

    raw_time = item.get("EventStartDate") or item.get("time") or ""
    time_val = convert_to_bangladesh_time(raw_time)

    return {
        "match_id": str(item.get("EntityId") or item.get("CategoryId") or item.get("id") or ""),
        "title": title,
        "synopsis": synopsis,
        "status": status,
        "time": time_val,
        "cover_image": cover_image,
        "match_url": match_url,
        "is_free": item.get("IsFreeToWatch", False),
        "stream_url": stream_urls
    }

# ================= BINGSTREAM (ADVANCED MULTI-STREAM SUPPORT) =================
def is_bingstream_item(item):
    if not isinstance(item, dict):
        return False
    if any(k in item for k in ["link_live", "extracted_links", "cdn_domain", "stream_link", "chanel_id", "localteam_name"]):
        return True
    if any(k in item for k in ["videoURL", "video_url", "video URL", "videoUrl"]):
        return True
    source_val = str(item.get("source", "") or item.get("provider", "") or item.get("type", "")).lower()
    return "bing" in source_val

def extract_bingstream_streams(item):
    servers = {}
    found_labeled_urls = []

    referer_val = (
        item.get("referer") or 
        item.get("referrer") or 
        item.get("referer_url") or 
        item.get("referrer_url") or 
        "https://iframe.rumsport11.site"
    )
    if referer_val and not referer_val.endswith("/"):
        referer_val += "/"

    user_agent_val = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    suffix = f"|http-user-agent={user_agent_val}&http-referrer={referer_val}"

    def parse_stream_object_list(obj_list):
        if isinstance(obj_list, list):
            for s_obj in obj_list:
                if isinstance(s_obj, dict):
                    v_url = (
                        s_obj.get("stream_link") or 
                        s_obj.get("videoURL") or 
                        s_obj.get("video_url") or 
                        s_obj.get("videoUrl") or 
                        s_obj.get("url") or 
                        s_obj.get("link")
                    )
                    label = s_obj.get("display_name") or s_obj.get("stream_name") or s_obj.get("name") or s_obj.get("extra_title") or "Server"
                    if v_url and str(v_url).startswith("http") and is_mpd_or_m3u8(str(v_url)):
                        found_labeled_urls.append((str(label).strip(), str(v_url).strip()))
                elif isinstance(s_obj, str) and s_obj.startswith("http") and is_mpd_or_m3u8(s_obj):
                    found_labeled_urls.append(("Server", s_obj.strip()))

    for list_key in ["link_live", "channels", "streams", "channel_list", "stream_list"]:
        if list_key in item and isinstance(item[list_key], list):
            parse_stream_object_list(item[list_key])

    for k, v in item.items():
        if isinstance(v, list) and k not in ["extracted_links", "link_live"]:
            parse_stream_object_list(v)

    if "extracted_links" in item and isinstance(item["extracted_links"], list):
        for idx, ext_url in enumerate(item["extracted_links"], 1):
            if isinstance(ext_url, str) and ext_url.startswith("http") and is_mpd_or_m3u8(ext_url):
                found_labeled_urls.append((f"Server {idx}", ext_url.strip()))

    cdn = item.get("cdn_domain")
    if cdn and isinstance(cdn, str) and cdn.startswith("http") and is_mpd_or_m3u8(cdn):
        found_labeled_urls.append(("CDN Direct", cdn.strip()))

    video_keys = ["videoURL", "video_url", "video URL", "videoUrl", "stream_url", "stream_link"]
    for key in video_keys:
        if key in item and item[key]:
            val = item[key]
            if isinstance(val, str):
                for p in re.split(r'[\r\n,]+', val.strip()):
                    p_clean = p.strip()
                    if p_clean.startswith("http") and is_mpd_or_m3u8(p_clean):
                        found_labeled_urls.append(("Server", p_clean))

    seen_urls = set()
    counter = 1
    for label, u in found_labeled_urls:
        clean_u = u.split("|http-")[0] if "|http-" in u else u
        if clean_u in seen_urls:
            continue
        seen_urls.add(clean_u)

        final_label = f"Server {counter} ({label})" if label and label != "Server" else f"Server {counter}"
        servers[final_label] = f"{clean_u}{suffix}"
        counter += 1

    return servers

def transform_bingstream_item(item):
    team_a = item.get("localteam_name") or item.get("team_a") or ""
    team_b = item.get("visitorteam_name") or item.get("team_b") or ""
    match_title = f"{team_a} vs {team_b}" if team_a and team_b else ""

    title = item.get("name") or item.get("title") or match_title or item.get("event_name") or item.get("match_name") or "Sports Match"
    league = item.get("league_name", "")
    synopsis = item.get("synopsis") or item.get("description") or (f"{league} - {title}" if league else title)
    
    cover_image = (
        item.get("cover_image") or 
        item.get("thumbnail") or 
        item.get("league_logo") or 
        item.get("localteam_logo") or 
        item.get("visitorteam_logo") or 
        item.get("logo") or
        item.get("image") or ""
    )

    stream_urls = extract_bingstream_streams(item)
    first_url = next(iter(stream_urls.values())) if stream_urls else ""
    match_url = item.get("match_url") or first_url or ""
    
    is_live = False
    if item.get("is_playing") is True:
        is_live = True
    elif stream_urls and not item.get("has_ended", False):
        is_live = True
    elif is_live_event(item):
        is_live = True

    status = "LIVE" if is_live else (item.get("status") or "UPCOMING")
    match_id = str(item.get("id") or item.get("match_id") or item.get("contentId") or item.get("eventId") or "")
    
    raw_time = item.get("bd_time") or item.get("time") or item.get("start_time") or item.get("date") or ""
    time_val = convert_to_bangladesh_time(raw_time)

    return {
        "match_id": match_id,
        "title": title,
        "synopsis": synopsis,
        "status": status,
        "time": time_val,
        "cover_image": cover_image,
        "match_url": match_url,
        "is_free": False,
        "stream_url": stream_urls,
        "referer": item.get("referer", "https://iframe.rumsport11.site"),
        "extracted_links": item.get("extracted_links", [])
    }

# ================= ALL MULTI-SERVER STREAM EXTRACTOR =================
def extract_all_mpd_m3u8_streams(item):
    found_streams = {}

    def add_url(u, label=""):
        if not u or not isinstance(u, str):
            return
        for part in re.split(r'[\r\n,]+', u.strip()):
            clean_u = part.strip()
            if is_mpd_or_m3u8(clean_u):
                cmp_u = clean_u.split('|')[0].strip()
                if cmp_u not in found_streams:
                    found_streams[cmp_u] = (label.strip(), clean_u)
                elif "|" in clean_u and "|" not in found_streams[cmp_u][1]:
                    found_streams[cmp_u] = (label.strip() or found_streams[cmp_u][0], clean_u)

    def crawl_item(data, parent_key=""):
        if isinstance(data, dict):
            for k, v in data.items():
                k_str = str(k).strip()
                k_lower = k_str.lower()
                if k_lower in ["cover_image", "thumbnail", "thumbnailstandard", "thumbnailtv", "image", "logo", "synopsis", "description", "website", "telegram", "time", "date", "event_page"]:
                    continue

                current_prefix = f"{parent_key} - {k_str}" if parent_key else k_str

                if isinstance(v, str):
                    add_url(v, label=current_prefix)
                elif isinstance(v, (dict, list)):
                    crawl_item(v, parent_key=current_prefix if any(x in k_lower for x in ["alpha", "bravo", "charlie", "delta", "stream", "server", "feed", "link"]) else parent_key)
        elif isinstance(data, list):
            for idx, el in enumerate(data, 1):
                lbl = f"{parent_key} {idx}".strip() if parent_key else f"Server {idx}"
                if isinstance(el, str):
                    add_url(el, label=lbl)
                elif isinstance(el, (dict, list)):
                    crawl_item(el, parent_key=lbl)

    crawl_item(item)

    servers = {}
    for idx, (lbl, url) in enumerate(found_streams.values(), 1):
        clean_lbl = lbl.replace("stream_url_", "").replace("stream_url", "").replace("_", " ").strip()
        if clean_lbl.startswith("- "):
            clean_lbl = clean_lbl[2:].strip()

        if clean_lbl and clean_lbl.lower() not in ["match url", "url", "stream", "video url", "link"]:
            final_label = clean_lbl.title()
            if final_label in servers:
                final_label = f"{final_label} ({idx})"
            servers[final_label] = url
        else:
            servers[f"Server {idx}"] = url

    return servers

# ================= COMMON UTILS =================
def is_live_event(item):
    if not isinstance(item, dict):
        return False
    
    if item.get("is_playing") is True:
        return True
    if item.get("has_ended") is True:
        return False

    stream_info = item.get("stream_info", {}) if isinstance(item.get("stream_info"), dict) else {}
    dai = stream_info.get("dai_asset_key") or stream_info.get("dai_url") or item.get("dai_asset_key") or item.get("dai_url")
    if dai and str(dai).startswith("http"):
        return True
    
    stream_obj = item.get("stream_url", {})
    if isinstance(stream_obj, dict):
        if any(str(v).startswith("http") for v in stream_obj.values() if v):
            return True
    elif isinstance(stream_obj, str) and stream_obj.startswith("http"):
        return True

    if "streams" in item and isinstance(item["streams"], list) and len(item["streams"]) > 0:
        return True

    for k in ["is_live", "live", "isLive", "islive", "is_stream_live"]:
        if k in item:
            val = item[k]
            if val in [True, 1, "true", "1", "live", "yes", "True", "LIVE"]:
                return True
    
    v_info = item.get("video_info", {}) if isinstance(item.get("video_info"), dict) else {}
    for k in ["is_live", "live", "isLive", "islive", "broadcast_state"]:
        if k in v_info:
            val = v_info[k]
            if val in [True, 1, "true", "1", "live", "yes", "True", "LIVE"]:
                return True

    for k in ["status", "event_status", "match_status", "badge", "state", "Status"]:
        if k in item and isinstance(item[k], str) and "live" in item[k].lower():
            return True
        if k in v_info and isinstance(v_info[k], str) and "live" in v_info[k].lower():
            return True
    
    return False

def extract_items_from_json(data):
    items = []

    def walk(obj):
        if isinstance(obj, list):
            for elem in obj:
                walk(elem)
        elif isinstance(obj, dict):
            is_match_item = False
            has_id = any(k in obj for k in ["contentId", "match_id", "eventId", "event_id", "id", "fixture_id", "EntityId", "CategoryId", "chno", "chanel_id"])
            has_media = any(k in obj for k in ["link_live", "extracted_links", "cdn_domain", "stream_link", "video_info", "stream_info", "image_cdn", "air_dates", "stream_url", "stream_url_alpha", "stream_url_bravo", "multiple_audio_links", "video_url", "video URL", "videoUrl", "videoURL", "ThumbnailStandard", "ThumbnailTV", "drm_key", "url", "streams"])
            has_sports = any(k in obj for k in ["localteam_name", "visitorteam_name", "league_name", "timelive", "is_playing", "VideoName", "CategoryName", "StageName", "sport", "event", "event_name", "team_a", "team_b", "schedule_time", "event_logo"])

            if (has_id and (has_media or has_sports)) or has_media:
                is_match_item = True
            elif any(k in obj for k in ["title", "name", "VideoName", "CategoryName", "event", "event_name"]) and any(k in obj for k in ["match_url", "status", "Status", "stream_url", "stream_url_alpha", "cover_image", "time", "start_time", "bd_time", "schedule_time", "link_live", "url", "streams"]):
                is_match_item = True

            if is_match_item:
                items.append(obj)
            else:
                for k, v in obj.items():
                    if k.lower() in ["owner", "credit", "website", "admin", "notice"]:
                        continue
                    walk(v)

    walk(data)

    seen = set()
    unique_items = []
    for it in items:
        uid = (
            it.get("contentId") or 
            it.get("match_id") or 
            it.get("eventId") or 
            it.get("EntityId") or 
            (f"{it.get('name')}_{it.get('chno')}" if it.get('name') and it.get('chno') else None) or
            (f"{it.get('team_a', {}).get('name')}_{it.get('team_b', {}).get('name')}" if isinstance(it.get('team_a'), dict) and isinstance(it.get('team_b'), dict) and it.get('team_a', {}).get('name') else None) or
            it.get("event_name") or
            it.get("name") or 
            it.get("title") or 
            it.get("event") or
            it.get("VideoName") or 
            it.get("stream_url") or 
            it.get("url") or 
            (str(it.get("id")) if it.get("id") is not None else None)
        )
        if uid and uid not in seen:
            seen.add(uid)
            unique_items.append(it)
        elif not uid:
            unique_items.append(it)

    return unique_items

def get_playlist_info(url_line):
    if "|" in url_line:
        name, url = url_line.split("|", 1)
        name = name.strip()
        slug = re.sub(r'[^a-zA-Z0-9_-]', '_', name.lower()).strip('_')
        return name, slug, url.strip()
    
    url = url_line.strip()
    url_lower = url.lower()
    if "willow" in url_lower or "live_sports" in url_lower:
        return "Willow", "willow", url
    elif "prime" in url_lower:
        return "PrimeVideo", "primevideo", url
    elif "sony" in url_lower:
        return "Sony LIV", "sonyliv", url
    elif "tapmad" in url_lower:
        return "Tapmad", "tapmad", url
    elif "bing" in url_lower or "bingstream" in url_lower:
        return "Bingstream", "bingstream", url
    elif "sports_data" in url_lower:
        return "Sports Data", "sports_data", url
    else:
        base = os.path.splitext(os.path.basename(url.split("?")[0]))[0]
        slug = re.sub(r'[^a-zA-Z0-9_-]', '_', base).strip('_') if base else "playlist"
        return slug.replace("_", " ").title(), slug.lower(), url

# ================= ADVANCED M3U / DRM / HEADER PARSER =================
def parse_stream_details(stream_url_raw, item_meta):
    stream_url = str(stream_url_raw).strip()
    headers = {}

    if "|" in stream_url:
        base_url, pipe_part = stream_url.split("|", 1)
        base_url = base_url.strip()
        for pair in pipe_part.split("&"):
            if "=" in pair:
                k, v = pair.split("=", 1)
                k_lower = k.strip().lower()
                if "user-agent" in k_lower or "user_agent" in k_lower:
                    headers["User-Agent"] = v.strip()
                elif "refer" in k_lower:
                    headers["Referer"] = v.strip()
                elif "cookie" in k_lower:
                    headers["Cookie"] = v.strip()
                elif "origin" in k_lower:
                    headers["Origin"] = v.strip()
                else:
                    headers[k.strip()] = v.strip()
    else:
        base_url = stream_url

    item_headers = item_meta.get("headers", {}) if isinstance(item_meta.get("headers"), dict) else {}
    for hk, hv in item_headers.items():
        if hv and hk not in headers:
            headers[hk] = str(hv).strip()

    for ua_k in ["user_agent", "User-Agent", "user-agent", "agent", "http-user-agent", "http_user_agent"]:
        if ua_k in item_meta and item_meta[ua_k] and "User-Agent" not in headers:
            headers["User-Agent"] = str(item_meta[ua_k]).strip()
            break

    for ref_k in ["referer", "Referer", "referrer", "Referrer", "http-referrer", "http_referrer", "origin", "Origin"]:
        if ref_k in item_meta and item_meta[ref_k] and "Referer" not in headers:
            headers["Referer"] = str(item_meta[ref_k]).strip()
            break

    for ck_k in ["cookie", "Cookie", "cookies", "http-cookie", "http_cookie"]:
        if ck_k in item_meta and item_meta[ck_k] and "Cookie" not in headers:
            headers["Cookie"] = str(item_meta[ck_k]).strip()
            break

    # DRM Key
    clearkey_val = ""
    for ck_name in ["drm_key", "clearkey", "clear_key", "key", "keys", "license_key", "drm"]:
        if ck_name in item_meta and item_meta[ck_name]:
            val = item_meta[ck_name]
            if isinstance(val, str) and (":" in val or len(val) >= 32):
                clearkey_val = val.strip()
                break
            elif isinstance(val, dict):
                kid = val.get("kid") or val.get("key_id") or val.get("k1") or ""
                k = val.get("key") or val.get("k") or val.get("k2") or ""
                if kid and k:
                    clearkey_val = f"{kid}:{k}"
                    break
            elif isinstance(val, list) and len(val) > 0:
                first = val[0]
                if isinstance(first, dict):
                    kid = first.get("kid") or first.get("key_id") or ""
                    k = first.get("k") or first.get("key") or ""
                    if kid and k:
                        clearkey_val = f"{kid}:{k}"
                        break
                elif isinstance(first, str):
                    clearkey_val = first.strip()
                    break

    is_mpd = ".mpd" in base_url.lower() or item_meta.get("manifest_type") == "mpd" or bool(clearkey_val)

    return base_url, headers, clearkey_val, is_mpd

def build_m3u_channel_block(title, logo, group, stream_url_raw, item_meta):
    base_url, headers, clearkey, is_mpd = parse_stream_details(stream_url_raw, item_meta)
    if not base_url or not base_url.startswith("http"):
        return ""

    block = []
    block.append(f'#EXTINF:-1 tvg-name="{title}" tvg-logo="{logo}" group-title="{group}",{title}\n')

    if is_mpd:
        block.append("#KODIPROP:inputstream=inputstream.adaptive\n")
        block.append("#KODIPROP:inputstream.adaptive.manifest_type=mpd\n")
        if clearkey:
            block.append("#KODIPROP:inputstream.adaptive.license_type=clearkey\n")
            block.append(f"#KODIPROP:inputstream.adaptive.license_key={clearkey}\n")

    if headers:
        header_pairs = []
        for hk, hv in headers.items():
            if hk.lower() == "user-agent":
                block.append(f"#EXTVLCOPT:http-user-agent={hv}\n")
                header_pairs.append(f"User-Agent={hv}")
            elif hk.lower() in ["referer", "referrer"]:
                block.append(f"#EXTVLCOPT:http-referrer={hv}\n")
                header_pairs.append(f"Referer={hv}")
            elif hk.lower() == "cookie":
                block.append(f"#EXTVLCOPT:http-cookie={hv}\n")
                header_pairs.append(f"Cookie={hv}")
            else:
                header_pairs.append(f"{hk}={hv}")

        if header_pairs:
            manifest_headers = "&".join(header_pairs)
            block.append(f"#KODIPROP:inputstream.adaptive.manifest_headers={manifest_headers}\n")
            block.append(f"#KODIPROP:inputstream.adaptive.stream_headers={manifest_headers}\n")

    if headers:
        pipe_str = "&".join([f"{k}={v}" for k, v in headers.items()])
        final_url = f"{base_url}|{pipe_str}\n"
    else:
        final_url = f"{base_url}\n"

    block.append(final_url)
    return "".join(block)

# ================= M3U BUILDER (HANDLES ALL MATCH SERVERS) =================
def generate_m3u(playlist_name, items, total_count, live_count, current_time):
    lines = ["#EXTM3U\n"]
    lines.append(f"# Playlist Name  : {playlist_name}\n")
    lines.append(f"# Channels Amount: {total_count}\n")
    lines.append(f"# Live Count     : {live_count}\n")
    lines.append(f"# Last Update    : {current_time}\n\n")

    for it in items:
        title = it.get("title") or it.get("VideoName") or it.get("name") or it.get("event") or "Live Event"
        logo = it.get("cover_image") or it.get("ThumbnailTV") or it.get("ThumbnailStandard") or it.get("logo") or it.get("image") or ""
        
        stream_urls = it.get("stream_url")
        if isinstance(stream_urls, dict) and stream_urls:
            for s_name, s_url in stream_urls.items():
                if s_url and is_mpd_or_m3u8(str(s_url)):
                    stream_title = f"{title} ({s_name})" if len(stream_urls) > 1 else title
                    lines.append(build_m3u_channel_block(stream_title, logo, playlist_name, str(s_url).strip(), it))
        elif "streams" in it and isinstance(it["streams"], list) and len(it["streams"]) > 0:
            for s in it["streams"]:
                if not isinstance(s, dict):
                    continue
                s_url = s.get("link") or s.get("url") or s.get("stream_url")
                if not s_url or not is_mpd_or_m3u8(str(s_url)):
                    continue
                s_name = s.get("name") or "Server"
                stream_title = f"{title} ({s_name})" if len(it["streams"]) > 1 else title
                lines.append(build_m3u_channel_block(stream_title, logo, playlist_name, str(s_url).strip(), s))
        else:
            streams = extract_all_mpd_m3u8_streams(it)
            if not streams:
                continue
            for s_name, s_url in streams.items():
                stream_title = f"{title} ({s_name})" if len(streams) > 1 else title
                lines.append(build_m3u_channel_block(stream_title, logo, playlist_name, s_url, it))

    return "".join(lines)

def run():
    print(f"Total Playlist URLs Found in Secrets: {len(lines)}")
    out_dir = os.path.dirname(os.path.abspath(__file__))

    all_master_events = []
    seen_master_matches = {}

    for idx, line in enumerate(lines, 1):
        name, slug, url = get_playlist_info(line)
        current_source_items = []
        is_bing_source = ("bing" in name.lower() or "bing" in url.lower() or "bingstream" in url.lower())
        is_tapmad_source = ("tapmad" in name.lower() or "tapmad" in url.lower())

        try:
            print(f"[{idx}/{len(lines)}] Fetching: {name} from {url[:45]}...")
            raw_json = fetch_data(url)
            raw_items = extract_items_from_json(raw_json)

            for it in raw_items:
                if not isinstance(it, dict):
                    continue
                if is_sonyliv_item(it):
                    current_source_items.append(transform_sonyliv_item(it))
                elif is_tapmad_source or is_tapmad_item(it):
                    current_source_items.append(transform_tapmad_item(it))
                elif is_bing_source or is_bingstream_item(it):
                    current_source_items.append(transform_bingstream_item(it))
                elif is_custom_streams_item(it):
                    current_source_items.append(transform_custom_streams_item(it))
                else:
                    current_source_items.append(it)

            # Promo ছাড়া সরাসরি ইভেন্ট লিস্ট
            final_json_events = current_source_items

            src_total = len(final_json_events)
            src_live = sum(1 for it in final_json_events if is_live_event(it))

            # ১. সোর্স ভিত্তিক JSON ফাইল তৈরি (যেমন: tapmad.json)
            source_json = {
                "playlist_name": name,
                "channels_amount": src_total,
                "live_count": src_live,
                "last_update": current_time,
                "events": final_json_events
            }
            json_file = os.path.join(out_dir, f"{slug}.json")
            with open(json_file, "w", encoding="utf-8") as f:
                json.dump(source_json, f, indent=2, ensure_ascii=False)

            # ২. সোর্স ভিত্তিক M3U ফাইল তৈরি (যেমন: tapmad.m3u)
            m3u_data = generate_m3u(name, current_source_items, src_total, src_live, current_time)
            m3u_file = os.path.join(out_dir, f"{slug}.m3u")
            with open(m3u_file, "w", encoding="utf-8") as f:
                f.write(m3u_data)

            print(f"✅ Success: {name} -> Created: {slug}.json & {slug}.m3u (Total: {src_total}, Live: {src_live})")

            # মাস্টার কালেকশনে যোগ করা (ডুপ্লিকেট ম্যাচ মার্জিং সহ)
            for it in current_source_items:
                match_id = str(it.get("match_id", "")).strip()
                title = str(it.get("title", "")).strip()
                norm_key = match_id if (match_id and match_id != "None") else re.sub(r'[^a-zA-Z0-9]', '', title.lower())

                if norm_key and norm_key in seen_master_matches:
                    existing = seen_master_matches[norm_key]
                    exist_streams = existing.get("stream_url", {})
                    new_streams = it.get("stream_url", {})
                    if isinstance(exist_streams, dict) and isinstance(new_streams, dict):
                        for s_lbl, s_url in new_streams.items():
                            if s_url not in exist_streams.values():
                                exist_streams[f"{s_lbl} ({name})"] = s_url
                else:
                    if norm_key:
                        seen_master_matches[norm_key] = it
                    all_master_events.append(it)

        except Exception as e:
            print(f"❌ Error fetching {name} ({url}): {e}")

    # ================= ৩. অল-ইন-ওয়ান মাস্টার live_events.json & live_events.m3u =================
    master_total = len(all_master_events)
    master_live = sum(1 for it in all_master_events if is_live_event(it))

    master_json = {
        "playlist_name": "All Live Sports Events",
        "channels_amount": master_total,
        "live_count": master_live,
        "last_update": current_time,
        "events": all_master_events
    }
    master_json_file = os.path.join(out_dir, "live_events.json")
    with open(master_json_file, "w", encoding="utf-8") as f:
        json.dump(master_json, f, indent=2, ensure_ascii=False)

    master_m3u_data = generate_m3u("Live Sports Events", all_master_events, master_total, master_live, current_time)
    master_m3u_file = os.path.join(out_dir, "live_events.m3u")
    with open(master_m3u_file, "w", encoding="utf-8") as f:
        f.write(master_m3u_data)

    print(f"🌟 Master Playlist Created: live_events.json & live_events.m3u (Total: {master_total}, Live: {master_live})")
    print(f"🎯 All playlists processed successfully!")

if __name__ == "__main__":
    run()
