import os
import re
import json
import httpx
import urllib.parse
from urllib.parse import urlparse, parse_qsl
import asyncio
import random
import time
import datetime
import base64
import hashlib
import hmac
import aiomysql
from dotenv import load_dotenv
from fastapi import FastAPI, Request, HTTPException, Query, Response
from fastapi.responses import HTMLResponse, StreamingResponse, JSONResponse, RedirectResponse, PlainTextResponse
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from contextlib import asynccontextmanager

# ==========================================================================
# CONFIGURATION
# ==========================================================================
base_dir = os.path.dirname(os.path.abspath(__file__))
env_path = os.path.join(base_dir, ".env")
load_dotenv(env_path)

DB_HOST = os.getenv("DB_HOST", "127.0.0.1")
DB_PORT = int(os.getenv("DB_PORT", "3306"))
DB_USER = os.getenv("DB_USER", "root")
DB_PASSWORD = os.getenv("DB_PASSWORD", "")
DB_NAME = os.getenv("DB_NAME", "moviebox")

db_pool = None
db_init_lock = None

API_BASE = "https://h5-api.aoneroom.com"
GATEWAY_SECRET_ONLINE = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O"
DEFAULT_GUEST_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1aWQiOjcwNjU5NDg0MTAyMTM4MTYyMzIsInV0cCI6MSwiZXhwIjoxNzkxNzMyMjMzLCJpYXQiOjE3ODM5NTU5MzN9.7iyEzTj4vWAbOF0oXwNnZ0p3Nc1QaO6K9eMiGFyVfGs"

_slug_to_id = {}
_id_to_slug = {}
_banners_cache = None
_banners_cache_time = 0

def md5_hex(data):
    if not data: return ""
    if isinstance(data, str): data = data.encode('utf-8')
    return hashlib.md5(data).hexdigest()

def generate_client_token():
    ts = str(int(time.time() * 1000))
    return f"{ts},{md5_hex(ts[::-1])}"

def generate_tr_signature(method: str, url: str, body_data: str = "", timestamp: int = None) -> str:
    if timestamp is None:
        timestamp = int(time.time() * 1000)
    parsed_url = urlparse(url)
    path = parsed_url.path
    query_params = parse_qsl(parsed_url.query, keep_blank_values=True)
    if query_params:
        query_params.sort(key=lambda x: x[0])
        query_string = "&".join([f"{k}={v}" for k, v in query_params])
        resource = f"{path}?{query_string}"
    else:
        resource = path
    body_for_md5 = body_data[:0x19000] if body_data else ""
    body_md5 = md5_hex(body_for_md5) if body_data else ""
    canonical_list = [
        method.upper(),
        "application/json",
        "application/json;charset=UTF-8",
        str(len(body_data)) if body_data else "",
        str(timestamp),
        body_md5,
        resource
    ]
    canonical_string = "\n".join(canonical_list)
    key = base64.b64decode(GATEWAY_SECRET_ONLINE)
    h = hmac.new(key, canonical_string.encode('utf-8'), hashlib.md5)
    signature_b64 = base64.b64encode(h.digest()).decode('utf-8')
    return f"{timestamp}|2|{signature_b64}"

MB_HOST_POOL = [
    "https://api6.aoneroom.com",
    "https://api5.aoneroom.com",
    "https://api4.aoneroom.com",
    "https://api3.aoneroom.com"
]
_mb_current_version_code = 50020131
_mb_current_version_name = "4.0.03.0922.03"
_last_version_auto_check = 0

async def auto_refresh_mb_version():
    global _mb_current_version_code, _mb_current_version_name, _last_version_auto_check
    now = time.time()
    if now - _last_version_auto_check < 43200:
        return
    _last_version_auto_check = now
    try:
        url = "https://h5.aoneroom.com/wefeed-h5-bff/app/get-latest-app-pkgs?app_name=moviebox"
        async with httpx.AsyncClient(timeout=8.0) as client:
            resp = await client.get(url)
            if resp.status_code == 200:
                data = resp.json().get("data") or []
                for pkg in data:
                    if pkg.get("pkgName") == "com.community.oneroom" and pkg.get("versionCode"):
                        vc = int(pkg["versionCode"])
                        if vc > _mb_current_version_code:
                            _mb_current_version_code = vc
                            _mb_current_version_name = pkg.get("versionName") or _mb_current_version_name
                            print(f"[Auto-Updater] MovieBox client dynamically upgraded to code {vc} ({_mb_current_version_name})")
                            break
    except Exception:
        pass

def get_mb_client_info() -> str:
    return json.dumps({
        "package_name": "com.community.oneroom",
        "version_name": _mb_current_version_name,
        "version_code": _mb_current_version_code,
        "os": "android",
        "os_version": "9",
        "install_ch": "ps",
        "device_id": "141c9f8c638592d7fc7365c638f230b9",
        "install_store": "ps",
        "gaid": "e42d5548-e08d-4125-b81b-4efa8a35b3e1",
        "brand": "Redmi",
        "model": "M2007J20CG",
        "system_language": "en",
        "net": "NETWORK_WIFI",
        "region": "US",
        "timezone": "America/New_York",
        "sp_code": "40401",
        "X-Play-Mode": "2"
    })

def get_mb_user_agent() -> str:
    return f"com.community.oneroom/{_mb_current_version_code} (Linux; U; Android 9; en_US; M2007J20CG; Build/PQ3A.190605.03081104; Cronet/135.0.7012.3)"

class MovieBoxHttpClient:
    def __init__(self, host_pool=MB_HOST_POOL, timeout=15.0):
        self._host_pool = host_pool
        self._timeout = timeout
        self._client = None
        self._runtime_token = None

    async def __aenter__(self):
        self._client = httpx.AsyncClient(timeout=self._timeout, follow_redirects=True)
        return self

    async def __aexit__(self, *args):
        if self._client:
            await self._client.aclose()

    def _build_headers(self, method, url, body="", content_type="application/json"):
        ts = int(time.time() * 1000)
        accept = "application/json"
        parsed = urlparse(url)
        path = parsed.path or ""
        qs = parse_qsl(parsed.query, keep_blank_values=True)
        if qs:
            qs.sort(key=lambda x: x[0])
            query = "&".join([f"{k}={v}" for k, v in qs])
            canonical_url = f"{path}?{query}"
        else:
            canonical_url = path

        body_bytes = body.encode("utf-8") if body else None
        if body_bytes is not None:
            truncated = body_bytes[:0x19000]
            body_hash = md5_hex(truncated)
            body_length = str(len(body_bytes))
        else:
            body_hash = ""
            body_length = ""

        canonical = (
            f"{method.upper()}\n"
            f"{accept}\n"
            f"{content_type}\n"
            f"{body_length}\n"
            f"{ts}\n"
            f"{body_hash}\n"
            f"{canonical_url}"
        )
        padding = (4 - len(GATEWAY_SECRET_ONLINE) % 4) % 4
        key = base64.b64decode(GATEWAY_SECRET_ONLINE + "=" * padding)
        mac = hmac.new(key, canonical.encode("utf-8"), hashlib.md5)
        sig = f"{ts}|2|{base64.b64encode(mac.digest()).decode()}"

        headers = {
            "User-Agent": get_mb_user_agent(),
            "Accept": accept,
            "Content-Type": content_type,
            "Connection": "keep-alive",
            "X-Client-Token": generate_client_token(),
            "x-tr-signature": sig,
            "X-Client-Info": get_mb_client_info(),
            "X-Client-Status": "0"
        }
        if self._runtime_token:
            headers["Authorization"] = f"Bearer {self._runtime_token}"
        return headers

    def _absorb_x_user(self, headers):
        x_user = headers.get("x-user", "")
        if x_user:
            try:
                p = json.loads(x_user)
                t = p.get("token")
                if t:
                    self._runtime_token = t
            except Exception:
                pass

    async def get(self, path, params=None):
        query = ("?" + "&".join(f"{k}={v}" for k, v in params.items())) if params else ""
        last_resp = None
        for base in self._host_pool:
            url = f"{base}{path}{query}"
            try:
                headers = self._build_headers("GET", url, content_type="application/json")
                resp = await self._client.get(url, headers=headers)
                self._absorb_x_user(resp.headers)
                last_resp = resp
                if resp.status_code not in (403, 429, 500, 502, 503, 504):
                    return base, resp
            except Exception:
                continue
        return self._host_pool[0], last_resp

    async def post(self, path, payload=None, params=None):
        query = ("?" + "&".join(f"{k}={v}" for k, v in params.items())) if params else ""
        body = json.dumps(payload or {}) if isinstance(payload, dict) else (str(payload or "") if payload else "")
        last_resp = None
        for base in self._host_pool:
            url = f"{base}{path}{query}"
            try:
                headers = self._build_headers("POST", url, body, content_type="application/json; charset=utf-8")
                resp = await self._client.post(url, headers=headers, content=body.encode("utf-8"))
                self._absorb_x_user(resp.headers)
                last_resp = resp
                if resp.status_code not in (403, 429, 500, 502, 503, 504):
                    return base, resp
            except Exception:
                continue
        return self._host_pool[0], last_resp

_mb_client = None
_mb_lock = asyncio.Lock()

async def get_moviebox_client():
    global _mb_client
    if _mb_client is None:
        async with _mb_lock:
            if _mb_client is None:
                client = MovieBoxHttpClient(timeout=15.0)
                await client.__aenter__()
                try:
                    await client.get("/wefeed-mobile-bff/tab-operating", params={"tabId": "0"})
                except Exception as e:
                    print(f"Token bootstrap notice: {e}")
                _mb_client = client
    return _mb_client

async def request_moviebox_mobile(endpoint: str, method: str = "GET", params: dict = None, data: dict = None) -> dict:
    try:
        client = await get_moviebox_client()
        for attempt in range(2):
            try:
                if method.upper() == "POST":
                    base, resp = await client.post(endpoint, payload=data or {}, params=params)
                else:
                    base, resp = await client.get(endpoint, params=params)
                
                res_json = resp.json()
                if resp.status_code in (401, 441) or res_json.get("code") in (401, 441):
                    client._runtime_token = None
                    await client.get("/wefeed-mobile-bff/tab-operating", params={"tabId": "0"})
                    continue
                return res_json
            except Exception as e:
                if attempt == 1:
                    print(f"Mobile API error ({endpoint}): {e}")
                    return {}
    except Exception as e:
        print(f"Mobile API fatal error ({endpoint}): {e}")
        return {}
    return {}

ADULT_GENRES = {
    'erotic', 'hot', 'adult', '18+', '18', 'ecchi', 'hentai', 'porn', 'pornography',
    'sex', 'sensual', 'nudity', 'sexual', 'uncensored', 'nsfw', 'softcore', 'hardcore',
    'smut', 'yaoi', 'yuri', 'mature'
}

ADULT_KEYWORDS = [
    '18+', '18 +', 'r18', 'r-18', 'r30', 'r-30', 'nc-17', 'erotic', 'erotica', 'softcore', 'hardcore',
    'porn', 'pornography', 'adult movie', 'adult series', 'adult film', 'adult animation',
    'sensual', 'uncensored', 'nudity', 'sexual', 'masturbat', 'seduction', 'striptease',
    'kaam sastra', 'charmsukh', 'palang tod', 'riti riwaj', 'kavita bhabhi', 'chull',
    'hotspot', 'gandii baat', 'kama sutra', 'kamasutra', 'mastram', 'tandoor', 'rasikan',
    'dani daniels', 'mia khalifa', 'eva elfie', 'kendra lust', 'johnny sins',
    'lana rhoades', 'angela white', 'riley reid', 'leah gotti', 'sunny leone adult',
    'ullu', 'kooku', 'primeshots', 'hunters', 'voovi', 'moodx', 'besharams',
    'rabbit movies', 'neonx', 'hotx', 'feneo', 'jalva', 'boomx', 'big bang',
    'natsuzuma', 'nikuen', 'shitsurakuen', 'anime edition', 'niku mesu', 'boin', 'mankitsu',
    'overflow', 'boku no pico', 'kuroinu', 'futanari', 'ahegao', 'chikan', 'ero ',
    'eroge', 'oppai', 'tentacle', 'savita bhabhi', 'boobs', 'tits', 'pussy', 'creampie',
    'blowjob', 'gangbang', 'horny', 'xxx', 'seiso de bitch', 'mesu-tachi', 'succubus',
    'sexfriend', 'stepmom', 'bhabhi', 'desi sex', 'desi porn'
]

def is_adult_content(item: dict) -> bool:
    if not isinstance(item, dict):
        return False

    # 1. Check genres
    genres = item.get("genre") or []
    if isinstance(genres, str):
        genres = [g.strip() for g in genres.replace("/", ",").split(",")]
    elif not isinstance(genres, list):
        genres = [str(genres)]

    for g in genres:
        if str(g).lower().strip() in ADULT_GENRES:
            return True

    # 2. Check content ratings
    rating = str(item.get("classification") or item.get("ageRating") or item.get("rating") or "").lower().strip()
    if rating in ("18", "18+", "r18", "r-18", "r30", "nc-17", "adult", "x", "xxx"):
        return True

    # 3. Check title, description, content, detailPath, tags, cover
    title = str(item.get("title") or "").lower()
    desc = str(item.get("description") or item.get("content") or "").lower()
    dpath = str(item.get("detailPath") or item.get("detailUrl") or "").lower()
    tags = str(item.get("tags") or item.get("tagList") or "").lower()
    cover = str(item.get("cover") or "").lower()
    combined = f"{title} {desc} {dpath} {tags} {cover}"

    for kw in ADULT_KEYWORDS:
        if kw in title or kw in dpath or re.search(r'\b' + re.escape(kw) + r'\b', combined):
            return True

    return False

async def get_latest_banners():
    global _banners_cache, _banners_cache_time
    now = time.time()
    if _banners_cache and (now - _banners_cache_time < 600):
        return _banners_cache

    banner_items = []
    seen_ids = set()

    try:
        rec_res = await request_moviebox_mobile(
            "/wefeed-mobile-bff/subject-api/daily-movie-rec",
            method="POST",
            data={"page": 1, "pageSize": 20}
        )
        raw_items = rec_res.get("data", {}).get("items") or []
        for it in raw_items:
            sid = str(it.get("subjectId") or "")
            if not sid or sid in seen_ids or is_adult_content(it):
                continue
            seen_ids.add(sid)
            title = it.get("title") or ""
            detail_path = it.get("detailPath") or ""
            if not detail_path and it.get("detailUrl"):
                detail_path = it["detailUrl"].rstrip("/").split("/")[-1]
            if not detail_path:
                clean_slug = re.sub(r'[^a-zA-Z0-9]+', '-', title.lower()).strip('-')
                detail_path = f"{clean_slug}-{sid}"
            _slug_to_id[detail_path] = sid
            _slug_to_id[sid] = sid

            cover = it.get("cover") or {}
            cover_url = cover.get("url") if isinstance(cover, dict) else (cover if isinstance(cover, str) else "")
            
            banner_items.append({
                "subjectId": sid,
                "title": title,
                "content": title,
                "image": cover_url,
                "detailPath": detail_path,
                "subjectType": it.get("subjectType", 1),
                "subject": {
                    "subjectId": sid,
                    "title": title,
                    "detailPath": detail_path,
                    "cover": {"url": cover_url} if cover_url else {},
                    "genre": it.get("genre", "Trending"),
                    "imdbRatingValue": str(it.get("imdbRatingValue") or "7.5"),
                    "releaseDate": it.get("releaseDate") or "2026",
                    "countryName": it.get("countryName") or "USA",
                    "subjectType": it.get("subjectType", 1)
                }
            })
    except Exception as e:
        print(f"Error fetching latest banners: {e}")

    if not banner_items:
        try:
            h5_data = await _make_request(f"{API_BASE}/wefeed-h5api-bff/home?host=moviebox.ph")
            for op in h5_data.get("data", {}).get("operatingList", []):
                if op.get("type") == "BANNER":
                    banner_items.extend(op.get("banner", {}).get("items", []))
        except Exception as e:
            print(f"H5 banner fallback error: {e}")

    _banners_cache = banner_items
    _banners_cache_time = now
    return banner_items

