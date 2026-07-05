"""
MovieBox.ph  →  streamhit MySQL  |  Pure Python scraper
========================================================
One script. No Go server. No extra files needed.

How it works:
  - Catalog Discovery: queries the Mobile Search API with common alphanumeric prefix queries
                       using signed request headers (HMAC-MD5 signature)
  - Detail:            via proxy on h5-api.aoneroom.com OR Mobile Detail API
  - Stream URLs:       via proxy on h5.aoneroom.com/wefeed-h5-bff/subject/play-resource
                       (uses account cookie from proxy + Singapore IP spoof)

Modes:
  python scraper.py              full scrape (resumes from checkpoint)
  python scraper.py --fresh      reset & full scrape from page 1
  python scraper.py --watch      watch new episodes/movies every 10 min
  python scraper.py --watch --interval 300   (custom interval in seconds)
  python scraper.py --fresh --watch          scrape everything, then keep watching
"""

import argparse, json, logging, random, sys, time, urllib.parse
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional, Union, Tuple
import requests, pymysql, pymysql.cursors
import base64
import hashlib
import hmac

# ══════════════════════════════════════════════════════════════
#  CONFIG & SIGNATURE HELPERS
# ══════════════════════════════════════════════════════════════

DB = dict(host="localhost", user="lcsyxfen_streamhit", password="lcsyxfen_streamhit", database="lcsyxfen_streamhit", charset="utf8mb4")

PROXY      = "http://194.127.178.223/?url="   # Singapore proxy
H5         = "https://h5.aoneroom.com"
H5_BFF     = H5 + "/wefeed-h5-bff"
H5_API     = "https://h5-api.aoneroom.com/wefeed-h5api-bff"

COOKIE_EP  = H5_BFF  + "/app/get-latest-app-pkgs?app_name=moviebox"
LIST_EP    = H5_BFF  + "/subject/list"
BANNER_EP  = H5_BFF  + "/banner/list"
DETAIL_EP  = H5_API  + "/detail"
PLAY_EP    = H5_BFF  + "/web/subject/download"

UA         = "okhttp/4.12.0"
PAGE_SIZE  = 20
DELAY      = 1.0          # seconds between API calls
WATCH_MIN  = 10           # default watch interval in minutes

BLOCKED = {"basketball","mobile game","pc game",
           "reality","wrestling","yoruba","gameplay","volleyball"}

# Mobile API host pool & settings
HOST_POOL = [
    "https://api6.aoneroom.com",
    "https://api5.aoneroom.com",
    "https://api4.aoneroom.com",
    "https://api4sg.aoneroom.com",
    "https://api3.aoneroom.com",
    "https://api.inmoviebox.com",
]

SECRET_KEY_DEFAULT = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O"
SECRET_KEY_ALT = "Xqn2nnO41/L92o1iuXhSLHTbXvY4Z5ZZ62m8mSLA"

MOBILE_UA = "com.community.oneroom/50020045 (Linux; U; Android 11; en_US; Redmi 2201117TY; Build/RP1A.200720.011; Cronet/135.0.7012.3)"
CLIENT_INFO = '{"package_name":"com.community.oneroom","version_name":"3.0.03.0529.03","version_code":50020045,"os":"android","os_version":"11","install_ch":"ps","device_id":"59dbb601ebdf457b98d24b6118b6ee1b","install_store":"ps","gaid":"02b61de8-0cf2-4bc4-9d52-fa28a1c9ee1b","brand":"Redmi","model":"Redmi 2201117TY","system_language":"en","net":"NETWORK_WIFI","region":"US","timezone":"Asia/Kolkata","sp_code":"40401","X-Play-Mode":"2"}'

_auth_token = None

# ══════════════════════════════════════════════════════════════
#  LOGGING
# ══════════════════════════════════════════════════════════════
log = logging.getLogger("moviebox")
log.setLevel(logging.INFO)
fmt = logging.Formatter("%(asctime)s [%(levelname)s] %(message)s")
sh  = logging.StreamHandler(sys.stdout); sh.setFormatter(fmt); log.addHandler(sh)
fh  = logging.FileHandler("scraper.log", encoding="utf-8"); fh.setFormatter(fmt); log.addHandler(fh)

# ══════════════════════════════════════════════════════════════
#  HTTP & Cryptographic Signature helpers
# ══════════════════════════════════════════════════════════════

_sess    = requests.Session()
_cookie  = {"val": "", "exp": 0.0}


def _proxied(url: str) -> str:
    return PROXY + urllib.parse.quote(url, safe="")


