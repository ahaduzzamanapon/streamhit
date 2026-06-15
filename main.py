import os
import re
import time
import json
import base64
import hmac
import hashlib
import urllib.parse
import secrets
import asyncio
import random
from datetime import datetime, timedelta
import httpx
import pymysql
import aiomysql
import uvicorn
from fastapi import FastAPI, Request, HTTPException
from fastapi.responses import HTMLResponse, StreamingResponse, JSONResponse, RedirectResponse
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from dotenv import load_dotenv

# Load env variables from .env using absolute path
base_dir = os.path.dirname(os.path.abspath(__file__))
env_path = os.path.join(base_dir, ".env")
load_dotenv(env_path)


# ==========================================================================
# 1. CONFIGURATION & ENVIRONMENT VARIABLES
# ==========================================================================
DB_HOST = os.getenv("DB_HOST", "127.0.0.1")
DB_PORT = int(os.getenv("DB_PORT", "3306"))
DB_USER = os.getenv("DB_USER", "root")
DB_PASSWORD = os.getenv("DB_PASSWORD", "")
DB_NAME = os.getenv("DB_NAME", "moviebox")
APP_URL = os.getenv("APP_URL", "http://localhost:3005")

SECRET_KEY_DEFAULT = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O"
USER_AGENT = "com.community.oneroom/50020046 (Linux; U; Android 11; en_US; Redmi Note 10; Build/RP1A.200720.011; Cronet/135.0.7012.3)"

HOST_POOL = [
    "https://api6.aoneroom.com",
    "https://api5.aoneroom.com",
    "https://api4.aoneroom.com",
    "https://api4sg.aoneroom.com",
    "https://api3.aoneroom.com",
    "https://api6sg.aoneroom.com",
    "https://api.inmoviebox.com",
]

WORKER_PROXIES = [
    "https://frosty-tree-ae87.vidnest-1.workers.dev",
    "https://summer-hat-3d00.vidnest-2.workers.dev",
    "https://misty-salad-49cf.vidnest-3.workers.dev",
    "https://dry-darkness-c431.vudnest-4.workers.dev",
]

# Generate unique device ID for API signing
DEVICE_ID = secrets.token_hex(16)
CLIENT_INFO = {
    "package_name": "com.community.oneroom",
    "version_name": "3.0.03.0529.03",
    "version_code": 50020046,
    "os": "android",
    "os_version": "11",
    "install_ch": "ps",
    "device_id": DEVICE_ID,
    "install_store": "ps",
    "gaid": "a3f5a2e1-8f8e-4a6c-9c9d-8d8e8f8a8b8c",
    "brand": "Redmi",
    "model": "Redmi Note 10",
    "system_language": "en",
    "net": "NETWORK_WIFI",
    "region": "US",
    "timezone": "Asia/Kolkata",
    "sp_code": "40401",
    "X-Play-Mode": "2"
}
CLIENT_INFO_STR = json.dumps(CLIENT_INFO, separators=(',', ':'))

# Global state
db_pool = None
worker_index = 0
active_api_base = "https://h5-api.aoneroom.com"

app = FastAPI()

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
    expose_headers=["Content-Length", "Content-Range"]
)

# ==========================================================================
# 2. DATABASE HELPER
# ==========================================================================
async def init_db():
    global db_pool
    try:
        db_pool = await aiomysql.create_pool(
            host=DB_HOST,
            port=DB_PORT,
            user=DB_USER,
            password=DB_PASSWORD,
            db=DB_NAME,
            minsize=2,
            maxsize=10,
            autocommit=True,
            connect_timeout=5
        )
        
        async with db_pool.acquire() as conn:
            async with conn.cursor() as cur:
                # 1. Subjects table
                await cur.execute("""
                    CREATE TABLE IF NOT EXISTS subjects (
                        subject_id VARCHAR(50) PRIMARY KEY,
                        title VARCHAR(255) NOT NULL,
                        subject_type INT NOT NULL,
                        cover TEXT,
                        backdrop TEXT,
                        rating DECIMAL(3, 1),
                        release_date VARCHAR(50),
                        country VARCHAR(100),
                        genre TEXT,
                        description TEXT,
                        is_cam BOOLEAN DEFAULT FALSE,
                        detail_path VARCHAR(255),
                        tmdb_id VARCHAR(50) DEFAULT NULL,
                        dubs TEXT DEFAULT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """)
                try:
                    await cur.execute("ALTER TABLE subjects ADD COLUMN detail_path VARCHAR(255) DEFAULT NULL")
                    print("[Database] Successfully added detail_path column to subjects table.")
                except Exception:
                    pass
                try:
                    await cur.execute("ALTER TABLE subjects ADD COLUMN tmdb_id VARCHAR(50) DEFAULT NULL")
                    await cur.execute("ALTER TABLE subjects ADD INDEX idx_tmdb_id (tmdb_id)")
                    print("[Database] Successfully added tmdb_id column and index to subjects table.")
                except Exception:
                    pass
                try:
                    await cur.execute("ALTER TABLE subjects ADD COLUMN dubs TEXT DEFAULT NULL")
                    print("[Database] Successfully added dubs column to subjects table.")
                except Exception:
                    pass
                # 2. Seasons table
                await cur.execute("""
                    CREATE TABLE IF NOT EXISTS seasons (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        subject_id VARCHAR(50) NOT NULL,
                        season_number INT NOT NULL,
                        episode_count INT,
                        episodes_list TEXT,
                        UNIQUE KEY unique_subject_season (subject_id, season_number),
                        FOREIGN KEY (subject_id) REFERENCES subjects(subject_id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """)
                # 3. Play Resources table
                await cur.execute("""
                    CREATE TABLE IF NOT EXISTS play_resources (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        resource_id VARCHAR(100) NOT NULL,
                        subject_id VARCHAR(50) NOT NULL,
                        season INT DEFAULT 0,
                        episode INT DEFAULT 0,
                        resolution INT,
                        size BIGINT,
                        resource_link TEXT,
                        expires_at TIMESTAMP NULL,
                        UNIQUE KEY unique_resource (subject_id, season, episode, resolution),
                        FOREIGN KEY (subject_id) REFERENCES subjects(subject_id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """)
                # 4. Captions table
                await cur.execute("""
                    CREATE TABLE IF NOT EXISTS captions (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        caption_id VARCHAR(100) NOT NULL,
                        subject_id VARCHAR(50) NOT NULL,
                        resource_id VARCHAR(100) NOT NULL,
                        label VARCHAR(100),
                        lang VARCHAR(20),
                        url TEXT,
                        UNIQUE KEY unique_caption (subject_id, resource_id, lang),
                        FOREIGN KEY (subject_id) REFERENCES subjects(subject_id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """)
                # 5. Banners table
                await cur.execute("""
                    CREATE TABLE IF NOT EXISTS banners (
                        subject_id VARCHAR(50) PRIMARY KEY,
                        title VARCHAR(255) NOT NULL,
                        image_url TEXT,
                        detail_path VARCHAR(255),
                        subject_type INT DEFAULT 1,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """)
                print("[Database] MySQL Tables check/creation complete.")
    except Exception as e:
        print(f"[Database] Error initializing database: {e}")

# Database operations
async def db_save_banner(b: dict):
    if not db_pool: return
    async with db_pool.acquire() as conn:
        async with conn.cursor() as cur:
            await cur.execute("""
                INSERT INTO banners (subject_id, title, image_url, detail_path, subject_type)
                VALUES (%s, %s, %s, %s, %s)
                ON DUPLICATE KEY UPDATE
                    title = VALUES(title),
                    image_url = VALUES(image_url),
                    detail_path = VALUES(detail_path),
                    subject_type = VALUES(subject_type)
            """, (b["subject_id"], b["title"], b["image_url"], b["detail_path"], b["subject_type"]))

async def db_get_banners():
    if not db_pool: return []
    async with db_pool.acquire() as conn:
        async with conn.cursor(aiomysql.DictCursor) as cur:
            await cur.execute("SELECT * FROM banners ORDER BY created_at DESC LIMIT 20")
            return await cur.fetchall()

async def db_save_subject(s: dict):
    if not db_pool: return
    dubs_json = json.dumps(s.get("dubs")) if s.get("dubs") is not None else None
    async with db_pool.acquire() as conn:
        async with conn.cursor() as cur:
            await cur.execute("""
                INSERT INTO subjects (subject_id, title, subject_type, cover, backdrop, rating, release_date, country, genre, description, is_cam, detail_path, tmdb_id, dubs)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                ON DUPLICATE KEY UPDATE
                    title=VALUES(title),
                    cover=VALUES(cover),
                    backdrop=VALUES(backdrop),
                    rating=VALUES(rating),
                    release_date=VALUES(release_date),
                    country=VALUES(country),
                    genre=VALUES(genre),
                    description=VALUES(description),
                    is_cam=VALUES(is_cam),
                    detail_path=VALUES(detail_path),
                    tmdb_id=COALESCE(VALUES(tmdb_id), tmdb_id),
                    dubs=COALESCE(VALUES(dubs), dubs)
            """, (
                str(s["subject_id"]), s["title"], int(s["subject_type"]), s.get("cover"), s.get("backdrop"),
                s.get("rating", 0.0), s.get("release_date"), s.get("country"), s.get("genre"), s.get("description"), s.get("is_cam", False),
                s.get("detail_path"), s.get("tmdb_id"), dubs_json
            ))