_latest_cache = {}
_latest_cache_time = {}

async def get_latest_content(subject_type: int = 0, limit: int = 36) -> list:
    global _latest_cache, _latest_cache_time
    cache_key = f"type_{subject_type}"
    now = time.time()
    if cache_key in _latest_cache and (now - _latest_cache_time.get(cache_key, 0) < 600):
        return _latest_cache[cache_key]

    seen = set()
    raw_candidates = []

    # 1. Fresh daily recommendations from MovieBox Mobile
    try:
        rec_res = await request_moviebox_mobile(
            "/wefeed-mobile-bff/subject-api/daily-movie-rec",
            method="POST",
            data={"page": 1, "pageSize": 20}
        )
        for it in (rec_res.get("data", {}).get("items") or []):
            sid = str(it.get("subjectId") or "")
            if sid and sid not in seen:
                seen.add(sid)
                raw_candidates.append(it)
    except Exception as e:
        print(f"Error fetching daily recs: {e}")

    # 2. Year 2026 search (latest releases)
    try:
        s26 = await request_moviebox_mobile(
            "/wefeed-mobile-bff/subject-api/search",
            method="POST",
            data={"keyword": "2026", "page": 1, "pageSize": 20, "type": subject_type or 0}
        )
        for it in (s26.get("data", {}).get("items") or []):
            sid = str(it.get("subjectId") or "")
            if sid and sid not in seen:
                seen.add(sid)
                raw_candidates.append(it)
    except Exception as e:
        print(f"Error fetching 2026 latest: {e}")

    # 3. Year 2025 search
    try:
        s25 = await request_moviebox_mobile(
            "/wefeed-mobile-bff/subject-api/search",
            method="POST",
            data={"keyword": "2025", "page": 1, "pageSize": 15, "type": subject_type or 0}
        )
        for it in (s25.get("data", {}).get("items") or []):
            sid = str(it.get("subjectId") or "")
            if sid and sid not in seen:
                seen.add(sid)
                raw_candidates.append(it)
    except Exception as e:
        print(f"Error fetching 2025 latest: {e}")

    # Filter by subject_type if requested (1 = movie, 2 = tv)
    if subject_type in (1, 2):
        raw_candidates = [it for it in raw_candidates if it.get("subjectType") == subject_type]

    # Filter out type 6 (music), type 9 (clips/sumo/wwe), type 7 (short vertical dramas)
    raw_candidates = [it for it in raw_candidates if it.get("subjectType") not in (6, 7, 9)]

    # Sort newest first
    def get_sort_key(it):
        d = it.get("releaseDate") or ""
        return d if d else "1970-01-01"

    raw_candidates.sort(key=get_sort_key, reverse=True)

    cleaned = []
    for it in raw_candidates[:limit]:
        sid = str(it.get("subjectId") or "")
        title = it.get("title") or ""
        detail_path = it.get("detailPath") or ""
        if not detail_path and it.get("detailUrl"):
            detail_path = it["detailUrl"].rstrip("/").split("/")[-1]
        if not detail_path:
            clean_slug = re.sub(r'[^a-zA-Z0-9]+', '-', title.lower()).strip('-')
            detail_path = f"{clean_slug}-{sid}"

        _slug_to_id[detail_path] = sid
        _slug_to_id[sid] = sid

        cover = it.get("cover") or {}
        if isinstance(cover, str):
            cover = {"url": cover}

        genres = it.get("genre") or []
        if isinstance(genres, str):
            genres = [g.strip() for g in genres.split(",") if g.strip()]

        cleaned.append({
            "subjectId": sid,
            "title": title,
            "detailPath": detail_path,
            "subjectType": it.get("subjectType", 1),
            "cover": cover,
            "imdbRatingValue": str(it.get("imdbRatingValue") or "7.5"),
            "releaseDate": it.get("releaseDate") or "",
            "genre": genres,
            "duration": it.get("duration") or "",
            "hasResource": bool(it.get("hasResource", True)),
            "isCam": bool(it.get("isCam", False))
        })

    _latest_cache[cache_key] = cleaned
    _latest_cache_time[cache_key] = now
    return cleaned

WORKER_PROXIES = [
    "https://frosty-tree-ae87.vidnest-1.workers.dev",
    "https://summer-hat-3d00.vidnest-2.workers.dev",
    "https://misty-salad-49cf.vidnest-3.workers.dev",
    "https://dry-darkness-c431.vudnest-4.workers.dev",
]

DEFAULT_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36",
    "Referer": "https://moviebox.ph/",
    "Origin": "https://moviebox.ph",
    "Accept": "application/json",
}

PLAYER_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36",
    "Accept": "application/json",
}

worker_index = 0
def get_next_worker():
    global worker_index
    w = WORKER_PROXIES[worker_index]
    worker_index = (worker_index + 1) % len(WORKER_PROXIES)
    return w

_bearer_token = None
http_client = httpx.AsyncClient(timeout=25.0)

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

def get_random_singapore_ip():
    start_ip, end_ip = random.choice(SINGAPORE_IP_RANGES)
    start_long = (start_ip[0] << 24) | (start_ip[1] << 16) | (start_ip[2] << 8) | start_ip[3]
    end_long = (end_ip[0] << 24) | (end_ip[1] << 16) | (end_ip[2] << 8) | end_ip[3]
    random_long = random.randint(start_long, end_long)
    return f"{(random_long >> 24) & 255}.{(random_long >> 16) & 255}.{(random_long >> 8) & 255}.{random_long & 255}"

_h5_cookie = None

async def _get_h5_cookie() -> str:
    global _h5_cookie
    if _h5_cookie:
        return _h5_cookie
    ip = get_random_singapore_ip()
    headers = {
        "X-Forwarded-For": ip,
        "CF-Connecting-IP": ip,
        "X-Real-IP": ip,
        "Accept-Language": "en-US,en;q=0.5",
        "Accept": "application/json",
        "User-Agent": "okhttp/4.12.0",
        "Referer": "https://h5.aoneroom.com"
    }
    cookie_url = "https://h5.aoneroom.com/wefeed-h5-bff/app/get-latest-app-pkgs?app_name=moviebox"
    try:
        async with httpx.AsyncClient(trust_env=False, timeout=15.0) as client:
            resp = await client.get(cookie_url, headers=headers)
            cookie_headers = resp.headers.get_list("Set-Cookie")
            parsed = []
            for cookie in cookie_headers:
                parsed.append(cookie.split(";")[0])
            _h5_cookie = "; ".join(parsed)
    except Exception as e:
        print(f"Error getting H5 cookie: {e}")
    return _h5_cookie or ""

async def _fetch_download_resources(subject_id: str, se: int = 1, ep: int = 1, detail_path: str = "") -> dict:
    cookie = await _get_h5_cookie()
    url = f"https://h5.aoneroom.com/wefeed-h5-bff/web/subject/download?subjectId={subject_id}&se={se}&ep={ep}"
    ip = get_random_singapore_ip()
    headers = {
        "X-Forwarded-For": ip,
        "CF-Connecting-IP": ip,
        "X-Real-IP": ip,
        "Accept-Language": "en-US,en;q=0.5",
        "Accept": "application/json",
        "User-Agent": "okhttp/4.12.0",
        "Origin": "https://123movienow.cc",
        "Referer": f"https://123movienow.cc/spa/videoPlayPage/movies/{detail_path}?id={subject_id}&type=/movie/detail" if detail_path else f"https://123movienow.cc/spa/videoPlayPage/movies/details?id={subject_id}&type=/movie/detail",
        "Cookie": cookie
    }
    try:
        resp = await http_client.get(url, headers=headers, timeout=2.0)
        if resp.status_code == 200:
            data = resp.json().get("data")
            if data:
                return data
    except Exception:
        pass
    return {}

async def _get_bearer_token() -> str:
    global _bearer_token
    if _bearer_token: return _bearer_token
    for attempt in range(3):
        ip = get_random_singapore_ip()
        headers = {
            **DEFAULT_HEADERS,
            "X-Forwarded-For": ip,
            "CF-Connecting-IP": ip,
            "X-Real-IP": ip,
            "Accept-Language": "en-US,en;q=0.9",
        }
        try:
            resp = await http_client.get(f"{API_BASE}/wefeed-h5api-bff/home?host=moviebox.ph", headers=headers)
            x_user = resp.headers.get("x-user")
            if x_user:
                token = json.loads(x_user).get("token")
                if token:
                    _bearer_token = token
                    return _bearer_token
        except Exception as e:
            print(f"Error fetching bearer token (attempt {attempt+1}): {e}")
        await asyncio.sleep(0.5)
    return ""

async def _make_request(url: str, method: str = "GET", payload: dict = None, custom_headers: dict = None) -> dict:
    global _bearer_token
    token = await _get_bearer_token()
    headers = {**DEFAULT_HEADERS, "Authorization": f"Bearer {token}" if token else "", **(custom_headers or {})}
    try:
        if method == "POST": resp = await http_client.post(url, headers=headers, json=payload)
        else: resp = await http_client.get(url, headers=headers)
        x_user = resp.headers.get("x-user")
        if x_user:
            new_token = json.loads(x_user).get("token")
            if new_token: _bearer_token = new_token
        return resp.json()
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"Request failed: {str(e)}")

# ==========================================================================
# DATABASE HELPER & POOL MANAGEMENT
# ==========================================================================
async def init_db():
    global db_pool
    try:
        import warnings
        warnings.filterwarnings("ignore", module="aiomysql")
        db_pool = await aiomysql.create_pool(
            host=DB_HOST,
            port=DB_PORT,
            user=DB_USER,
            password=DB_PASSWORD,
            db=DB_NAME,
            minsize=1,
            maxsize=10,
            autocommit=True,
            connect_timeout=5
        )
        async with db_pool.acquire() as conn:
            async with conn.cursor() as cur:
                await cur.execute("""
                    CREATE TABLE IF NOT EXISTS admins (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        username VARCHAR(100) NOT NULL UNIQUE,
                        password_hash VARCHAR(255) NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """)
                await cur.execute("""
                    CREATE TABLE IF NOT EXISTS subjects (
                        subject_id VARCHAR(50) PRIMARY KEY,
                        title VARCHAR(255) NOT NULL,
                        subject_type INT NOT NULL DEFAULT 1,
                        cover TEXT,
                        detail_path VARCHAR(255),
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """)
                await cur.execute("""
                    CREATE TABLE IF NOT EXISTS banners (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        subject_id VARCHAR(50) NOT NULL UNIQUE,
                        title VARCHAR(255) NOT NULL,
                        image_url TEXT,
                        detail_path VARCHAR(255),
                        subject_type INT DEFAULT 1,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """)
                await cur.execute("""
                    CREATE TABLE IF NOT EXISTS notifications (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        title VARCHAR(255) NOT NULL,
                        message TEXT NOT NULL,
                        subject_id VARCHAR(50),
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """)
                await cur.execute("""
                    CREATE TABLE IF NOT EXISTS fcm_tokens (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        token VARCHAR(191) NOT NULL UNIQUE,
                        device_id VARCHAR(100),
                        model VARCHAR(100),
                        os VARCHAR(50),
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """)
                await cur.execute("""
                    CREATE TABLE IF NOT EXISTS app_versions (
                        version_code INT PRIMARY KEY,
                        version_name VARCHAR(50) NOT NULL,
                        apk_url TEXT NOT NULL,
                        must_update BOOLEAN DEFAULT FALSE,
                        release_notes TEXT,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """)
                await cur.execute("""
                    CREATE TABLE IF NOT EXISTS live_sports (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        title VARCHAR(255) NOT NULL,
                        logo TEXT,
                        team1_name VARCHAR(100),
                        team1_logo TEXT,
                        team2_name VARCHAR(100),
                        team2_logo TEXT,
                        stream_links TEXT NOT NULL,
                        referer TEXT,
                        origin TEXT,
                        use_bd_proxy BOOLEAN DEFAULT TRUE,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """)
                await cur.execute("""
                    CREATE TABLE IF NOT EXISTS live_tv_channels (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        name VARCHAR(255) NOT NULL,
                        logo TEXT,
                        category VARCHAR(100) DEFAULT 'General',
                        stream_links TEXT NOT NULL,
                        referer TEXT,
                        origin TEXT,
                        use_bd_proxy BOOLEAN DEFAULT TRUE,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """)
                await cur.execute("""
                    CREATE TABLE IF NOT EXISTS stream_resources (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        subject_id VARCHAR(50) NOT NULL,
                        season_num INT NOT NULL DEFAULT 1,
                        episode_num INT NOT NULL DEFAULT 1,
                        detail_path VARCHAR(255) DEFAULT '',
                        resources_json MEDIUMTEXT NOT NULL,
                        captions_json MEDIUMTEXT,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        expires_at TIMESTAMP NOT NULL,
                        UNIQUE KEY uniq_sub_se_ep (subject_id, season_num, episode_num),
                        INDEX idx_expires (subject_id, season_num, episode_num, expires_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """)
                # Seed default admin if table is empty
                await cur.execute("SELECT COUNT(*) FROM admins")
                row = await cur.fetchone()
                if row and row[0] == 0:
                    default_pw = "admin123"
                    salt = "streamfit_secure_salt_2026"
                    pw_hash = hashlib.sha256((default_pw + salt).encode('utf-8')).hexdigest()
                    await cur.execute("INSERT INTO admins (username, password_hash) VALUES (%s, %s)", ("admin", pw_hash))
                    print("[Database] Created default admin account (admin / admin123).")
        print("[Database] MySQL pool and tables initialized successfully.")
    except Exception as e:
        print(f"[Database Warning] MySQL connection failed ({e}). Running in standalone mode.")
        db_pool = None

_db_init_attempted = False

async def get_db_pool():
    global db_pool, db_init_lock, _db_init_attempted
    if db_pool is not None:
        return db_pool
    if _db_init_attempted:
        return None
    if db_init_lock is None:
        db_init_lock = asyncio.Lock()
    async with db_init_lock:
        if db_pool is None and not _db_init_attempted:
            _db_init_attempted = True
            await init_db()
    return db_pool