def get_cookie() -> str:
    now = time.time()
    if _cookie["val"] and now < _cookie["exp"]:
        return _cookie["val"]
    try:
        r = _sess.get(_proxied(COOKIE_EP),
                      headers={"User-Agent": UA, "Accept": "application/json"},
                      timeout=15)
        parts = []
        for hdr in r.raw.headers.getlist("Set-Cookie"):
            parts.append(hdr.split(";")[0].strip())
        val = "; ".join(parts) if parts else "; ".join(f"{c.name}={c.value}" for c in r.cookies)
        if val:
            _cookie["val"] = val
            _cookie["exp"] = now + 3600
            log.info("Cookie refreshed")
    except Exception as e:
        log.warning("Cookie refresh failed: %s", e)
    return _cookie["val"]


def _hdrs(extra: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    h = {"User-Agent": UA, "Accept": "application/json",
         "Accept-Language": "en-US,en;q=0.5",
         "Referer": H5, "Cookie": get_cookie()}
    if extra:
        h.update(extra)
    return h


def pget(url: str, params: Optional[Dict[str, Any]] = None, retries=3) -> Optional[Dict[str, Any]]:
    """GET via proxy with retries."""
    target = url + ("?" + urllib.parse.urlencode(params) if params else "")
    for i in range(retries):
        try:
            r = _sess.get(_proxied(target), headers=_hdrs(), timeout=25)
            if r.status_code == 200:
                return r.json()
            log.debug("HTTP %d %s (attempt %d)", r.status_code, url, i+1)
            if r.status_code in (429, 503):
                time.sleep(5*(i+1))
            else:
                return None
        except Exception as e:
            log.debug("pget error (attempt %d): %s", i+1, e)
            time.sleep(2*(i+1))
    return None


def dget(url: str, params: Optional[Dict[str, Any]] = None) -> Optional[Dict[str, Any]]:
    """Direct GET (no proxy) — for list/banner which don't need it."""
    try:
        r = _sess.get(url, params=params,
                      headers={"User-Agent": UA, "Accept": "application/json",
                               "Referer": H5, "Cookie": get_cookie()},
                      timeout=20)
        if r.status_code == 200:
            return r.json()
    except Exception as e:
        log.debug("dget error: %s", e)
    return None


def md5_hex(data: bytes) -> str:
    return hashlib.md5(data).hexdigest()


def b64_decode(value: str) -> bytes:
    padding = (4 - len(value) % 4) % 4
    return base64.b64decode(value + "=" * padding)


def b64_encode(data: bytes) -> str:
    return base64.b64encode(data).decode()


def generate_x_client_token(timestamp_ms: int) -> str:
    ts = str(timestamp_ms)
    reversed_ts = ts[::-1]
    hash_val = md5_hex(reversed_ts.encode())
    return f"{ts},{hash_val}"


def _sorted_query_string(url: str) -> str:
    parsed = urllib.parse.urlparse(url)
    qs = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
    if not qs:
        return ""
    parts = []
    for key in sorted(qs.keys()):
        for value in qs[key]:
            parts.append(f"{key}={value}")
    return "&".join(parts)


def build_canonical_string(method: str, accept: Optional[str], content_type: Optional[str], url: str, body: Optional[str], timestamp_ms: int) -> str:
    parsed = urllib.parse.urlparse(url)
    path = parsed.path or ""
    query = _sorted_query_string(url)
    canonical_url = f"{path}?{query}" if query else path

    body_bytes = body.encode("utf-8") if body is not None else None
    if body_bytes is not None:
        truncated = body_bytes[:102400]
        body_hash = md5_hex(truncated)
        body_length = str(len(body_bytes))
    else:
        body_hash = ""
        body_length = ""

    return (
        f"{method.upper()}\n"
        f"{accept or ''}\n"
        f"{content_type or ''}\n"
        f"{body_length}\n"
        f"{timestamp_ms}\n"
        f"{body_hash}\n"
        f"{canonical_url}"
    )


def generate_x_tr_signature(method: str, accept: Optional[str], content_type: Optional[str], url: str, body: Optional[str] = None, use_alt_key: bool = False, timestamp_ms: Optional[int] = None) -> str:
    ts = timestamp_ms if timestamp_ms is not None else int(time.time() * 1000)
    canonical = build_canonical_string(method, accept, content_type, url, body, ts)
    secret_b64 = SECRET_KEY_ALT if use_alt_key else SECRET_KEY_DEFAULT
    secret_bytes = b64_decode(secret_b64)
    mac = hmac.new(secret_bytes, canonical.encode("utf-8"), hashlib.md5)
    sig_b64 = b64_encode(mac.digest())
    return f"{ts}|2|{sig_b64}"


def build_signed_headers(method: str, url: str, accept: str = "application/json", content_type: str = "application/json", body: Optional[str] = None, auth_token: Optional[str] = None) -> Dict[str, str]:
    ts = int(time.time() * 1000)
    headers = {
        "User-Agent": MOBILE_UA,
        "Accept": accept,
        "Content-Type": content_type,
        "Connection": "keep-alive",
        "X-Client-Token": generate_x_client_token(ts),
        "x-tr-signature": generate_x_tr_signature(method, accept, content_type, url, body, False, ts),
        "X-Client-Info": CLIENT_INFO,
        "X-Client-Status": "0",
    }
    if auth_token:
        headers["Authorization"] = f"Bearer {auth_token}"
    return headers


def mobile_request(method: str, path: str, params: Optional[Dict[str, Any]] = None, json_body: Optional[Dict[str, Any]] = None) -> Optional[Dict[str, Any]]:
    global _auth_token
    path_and_query = path
    if params:
        parts = []
        for k, v in sorted(params.items()):
            parts.append(f"{k}={v}")
        path_and_query = f"{path}?{'&'.join(parts)}"
        
    body_str = json.dumps(json_body) if json_body else None
    
    for base in HOST_POOL:
        url = f"{base}{path_and_query}"
        headers = build_signed_headers(
            method=method,
            url=url,
            accept="application/json",
            content_type="application/json; charset=utf-8" if json_body else "application/json",
            body=body_str,
            auth_token=_auth_token
        )
        try:
            if method.upper() == "GET":
                r = _sess.get(url, headers=headers, timeout=20)
            else:
                r = _sess.post(url, headers=headers, data=body_str.encode("utf-8") if body_str else b"", timeout=20)
                
            if r.status_code == 200:
                x_user = r.headers.get("x-user", "")
                if x_user:
                    try:
                        token = json.loads(x_user).get("token")
                        if token:
                            _auth_token = token
                    except Exception:
                        pass
                
                resp_json = r.json()
                if resp_json.get("code") == 0:
                    return resp_json.get("data")
                else:
                    log.warning("Mobile API response error code: %s (%s)", resp_json.get("code"), resp_json.get("msg"))
            elif r.status_code in (429, 503):
                log.debug("Host %s returned status %d. Retrying next host...", base, r.status_code)
                continue
        except Exception as e:
            log.debug("Connection error to %s: %s", base, e)
    return None


def mobile_get_details(subject_id: str) -> Optional[Dict[str, Any]]:
    # 1. Details
    details = mobile_request("GET", "/wefeed-mobile-bff/subject-api/get", {"subjectId": subject_id})
    if not details:
        return None
    # 2. Seasons
    seasons_data = mobile_request("GET", "/wefeed-mobile-bff/subject-api/season-info", {"subjectId": subject_id})
    if seasons_data:
        details["seasons"] = seasons_data.get("seasons")
    return details


# ══════════════════════════════════════════════════════════════
#  MovieBox API calls (H5 & Web components)
# ══════════════════════════════════════════════════════════════

def api_banners() -> list:
    data = dget(BANNER_EP, {"pageSize": 50})
    return (data or {}).get("data", {}).get("list", [])


STREAM_PROXY = None


def get_sg_ip() -> str:
    return f"1.21.{random.randint(224, 255)}.{random.randint(1, 254)}"


def api_play(subject_id: str, season=0, episode=0, detail_path="") -> Tuple[List[Any], List[Any]]:
    """Get stream URLs directly with Singapore IP spoofing.
    Returns (qualities, captions).
    """
    ref_dpath = detail_path or f"path-{subject_id}"
    referer = f"https://123movienow.cc/spa/videoPlayPage/movies/{ref_dpath}?id={subject_id}&type=/movie/detail"
    
    sg_ip = get_sg_ip()
    headers = {
        "User-Agent": UA,
        "Referer": referer,
        "Origin": "https://123movienow.cc",
        "X-Forwarded-For": sg_ip,
        "CF-Connecting-IP": sg_ip,
        "X-Real-IP": sg_ip,
    }
    cookie = get_cookie()
    if cookie:
        headers["Cookie"] = cookie

    params = {
        "subjectId": subject_id,
        "se": str(season),
        "ep": str(episode)
    }

    # Fetch with retries
    for attempt in range(3):
        try:
            proxies = None
            if STREAM_PROXY:
                proxies = {"http": STREAM_PROXY, "https": STREAM_PROXY}
            r = _sess.get(PLAY_EP, params=params, headers=headers, proxies=proxies, timeout=20)
            if r.status_code == 200:
                data = r.json()
                if data.get("code") == 0:
                    d = data.get("data", {})
                    qualities = []
                    for dl in (d.get("downloads") or []):
                        url = dl.get("url") or ""
                        res = int(dl.get("resolution") or 0)
                        sz  = int(dl.get("size") or 0)
                        if url:
                            qualities.append({"res": res, "size": sz, "url": url})
                    if not qualities:
                        for q in (d.get("qualityList") or []):
                            url = q.get("url") or ""
                            res = int(q.get("resolution") or 0)
                            if url:
                                qualities.append({"res": res, "size": 0, "url": url})
                    if not qualities:
                        for q in (d.get("hls") or []):
                            url = q.get("url") or ""
                            res = int(q.get("resolution") or 0)
                            if url:
                                qualities.append({"res": res, "size": 0, "url": url})
                    
                    captions = [
                        {"lang": c.get("language") or c.get("lan") or "", "url": c.get("url", "")}
                        for c in (d.get("captions") or [])
                        if c.get("url")
                    ]
                    return qualities, captions
            log.debug("api_play attempt %d failed (status=%d)", attempt + 1, r.status_code)
            time.sleep(2 * (attempt + 1))
        except Exception as e:
            log.debug("api_play error on attempt %d: %s", attempt + 1, e)
            time.sleep(2 * (attempt + 1))
            
    return [], []


# ══════════════════════════════════════════════════════════════
#  DB helpers
# ══════════════════════════════════════════════════════════════

def db_conn():
    return pymysql.connect(**DB)


def _img(o) -> Optional[str]:
    if not o: return None
    return o.get("url") if isinstance(o, dict) else str(o)


def _f(v) -> Optional[float]:
    try: return float(v) or None
    except: return None


def upsert_subject(c, s: dict):
    c.execute("""
        INSERT INTO subjects
          (subject_id,title,subject_type,cover,backdrop,rating,
           release_date,country,genre,description,is_cam,detail_path,tmdb_id,dubs)
        VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
        ON DUPLICATE KEY UPDATE
          title=VALUES(title),
          cover=VALUES(cover),
          backdrop=IF(VALUES(backdrop) IS NOT NULL AND VALUES(backdrop) != '', VALUES(backdrop), backdrop),
          rating=IF(VALUES(rating) IS NOT NULL, VALUES(rating), rating),
          release_date=VALUES(release_date),
          country=IF(VALUES(country) IS NOT NULL AND VALUES(country) != '', VALUES(country), country),
          genre=IF(VALUES(genre) IS NOT NULL AND VALUES(genre) != '', VALUES(genre), genre),
          description=IF(VALUES(description) IS NOT NULL AND VALUES(description) != '', VALUES(description), description),
          detail_path=VALUES(detail_path),
          tmdb_id=IF(VALUES(tmdb_id) IS NOT NULL AND VALUES(tmdb_id) != '', VALUES(tmdb_id), tmdb_id),
          dubs=IF(VALUES(dubs) IS NOT NULL AND VALUES(dubs) != '[]' AND VALUES(dubs) != '', VALUES(dubs), dubs),
          updated_at=CURRENT_TIMESTAMP
    """, (s["id"], s["title"], s["stype"], s.get("cover"), s.get("backdrop"),
          s.get("rating"), s.get("rel_date"), s.get("country"), s.get("genre"),
          s.get("desc"), s.get("is_cam", 0), s.get("dpath"), s.get("tmdb_id"),
          json.dumps(s.get("dubs", []), ensure_ascii=False)))


def upsert_play(c, sid: str, season: int, ep: int, res: int, size: int, url: str):
    rid = f"{sid}_{season}_{ep}_{res}"
    exp = datetime.now() + timedelta(hours=8)
    c.execute("""
        INSERT INTO play_resources
          (resource_id,subject_id,season,episode,resolution,size,resource_link,expires_at)
        VALUES(%s,%s,%s,%s,%s,%s,%s,%s)
        ON DUPLICATE KEY UPDATE resource_link=VALUES(resource_link),expires_at=VALUES(expires_at)
    """, (rid, sid, season, ep, res, size, url, exp))


def upsert_caption(c, sid: str, season: int, ep: int, lang: str, url: str):
    cid = f"{sid}_{season}_{ep}_{lang}"
    c.execute("""
        INSERT INTO captions(caption_id,subject_id,resource_id,label,lang,url)
        VALUES(%s,%s,%s,%s,%s,%s)
        ON DUPLICATE KEY UPDATE url=VALUES(url)
    """, (cid, sid, cid, lang, lang, url))


def upsert_season(c, sid: str, snum: int, ep_count: int, eps: list):
    c.execute("""
        INSERT INTO seasons(subject_id,season_number,episode_count,episodes_list)
        VALUES(%s,%s,%s,%s)
        ON DUPLICATE KEY UPDATE episode_count=VALUES(episode_count),
          episodes_list=VALUES(episodes_list)
    """, (sid, snum, ep_count, json.dumps(eps, ensure_ascii=False)))


def upsert_banner(c, sid: str, title: str, img: str, dpath: str, stype: int):
    c.execute("""
        INSERT INTO banners(subject_id,title,image_url,detail_path,subject_type)
        VALUES(%s,%s,%s,%s,%s)
        ON DUPLICATE KEY UPDATE title=VALUES(title),image_url=VALUES(image_url),
          detail_path=VALUES(detail_path)
    """, (sid, title, img, dpath, stype))


def _val(row) -> Any:
    if row is None:
        return None
    if isinstance(row, dict):
        return list(row.values())[0]
    return row[0]


def get_progress(c, stype: int) -> int:
    c.execute("SELECT current_page FROM scraper_progress WHERE subject_type=%s", (stype,))
    row = c.fetchone()
    val = _val(row)
    return val if val is not None else 1


def set_progress(c, stype: int, page: int):
    c.execute("""
        INSERT INTO scraper_progress(subject_type,current_page) VALUES(%s,%s)
        ON DUPLICATE KEY UPDATE current_page=VALUES(current_page)
    """, (stype, page))


# ══════════════════════════════════════════════════════════════
#  Parse + process one subject
# ══════════════════════════════════════════════════════════════

def blocked(genre_str: str, title_str: str = "") -> bool:
    lo_g = (genre_str or "").lower()
    lo_t = (title_str or "").lower()
    return any(b in lo_g or b in lo_t for b in BLOCKED)


def parse(item: Dict[str, Any], detail: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    m = {**item, **(detail or {})}
    genre = m.get("genre") or ""
    if isinstance(genre, list):
        genre = ", ".join((g.get("name") if isinstance(g, dict) else str(g)) for g in genre)
    country = m.get("countryName") or m.get("country") or ""
    if isinstance(country, list):
        country = ", ".join(country)
    dubs = [{"subjectId": d.get("subjectId"), "lanName": d.get("lanName"),
              "lanCode": d.get("lanCode")} for d in (m.get("dubs") or [])]
              
    dpath = m.get("detailPath") or ""
    if not dpath and m.get("detailUrl"):
        durl = m.get("detailUrl")
        if "/detail/" in durl:
            dpath = durl.split("/detail/")[-1]
            
    return dict(
        id=str(m.get("subjectId") or ""), title=m.get("title") or "",
        stype=int(m.get("subjectType") or 1), cover=_img(m.get("cover")),
        backdrop=_img(m.get("backdrop") or m.get("stills")), rating=_f(m.get("imdbRatingValue") or m.get("imdbRate") or m.get("rate")),
        rel_date=m.get("releaseDate"), country=country, genre=genre,
        desc=m.get("description"), is_cam=int(m.get("isCam") or 0),
        dpath=dpath, tmdb_id=m.get("tmdbId"), dubs=dubs,
    )


def process(cur, item: dict) -> bool:
    sid = str(item.get("subjectId") or "")
    if not sid:
        return False

    time.sleep(DELAY * 0.5)
    detail_data = mobile_get_details(sid)
    if not detail_data:
        return False
        
    subject = parse(detail_data)

    if blocked(subject["genre"], subject["title"]):
        log.info("  [SKIP] %s (%s)", subject["title"], subject["genre"])
        return False

    upsert_subject(cur, subject)

    # Check database optimizations to skip fetching streams if still valid and unexpired
    if subject["stype"] == 1:
        cur.execute("SELECT COUNT(*) FROM play_resources WHERE subject_id = %s AND season = 0 AND episode = 0 AND expires_at > NOW()", (sid,))
        if _val(cur.fetchone()) > 0:
            log.info("  [SKIP] [%s] %s — streams are still valid", sid, subject["title"])
            return True

    # Resolve Original/English version for stream retrieval
    target_sid = sid
    target_dpath = subject["dpath"]
    for d in detail_data.get("dubs", []):
        if d.get("original") or d.get("lanCode") == "en":
            target_sid = d.get("subjectId")
            durl = d.get("detailUrl") or ""
            if "/detail/" in durl:
                target_dpath = durl.split("/detail/")[-1]
            break

    # Get seasons from data.resource.seasons
    seasons = detail_data.get("seasons") or []

    if seasons:
        # Multi-season TV
        for s_info in seasons:
            snum     = int(s_info.get("se") or 1)
            ep_count = int(s_info.get("maxEp") or 0)
            ep_list  = []
            for ep_num in range(1, ep_count + 1):
                # Check if this episode already has unexpired play resources
                cur.execute("SELECT COUNT(*) FROM play_resources WHERE subject_id = %s AND season = %s AND episode = %s AND expires_at > NOW()", (sid, snum, ep_num))
                if _val(cur.fetchone()) > 0:
                    ep_list.append({"episode": ep_num, "title": ""})
                    continue
                    
                time.sleep(DELAY * 0.3)
                qs, caps = api_play(target_sid, snum, ep_num, target_dpath)
                for q in qs:
                    upsert_play(cur, sid, snum, ep_num, q["res"], q["size"], q["url"])
                for cap in caps:
                    upsert_caption(cur, sid, snum, ep_num, cap["lang"], cap["url"])
                ep_list.append({"episode": ep_num, "title": ""})
            upsert_season(cur, sid, snum, ep_count, ep_list)
    else:
        # Movie or single episode
        time.sleep(DELAY)
        qs, caps = api_play(target_sid, 0, 0, target_dpath)
        for q in qs:
            upsert_play(cur, sid, 0, 0, q["res"], q["size"], q["url"])
        for cap in caps:
            upsert_caption(cur, sid, 0, 0, cap["lang"], cap["url"])

    streams = len(qs) if not seasons else "?"
    log.info("  [OK] [%s] %s — streams=%s", sid, subject["title"], streams)
    
    # Update has_resource based on fetched play_resources
    cur.execute("SELECT COUNT(*) FROM play_resources WHERE subject_id = %s AND expires_at > NOW()", (sid,))
    has_res = bool(_val(cur.fetchone()) > 0)
    cur.execute("UPDATE subjects SET has_resource = %s WHERE subject_id = %s", (has_res, sid))
    
    return True


# ══════════════════════════════════════════════════════════════
#  Discovery Loop (Discover ALL items in catalog)
# ══════════════════════════════════════════════════════════════

def db_cleanup_blocked(db):
    log.info("Cleaning up blocked items from database...")
    cur = db.cursor()
    deleted_subjects = 0
    for b in BLOCKED:
        # Find all subject IDs to delete
        cur.execute("SELECT subject_id FROM subjects WHERE LOWER(genre) LIKE %s OR LOWER(title) LIKE %s", (f"%{b}%", f"%{b}%"))
        sids = [row[0] for row in cur.fetchall()]
        if not sids:
            continue
            
        format_strings = ','.join(['%s'] * len(sids))
        
        # Delete dependencies first
        cur.execute(f"DELETE FROM play_resources WHERE subject_id IN ({format_strings})", tuple(sids))
        cur.execute(f"DELETE FROM captions WHERE subject_id IN ({format_strings})", tuple(sids))
        cur.execute(f"DELETE FROM seasons WHERE subject_id IN ({format_strings})", tuple(sids))
        cur.execute(f"DELETE FROM subjects WHERE subject_id IN ({format_strings})", tuple(sids))
        
        deleted_subjects += len(sids)
        
    if deleted_subjects > 0:
        db.commit()
        log.info("Successfully cleaned up %d blocked subjects from database.", deleted_subjects)
    else:
        log.info("No blocked subjects found in database to clean up.")
    cur.close()


def discover_catalog(db):
    log.info("=" * 60)
    log.info("Catalog Discovery via Mobile search/homepage APIs")
    log.info("=" * 60)
    
    # Run cleanup of existing blocked items first
    db_cleanup_blocked(db)
    
    discovered_sids = set()
    
    # 1. Homepage Discovery
    for tab_id in [0, 4, 5]:  # All, Movie, TV
        log.info("Checking homepage tab %d...", tab_id)
        res = mobile_request("GET", "/wefeed-mobile-bff/tab-operating", {"page": "1", "tabId": str(tab_id), "version": ""})
        if res and res.get("items"):
            cur = db.cursor()
            for group in res.get("items", []):
                for s in group.get("subjects", []):
                    sid = str(s.get("subjectId"))
                    if sid:
                        try:
                            subject = parse(s)
                            if blocked(subject["genre"], subject["title"]):
                                continue
                            upsert_subject(cur, subject)
                            discovered_sids.add(sid)
                        except Exception as e:
                            log.error("Failed to save homepage subject %s: %s", sid, e)
            db.commit()
            cur.close()

    # 2. Search Discovery
    search_configs = [
        # (list of terms, max_pages)
        # Regional keywords (high priority, fetch all pages up to 15 to get all regional content)
        (['bangla', 'bangladesh', 'bengali', 'natok', 'dhaka', 'kolkata', 'house full', 'sakin sarisuri', 'baaji', 'myself allen', 'bachelor point', 'karagar', 'taqdeer', 'sikandar box', 'rakkhosh'], 100),
        # Genre keywords (high priority, fetch all pages up to 15 to get all genre content)
        (['Action', 'Adventure', 'Animation', 'Comedy', 'Crime', 'Documentary', 'Drama', 'Family', 'Fantasy', 'History', 'Horror', 'Music', 'Mystery', 'Romance', 'Sci-Fi', 'Thriller', 'War', 'Western'], 100),
        # Single-letter prefixes (to catch short names)
        ([chr(i) for i in range(ord('a'), ord('z')+1)], 30),
        # Digits and common words
        ([str(i) for i in range(10)] + ['the', 'and', 'man', 'love', 'world', 'story', 'time', 'life', 'night', 'day', 'show', 'movie', 'star', 'war', 'dark', 'dead', 'last', 'black', 'white', 'red', 'blue', 'fire', 'water'], 100),
        # Two-letter prefixes (comprehensive catalog scan)
        ([chr(i) + chr(j) for i in range(ord('a'), ord('z')+1) for j in range(ord('a'), ord('z')+1)], 30)
    ]
    
    for terms, max_pages in search_configs:
        for term in terms:
            log.info("Searching catalog for query '%s' (max %d pages)...", term, max_pages)
            page = 1
            while True:
                payload = {
                    "keyword": term,
                    "page": page,
                    "perPage": 20,
                    "subjectType": 0
                }
                res = mobile_request("POST", "/wefeed-mobile-bff/subject-api/search", json_body=payload)
                if not res or not res.get("items"):
                    break
                    
                cur = db.cursor()
                items = res.get("items", [])
                for s in items:
                    sid = str(s.get("subjectId"))
                    if sid:
                        try:
                            subject = parse(s)
                            if blocked(subject["genre"], subject["title"]):
                                continue
                            upsert_subject(cur, subject)
                            discovered_sids.add(sid)
                        except Exception as e:
                            log.error("Failed to save search subject %s: %s", sid, e)
                db.commit()
                cur.close()
                
                pager = res.get("pager", {})
                if not pager.get("hasMore") or page >= max_pages:
                    break
                page += 1
                time.sleep(0.05)
            time.sleep(0.05)
            
    log.info("Discovery done. Discovered & registered %d unique subjects in DB.", len(discovered_sids))



def get_subjects_to_scrape(db, fresh=False) -> List[Dict[str, Any]]:
    cur = db.cursor(pymysql.cursors.DictCursor)
    if fresh:
        cur.execute("SELECT subject_id, detail_path, subject_type FROM subjects")
    else:
        # Select subjects that either:
        # 1. Have no play resources in DB
        # 2. Or have expired play resources (expires_at < NOW())
        # 3. Or are TV series (subject_type = 2) where we check if there are new episodes
        # 4. Or have missing/incomplete metadata (description, rating, backdrop, dubs)
        cur.execute("""
            SELECT s.subject_id, s.detail_path, s.subject_type
            FROM subjects s
            LEFT JOIN (
                SELECT subject_id, MIN(expires_at) as min_exp
                FROM play_resources
                GROUP BY subject_id
            ) p ON p.subject_id = s.subject_id
            WHERE p.min_exp IS NULL 
               OR p.min_exp < NOW() 
               OR s.subject_type = 2
               OR s.description IS NULL OR s.description = ''
               OR s.rating IS NULL
               OR s.backdrop IS NULL
               OR s.dubs IS NULL OR s.dubs = '[]'
        """)
    rows = cur.fetchall()
    cur.close()
    return rows


# ══════════════════════════════════════════════════════════════
#  Full scrape
# ══════════════════════════════════════════════════════════════

def scrape(db, fresh=False):
    log.info("=" * 60)
    log.info("MovieBox.ph  →  streamhit  |  Full Catalog Scrape")
    log.info("=" * 60)

    # Banners
    banners = api_banners()
    if banners:
        cur = db.cursor()
        for b in banners:
            sid = str(b.get("subjectId") or "")
            if sid:
                upsert_banner(cur, sid, b.get("title") or "",
                              _img(b.get("url") or b.get("imageUrl")) or "",
                              b.get("detailPath") or "",
                              int(b.get("subjectType") or 1))
        db.commit(); cur.close()
        log.info("Banners saved: %d", len(banners))

    # Run catalog discovery
    discover_catalog(db)

    # Run detail & play resource scraper for pending/expired items
    subjects_to_process = get_subjects_to_scrape(db, fresh)
    log.info("Total subjects requiring detail/stream checks: %d", len(subjects_to_process))

    for idx, s in enumerate(subjects_to_process):
        log.info("[%d/%d] Processing [%s] %s...", idx + 1, len(subjects_to_process), s["subject_id"], s["detail_path"])
        cur = db.cursor()
        try:
            item = {
                "subjectId": s["subject_id"],
                "detailPath": s["detail_path"],
                "subjectType": s["subject_type"]
            }
            process(cur, item)
            db.commit()
        except KeyboardInterrupt:
            raise
        except Exception as e:
            log.error("Failed to process subject %s: %s", s["subject_id"], e, exc_info=True)
        finally:
            cur.close()
        time.sleep(DELAY)


# ══════════════════════════════════════════════════════════════
#  Watch: detect new movies/episodes every N minutes
# ══════════════════════════════════════════════════════════════

def watch(db, interval_secs=WATCH_MIN*60):
    log.info("=" * 60)
    log.info("Episode/Movie Watcher  |  interval=%ds", interval_secs)
    log.info("=" * 60)

    while True:
        log.info("[WATCH] Checking for new content...")

        # --- Check new subjects on homepage ---
        cur = db.cursor()
        for tab_id in [0, 4, 5]:
            res = mobile_request("GET", "/wefeed-mobile-bff/tab-operating", {"page": "1", "tabId": str(tab_id), "version": ""})
            if res and res.get("items"):
                for group in res.get("items", []):
                    for s in group.get("subjects", []):
                        sid = str(s.get("subjectId"))
                        if not sid:
                            continue
                        cur.execute("SELECT 1 FROM subjects WHERE subject_id=%s", (sid,))
                        if cur.fetchone():
                            continue   # already in DB
                        
                        log.info("[WATCH][NEW] %s", s.get("title", ""))
                        try:
                            subject = parse(s)
                            if blocked(subject["genre"], subject["title"]):
                                continue
                            upsert_subject(cur, subject)
                            db.commit()
                            
                            process(cur, {"subjectId": sid, "detailPath": subject["dpath"], "subjectType": subject["stype"]})
                            db.commit()
                        except Exception as e:
                            log.error("[WATCH] Failed to save/process new homepage subject %s: %s", sid, e)
                        time.sleep(DELAY)
        cur.close()

        # --- Check existing TV seasons for new episodes ---
        cur = db.cursor(pymysql.cursors.DictCursor)
        cur.execute("""
            SELECT s.subject_id, s.title, s.detail_path,
                   se.season_number, se.episode_count, se.episodes_list
            FROM subjects s
            JOIN seasons se ON se.subject_id = s.subject_id
        """)
        rows = cur.fetchall()
        cur.close()

        for row in rows:
            sid, title = row["subject_id"], row["title"]
            dpath      = row["detail_path"] or ""
            snum       = row["season_number"]
            old_count  = row["episode_count"] or 0

            time.sleep(DELAY)
            detail_data = mobile_get_details(sid)
            if not detail_data:
                continue

            # Resolve Original/English version for stream retrieval
            target_sid = sid
            target_dpath = dpath
            for d in detail_data.get("dubs", []):
                if d.get("original") or d.get("lanCode") == "en":
                    target_sid = d.get("subjectId")
                    durl = d.get("detailUrl") or ""
                    if "/detail/" in durl:
                        target_dpath = durl.split("/detail/")[-1]
                    break

            seasons = detail_data.get("seasons") or []
            for s_info in seasons:
                if int(s_info.get("se") or 1) != snum:
                    continue
                new_count = int(s_info.get("maxEp") or 0)
                if new_count <= old_count:
                    break
                log.info("[WATCH][NEW EP] %s  S%d: %d→%d eps", title, snum, old_count, new_count)
                try:
                    existing = {ep["episode"] for ep in json.loads(row["episodes_list"] or "[]")}
                except Exception:
                    existing = set()
                ep_list = list(json.loads(row["episodes_list"] or "[]"))
                
                cur2 = db.cursor()
                for ep_num in range(1, new_count + 1):
                    if ep_num not in existing:
                        qs, caps = api_play(target_sid, snum, ep_num, target_dpath)
                        for q in qs:
                            upsert_play(cur2, sid, snum, ep_num, q["res"], q["size"], q["url"])
                        for cap in caps:
                            upsert_caption(cur2, sid, snum, ep_num, cap["lang"], cap["url"])
                        ep_list.append({"episode": ep_num})
                        time.sleep(DELAY * 0.5)
                upsert_season(cur2, sid, snum, new_count, ep_list)
                db.commit(); cur2.close()
                break

        log.info("[WATCH] Check done. Sleeping %ds...", interval_secs)
        time.sleep(interval_secs)


# ══════════════════════════════════════════════════════════════
#  Entry point
# ══════════════════════════════════════════════════════════════

def main():
    ap = argparse.ArgumentParser(description="MovieBox.ph → streamhit  |  pure Python")
    ap.add_argument("--fresh",    action="store_true", help="Reset progress, scrape from page 1")
    ap.add_argument("--watch",    action="store_true", help="Watch for new content after scrape")
    ap.add_argument("--interval", type=int, default=WATCH_MIN*60,
                    help=f"Watch interval in seconds (default {WATCH_MIN*60})")
    ap.add_argument("--stream-proxy", type=str, default=None,
                    help="Proxy URL for stream download requests (e.g. http://ip:port)")
    ap.add_argument("--discover-only", action="store_true", help="Only run catalog discovery")
    args = ap.parse_args()

    global STREAM_PROXY
    STREAM_PROXY = args.stream_proxy

    db = db_conn()
    try:
        if args.discover_only:
            discover_catalog(db)
        elif not args.watch:
            # Full scrape only
            scrape(db, fresh=args.fresh)
        else:
            # Scrape first, then watch
            scrape(db, fresh=args.fresh)
            watch(db, interval_secs=args.interval)
    except KeyboardInterrupt:
        log.warning("Interrupted. Progress saved.")
    finally:
        db.close()
        log.info("Done.")


if __name__ == "__main__":
    main()