async def db_save_season(subject_id: str, season_number: int, episode_count: int, episodes_list: str):
    if not db_pool: return
    async with db_pool.acquire() as conn:
        async with conn.cursor() as cur:
            await cur.execute("""
                INSERT INTO seasons (subject_id, season_number, episode_count, episodes_list)
                VALUES (%s, %s, %s, %s)
                ON DUPLICATE KEY UPDATE
                    episode_count=VALUES(episode_count),
                    episodes_list=VALUES(episodes_list)
            """, (str(subject_id), int(season_number), int(episode_count), episodes_list))

async def db_save_resource(r: dict):
    if not db_pool: return
    async with db_pool.acquire() as conn:
        async with conn.cursor() as cur:
            await cur.execute("""
                INSERT INTO play_resources (resource_id, subject_id, season, episode, resolution, size, resource_link, expires_at)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                ON DUPLICATE KEY UPDATE
                    resource_id=VALUES(resource_id),
                    resource_link=VALUES(resource_link),
                    expires_at=VALUES(expires_at),
                    size=VALUES(size)
            """, (
                str(r["resource_id"]), str(r["subject_id"]), int(r.get("season", 0)), int(r.get("episode", 0)),
                int(r["resolution"]), int(r.get("size", 0)), r["resource_link"], r.get("expires_at")
            ))

async def db_save_caption(c: dict):
    if not db_pool: return
    async with db_pool.acquire() as conn:
        async with conn.cursor() as cur:
            await cur.execute("""
                INSERT INTO captions (caption_id, subject_id, resource_id, label, lang, url)
                VALUES (%s, %s, %s, %s, %s, %s)
                ON DUPLICATE KEY UPDATE
                    url=VALUES(url),
                    label=VALUES(label)
            """, (
                str(c["caption_id"]), str(c["subject_id"]), str(c["resource_id"]), c.get("label"), c["lang"], c["url"]
            ))

# ==========================================================================
# 3. ONEROOM COOKIE-BASED H5 API CLIENT (SIGNATURE-FREE)
# ==========================================================================
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

global_cookies = ""
cookies_expiry = 0.0

async def refresh_cookies_if_needed() -> str:
    global global_cookies, cookies_expiry
    now = time.time()
    if global_cookies and now < cookies_expiry:
        return global_cookies

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

    try:
        async with httpx.AsyncClient(trust_env=False, timeout=15.0) as client:
            cookie_url = "https://h5.aoneroom.com/wefeed-h5-bff/app/get-latest-app-pkgs?app_name=moviebox"
            resp = await client.get(cookie_url, headers=headers)
            if resp.status_code == 200:
                cookie_headers = resp.headers.get_list("Set-Cookie")
                parsed_cookies = []
                for cookie in cookie_headers:
                    part = cookie.split(";")[0]
                    parsed_cookies.append(part)
                global_cookies = "; ".join(parsed_cookies)
                cookies_expiry = now + 3600.0  # 60 mins cache
                print(f"[API Auth] Refreshed H5 cookies successfully.")
                return global_cookies
    except Exception as e:
        print(f"[API Auth] Cookie refresh failed: {e}")
    
    if global_cookies:
        return global_cookies
    raise Exception("Failed to acquire OneRoom H5 cookies")

async def request_h5_api(method: str, path: str, body_dict: dict = None, host: str = "https://h5-api.aoneroom.com", origin: str = None, referer: str = None) -> dict:
    cookies = await refresh_cookies_if_needed()
    ip = get_random_singapore_ip()
    
    headers = {
        "X-Forwarded-For": ip,
        "CF-Connecting-IP": ip,
        "X-Real-IP": ip,
        "X-Client-Info": json.dumps({"timezone": "Africa/Nairobi"}),
        "Accept-Language": "en-US,en;q=0.5",
        "Accept": "application/json",
        "User-Agent": "okhttp/4.12.0",
        "Referer": referer or "https://h5.aoneroom.com",
        "Cookie": cookies
    }
    if origin:
        headers["Origin"] = origin

    url = f"{host}{path}"
    try:
        async with httpx.AsyncClient(trust_env=False, timeout=15.0) as client:
            if method.upper() == "POST":
                resp = await client.post(url, json=body_dict, headers=headers)
            else:
                resp = await client.get(url, headers=headers)
                
            if resp.status_code == 200:
                data = resp.json()
                if data.get("code") == 0 or "data" in data:
                    return data
                else:
                    raise Exception(data.get("message", f"API error code {data.get('code')}"))
            else:
                raise Exception(f"HTTP status {resp.status_code}")
    except Exception as e:
        print(f"[API Error] H5 API request failed for {path}: {e}")
        raise e

# Extract CDN expiration
def get_link_expiration(url: str) -> datetime:
    match = re.search(r'[?&](t|expires|exp)=(\d+)', url)
    if match:
        timestamp = int(match.group(2))
        if timestamp > 9999999999:  # Milliseconds
            timestamp = timestamp // 1000
        return datetime.fromtimestamp(timestamp)
    return datetime.now() + timedelta(hours=2)

# ==========================================================================
# 4. BACKGROUND SCRAPER SYSTEM
# ==========================================================================
async def run_config_sync():
    global active_api_base
    try:
        async with httpx.AsyncClient(trust_env=False, timeout=10.0) as client:
            resp = await client.get("https://moviebox.ph/", headers={
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            })
            if resp.status_code == 200:
                html = resp.text
                match = re.search(r'baseUrl\s*:\s*"([^"]+)"', html)
                if match:
                    active_api_base = match.group(1)
                match2 = re.search(r'clientFetch\s*:\s*\{[^}]*baseUrl\s*:\s*"([^"]+)"', html)
                if match2:
                    active_api_base = match2.group(1)
                print(f"[Scraper] Configuration synchronized. Active Base URL: {active_api_base}")
    except Exception as e:
        print(f"[Scraper] Failed to fetch moviebox.ph configuration: {e}")

async def scrape_subject_details(subject_id: str) -> dict:
    try:
        # Get details from H5 API
        data = await request_h5_api("GET", f"/wefeed-h5api-bff/detail?subjectId={subject_id}")
        detail = data.get("data", {}).get("subject", {})
        if not detail: return {}

        genres = detail.get("genre", [])
        genres_str = ",".join(genres) if isinstance(genres, list) else str(genres)
        
        cover_val = detail.get("cover")
        cover_url = cover_val.get("url") if isinstance(cover_val, dict) else str(cover_val)
        
        backdrop_val = detail.get("boxCover") or detail.get("cover")
        backdrop_url = backdrop_val.get("url") if isinstance(backdrop_val, dict) else cover_url

        subject_data = {
            "subject_id": str(subject_id),
            "title": detail.get("title", ""),
            "subject_type": int(detail.get("subjectType", 1)),
            "cover": cover_url,
            "backdrop": backdrop_url,
            "rating": float(detail.get("imdbRatingValue") or 0.0),
            "release_date": detail.get("releaseDate", ""),
            "country": detail.get("countryName", ""),
            "genre": genres_str,
            "description": detail.get("description", ""),
            "is_cam": bool(detail.get("isCam", False)),
            "detail_path": detail.get("detailPath"),
            "dubs": detail.get("dubs", [])
        }
        
        await db_save_subject(subject_data)
        safe_title = subject_data['title'].encode('ascii', 'replace').decode('ascii')
        print(f"[Scraper] Successfully cached subject metadata: {safe_title}")

        # Get Seasons & Episodes if TV Show
        if subject_data["subject_type"] == 2:
            resource_obj = data.get("data", {}).get("resource", {})
            seasons = resource_obj.get("seasons", [])
            for se in seasons:
                se_num = int(se.get("se", 1))
                max_ep = int(se.get("maxEp", 0))
                episodes_list = ",".join(str(i) for i in range(1, max_ep + 1))
                await db_save_season(subject_id, se_num, max_ep, episodes_list)
                
                # Fetch play resources/captions for each episode in the first season proactively
                if se_num == 1 and max_ep > 0:
                    for ep_num in range(1, max_ep + 1):
                        await scrape_episode_resources(subject_id, se_num, ep_num)
                        await asyncio.sleep(0.5)
        else:
            # Movie - fetch resources directly
            await scrape_episode_resources(subject_id, 0, 0)

        return subject_data
    except Exception as e:
        print(f"[Scraper] Error scraping details for ID {subject_id}: {e}")
        return {}