async def db_get_stream_resource(subject_id: str, season: int = 1, episode: int = 1) -> dict:
    pool = await get_db_pool()
    if not pool or not subject_id:
        return None
    try:
        async with pool.acquire() as conn:
            async with conn.cursor(aiomysql.DictCursor) as cur:
                await cur.execute("""
                    SELECT resources_json, captions_json, expires_at 
                    FROM stream_resources 
                    WHERE subject_id = %s AND season_num = %s AND episode_num = %s
                    LIMIT 1
                """, (str(subject_id), int(season), int(episode)))
                row = await cur.fetchone()
                if row:
                    expires_at = row.get("expires_at")
                    now = datetime.datetime.now()
                    if expires_at and expires_at > now:
                        res_list = json.loads(row.get("resources_json") or "[]")
                        cap_list = json.loads(row.get("captions_json") or "[]") if row.get("captions_json") else []
                        if res_list and not any(is_deprecated_or_dummy_url(it.get("url", "")) or is_deprecated_or_dummy_url(it.get("resourceLink", "")) for it in res_list):
                            return {
                                "resources": res_list,
                                "captions": cap_list
                            }
    except Exception as e:
        print(f"[DB Resource Read Error] {e}")
    return None

async def db_save_stream_resource(subject_id: str, season: int = 1, episode: int = 1, detail_path: str = "", resources: list = None, captions: list = None, ttl_hours: float = 24.0):
    pool = await get_db_pool()
    if not pool or not subject_id:
        return
    try:
        res_json = json.dumps(resources or [])
        cap_json = json.dumps(captions or []) if captions is not None else None
        
        hours = ttl_hours if (resources and len(resources) > 0) else 0.05
        expires_at = datetime.datetime.now() + datetime.timedelta(hours=hours)

        async with pool.acquire() as conn:
            async with conn.cursor() as cur:
                if cap_json is not None:
                    await cur.execute("""
                        INSERT INTO stream_resources (subject_id, season_num, episode_num, detail_path, resources_json, captions_json, expires_at)
                        VALUES (%s, %s, %s, %s, %s, %s, %s)
                        ON DUPLICATE KEY UPDATE
                            detail_path = VALUES(detail_path),
                            resources_json = VALUES(resources_json),
                            captions_json = VALUES(captions_json),
                            expires_at = VALUES(expires_at),
                            created_at = CURRENT_TIMESTAMP
                    """, (str(subject_id), int(season), int(episode), detail_path or "", res_json, cap_json, expires_at))
                else:
                    await cur.execute("""
                        INSERT INTO stream_resources (subject_id, season_num, episode_num, detail_path, resources_json, expires_at)
                        VALUES (%s, %s, %s, %s, %s, %s)
                        ON DUPLICATE KEY UPDATE
                            detail_path = VALUES(detail_path),
                            resources_json = VALUES(resources_json),
                            expires_at = VALUES(expires_at),
                            created_at = CURRENT_TIMESTAMP
                    """, (str(subject_id), int(season), int(episode), detail_path or "", res_json, expires_at))
    except Exception as e:
        print(f"[DB Resource Save Error] {e}")

async def db_update_captions(subject_id: str, season: int = 1, episode: int = 1, captions: list = None):
    pool = await get_db_pool()
    if not pool or not subject_id or not captions:
        return
    try:
        cap_json = json.dumps(captions)
        async with pool.acquire() as conn:
            async with conn.cursor() as cur:
                await cur.execute("""
                    UPDATE stream_resources 
                    SET captions_json = %s 
                    WHERE subject_id = %s AND season_num = %s AND episode_num = %s
                """, (cap_json, str(subject_id), int(season), int(episode)))
    except Exception as e:
        print(f"[DB Captions Update Error] {e}")

async def db_save_subject_meta(subject_id: str, title: str, subject_type: int = 1, cover: str = "", detail_path: str = ""):
    pool = await get_db_pool()
    if not pool or not subject_id or not title:
        return
    try:
        async with pool.acquire() as conn:
            async with conn.cursor() as cur:
                await cur.execute("""
                    INSERT INTO subjects (subject_id, title, subject_type, cover, detail_path)
                    VALUES (%s, %s, %s, %s, %s)
                    ON DUPLICATE KEY UPDATE
                        title = VALUES(title),
                        subject_type = VALUES(subject_type),
                        cover = COALESCE(NULLIF(VALUES(cover), ''), cover),
                        detail_path = COALESCE(NULLIF(VALUES(detail_path), ''), detail_path),
                        updated_at = CURRENT_TIMESTAMP
                """, (str(subject_id), str(title), int(subject_type or 1), str(cover or ""), str(detail_path or "")))
    except Exception:
        pass

def _is_process_alive(pid: int) -> bool:
    try:
        if os.name == "nt":
            import ctypes
            kernel32 = ctypes.windll.kernel32
            process = kernel32.OpenProcess(0x00100000, False, pid)
            if process:
                kernel32.CloseHandle(process)
                return True
            return False
        else:
            os.kill(pid, 0)
            return True
    except Exception:
        return False

async def run_sitemap_generator_background():
    enable_startup = os.getenv("ENABLE_STARTUP_SITEMAP", "false").strip().lower() in ("true", "1", "yes")
    if not enable_startup:
        print("[Sitemap] Startup sitemap generation skipped (handled by daily cron). Set ENABLE_STARTUP_SITEMAP=true to enable.")
        return

    pid_file = os.path.join(base_dir, ".sitemap_generator.pid")
    tmp_pid_file = "/tmp/sitemap_generator.pid"

    for pfile in [pid_file, tmp_pid_file]:
        if os.path.exists(pfile):
            try:
                with open(pfile, "r") as f:
                    old_pid = int(f.read().strip())
                if _is_process_alive(old_pid):
                    print(f"[Sitemap] Sitemap generator already running (PID: {old_pid}). Skipping duplicate startup.")
                    return
            except Exception:
                pass

    try:
        import sys
        import subprocess
        print("[Sitemap] Starting background sitemap generator subprocess...")
        proc = subprocess.Popen([sys.executable, os.path.join(base_dir, "sitemap_generator.py")], cwd=base_dir)
        try:
            with open(pid_file, "w") as f:
                f.write(str(proc.pid))
        except Exception:
            pass
        print(f"[Sitemap] Background sitemap generator launched with PID {proc.pid}.")
    except Exception as e:
        print(f"[Sitemap] Failed to start background sitemap generator: {e}")

@asynccontextmanager
async def lifespan(app: FastAPI):
    asyncio.create_task(run_sitemap_generator_background())
    yield
    await http_client.aclose()
    global db_pool
    if db_pool:
        try:
            db_pool.close()
            await db_pool.wait_closed()
        except Exception:
            pass

app = FastAPI(lifespan=lifespan)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_credentials=True, allow_methods=["*"], allow_headers=["*"])

# ── Active User Tracking ───────────────────────────────────────────────────
import sqlite3

_ACTIVE_DB_PATH = os.path.join(base_dir, "active_users.db")

import datetime

_active_web_users = {}
_active_app_users = {}
_last_db_sync = {}

def _init_active_db():
    try:
        with sqlite3.connect(_ACTIVE_DB_PATH, timeout=5) as conn:
            conn.execute("PRAGMA journal_mode=WAL")
            conn.execute("PRAGMA synchronous=NORMAL")
            conn.execute("""
                CREATE TABLE IF NOT EXISTS active_sessions (
                    user_id TEXT PRIMARY KEY,
                    user_type TEXT NOT NULL,
                    last_seen REAL NOT NULL
                )
            """)
            conn.execute("CREATE INDEX IF NOT EXISTS idx_active_last_seen ON active_sessions(last_seen)")
            conn.execute("""
                CREATE TABLE IF NOT EXISTS app_installations (
                    device_id TEXT PRIMARY KEY,
                    ip TEXT,
                    device_model TEXT,
                    os_version TEXT,
                    app_version TEXT,
                    first_seen REAL NOT NULL,
                    session_start REAL NOT NULL,
                    last_seen REAL NOT NULL,
                    daily_active_seconds REAL DEFAULT 0,
                    today_date TEXT DEFAULT '',
                    current_watching TEXT DEFAULT '',
                    watching_detail_path TEXT DEFAULT '',
                    is_watching INTEGER DEFAULT 0
                )
            """)
            conn.execute("CREATE INDEX IF NOT EXISTS idx_installs_last_seen ON app_installations(last_seen)")
            # Upgrade existing tables if columns are missing
            for col, col_def in [
                ("session_start", "REAL DEFAULT 0"),
                ("daily_active_seconds", "REAL DEFAULT 0"),
                ("today_date", "TEXT DEFAULT ''"),
                ("current_watching", "TEXT DEFAULT ''"),
                ("watching_detail_path", "TEXT DEFAULT ''"),
                ("is_watching", "INTEGER DEFAULT 0")
            ]:
                try:
                    conn.execute(f"ALTER TABLE app_installations ADD COLUMN {col} {col_def}")
                except Exception:
                    pass
    except Exception:
        pass

_init_active_db()

def clear_all_app_installations():
    try:
        with sqlite3.connect(_ACTIVE_DB_PATH, timeout=5) as conn:
            conn.execute("DELETE FROM app_installations")
            conn.execute("DELETE FROM active_sessions")
        _active_app_users.clear()
        _active_web_users.clear()
        _last_db_sync.clear()
        return True
    except Exception as e:
        print(f"Error clearing app installations: {e}")
        return False

def record_app_device(
    device_id: str,
    ip: str = "",
    device_model: str = "Android Device",
    os_version: str = "Android",
    app_version: str = "1.0.7",
    current_watching: str = "",
    watching_detail_path: str = "",
    is_watching: int = 0
):
    if not device_id:
        return
    now = time.time()
    today_str = datetime.datetime.fromtimestamp(now).strftime("%Y-%m-%d")
    if device_id.startswith("web_"):
        _active_web_users[device_id] = now
    else:
        _active_app_users[device_id] = now
    try:
        with sqlite3.connect(_ACTIVE_DB_PATH, timeout=2) as conn:
            conn.row_factory = sqlite3.Row
            cur = conn.cursor()
            cur.execute("""
                SELECT first_seen, session_start, last_seen, daily_active_seconds, today_date,
                       current_watching, watching_detail_path, is_watching
                FROM app_installations WHERE device_id = ?
            """, (device_id,))
            row = cur.fetchone()

            if not row:
                first_seen = now
                session_start = now
                daily_active_seconds = 30.0
                today_date = today_str
                c_watching = current_watching
                w_path = watching_detail_path
                w_flag = is_watching
            else:
                first_seen = row["first_seen"] or now
                prev_last_seen = row["last_seen"] or now
                prev_session_start = row["session_start"] or now
                prev_daily_sec = row["daily_active_seconds"] or 0.0
                prev_today_date = row["today_date"] or ""

                # New session if away for more than 5 minutes (300 seconds)
                if (now - prev_last_seen) > 300:
                    session_start = now
                else:
                    session_start = prev_session_start if prev_session_start > 0 else now

                # Reset timer if new calendar day
                if prev_today_date != today_str:
                    daily_active_seconds = 30.0
                    today_date = today_str
                else:
                    delta = min(max(0.0, now - prev_last_seen), 60.0)
                    daily_active_seconds = prev_daily_sec + delta
                    today_date = today_str

                if current_watching != "":
                    c_watching = current_watching
                    w_path = watching_detail_path
                    w_flag = is_watching
                else:
                    c_watching = row["current_watching"] or ""
                    w_path = row["watching_detail_path"] or ""
                    w_flag = row["is_watching"] or 0

            conn.execute("""
                INSERT INTO app_installations (
                    device_id, ip, device_model, os_version, app_version,
                    first_seen, session_start, last_seen, daily_active_seconds, today_date,
                    current_watching, watching_detail_path, is_watching
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(device_id) DO UPDATE SET
                    ip = excluded.ip,
                    device_model = CASE WHEN excluded.device_model != '' THEN excluded.device_model ELSE app_installations.device_model END,
                    os_version = CASE WHEN excluded.os_version != '' THEN excluded.os_version ELSE app_installations.os_version END,
                    app_version = CASE WHEN excluded.app_version != '' THEN excluded.app_version ELSE app_installations.app_version END,
                    session_start = excluded.session_start,
                    last_seen = excluded.last_seen,
                    daily_active_seconds = excluded.daily_active_seconds,
                    today_date = excluded.today_date,
                    current_watching = excluded.current_watching,
                    watching_detail_path = excluded.watching_detail_path,
                    is_watching = excluded.is_watching
            """, (
                device_id, ip, device_model, os_version, app_version,
                first_seen, session_start, now, daily_active_seconds, today_date,
                c_watching, w_path, w_flag
            ))
            u_type = "web" if device_id.startswith("web_") else "app"
            conn.execute(
                "INSERT OR REPLACE INTO active_sessions (user_id, user_type, last_seen) VALUES (?, ?, ?)",
                (device_id, u_type, now)
            )
    except Exception:
        pass

def parse_client_device(user_agent: str) -> tuple[str, str]:
    if not user_agent:
        return "Web Browser", "Web"
    ua_lower = user_agent.lower()

    # OS Detection
    os_name = "Unknown OS"
    if "windows nt 10.0" in ua_lower:
        os_name = "Windows 10/11"
    elif "windows nt 6.3" in ua_lower:
        os_name = "Windows 8.1"
    elif "windows nt 6.1" in ua_lower:
        os_name = "Windows 7"
    elif "macintosh" in ua_lower or "mac os x" in ua_lower:
        os_name = "macOS"
    elif "iphone" in ua_lower:
        os_name = "iOS (iPhone)"
    elif "ipad" in ua_lower:
        os_name = "iPadOS"
    elif "android" in ua_lower:
        os_name = "Android"
    elif "linux" in ua_lower:
        os_name = "Linux"

    # Browser Detection
    browser = "Browser"
    if "edg/" in ua_lower:
        browser = "Edge"
    elif "chrome/" in ua_lower and "chromium" not in ua_lower:
        browser = "Chrome"
    elif "firefox/" in ua_lower:
        browser = "Firefox"
    elif "safari/" in ua_lower and "chrome" not in ua_lower:
        browser = "Safari"
    elif "opera" in ua_lower or "opr/" in ua_lower:
        browser = "Opera"

    if "iphone" in ua_lower:
        device_model = f"iPhone ({browser})"
    elif "android" in ua_lower and "mobile" in ua_lower:
        device_model = f"Android Phone ({browser})"
    elif "ipad" in ua_lower:
        device_model = f"iPad ({browser})"
    elif "windows" in ua_lower:
        device_model = f"PC ({browser})"
    elif "macintosh" in ua_lower:
        device_model = f"Mac ({browser})"
    else:
        device_model = f"Web ({browser})"

    return device_model, os_name