async def scrape_episode_resources(subject_id: str, season: int, episode: int):
    try:
        detail_path = ""
        async with db_pool.acquire() as conn:
            async with conn.cursor() as cur:
                await cur.execute("SELECT detail_path FROM subjects WHERE subject_id = %s", (subject_id,))
                row = await cur.fetchone()
                if row:
                    detail_path = row[0]
                    
        if not detail_path:
            detail_path = "details"
            
        referer = f"https://123movienow.cc/spa/videoPlayPage/movies/{detail_path}?id={subject_id}&type=/movie/detail"
        origin = "https://123movienow.cc"
        
        path = f"/wefeed-h5-bff/web/subject/download?subjectId={subject_id}&se={season}&ep={episode}"
        download_data = await request_h5_api("GET", path, host="https://h5.aoneroom.com", origin=origin, referer=referer)
        
        inner_data = download_data.get("data", {})
        downloads = inner_data.get("downloads", [])
        captions = inner_data.get("captions", [])
        
        for r in downloads:
            r_id = r.get("id")
            r_link = r.get("url")
            if not r_id or not r_link: continue
            
            exp = get_link_expiration(r_link)
            
            resource_item = {
                "resource_id": str(r_id),
                "subject_id": str(subject_id),
                "season": season,
                "episode": episode,
                "resolution": int(r.get("resolution", 0)),
                "size": int(r.get("size", 0)),
                "resource_link": r_link,
                "expires_at": exp.strftime('%Y-%m-%d %H:%M:%S')
            }
            await db_save_resource(resource_item)
            
        # Cache captions associated with each resource
        for cap in captions:
            for r in downloads:
                r_id = r.get("id")
                if not r_id: continue
                cap_item = {
                    "caption_id": str(cap.get("id")),
                    "subject_id": str(subject_id),
                    "resource_id": str(r_id),
                    "label": cap.get("lanName"),
                    "lang": cap.get("lan"),
                    "url": cap.get("url")
                }
                await db_save_caption(cap_item)
    except Exception as e:
        print(f"[Scraper] Failed to fetch resources for {subject_id} S{season}E{episode}: {e}")

async def run_incremental_scraper():
    print("[Scraper] Starting incremental scraping cycle...")
    try:
        # Fetch latest Movie/TV/Anime updates from filter endpoint
        for sub_type in [1, 2, 7]:
            payload = {
                "page": 1,
                "perPage": 20,
                "genre": "",
                "country": "",
                "year": "",
                "language": "",
                "sort": "Latest",
                "subjectType": sub_type
            }
            
            data = await request_h5_api("POST", "/wefeed-h5api-bff/subject/filter", payload)
            items = data.get("data", {}).get("items", [])
            
            if not items and data.get("data", {}).get("results"):
                results = data.get("data", {}).get("results", [])
                if results:
                    items = results[0].get("subjects", [])

            for item in items:
                sub_id = item.get("subjectId")
                if not sub_id: continue
                
                # Check if already cached
                async with db_pool.acquire() as conn:
                    async with conn.cursor() as cur:
                        await cur.execute("SELECT 1 FROM subjects WHERE subject_id = %s", (str(sub_id),))
                        exists = await cur.fetchone()
                        
                if not exists:
                    safe_title = item.get('title', '').encode('ascii', 'replace').decode('ascii')
                    print(f"[Scraper] New title found: {safe_title}. Fetching full details...")
                    await scrape_subject_details(sub_id)
                    await asyncio.sleep(1.0)
    except Exception as e:
        print(f"[Scraper] Incremental scraper loop encountered error: {e}")

async def scraper_loop():
    # Wait for DB connection
    while db_pool is None:
        await asyncio.sleep(1)
        
    await run_config_sync()
    
    while True:
        try:
            # Sync config and crawl latest lists
            await run_config_sync()
            await run_incremental_scraper()
        except Exception as e:
            print(f"[Scraper Loop] Error: {e}")
        # Run every 10 minutes
        await asyncio.sleep(600)

PROGRESS_FILE = os.path.join(base_dir, ".scraper_progress.json")

def read_scraper_progress() -> dict:
    if os.path.exists(PROGRESS_FILE):
        try:
            with open(PROGRESS_FILE, "r") as f:
                return json.load(f)
        except Exception:
            pass
    return {"1": 2, "2": 2, "7": 2}

def save_scraper_progress(progress: dict):
    try:
        with open(PROGRESS_FILE, "w") as f:
            json.dump(progress, f)
    except Exception as e:
        print(f"[Scraper] Failed to save progress: {e}")

async def run_historical_scraper():
    progress = read_scraper_progress()
    print(f"[Scraper] Starting historical scraping cycle. Current progress: {progress}")
    
    for sub_type_str in ["2", "1", "7"]:
        sub_type = int(sub_type_str)
        current_page = progress.get(sub_type_str, 2)
        
        while current_page <= 300:
            print(f"[Scraper] Scraping historical page {current_page} for subject type {sub_type}...")
            try:
                payload = {
                    "page": current_page,
                    "perPage": 20,
                    "genre": "",
                    "country": "",
                    "year": "",
                    "language": "",
                    "sort": "Latest",
                    "subjectType": sub_type
                }
                
                data = await request_h5_api("POST", "/wefeed-h5api-bff/subject/filter", payload)
                items = data.get("data", {}).get("items", [])
                
                if not items and data.get("data", {}).get("results"):
                    results = data.get("data", {}).get("results", [])
                    if results:
                        items = results[0].get("subjects", [])
                
                if not items:
                    print(f"[Scraper] No more items found on page {current_page} for type {sub_type}. Stopping historical scraper for this type.")
                    break
                
                new_items_count = 0
                for item in items:
                    sub_id = item.get("subjectId")
                    if not sub_id: continue
                    
                    async with db_pool.acquire() as conn:
                        async with conn.cursor() as cur:
                            await cur.execute("SELECT 1 FROM subjects WHERE subject_id = %s", (str(sub_id),))
                            exists = await cur.fetchone()
                            
                    if not exists:
                        safe_title = item.get('title', '').encode('ascii', 'replace').decode('ascii')
                        print(f"[Scraper] Historical crawl: Found '{safe_title}' (ID: {sub_id}). Scraping details...")
                        await scrape_subject_details(sub_id)
                        new_items_count += 1
                        await asyncio.sleep(2.0)
                
                print(f"[Scraper] Page {current_page} done. Crawled {new_items_count} new items.")
                current_page += 1
                progress[sub_type_str] = current_page
                save_scraper_progress(progress)
                
                await asyncio.sleep(5.0)
                
            except Exception as e:
                print(f"[Scraper] Error during historical crawl of page {current_page} for type {sub_type}: {e}")
                await asyncio.sleep(15.0)

async def historical_scraper_loop():
    while db_pool is None:
        await asyncio.sleep(1)
        
    await asyncio.sleep(30)
    
    while True:
        try:
            await run_historical_scraper()
        except Exception as e:
            print(f"[Historical Scraper Loop] Error: {e}")
        await asyncio.sleep(3600)

# Start scraper on application startup
@app.on_event("startup")
async def startup_event():
    asyncio.create_task(init_db())
    if os.getenv("RUN_SCRAPER", "false").lower() == "true":
        asyncio.create_task(scraper_loop())
        asyncio.create_task(historical_scraper_loop())

@app.on_event("shutdown")
async def shutdown_event():
    if db_pool:
        db_pool.close()
        await db_pool.wait_closed()

# ==========================================================================
# 4.5 TMDB TO MOVIEBOX RESOLVER SYSTEM
# ==========================================================================
TMDB_API_KEY = os.getenv("TMDB_API_KEY", "52c48694824d7f57b4179fc097ec03d3")
TMDB_BASE_URL = "https://api.themoviedb.org/3"

async def fetch_tmdb_data(tmdb_id: str, is_tv: bool) -> dict:
    path = f"/tv/{tmdb_id}" if is_tv else f"/movie/{tmdb_id}"
    url = f"{TMDB_BASE_URL}{path}?api_key={TMDB_API_KEY}"
    try:
        async with httpx.AsyncClient(trust_env=False, timeout=10.0) as client:
            resp = await client.get(url)
            if resp.status_code == 200:
                data = resp.json()
                if is_tv:
                    title = data.get("name", "")
                    air_date = data.get("first_air_date", "")
                    year = air_date[:4] if len(air_date) >= 4 else ""
                else:
                    title = data.get("title", "")
                    rel_date = data.get("release_date", "")
                    year = rel_date[:4] if len(rel_date) >= 4 else ""
                return {"title": title, "year": year}
            else:
                raise Exception(f"TMDB status {resp.status_code}")
    except Exception as e:
        print(f"[TMDB Error] Failed to fetch TMDB data for {tmdb_id}: {e}")
        raise e

def normalize_title(title: str) -> str:
    if not title:
        return ""
    lower = title.lower()
    cleaned = re.sub(r'[^a-z0-9\s]', ' ', lower)
    return re.sub(r'\s+', ' ', cleaned).strip()

def title_looks_like_match(tmdb_title: str, moviebox_title: str, is_tv: bool, season: int, episode: int) -> bool:
    norm_tmdb = normalize_title(tmdb_title)
    norm_box = normalize_title(moviebox_title)
    
    clean_tmdb = re.sub(r'\s*\(?\d{4}\)?\s*$', '', norm_tmdb).strip()
    clean_box = re.sub(r'\s*\(?\d{4}\)?\s*$', '', norm_box).strip()
    
    if clean_tmdb == clean_box:
        return True
        
    if clean_box.startswith(clean_tmdb) or clean_tmdb.startswith(clean_box):
        diff = abs(len(clean_box) - len(clean_tmdb))
        return diff < 15
        
    if is_tv and season > 0:
        s_match = f"s{season}" in norm_box or f"season {season}" in norm_box
        if not s_match:
            return False
        if episode > 0:
            e_match = f"e{episode}" in norm_box or f"{season}x{episode}" in norm_box
            if not e_match:
                return False
        return True
    return False

async def search_moviebox_for_tmdb(title: str, year: str, is_tv: bool, season: int, episode: int) -> dict:
    queries = [title]
    title_no_year = re.sub(r'\s*\(?\d{4}\)?\s*$', '', title).strip()
    if title_no_year != title:
        queries.append(title_no_year)
        
    if is_tv and season > 0:
        base = title_no_year if title_no_year else title
        queries.append(f"{base} S{season}")
        queries.append(f"{base} Season {season}")
        if episode > 0:
            queries.append(f"{base} S{season}E{episode}")
            queries.append(f"{base} {season}x{episode}")
            
    subject_type = 2 if is_tv else 1
    
    for keyword in queries:
        try:
            payload = {
                "keyword": keyword,
                "page": 1,
                "perPage": 30,
                "subjectType": subject_type
            }
            data = await request_h5_api("POST", "/wefeed-h5api-bff/subject/search", payload)
            items = data.get("data", {}).get("items", [])
            for item in items:
                sub_id = item.get("subjectId")
                item_title = item.get("title", "")
                detail_path = item.get("detailPath", "")
                if not sub_id or not item_title or not detail_path:
                    continue
                    
                item_sub_type = int(item.get("subjectType", 1))
                if is_tv and item_sub_type != 2:
                    continue
                if not is_tv and item_sub_type != 1:
                    continue
                    
                if not title_looks_like_match(title, item_title, is_tv, season, episode):
                    continue
                    
                if not is_tv:
                    rel_date = item.get("releaseDate", "")
                    last_rel_date = item.get("lastReleaseDate", "")
                    item_year = rel_date[:4] if len(rel_date) >= 4 else (last_rel_date[:4] if len(last_rel_date) >= 4 else "")
                    if year and item_year and year != item_year:
                        continue
                        
                return {"subjectId": str(sub_id), "detailPath": detail_path, "title": item_title}
        except Exception as e:
            print(f"[Search Error] Search failed for keyword '{keyword}': {e}")
            continue
            
    raise Exception(f"No matching MovieBox title found for '{title}'")

LANGUAGE_DUB_MAPPINGS = {
    "hindi": {"code": "hi", "contains": "hindi dub"},
    "tamil": {"code": "ta", "contains": "tamil dub"},
    "telugu": {"code": "te", "contains": "telugu dub"},
    "malayalam": {"code": "ml", "contains": "malayalam dub"},
    "kannada": {"code": "kn", "contains": "kannada dub"},
    "bengali": {"code": "bn", "contains": "bengali dub"},
    "russian": {"code": "ru", "contains": "russian dub"},
    "arabic": {"code": "ar", "contains": "arabic dub"},
    "french": {"code": "fr", "contains": "french dub"},
    "portuguese": {"code": "ptbr", "contains": "ptbr dub"},
    "kurdish": {"code": "ku", "contains": "kurdish dub"},
    "indonesian": {"code": "id", "contains": "indonesian dub"},
    "tagalog": {"code": "tl", "contains": "tagalog dub"},
    "punjabi": {"code": "pa", "contains": "punjabi dub"},
}

async def find_best_subject_for_language(detail_path: str, requested_lang: str, fallback_subject_id: str) -> dict:
    if not detail_path:
        return {"subjectId": fallback_subject_id, "detailPath": detail_path}
        
    try:
        data = await request_h5_api("GET", f"/wefeed-h5api-bff/detail?detailPath={urllib.parse.quote(detail_path)}")
        dubs = data.get("data", {}).get("subject", {}).get("dubs", [])
        
        if not dubs:
            if requested_lang == "en":
                return {"subjectId": fallback_subject_id, "detailPath": detail_path, "original": True}
            return {"subjectId": fallback_subject_id, "detailPath": detail_path}
            
        requested_lang = requested_lang.lower().strip()
        if requested_lang != "en":
            mapping = LANGUAGE_DUB_MAPPINGS.get(requested_lang)
            if not mapping:
                return {"subjectId": fallback_subject_id, "detailPath": detail_path}
                
            candidates = []
            for d in dubs:
                code_match = d.get("lanCode") == mapping["code"]
                name_match = mapping["contains"] in d.get("lanName", "").lower()
                if code_match or name_match:
                    candidates.append(d)
            if not candidates:
                return {"subjectId": fallback_subject_id, "detailPath": detail_path}
                
            best = candidates[0]
            for d in candidates:
                if d.get("type") == 0:
                    best = d
                    break
            return {
                "subjectId": str(best["subjectId"]),
                "detailPath": best.get("detailPath") or detail_path,
                "lanName": best.get("lanName"),
                "lanCode": best.get("lanCode")
            }
            
        for d in dubs:
            if d.get("original"):
                return {"subjectId": str(d["subjectId"]), "detailPath": d.get("detailPath") or detail_path, "original": True}
        for d in dubs:
            if d.get("lanCode") == "en":
                return {"subjectId": str(d["subjectId"]), "detailPath": d.get("detailPath") or detail_path}
                
        first = dubs[0]
        return {"subjectId": str(first["subjectId"]), "detailPath": first.get("detailPath") or detail_path}
    except Exception as e:
        print(f"[Dub Error] Error resolving language dub: {e}")
        return {"subjectId": fallback_subject_id, "detailPath": detail_path}