def record_active_user(request: Request):
    try:
        now = time.time()
        client_ip = request.client.host if request.client else "127.0.0.1"
        forwarded = request.headers.get("X-Forwarded-For")
        if forwarded:
            client_ip = forwarded.split(",")[0].strip()
            
        raw_ua = request.headers.get("User-Agent", "")
        user_agent = raw_ua.lower()
        app_dev_id = request.headers.get("X-App-Device-Id") or request.headers.get("device_id")
        
        is_app = (app_dev_id is not None or 
                  "streamfit" in user_agent or 
                  "streamhit" in user_agent or
                  "moviebox" in user_agent or 
                  "dart" in user_agent or 
                  "okhttp" in user_agent or 
                  request.headers.get("X-M-Version") is not None or 
                  request.headers.get("X-Client-Token") is not None)
                  
        user_type = "app" if is_app else "web"
        user_id = app_dev_id or (request.headers.get("X-Client-Token") if is_app else None) or client_ip

        if is_app:
            _active_app_users[user_id] = now
            dev_model = request.headers.get("X-App-Model", "Android Device")
            dev_os = request.headers.get("X-App-OS", "Android")
            dev_ver = request.headers.get("X-App-Version", "1.0.7")
            if app_dev_id:
                record_app_device(app_dev_id, client_ip, dev_model, dev_os, dev_ver)
        else:
            _active_web_users[user_id] = now
            dev_model, dev_os = parse_client_device(raw_ua)
            web_dev_id = f"web_{hashlib.md5((client_ip + raw_ua).encode()).hexdigest()[:12]}"
            
            # Detect current visiting page / movie
            path = request.url.path
            c_watching = ""
            w_path = ""
            is_watching = 0
            if path.startswith("/movie/"):
                w_path = path.replace("/movie/", "").strip()
                c_watching = w_path.replace("-", " ").title()
                is_watching = 1
            elif path.startswith("/play"):
                c_watching = "Playing Video"
                is_watching = 1
            elif path in ("/", "/index.html"):
                c_watching = "Browsing Homepage"
            elif path.startswith("/search"):
                c_watching = "Searching Movies"
            elif path.startswith("/sports"):
                c_watching = "Live Sports"
            elif path.startswith("/tv"):
                c_watching = "Live TV"
            elif path.startswith("/api/"):
                c_watching = "Browsing Site"

            # Record web device install telemetry throttled every 20s
            if now - _last_db_sync.get(web_dev_id, 0) > 20:
                _last_db_sync[web_dev_id] = now
                record_app_device(
                    device_id=web_dev_id,
                    ip=client_ip,
                    device_model=dev_model,
                    os_version=dev_os,
                    app_version="Web",
                    current_watching=c_watching,
                    watching_detail_path=w_path,
                    is_watching=is_watching
                )

        # Throttle DB writes to once every 20s per user to minimize disk I/O
        if now - _last_db_sync.get(user_id, 0) > 20:
            _last_db_sync[user_id] = now
            try:
                with sqlite3.connect(_ACTIVE_DB_PATH, timeout=2) as conn:
                    conn.execute(
                        "INSERT OR REPLACE INTO active_sessions (user_id, user_type, last_seen) VALUES (?, ?, ?)",
                        (user_id, user_type, now)
                    )
            except Exception:
                pass
    except Exception:
        pass

def get_active_users_stats():
    now = time.time()
    cutoff = now - 300  # active within 5 minutes
    
    web_count = 0
    app_count = 0
    total_installs = 0
    db_success = False

    try:
        with sqlite3.connect(_ACTIVE_DB_PATH, timeout=3) as conn:
            conn.execute("DELETE FROM active_sessions WHERE last_seen < ?", (now - 3600,))
            cur = conn.cursor()
            cur.execute("""
                SELECT user_type, COUNT(DISTINCT user_id) 
                FROM active_sessions 
                WHERE last_seen >= ? 
                GROUP BY user_type
            """, (cutoff,))
            rows = cur.fetchall()
            for utype, cnt in rows:
                if utype == "web":
                    web_count = cnt
                elif utype == "app":
                    app_count = cnt

            # Also check app_installations for live apps
            cur.execute("SELECT COUNT(DISTINCT device_id) FROM app_installations WHERE last_seen >= ?", (cutoff,))
            installed_live = cur.fetchone()[0] or 0
            app_count = max(app_count, installed_live)

            cur.execute("SELECT COUNT(*) FROM app_installations")
            total_installs = cur.fetchone()[0] or 0
            db_success = True
    except Exception:
        pass

    if not db_success:
        dead_web = [k for k, v in _active_web_users.items() if v < cutoff]
        for k in dead_web: _active_web_users.pop(k, None)
        dead_app = [k for k, v in _active_app_users.items() if v < cutoff]
        for k in dead_app: _active_app_users.pop(k, None)
        web_count = len(_active_web_users)
        app_count = len(_active_app_users)
        total_installs = app_count

    # Always at least 1 web user if admin is active
    if web_count == 0:
        web_count = 1

    return {
        "total": web_count + app_count,
        "web": web_count,
        "app": app_count,
        "total_installs": total_installs
    }

def get_app_users_list():
    now = time.time()
    cutoff = now - 300  # 5 min live
    today_start = now - (now % 86400)
    users = []
    total_installs = 0
    live_count = 0
    watching_count = 0
    today_count = 0
    try:
        with sqlite3.connect(_ACTIVE_DB_PATH, timeout=3) as conn:
            conn.row_factory = sqlite3.Row
            cur = conn.cursor()
            cur.execute("SELECT COUNT(*) as cnt FROM app_installations")
            total_installs = cur.fetchone()["cnt"]
            cur.execute("SELECT COUNT(*) as cnt FROM app_installations WHERE last_seen >= ?", (cutoff,))
            live_count = cur.fetchone()["cnt"]
            cur.execute("SELECT COUNT(*) as cnt FROM app_installations WHERE last_seen >= ? AND is_watching = 1", (cutoff,))
            watching_count = cur.fetchone()["cnt"]
            cur.execute("SELECT COUNT(*) as cnt FROM app_installations WHERE last_seen >= ?", (today_start,))
            today_count = cur.fetchone()["cnt"]

            cur.execute("""
                SELECT device_id, ip, device_model, os_version, app_version,
                       first_seen, session_start, last_seen, daily_active_seconds,
                       current_watching, watching_detail_path, is_watching
                FROM app_installations
                ORDER BY last_seen DESC
                LIMIT 300
            """)
            for row in cur.fetchall():
                last = row["last_seen"] or 0
                is_online = (last >= cutoff)
                dev_id = str(row["device_id"] or "")
                app_ver = str(row["app_version"] or "")
                u_type = "web" if (dev_id.startswith("web_") or "web" in app_ver.lower()) else "app"
                users.append({
                    "device_id": dev_id,
                    "ip": row["ip"] or "",
                    "device_model": row["device_model"] or ("Web Browser" if u_type == "web" else "Android Device"),
                    "os_version": row["os_version"] or ("Web" if u_type == "web" else "Android"),
                    "app_version": app_ver or ("Web" if u_type == "web" else "1.0.7"),
                    "user_type": u_type,
                    "first_seen": row["first_seen"] or last,
                    "session_start": row["session_start"] or last,
                    "last_seen": last,
                    "daily_active_seconds": int(row["daily_active_seconds"] or 0),
                    "current_watching": row["current_watching"] or "",
                    "watching_detail_path": row["watching_detail_path"] or "",
                    "is_watching": (row["is_watching"] == 1 and is_online),
                    "is_online": is_online
                })
    except Exception:
        pass

    web_count = sum(1 for u in users if u["user_type"] == "web")
    app_count = sum(1 for u in users if u["user_type"] == "app")
    web_live = sum(1 for u in users if u["user_type"] == "web" and u["is_online"])
    app_live = sum(1 for u in users if u["user_type"] == "app" and u["is_online"])

    return {
        "total_installs": total_installs,
        "total_users": len(users),
        "web_count": web_count,
        "app_count": app_count,
        "web_live": web_live,
        "app_live": app_live,
        "live_count": live_count,
        "watching_count": watching_count,
        "today_count": today_count,
        "users": users
    }

@app.middleware("http")
async def track_active_users_middleware(request: Request, call_next):
    path = request.url.path
    if not path.endswith((".css", ".js", ".png", ".jpg", ".jpeg", ".svg", ".ico", ".woff", ".woff2", ".ttf", ".xml")):
        record_active_user(request)
    return await call_next(request)

@app.post("/api/app/heartbeat")
async def api_app_heartbeat(request: Request):
    try:
        data = await request.json()
    except Exception:
        data = {}
    now = time.time()
    client_ip = request.client.host if request.client else "127.0.0.1"
    forwarded = request.headers.get("X-Forwarded-For")
    if forwarded:
        client_ip = forwarded.split(",")[0].strip()

    device_id = data.get("device_id") or request.headers.get("X-App-Device-Id") or client_ip
    device_model = data.get("device_model") or request.headers.get("X-App-Model", "Android Device")
    os_version = data.get("os_version") or request.headers.get("X-App-OS", "Android")
    app_version = data.get("app_version") or request.headers.get("X-App-Version", "1.0.7")
    current_watching = str(data.get("current_watching") or "").strip()
    detail_path = str(data.get("detail_path") or "").strip()
    is_watching_raw = str(data.get("is_watching") or "0").strip()
    is_watching = 1 if is_watching_raw in ("1", "true", "True") else 0

    record_app_device(device_id, client_ip, device_model, os_version, app_version, current_watching, detail_path, is_watching)
    record_active_user(request)
    return {"code": 0, "status": "ok", "t": int(now)}

@app.get("/api/ping")
async def api_ping(request: Request):
    record_active_user(request)
    return {"status": "ok", "t": int(time.time())}

@app.post("/api/fcm/register")
async def register_fcm_token(request: Request):
    try:
        data = await request.json()
        token = str(data.get("token") or "").strip()
        device_id = str(data.get("device_id") or "").strip()
        model = str(data.get("model") or "").strip()
        os_ver = str(data.get("os") or "").strip()

        if not token:
            return JSONResponse(status_code=400, content={"code": 400, "message": "Token is required"})

        pool = await get_db_pool()
        if pool:
            async with pool.acquire() as conn:
                async with conn.cursor() as cur:
                    await cur.execute("""
                        INSERT INTO fcm_tokens (token, device_id, model, os)
                        VALUES (%s, %s, %s, %s)
                        ON DUPLICATE KEY UPDATE
                            device_id = VALUES(device_id),
                            model = VALUES(model),
                            os = VALUES(os),
                            updated_at = CURRENT_TIMESTAMP
                    """, (token, device_id, model, os_ver))
        return {"code": 200, "message": "FCM Token registered successfully"}
    except Exception as e:
        return JSONResponse(status_code=500, content={"code": 500, "message": str(e)})

import admin
app.include_router(admin.router)

import traceback
from fastapi.responses import JSONResponse

@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    err_trace = traceback.format_exc()
    print(f"GLOBAL APP ERROR [{request.method} {request.url.path}]: {exc}\n{err_trace}")
    try:
        with open("/tmp/fastapi_error.log", "a") as f:
            f.write(f"\n--- ERROR: {request.method} {request.url.path} ---\n{err_trace}\n")
    except:
        pass
    return JSONResponse(
        status_code=500,
        content={"code": 500, "message": f"Server Error: {exc}", "trace": err_trace}
    )

@app.middleware("http")
async def add_no_cache_headers(request: Request, call_next):
    response = await call_next(request)
    if request.url.path.startswith("/api/") or request.url.path.startswith("/fetch"):
        response.headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
        response.headers["Pragma"] = "no-cache"
        response.headers["Expires"] = "0"
    return response

async def resolve_subject_id(detail_path: str = "", subject_id: str = "") -> str:
    if subject_id:
        return str(subject_id).strip()
    if not detail_path:
        return ""
    
    detail_path = detail_path.strip()

    # 1. Check in-memory mapping
    if detail_path in _slug_to_id:
        return str(_slug_to_id[detail_path])

    # 2. Extract numeric subjectId from slug tail (e.g. title-803658983365039832)
    if "-" in detail_path:
        tail = detail_path.split("-")[-1]
        if tail.isdigit():
            _slug_to_id[detail_path] = tail
            return tail
    elif detail_path.isdigit():
        _slug_to_id[detail_path] = detail_path
        return detail_path

    # 3. Check MySQL subjects table if DB pool ready
    try:
        pool = await get_db_pool()
        if pool:
            async with pool.acquire() as conn:
                async with conn.cursor() as cur:
                    await cur.execute("SELECT subject_id FROM subjects WHERE detail_path = %s LIMIT 1", (detail_path,))
                    row = await cur.fetchone()
                    if row and row[0]:
                        sid = str(row[0])
                        _slug_to_id[detail_path] = sid
                        return sid
    except Exception:
        pass

    # 4. Check H5 API detail
    try:
        data = await _make_request(f"{API_BASE}/wefeed-h5api-bff/detail?detailPath={detail_path}")
        subj = data.get("data", {}).get("subject") or {}
        sid = subj.get("subjectId")
        if sid:
            sid = str(sid)
            _slug_to_id[detail_path] = sid
            return sid
    except Exception:
        pass

    # 5. Reverse match via MovieBox Mobile search using title words extracted from slug
    try:
        words = detail_path.rsplit('-', 1)[0] if '-' in detail_path else detail_path
        clean_words = ' '.join(re.sub(r'[^a-zA-Z0-9]+', ' ', words).split())
        if clean_words:
            res = await request_moviebox_mobile(
                "/wefeed-mobile-bff/subject-api/search",
                method="POST",
                data={"keyword": clean_words, "q": clean_words, "page": 1, "pageSize": 10, "type": 0}
            )
            items = res.get("data", {}).get("items") or []
            for it in items:
                d_url = it.get("detailUrl") or ""
                it_sid = str(it.get("subjectId") or "")
                it_path = it.get("detailPath") or ""
                if detail_path in d_url or detail_path == it_path or (it_sid and detail_path.endswith(it_sid)):
                    _slug_to_id[detail_path] = it_sid
                    return it_sid
            if items:
                top_sid = str(items[0].get("subjectId") or "")
                if top_sid:
                    _slug_to_id[detail_path] = top_sid
                    return top_sid
    except Exception as e:
        print(f"Error resolving slug '{detail_path}': {e}")

    return ""

async def resolve_detail_path(subject_id: str = "", detail_path: str = "") -> str:
    if detail_path:
        return detail_path.strip()
    if not subject_id:
        return ""
    sid = str(subject_id).strip()
    if sid in _id_to_slug:
        return _id_to_slug[sid]
    # Check MySQL subjects table if DB pool ready
    try:
        pool = await get_db_pool()
        if pool:
            async with pool.acquire() as conn:
                async with conn.cursor() as cur:
                    await cur.execute("SELECT detail_path FROM subjects WHERE subject_id = %s LIMIT 1", (sid,))
                    row = await cur.fetchone()
                    if row and row[0]:
                        slug = str(row[0]).strip()
                        _id_to_slug[sid] = slug
                        _slug_to_id[slug] = sid
                        return slug
    except Exception:
        pass
    # Check mobile API
    try:
        mob_res = await request_moviebox_mobile("/wefeed-mobile-bff/subject-api/get", params={"subjectId": sid})
        mob_data = mob_res.get("data") or {}
        subj = mob_data.get("subject") or mob_data
        if subj and isinstance(subj, dict):
            d_url = subj.get("detailUrl")
            if d_url:
                slug = d_url.rstrip("/").split("/")[-1]
                _id_to_slug[sid] = slug
                _slug_to_id[slug] = sid
                return slug
    except Exception:
        pass
    return ""

def get_brand_config(host: str = "") -> dict:
    host_lower = (host or "").lower()
    if "nxly" in host_lower:
        return {
            "name": "NXLY",
            "name_html": 'NX<span>LY</span>',
            "domain": "nxly.online",
            "full_url": "https://nxly.online",
            "favicon": "/favicon-nxly.svg",
            "logo_icon": '<span class="logo-nx-glyph">NX</span>',
            "by_brand": "By NXLY"
        }
    return {
        "name": "Streamfit",
        "name_html": 'Stream<span>fit</span>',
        "domain": "streamfit.ehealthfinder.com",
        "full_url": "https://streamfit.ehealthfinder.com",
        "favicon": "/favicon.svg",
        "logo_icon": '<i class="fa-solid fa-play"></i>',
        "by_brand": "By Streamfit"
    }

async def get_subject_meta(slug: str, host: str = ""):
    brand = get_brand_config(host)
    meta = {
        "title": f"{brand['name']} - Watch Free Movies & TV Shows",
        "description": f"{brand['name']} offers the best free high-quality streaming.",
        "cover": "https://images.unsplash.com/photo-1594909122845-11baa439b7bf?w=1200&q=80",
        "url": f"{brand['full_url']}/movie/{slug}",
        "schema": ""
    }
    if not slug: return meta
    inner = None
    url = f"{API_BASE}/wefeed-h5api-bff/detail?detailPath={slug}"
    try:
        data = await _make_request(url)
        inner = data.get("data", {}).get("subject", {})
    except Exception:
        pass

    if not inner:
        sid = await resolve_subject_id(slug)
        if sid:
            try:
                mob_res = await request_moviebox_mobile("/wefeed-mobile-bff/subject-api/get", params={"subjectId": sid})
                mob_data = mob_res.get("data") or {}
                inner = mob_data.get("subject") or mob_data
            except Exception:
                pass

    if inner and isinstance(inner, dict):
        meta["title"] = f"Watch {inner.get('title', 'Movie')} - {brand['name']}"
        meta["description"] = inner.get("description", meta["description"]).replace('"', '\\"')
        cover_val = inner.get("cover")
        if isinstance(cover_val, dict):
            meta["cover"] = cover_val.get("url", meta["cover"])
        elif isinstance(cover_val, str):
            meta["cover"] = cover_val
        schema_obj = {
            "@context": "https://schema.org",
            "@type": "VideoObject" if inner.get("subjectType") == 1 else "TVSeries",
            "name": inner.get("title", ""),
            "description": inner.get("description", ""),
            "image": meta["cover"],
            "url": meta["url"],
        }
        if inner.get("subjectType") == 1:
            schema_obj["thumbnailUrl"] = [meta["cover"]]
            schema_obj["uploadDate"] = inner.get("releaseDate", "2026-01-01")
            schema_obj["duration"] = "PT120M"
        
        meta["schema"] = f'<script type="application/ld+json">{json.dumps(schema_obj)}</script>'
    return meta

def serve_html(filename: str, meta_replacements=None, host: str = ""):
    path = os.path.join(base_dir, filename)
    if not os.path.exists(path): return HTMLResponse("Not Found", status_code=404)
    with open(path, "r", encoding="utf-8") as f: html = f.read()
    
    brand = get_brand_config(host)
    if brand["name"] != "Streamfit":
        html = html.replace('Stream<span>fit</span>', brand["name_html"])
        html = html.replace('Streamfit', brand["name"])
        html = html.replace('streamfit.ehealthfinder.com', brand["domain"])
        html = html.replace('/favicon.svg', brand["favicon"])
        html = html.replace('<div class="play-logo"><i class="fa-solid fa-play"></i></div>', f'<div class="play-logo">{brand["logo_icon"]}</div>')
        html = html.replace('<div class="play-logo" style="width:28px;height:28px;font-size:11px;"><i class="fa-solid fa-play"></i></div>', f'<div class="play-logo" style="width:28px;height:28px;font-size:11px;">{brand["logo_icon"]}</div>')
        html = html.replace('By Streamfit', brand["by_brand"])

    if meta_replacements:
        for k, v in meta_replacements.items(): html = html.replace(k, v)
    return HTMLResponse(content=html)

@app.get("/", response_class=HTMLResponse)
async def index(request: Request): return serve_html("public/index.html", host=request.headers.get("host", ""))

@app.get("/admin", response_class=HTMLResponse)
async def admin_page(request: Request): return serve_html("public/admin.html", host=request.headers.get("host", ""))

@app.get("/movies", response_class=HTMLResponse)
async def movies(request: Request):
    host = request.headers.get("host", "")
    brand = get_brand_config(host)
    return serve_html("public/movies.html", {"<title>Explore Movies - Streamfit</title>": f"<title>Explore Movies - {brand['name']}</title>"}, host=host)

@app.get("/tv", response_class=HTMLResponse)
async def tv(request: Request):
    host = request.headers.get("host", "")
    brand = get_brand_config(host)
    return serve_html("public/tv.html", {"<title>Explore TV Series - Streamfit</title>": f"<title>Explore TV Series - {brand['name']}</title>"}, host=host)

@app.get("/live-tv")
@app.get("/live-tv.html")
async def livetv(request: Request):
    return RedirectResponse(url="/download", status_code=302)

@app.get("/download", response_class=HTMLResponse)
@app.get("/download.html", response_class=HTMLResponse)
@app.get("/app", response_class=HTMLResponse)
async def download_page(request: Request):
    host = request.headers.get("host", "")
    return serve_html("public/download.html", host=host)

@app.get("/sitemap.xml")
async def sitemap_endpoint():
    path = os.path.join(base_dir, "public", "sitemap.xml")
    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as f:
            content = f.read()
        return Response(content=content, media_type="application/xml", headers={"Cache-Control": "public, max-age=3600"})
    raise HTTPException(status_code=404, detail="Sitemap not found")

@app.get("/robots.txt")
async def robots_endpoint():
    path = os.path.join(base_dir, "public", "robots.txt")
    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as f:
            content = f.read()
        return Response(content=content, media_type="text/plain", headers={"Cache-Control": "public, max-age=3600"})
    raise HTTPException(status_code=404, detail="Robots not found")

@app.get("/google{verification_code}.html")
async def google_verification_endpoint(verification_code: str):
    file_path = os.path.join(base_dir, "public", f"google{verification_code}.html")
    if os.path.exists(file_path):
        with open(file_path, "r", encoding="utf-8") as f:
            return HTMLResponse(content=f.read())
    return HTMLResponse(content=f"google-site-verification: google{verification_code}.html")

@app.get("/movie/{slug}", response_class=HTMLResponse)
@app.get("/tv/{slug}", response_class=HTMLResponse)
async def details(slug: str, request: Request):
    host = request.headers.get("host", "")
    brand = get_brand_config(host)
    meta = await get_subject_meta(slug, host=host)
    reps = {
        '<title>Details — Streamfit</title>': f'<title>{meta["title"]}</title>',
        'id="detailsTitle">Title': f'id="detailsTitle">{meta["title"]}',
        'id="watchDescription">Description loading...': f'id="watchDescription">{meta["description"]}',
        'src="/default-cover.png"': f'src="{meta["cover"]}"',
        'content="Watch free movies, TV shows, anime and live sports online."': f'content="{meta["description"]}"',
        'content="Streamfit - Free Movies, TV Shows & Anime Streaming"': f'content="{meta["title"]}"',
        'content="https://streamfit.ehealthfinder.com/"': f'content="{meta["url"]}"',
        'content="https://images.unsplash.com/photo-1594909122845-11baa439b7bf?w=1200&q=80"': f'content="{meta["cover"]}"',
        'content="website"': 'content="video.movie"',
        '<!-- SCHEMA_PLACEHOLDER -->': meta.get("schema", "")
    }
    return serve_html("public/details.html", reps, host=host)

@app.get("/watch/movie/{slug}", response_class=HTMLResponse)
@app.get("/watch/tv/{slug}", response_class=HTMLResponse)
async def watch(slug: str, request: Request):
    host = request.headers.get("host", "")
    brand = get_brand_config(host)
    meta = await get_subject_meta(slug, host=host)
    reps = {
        '<title>Watch — Streamfit</title>': f'<title>Watching {meta["title"]}</title>',
        'content="Watch free movies, TV shows, anime and live sports online."': f'content="{meta["description"]}"',
        'content="Streamfit - Free Movies, TV Shows & Anime Streaming"': f'content="{meta["title"]}"',
        'content="https://streamfit.ehealthfinder.com/"': f'content="{meta["url"]}"',
        'content="https://images.unsplash.com/photo-1594909122845-11baa439b7bf?w=1200&q=80"': f'content="{meta["cover"]}"',
        'content="website"': 'content="video.movie"',
        '<!-- SCHEMA_PLACEHOLDER -->': meta.get("schema", "")
    }
    return serve_html("public/watch.html", reps, host=host)

# backward compatibility redirects
@app.get("/details")
async def old_details(id: str = None, path: str = None):
    if path: return RedirectResponse(url=f"/movie/{path}", status_code=301)
    return RedirectResponse(url="/", status_code=301)

@app.get("/watch")
async def old_watch(request: Request, id: str = None, path: str = None, type: str = None, season: int = None, episode: int = None):
    if type in ["sports", "tv"] and not path:
        return serve_html("public/watch.html")
    if path:
        q_params = dict(request.query_params)
        is_tv = (season and season > 0) or (type == "tv")
        type_segment = "tv" if is_tv else "movie"
        q_params.pop("path", None)
        q_params.pop("type", None)
        q_str = f"?{urllib.parse.urlencode(q_params)}" if q_params else ""
        return RedirectResponse(url=f"/watch/{type_segment}/{path}{q_str}", status_code=301)
    return RedirectResponse(url="/", status_code=301)

# API
_home_cache = {}
_home_cache_time = {}

@app.get("/api/home")
async def get_home(page: int = 1, tabId: int = 0):
    cache_key = f"{page}_{tabId}"
    now = time.time()
    if cache_key in _home_cache and (now - _home_cache_time.get(cache_key, 0) < 300):
        return _home_cache[cache_key]

    url = f"{API_BASE}/wefeed-h5api-bff/tab-operating?page={page}&tabId={tabId}"
    data = await _make_request(url)
    if "data" in data and "operatingList" in data["data"]:
        # Update hero banner items with latest movies from MovieBox Mobile
        try:
            latest_banners = await get_latest_banners()
            latest_banners = [b for b in latest_banners if not is_adult_content(b)]
            banner_found = False
            for op in data["data"]["operatingList"]:
                if op.get("type") == "BANNER":
                    op["banner"] = {"items": latest_banners}
                    banner_found = True
                    break
            if not banner_found and latest_banners:
                data["data"]["operatingList"].insert(0, {
                    "type": "BANNER",
                    "title": "Banners",
                    "banner": {"items": latest_banners}
                })
        except Exception as e:
            print(f"Error updating home banners: {e}")

        # Filter out adult content from operatingList
        filtered_op_list = []
        for op in data["data"]["operatingList"]:
            if op.get("type") == "BANNER":
                banner_items = op.get("banner", {}).get("items") or []
                clean_banner = [b for b in banner_items if not is_adult_content(b)]
                if clean_banner:
                    op["banner"]["items"] = clean_banner
                    filtered_op_list.append(op)
            else:
                subjects = op.get("subjects") or []
                clean_subjs = [s for s in subjects if not is_adult_content(s)]
                if clean_subjs:
                    op["subjects"] = clean_subjs
                    filtered_op_list.append(op)
        data["data"]["operatingList"] = filtered_op_list
        data["data"]["items"] = filtered_op_list
        _home_cache[cache_key] = data
        _home_cache_time[cache_key] = now
    return data

@app.get("/api/banners")
async def get_banners():
    items = await get_latest_banners()
    return {"code": 0, "data": {"list": items}}

_movies_pool_cache = []
_movies_pool_cache_time = 0

_tv_pool_cache = []
_tv_pool_cache_time = 0

async def get_movies_pool() -> list:
    global _movies_pool_cache, _movies_pool_cache_time
    now = time.time()
    if _movies_pool_cache and (now - _movies_pool_cache_time < 900):
        return _movies_pool_cache

    seen = set()
    pool = []

    # 1. Fresh latest movies from MovieBox Mobile (2026, 2025, daily recs)
    try:
        latest_movies = await get_latest_content(subject_type=1)
        for m in latest_movies:
            sid = str(m.get("subjectId") or "")
            if sid and sid not in seen and not is_adult_content(m):
                seen.add(sid)
                pool.append(m)
    except Exception as e:
        print(f"Error fetching latest movies for pool: {e}")

    # 2. Curated operating list from official Movie tab (tabId=2)
    try:
        url = f"{API_BASE}/wefeed-h5api-bff/tab-operating?page=1&tabId=2"
        d = await _make_request(url)
        op_list = d.get("data", {}).get("operatingList", [])
        for op in op_list:
            for s in op.get("subjects", []):
                sid = str(s.get("subjectId") or "")
                st = s.get("subjectType", 1)
                if st == 1 and sid and sid not in seen and not is_adult_content(s):
                    seen.add(sid)
                    title = s.get("title") or ""
                    sid_str = str(sid)
                    dpath = s.get("detailPath") or ""
                    if not dpath and s.get("detailUrl"):
                        dpath = s["detailUrl"].rstrip("/").split("/")[-1]
                    clean_slug = re.sub(r'[^a-zA-Z0-9]+', '-', title.lower()).strip('-')
                    if not dpath:
                        dpath = f"{clean_slug}-{sid_str}" if sid_str else clean_slug
                    s["detailPath"] = dpath
                    _slug_to_id[dpath] = sid_str
                    _slug_to_id[sid_str] = sid_str
                    if s.get("detailUrl"):
                        raw_slug = s["detailUrl"].rstrip("/").split("/")[-1]
                        _slug_to_id[raw_slug] = sid_str
                    pool.append(s)
    except Exception as e:
        print(f"Error fetching tabId=2 operating movies: {e}")

    _movies_pool_cache = pool
    _movies_pool_cache_time = now
    return pool

async def get_tv_pool() -> list:
    global _tv_pool_cache, _tv_pool_cache_time
    now = time.time()
    if _tv_pool_cache and (now - _tv_pool_cache_time < 900):
        return _tv_pool_cache

    seen = set()
    pool = []

    # 1. Search popular TV queries with type=2 from MovieBox Mobile
    tv_queries = ["2026", "2025", "2024", "drama", "series", "hindi", "season"]
    for q in tv_queries:
        try:
            r = await request_moviebox_mobile(
                "/wefeed-mobile-bff/subject-api/search",
                method="POST",
                data={"keyword": q, "q": q, "page": 1, "pageSize": 20, "type": 2}
            )
            items = r.get("data", {}).get("items") or []
            for it in items:
                sid = str(it.get("subjectId") or "")
                st = it.get("subjectType")
                if st == 2 and sid and sid not in seen and not is_adult_content(it):
                    seen.add(sid)
                    title = it.get("title") or ""
                    sid_str = str(sid)
                    dpath = it.get("detailPath") or ""
                    if not dpath and it.get("detailUrl"):
                        dpath = it["detailUrl"].rstrip("/").split("/")[-1]
                    clean_slug = re.sub(r'[^a-zA-Z0-9]+', '-', title.lower()).strip('-')
                    if not dpath:
                        dpath = f"{clean_slug}-{sid_str}" if sid_str else clean_slug
                    it["detailPath"] = dpath
                    _slug_to_id[dpath] = sid_str
                    _slug_to_id[sid_str] = sid_str
                    if it.get("detailUrl"):
                        raw_slug = it["detailUrl"].rstrip("/").split("/")[-1]
                        _slug_to_id[raw_slug] = sid_str
                    pool.append(it)
        except Exception as e:
            print(f"Error searching TV {q}: {e}")

    # 2. Curated operating list from Home tab (tabId=0)
    try:
        url = f"{API_BASE}/wefeed-h5api-bff/tab-operating?page=1&tabId=0"
        d = await _make_request(url)
        op_list = d.get("data", {}).get("operatingList", [])
        for op in op_list:
            for s in op.get("subjects", []):
                sid = str(s.get("subjectId") or "")
                st = s.get("subjectType", 2)
                if st == 2 and sid and sid not in seen and not is_adult_content(s):
                    seen.add(sid)
                    title = s.get("title") or ""
                    sid_str = str(sid)
                    dpath = s.get("detailPath") or ""
                    if not dpath and s.get("detailUrl"):
                        dpath = s["detailUrl"].rstrip("/").split("/")[-1]
                    clean_slug = re.sub(r'[^a-zA-Z0-9]+', '-', title.lower()).strip('-')
                    if not dpath:
                        dpath = f"{clean_slug}-{sid_str}" if sid_str else clean_slug
                    s["detailPath"] = dpath
                    _slug_to_id[dpath] = sid_str
                    _slug_to_id[sid_str] = sid_str
                    if s.get("detailUrl"):
                        raw_slug = s["detailUrl"].rstrip("/").split("/")[-1]
                        _slug_to_id[raw_slug] = sid_str
                    pool.append(s)
    except Exception as e:
        print(f"Error fetching tabId=0 operating TV: {e}")

    _tv_pool_cache = pool
    _tv_pool_cache_time = now
    return pool