async def resolve_tmdb_resource(tmdb_id: str, is_tv: bool, season: int, episode: int, lang: str = "en") -> dict:
    now = datetime.now()
    
    # 1. Try local MySQL lookup first
    if db_pool:
        try:
            async with db_pool.acquire() as conn:
                async with conn.cursor(aiomysql.DictCursor) as cur:
                    await cur.execute("SELECT * FROM subjects WHERE tmdb_id = %s AND subject_type = %s", (tmdb_id, 2 if is_tv else 1))
                    subject = await cur.fetchone()
                    if subject:
                        subject_id = subject["subject_id"]
                        await cur.execute("""
                            SELECT * FROM play_resources 
                            WHERE subject_id = %s AND season = %s AND episode = %s
                        """, (subject_id, season, episode))
                        resources = await cur.fetchall()
                        
                        valid_resources = [r for r in resources if r["expires_at"] and r["expires_at"] > now + timedelta(minutes=5)]
                        
                        if valid_resources:
                            await cur.execute("""
                                SELECT * FROM captions WHERE subject_id = %s AND resource_id = %s
                            """, (subject_id, valid_resources[0]["resource_id"]))
                            caps = await cur.fetchall()
                            
                            qualities = []
                            for r in valid_resources:
                                qualities.append({
                                    "resolution": r["resolution"],
                                    "size": r["size"],
                                    "url": f"{APP_URL}/fetch?source_url={urllib.parse.quote(r['resource_link'])}"
                                })
                            qualities.sort(key=lambda q: q["resolution"])
                            
                            captions = []
                            for c in caps:
                                captions.append({
                                    "language": c["lang"],
                                    "name": c["label"],
                                    "url": f"{APP_URL}/api/proxy-subtitle?url={urllib.parse.quote(c['url'])}"
                                })
                                
                            highest = qualities[-1]["url"] if qualities else ""
                            return {
                                "subjectId": subject_id,
                                "title": subject["title"],
                                "url": highest,
                                "highest": highest,
                                "qualities": qualities,
                                "captions": captions
                            }
        except Exception as db_err:
            print(f"[DB Resolver Lookup Error] {db_err}")
                    
    # 2. Cache miss: Resolve using TMDB -> MovieBox API
    td = await fetch_tmdb_data(tmdb_id, is_tv)
    match = await search_moviebox_for_tmdb(td["title"], td["year"], is_tv, season, episode)
    
    preferred = await find_best_subject_for_language(match["detailPath"], lang, match["subjectId"])
    subject_id = preferred["subjectId"]
    
    # Save/Update subject in database with tmdb_id
    try:
        api_data = await request_h5_api("GET", f"/wefeed-h5api-bff/detail?subjectId={subject_id}")
        detail = api_data.get("data", {}).get("subject", {})
        if detail:
            genres = detail.get("genre", [])
            genres_str = ",".join(genres) if isinstance(genres, list) else str(genres)
            cover_val = detail.get("cover")
            cover_url = cover_val.get("url") if isinstance(cover_val, dict) else str(cover_val)
            backdrop_val = detail.get("boxCover") or detail.get("cover")
            backdrop_url = backdrop_val.get("url") if isinstance(backdrop_val, dict) else cover_url
            
            subject_data = {
                "subject_id": str(subject_id),
                "title": detail.get("title", td["title"]),
                "subject_type": 2 if is_tv else 1,
                "cover": cover_url,
                "backdrop": backdrop_url,
                "rating": float(detail.get("imdbRatingValue") or 0.0),
                "release_date": detail.get("releaseDate", td["year"]),
                "country": detail.get("countryName", ""),
                "genre": genres_str,
                "description": detail.get("description", ""),
                "is_cam": bool(detail.get("isCam", False)),
                "detail_path": detail.get("detailPath", match["detailPath"]),
                "tmdb_id": tmdb_id,
                "dubs": detail.get("dubs", [])
            }
            await db_save_subject(subject_data)
            
            if is_tv:
                resource_obj = api_data.get("data", {}).get("resource", {})
                seasons_list = resource_obj.get("seasons", [])
                for se in seasons_list:
                    se_num = int(se.get("se", 1))
                    max_ep = int(se.get("maxEp", 0))
                    episodes_list = ",".join(str(i) for i in range(1, max_ep + 1))
                    await db_save_season(subject_id, se_num, max_ep, episodes_list)
    except Exception as e:
        print(f"[Resolver] Metadata scrape failed: {e}")
        subject_data = {
            "subject_id": str(subject_id),
            "title": match["title"],
            "subject_type": 2 if is_tv else 1,
            "tmdb_id": tmdb_id,
            "detail_path": match["detailPath"]
        }
        await db_save_subject(subject_data)
        
    referer = f"https://123movienow.cc/spa/videoPlayPage/movies/{preferred.get('detailPath') or match['detailPath']}?id={subject_id}&type=/movie/detail"
    origin = "https://123movienow.cc"
    
    path = f"/wefeed-h5-bff/web/subject/download?subjectId={subject_id}&se={season if is_tv else 0}&ep={episode if is_tv else 0}"
    download_data = await request_h5_api("GET", path, host="https://h5.aoneroom.com", origin=origin, referer=referer)
    
    inner_data = download_data.get("data", {})
    downloads = inner_data.get("downloads", [])
    captions = inner_data.get("captions", [])
    
    qualities = []
    for r in downloads:
        r_id = r.get("id")
        r_link = r.get("url")
        if not r_id or not r_link:
            continue
            
        exp = get_link_expiration(r_link)
        resolution = int(r.get("resolution", 0))
        size = int(r.get("size", 0))
        
        resource_item = {
            "resource_id": str(r_id),
            "subject_id": str(subject_id),
            "season": season if is_tv else 0,
            "episode": episode if is_tv else 0,
            "resolution": resolution,
            "size": size,
            "resource_link": r_link,
            "expires_at": exp.strftime('%Y-%m-%d %H:%M:%S')
        }
        await db_save_resource(resource_item)
        
        qualities.append({
            "resolution": resolution,
            "size": size,
            "url": f"{APP_URL}/fetch?source_url={urllib.parse.quote(r_link)}"
        })
        
    qualities.sort(key=lambda q: q["resolution"])
    
    formatted_captions = []
    for cap in captions:
        cap_id = cap.get("id")
        cap_url = cap.get("url")
        if not cap_id or not cap_url:
            continue
            
        for r in downloads:
            r_id = r.get("id")
            if not r_id:
                continue
            cap_item = {
                "caption_id": str(cap_id),
                "subject_id": str(subject_id),
                "resource_id": str(r_id),
                "label": cap.get("lanName"),
                "lang": cap.get("lan"),
                "url": cap_url
            }
            await db_save_caption(cap_item)
            
        formatted_captions.append({
            "language": cap.get("lan"),
            "name": cap.get("lanName"),
            "url": f"{APP_URL}/api/proxy-subtitle?url={urllib.parse.quote(cap_url)}"
        })
        
    highest = qualities[-1]["url"] if qualities else ""
    
    return {
        "subjectId": subject_id,
        "title": match["title"],
        "url": highest,
        "highest": highest,
        "qualities": qualities,
        "captions": formatted_captions
    }

# ==========================================================================
# 5. REST API ENDPOINTS
# ==========================================================================

@app.get("/movie/{tmdbID}")
async def resolve_movie_endpoint(tmdbID: str, lang: str = "en"):
    try:
        data = await resolve_tmdb_resource(tmdbID, is_tv=False, season=0, episode=0, lang=lang)
        return data
    except Exception as e:
        raise HTTPException(status_code=502, detail=str(e))

@app.get("/tv/{tmdbID}/{season}/{episode}")
async def resolve_tv_endpoint(tmdbID: str, season: int, episode: int, lang: str = "en"):
    try:
        data = await resolve_tmdb_resource(tmdbID, is_tv=True, season=season, episode=episode, lang=lang)
        return data
    except Exception as e:
        raise HTTPException(status_code=502, detail=str(e))

@app.get("/api/resolve-tmdb")
async def resolve_tmdb_api(tmdbId: str, type: str = "movie", season: int = 1, episode: int = 1):
    try:
        is_tv = type == "tv"
        data = await resolve_tmdb_resource(tmdbId, is_tv=is_tv, season=season, episode=episode)
        
        return {
            "code": 0,
            "data": {
                "subjectId": data["subjectId"],
                "title": data["title"],
                "subjectType": 2 if is_tv else 1,
                "season": season,
                "episode": episode
            }
        }
    except Exception as e:
        raise HTTPException(status_code=502, detail=str(e))


# Transparent proxy to grab initial feeds and auto-cache subjects
@app.get("/api/home")
async def get_home(page: int = 1, tabId: int = 0):
    try:
        api_path = f"/wefeed-h5api-bff/tab-operating?page={page}&tabId={tabId}"
        data = await request_h5_api("GET", api_path)
        
        # Async background save discovered movies to db
        asyncio.create_task(cache_discovered_subjects(data))
        
        operating_list = data.get("data", {}).get("operatingList", [])
        mapped_data = {
            "code": 0,
            "data": {
                "items": operating_list
            }
        }
        return mapped_data
    except Exception as e:
        return JSONResponse(status_code=500, content={"error": str(e)})

@app.get("/api/banners")
async def get_banners():
    try:
        banners = await db_get_banners()
        items = []
        for b in banners:
            items.append({
                "subjectId": b["subject_id"],
                "title": b["title"],
                "image": b["image_url"],
                "detailPath": b["detail_path"],
                "subjectType": b["subject_type"],
                "content": b["title"],
                "subject": {
                    "title": b["title"],
                    "detailPath": b["detail_path"],
                    "subjectType": b["subject_type"]
                }
            })
        return {"code": 0, "data": {"list": items}}
    except Exception as e:
        return JSONResponse(status_code=500, content={"error": str(e)})

async def cache_discovered_subjects(home_data: dict):
    operating_list = home_data.get("data", {}).get("operatingList", [])
    subjects = []
    
    for sec in operating_list:
        # Extract subjects from banners
        banner = sec.get("banner")
        if banner and isinstance(banner, dict):
            banner_items = banner.get("items", [])
            for item in banner_items:
                sub = item.get("subject")
                if sub:
                    subjects.append(sub)
                
                # Cache banner details in banners table
                sub_id = item.get("subjectId") or (sub.get("subjectId") if sub else None)
                if sub_id and str(sub_id) != "0":
                    img_val = item.get("image")
                    img_url = img_val.get("url") if isinstance(img_val, dict) else str(img_val or "")
                    if not img_url and sub:
                        img_url = sub.get("cover", {}).get("url") if isinstance(sub.get("cover"), dict) else str(sub.get("cover") or "")
                    
                    b_data = {
                        "subject_id": str(sub_id),
                        "title": item.get("title") or (sub.get("title") if sub else ""),
                        "image_url": img_url,
                        "detail_path": item.get("detailPath") or (sub.get("detailPath") if sub else ""),
                        "subject_type": int(item.get("subjectType") or (sub.get("subjectType") if sub else 1))
                    }
                    asyncio.create_task(db_save_banner(b_data))
                    
        # Extract from regular subject lists
        sec_subjects = sec.get("subjects", [])
        if sec_subjects:
            subjects.extend(sec_subjects)
            
    for sub in subjects:
        sub_id = sub.get("subjectId")
        if not sub_id or str(sub_id) == "0": continue
        
        # Simple metadata extraction
        genres = sub.get("genre", [])
        genres_str = ",".join(genres) if isinstance(genres, list) else str(genres)
        cover_val = sub.get("cover")
        cover_url = cover_val.get("url") if isinstance(cover_val, dict) else str(cover_val)
        
        s_data = {
            "subject_id": str(sub_id),
            "title": sub.get("title", ""),
            "subject_type": int(sub.get("subjectType", 1)),
            "cover": cover_url,
            "backdrop": cover_url,
            "rating": float(sub.get("imdbRatingValue") or 7.5),
            "release_date": sub.get("releaseDate", "2026"),
            "country": sub.get("countryName", ""),
            "genre": genres_str,
            "description": "",
            "is_cam": bool(sub.get("isCam", False)),
            "detail_path": sub.get("detailPath")
        }
        await db_save_subject(s_data)