_filter_cache = {}
_filter_cache_time = {}

@app.post("/api/filter")
async def api_filter(request: Request):
    payload = await request.json()
    raw_type = payload.get("subjectType")
    if raw_type is None:
        tab_val = payload.get("tabId")
        raw_type = 2 if tab_val in (2, "2", "tv") else 1
    subject_type = int(raw_type)
    if subject_type not in (1, 2):
        subject_type = 1

    page = int(payload.get("page", 1))
    per_page = int(payload.get("perPage", 24))

    filter_data = payload.get("filter") or {}
    genre = payload.get("genre") or filter_data.get("genre") or "ALL"
    country = payload.get("country") or filter_data.get("country") or "ALL"
    year = payload.get("year") or filter_data.get("year") or "ALL"
    sort = payload.get("sort") or filter_data.get("sort") or "RECOMMEND"

    if str(genre).lower() in ("*", "all"): genre = "ALL"
    if str(country).lower() in ("*", "all"): country = "ALL"
    if str(year).lower() in ("*", "all"): year = "ALL"

    cache_key = f"{subject_type}_{genre}_{country}_{year}_{sort}_{page}_{per_page}"
    now = time.time()
    if cache_key in _filter_cache and (now - _filter_cache_time.get(cache_key, 0) < 600):
        return _filter_cache[cache_key]

    pool = await (get_movies_pool() if subject_type == 1 else get_tv_pool())
    filtered = list(pool)

    # 1. Filter by genre
    if genre != "ALL":
        g_lower = str(genre).lower()
        filtered = [
            m for m in filtered
            if g_lower in (
                ", ".join(m.get("genre", [])) if isinstance(m.get("genre"), list) else str(m.get("genre") or "")
            ).lower()
        ]

    # 2. Filter by country
    if country != "ALL":
        c_lower = str(country).lower()
        filtered = [
            m for m in filtered
            if c_lower in str(m.get("countryName") or m.get("country") or "").lower()
        ]

    # 3. Filter by year
    if year != "ALL":
        y_str = str(year)
        filtered = [
            m for m in filtered
            if y_str in str(m.get("releaseDate") or m.get("year") or "")
        ]

    # 4. If filter results are few and a specific filter was requested, supplement from Mobile search
    if len(filtered) < (page * per_page) and (genre != "ALL" or year != "ALL" or country != "ALL"):
        query = genre if genre != "ALL" else (year if year != "ALL" else country)
        if str(query).lower() in ("anime", "hentai", "ecchi"):
            query = "Animation"
        try:
            mob_res = await request_moviebox_mobile(
                "/wefeed-mobile-bff/subject-api/search",
                method="POST",
                data={"keyword": query, "q": query, "page": page, "pageSize": per_page, "type": subject_type}
            )
            raw_items = mob_res.get("data", {}).get("items") or []
            existing_ids = {str(m.get("subjectId")) for m in filtered}
            for it in raw_items:
                sid = str(it.get("subjectId") or "")
                if sid and sid not in existing_ids and it.get("subjectType", subject_type) == subject_type and not is_adult_content(it):
                    existing_ids.add(sid)
                    title = it.get("title") or ""
                    sid_str = str(sid)
                    dpath = it.get("detailPath") or ""
                    clean_slug = re.sub(r'[^a-zA-Z0-9]+', '-', title.lower()).strip('-')
                    if not dpath:
                        dpath = f"{clean_slug}-{sid_str}" if sid_str else clean_slug
                    it["detailPath"] = dpath
                    _slug_to_id[dpath] = sid_str
                    _slug_to_id[sid_str] = sid_str
                    filtered.append(it)
        except Exception as e:
            print(f"Error querying mobile search for filter: {e}")

    # Remove any adult content from filtered results
    filtered = [m for m in filtered if not is_adult_content(m)]

    # 5. Sort
    sort_upper = str(sort).upper()
    if sort_upper in ("RATING", "TOP RATED"):
        def parse_rating(item):
            try:
                return float(item.get("imdbRatingValue") or 0)
            except:
                return 0.0
        filtered.sort(key=parse_rating, reverse=True)
    elif sort_upper in ("NEWEST", "LATEST", "HOTTEST"):
        def parse_date(item):
            return str(item.get("releaseDate") or item.get("year") or "")
        filtered.sort(key=parse_date, reverse=True)

    start = (page - 1) * per_page
    end = start + per_page
    items = filtered[start:end]
    has_more = len(filtered) > end

    res = {
        "code": 0,
        "message": "ok",
        "data": {
            "items": items,
            "pager": {
                "hasMore": has_more,
                "page": page,
                "perPage": per_page,
                "totalCount": len(filtered)
            }
        }
    }
    _filter_cache[cache_key] = res
    _filter_cache_time[cache_key] = now
    return res

@app.get("/api/search")
@app.post("/api/search")
async def api_search(request: Request):
    if request.method == "POST":
        try:
            payload = await request.json()
        except Exception:
            payload = {}
        keyword = payload.get("keyword", "")
        page = payload.get("page", 1)
        perPage = payload.get("perPage", 24)
        subjectType = payload.get("subjectType", 0)
    else:
        keyword = request.query_params.get("keyword", "") or request.query_params.get("q", "")
        try: page = int(request.query_params.get("page", 1))
        except Exception: page = 1
        try: perPage = int(request.query_params.get("perPage", 24))
        except Exception: perPage = 24
        try: subjectType = int(request.query_params.get("subjectType", 0))
        except Exception: subjectType = 0

    # 1. Primary: Official MovieBox Mobile Gateway (contains all latest releases)
    try:
        mob_res = await request_moviebox_mobile(
            "/wefeed-mobile-bff/subject-api/search",
            method="POST",
            data={"keyword": keyword, "q": keyword, "page": page, "pageSize": perPage, "type": subjectType or 0}
        )
        raw_items = mob_res.get("data", {}).get("items") or []
        if raw_items:
            cleaned_items = []
            for it in raw_items:
                sid = str(it.get("subjectId") or "")
                title = it.get("title") or ""
                detail_path = it.get("detailPath") or ""
                if not detail_path and it.get("detailUrl"):
                    detail_path = it["detailUrl"].rstrip("/").split("/")[-1]
                clean_slug = re.sub(r'[^a-zA-Z0-9]+', '-', title.lower()).strip('-')
                if not detail_path:
                    detail_path = f"{clean_slug}-{sid}" if sid else clean_slug
                if it.get("detailUrl"):
                    raw_slug = it["detailUrl"].rstrip("/").split("/")[-1]
                    _slug_to_id[raw_slug] = sid
                    
                _slug_to_id[detail_path] = sid
                _slug_to_id[sid] = sid
                
                cover = it.get("cover") or {}
                if isinstance(cover, str):
                    cover = {"url": cover}
                    
                genres = it.get("genre") or []
                if isinstance(genres, str):
                    genres = [g.strip() for g in genres.split(",") if g.strip()]
                    
                if is_adult_content(it):
                    continue

                cleaned_items.append({
                    "subjectId": sid,
                    "title": title,
                    "detailPath": detail_path,
                    "subjectType": it.get("subjectType", 1),
                    "cover": cover,
                    "imdbRatingValue": str(it.get("imdbRatingValue") or "7.5"),
                    "releaseDate": it.get("releaseDate") or "",
                    "genre": genres,
                    "duration": it.get("duration") or "",
                    "hasResource": bool(it.get("hasResource", True)),
                    "isCam": bool(it.get("isCam", False))
                })

            for ci in cleaned_items:
                c_url = ci.get("cover", {}).get("url", "") if isinstance(ci.get("cover"), dict) else str(ci.get("cover") or "")
                asyncio.create_task(db_save_subject_meta(
                    subject_id=ci.get("subjectId", ""),
                    title=ci.get("title", ""),
                    subject_type=ci.get("subjectType", 1),
                    cover=c_url,
                    detail_path=ci.get("detailPath", "")
                ))

            return {"code": 0, "data": {"items": cleaned_items, "pager": {"hasMore": len(cleaned_items) >= perPage}}}
    except Exception as e:
        print(f"Mobile search error: {e}")

    # Fallback to H5 search
    url = f"{API_BASE}/wefeed-h5api-bff/subject/search"
    data = await _make_request(url, method="POST", payload={"keyword": keyword, "page": page, "perPage": perPage})
    if "data" in data and "list" in data["data"]:
        data["data"]["items"] = [it for it in data["data"]["list"] if not is_adult_content(it)]
    return data

@app.post("/api/heartbeat")
async def api_heartbeat():
    return {"code": 0, "message": "success"}

@app.get("/api/notifications/latest")
async def api_notifications():
    return {"code": 0, "data": []}

@app.get("/api/sports/live")
async def api_sports():
    return {"code": 0, "data": []}

@app.get("/api/search/suggest")
async def search_suggest(q: str = ""):
    if not q:
        return {"code": 0, "data": {"items": []}}

    # Primary: Official MovieBox Mobile Gateway
    try:
        mob_res = await request_moviebox_mobile(
            "/wefeed-mobile-bff/subject-api/search",
            method="POST",
            data={"keyword": q, "q": q, "page": 1, "pageSize": 10, "type": 0}
        )
        raw_items = mob_res.get("data", {}).get("items") or []
        if raw_items:
            items = []
            for it in raw_items:
                if is_adult_content(it):
                    continue
                sid = str(it.get("subjectId") or "")
                title = it.get("title") or ""
                detail_path = it.get("detailPath") or ""
                if not detail_path and it.get("detailUrl"):
                    detail_path = it["detailUrl"].rstrip("/").split("/")[-1]
                clean_slug = re.sub(r'[^a-zA-Z0-9]+', '-', title.lower()).strip('-')
                if not detail_path:
                    detail_path = f"{clean_slug}-{sid}" if sid else clean_slug
                if it.get("detailUrl"):
                    raw_slug = it["detailUrl"].rstrip("/").split("/")[-1]
                    _slug_to_id[raw_slug] = sid
                _slug_to_id[detail_path] = sid
                _slug_to_id[sid] = sid

                cover = it.get("cover") or {}
                if isinstance(cover, str):
                    cover = {"url": cover}

                items.append({
                    "title": title,
                    "subjectId": sid,
                    "subjectType": it.get("subjectType", 1),
                    "cover": cover,
                    "rating": str(it.get("imdbRatingValue") or "7.5"),
                    "detailPath": detail_path,
                    "releaseDate": it.get("releaseDate") or "",
                    "hasResource": bool(it.get("hasResource", True)),
                    "isKeyword": False
                })
            return {"code": 0, "data": {"items": items}}
    except Exception as e:
        print(f"Mobile search-suggest error: {e}")

    # Fallback to H5 search-suggest
    url = f"{API_BASE}/wefeed-h5api-bff/subject/search-suggest"
    data = await _make_request(url, method="POST", payload={"keyword": q, "perPage": 10})
    if "data" in data and "items" in data["data"]:
        raw_items = data["data"]["items"]
        items = []
        for d in raw_items:
            sub = d.get("subject") or {}
            if sub:
                d["title"] = sub.get("title", "")
                d["subjectType"] = sub.get("subjectType", 1)
                d["cover"] = sub.get("cover", {})
                d["rating"] = sub.get("imdbRatingValue", "")
                d["detailPath"] = sub.get("detailPath", "")
                d["releaseDate"] = sub.get("releaseDate", "")
                d["isKeyword"] = False
            else:
                d["title"] = d.get("word", "")
                d["subjectType"] = 0
                d["cover"] = {}
                d["rating"] = ""
                d["detailPath"] = ""
                d["releaseDate"] = ""
                d["isKeyword"] = True
            if d["title"]:
                items.append(d)
        data["data"]["items"] = items
    return data

@app.get("/api/detail")
async def api_detail(detailPath: str = "", subjectId: str = ""):
    # 1. Try H5 detail first if detailPath given
    if detailPath:
        try:
            url = f"{API_BASE}/wefeed-h5api-bff/detail?detailPath={detailPath}"
            data = await _make_request(url)
            if "data" in data and "subject" in data["data"] and data["data"]["subject"]:
                subj = data["data"]["subject"]
                # If subjectId was explicitly requested and differs from H5 result, it's a dub switch; resolve by subjectId below
                if subjectId and str(subj.get("subjectId")) != str(subjectId):
                    pass
                else:
                    if subj.get("subjectType") == 1:
                        subj["seasons"] = []
                        subj["seNum"] = 0
                    else:
                        if "resource" in data["data"]:
                            subj["seasons"] = data["data"]["resource"].get("seasons", [])
                            subj["seNum"] = len(subj["seasons"])
                    data["data"] = subj
                    sid = str(subj.get("subjectId") or "")
                    if sid:
                        _slug_to_id[detailPath] = sid
                        _id_to_slug[sid] = detailPath
                    return data
        except Exception:
            pass

    # 2. Fallback to MovieBox Mobile Gateway
    sid = await resolve_subject_id(detailPath, subjectId)
    if sid:
        try:
            mob_res = await request_moviebox_mobile("/wefeed-mobile-bff/subject-api/get", params={"subjectId": sid})
            mob_data = mob_res.get("data") or {}
            subj = mob_data.get("subject") or mob_data
            if subj and isinstance(subj, dict):
                subj["subjectId"] = sid
                d_url = subj.get("detailUrl")
                if d_url:
                    official_slug = d_url.rstrip("/").split("/")[-1]
                    _slug_to_id[official_slug] = sid
                    _id_to_slug[sid] = official_slug
                    if detailPath:
                        _slug_to_id[detailPath] = sid
                    # Try H5 with official slug
                    try:
                        h5_res = await _make_request(f"{API_BASE}/wefeed-h5api-bff/detail?detailPath={official_slug}")
                        h5_subj = h5_res.get("data", {}).get("subject")
                        if h5_subj:
                            if h5_subj.get("subjectType") == 1:
                                h5_subj["seasons"] = []
                                h5_subj["seNum"] = 0
                            else:
                                if "resource" in h5_res.get("data", {}):
                                    h5_subj["seasons"] = h5_res["data"]["resource"].get("seasons", [])
                                    h5_subj["seNum"] = len(h5_subj["seasons"])
                            h5_subj["subjectId"] = sid
                            h5_subj["detailPath"] = official_slug
                            return {"code": 0, "data": h5_subj}
                    except Exception:
                        pass
                if not subj.get("detailPath"):
                    subj["detailPath"] = _id_to_slug.get(sid, detailPath)
                if subj.get("subjectType") == 1:
                    subj["seasons"] = []
                    subj["seNum"] = 0
                elif subj.get("subjectType") == 2:
                    if not subj.get("seasons"):
                        try:
                            s_mob = await request_moviebox_mobile(
                                "/wefeed-mobile-bff/subject-api/season-info",
                                params={"subjectId": sid}
                            )
                            s_data = s_mob.get("data", {}).get("seasons") or []
                            if s_data:
                                for s in s_data:
                                    if not s.get("allEp") and s.get("maxEp"):
                                        s["allEp"] = ",".join(str(i) for i in range(1, int(s["maxEp"]) + 1))
                                subj["seasons"] = s_data
                                subj["seNum"] = len(s_data)
                        except Exception:
                            pass
                    if not subj.get("seasons"):
                        se_num = int(subj.get("seNum") or 1)
                        detectors = subj.get("resourceDetectors") or []
                        max_ep = 1
                        for d in detectors:
                            ep_cnt = int(d.get("totalEpisode") or 0)
                            if ep_cnt > max_ep:
                                max_ep = ep_cnt
                        subj["seasons"] = [{"se": s, "maxEp": max_ep, "allEp": ",".join(str(i) for i in range(1, max_ep + 1))} for s in range(1, se_num + 1)]
                        subj["seNum"] = se_num
                else:
                    if not subj.get("seasons"):
                        subj["seasons"] = []
                    subj["seNum"] = len(subj["seasons"])
                return {"code": 0, "data": subj}
        except Exception as e:
            print(f"Mobile detail error: {e}")

    return {"code": 404, "message": "subject not found", "data": {}}