# Search endpoint - queries local db first, queries OneRoom and caches on fallback
@app.post("/api/search")
async def search_content(payload: dict):
    keyword = payload.get("keyword", "").strip()
    page = int(payload.get("page", 1))
    per_page = int(payload.get("perPage", 20))
    subject_type = int(payload.get("subjectType", 0))

    if not keyword:
        return {"code": 0, "data": {"items": []}}

    # Try local MySQL search
    offset = (page - 1) * per_page
    query = "SELECT * FROM subjects WHERE title LIKE %s"
    params = [f"%{keyword}%"]
    
    if subject_type > 0:
        query += " AND subject_type = %s"
        params.append(subject_type)
        
    query += " ORDER BY created_at DESC LIMIT %s OFFSET %s"
    params.extend([per_page, offset])

    local_results = []
    if db_pool:
        try:
            async with db_pool.acquire() as conn:
                async with conn.cursor(aiomysql.DictCursor) as cur:
                    await cur.execute(query, tuple(params))
                    rows = await cur.fetchall()
                    for row in rows:
                        local_results.append({
                            "subjectId": row["subject_id"],
                            "title": row["title"],
                            "subjectType": row["subject_type"],
                            "cover": {"url": row["cover"]},
                            "imdbRatingValue": str(row["rating"]),
                            "releaseDate": row["release_date"],
                            "countryName": row["country"],
                            "genre": row["genre"].split(",") if row["genre"] else [],
                            "isCam": row["is_cam"]
                        })
        except Exception as db_err:
            print(f"[DB Search Error] {db_err}")

    if len(local_results) >= 5:
        # Return local results
        return {
            "code": 0,
            "data": {
                "items": local_results,
                "pager": {"hasMore": len(local_results) == per_page}
            }
        }

    # Fetch from API and save missing to DB
    try:
        api_path = "/wefeed-h5api-bff/subject/search"
        api_payload = {
            "keyword": keyword,
            "page": page,
            "perPage": per_page,
            "subjectType": subject_type
        }
        data = await request_h5_api("POST", api_path, api_payload)
        items = data.get("data", {}).get("items", [])
        
        # Cache results asynchronously
        for item in items:
            sub_id = item.get("subjectId")
            if sub_id:
                asyncio.create_task(scrape_subject_details(sub_id))
                
        return data
    except Exception as e:
        return {"code": 0, "data": {"items": local_results, "pager": {"hasMore": False}}}

# Dynamic Multi-Level Filters
@app.post("/api/filter")
async def filter_content(payload: dict):
    genre = payload.get("genre", "*")
    country = payload.get("country", "*")
    year = payload.get("year", "*")
    language = payload.get("language", "*")
    sort = payload.get("sort", "ForYou")
    subject_type = int(payload.get("subjectType", 0))
    page = int(payload.get("page", 1))
    per_page = int(payload.get("perPage", 20))

    # Build MySQL Query
    conditions = []
    params = []

    if genre != "*":
        conditions.append("genre LIKE %s")
        params.append(f"%{genre}%")
    if country != "*":
        conditions.append("country = %s")
        params.append(country)
    if year != "*":
        conditions.append("release_date LIKE %s")
        params.append(f"{year}%")
    if subject_type > 0:
        conditions.append("subject_type = %s")
        params.append(subject_type)

    where_clause = " WHERE " + " AND ".join(conditions) if conditions else ""
    
    order_clause = " ORDER BY created_at DESC"
    if sort == "Hottest":
        order_clause = " ORDER BY rating DESC"
    elif sort == "Latest":
        order_clause = " ORDER BY release_date DESC"

    offset = (page - 1) * per_page
    query = f"SELECT * FROM subjects{where_clause}{order_clause} LIMIT %s OFFSET %s"
    params.extend([per_page, offset])

    local_results = []
    if db_pool:
        try:
            async with db_pool.acquire() as conn:
                async with conn.cursor(aiomysql.DictCursor) as cur:
                    await cur.execute(query, tuple(params))
                    rows = await cur.fetchall()
                    for row in rows:
                        local_results.append({
                            "subjectId": row["subject_id"],
                            "title": row["title"],
                            "subjectType": row["subject_type"],
                            "cover": {"url": row["cover"]},
                            "imdbRatingValue": str(row["rating"]),
                            "releaseDate": row["release_date"],
                            "countryName": row["country"],
                            "genre": row["genre"].split(",") if row["genre"] else [],
                            "isCam": row["is_cam"]
                        })
        except Exception as db_err:
            print(f"[DB Filter Error] {db_err}")

    if len(local_results) >= 5 or (page > 1 and len(local_results) > 0):
        return {
            "code": 0,
            "data": {
                "items": local_results,
                "pager": {"hasMore": len(local_results) == per_page}
            }
        }

    # Fetch from MovieBox API
    try:
        api_payload = {
            "page": page,
            "perPage": per_page,
            "genre": "" if genre == "*" else genre,
            "country": "" if country == "*" else country,
            "year": "" if year == "*" else year,
            "language": "" if language == "*" else language,
            "sort": sort,
            "subjectType": subject_type
        }
        
        data = await request_h5_api("POST", "/wefeed-h5api-bff/subject/filter", api_payload)
        items = data.get("data", {}).get("items", [])
        
        if not items and data.get("data", {}).get("results"):
            results = data.get("data", {}).get("results", [])
            if results:
                items = results[0].get("subjects", [])
                
        for item in items:
            sub_id = item.get("subjectId")
            if sub_id:
                asyncio.create_task(scrape_subject_details(sub_id))
                
        return data
    except Exception as e:
        print(f"[Filter Endpoint] Error: {e}")

    return {
        "code": 0,
        "data": {
            "items": local_results,
            "pager": {"hasMore": False}
        }
    }

# Details Endpoint
@app.get("/api/detail")
async def get_detail(subjectId: str, detailPath: str = ""):
    db_detail_path = ""
    row = None
    # Try MySQL
    if db_pool:
        try:
            async with db_pool.acquire() as conn:
                async with conn.cursor(aiomysql.DictCursor) as cur:
                    await cur.execute("SELECT * FROM subjects WHERE subject_id = %s", (subjectId,))
                    row = await cur.fetchone()
                    if row:
                        db_detail_path = row.get("detail_path") or ""
        except Exception as db_err:
            print(f"[DB Details Lookup Error] {db_err}")
            row = None
            
    if row:
        db_detail_path = row.get("detail_path") or ""
        dubs_data = []
        if row.get("dubs"):
            try:
                dubs_data = json.loads(row["dubs"])
            except Exception:
                pass
        if row["description"]:
            return {
                "code": 0,
                "data": {
                    "subjectId": row["subject_id"],
                    "title": row["title"],
                    "subjectType": row["subject_type"],
                    "cover": {"url": row["cover"]},
                    "imdbRatingValue": str(row["rating"]),
                    "releaseDate": row["release_date"],
                    "countryName": row["country"],
                    "genre": row["genre"].split(",") if row["genre"] else [],
                    "description": row["description"],
                    "isCam": row["is_cam"],
                    "dubs": dubs_data
                }
            }

    # API Fallback
    path_to_use = detailPath or db_detail_path
    if not path_to_use:
        path_to_use = f"details?subjectId={subjectId}"
        
    try:
        if "details" in path_to_use or "subjectId" in path_to_use:
            api_path = f"/wefeed-h5api-bff/detail?{path_to_use if '=' in path_to_use else f'detailPath={path_to_use}'}"
        else:
            api_path = f"/wefeed-h5api-bff/detail?detailPath={urllib.parse.quote(path_to_use)}"
            
        api_data = await request_h5_api("GET", api_path)
        subject_info = api_data.get("data", {}).get("subject", {})
        if not subject_info:
            raise Exception("Subject not found in H5 API")
            
        # Async background scrape details to DB
        asyncio.create_task(scrape_subject_details(subjectId))
        
        genres = subject_info.get("genre", "")
        genres_list = genres.split(",") if isinstance(genres, str) else (genres if isinstance(genres, list) else [])
        cover_val = subject_info.get("cover")
        cover_url = cover_val.get("url") if isinstance(cover_val, dict) else str(cover_val)
        
        duration = subject_info.get("duration", 0)
        duration_str = f"{duration // 60} min" if duration > 0 else "-- min"
        
        formatted_detail = {
            "subjectId": subject_info.get("subjectId") or subjectId,
            "title": subject_info.get("title", ""),
            "subjectType": int(subject_info.get("subjectType", 1)),
            "cover": {"url": cover_url},
            "imdbRatingValue": str(subject_info.get("imdbRatingValue") or "7.5"),
            "releaseDate": subject_info.get("releaseDate", ""),
            "countryName": subject_info.get("countryName", ""),
            "genre": genres_list,
            "description": subject_info.get("description", ""),
            "isCam": bool(subject_info.get("isCam", False)),
            "duration": duration_str,
            "dubs": subject_info.get("dubs", [])
        }
        return {"code": 0, "data": formatted_detail}
    except Exception as e:
        print(f"[Details API Fallback] Failed to fetch details for {subjectId} path {path_to_use}: {e}")
        if row:
            dubs_data = []
            if row.get("dubs"):
                try:
                    dubs_data = json.loads(row["dubs"])
                except Exception:
                    pass
            return {
                "code": 0,
                "data": {
                    "subjectId": row["subject_id"],
                    "title": row["title"],
                    "subjectType": row["subject_type"],
                    "cover": {"url": row["cover"]},
                    "imdbRatingValue": str(row["rating"]),
                    "releaseDate": row["release_date"],
                    "countryName": row["country"],
                    "genre": row["genre"].split(",") if row["genre"] else [],
                    "description": "Description temporarily unavailable.",
                    "isCam": row["is_cam"],
                    "dubs": dubs_data
                }
            }
        return {
            "code": 0,
            "data": {
                "subjectId": subjectId,
                "title": "Unknown Title",
                "subjectType": 1,
                "cover": {"url": ""},
                "imdbRatingValue": "7.5",
                "releaseDate": "2026",
                "countryName": "",
                "genre": [],
                "description": "Stream details resolved. Please use controls below to play.",
                "isCam": False
            }
        }

# TV Seasons/Episodes
@app.get("/api/season-info")
async def get_season_info(subjectId: str, detailPath: str = ""):
    # Try MySQL
    seasons = []
    async with db_pool.acquire() as conn:
        async with conn.cursor(aiomysql.DictCursor) as cur:
            await cur.execute("SELECT * FROM seasons WHERE subject_id = %s ORDER BY season_number", (subjectId,))
            rows = await cur.fetchall()
            for r in rows:
                seasons.append({
                    "se": r["season_number"],
                    "episodeCount": r["episode_count"],
                    "allEp": r["episodes_list"]
                })
                
    if seasons:
        return {"code": 0, "data": {"seasons": seasons}}

    # Try to get detail path from db
    db_detail_path = ""
    async with db_pool.acquire() as conn:
        async with conn.cursor() as cur:
            await cur.execute("SELECT detail_path FROM subjects WHERE subject_id = %s", (subjectId,))
            row = await cur.fetchone()
            if row:
                db_detail_path = row[0] or ""

    # Fallback to API
    path_to_use = detailPath or db_detail_path
    if not path_to_use:
        path_to_use = f"details?subjectId={subjectId}"
        
    try:
        if "details" in path_to_use or "subjectId" in path_to_use:
            api_path = f"/wefeed-h5api-bff/detail?{path_to_use if '=' in path_to_use else f'detailPath={path_to_use}'}"
        else:
            api_path = f"/wefeed-h5api-bff/detail?detailPath={urllib.parse.quote(path_to_use)}"
            
        api_data = await request_h5_api("GET", api_path)
        resource_obj = api_data.get("data", {}).get("resource", {})
        seasons_list = resource_obj.get("seasons", [])
        
        formatted_seasons = []
        for se in seasons_list:
            se_num = int(se.get("se", 1))
            max_ep = int(se.get("maxEp", 0))
            episodes_list = ",".join(str(i) for i in range(1, max_ep + 1))
            
            # Save to DB asynchronously
            await db_save_season(subjectId, se_num, max_ep, episodes_list)
            
            formatted_seasons.append({
                "se": se_num,
                "episodeCount": max_ep,
                "allEp": episodes_list
            })
            
        return {"code": 0, "data": {"seasons": formatted_seasons}}
    except Exception as e:
        print(f"[Season Info API Fallback] Failed to fetch seasons for {subjectId} path {path_to_use}: {e}")
        return {"code": 0, "data": {"seasons": [{"se": 1, "episodeCount": 1, "allEp": "1"}]}}

# Play Resource link (resolves and auto-renews CDN links)
@app.get("/api/resource")
async def get_resource(subjectId: str, se: int = 0, ep: int = 0):
    now = datetime.now()

    # Query MySQL for cached play resources
    async with db_pool.acquire() as conn:
        async with conn.cursor(aiomysql.DictCursor) as cur:
            await cur.execute("""
                SELECT * FROM play_resources 
                WHERE subject_id = %s AND season = %s AND episode = %s
            """, (subjectId, se, ep))
            cached_rows = await cur.fetchall()

    # Check if any cached resource is valid (expires at least 5 minutes in the future)
    valid_resources = []
    for row in cached_rows:
        if row["expires_at"] and row["expires_at"] > now + timedelta(minutes=5):
            valid_resources.append(row)

    if valid_resources:
        # Format play links list
        items = []
        for r in valid_resources:
            items.append({
                "resourceId": r["resource_id"],
                "resolution": r["resolution"],
                "size": r["size"],
                # Wrap streaming link through local streaming fetch proxy to resolve CORS and 403 Forbidden
                "resourceLink": f"{APP_URL}/fetch?source_url={urllib.parse.quote(r['resource_link'])}"
            })
        return {"code": 0, "data": {"list": items}}

    # Missing or expired: fetch fresh links from OneRoom
    try:
        detail_path = ""
        async with db_pool.acquire() as conn:
            async with conn.cursor() as cur:
                await cur.execute("SELECT detail_path FROM subjects WHERE subject_id = %s", (subjectId,))
                row = await cur.fetchone()
                if row:
                    detail_path = row[0]
                    
        if not detail_path:
            detail_path = "details"
            
        referer = f"https://123movienow.cc/spa/videoPlayPage/movies/{detail_path}?id={subjectId}&type=/movie/detail"
        origin = "https://123movienow.cc"
        
        path = f"/wefeed-h5-bff/web/subject/download?subjectId={subjectId}&se={se}&ep={ep}"
        download_data = await request_h5_api("GET", path, host="https://h5.aoneroom.com", origin=origin, referer=referer)
        
        inner_data = download_data.get("data", {})
        downloads = inner_data.get("downloads", [])
        captions = inner_data.get("captions", [])
        
        items = []
        for r in downloads:
            r_id = r.get("id")
            r_link = r.get("url")
            if not r_id or not r_link: continue
            
            exp = get_link_expiration(r_link)
            
            resource_item = {
                "resource_id": str(r_id),
                "subject_id": str(subjectId),
                "season": se,
                "episode": ep,
                "resolution": int(r.get("resolution", 0)),
                "size": int(r.get("size", 0)),
                "resource_link": r_link,
                "expires_at": exp.strftime('%Y-%m-%d %H:%M:%S')
            }
            await db_save_resource(resource_item)
            
            items.append({
                "resourceId": str(r_id),
                "resolution": int(r.get("resolution", 0)),
                "size": int(r.get("size", 0)),
                "resourceLink": f"{APP_URL}/fetch?source_url={urllib.parse.quote(r_link)}"
            })
            
        # Cache captions associated with each resource retrieved
        for cap in captions:
            for r in downloads:
                r_id = r.get("id")
                if not r_id: continue
                cap_item = {
                    "caption_id": str(cap.get("id")),
                    "subject_id": str(subjectId),
                    "resource_id": str(r_id),
                    "label": cap.get("lanName"),
                    "lang": cap.get("lan"),
                    "url": cap.get("url")
                }
                await db_save_caption(cap_item)
                
        return {"code": 0, "data": {"list": items}}
    except Exception as e:
        raise HTTPException(status_code=502, detail=str(e))

# Captions (Subtitles)
@app.get("/api/captions")
async def get_captions(subjectId: str, resourceId: str):
    # Query MySQL
    captions = []
    async with db_pool.acquire() as conn:
        async with conn.cursor(aiomysql.DictCursor) as cur:
            await cur.execute("""
                SELECT * FROM captions 
                WHERE subject_id = %s AND resource_id = %s
            """, (subjectId, resourceId))
            rows = await cur.fetchall()
            for r in rows:
                captions.append({
                    "id": r["caption_id"],
                    "lanName": r["label"],
                    "lan": r["lang"],
                    "url": r["url"]
                })

    if captions:
        return {"code": 0, "data": {"extCaptions": captions}}

    # Fallback to API
    try:
        se = 0
        ep = 0
        async with db_pool.acquire() as conn:
            async with conn.cursor() as cur:
                await cur.execute("""
                    SELECT season, episode FROM play_resources 
                    WHERE subject_id = %s AND resource_id = %s
                """, (subjectId, resourceId))
                row = await cur.fetchone()
                if row:
                    se = row[0]
                    ep = row[1]
                    
        await get_resource(subjectId, se, ep)
        
        # Query MySQL again
        captions = []
        async with db_pool.acquire() as conn:
            async with conn.cursor(aiomysql.DictCursor) as cur:
                await cur.execute("""
                    SELECT * FROM captions 
                    WHERE subject_id = %s AND resource_id = %s
                """, (subjectId, resourceId))
                rows = await cur.fetchall()
                for r in rows:
                    captions.append({
                        "id": r["caption_id"],
                        "lanName": r["label"],
                        "lan": r["lang"],
                        "url": r["url"]
                    })
        return {"code": 0, "data": {"extCaptions": captions}}
    except Exception as e:
        raise HTTPException(status_code=502, detail=str(e))