@app.get("/api/season-info")
async def api_season(detailPath: str = "", subjectId: str = ""):
    if detailPath:
        try:
            url = f"{API_BASE}/wefeed-h5api-bff/detail?detailPath={detailPath}"
            data = await _make_request(url)
            subj = data.get("data", {}).get("subject") or {}
            if subjectId and subj.get("subjectId") and str(subj.get("subjectId")) != str(subjectId):
                pass
            else:
                if subj.get("subjectType") == 1:
                    return {"code": 0, "data": {"seasons": []}}
                seasons = []
                if "data" in data and "resource" in data["data"]:
                    seasons = data["data"]["resource"].get("seasons", [])
                    if seasons:
                        return {"code": 0, "data": {"seasons": seasons}}
        except Exception:
            pass

    sid = await resolve_subject_id(detailPath, subjectId)
    if sid:
        try:
            mob_res = await request_moviebox_mobile("/wefeed-mobile-bff/subject-api/season-info", params={"subjectId": sid})
            seasons = mob_res.get("data", {}).get("seasons") or []
            if seasons:
                for s in seasons:
                    if not s.get("allEp") and s.get("maxEp"):
                        s["allEp"] = ",".join(str(i) for i in range(1, int(s["maxEp"]) + 1))
                return {"code": 0, "data": {"seasons": seasons}}
        except Exception:
            pass

        try:
            mob_res = await request_moviebox_mobile("/wefeed-mobile-bff/subject-api/get", params={"subjectId": sid})
            mob_data = mob_res.get("data") or {}
            subj = mob_data.get("subject") or mob_data
            if subj and subj.get("subjectType") == 1:
                return {"code": 0, "data": {"seasons": []}}
            if subj and subj.get("seasons"):
                return {"code": 0, "data": {"seasons": subj["seasons"]}}
            if subj and subj.get("subjectType") == 2:
                se_num = int(subj.get("seNum") or 1)
                detectors = subj.get("resourceDetectors") or []
                max_ep = 1
                for d in detectors:
                    ep_cnt = int(d.get("totalEpisode") or 0)
                    if ep_cnt > max_ep:
                        max_ep = ep_cnt
                seasons = []
                for s in range(1, se_num + 1):
                    all_eps = ",".join(str(i) for i in range(1, max_ep + 1))
                    seasons.append({
                        "se": s,
                        "maxEp": max_ep,
                        "allEp": all_eps
                    })
                return {"code": 0, "data": {"seasons": seasons}}
        except Exception:
            pass

    return {"code": 0, "data": {"seasons": [], "isUpcoming": True}}

_resource_cache = {}
_resource_cache_time = {}

DEPRECATED_VIDEO_HASHES = [
    "80da52722ee7de7a4303290af6783424",
    "/other/2026/10/10/",
    "service_update",
    "discontinued",
    "480p-tran-audio-channel"
]

def is_deprecated_or_dummy_url(url: str) -> bool:
    if not url:
        return True
    u_lower = str(url).lower()
    return any(bad in u_lower for bad in DEPRECATED_VIDEO_HASHES)

def _extract_playable_detectors(detectors: list) -> list:
    mob_items = []
    for det in detectors:
        res_list = det.get("resolutionList", [])
        if res_list:
            for r in res_list:
                r_link = r.get("resourceLink") or r.get("url")
                if r_link and not any(bad in r_link.lower() for bad in ["fzmovies", "southfreak"]) and not is_deprecated_or_dummy_url(r_link):
                    fetch_link = f"https://nxly.online/fetch?source_url={urllib.parse.quote(r_link)}" if r_link.startswith("http") else r_link
                    mob_items.append({
                        "resourceId": str(r.get("resourceId") or r.get("id") or "0"),
                        "resolution": int(r.get("resolution", 720)),
                        "size": int(r.get("size", 0)),
                        "url": fetch_link,
                        "resourceLink": fetch_link
                    })
        elif det.get("resourceLink") and not any(bad in det.get("resourceLink", "").lower() for bad in ["fzmovies", "southfreak"]):
            r_link = det["resourceLink"]
            if not is_deprecated_or_dummy_url(r_link):
                fetch_link = f"https://nxly.online/fetch?source_url={urllib.parse.quote(r_link)}" if r_link.startswith("http") else r_link
                mob_items.append({
                    "resourceId": str(det.get("resourceId") or "0"),
                    "resolution": 720,
                    "size": int(det.get("totalSize") or det.get("firstSize") or 0),
                    "url": fetch_link,
                    "resourceLink": fetch_link
                })
    return mob_items

_resource_inflight = {}
_captions_cache = {}
_captions_cache_time = {}
_tts_cache = {}

async def _do_fetch_resource(subjectId: str, se: int, ep: int, detailPath: str, cache_key: str) -> dict:
    now = time.time()
    items = []

    # 1. Primary: Official H5 Download API (Real full 1080p, 720p, 480p, 360p CDN files)
    try:
        h5_data = await _fetch_download_resources(subjectId, se, ep, detailPath)
        if isinstance(h5_data, dict):
            downloads = h5_data.get("downloads") or []
            for d in downloads:
                url_val = d.get("url")
                if url_val and not is_deprecated_or_dummy_url(url_val):
                    res_id = str(d.get("id") or "0")
                    res_val = int(d.get("resolution") or 720)
                    sz_val = int(d.get("size") or 0)
                    fetch_link = f"https://nxly.online/fetch?source_url={urllib.parse.quote(url_val)}"
                    items.append({
                        "resourceId": res_id,
                        "resolution": res_val,
                        "size": sz_val,
                        "url": url_val,
                        "resourceLink": fetch_link,
                        "proxyUrl": fetch_link
                    })
    except Exception as e:
        print(f"H5 download resource fetch error: {e}")

    if items:
        res = {"code": 0, "data": {"list": items, "isUpcoming": False}}
        _resource_cache[cache_key] = res
        _resource_cache_time[cache_key] = now
        return res

    # 2. Fallback: Query Mobile Subject detectors and Candidate dubs (strictly rejecting deprecation notice videos)
    mob_subj = {}
    try:
        mob_res = await request_moviebox_mobile(
            "/wefeed-mobile-bff/subject-api/get",
            params={"subjectId": subjectId, "se": se, "ep": ep}
        )
        if isinstance(mob_res, dict):
            mob_subj = mob_res.get("data", {}).get("subject") or mob_res.get("data") or {}
            detectors = mob_subj.get("resourceDetectors") or []
            items = _extract_playable_detectors(detectors)
    except Exception as e:
        print(f"Fallback mobile get error: {e}")

    # Candidate dubs fallback
    if not items and mob_subj:
        dubs = mob_subj.get("dubs") or []
        orig = next((d for d in dubs if d.get("original") and str(d.get("subjectId")) != str(subjectId)), None)
        other_candidates = [d for d in dubs if str(d.get("subjectId")) != str(subjectId) and d != orig]
        check_candidates = ([orig] if orig else []) + other_candidates[:2]

        for cand in check_candidates:
            cand_id = str(cand.get("subjectId") or "")
            cand_path = cand.get("detailPath") or ""
            if not cand_id:
                continue
            try:
                cand_h5 = await _fetch_download_resources(cand_id, se, ep, cand_path)
                for d in (cand_h5.get("downloads") or []):
                    u = d.get("url")
                    if u and not is_deprecated_or_dummy_url(u):
                        fetch_link = f"https://nxly.online/fetch?source_url={urllib.parse.quote(u)}"
                        items.append({
                            "resourceId": str(d.get("id") or "0"),
                            "resolution": int(d.get("resolution") or 720),
                            "size": int(d.get("size") or 0),
                            "url": u,
                            "resourceLink": fetch_link,
                            "proxyUrl": fetch_link
                        })
                if items:
                    break
            except Exception:
                pass

    # Search fallback if title has dub markers like [Hindi], [Dual Audio], etc.
    if not items and mob_subj:
        raw_title = mob_subj.get("title", "")
        import re
        cleaned_title = re.sub(r'\[.*?\]|\(.*?\)', '', raw_title).strip()
        if cleaned_title and len(cleaned_title) > 2 and cleaned_title.lower() != raw_title.lower():
            try:
                search_res = await request_moviebox_mobile(
                    "/wefeed-mobile-bff/subject-api/search",
                    params={"keyword": cleaned_title, "page": 1, "size": 5}
                )
                if isinstance(search_res, dict):
                    cand_list = search_res.get("data", {}).get("list") or search_res.get("data", {}).get("items") or []
                    for c_item in cand_list:
                        c_id = str(c_item.get("subjectId") or c_item.get("id") or "")
                        c_path = c_item.get("detailPath") or ""
                        if c_id and c_id != str(subjectId):
                            c_h5 = await _fetch_download_resources(c_id, se, ep, c_path)
                            for d in (c_h5.get("downloads") or []):
                                u = d.get("url")
                                if u and not is_deprecated_or_dummy_url(u):
                                    fetch_link = f"https://nxly.online/fetch?source_url={urllib.parse.quote(u)}"
                                    items.append({
                                        "resourceId": str(d.get("id") or "0"),
                                        "resolution": int(d.get("resolution") or 720),
                                        "size": int(d.get("size") or 0),
                                        "url": u,
                                        "resourceLink": fetch_link,
                                        "proxyUrl": fetch_link
                                    })
                            if items:
                                break
            except Exception as se:
                print(f"Fallback cleaned title search error: {se}")

    res = {"code": 0, "data": {"list": items, "isUpcoming": len(items) == 0}}
    _resource_cache[cache_key] = res
    # Cache valid resources for 2 hours; empty results negative-cached for 60s
    _resource_cache_time[cache_key] = now if items else (now - 7200 + 60)
    return res

@app.get("/api/resource")
async def api_resource(se: int = 1, ep: int = 1, detailPath: str = "", subjectId: str = ""):
    if not subjectId and detailPath:
        subjectId = await resolve_subject_id(detailPath, subjectId)
            
    if not subjectId:
        return {"code": 0, "data": {"list": []}}

    if not detailPath and subjectId:
        detailPath = await resolve_detail_path(subjectId, detailPath)

    cache_key = f"{subjectId}_{se}_{ep}_{detailPath}"
    now = time.time()

    # 1. In-memory fast cache (Instant: < 0.001s)
    if cache_key in _resource_cache and (now - _resource_cache_time.get(cache_key, 0) < 7200):
        cached_val = _resource_cache[cache_key]
        cached_list = cached_val.get("data", {}).get("list") or []
        if not any(is_deprecated_or_dummy_url(it.get("url", "")) or is_deprecated_or_dummy_url(it.get("resourceLink", "")) for it in cached_list):
            return cached_val

    # 2. Database persistent cache (Super fast: < 0.005s)
    db_data = await db_get_stream_resource(subjectId, se, ep)
    if db_data and db_data.get("resources"):
        items = db_data["resources"]
        if not any(is_deprecated_or_dummy_url(it.get("url", "")) or is_deprecated_or_dummy_url(it.get("resourceLink", "")) for it in items):
            res = {"code": 0, "data": {"list": items, "isUpcoming": False}}
            _resource_cache[cache_key] = res
            _resource_cache_time[cache_key] = now
            return res

    # 3. Single-flight request coalescing for upstream fetch
    if cache_key in _resource_inflight:
        try:
            return await _resource_inflight[cache_key]
        except Exception:
            pass

    task = asyncio.create_task(_do_fetch_resource(subjectId, se, ep, detailPath, cache_key))
    _resource_inflight[cache_key] = task
    try:
        res = await task
        items = res.get("data", {}).get("list") or []
        if items:
            asyncio.create_task(db_save_stream_resource(subjectId, se, ep, detailPath, items, ttl_hours=24.0))
        return res
    finally:
        _resource_inflight.pop(cache_key, None)
 
_translate_cache = {}

@app.get("/api/translate")
async def api_translate(text: str = "", target_lang: str = "en", source_lang: str = "auto"):
    if not text:
        return {"code": 0, "translated": ""}
    cache_key = f"{source_lang}_{target_lang}_{text}"
    if cache_key in _translate_cache:
        return {"code": 0, "translated": _translate_cache[cache_key]}
    
    url = f"https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl={source_lang}&tl={target_lang}&q={urllib.parse.quote(text)}"
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        resp_bytes = await asyncio.to_thread(lambda: urllib.request.urlopen(req, timeout=5).read())
        res = json.loads(resp_bytes.decode("utf-8"))
        translated_text = res[0][0] if isinstance(res, list) and res and isinstance(res[0], list) and res[0] else (res[0] if isinstance(res, list) and res else str(res))
        _translate_cache[cache_key] = translated_text
        return {"code": 0, "translated": translated_text}
    except Exception as e:
        return {"code": 0, "translated": text}

@app.post("/api/translate-batch")
async def api_translate_batch(payload: dict):
    texts = payload.get("texts") or []
    target_lang = payload.get("target_lang") or "en"
    source_lang = payload.get("source_lang") or "auto"
    results = []
    for text in texts:
        if not text:
            results.append("")
            continue
        cache_key = f"{source_lang}_{target_lang}_{text}"
        if cache_key in _translate_cache:
            results.append(_translate_cache[cache_key])
            continue
        url = f"https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl={source_lang}&tl={target_lang}&q={urllib.parse.quote(text)}"
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            resp_bytes = await asyncio.to_thread(lambda: urllib.request.urlopen(req, timeout=5).read())
            res = json.loads(resp_bytes.decode("utf-8"))
            translated_text = res[0][0] if isinstance(res, list) and res and isinstance(res[0], list) and res[0] else (res[0] if isinstance(res, list) and res else str(res))
            _translate_cache[cache_key] = translated_text
            results.append(translated_text)
        except Exception:
            results.append(text)
    return {"code": 0, "translations": results}