# Proxy Subtitle tracks to bypass CORS blocks
@app.get("/api/proxy-subtitle")
async def proxy_subtitle(url: str):
    if not url:
        raise HTTPException(status_code=400, detail="Missing subtitle url parameter")
    try:
        async with httpx.AsyncClient(trust_env=False) as client:
            resp = await client.get(url)
            text = resp.text
            
            # Simple conversion of SRT to VTT if necessary (Plyr prefers VTT)
            if not text.startswith("WEBVTT"):
                # Treat SRT to WebVTT simple header replacement
                text = "WEBVTT\n\n" + text
                
            return StreamingResponse(
                iter([text.encode('utf-8')]),
                media_type="text/vtt",
                headers={
                    "Access-Control-Allow-Origin": "*",
                    "Access-Control-Allow-Headers": "*",
                    "Access-Control-Allow-Methods": "GET, OPTIONS"
                }
            )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

# ==========================================================================
# 6. STREAM PROXYING & REGION LOCK BYPASS
# ==========================================================================
def get_next_worker():
    global worker_index
    worker = WORKER_PROXIES[worker_index % len(WORKER_PROXIES)]
    worker_index += 1
    return worker

def parse_range_header(range_header: str, content_length: int):
    try:
        r_str = range_header.replace("bytes=", "")
        parts = r_str.split("-")
        start = int(parts[0]) if parts[0] else 0
        if len(parts) > 1 and parts[1]:
            end = int(parts[1])
        else:
            end = content_length - 1
            if end < 0 or end - start > 50 * 1024 * 1024:
                end = start + 50 * 1024 * 1024 - 1
        return start, end
    except:
        return 0, content_length - 1 if content_length > 0 else 50 * 1024 * 1024 - 1

async def stream_chunks(resp, start=None, end=None):
    bytes_sent = 0
    chunk_size = 1048576
    if start is not None and end is not None:
        length_to_send = end - start + 1
        bytes_skipped = 0
        # Only skip bytes manually if upstream server returned 200 OK instead of 206 Partial Content
        should_skip = (resp.status_code == 200)
        async for chunk in resp.aiter_bytes(chunk_size=chunk_size):
            if should_skip and bytes_skipped < start:
                if bytes_skipped + len(chunk) <= start:
                    bytes_skipped += len(chunk)
                    continue
                else:
                    chunk = chunk[start - bytes_skipped:]
                    bytes_skipped = start
            
            if bytes_sent + len(chunk) <= length_to_send:
                yield chunk
                bytes_sent += len(chunk)
            else:
                yield chunk[:length_to_send - bytes_sent]
                bytes_sent = length_to_send
                break
    else:
        async for chunk in resp.aiter_bytes(chunk_size=chunk_size):
            yield chunk

@app.get("/fetch")
async def handle_fetch(request: Request, source_url: str):
    if not source_url:
        raise HTTPException(status_code=400, detail="Missing source_url parameter")

    # Combine with any additional query parameters passed
    query_params = dict(request.query_params)
    query_params.pop("source_url")
    if query_params:
        extra_qs = urllib.parse.urlencode(query_params)
        connector = "&" if "?" in source_url else "?"
        source_url = f"{source_url}{connector}{extra_qs}"

    range_header = request.headers.get("Range")
    if_range = request.headers.get("If-Range")

    headers_to_send = {
        "Origin": "https://fmoviesunblocked.net",
        "Referer": "https://fmoviesunblocked.net/",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept": "*/*",
    }
    if range_header:
        headers_to_send["Range"] = range_header
    if if_range:
        headers_to_send["If-Range"] = if_range

    client = httpx.AsyncClient(trust_env=False, timeout=120.0)
    
    # 1. Try direct proxying first for high-speed, direct range-request streaming
    try:
        req = client.build_request("GET", source_url, headers=headers_to_send)
        resp = await client.send(req, stream=True)
        if resp.status_code in [200, 206]:
            return build_streaming_response(resp, range_header)
        else:
            print(f"[Proxy] Direct proxy returned status {resp.status_code}. Falling back to worker proxy...")
    except Exception as e:
        print(f"[Proxy] Direct proxy failed: {e}. Falling back to worker proxy...")

    # 2. Worker proxy fallback if direct fetch fails (e.g., due to region blocks)
    try:
        worker = get_next_worker()
        proxy_url = f"{worker}/mp4-proxy?url={urllib.parse.quote(source_url)}&headers={urllib.parse.quote(json.dumps(headers_to_send))}"
        
        req = client.build_request(request.method, proxy_url)
        resp = await client.send(req, stream=True)
        if resp.status_code in [200, 206]:
            return build_streaming_response(resp, range_header)
    except Exception as e:
        print(f"[Proxy] Worker proxy failed: {e}")

    raise HTTPException(status_code=502, detail="Streaming proxy failed on all routes")

def build_streaming_response(resp, range_header):
    if range_header and resp.status_code == 200:
        content_length = int(resp.headers.get("Content-Length", 0))
        start, end = parse_range_header(range_header, content_length)
        length = end - start + 1
        
        headers = {
            "Content-Range": f"bytes {start}-{end}/{content_length or '*'}",
            "Content-Length": str(length),
            "Accept-Ranges": "bytes",
            "Access-Control-Allow-Origin": "*",
            "Access-Control-Allow-Headers": "Range, Content-Type, Authorization",
            "Access-Control-Allow-Methods": "GET, OPTIONS, HEAD",
            "Access-Control-Expose-Headers": "Content-Length, Content-Range, Accept-Ranges",
        }
        return StreamingResponse(
            stream_chunks(resp, start, end),
            status_code=206,
            headers=headers,
            media_type="video/mp4"
        )
        
    # For non-range (full) responses, only forward safe, non-duplicate headers.
    # Skip hop-by-hop and CORS/identity headers we set ourselves to avoid "*, *" duplicates.
    _skip_keys = {
        "content-encoding", "transfer-encoding", "content-length", "content-type",
        "access-control-allow-origin", "access-control-allow-headers",
        "access-control-allow-methods", "access-control-expose-headers",
        "accept-ranges", "date", "server", "connection", "alt-svc",
        "report-to", "nel", "cf-ray", "cf-cache-status",
    }
    headers = {}
    for key, val in resp.headers.items():
        if key.lower() not in _skip_keys:
            headers[key] = val

    headers["Access-Control-Allow-Origin"] = "*"
    headers["Access-Control-Allow-Headers"] = "Range, Content-Type, Authorization"
    headers["Access-Control-Allow-Methods"] = "GET, OPTIONS, HEAD"
    headers["Access-Control-Expose-Headers"] = "Content-Length, Content-Range, Accept-Ranges"
    headers["Accept-Ranges"] = "bytes"
    if "content-length" in resp.headers:
        headers["Content-Length"] = resp.headers["content-length"]
        
    return StreamingResponse(
        stream_chunks(resp),
        status_code=resp.status_code,
        headers=headers,
        media_type="video/mp4"
    )

# ==========================================================================
# 7. FRONTEND PAGE ROUTING
# ==========================================================================
@app.get("/", response_class=HTMLResponse)
async def serve_home():
    path = os.path.join(base_dir, "public/index.html")
    with open(path, "r", encoding="utf-8") as f:
        return HTMLResponse(content=f.read())

@app.get("/movies", response_class=HTMLResponse)
async def serve_movies():
    path = os.path.join(base_dir, "public/movies.html")
    with open(path, "r", encoding="utf-8") as f:
        return HTMLResponse(content=f.read())

@app.get("/tv", response_class=HTMLResponse)
async def serve_tv_shows():
    path = os.path.join(base_dir, "public/tv.html")
    with open(path, "r", encoding="utf-8") as f:
        return HTMLResponse(content=f.read())

@app.get("/watch", response_class=HTMLResponse)
async def serve_watch_page():
    path = os.path.join(base_dir, "public/watch.html")
    with open(path, "r", encoding="utf-8") as f:
        return HTMLResponse(content=f.read())

# Mount general static assets
app.mount("/", StaticFiles(directory=os.path.join(base_dir, "public")), name="public")

if __name__ == "__main__":
    uvicorn.run("main:app", host="0.0.0.0", port=3005, reload=False)