@app.get("/api/tts")
async def api_tts(text: str = "", lang: str = "bn"):
    if not text:
        return Response(status_code=400, content="Missing text")
    cache_key = f"{lang}_{text}"
    if cache_key in _tts_cache:
        return Response(content=_tts_cache[cache_key], media_type="audio/mpeg")
    
    encoded = urllib.parse.quote(text)
    url = f"https://translate.google.com/translate_tts?ie=UTF-8&tl={lang}&client=tw-ob&q={encoded}"
    try:
        async with httpx.AsyncClient(timeout=6.0) as client:
            resp = await client.get(url, headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"})
            if resp.status_code == 200 and len(resp.content) > 100:
                _tts_cache[cache_key] = resp.content
                return Response(content=resp.content, media_type="audio/mpeg")
    except Exception as e:
        print(f"TTS fetch error: {e}")
    return Response(status_code=502, content="TTS service unavailable")

@app.get("/api/captions")
async def api_captions(se: int = 1, ep: int = 1, detailPath: str = "", subjectId: str = "", resourceId: str = ""):
    if not subjectId and detailPath:
        subjectId = await resolve_subject_id(detailPath, subjectId)
            
    if not subjectId:
        return {"code": 0, "data": {"list": []}}

    cap_cache_key = f"{subjectId}_{se}_{ep}"
    now = time.time()
    if cap_cache_key in _captions_cache and (now - _captions_cache_time.get(cap_cache_key, 0) < 43200):
        return _captions_cache[cap_cache_key]

    # 1. Database persistent captions check (< 0.005s)
    db_data = await db_get_stream_resource(subjectId, se, ep)
    if db_data and db_data.get("captions"):
        db_caps = db_data["captions"]
        res = {"code": 0, "data": {"list": db_caps}}
        _captions_cache[cap_cache_key] = res
        _captions_cache_time[cap_cache_key] = now
        return res

    # Resolve resourceId if not supplied
    if not resourceId:
        for ck, cv in _resource_cache.items():
            if ck.startswith(f"{subjectId}_{se}_{ep}"):
                c_list = cv.get("data", {}).get("list") or []
                if c_list and c_list[0].get("resourceId"):
                    resourceId = str(c_list[0].get("resourceId"))
                    break
        if not resourceId and db_data and db_data.get("resources"):
            c_list = db_data["resources"]
            if c_list and c_list[0].get("resourceId"):
                resourceId = str(c_list[0].get("resourceId"))
        if not resourceId:
            try:
                res_obj = await api_resource(se=se, ep=ep, detailPath=detailPath, subjectId=subjectId)
                c_list = res_obj.get("data", {}).get("list") or []
                if c_list and c_list[0].get("resourceId"):
                    resourceId = str(c_list[0].get("resourceId"))
            except Exception:
                pass

    captions = []
    if resourceId:
        try:
            ext_res = await request_moviebox_mobile(
                "/wefeed-mobile-bff/subject-api/get-ext-captions",
                params={"subjectId": subjectId, "resourceId": resourceId, "episode": ep}
            )
            if isinstance(ext_res, dict):
                captions = ext_res.get("data", {}).get("extCaptions") or ext_res.get("data", {}).get("list") or []
        except Exception:
            pass

    if not captions:
        try:
            data = await _fetch_download_resources(subjectId, se, ep, detailPath)
            captions = data.get("captions") or []
        except Exception:
            pass
    
    formatted = []
    seen_urls = set()
    for c in captions:
        raw_u = c.get("url") or ""
        if raw_u and raw_u not in seen_urls:
            seen_urls.add(raw_u)
            formatted.append({
                "label": c.get("lanName") or c.get("lan", "English"),
                "lang": c.get("lan", "en"),
                "url": f"/api/proxy-subtitle?url={urllib.parse.quote(raw_u)}"
            })

    result = {"code": 0, "data": {"list": formatted}}
    if formatted:
        _captions_cache[cap_cache_key] = result
        _captions_cache_time[cap_cache_key] = now
        # Update captions in database!
        asyncio.create_task(db_update_captions(subjectId, se, ep, formatted))
    return result

CDN_CANDIDATE_HEADERS = [
    {
        "User-Agent": "okhttp/4.12.0",
        "Accept": "*/*"
    },
    {
        "Origin": "https://123movienow.cc",
        "Referer": "https://123movienow.cc/",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept": "*/*"
    },
    {
        "Origin": "https://123movienow.cc",
        "Referer": "https://123movienow.cc/",
        "User-Agent": "okhttp/4.12.0",
        "Accept": "*/*"
    },
    {
        "Origin": "https://moviebox.ph",
        "Referer": "https://moviebox.ph/",
        "User-Agent": DEFAULT_HEADERS["User-Agent"],
        "Accept": "*/*"
    },
    {
        "Origin": "https://netfilm.world",
        "Referer": "https://netfilm.world/",
        "User-Agent": DEFAULT_HEADERS["User-Agent"],
        "Accept": "*/*"
    },
    {
        "User-Agent": DEFAULT_HEADERS["User-Agent"],
        "Accept": "*/*"
    }
]

_fetch_client = None

def get_fetch_client():
    global _fetch_client
    if _fetch_client is None or _fetch_client.is_closed:
        _fetch_client = httpx.AsyncClient(
            trust_env=False,
            timeout=httpx.Timeout(30.0, connect=10.0),
            follow_redirects=True,
            limits=httpx.Limits(max_keepalive_connections=100, max_connections=300, keepalive_expiry=30.0)
        )
    return _fetch_client

@app.get("/fetch")
async def handle_fetch(request: Request, source_url: str):
    if not source_url:
        raise HTTPException(status_code=400, detail="Missing source_url")
        
    qp = dict(request.query_params)
    qp.pop("source_url", None)
    if qp:
        source_url += "&" + urllib.parse.urlencode(qp)
        
    range_header = request.headers.get("Range")
    client = get_fetch_client()
    resp = None
    last_error_status = 502

    # Prioritize candidate headers based on target CDN host
    source_lower = source_url.lower()
    if "bcdnxw" in source_lower:
        sorted_templates = [CDN_CANDIDATE_HEADERS[1], CDN_CANDIDATE_HEADERS[2]] + [t for i, t in enumerate(CDN_CANDIDATE_HEADERS) if i not in (1, 2)]
    elif "bcdn." in source_lower:
        sorted_templates = [CDN_CANDIDATE_HEADERS[0]] + [t for i, t in enumerate(CDN_CANDIDATE_HEADERS) if i != 0]
    else:
        sorted_templates = CDN_CANDIDATE_HEADERS

    for template in sorted_templates:
        headers_to_send = dict(template)
        if range_header:
            headers_to_send["Range"] = range_header
            
        try:
            req = client.build_request("GET", source_url, headers=headers_to_send)
            candidate_resp = await client.send(req, stream=True)
            if candidate_resp.status_code in (200, 206):
                resp = candidate_resp
                break
            else:
                last_error_status = candidate_resp.status_code
                await candidate_resp.aclose()
        except Exception:
            continue

    if not resp:
        raise HTTPException(status_code=last_error_status, detail=f"CDN stream unavailable or rate limited ({last_error_status})")

    response_headers = {
        "Content-Type": resp.headers.get("Content-Type", "video/mp4"),
        "Access-Control-Allow-Origin": "*",
        "X-Accel-Buffering": "no",
        "Cache-Control": "no-cache, no-store, must-revalidate"
    }
    if resp.headers.get("Content-Range"):
        response_headers["Content-Range"] = resp.headers.get("Content-Range")
    if resp.headers.get("Content-Length"):
        response_headers["Content-Length"] = resp.headers.get("Content-Length")
    if resp.headers.get("Accept-Ranges"):
        response_headers["Accept-Ranges"] = resp.headers.get("Accept-Ranges")
        
    real_status = resp.status_code
    
    async def stream_generator():
        try:
            async for chunk in resp.aiter_bytes(chunk_size=1024 * 256):
                yield chunk
        finally:
            await resp.aclose()
            
    return StreamingResponse(
        stream_generator(),
        status_code=real_status,
        headers=response_headers
    )

@app.get("/api/proxy-subtitle")
async def proxy_subtitle(url: str):
    if not url: return "Missing URL"
    try:
        resp = await http_client.get(url, headers={"Referer": "https://netfilm.world/"})
        return StreamingResponse(resp.iter_bytes(), media_type="text/vtt", headers={"Access-Control-Allow-Origin": "*"})
    except: return ""

# ==========================================================================
# META / FACEBOOK COMPLIANCE & LEGAL ROUTES
# ==========================================================================
@app.get("/privacy-policy", response_class=HTMLResponse)
@app.get("/privacy", response_class=HTMLResponse)
async def serve_privacy_policy():
    path = os.path.join(base_dir, "public", "privacy-policy.html")
    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as f:
            return HTMLResponse(content=f.read())
    raise HTTPException(status_code=404, detail="Privacy policy not found")

@app.get("/terms-of-service", response_class=HTMLResponse)
@app.get("/terms", response_class=HTMLResponse)
async def serve_terms_of_service():
    path = os.path.join(base_dir, "public", "terms-of-service.html")
    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as f:
            return HTMLResponse(content=f.read())
    raise HTTPException(status_code=404, detail="Terms of service not found")

@app.get("/data-deletion", response_class=HTMLResponse)
@app.get("/data-deletion-instructions", response_class=HTMLResponse)
async def serve_data_deletion():
    path = os.path.join(base_dir, "public", "data-deletion.html")
    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as f:
            return HTMLResponse(content=f.read())
    raise HTTPException(status_code=404, detail="Data deletion page not found")

@app.get("/fb-callback", response_class=HTMLResponse)
@app.get("/fb-callback.html", response_class=HTMLResponse)
async def serve_fb_callback():
    path = os.path.join(base_dir, "public", "fb-callback.html")
    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as f:
            return HTMLResponse(content=f.read())
    raise HTTPException(status_code=404, detail="Callback page not found")

@app.post("/api/facebook/exchange-user-token")
async def exchange_facebook_user_token(request: Request):
    try:
        body = await request.json()
        user_token = body.get("user_token", "").strip()
        if not user_token:
            raise HTTPException(status_code=400, detail="Missing user_token")
        
        import facebook_bot
        import requests
        cfg = facebook_bot.load_fb_config()
        app_id = cfg.get("app_id") or "1814477826460376"
        app_secret = cfg.get("app_secret") or "d20b3446cb2fc0c2b099aa5af16db89e"
        
        # 1. Exchange for long-lived user token
        exchange_url = "https://graph.facebook.com/v19.0/oauth/access_token"
        r = requests.get(exchange_url, params={
            "grant_type": "fb_exchange_token",
            "client_id": app_id,
            "client_secret": app_secret,
            "fb_exchange_token": user_token
        }, timeout=15)
        ex_data = r.json()
        long_lived_user_token = ex_data.get("access_token") or user_token
        
        # 2. Fetch Page Accounts
        acc_url = "https://graph.facebook.com/v19.0/me/accounts"
        r_acc = requests.get(acc_url, params={
            "access_token": long_lived_user_token
        }, timeout=15)
        acc_data = r_acc.json()
        pages = acc_data.get("data", [])
        
        target_page = None
        target_page_id = cfg.get("page_id") or "1367186939808744"
        for p in pages:
            if str(p.get("id")) == str(target_page_id) or p.get("name", "").lower() == "nxly":
                target_page = p
                break
        
        if not target_page and pages:
            target_page = pages[0]
            
        if not target_page:
            return JSONResponse({
                "success": False,
                "detail": "No Facebook Pages found for this user. Ensure NXLY page is linked."
            }, status_code=400)
            
        page_token = target_page.get("access_token")
        page_name = target_page.get("name")
        page_id = target_page.get("id")
        
        cfg["page_token"] = page_token
        cfg["page_id"] = page_id
        cfg["page_name"] = page_name
        cfg["user_token"] = long_lived_user_token
        cfg["app_id"] = app_id
        cfg["app_secret"] = app_secret
        facebook_bot.save_fb_config(cfg)
        
        facebook_bot.bot_log(f"[Auth] 🔑 Successfully connected Page: {page_name} ({page_id}) with permanent token!")
        return {
            "success": True,
            "page_name": page_name,
            "page_id": page_id
        }
    except Exception as e:
        return JSONResponse({"success": False, "detail": str(e)}, status_code=500)

@app.api_route("/api/facebook/data-deletion", methods=["GET", "POST"])
async def facebook_data_deletion_callback(request: Request):
    """
    Official Meta Data Deletion Request Callback.
    Complies with Meta Platform Terms.
    Returns: { 'url': '<status_url>', 'confirmation_code': '<code_string>' }
    """
    signed_request = None
    if request.method == "POST":
        try:
            form = await request.form()
            signed_request = form.get("signed_request")
        except Exception:
            pass
        if not signed_request:
            try:
                body = await request.json()
                signed_request = body.get("signed_request")
            except Exception:
                pass

    user_id = "user"
    confirmation_code = f"NXLY-DEL-{int(time.time())}-{random.randint(1000, 9999)}"

    if signed_request and "." in signed_request:
        try:
            import facebook_bot
            fb_cfg = facebook_bot.load_fb_config()
            app_secret = fb_cfg.get("app_secret") or "ae0f70b5f83ac3af8e9a86fa795c5fb6"
            encoded_sig, payload = signed_request.split(".", 1)
            sig = base64.urlsafe_b64decode(encoded_sig + "=" * (-len(encoded_sig) % 4))
            data = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)).decode("utf-8"))
            
            expected_sig = hmac.new(app_secret.encode("utf-8"), payload.encode("utf-8"), hashlib.sha256).digest()
            if hmac.compare_digest(sig, expected_sig) and "user_id" in data:
                user_id = str(data["user_id"])
                confirmation_code = f"NXLY-DEL-{user_id[:8]}-{int(time.time())}"
                facebook_bot.bot_log(f"[DataDeletion] 🗑️ Processed Meta data deletion for User ID: {user_id}")
        except Exception:
            pass

    status_url = f"https://nxly.online/data-deletion?confirmation_code={confirmation_code}"
    return JSONResponse(
        content={
            "url": status_url,
            "confirmation_code": confirmation_code
        },
        status_code=200
    )

@app.get("/tiktokNTqeb08BaARdLz5iiOUzMfzMoV9qKI3R.txt", response_class=PlainTextResponse)
async def serve_tiktok_site_verification_old():
    return "tiktok-developers-site-verification=NTqeb08BaARdLz5iiOUzMfzMoV9qKI3R"

@app.get("/tiktok8ERMyMc1w1RVxSA9OuIdVCYUZ79ZQoHi.txt", response_class=PlainTextResponse)
@app.get("/tiktok-developers-site-verification", response_class=PlainTextResponse)
@app.get("/tiktok-developers-site-verification.txt", response_class=PlainTextResponse)
async def serve_tiktok_site_verification():
    return "tiktok-developers-site-verification=8ERMyMc1w1RVxSA9OuIdVCYUZ79ZQoHi"

app.mount("/", StaticFiles(directory=os.path.join(base_dir, "public"), html=True), name="public")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)
