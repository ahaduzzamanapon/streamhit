# Streamhit FastAPI Application Backend
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
from datetime import datetime, timedelta, timezone
import httpx
import pymysql
import warnings
warnings.filterwarnings("ignore", category=pymysql.Warning)
warnings.filterwarnings("ignore", message=".*already exists.*")
import aiomysql
import uvicorn
from fastapi import FastAPI, Request, HTTPException
from fastapi.responses import HTMLResponse, StreamingResponse, JSONResponse, RedirectResponse
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from contextlib import asynccontextmanager
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
APP_URL = "https://streamfit.ehealthfinder.com"
DEPLOY_SECRET = os.getenv("DEPLOY_SECRET", "streamhit_secret_update_2026")

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
db_init_locks = {}
db_migrated = False
worker_index = 0
active_api_base = "https://h5-api.aoneroom.com"
loop_clients = {}

def get_http_client() -> httpx.AsyncClient:
    current_loop = asyncio.get_running_loop()
    # clean up closed loops to prevent memory leak
    for lp in list(loop_clients.keys()):
        if lp.is_closed():
            loop_clients.pop(lp, None)
    if current_loop not in loop_clients:
        loop_clients[current_loop] = httpx.AsyncClient(trust_env=False, timeout=120.0)
    return loop_clients[current_loop]

loop_proxy_clients = {}

def get_proxy_client(proxy_url: str) -> httpx.AsyncClient:
    current_loop = asyncio.get_running_loop()
    for lp in list(loop_proxy_clients.keys()):
        if lp.is_closed():
            loop_proxy_clients.pop(lp, None)
    key = (current_loop, proxy_url)
    if key not in loop_proxy_clients:
        loop_proxy_clients[key] = httpx.AsyncClient(proxy=proxy_url, trust_env=False, timeout=30.0)
    return loop_proxy_clients[key]

@asynccontextmanager
async def lifespan(app: FastAPI):
    # ---- startup ----
    get_http_client()
    await init_db()  # wait for DB to be ready before starting scrapers
    if os.getenv("RUN_SCRAPER", "false").lower() == "true":
        asyncio.create_task(scraper_loop())
        asyncio.create_task(historical_scraper_loop())
    
    # Always run the missing resource rechecker so dead movies come back
    asyncio.create_task(missing_resource_rechecker_loop())
    
    # Start the worker proxy health check loop
    asyncio.create_task(proxy_manager.worker_health_check_loop())
    
    yield
    # ---- shutdown ----
    global db_pool
    for lp, client in list(loop_clients.items()):
        try:
            await client.aclose()
        except Exception:
            pass
    loop_clients.clear()
    if db_pool:
        try:
            db_pool.close()
            await db_pool.wait_closed()
        except Exception:
            pass

app = FastAPI(lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
    expose_headers=["Content-Length", "Content-Range"]
)


# --- Page Routing (Registered early to prevent path parameter conflicts) ---

@app.get("/debug-routes")
async def debug_routes():
    routes = []
    for route in app.routes:
        methods = None
        if hasattr(route, "methods") and route.methods is not None:
            methods = list(route.methods)
        routes.append({
            "path": getattr(route, "path", str(route)),
            "name": getattr(route, "name", str(route)),
            "methods": methods
        })
    return {"routes": routes}


@app.get("/debug-file")
async def debug_file(filename: str = "public/tv.html"):
    path = os.path.join(base_dir, filename)
    exists = os.path.exists(path)
    return {
        "filename": filename,
        "absolute_path": path,
        "exists": exists,
        "base_dir": base_dir
    }


@app.get("/movies", response_class=HTMLResponse)
async def serve_movies(request: Request):
    path = os.path.join(base_dir, "public/movies.html")
    with open(path, "r", encoding="utf-8") as f:
        html_content = f.read()
        
    meta = {
        "title": "Explore Movies - Streamfit",
        "description": "Browse the latest Hollywood, Bollywood, and international movies on Streamfit. High quality free streaming with multilingual dubs.",
        "cover": "https://images.unsplash.com/photo-1594909122845-11baa439b7bf?w=1200&q=80"
    }
    
    html_content = html_content.replace("<title>Explore Movies - Streamfit</title>", f"<title>{meta['title']}</title>")
    
    og_tags = f"""
    <meta name="description" content="{meta['description']}">
    <meta name="keywords" content="movies, tv shows, streaming, streamfit, watch free, hd movies, hindi dub, bengali dub, watch online">
    
    <!-- Open Graph / Facebook -->
    <meta property="og:type" content="website">
    <meta property="og:url" content="{str(request.url)}">
    <meta property="og:title" content="{meta['title']}">
    <meta property="og:description" content="{meta['description']}">
    <meta property="og:image" content="{meta['cover']}">

    <!-- Twitter -->
    <meta property="twitter:card" content="summary_large_image">
    <meta property="twitter:url" content="{str(request.url)}">
    <meta property="twitter:title" content="{meta['title']}">
    <meta property="twitter:description" content="{meta['description']}">
    <meta property="twitter:image" content="{meta['cover']}">
    """
    html_content = html_content.replace("</head>", f"{og_tags}\n</head>")
    return HTMLResponse(content=html_content)


@app.get("/tv", response_class=HTMLResponse)
async def serve_tv_shows(request: Request):
    try:
        path = os.path.join(base_dir, "public/tv.html")
        with open(path, "r", encoding="utf-8") as f:
            html_content = f.read()
            
        meta = {
            "title": "Explore TV Series - Streamfit",
            "description": "Watch trending television shows and web series online for free. Enjoy full seasons with multiple subtitle and language tracks.",
            "cover": "https://images.unsplash.com/photo-1594909122845-11baa439b7bf?w=1200&q=80"
        }
        
        html_content = html_content.replace("<title>Explore TV Series - Streamfit</title>", f"<title>{meta['title']}</title>")
        
        og_tags = f"""
        <meta name="description" content="{meta['description']}">
        <meta name="keywords" content="movies, tv shows, streaming, streamfit, watch free, hd movies, hindi dub, bengali dub, watch online">
        
        <!-- Open Graph / Facebook -->
        <meta property="og:type" content="website">
        <meta property="og:url" content="{str(request.url)}">
        <meta property="og:title" content="{meta['title']}">
        <meta property="og:description" content="{meta['description']}">
        <meta property="og:image" content="{meta['cover']}">

        <!-- Twitter -->
        <meta property="twitter:card" content="summary_large_image">
        <meta property="twitter:url" content="{str(request.url)}">
        <meta property="twitter:title" content="{meta['title']}">
        <meta property="twitter:description" content="{meta['description']}">
        <meta property="twitter:image" content="{meta['cover']}">
        """
        html_content = html_content.replace("</head>", f"{og_tags}\n</head>")
        return HTMLResponse(content=html_content)
    except Exception as e:
        import traceback
        return HTMLResponse(content=f"<pre>Error: {str(e)}\n{traceback.format_exc()}</pre>", status_code=500)


@app.get("/live-tv", response_class=HTMLResponse)
async def serve_live_tv(request: Request):
    path = os.path.join(base_dir, "public/live-tv.html")
    with open(path, "r", encoding="utf-8") as f:
        html_content = f.read()
        
    meta = {
        "title": "Live TV Channels - Streamfit",
        "description": "Watch your favorite live TV channels online for free in high quality. Enjoy sports, news, and entertainment streams on Streamfit.",
        "cover": "https://images.unsplash.com/photo-1598257006458-087169a1f08d?w=1200&q=80"
    }
    
    html_content = html_content.replace("<title>Live TV Channels - Streamfit</title>", f"<title>{meta['title']}</title>")
    
    og_tags = f"""
    <meta name="description" content="{meta['description']}">
    <meta name="keywords" content="live tv, channels, sports live, news live, streaming, streamfit, watch free, tv online">
    
    <!-- Open Graph / Facebook -->
    <meta property="og:type" content="website">
    <meta property="og:url" content="{str(request.url)}">
    <meta property="og:title" content="{meta['title']}">
    <meta property="og:description" content="{meta['description']}">
    <meta property="og:image" content="{meta['cover']}">

    <!-- Twitter -->
    <meta property="twitter:card" content="summary_large_image">
    <meta property="twitter:url" content="{str(request.url)}">
    <meta property="twitter:title" content="{meta['title']}">
    <meta property="twitter:description" content="{meta['description']}">
    <meta property="twitter:image" content="{meta['cover']}">
    """
    html_content = html_content.replace("</head>", f"{og_tags}\n</head>")
    return HTMLResponse(content=html_content)


@app.get("/details", response_class=HTMLResponse)
async def serve_details_page(request: Request, id: str = None, tmdb: str = None, type: str = "movie"):
    path = os.path.join(base_dir, "public/details.html")
    with open(path, "r", encoding="utf-8") as f:
        html_content = f.read()
        
    sub_type = 2 if type == "tv" else 1
    meta = await get_subject_meta(subject_id=id, tmdb_id=tmdb, subject_type=sub_type)
    
    html_content = html_content.replace("<title>Details - Streamfit</title>", f"<title>{meta['title']}</title>")
    html_content = html_content.replace('id="detailsTitle">Title', f'id="detailsTitle">{meta["title"]}')
    html_content = html_content.replace('id="watchDescription">Description loading...', f'id="watchDescription">{meta["description"]}')
    html_content = html_content.replace('src="/default-cover.png"', f'src="{meta["cover"]}"')
    
    og_tags = f"""
    <meta name="description" content="{meta['description']}">
    <meta name="keywords" content="movies, tv shows, streaming, streamfit, watch free, hd movies, hindi dub, bengali dub, watch online">
    
    <!-- Open Graph / Facebook -->
    <meta property="og:type" content="video.other">
    <meta property="og:url" content="{str(request.url)}">
    <meta property="og:title" content="{meta['title']}">
    <meta property="og:description" content="{meta['description']}">
    <meta property="og:image" content="{meta['cover']}">

    <!-- Twitter -->
    <meta property="twitter:card" content="summary_large_image">
    <meta property="twitter:url" content="{str(request.url)}">
    <meta property="twitter:title" content="{meta['title']}">
    <meta property="twitter:description" content="{meta['description']}">
    <meta property="twitter:image" content="{meta['cover']}">
    """
    html_content = html_content.replace("</head>", f"{og_tags}\n</head>")
    return HTMLResponse(content=html_content)


@app.get("/watch", response_class=HTMLResponse)
async def serve_watch_page(request: Request, id: str = None, tmdb: str = None, type: str = "movie"):
    # Server-side crawler bot detection and block
    user_agent = request.headers.get("user-agent", "").lower()
    bot_keywords = [
        "googlebot", "bingbot", "yandexbot", "baiduspider", "duckduckbot", "exabot", "sogou", 
        "facebot", "facebookexternalhit", "ia_archiver", "twitterbot", "discordbot", "telegrambot", 
        "slackbot", "bot", "crawler", "spider", "crawl", "slurp", "screaming frog"
    ]
    if any(keyword in user_agent for keyword in bot_keywords):
        print(f"[Bot Blocked] IP: {request.client.host if request.client else 'Unknown'} | User-Agent: {user_agent}")
        raise HTTPException(status_code=403, detail="Forbidden")

    path = os.path.join(base_dir, "public/watch.html")
    with open(path, "r", encoding="utf-8") as f:
        html_content = f.read()
        
    sub_type = 2 if type == "tv" else 1
    meta = await get_subject_meta(subject_id=id, tmdb_id=tmdb, subject_type=sub_type)
    
    html_content = html_content.replace("<title>Watch Online - Streamfit</title>", f"<title>{meta['title']}</title>")
    
    og_tags = f"""
    <meta name="robots" content="noindex, nofollow, noarchive, nosnippet">
    <meta name="description" content="{meta['description']}">
    <meta name="keywords" content="movies, tv shows, streaming, streamfit, watch free, hd movies, hindi dub, bengali dub, watch online">
    
    <!-- Open Graph / Facebook -->
    <meta property="og:type" content="video.other">
    <meta property="og:url" content="{str(request.url)}">
    <meta property="og:title" content="{meta['title']}">
    <meta property="og:description" content="{meta['description']}">
    <meta property="og:image" content="{meta['cover']}">

    <!-- Twitter -->
    <meta property="twitter:card" content="summary_large_image">
    <meta property="twitter:url" content="{str(request.url)}">
    <meta property="twitter:title" content="{meta['title']}">
    <meta property="twitter:description" content="{meta['description']}">
    <meta property="twitter:image" content="{meta['cover']}">
    """
    html_content = html_content.replace("</head>", f"{og_tags}\n</head>")
    html_content = html_content.replace("</head>", f"{og_tags}\n</head>")
    return HTMLResponse(content=html_content)


@app.get("/download", response_class=HTMLResponse)
async def serve_download_page(request: Request):
    # Server-side crawler bot detection and block
    user_agent = request.headers.get("user-agent", "").lower()
    bot_keywords = [
        "googlebot", "bingbot", "yandexbot", "baiduspider", "duckduckbot", "exabot", "sogou", 
        "facebot", "facebookexternalhit", "ia_archiver", "twitterbot", "discordbot", "telegrambot", 
        "slackbot", "bot", "crawler", "spider", "crawl", "slurp", "screaming frog", "python", "http-client", "curl", "wget"
    ]
    if any(keyword in user_agent for keyword in bot_keywords):
        print(f"[Bot Blocked on Download] IP: {request.client.host if request.client else 'Unknown'} | User-Agent: {user_agent}")
        raise HTTPException(status_code=403, detail="Forbidden")

    path = os.path.join(base_dir, "public/download.html")
    if not os.path.exists(path):
        raise HTTPException(status_code=404, detail="Page not found")
        
    with open(path, "r", encoding="utf-8") as f:
        html_content = f.read()
        
    return HTMLResponse(content=html_content)


@app.get("/admin", response_class=HTMLResponse)
async def serve_admin(request: Request):
    path = os.path.join(base_dir, "public/admin.html")
    if not os.path.exists(path):
        raise HTTPException(status_code=404, detail="Admin page not found")
    with open(path, "r", encoding="utf-8") as f:
        html_content = f.read()
    return HTMLResponse(content=html_content)


@app.get("/logs", response_class=HTMLResponse)
async def serve_logs(request: Request):
    path = os.path.join(base_dir, "public/logs.html")
    if not os.path.exists(path):
        raise HTTPException(status_code=404, detail="Logs page not found")
    with open(path, "r", encoding="utf-8") as f:
        html_content = f.read()
    return HTMLResponse(content=html_content)


# ---------------------------------------------------------------------------


async def get_db_pool():
    global db_pool
    current_loop = asyncio.get_running_loop()
    
    # clean up closed loops to prevent memory leak
    for lp in list(db_init_locks.keys()):
        if lp.is_closed():
            db_init_locks.pop(lp, None)
            
    if db_pool is not None:
        pool_loop = getattr(db_pool, "_loop", None)
        if pool_loop is None or pool_loop.is_closed() or pool_loop is not current_loop:
            try:
                db_pool.close()
            except Exception:
                pass
            db_pool = None

    if db_pool is None:
        if current_loop not in db_init_locks:
            db_init_locks[current_loop] = asyncio.Lock()
        async with db_init_locks[current_loop]:
            if db_pool is None:
                await init_db()
    return db_pool

# ==========================================================================
# 2. DATABASE HELPER
# ==========================================================================
async def init_db():
    global db_pool, db_migrated
    try:
        # Connect to MySQL first without specifying the DB to verify/create it
        try:
            temp_conn = await aiomysql.connect(
                host=DB_HOST,
                port=DB_PORT,
                user=DB_USER,
                password=DB_PASSWORD,
                autocommit=True
            )
            async with temp_conn.cursor() as temp_cur:
                await temp_cur.execute(f"CREATE DATABASE IF NOT EXISTS {DB_NAME} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
            temp_conn.close()
            print(f"[Database] Verified or created database: {DB_NAME}")
        except Exception as db_create_err:
            print(f"[Database] Pre-init database check/creation failed: {db_create_err}")

        db_pool = await aiomysql.create_pool(
            host=DB_HOST,
            port=DB_PORT,
            user=DB_USER,
            password=DB_PASSWORD,
            db=DB_NAME,
            minsize=2,
            maxsize=10,
            autocommit=True,
            connect_timeout=5,
            init_command="SET time_zone='+00:00'"
        )
        
        if db_migrated:
            return
            
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
                        has_resource BOOLEAN DEFAULT TRUE,
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
                try:
                    await cur.execute("ALTER TABLE subjects ADD COLUMN has_resource BOOLEAN DEFAULT TRUE")
                    print("[Database] Successfully added has_resource column to subjects table.")
                except Exception:
                    pass
                
                # Auto-heal AUTO_INCREMENT on seasons and play_resources tables if missing
                try:
                    await cur.execute("ALTER TABLE seasons MODIFY COLUMN id INT AUTO_INCREMENT")
                    print("[Database] Verified/Applied AUTO_INCREMENT on seasons table.")
                except Exception as e:
                    print(f"[Database Schema Migration Warning] Failed to alter seasons table: {e}")
                
                try:
                    await cur.execute("ALTER TABLE play_resources MODIFY COLUMN id INT AUTO_INCREMENT")
                    print("[Database] Verified/Applied AUTO_INCREMENT on play_resources table.")
                except Exception as e:
                    print(f"[Database Schema Migration Warning] Failed to alter play_resources table: {e}")
                
                # Database index migrations for performance optimization
                try:
                    await cur.execute("ALTER TABLE subjects ADD INDEX idx_subject_type (subject_type)")
                except Exception:
                    pass
                try:
                    await cur.execute("ALTER TABLE subjects ADD INDEX idx_release_date (release_date)")
                except Exception:
                    pass
                try:
                    await cur.execute("ALTER TABLE subjects ADD INDEX idx_rating (rating)")
                except Exception:
                    pass
                try:
                    await cur.execute("ALTER TABLE subjects ADD INDEX idx_created_at (created_at)")
                except Exception:
                    pass
                try:
                    await cur.execute("ALTER TABLE subjects ADD INDEX idx_updated_at (updated_at)")
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
                # 6. Scraper Progress table
                await cur.execute("""
                    CREATE TABLE IF NOT EXISTS scraper_progress (
                        subject_type INT PRIMARY KEY,
                        current_page INT NOT NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """)
                # 7. API Cache table
                await cur.execute("""
                    CREATE TABLE IF NOT EXISTS api_cache (
                        cache_key VARCHAR(255) PRIMARY KEY,
                        response_data LONGTEXT NOT NULL,
                        expires_at TIMESTAMP NOT NULL,
                        INDEX idx_expires_at (expires_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """)
                try:
                    await cur.execute("DELETE FROM api_cache WHERE expires_at < NOW()")
                    affected = cur.rowcount
                    if affected > 0:
                        print(f"[Database Cleanup] Successfully deleted {affected} expired cache entries.")
                except Exception as cleanup_err:
                    print(f"[Database Cleanup] Error deleting expired cache entries: {cleanup_err}")

                # One-time migration to clear play_resources timezone cache
                migration_flag = os.path.join(base_dir, ".db_timezone_migration_done")
                if not os.path.exists(migration_flag):
                    try:
                        await cur.execute("DELETE FROM play_resources")
                        print("[Database] Successfully cleared play_resources table for timezone migration.")
                        with open(migration_flag, "w") as f:
                            f.write("done")
                    except Exception as migration_err:
                        print(f"[Database] Error during timezone migration: {migration_err}")

                # Cleanup educational / classroom items and blocked genres from subjects table automatically
                try:
                    await cur.execute("""
                        DELETE FROM subjects 
                        WHERE title REGEXP 'class[[:space:]]*([0-9]|one|two|three|four|five|six|seven|eight|nine|ten)'
                           OR title LIKE '%english 1st paper%'
                           OR title LIKE '%english 2nd paper%'
                           OR title LIKE '%ssc 20%'
                           OR title LIKE '%hsc 20%'
                           OR title LIKE '%jsc 20%'
                           OR title LIKE '%nctb%'
                           OR title LIKE '%শ্রেণি%'
                           OR title LIKE '%শ্রেণী%'
                           OR title LIKE '%অধ্যায়%'
                           OR title LIKE '%শিক্ষা%'
                           OR title LIKE '%গণিত%'
                           OR title LIKE '%বিজ্ঞান%'
                           OR title LIKE '%ব্যাকরণ%'
                           OR genre LIKE '%Basketball%'
                           OR genre LIKE '%Football%'
                           OR genre LIKE '%Mobile Game%'
                           OR genre LIKE '%PC Game%'
                           OR genre LIKE '%Reality%'
                           OR genre LIKE '%Wrestling%'
                           OR genre LIKE '%Yoruba%'
                           OR genre LIKE '%Gameplay%'
                           OR genre LIKE '%Volleyball%'
                    """)
                    affected = cur.rowcount
                    if affected > 0:
                        print(f"[Database Cleanup] Successfully deleted {affected} educational/blocked-genre subjects from database.")
                except Exception as cleanup_err:
                    print(f"[Database Cleanup] Error deleting educational/blocked subjects: {cleanup_err}")

                # 8. Admins table
                await cur.execute("""
                    CREATE TABLE IF NOT EXISTS admins (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        username VARCHAR(50) UNIQUE NOT NULL,
                        password_hash VARCHAR(255) NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """)
                
                # Seed default admin if table is empty
                await cur.execute("SELECT COUNT(*) FROM admins")
                admin_count = (await cur.fetchone())[0]
                if admin_count == 0:
                    default_user = "admin"
                    default_pass = "admin123"
                    h = hashlib.sha256((default_pass + "streamfit_secure_salt_2026").encode('utf-8')).hexdigest()
                    await cur.execute("INSERT INTO admins (username, password_hash) VALUES (%s, %s)", (default_user, h))
                    print("[Database] Seeded default admin account: admin / admin123")

                # 9. App Versions table
                await cur.execute("""
                    CREATE TABLE IF NOT EXISTS app_versions (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        version_code INT UNIQUE NOT NULL,
                        version_name VARCHAR(50) NOT NULL,
                        apk_url VARCHAR(255) NOT NULL,
                        must_update BOOLEAN DEFAULT FALSE,
                        release_notes TEXT,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """)

                # 10. Notifications table
                await cur.execute("""
                    CREATE TABLE IF NOT EXISTS notifications (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        title VARCHAR(255) NOT NULL,
                        message TEXT NOT NULL,
                        subject_id VARCHAR(50) DEFAULT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """)

                # 11. Live Sports table
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
                        referer VARCHAR(255),
                        origin VARCHAR(255),
                        use_bd_proxy BOOLEAN DEFAULT TRUE,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """)

                # 12. Live TV Channels table
                await cur.execute("""
                    CREATE TABLE IF NOT EXISTS live_tv_channels (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        name VARCHAR(255) NOT NULL,
                        logo TEXT,
                        category VARCHAR(100) DEFAULT 'General',
                        stream_links TEXT NOT NULL,
                        referer VARCHAR(255),
                        origin VARCHAR(255),
                        use_bd_proxy BOOLEAN DEFAULT TRUE,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """)

                db_migrated = True
                print("[Database] MySQL Tables check/creation complete.")
    except Exception as e:
        print(f"[Database] Error initializing database: {e}")

# Database operations
async def db_save_banner(b: dict):
    pool = await get_db_pool()
    if not pool: return
    async with pool.acquire() as conn:
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
    pool = await get_db_pool()
    if not pool: return []
    async with pool.acquire() as conn:
        async with conn.cursor(aiomysql.DictCursor) as cur:
            # Query custom banners explicitly added by admin first
            await cur.execute("""
                SELECT b.* FROM banners b
                LEFT JOIN subjects s ON b.subject_id = s.subject_id
                WHERE s.has_resource IS NULL OR s.has_resource = TRUE
                ORDER BY b.created_at DESC 
                LIMIT 20
            """)
            rows = await cur.fetchall()
            if rows:
                return rows
            
            # Fallback to latest created/updated subjects if banners table is empty
            await cur.execute("""
                SELECT subject_id, title, cover as image_url, detail_path, subject_type 
                FROM subjects 
                WHERE cover IS NOT NULL AND cover != '' AND title != 'Placeholder' AND title != ''
                  AND has_resource = TRUE
                ORDER BY updated_at DESC, created_at DESC 
                LIMIT 10
            """)
            return await cur.fetchall()

async def db_save_subject(s: dict):
    if is_future_subject(s):
        return
    pool = await get_db_pool()
    if not pool: return
    dubs_json = json.dumps(s.get("dubs")) if s.get("dubs") is not None else None
    async with pool.acquire() as conn:
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
    pool = await get_db_pool()
    if not pool: return
    async with pool.acquire() as conn:
        async with conn.cursor() as cur:
            # Insert placeholder subject to avoid foreign key failure
            await cur.execute("""
                INSERT IGNORE INTO subjects (subject_id, title, subject_type, release_date)
                VALUES (%s, %s, %s, %s)
            """, (str(subject_id), "Placeholder", 2, "2026"))
            
            await cur.execute("""
                INSERT INTO seasons (subject_id, season_number, episode_count, episodes_list)
                VALUES (%s, %s, %s, %s)
                ON DUPLICATE KEY UPDATE
                    episode_count=VALUES(episode_count),
                    episodes_list=VALUES(episodes_list)
            """, (str(subject_id), int(season_number), int(episode_count), episodes_list))

async def db_save_resource(r: dict):
    pool = await get_db_pool()
    if not pool: return
    subject_id = str(r["subject_id"])
    season = int(r.get("season", 0))
    episode = int(r.get("episode", 0))
    guess_type = 2 if (season > 0 or episode > 0) else 1
    
    async with pool.acquire() as conn:
        async with conn.cursor() as cur:
            # Insert placeholder subject to avoid foreign key failure
            await cur.execute("""
                INSERT IGNORE INTO subjects (subject_id, title, subject_type, release_date)
                VALUES (%s, %s, %s, %s)
            """, (subject_id, "Placeholder", guess_type, "2026"))
            
            await cur.execute("""
                INSERT INTO play_resources (resource_id, subject_id, season, episode, resolution, size, resource_link, expires_at)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                ON DUPLICATE KEY UPDATE
                    resource_id=VALUES(resource_id),
                    resource_link=VALUES(resource_link),
                    expires_at=VALUES(expires_at),
                    size=VALUES(size)
            """, (
                str(r["resource_id"]), subject_id, season, episode,
                int(r["resolution"]), int(r.get("size", 0)), r["resource_link"], r.get("expires_at")
            ))

async def db_save_caption(c: dict):
    pool = await get_db_pool()
    if not pool: return
    subject_id = str(c["subject_id"])
    try:
        async with pool.acquire() as conn:
            async with conn.cursor() as cur:
                # Insert placeholder subject to avoid foreign key failure
                await cur.execute("""
                    INSERT IGNORE INTO subjects (subject_id, title, subject_type, release_date)
                    VALUES (%s, %s, %s, %s)
                """, (subject_id, "Placeholder", 1, "2026"))
                
                await cur.execute("""
                    INSERT INTO captions (caption_id, subject_id, resource_id, label, lang, url)
                    VALUES (%s, %s, %s, %s, %s, %s)
                    ON DUPLICATE KEY UPDATE
                        caption_id = VALUES(caption_id),
                        label = VALUES(label),
                        url = VALUES(url)
                """, (c["caption_id"], subject_id, c["resource_id"], c["label"], c["lang"], c["url"]))
    except Exception as e:
        print(f"[Database] Error saving caption: {e}")

async def db_read_scraper_progress() -> dict:
    progress = {"1": 2, "2": 2, "7": 2}
    pool = await get_db_pool()
    if not pool:
        return progress
    try:
        async with pool.acquire() as conn:
            async with conn.cursor(aiomysql.DictCursor) as cur:
                await cur.execute("SELECT * FROM scraper_progress")
                rows = await cur.fetchall()
                for r in rows:
                    progress[str(r["subject_type"])] = r["current_page"]
    except Exception as e:
        print(f"[Database] Error reading scraper progress: {e}")
    return progress

async def db_save_scraper_progress(subject_type: int, current_page: int):
    pool = await get_db_pool()
    if not pool:
        return
    try:
        async with pool.acquire() as conn:
            async with conn.cursor() as cur:
                await cur.execute("""
                    INSERT INTO scraper_progress (subject_type, current_page)
                    VALUES (%s, %s)
                    ON DUPLICATE KEY UPDATE current_page = VALUES(current_page)
                """, (int(subject_type), int(current_page)))
    except Exception as e:
        print(f"[Database] Error saving scraper progress: {e}")

# Genres that should never be scraped or shown
BLOCKED_GENRES = {
    "basketball", "football", "mobile game", "pc game",
    "reality", "wrestling", "yoruba", "gameplay", "volleyball"
}


def is_educational_content(title: str, genre: str = "") -> bool:
    if not title:
        return False
    title_lower = title.lower()

    # Block unwanted genres
    if genre:
        genre_lower = genre.lower()
        for blocked in BLOCKED_GENRES:
            if blocked in genre_lower:
                return True

    # Check for Bangla class keywords
    bangla_keywords = ["শ্রেণি", "শ্রেণী", "অধ্যায়", "পাঠ্য", "শিক্ষা", "গণিত", "বিজ্ঞান", "ব্যাকরণ"]
    for kw in bangla_keywords:
        if kw in title_lower:
            return True

    # Regex for "Class X" where X is a digit or word representation
    if re.search(r'\bclass\s*(\d+|one|two|three|four|five|six|seven|eight|nine|ten)\b', title_lower):
        return True

    # Other educational / exam keywords
    edu_keywords = ["english 1st paper", "english 2nd paper", "ssc 20", "hsc 20", "jsc 20", "nctb"]
    for kw in edu_keywords:
        if kw in title_lower:
            return True

    return False


def is_future_subject(sub: dict) -> bool:
    if not sub:
        return False
    release_date = sub.get("releaseDate") or sub.get("release_date") or ""
    if not release_date:
        return False
    try:
        import re
        match = re.search(r'\b(20\d{2})\b', str(release_date))
        if match:
            year = int(match.group(1))
            if year > 2026:
                return True
    except Exception:
        pass
    return False


async def cache_item_metadata_only(item: dict):
    try:
        sub_id = item.get("subjectId")
        if not sub_id or str(sub_id) == "0":
            return
        genres = item.get("genre", [])
        genres_str = ",".join(genres) if isinstance(genres, list) else str(genres or "")
        cover_val = item.get("cover")
        cover_url = cover_val.get("url") if isinstance(cover_val, dict) else str(cover_val or "")
        
        s_data = {
            "subject_id": str(sub_id),
            "title": item.get("title", ""),
            "subject_type": int(item.get("subjectType", 1)),
            "cover": cover_url,
            "backdrop": cover_url,
            "rating": float(item.get("imdbRatingValue") or 0.0),
            "release_date": item.get("releaseDate", ""),
            "country": item.get("countryName", "") or item.get("country", ""),
            "genre": genres_str,
            "description": "",
            "is_cam": bool(item.get("isCam", False)),
            "detail_path": item.get("detailPath"),
            "dubs": None
        }
        await db_save_subject(s_data)
    except Exception as e:
        print(f"[Metadata Cache Error] {e}")

# ==========================================================================
# 3. PROXY & H5 API CLIENT (MULTI-PROXY ROTATION)
# ==========================================================================

# Spoofed IP ranges for South Asian regional content (Bangladesh and India)
SINGAPORE_IP_RANGES = [
    ((103, 108, 140, 0), (103, 108, 143, 255)),
    ((103, 242, 21, 0), (103, 242, 21, 255)),
    ((103, 95, 96, 0), (103, 95, 99, 255)),
    ((45, 127, 244, 0), (45, 127, 247, 255)),
    ((103, 15, 100, 0), (103, 15, 103, 255)),
    ((103, 20, 100, 0), (103, 20, 103, 255)),
    ((103, 24, 100, 0), (103, 24, 103, 255)),
    ((103, 28, 100, 0), (103, 28, 103, 255)),
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

# In-Memory Cache for API responses
api_cache = {}  # key -> (expiry_timestamp, response_data)
API_CACHE_TTL = 600.0  # 10 minutes cache TTL

def get_cached_response(key: str) -> dict:
    now = time.time()
    if key in api_cache:
        expiry, data = api_cache[key]
        if now < expiry:
            return data
        else:
            del api_cache[key]
    return None

def set_cached_response(key: str, data: any, ttl: float = API_CACHE_TTL):
    api_cache[key] = (time.time() + ttl, data)

# Persistent Direct Connection Failure Tracker
LAST_FAIL_FILE = os.path.join(base_dir, ".last_direct_fail_time")

def get_last_direct_fail_time() -> float:
    try:
        if os.path.exists(LAST_FAIL_FILE):
            with open(LAST_FAIL_FILE, "r") as f:
                return float(f.read().strip())
    except Exception:
        pass
    return 0.0

def set_last_direct_fail_time(t: float):
    try:
        with open(LAST_FAIL_FILE, "w") as f:
            f.write(str(t))
    except Exception:
        pass

class ProxyManager:
    def __init__(self):
        self.workers = list(WORKER_PROXIES)
        self.active_workers = []
        self.worker_index = 0
        self.external_proxies = []
        self.proxy_index = 0
        self.load_external_workers()
        self.load_external_proxies()

    def load_external_workers(self):
        worker_file = os.path.join(base_dir, "workers.txt")
        if os.path.exists(worker_file):
            try:
                with open(worker_file, "r") as f:
                    file_workers = [line.strip() for line in f if line.strip() and not line.startswith("#")]
                if file_workers:
                    self.workers = file_workers
                    print(f"[ProxyManager] Loaded {len(self.workers)} workers from workers.txt")
                else:
                    self.workers = list(WORKER_PROXIES)
            except Exception as e:
                print(f"[ProxyManager] Error loading workers.txt: {e}")
        else:
            self.workers = list(WORKER_PROXIES)

    def load_external_proxies(self):
        proxy_file = os.path.join(base_dir, "proxies.txt")
        if os.path.exists(proxy_file):
            try:
                with open(proxy_file, "r") as f:
                    self.external_proxies = [line.strip() for line in f if line.strip() and not line.startswith("#")]
                print(f"[ProxyManager] Loaded {len(self.external_proxies)} external proxies from proxies.txt")
            except Exception as e:
                print(f"[ProxyManager] Error loading proxies.txt: {e}")
        else:
            # Default fallback list if file doesn't exist yet
            self.external_proxies = ["194.127.178.223"]

    def get_next_active_worker(self):
        if not self.active_workers:
            return None
        worker = self.active_workers[self.worker_index % len(self.active_workers)]
        self.worker_index += 1
        return worker

    def get_next_worker(self):
        list_to_use = self.active_workers if self.active_workers else self.workers
        if not list_to_use:
            return None
        worker = list_to_use[self.worker_index % len(list_to_use)]
        self.worker_index += 1
        return worker

    def get_next_proxy(self):
        if not self.external_proxies:
            return "194.127.178.223"
        proxy = self.external_proxies[self.proxy_index % len(self.external_proxies)]
        self.proxy_index += 1
        return proxy

    async def worker_health_check_loop(self):
        print("[ProxyManager] Starting worker health check loop...")
        while True:
            # Load from workers.txt dynamically if updated
            self.load_external_workers()
            
            valid_workers = []
            for w in self.workers:
                try:
                    # Perform simple HTTP GET check on worker endpoint
                    client = get_http_client()
                    resp = await client.get(f"{w}/mp4-proxy?url=https%3A//google.com", timeout=5.0)
                    # 404 indicates the worker script path is not found/deleted.
                    # 200, 206, or 403 (CF challenge/Forbidden) indicate the worker exists on Cloudflare.
                    if resp.status_code != 404:
                        valid_workers.append(w)
                    else:
                        print(f"[ProxyManager Health Check] Worker {w} returned 404. Deactivated.")
                except Exception as e:
                    print(f"[ProxyManager Health Check] Worker {w} failed check: {e}. Deactivated.")
            
            self.active_workers = valid_workers
            print(f"[ProxyManager Health Check] Active workers updated: {len(self.active_workers)}/{len(self.workers)}")
            
            # Run check every 10 minutes
            await asyncio.sleep(600)

proxy_manager = ProxyManager()

global_cookies = ""
cookies_expiry = 0.0
guest_bearer_token = ""
guest_token_expiry = 0.0

async def refresh_cookies_if_needed() -> str:
    global global_cookies, cookies_expiry
    now = time.time()
    if global_cookies and now < cookies_expiry:
        return global_cookies

    ip = get_random_singapore_ip()
    print(f"[API Network] Using spoofed IP: {ip}")
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

    last_direct_fail_time = get_last_direct_fail_time()
    skip_direct = (now - last_direct_fail_time < 30.0)
    cookie_url = "https://h5.aoneroom.com/wefeed-h5-bff/app/get-latest-app-pkgs?app_name=moviebox"

    # Try Direct -> Workers -> Random Proxy
    attempts = ["direct", "worker", "proxy"]
    if skip_direct: attempts.remove("direct")

    for mode in attempts:
        try:
            async with httpx.AsyncClient(trust_env=False, timeout=6.0) as client:
                if mode == "direct":
                    resp = await client.get(cookie_url, headers=headers)
                elif mode == "worker":
                    worker = proxy_manager.get_next_worker()
                    proxied_url = f"{worker}/mp4-proxy?url={urllib.parse.quote(cookie_url)}"
                    resp = await client.get(proxied_url, headers=headers)
                else:
                    proxy_ip = proxy_manager.get_next_proxy()
                    proxied_url = f"http://{proxy_ip}/?url={urllib.parse.quote(cookie_url)}"
                    resp = await client.get(proxied_url, headers=headers)

                if resp.status_code == 200:
                    cookie_headers = resp.headers.get_list("Set-Cookie")
                    parsed_cookies = []
                    for cookie in cookie_headers:
                        part = cookie.split(";")[0]
                        parsed_cookies.append(part)
                    global_cookies = "; ".join(parsed_cookies)
                    cookies_expiry = now + 3600.0
                    print(f"[API Auth] Refreshed H5 cookies successfully via {mode}.")
                    return global_cookies
        except Exception as e:
            if mode == "direct":
                print(f"[API Auth] Direct cookie refresh failed. Marking direct API as failed.")
                set_last_direct_fail_time(time.time())
            else:
                print(f"[API Auth] Cookie refresh failed via {mode}: {e}")

    if global_cookies: return global_cookies
    raise Exception("Failed to acquire OneRoom H5 cookies after multiple attempts")

async def get_guest_bearer_token() -> str:
    global guest_bearer_token, guest_token_expiry
    now = time.time()
    if guest_bearer_token and now < guest_token_expiry:
        return guest_bearer_token

    url = "https://h5-api.aoneroom.com/wefeed-h5api-bff/home?host=moviebox.ph"
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
        "Referer": "https://moviebox.ph/",
        "Origin": "https://moviebox.ph",
        "X-Client-Info": json.dumps({"timezone": "Asia/Dhaka"}),
        "X-Source": "",
        "sec-ch-ua": '"Chromium";v="148", "Google Chrome";v="148", "Not/A)Brand";v="99"',
        "sec-ch-ua-mobile": "?0",
        "sec-ch-ua-platform": '"Windows"',
        "sec-fetch-dest": "empty",
        "sec-fetch-mode": "cors",
        "sec-fetch-site": "same-origin",
    }

    try:
        async with httpx.AsyncClient(follow_redirects=True, timeout=15.0) as client:
            resp = await client.get(url, headers=headers)
            x_user = resp.headers.get("x-user")
            token = None
            if x_user:
                try:
                    token = json.loads(x_user).get("token")
                except Exception:
                    pass
            if not token:
                cookie = resp.headers.get("set-cookie", "")
                import re as _re
                m = _re.search(r"token=([^;]+)", cookie)
                if m:
                    token = m.group(1)
            
            if token:
                guest_bearer_token = token
                guest_token_expiry = now + 3600.0  # cache for 1 hour
                print(f"[API Auth] Acquired guest Bearer token successfully.")
                return guest_bearer_token
    except Exception as e:
        print(f"[API Auth] Failed to acquire guest Bearer token: {e}")

    if guest_bearer_token:
        return guest_bearer_token
    return ""

async def request_h5_api(method: str, path: str, body_dict: dict = None, host: str = "https://h5-api.aoneroom.com", origin: str = None, referer: str = None) -> dict:
    token = await get_guest_bearer_token()
    ip = get_random_singapore_ip()
    print(f"[API Network] Using spoofed IP: {ip}")
    
    headers = {
        "X-Forwarded-For": ip,
        "CF-Connecting-IP": ip,
        "X-Real-IP": ip,
        "X-Client-Info": json.dumps({"timezone": "Asia/Dhaka"}),
        "Accept-Language": "en-US,en;q=0.5",
        "Accept": "application/json",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
        "Referer": referer or "https://moviebox.ph/",
        "Origin": origin or "https://moviebox.ph",
        "Authorization": f"Bearer {token}" if token else ""
    }

    url = f"{host}{path}"
    now = time.time()
    last_direct_fail_time = get_last_direct_fail_time()
    skip_direct = (now - last_direct_fail_time < 30.0)

    # Rotation Strategy: Direct -> Worker -> External Proxy
    attempts = ["direct", "worker", "proxy"]
    if skip_direct: attempts.remove("direct")

    # Workers (Cloudflare) return 403 for POST requests (filter/search endpoints).
    # Only use workers for GET requests.
    if method.upper() != "GET" and "worker" in attempts:
        attempts.remove("worker")

    print(f"[API Network] Rotation attempts for {path}: {attempts}")

    last_status_code = None
    for mode in attempts:
        try:
            async with httpx.AsyncClient(trust_env=False, timeout=5.0) as client:
                if mode == "direct":
                    if method.upper() == "GET":
                        resp = await client.get(url, headers=headers)
                    else:
                        resp = await client.post(url, json=body_dict, headers=headers)
                elif mode == "worker":
                    worker = proxy_manager.get_next_worker()
                    worker_headers = {**headers}
                    if method.upper() == "GET":
                        # Pass headers via query string so the worker injects them
                        proxied_url = f"{worker}/mp4-proxy?url={urllib.parse.quote(url)}&headers={urllib.parse.quote(json.dumps(worker_headers))}"
                        resp = await client.get(proxied_url)
                    else:
                        # Workers support POST too
                        proxied_url = f"{worker}/mp4-proxy?url={urllib.parse.quote(url)}"
                        resp = await client.post(proxied_url, json=body_dict, headers=worker_headers)
                else:
                    proxy_ip = proxy_manager.get_next_proxy()
                    proxied_url = f"http://{proxy_ip}/?url={urllib.parse.quote(url)}"
                    if method.upper() == "GET":
                        resp = await client.get(proxied_url, headers=headers)
                    else:
                        resp = await client.post(proxied_url, json=body_dict, headers=headers)

                last_status_code = resp.status_code
                if resp.status_code == 200:
                    data = resp.json()
                    if data.get("code") == 0 or "data" in data:
                        return data
                    else:
                        raise Exception(f"API Error {data.get('code')}: {data.get('message')}")
                else:
                    raise Exception(f"HTTP Status {resp.status_code}")

        except Exception as e:
            print(f"[API Warning] Request via {mode} failed for {path}: {e}")
            if mode == "direct": set_last_direct_fail_time(time.time())

    # Include last HTTP status in exception so callers can detect 400 (out-of-bounds), 403 etc.
    status_hint = f" [HTTP {last_status_code}]" if last_status_code else ""
    raise Exception(f"Failed to request {path} after all proxy attempts{status_hint}")


# Extract CDN expiration
def get_link_expiration(url: str) -> datetime:
    # Check for t (generation time, valid for 1 hour)
    match_t = re.search(r'[?&]t=(\d+)', url)
    if match_t:
        timestamp = int(match_t.group(1))
        if timestamp > 9999999999:  # Milliseconds
            timestamp = timestamp // 1000
        # Add 1 hour (3600 seconds) for expiration
        timestamp += 3600
        return datetime.fromtimestamp(timestamp, tz=timezone.utc).replace(tzinfo=None)
        
    # Check for expires or exp (actual expiration time)
    match_exp = re.search(r'[?&](expires|exp)=(\d+)', url)
    if match_exp:
        timestamp = int(match_exp.group(2))
        if timestamp > 9999999999:  # Milliseconds
            timestamp = timestamp // 1000
        return datetime.fromtimestamp(timestamp, tz=timezone.utc).replace(tzinfo=None)
        
    return datetime.now(timezone.utc).replace(tzinfo=None) + timedelta(hours=2)

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

async def scrape_subject_details(subject_id: str, only_se: int = -1, only_ep: int = -1) -> dict:
    # Retry logic with proxy rotation
    attempts = [
        {"name": "Direct", "use_worker": False, "use_proxy": False},
        {"name": "Worker", "use_worker": True, "use_proxy": False},
        {"name": "Singapore Proxy", "use_worker": False, "use_proxy": True}
    ]
    
    last_error = ""
    for attempt in attempts:
        try:
            # We modify request_h5_api behavior via a temporary context or parameters if needed,
            # but for simplicity, we'll implement the logic here to ensure it uses the specific path.
            
            # Since request_h5_api already has internal fallback, we will call it and 
            # only if IT fails (after its internal fallbacks), we log and try a harder retry.
            
            data = await request_h5_api("GET", f"/wefeed-h5api-bff/detail?subjectId={subject_id}")
            detail = data.get("data", {}).get("subject", {})
            if not detail: 
                last_error = "No subject data in response"
                continue

            genres = detail.get("genre", [])
            genres_str = ",".join(genres) if isinstance(genres, list) else str(genres)
            
            # Disable educational/genre filtering to get "all data"
            if is_educational_content(detail.get("title", ""), genres_str):
                return {}
                
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
            print(f"[Scraper] Successfully saved ({attempt['name']}): {safe_title}")

            # Seasons/Episodes — scrape all seasons and all episodes
            if subject_data["subject_type"] == 2:
                resource_obj = data.get("data", {}).get("resource", {})
                seasons = resource_obj.get("seasons", [])
                for se in seasons:
                    se_num = int(se.get("se", 1))
                    max_ep = int(se.get("maxEp", 0))
                    episodes_list = ",".join(str(i) for i in range(1, max_ep + 1))
                    await db_save_season(subject_id, se_num, max_ep, episodes_list)
                    if only_se != -1 and only_ep != -1:
                        # Only scrape the requested episode inline, and background scrape the rest
                        if se_num == only_se:
                            await scrape_episode_resources(subject_id, only_se, only_ep)
                        asyncio.create_task(scrape_other_episodes_in_background(subject_id, se_num, max_ep, exclude_ep=only_ep if se_num == only_se else -1))
                    else:
                        if max_ep > 0:
                            for ep_num in range(1, max_ep + 1):
                                await scrape_episode_resources(subject_id, se_num, ep_num)
                                await asyncio.sleep(0.5)
            else:
                await scrape_episode_resources(subject_id, 0, 0)

            return subject_data
            
        except Exception as e:
            last_error = str(e)
            print(f"[Scraper Warning] Attempt with {attempt['name']} failed for {subject_id}: {e}")
            await asyncio.sleep(2.0)

    print(f"[Scraper Error] All proxy attempts failed for subject {subject_id}. Skipping for now. Last error: {last_error}")
    return {}

async def scrape_all_episodes_for_season(subject_id: str, season: int, max_ep: int):
    """Background task: scrape all episodes of a season that wasn't previously in DB."""
    for ep_num in range(1, max_ep + 1):
        try:
            await scrape_episode_resources(subject_id, season, ep_num)
            await asyncio.sleep(0.5)
        except Exception as e:
            print(f"[Season Scraper] Error on S{season}E{ep_num} for {subject_id}: {e}")

async def scrape_other_episodes_in_background(subject_id: str, season: int, max_ep: int, exclude_ep: int = -1):
    """Background task: scrape all episodes of a season except the excluded one."""
    for ep_num in range(1, max_ep + 1):
        if ep_num == exclude_ep:
            continue
        try:
            await scrape_episode_resources(subject_id, season, ep_num)
            await asyncio.sleep(0.5)
        except Exception as e:
            print(f"[Background Scraper] Error on S{season}E{ep_num} for {subject_id}: {e}")

async def scrape_episode_resources(subject_id: str, season: int, episode: int):
    try:
        detail_path = ""
        pool = await get_db_pool()
        if pool:
            async with pool.acquire() as conn:
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

        # Fallback: try netfilm.world /subject/play when /download returns empty
        if not downloads:
            try:
                token = await get_guest_bearer_token()
                async with httpx.AsyncClient(follow_redirects=True, timeout=20.0) as hx:
                    dom_resp = await hx.get(
                        "https://h5-api.aoneroom.com/wefeed-h5api-bff/media-player/get-domain",
                        headers={
                            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                            "Referer": "https://moviebox.ph/", "Origin": "https://moviebox.ph",
                            "X-Client-Info": json.dumps({"timezone": "Asia/Dhaka"}),
                            "Authorization": f"Bearer {token}"
                        }
                    )
                    player_domain = "https://netfilm.world"
                    if dom_resp.status_code == 200:
                        dom_val = dom_resp.json().get("data", player_domain)
                        if isinstance(dom_val, str) and dom_val.startswith("http"):
                            player_domain = dom_val.rstrip("/")

                    play_referer = f"{player_domain}/spa/videoPlayPage/movies/{detail_path}?id={subject_id}&type=/movie/detail&detailSe={season}&detailEp={episode}&lang=en"
                    play_url = f"{player_domain}/wefeed-h5api-bff/subject/play?subjectId={subject_id}&se={season}&ep={episode}&detailPath={detail_path}"
                    play_resp = await hx.get(play_url, headers={
                        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        "Referer": play_referer, "Accept": "application/json",
                        "X-Client-Info": json.dumps({"timezone": "Asia/Dhaka"}),
                    })
                    play_data = play_resp.json().get("data", {})
                    streams = play_data.get("streams", [])
                    if streams:
                        print(f"[Scraper] netfilm.world returned {len(streams)} streams for {subject_id} S{season}E{episode}")
                        for s in streams:
                            url = s.get("url", "")
                            if url:
                                downloads.append({
                                    "id": str(s.get("id", f"play_{s.get('resolutions', 0)}")),
                                    "resolution": int(s.get("resolutions", 720)),
                                    "size": int(s.get("size", 0)),
                                    "url": url
                                })
                        captions = play_data.get("captions", captions)
            except Exception as play_err:
                print(f"[Scraper] netfilm.world play fallback failed for {subject_id} S{season}E{episode}: {play_err}")
        
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
            items = data.get("data", {}).get("items") or data.get("data", {}).get("list") or []
            
            if not items and data.get("data", {}).get("results"):
                results = data.get("data", {}).get("results", [])
                if results:
                    items = results[0].get("subjects", [])

            for item in items:
                sub_id = item.get("subjectId")
                if not sub_id: continue
                if is_educational_content(item.get("title", "")): continue
                
                # Check if already fully cached (has description and resources)
                pool = await get_db_pool()
                needs_scrape = True
                if pool:
                    async with pool.acquire() as conn:
                        async with conn.cursor() as cur:
                            await cur.execute(
                                "SELECT subject_id FROM subjects WHERE subject_id = %s AND description IS NOT NULL AND description != '' AND title != 'Placeholder'",
                                (str(sub_id),)
                            )
                            row = await cur.fetchone()
                            if row:
                                needs_scrape = False
                        
                if needs_scrape:
                    safe_title = item.get('title', '').encode('ascii', 'replace').decode('ascii')
                    print(f"[Scraper] New/incomplete title found: {safe_title}. Fetching full details...")
                    await scrape_subject_details(sub_id)
                    await asyncio.sleep(1.0)
    except Exception as e:
        print(f"[Scraper] Incremental scraper loop encountered error: {e}")

async def scraper_loop():
    # Wait for DB connection
    await get_db_pool()
    await run_config_sync()
    
    while True:
        try:
            # Sync config and crawl latest lists
            await run_config_sync()
            await run_incremental_scraper()
        except Exception as e:
            print(f"[Scraper Loop] Error: {e}")
        # Run every 20 minutes
        await asyncio.sleep(1200)

async def missing_resource_rechecker_loop():
    """Periodically checks subjects marked as has_resource = FALSE to see if they've come back online."""
    # Wait for DB connection
    await get_db_pool()
    while True:
        try:
            pool = await get_db_pool()
            if pool:
                async with pool.acquire() as conn:
                    async with conn.cursor(aiomysql.DictCursor) as cur:
                        # Get all subjects that are marked as having no resources
                        await cur.execute("SELECT subject_id FROM subjects WHERE has_resource = FALSE")
                        dead_subjects = await cur.fetchall()
                
                if dead_subjects:
                    print(f"[Rechecker] Found {len(dead_subjects)} subjects with no resources. Checking...")
                    for row in dead_subjects:
                        sub_id = row["subject_id"]
                        detail_data = await request_h5_api("GET", f"/wefeed-h5api-bff/detail?subjectId={sub_id}")
                        if detail_data and detail_data.get("code") == 0:
                            sub_info = detail_data.get("data", {}).get("subject", {})
                            if sub_info.get("hasResource") is True:
                                print(f"[Rechecker] {sub_id} now HAS resources! Updating database.")
                                async with pool.acquire() as conn:
                                    async with conn.cursor() as cur:
                                        await cur.execute("UPDATE subjects SET has_resource = TRUE WHERE subject_id = %s", (sub_id,))
                        
                        # Be gentle on the API
                        await asyncio.sleep(2)
        except Exception as e:
            print(f"[Rechecker Loop] Error: {e}")
        
        # Run every 12 hours (43200 seconds)
        await asyncio.sleep(43200)

async def run_historical_scraper():
    progress = await db_read_scraper_progress()
    print(f"[Scraper] Starting historical scraping cycle. Current progress: {progress}")
    
    # Re-order to prioritize Movies (1) then TV (2) then Anime (7)
    for sub_type_str in ["1", "2", "7"]:
        sub_type = int(sub_type_str)
        current_page = progress.get(sub_type_str, 2)
        retry_count = 0
        
        while current_page <= 2000:
            print(f"[Scraper] Scraping historical page {current_page} for subject type {sub_type}...")
            try:
                # ... payload construction ...
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
                items = data.get("data", {}).get("items") or data.get("data", {}).get("list") or []
                
                if not items and data.get("data", {}).get("results"):
                    results = data.get("data", {}).get("results", [])
                    if results:
                        items = results[0].get("subjects", [])
                
                if not items:
                    if current_page > 5:
                        print(f"[Scraper] No more items on page {current_page} for type {sub_type}. Marking as completed.")
                        progress[sub_type_str] = 999
                        await db_save_scraper_progress(sub_type, 999)
                    else:
                        print(f"[Scraper] No items on early page {current_page} for type {sub_type}. Skipping.")
                        current_page += 1
                        progress[sub_type_str] = current_page
                        await db_save_scraper_progress(sub_type, current_page)
                    break
                
                new_items_count = 0
                for item in items:
                    sub_id = item.get("subjectId")
                    item_type = item.get("subjectType")
                    
                    if not sub_id: continue
                    
                    # Core Filter: Only process Movies (1) and TV Series (2). Everything else (like 8 for user uploads/games) is junk.
                    if item_type not in [1, 2]:
                        continue
                        
                    if is_educational_content(item.get("title", "")): continue

                    # Skip 0-rating items in historical scraper (old/unrated junk)
                    item_rating = float(item.get("imdbRatingValue") or item.get("score") or 0.0)
                    if item_rating == 0.0: continue
                    
                    pool = await get_db_pool()
                    needs_full_scrape = True
                    if pool:
                        async with pool.acquire() as conn:
                            async with conn.cursor() as cur:
                                await cur.execute(
                                    "SELECT subject_id FROM subjects WHERE subject_id = %s AND description IS NOT NULL AND description != '' AND title != 'Placeholder'",
                                    (str(sub_id),)
                                )
                                row = await cur.fetchone()
                                if row:
                                    needs_full_scrape = False
                            
                    if needs_full_scrape:
                        safe_title = item.get('title', '').encode('ascii', 'replace').decode('ascii')
                        print(f"[Scraper] Page {current_page} | Found '{safe_title}' (ID: {sub_id}). Scraping details...")
                        res = await scrape_subject_details(sub_id)
                        if res:
                            new_items_count += 1
                        await asyncio.sleep(0.5) # Fast
                
                print(f"[Scraper] Page {current_page} done. Successfully saved {new_items_count} items.")
                retry_count = 0
                current_page += 1
                progress[sub_type_str] = current_page
                await db_save_scraper_progress(sub_type, current_page)
                
                await asyncio.sleep(2.0) # Faster delay between pages
                
            except Exception as e:
                err_msg = str(e).lower()
                # Mark as completed if HTTP 400 (out of bounds on this API).
                # HTTP 400 propagates either as "http status 400" or "[http 400]" in error message.
                is_out_of_bounds = ("400" in err_msg) and not any(x in err_msg for x in ["token", "auth", "sign", "cookie"])
                
                if is_out_of_bounds:
                    print(f"[Scraper] Page {current_page} returned 400 (Out of bounds or API limit). Marking type {sub_type} as completed.")
                    progress[sub_type_str] = 999
                    await db_save_scraper_progress(sub_type, 999)
                    break
                
                retry_count += 1
                if retry_count >= 3:
                    print(f"[Scraper] Too many failures ({retry_count}) on page {current_page} for type {sub_type}. Skipping page. Error: {e}")
                    retry_count = 0
                    current_page += 1
                    progress[sub_type_str] = current_page
                    await db_save_scraper_progress(sub_type, current_page)
                else:
                    print(f"[Scraper] Error on page {current_page} for type {sub_type}: {e}. Retrying ({retry_count}/3) in 15s...")
                    await asyncio.sleep(15.0)

async def run_missing_resources_scraper():
    """Background task: find subjects in DB that have no play_resources and scrape them."""
    print("[Scraper] Starting missing-resources scraper...")
    pool = await get_db_pool()
    if not pool:
        return
    try:
        async with pool.acquire() as conn:
            async with conn.cursor(aiomysql.DictCursor) as cur:
                # Find up to 50 subjects with description but no play_resources
                await cur.execute("""
                    SELECT s.subject_id, s.subject_type, s.title
                    FROM subjects s
                    LEFT JOIN play_resources pr ON pr.subject_id = s.subject_id
                    WHERE s.title != 'Placeholder'
                      AND s.description IS NOT NULL AND s.description != ''
                      AND pr.subject_id IS NULL
                    LIMIT 50
                """)
                missing = await cur.fetchall()
        
        if not missing:
            print("[Scraper] No subjects missing resources. Skipping.")
            return
        
        print(f"[Scraper] Found {len(missing)} subjects missing play_resources. Scraping...")
        for row in missing:
            sub_id = row["subject_id"]
            sub_type = row["subject_type"]
            safe_title = row["title"].encode('ascii', 'replace').decode('ascii')
            print(f"[Scraper] Fetching resources for: {safe_title} (ID: {sub_id})")
            try:
                if sub_type == 2:
                    # TV show — scrape season 1, episode 1 resources
                    await scrape_episode_resources(sub_id, 1, 1)
                else:
                    await scrape_episode_resources(sub_id, 0, 0)
            except Exception as e:
                print(f"[Scraper] Failed fetching resources for {sub_id}: {e}")
            await asyncio.sleep(1.5)
    except Exception as e:
        print(f"[Scraper] Missing-resources scraper error: {e}")

async def historical_scraper_loop():
    await get_db_pool()
    await asyncio.sleep(30)
    
    while True:
        try:
            await run_historical_scraper()
        except Exception as e:
            print(f"[Historical Scraper Loop] Error: {e}")
        # After full historical scrape, wait 1 hour then try again
        await asyncio.sleep(3600)

async def missing_resources_loop():
    """Loop that repeatedly fills in missing play_resources for known subjects."""
    await get_db_pool()
    await asyncio.sleep(60)  # Wait 1 min after startup
    while True:
        try:
            await run_missing_resources_scraper()
        except Exception as e:
            print(f"[Missing Resources Loop] Error: {e}")
        # Run every 5 minutes
        await asyncio.sleep(300)

# Startup/shutdown is handled by the lifespan context manager above (app = FastAPI(lifespan=lifespan))

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
            items = data.get("data", {}).get("items") or data.get("data", {}).get("list") or []
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
    now = datetime.now(timezone.utc).replace(tzinfo=None)
    
    # 1. Try local MySQL lookup first
    pool = await get_db_pool()
    if pool:
        try:
            async with pool.acquire() as conn:
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
                                    "url": f"/fetch?source_url={urllib.parse.quote(r['resource_link'])}"
                                })
                            qualities.sort(key=lambda q: q["resolution"])
                            
                            captions = []
                            for c in caps:
                                captions.append({
                                    "language": c["lang"],
                                    "name": c["label"],
                                    "url": c['url']
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
    
    if not downloads:
        # Regex fallback: scan raw JSON response string for any mp4 link
        raw_text = json.dumps(download_data)
        mp4_matches = re.findall(r'https?://[^\s"\']+\.mp4[^\s"\']*', raw_text)
        if mp4_matches:
            unique_links = list(dict.fromkeys(mp4_matches))
            downloads = [{"id": f"regex_fallback_{idx}", "resolution": 720 + idx, "size": 0, "url": link} for idx, link in enumerate(unique_links)]

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
            "url": f"/fetch?source_url={urllib.parse.quote(r_link)}"
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
            "url": cap_url
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


async def get_local_operating_list() -> list:
    pool = await get_db_pool()
    if not pool:
        return []
    
    sections = [
        {"title": "Latest Movies", "type": 1, "sort": "latest"},
        {"title": "Latest TV Shows", "type": 2, "sort": "latest"},
        {"title": "Top Rated Movies", "type": 1, "sort": "rating"},
        {"title": "Top Rated TV Shows", "type": 2, "sort": "rating"}
    ]
    
    import datetime
    current_year = datetime.datetime.now().year
    cutoff_date = f"{current_year}-12-31"
    
    operating_list = []
    try:
        async with pool.acquire() as conn:
            async with conn.cursor(aiomysql.DictCursor) as cur:
                for sec in sections:
                    order_by = "release_date DESC, created_at DESC" if sec["sort"] == "latest" else "rating DESC"
                    await cur.execute(f"""
                        SELECT * FROM subjects 
                        WHERE subject_type = %s AND title != 'Placeholder'
                          AND has_resource = TRUE
                          AND (release_date IS NULL OR release_date = '' OR release_date <= %s)
                        ORDER BY {order_by} 
                        LIMIT 12
                    """, (sec["type"], cutoff_date))
                    rows = await cur.fetchall()
                    
                    subjects = []
                    for row in rows:
                        subjects.append({
                            "subjectId": row["subject_id"],
                            "title": row["title"],
                            "subjectType": row["subject_type"],
                            "cover": {"url": row["cover"]},
                            "imdbRatingValue": str(row["rating"]),
                            "releaseDate": row["release_date"],
                            "countryName": row["country"],
                            "genre": row["genre"].split(",") if row["genre"] else [],
                            "isCam": row["is_cam"],
                            "detailPath": row["detail_path"] or ""
                        })
                    
                    if subjects:
                        operating_list.append({
                            "name": sec["title"],
                            "subjects": subjects
                        })
    except Exception as e:
        print(f"[Home Local Fallback Error] {e}")
    return operating_list

# Transparent proxy to grab initial feeds and auto-cache subjects
@app.get("/api/home")
async def get_home(page: int = 1, tabId: int = 0):
    cache_key = f"home:{page}:{tabId}"
    cached = get_cached_response(cache_key)
    if cached:
        return cached

    # Fetch fresh remote home data first (quick load)
    try:
        result = await _fetch_and_cache_remote_home(page, tabId, cache_key)
        if result:
            return result
    except Exception as e:
        print(f"[Home API Proxy Error] {e}")

    # Fallback to local DB categories only if remote API fails
    local_list = await get_local_operating_list()
    if local_list:
        local_result = {
            "code": 0,
            "data": {
                "items": local_list
            }
        }
        return local_result

    # Final fallback: empty response
    return {"code": 0, "data": {"items": []}}


async def _fetch_and_cache_remote_home(page: int, tabId: int, cache_key: str):
    """Fetch home data from remote API, process, cache, and return result. Returns None on failure."""
    remote_data = await request_h5_api("GET", "/wefeed-h5api-bff/home?host=moviebox.ph")
    if not (remote_data and remote_data.get("code") == 0):
        return None

    operating_list = remote_data.get("data", {}).get("operatingList", [])
    cleaned_list = []

    for sec in operating_list:
        sec_type = sec.get("type")
        sec_title = sec.get("title", "")

        # Check for banner list
        if sec_type == "BANNER":
            banner_data = sec.get("banner", {})
            items = banner_data.get("items", [])
            cleaned_items = []
            for item in items:
                sub = item.get("subject")
                if sub and (is_future_subject(sub) or is_educational_content(sub.get("title", ""))):
                    continue
                cleaned_items.append(item)

            if cleaned_items:
                banner_data["items"] = cleaned_items
                sec["banner"] = banner_data
                sec["name"] = sec_title
                cleaned_list.append(sec)

                # Cache banner items asynchronously in database
                for item in cleaned_items:
                    sub = item.get("subject")
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

        # Check for regular subjects list
        elif sec_type == "SUBJECTS_MOVIE" or (sec.get("subjects") and len(sec["subjects"]) > 0):
            subjects = sec.get("subjects", [])
            cleaned_subjects = []
            for sub in subjects:
                if is_future_subject(sub) or is_educational_content(sub.get("title", "")):
                    continue
                cleaned_subjects.append(sub)

            if cleaned_subjects:
                sec["subjects"] = cleaned_subjects

                # Normalize section names
                if "Popular Movie" in sec_title:
                    sec["title"] = "Trending Now"
                    sec["name"] = "Trending Now"
                elif "Popular Series" in sec_title:
                    sec["title"] = "Trending Series"
                    sec["name"] = "Trending Series"
                else:
                    sec["name"] = sec_title

                cleaned_list.append(sec)

                # Asynchronously cache all valid subjects to local database
                for sub in cleaned_subjects:
                    sub_id = sub.get("subjectId")
                    if sub_id and str(sub_id) != "0":
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
                            "rating": float(sub.get("imdbRatingValue") or sub.get("score") or 7.5),
                            "release_date": sub.get("releaseDate", "2026"),
                            "country": sub.get("countryName", ""),
                            "genre": genres_str,
                            "description": sub.get("description", ""),
                            "is_cam": bool(sub.get("isCam", False)),
                            "detail_path": sub.get("detailPath")
                        }
                        asyncio.create_task(db_save_subject(s_data))

    if not cleaned_list:
        return None

    result = {
        "code": 0,
        "data": {
            "items": cleaned_list
        }
    }
    # Cache for 10 minutes
    set_cached_response(cache_key, result, ttl=600.0)
    return result

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

@app.get("/api/search/suggest")
async def search_suggest(q: str = ""):
    q = q.strip()
    if not q:
        return {"code": 0, "data": {"items": []}}
    
    try:
        api_path = "/wefeed-h5api-bff/subject/search"
        api_payload = {
            "keyword": q,
            "page": 1,
            "perPage": 6,
            "subjectType": 0
        }
        data = await request_h5_api("POST", api_path, api_payload)
        items = data.get("data", {}).get("items") or data.get("data", {}).get("list") or []
        
        cleaned_items = []
        for item in items:
            sub = item.get("subject") or item
            if is_future_subject(sub) or is_educational_content(sub.get("title", "")):
                continue
            
            cover_val = sub.get("cover")
            cover_url = cover_val.get("url") if isinstance(cover_val, dict) else str(cover_val or "")
            
            cleaned_items.append({
                "subjectId": sub.get("subjectId"),
                "title": sub.get("title"),
                "detailPath": sub.get("detailPath") or item.get("detailPath") or "",
                "subjectType": sub.get("subjectType") or 1,
                "cover": {"url": cover_url},
                "rating": str(sub.get("imdbRatingValue") or sub.get("score") or "7.5"),
                "releaseDate": sub.get("releaseDate") or ""
            })
        return {"code": 0, "data": {"items": cleaned_items}}
    except Exception as e:
        print(f"[Remote Suggest Error] {e}")
        return {"code": 500, "error": str(e)}


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
        if is_educational_content(sub.get("title", "")): continue
        
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

async def background_search_and_cache(keyword: str, page: int, per_page: int, subject_type: int):
    try:
        api_path = "/wefeed-h5api-bff/subject/search"
        api_payload = {
            "keyword": keyword,
            "page": page,
            "perPage": per_page,
            "subjectType": subject_type
        }
        data = await request_h5_api("POST", api_path, api_payload)
        items = data.get("data", {}).get("items") or data.get("data", {}).get("list") or []
        for item in items:
            sub_id = item.get("subjectId")
            if sub_id:
                await scrape_subject_details(sub_id)
    except Exception as e:
        print(f"[Background Search Cache Error] {e}")

# Search endpoint — DB only for instant response, background cache from API
@app.post("/api/search")
async def search_content(payload: dict):
    keyword = payload.get("keyword", "").strip()
    page = int(payload.get("page", 1))
    per_page = int(payload.get("perPage", 20))
    genre = payload.get("genre", "*")
    country = payload.get("country", "*")
    year = payload.get("year", "*")
    sort = payload.get("sort", "Latest")
    subject_type = int(payload.get("subjectType", 0))

    if not keyword:
        return {"code": 0, "data": {"items": []}}

    cache_key = f"search_v2:{keyword}:{page}:{per_page}:{subject_type}:{genre}:{country}:{year}:{sort}"
    cached = get_cached_response(cache_key)
    if cached:
        return cached

    # Fetch from the remote Search API directly (fresh and complete!)
    try:
        api_path = "/wefeed-h5api-bff/subject/search"
        api_payload = {
            "keyword": keyword,
            "page": page,
            "perPage": per_page,
            "subjectType": subject_type
        }
        data = await request_h5_api("POST", api_path, api_payload)
        items = data.get("data", {}).get("items") or data.get("data", {}).get("list") or []
        
        cleaned_items = []
        for item in items:
            sub = item.get("subject") or item
            if is_future_subject(sub) or is_educational_content(sub.get("title", "")):
                continue
            
            cover_val = sub.get("cover")
            cover_url = cover_val.get("url") if isinstance(cover_val, dict) else str(cover_val or "")
            genres = sub.get("genre") or []
            if isinstance(genres, str):
                genres = genres.split(",")
                
            cleaned_items.append({
                "subjectId": sub.get("subjectId"),
                "title": sub.get("title"),
                "detailPath": sub.get("detailPath") or item.get("detailPath") or "",
                "subjectType": sub.get("subjectType") or 1,
                "cover": {"url": cover_url},
                "imdbRatingValue": str(sub.get("imdbRatingValue") or sub.get("score") or "7.5"),
                "releaseDate": sub.get("releaseDate") or "",
                "countryName": sub.get("countryName") or "",
                "genre": genres,
                "isCam": bool(sub.get("isCam", False))
            })
            
        res = {
            "code": 0,
            "data": {
                "items": cleaned_items,
                "pager": {"hasMore": len(cleaned_items) == per_page}
            }
        }
        set_cached_response(cache_key, res, ttl=300.0) # Cache search results for 5 minutes
        return res
    except Exception as e:
        print(f"[Remote Search Error] {e}")
        return {"code": 500, "error": str(e)}

async def background_filter_and_cache(genre, country, year, language, sort, subject_type, page, per_page):
    try:
        api_sort = "Rating" if sort == "Hottest" else sort
        api_payload = {
            "page": page,
            "perPage": per_page,
            "genre": "" if genre == "*" else genre,
            "country": "" if country == "*" else country,
            "year": "" if year == "*" else year,
            "language": "" if language == "*" else language,
            "sort": api_sort,
            "subjectType": subject_type
        }
        
        data = await request_h5_api("POST", "/wefeed-h5api-bff/subject/filter", api_payload)
        items = data.get("data", {}).get("items") or data.get("data", {}).get("list") or []
        
        if not items and data.get("data", {}).get("results"):
            results = data.get("data", {}).get("results", [])
            if results:
                items = results[0].get("subjects", [])
                
        for item in items:
            title = item.get("title", "")
            if is_educational_content(title):
                continue
            sub_id = item.get("subjectId")
            if sub_id:
                await scrape_subject_details(sub_id)
    except Exception as e:
        print(f"[Background Filter Cache Error] {e}")

def safe_float_rating(val) -> float:
    if not val:
        return 0.0
    try:
        return float(val)
    except ValueError:
        return 0.0

# Dynamic Multi-Level Filters — DB only, always fast
@app.post("/api/filter")
async def filter_content(payload: dict):
    genre = payload.get("genre", "*")
    country = payload.get("country", "*")
    year = payload.get("year", "*")
    language = payload.get("language", "*")
    sort = payload.get("sort", "Latest")
    subject_type = int(payload.get("subjectType", 0))
    page = int(payload.get("page", 1))
    per_page = int(payload.get("perPage", 20))

    cache_key = f"filter:{genre}:{country}:{year}:{language}:{sort}:{subject_type}:{page}:{per_page}"
    cached = get_cached_response(cache_key)
    if cached:
        return cached

    # Build MySQL Query — only from local database
    conditions = [
        "title != 'Placeholder'",
        "title != ''",
        "cover IS NOT NULL",
        "cover != ''",
        "(release_date IS NULL OR release_date = '' OR release_date <= CURDATE())"
    ]
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

    where_clause = " WHERE " + " AND ".join(conditions)

    if sort == "Hottest":
        order_clause = " ORDER BY rating DESC, release_date DESC, created_at DESC"
    elif sort == "Latest":
        order_clause = " ORDER BY (rating > 0) DESC, release_date DESC, rating DESC, created_at DESC"
    else:
        order_clause = " ORDER BY (rating > 0) DESC, release_date DESC, rating DESC, created_at DESC"

    offset = (page - 1) * per_page
    query = f"SELECT * FROM subjects{where_clause}{order_clause} LIMIT %s OFFSET %s"
    params.extend([per_page, offset])

    # Count total for hasMore
    count_query = f"SELECT COUNT(*) as cnt FROM subjects{where_clause}"

    local_results = []
    has_more = False
    pool = await get_db_pool()
    if pool:
        try:
            async with pool.acquire() as conn:
                async with conn.cursor(aiomysql.DictCursor) as cur:
                    await cur.execute(query, tuple(params))
                    rows = await cur.fetchall()
                    for row in rows:
                        if is_educational_content(row["title"]):
                            continue
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
                    has_more = len(rows) == per_page
        except Exception as db_err:
            print(f"[DB Filter Error] {db_err}")

    res = {
        "code": 0,
        "data": {
            "items": local_results,
            "pager": {"hasMore": has_more}
        }
    }
    set_cached_response(cache_key, res, ttl=120.0)
    return res

async def resolve_details_via_tmdb(subject_id: str, path_to_use: str, db_row: dict = None) -> dict:
    title_str = ""
    is_tv = True
    if db_row:
        is_tv = (db_row.get("subject_type") == 2)
        if db_row.get("title") and db_row.get("title") != "Placeholder":
            title_str = db_row.get("title")

    if not title_str:
        if not path_to_use or "subjectId" in path_to_use or "details" in path_to_use:
            return None
            
        path_clean = path_to_use.split("?")[0]
        parts = path_clean.split("-")
        if len(parts) > 1:
            last_part = parts[-1]
            # Check if last part is alphanumeric hash of length >= 8
            if len(last_part) >= 8 and any(c.isupper() for c in last_part) and any(c.islower() for c in last_part):
                title_parts = parts[:-1]
            else:
                title_parts = parts
            title_str = " ".join(title_parts).strip()
        else:
            title_str = path_to_use
            
        title_str = title_str.replace("_", " ").title()
        is_tv = any(k in path_to_use.lower() for k in ["season", "series", "episode", "got", "thrones"])
        
    if not title_str:
        return None
        
    print(f"[TMDB Details Fallback] Searching TMDB for '{title_str}' (is_tv={is_tv})")
    
    search_type = "tv" if is_tv else "movie"
    url = f"{TMDB_BASE_URL}/search/{search_type}?api_key={TMDB_API_KEY}&query={urllib.parse.quote(title_str)}"
    try:
        async with httpx.AsyncClient(trust_env=False, timeout=5.0) as client:
            resp = await client.get(url)
            if resp.status_code == 200:
                results = resp.json().get("results", [])
                if not results and not db_row:
                    search_type = "movie" if is_tv else "tv"
                    url2 = f"{TMDB_BASE_URL}/search/{search_type}?api_key={TMDB_API_KEY}&query={urllib.parse.quote(title_str)}"
                    resp2 = await client.get(url2)
                    if resp2.status_code == 200:
                        results = resp2.json().get("results", [])
                        if results:
                            is_tv = not is_tv
                            
                if results:
                    match = results[0]
                    tmdb_id = match.get("id")
                    
                    details_path = f"/tv/{tmdb_id}" if is_tv else f"/movie/{tmdb_id}"
                    details_url = f"{TMDB_BASE_URL}{details_path}?api_key={TMDB_API_KEY}"
                    resp_details = await client.get(details_url)
                    if resp_details.status_code == 200:
                        td = resp_details.json()
                        
                        title = td.get("name") or td.get("title")
                        release_date = td.get("first_air_date") or td.get("release_date") or ""
                        rating = td.get("vote_average", 7.5)
                        genres = [g.get("name") for g in td.get("genres", [])]
                        genres_str = ",".join(genres)
                        description = td.get("overview") or "No description available."
                        
                        poster_path = td.get("poster_path")
                        cover_url = f"https://image.tmdb.org/t/p/w500{poster_path}" if poster_path else ""
                        
                        backdrop_path = td.get("backdrop_path")
                        backdrop_url = f"https://image.tmdb.org/t/p/original{backdrop_path}" if backdrop_path else ""
                        
                        countries = td.get("production_countries", [])
                        country = countries[0].get("name") if countries else (td.get("origin_country", ["USA"])[0] if td.get("origin_country") else "USA")
                        
                        episode_run_time = td.get("episode_run_time", [])
                        duration = episode_run_time[0] if (is_tv and episode_run_time) else td.get("runtime", 0)
                        duration_str = f"{duration} min" if duration else "-- min"
                        
                        # Save to database
                        se_num = 0
                        pool = await get_db_pool()
                        if pool:
                            try:
                                async with pool.acquire() as conn:
                                    async with conn.cursor() as cur:
                                        await cur.execute("""
                                            INSERT INTO subjects (subject_id, title, subject_type, cover, backdrop, rating, release_date, country, genre, description, is_cam, detail_path, tmdb_id)
                                            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                                            ON DUPLICATE KEY UPDATE
                                                title=VALUES(title),
                                                cover=VALUES(cover),
                                                backdrop=VALUES(backdrop),
                                                rating=VALUES(rating),
                                                release_date=VALUES(release_date),
                                                country=VALUES(country),
                                                genre=VALUES(genre),
                                                description=VALUES(description),
                                                detail_path=VALUES(detail_path),
                                                tmdb_id=VALUES(tmdb_id)
                                        """, (
                                            str(subject_id), title, 2 if is_tv else 1, cover_url, backdrop_url,
                                            rating, release_date, country, genres_str, description, False,
                                            path_to_use, str(tmdb_id)
                                        ))
                                        
                                        # Also resolve and save seasons if it is TV series
                                        if is_tv:
                                            seasons_list = td.get("seasons", [])
                                            se_num = len(seasons_list)
                                            for se in seasons_list:
                                                se_num_val = se.get("season_number")
                                                # Skip season 0 (specials) unless it's the only one
                                                if se_num_val == 0 and len(seasons_list) > 1:
                                                    continue
                                                max_ep = se.get("episode_count", 0)
                                                if max_ep > 0:
                                                    episodes_str = ",".join(str(i) for i in range(1, max_ep + 1))
                                                    # Insert/update season
                                                    await cur.execute("""
                                                        INSERT INTO seasons (subject_id, season_number, episode_count, episodes_list)
                                                        VALUES (%s, %s, %s, %s)
                                                        ON DUPLICATE KEY UPDATE
                                                            episode_count=VALUES(episode_count),
                                                            episodes_list=VALUES(episodes_list)
                                                    """, (str(subject_id), int(se_num_val), int(max_ep), episodes_str))
                                                    
                                print(f"[TMDB Details Fallback] Successfully resolved and cached '{title}' to database.")
                            except Exception as db_err:
                                print(f"[TMDB Details Fallback DB Save Error] {db_err}")
                                
                        return {
                            "subjectId": str(subject_id),
                            "title": title,
                            "subjectType": 2 if is_tv else 1,
                            "cover": {"url": cover_url},
                            "imdbRatingValue": str(rating),
                            "releaseDate": release_date,
                            "countryName": country,
                            "genre": genres,
                            "description": description,
                            "isCam": False,
                            "duration": duration_str,
                            "dubs": [],
                            "seNum": se_num
                        }
    except Exception as e:
        print(f"[TMDB Details Fallback Error] {e}")
    return None

# Details Endpoint
@app.get("/api/detail")
async def get_detail(subjectId: str, detailPath: str = ""):
    db_detail_path = ""
    row = None
    se_num = 0
    # Try MySQL
    pool = await get_db_pool()
    if pool:
        try:
            async with pool.acquire() as conn:
                async with conn.cursor(aiomysql.DictCursor) as cur:
                    await cur.execute("SELECT * FROM subjects WHERE subject_id = %s", (subjectId,))
                    row = await cur.fetchone()
                    if row:
                        db_detail_path = row.get("detail_path") or ""
                        if int(row.get("subject_type", 1)) != 1:
                            # Fetch season count from seasons table
                            await cur.execute("SELECT COUNT(*) as count FROM seasons WHERE subject_id = %s", (subjectId,))
                            se_row = await cur.fetchone()
                            if se_row:
                                se_num = se_row["count"]
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
                
        # Self-healing: if details, description, detail_path or dubs are missing, trigger background scrape
        has_desc = bool(row.get("description") and "temporarily unavailable" not in row["description"])
        has_detail_path = bool(row.get("detail_path"))
        has_seasons = True
        if int(row.get("subject_type", 1)) == 2 and se_num == 0:
            has_seasons = False
            
        if not has_desc or not has_detail_path or not has_seasons:
            print(f"[Details API] Data incomplete for {subjectId} (desc={has_desc}, path={has_detail_path}, seasons={has_seasons}). Triggering background scrape...")
            asyncio.create_task(scrape_subject_details(subjectId))
            
        if row["description"] and row["title"] != "Placeholder" and "temporarily unavailable" not in row["description"]:
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
                    "dubs": dubs_data,
                    "detailPath": row["detail_path"] or db_detail_path or "",
                    "seNum": se_num
                }
            }

    # API Fallback
    path_to_use = detailPath or db_detail_path
    if not path_to_use:
        path_to_use = f"details?subjectId={subjectId}"
        
    try:
        if "subjectId=" in path_to_use:
            sub_id_val = path_to_use.split("subjectId=")[1].split("&")[0]
            api_path = f"/wefeed-h5api-bff/detail?subjectId={sub_id_val}"
        elif "details" in path_to_use:
            api_path = f"/wefeed-h5api-bff/detail?{path_to_use}"
        else:
            api_path = f"/wefeed-h5api-bff/detail?detailPath={urllib.parse.quote(path_to_use)}"
            
        api_data = await request_h5_api("GET", api_path)
        subject_info = api_data.get("data", {}).get("subject", {})
        if not subject_info:
            raise Exception("Subject not found in H5 API")
            
        resource_obj = api_data.get("data", {}).get("resource", {})
        seasons_list = resource_obj.get("seasons", [])
        api_se_num = len(seasons_list) if int(subject_info.get("subjectType", 1)) != 1 else 0
            
        # Check if educational or blocked
        genres = subject_info.get("genre", "")
        genres_str = ",".join(genres) if isinstance(genres, list) else str(genres)
        if is_educational_content(subject_info.get("title", ""), genres_str):
            raise HTTPException(status_code=403, detail="This content is not allowed (blocked genre or educational)")
            
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
            "dubs": subject_info.get("dubs", []),
            "detailPath": subject_info.get("detailPath") or path_to_use or "",
            "seNum": api_se_num
        }
        return {"code": 0, "data": formatted_detail}
    except Exception as e:
        print(f"[Details API Fallback] Failed to fetch details for {subjectId} path {path_to_use}: {e}")
        
        # Try resolving via TMDB fallback
        tmdb_resolved = await resolve_details_via_tmdb(subjectId, path_to_use, row)
        if tmdb_resolved:
            return {"code": 0, "data": tmdb_resolved}
            
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
                    "dubs": dubs_data,
                    "seNum": se_num
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
                "isCam": False,
                "seNum": 0
            }
        }

# Deployment / Auto-Update Endpoint
import subprocess

@app.get("/api/update-git-deploy")
@app.post("/api/update-git-deploy")
async def update_git_deploy(secret: str = ""):
    if secret != DEPLOY_SECRET:
        raise HTTPException(status_code=403, detail="Forbidden: Invalid Secret")
        
    try:
        script_path = os.path.join(base_dir, "streamhitupdate.sh")
        if os.path.exists(script_path):
            if os.name != 'nt':
                # Run the bash script detached in its own session group so it survives python restart
                subprocess.Popen(
                    ["/bin/bash", script_path],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    start_new_session=True
                )
                return {"status": "success", "message": "Production update script triggered."}
            else:
                # Windows (testing/development)
                return {"status": "success", "message": "Windows environment detected. Update simulated successfully."}
        else:
            return {"status": "error", "message": f"Update script not found at {script_path}"}
    except Exception as e:
        return {"status": "error", "message": str(e)}

# TV Seasons/Episodes
@app.get("/api/season-info")
async def get_season_info(subjectId: str, detailPath: str = ""):
    # Try MySQL
    seasons = []
    pool = await get_db_pool()
    if pool:
        try:
            async with pool.acquire() as conn:
                async with conn.cursor(aiomysql.DictCursor) as cur:
                    await cur.execute("SELECT * FROM seasons WHERE subject_id = %s ORDER BY season_number", (subjectId,))
                    rows = await cur.fetchall()
                    for r in rows:
                        seasons.append({
                            "se": r["season_number"],
                            "episodeCount": r["episode_count"],
                            "allEp": r["episodes_list"]
                        })
        except Exception as db_err:
            print(f"[DB Season Info Lookup Error] {db_err}")
                
    db_season_count = len(seasons)
    db_season_nums = {s["se"] for s in seasons}

    # Always fetch from API to check if there are more seasons than what's in DB
    # (e.g. "From S1-S4" was scraped with only S1 saved — API has S1-S4)

    # Try to get detail path from db
    db_detail_path = ""
    if pool:
        try:
            async with pool.acquire() as conn:
                async with conn.cursor() as cur:
                    await cur.execute("SELECT detail_path FROM subjects WHERE subject_id = %s", (subjectId,))
                    row = await cur.fetchone()
                    if row:
                        db_detail_path = row[0] or ""
        except Exception as db_err:
            print(f"[DB detail_path Lookup Error] {db_err}")

    # Fallback to API
    path_to_use = detailPath or db_detail_path
    if not path_to_use:
        path_to_use = f"details?subjectId={subjectId}"
        
    try:
        if "subjectId=" in path_to_use:
            sub_id_val = path_to_use.split("subjectId=")[1].split("&")[0]
            api_path = f"/wefeed-h5api-bff/detail?subjectId={sub_id_val}"
        elif "details" in path_to_use:
            api_path = f"/wefeed-h5api-bff/detail?{path_to_use}"
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
            
            # Save to DB (INSERT OR UPDATE)
            await db_save_season(subjectId, se_num, max_ep, episodes_list)

            # If this season wasn't in DB before, scrape its episode resources in background
            if se_num not in db_season_nums and max_ep > 0:
                print(f"[Season Info] New season {se_num} found for {subjectId}. Scraping {max_ep} episodes in background...")
                asyncio.create_task(scrape_all_episodes_for_season(subjectId, se_num, max_ep))
            
            formatted_seasons.append({
                "se": se_num,
                "episodeCount": max_ep,
                "allEp": episodes_list
            })
            
        # Check if TMDB has more seasons to merge
        try:
            db_row = None
            if pool:
                async with pool.acquire() as conn:
                    async with conn.cursor(aiomysql.DictCursor) as cur:
                        await cur.execute("SELECT * FROM subjects WHERE subject_id = %s", (subjectId,))
                        db_row = await cur.fetchone()
            
            if db_row and db_row.get("subject_type") == 2:
                tmdb_resolved = await resolve_details_via_tmdb(subjectId, path_to_use, db_row)
                if tmdb_resolved:
                    merged_seasons = []
                    async with pool.acquire() as conn:
                        async with conn.cursor(aiomysql.DictCursor) as cur:
                            await cur.execute("SELECT * FROM seasons WHERE subject_id = %s ORDER BY season_number", (subjectId,))
                            rows = await cur.fetchall()
                            for r in rows:
                                merged_seasons.append({
                                    "se": r["season_number"],
                                    "episodeCount": r["episode_count"],
                                    "allEp": r["episodes_list"]
                                })
                    if merged_seasons:
                        merged_dict = {s["se"]: s for s in merged_seasons}
                        for s in formatted_seasons:
                            merged_dict[s["se"]] = s
                        return {"code": 0, "data": {"seasons": sorted(merged_dict.values(), key=lambda x: x["se"])}}
        except Exception as tmdb_err:
            print(f"[Season Info TMDB Merge Error] {tmdb_err}")

        return {"code": 0, "data": {"seasons": formatted_seasons}}
    except Exception as e:
        print(f"[Season Info API Fallback] Failed to fetch seasons for {subjectId} path {path_to_use}: {e}")
        # Fall back to whatever we already have in DB
        if seasons:
            return {"code": 0, "data": {"seasons": seasons}}
        
        # Try resolving details (including seasons) via TMDB
        tmdb_resolved = await resolve_details_via_tmdb(subjectId, path_to_use)
        if tmdb_resolved and pool:
            # Query seasons table again
            try:
                async with pool.acquire() as conn:
                    async with conn.cursor(aiomysql.DictCursor) as cur:
                        await cur.execute("SELECT * FROM seasons WHERE subject_id = %s ORDER BY season_number", (subjectId,))
                        rows = await cur.fetchall()
                        if rows:
                            seasons_res = []
                            for r in rows:
                                seasons_res.append({
                                    "se": r["season_number"],
                                    "episodeCount": r["episode_count"],
                                    "allEp": r["episodes_list"]
                                })
                            return {"code": 0, "data": {"seasons": seasons_res}}
            except Exception as db_err:
                print(f"[DB Season Info Re-Lookup Error] {db_err}")
                
        return {"code": 0, "data": {"seasons": [{"se": 1, "episodeCount": 1, "allEp": "1"}]}}

# Play Resource link (resolves and auto-renews CDN links)
@app.get("/api/resource")
async def get_resource(subjectId: str, se: int = 0, ep: int = 0, detailPath: str = ""):
    now = datetime.now(timezone.utc).replace(tzinfo=None)
    pool = await get_db_pool()

    # Query MySQL for cached play resources
    cached_rows = []
    if pool:
        try:
            async with pool.acquire() as conn:
                async with conn.cursor(aiomysql.DictCursor) as cur:
                    await cur.execute("""
                        SELECT * FROM play_resources 
                        WHERE subject_id = %s AND season = %s AND episode = %s
                    """, (subjectId, se, ep))
                    cached_rows = await cur.fetchall()
        except Exception as db_err:
            print(f"[DB Resource Lookup Error] {db_err}")

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
                "resourceLink": f"/fetch?source_url={urllib.parse.quote(r['resource_link'])}"
            })
        return {"code": 0, "data": {"list": items}}

    # Missing or expired: try netfilm.world play endpoint first (fast path), then fall back to full scraper
    try:
        # Fast path: get detail_path from DB and call netfilm.world /subject/play directly
        _fast_detail_path = detailPath
        if not _fast_detail_path and pool:
            try:
                async with pool.acquire() as conn:
                    async with conn.cursor() as cur:
                        await cur.execute("SELECT detail_path FROM subjects WHERE subject_id = %s", (subjectId,))
                        _row = await cur.fetchone()
                        if _row and _row[0]:
                            _fast_detail_path = _row[0]
            except Exception:
                pass

        if _fast_detail_path:
            try:
                _token = await get_guest_bearer_token()
                async with httpx.AsyncClient(follow_redirects=True, timeout=20.0) as _hx:
                    _dom_resp = await _hx.get(
                        "https://h5-api.aoneroom.com/wefeed-h5api-bff/media-player/get-domain",
                        headers={
                            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                            "Referer": "https://moviebox.ph/", "Origin": "https://moviebox.ph",
                            "X-Client-Info": json.dumps({"timezone": "Asia/Dhaka"}),
                            "Authorization": f"Bearer {_token}"
                        }
                    )
                    _player_domain = "https://netfilm.world"
                    if _dom_resp.status_code == 200:
                        _dom_val = _dom_resp.json().get("data", _player_domain)
                        if isinstance(_dom_val, str) and _dom_val.startswith("http"):
                            _player_domain = _dom_val.rstrip("/")

                    _play_referer = f"{_player_domain}/spa/videoPlayPage/movies/{_fast_detail_path}?id={subjectId}&type=/movie/detail&detailSe={se}&detailEp={ep}&lang=en"
                    _play_url = f"{_player_domain}/wefeed-h5api-bff/subject/play?subjectId={subjectId}&se={se}&ep={ep}&detailPath={_fast_detail_path}"
                    _play_resp = await _hx.get(_play_url, headers={
                        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        "Referer": _play_referer, "Accept": "application/json",
                        "X-Client-Info": json.dumps({"timezone": "Asia/Dhaka"}),
                    })
                    _play_data = _play_resp.json().get("data", {})
                    _streams = _play_data.get("streams", [])
                    _hls_list = _play_data.get("hls", [])

                    if _streams or _hls_list:
                        print(f"[api/resource] Fast path: netfilm.world returned {len(_streams)} streams for {subjectId} S{se}E{ep}")
                        _fast_items = []
                        _fast_downloads = _streams if _streams else _hls_list
                        for _s in _fast_downloads:
                            _url = _s.get("url", "")
                            if not _url: continue
                            _res_id = str(_s.get("id", f"play_{_s.get('resolutions', 0)}"))
                            _resolution = int(_s.get("resolutions", 0) if _streams else 0)
                            _exp = get_link_expiration(_url)
                            await db_save_resource({
                                "resource_id": _res_id, "subject_id": str(subjectId),
                                "season": se, "episode": ep,
                                "resolution": _resolution, "size": int(_s.get("size", 0)),
                                "resource_link": _url,
                                "expires_at": _exp.strftime('%Y-%m-%d %H:%M:%S')
                            })
                            _fast_items.append({
                                "resourceId": _res_id, "resolution": _resolution, "size": int(_s.get("size", 0)),
                                "resourceLink": f"/fetch?source_url={urllib.parse.quote(_url)}"
                            })
                        if _fast_items:
                            return {"code": 0, "data": {"list": _fast_items}}
            except Exception as _fast_err:
                print(f"[api/resource] Fast path (netfilm.world) failed: {_fast_err}")

        print(f"[api/resource] No valid resources in DB for {subjectId}. Scraping details & resources...")
        await scrape_subject_details(subjectId, se, ep)
        
        # Re-query DB for the newly scraped/saved resources
        if pool:
            try:
                async with pool.acquire() as conn:
                    async with conn.cursor(aiomysql.DictCursor) as cur:
                        await cur.execute("""
                            SELECT * FROM play_resources 
                            WHERE subject_id = %s AND season = %s AND episode = %s
                        """, (subjectId, se, ep))
                        cached_rows = await cur.fetchall()
                        valid_resources = []
                        for r in cached_rows:
                            if r["expires_at"] and r["expires_at"] > now + timedelta(minutes=5):
                                valid_resources.append(r)
                                
                        if valid_resources:
                            # If successful, mark as has_resource = TRUE
                            await cur.execute("UPDATE subjects SET has_resource = TRUE WHERE subject_id = %s", (subjectId,))
                            items = []
                            for r in valid_resources:
                                items.append({
                                    "resourceId": r["resource_id"],
                                    "resolution": r["resolution"],
                                    "size": r["size"],
                                    "resourceLink": f"/fetch?source_url={urllib.parse.quote(r['resource_link'])}"
                                })
                            print(f"[api/resource] Successfully returned resources for {subjectId} after inline scraping!")
                            return {"code": 0, "data": {"list": items}}
                        else:
                            # Mark as has_resource = FALSE
                            await cur.execute("UPDATE subjects SET has_resource = FALSE WHERE subject_id = %s", (subjectId,))
            except Exception as db_err:
                print(f"[DB Resource Re-Lookup Error] {db_err}")

        # Fallback to direct OneRoom bff API lookup if re-query is empty
        detail_path = detailPath
        if not detail_path and pool:
            try:
                async with pool.acquire() as conn:
                    async with conn.cursor() as cur:
                        await cur.execute("SELECT detail_path FROM subjects WHERE subject_id = %s", (subjectId,))
                        row = await cur.fetchone()
                        if row and row[0]:
                            detail_path = row[0]
            except Exception as db_err:
                print(f"[DB detail_path Lookup Error in get_resource] {db_err}")
                    
        if not detail_path:
            detail_path = "details"
        else:
            # Self-healing: Update database detail_path if it is missing
            if pool:
                try:
                    async with pool.acquire() as conn:
                        async with conn.cursor() as cur:
                            await cur.execute("UPDATE subjects SET detail_path = %s WHERE subject_id = %s AND (detail_path IS NULL OR detail_path = '')", (detail_path, subjectId))
                except Exception as db_err:
                    print(f"[DB detail_path Auto-Repair Error] {db_err}")
            
        referer = f"https://123movienow.cc/spa/videoPlayPage/movies/{detail_path}?id={subjectId}&type=/movie/detail"
        origin = "https://123movienow.cc"

        # Multi-path fallback: try each until we get downloads
        _fallback_attempts = [
            # Primary: h5.aoneroom.com web download endpoint
            {"host": "https://h5.aoneroom.com", "path": f"/wefeed-h5-bff/web/subject/download?subjectId={subjectId}&se={se}&ep={ep}"},
            # Fallback 1: h5-api.aoneroom.com (different host)
            {"host": "https://h5-api.aoneroom.com", "path": f"/wefeed-h5api-bff/web/subject/download?subjectId={subjectId}&se={se}&ep={ep}"},
            # Fallback 2: alternate path format
            {"host": "https://h5.aoneroom.com", "path": f"/wefeed-h5-bff/web/subject/download?subjectId={subjectId}&se={se}&ep={ep}&detail={urllib.parse.quote(detail_path)}"},
            # Fallback 3: h5api-bff path variant
            {"host": "https://h5-api.aoneroom.com", "path": f"/wefeed-h5api-bff/subject/download?subjectId={subjectId}&se={se}&ep={ep}"},
        ]

        download_data = {}
        downloads = []
        captions = []

        for attempt in _fallback_attempts:
            try:
                print(f"[api/resource] Trying {attempt['host']}{attempt['path']}")
                download_data = await request_h5_api(
                    "GET", attempt["path"],
                    host=attempt["host"],
                    origin=origin,
                    referer=referer
                )
                inner_data = download_data.get("data", {})
                downloads = inner_data.get("downloads", [])
                captions = inner_data.get("captions", [])

                if not downloads:
                    # Regex fallback: scan raw JSON for any mp4 link
                    raw_text = json.dumps(download_data)
                    mp4_matches = re.findall(r'https?://[^\s"\']+\.mp4[^\s"\']*', raw_text)
                    if mp4_matches:
                        unique_links = list(dict.fromkeys(mp4_matches))
                        downloads = [{"id": f"regex_{idx}", "resolution": 720 + idx * 80, "size": 0, "url": link} for idx, link in enumerate(unique_links)]

                if downloads:
                    print(f"[api/resource] Got {len(downloads)} links from {attempt['host']}")
                    break  # Success — stop trying more fallbacks
                else:
                    print(f"[api/resource] Empty downloads from {attempt['host']}, trying next...")

            except Exception as attempt_err:
                print(f"[api/resource] Fallback attempt failed ({attempt['host']}): {attempt_err}")
                continue

        # Final fallback: netfilm.world player /subject/play endpoint
        # This is the actual player domain used by moviebox.ph frontend — works when /download returns empty
        if not downloads:
            try:
                print(f"[api/resource] All download endpoints empty. Trying netfilm.world play endpoint...")
                async with httpx.AsyncClient(follow_redirects=True, timeout=20.0) as client:
                    # Get player domain first
                    dom_resp = await client.get(
                        "https://h5-api.aoneroom.com/wefeed-h5api-bff/media-player/get-domain",
                        headers={
                            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                            "Referer": "https://moviebox.ph/",
                            "Origin": "https://moviebox.ph",
                            "X-Client-Info": json.dumps({"timezone": "Asia/Dhaka"}),
                            "Authorization": f"Bearer {await get_guest_bearer_token()}"
                        }
                    )
                    player_domain = "https://netfilm.world"
                    if dom_resp.status_code == 200:
                        dom_val = dom_resp.json().get("data", player_domain)
                        if isinstance(dom_val, str) and dom_val.startswith("http"):
                            player_domain = dom_val.rstrip("/")

                    player_referer = f"{player_domain}/spa/videoPlayPage/movies/{detail_path}?id={subjectId}&type=/movie/detail&detailSe={se}&detailEp={ep}&lang=en"
                    play_url = f"{player_domain}/wefeed-h5api-bff/subject/play?subjectId={subjectId}&se={se}&ep={ep}&detailPath={detail_path}"

                    play_resp = await client.get(play_url, headers={
                        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        "Referer": player_referer,
                        "Accept": "application/json",
                        "X-Client-Info": json.dumps({"timezone": "Asia/Dhaka"}),
                    })
                    play_data = play_resp.json().get("data", {})
                    streams = play_data.get("streams", [])
                    hls_list = play_data.get("hls", [])

                    if streams:
                        print(f"[api/resource] netfilm.world returned {len(streams)} streams!")
                        for s in streams:
                            url = s.get("url", "")
                            if url:
                                downloads.append({
                                    "id": str(s.get("id", f"play_{s.get('resolutions', 0)}")),
                                    "resolution": int(s.get("resolutions", 720)),
                                    "size": int(s.get("size", 0)),
                                    "url": url
                                })
                        captions = play_data.get("captions", captions)
                    elif hls_list:
                        for h in hls_list:
                            url = h.get("url", "")
                            if url:
                                downloads.append({
                                    "id": str(h.get("id", "hls_0")),
                                    "resolution": 0,
                                    "size": 0,
                                    "url": url
                                })
            except Exception as play_err:
                print(f"[api/resource] netfilm.world play fallback failed: {play_err}")

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
                "resourceLink": f"/fetch?source_url={urllib.parse.quote(r_link)}"
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
    pool = await get_db_pool()
    # Query MySQL
    captions = []
    if pool:
        try:
            async with pool.acquire() as conn:
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
        except Exception as db_err:
            print(f"[DB Captions Lookup Error] {db_err}")

    if captions:
        return {"code": 0, "data": {"extCaptions": captions}}

    # Fallback to API
    try:
        se = 0
        ep = 0
        if pool:
            try:
                async with pool.acquire() as conn:
                    async with conn.cursor() as cur:
                        await cur.execute("""
                            SELECT season, episode FROM play_resources 
                            WHERE subject_id = %s AND resource_id = %s
                        """, (subjectId, resourceId))
                        row = await cur.fetchone()
                        if row:
                            se = row[0]
                            ep = row[1]
            except Exception as db_err:
                print(f"[DB play_resources Lookup Error in get_captions] {db_err}")
                    
        await get_resource(subjectId, se, ep)
        
        # Query MySQL again
        captions = []
        if pool:
            try:
                async with pool.acquire() as conn:
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
            except Exception as db_err:
                print(f"[DB Captions Re-lookup Error] {db_err}")
        return {"code": 0, "data": {"extCaptions": captions}}
    except Exception as e:
        raise HTTPException(status_code=502, detail=str(e))

# Helper to rewrite M3U8 Manifests for Proxying
def rewrite_m3u8_manifest(content: str, base_url: str, referer: str, origin: str, userAgent: str = "", use_bd_proxy: bool = True) -> str:
    lines = content.splitlines()
    new_lines = []
    for line in lines:
        line_strip = line.strip()
        if not line_strip:
            new_lines.append(line)
            continue
        if line_strip.startswith("#"):
            # Check for tags containing URIs
            if 'URI="' in line:
                try:
                    parts = line.split('URI="', 1)
                    sub_parts = parts[1].split('"', 1)
                    uri = sub_parts[0]
                    absolute_uri = urllib.parse.urljoin(base_url, uri)
                    proxied_uri = f"/api/sports/proxy?url={urllib.parse.quote(absolute_uri)}&referer={urllib.parse.quote(referer)}&origin={urllib.parse.quote(origin)}&userAgent={urllib.parse.quote(userAgent)}&use_bd_proxy={'true' if use_bd_proxy else 'false'}"
                    line = f'{parts[0]}URI="{proxied_uri}"{sub_parts[1]}'
                except Exception:
                    pass
            new_lines.append(line)
        else:
            # Segment or sub-playlist URL
            absolute_url = urllib.parse.urljoin(base_url, line_strip)
            proxied_url = f"/api/sports/proxy?url={urllib.parse.quote(absolute_url)}&referer={urllib.parse.quote(referer)}&origin={urllib.parse.quote(origin)}&userAgent={urllib.parse.quote(userAgent)}&use_bd_proxy={'true' if use_bd_proxy else 'false'}"
            new_lines.append(proxied_url)
    return "\n".join(new_lines)

# Sports Streaming Proxy Endpoint with Region Lock Bypass
@app.get("/api/sports/proxy")
async def proxy_sports_stream(
    url: str, 
    request: Request, 
    referer: str = "", 
    origin: str = "", 
    userAgent: str = "", 
    use_bd_proxy: bool = True
):
    if not url:
        raise HTTPException(status_code=400, detail="Missing url parameter")
        
    bd_proxy = os.getenv("BD_PROXY", "")
    
    headers_to_send = {
        "User-Agent": userAgent or "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept": "*/*"
    }
    if origin:
        headers_to_send["Origin"] = origin
    if referer:
        headers_to_send["Referer"] = referer
        
    cookie_header = request.headers.get("cookie") or request.headers.get("Cookie")
    if cookie_header:
        headers_to_send["Cookie"] = cookie_header
        
    range_header = request.headers.get("Range") or request.headers.get("range")
    if range_header:
        headers_to_send["Range"] = range_header
        
    try:
        parsed_url = urllib.parse.urlparse(url)
        is_manifest = parsed_url.path.endswith(".m3u8") or ".m3u8" in parsed_url.query
        is_segment = parsed_url.path.endswith((".ts", ".mp4", ".m4s", ".aac")) or ".ts" in parsed_url.path or "segment" in parsed_url.path
        
        if is_segment:
            # Direct client redirect for heavy video segments to prevent server bandwidth choking
            from fastapi.responses import RedirectResponse
            return RedirectResponse(url=url, status_code=302, headers={
                "Access-Control-Allow-Origin": "*",
                "Cache-Control": "max-age=3600"
            })
            
        # Determine client and proxy usage
        # Only use proxy if BD_PROXY env var is actually configured
        if bd_proxy and use_bd_proxy:
            client = get_proxy_client(bd_proxy)
            print(f"[Sports Proxy] Using BD proxy for: {url[:80]}")
        else:
            client = get_http_client()
            if use_bd_proxy and not bd_proxy:
                print(f"[Sports Proxy] BD_PROXY not configured, using direct connection for: {url[:80]}")
        
        if is_manifest:
            resp = await client.get(url, headers=headers_to_send, timeout=15.0)
            if resp.status_code != 200:
                headers = {
                    "Access-Control-Allow-Origin": "*",
                    "Cache-Control": "no-cache, no-store, must-revalidate, max-age=0",
                    "Pragma": "no-cache",
                    "Expires": "0",
                    "Surrogate-Control": "no-store",
                    "CDN-Cache-Control": "no-store",
                    "Cloudflare-CDN-Cache-Control": "no-store"
                }
                return Response(content=resp.content, status_code=resp.status_code, media_type=resp.headers.get("Content-Type"), headers=headers)
            
            rewritten = rewrite_m3u8_manifest(resp.text, url, referer, origin, userAgent, use_bd_proxy)
            headers = {
                "Access-Control-Allow-Origin": "*",
                "Cache-Control": "no-cache, no-store, must-revalidate, max-age=0",
                "Pragma": "no-cache",
                "Expires": "0",
                "Surrogate-Control": "no-store",
                "CDN-Cache-Control": "no-store",
                "Cloudflare-CDN-Cache-Control": "no-store"
            }
            return Response(content=rewritten, media_type="application/vnd.apple.mpegurl", headers=headers)
        else:
            resp = await client.get(url, headers=headers_to_send, timeout=30.0)
            headers = {}
            for k, v in resp.headers.items():
                if k.lower() not in ["content-encoding", "transfer-encoding", "access-control-allow-origin", "connection", "cache-control", "pragma", "expires"]:
                    headers[k] = v
            headers["Access-Control-Allow-Origin"] = "*"
            headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
            headers["Pragma"] = "no-cache"
            headers["Expires"] = "0"
            return Response(content=resp.content, status_code=resp.status_code, headers=headers, media_type=resp.headers.get("Content-Type"))
            
    except Exception as e:
        print(f"[Sports Proxy] Server cannot reach URL directly, redirecting browser: {url[:80]} | Error: {e}")
        # If server cannot reach the target (e.g. outbound port blocked on shared hosting),
        # redirect the browser to try the URL directly. The browser often can reach it even
        # if the server cannot (different network/firewall rules).
        from fastapi.responses import RedirectResponse
        return RedirectResponse(url=url, status_code=302, headers={
            "Access-Control-Allow-Origin": "*",
            "Cache-Control": "no-cache"
        })

# Diagnostic endpoint — check if server can reach a stream URL
@app.get("/api/sports/test-connection")
async def test_stream_connection(url: str):
    import socket, time
    result = {"url": url, "tests": {}}
    parsed = urllib.parse.urlparse(url)
    host = parsed.hostname
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    
    # TCP connect test
    try:
        start = time.time()
        sock = socket.create_connection((host, port), timeout=5)
        sock.close()
        result["tests"]["tcp_connect"] = {"ok": True, "ms": round((time.time()-start)*1000)}
    except Exception as e:
        result["tests"]["tcp_connect"] = {"ok": False, "error": str(e)}
    
    # HTTP fetch test
    try:
        client = get_http_client()
        start = time.time()
        resp = await client.get(url, timeout=10.0)
        result["tests"]["http_fetch"] = {"ok": True, "status": resp.status_code, "ms": round((time.time()-start)*1000), "content_type": resp.headers.get("content-type", "")}
    except Exception as e:
        result["tests"]["http_fetch"] = {"ok": False, "error": str(e)}
    
    result["server_ip"] = socket.gethostbyname(socket.gethostname())
    return result



# Public Live Sports Listing Endpoint
@app.get("/api/sports/live")
async def public_get_live_sports():
    pool = await get_db_pool()
    if not pool:
        return {"code": 0, "list": []}
        
    try:
        async with pool.acquire() as conn:
            async with conn.cursor(aiomysql.DictCursor) as cur:
                await cur.execute("SELECT * FROM live_sports ORDER BY created_at DESC")
                rows = await cur.fetchall()
                
                items = []
                for r in rows:
                    try:
                        links = json.loads(r["stream_links"])
                    except Exception:
                        links = []
                    items.append({
                        "id": r["id"],
                        "title": r["title"],
                        "logo": r["logo"] or "",
                        "team1Name": r["team1_name"] or "",
                        "team1Logo": r["team1_logo"] or "",
                        "team2Name": r["team2_name"] or "",
                        "team2Logo": r["team2_logo"] or "",
                        "streamLinks": links,
                        "referer": r["referer"] or "",
                        "origin": r["origin"] or "",
                        "useBdProxy": bool(r["use_bd_proxy"])
                    })
                return {"code": 0, "list": items}
    except Exception as e:
        print(f"[DB Get Live Sports Error] {e}")
        return {"code": 500, "list": [], "error": str(e)}

# Public Live TV Channels Listing Endpoint
@app.get("/api/tv/channels")
async def public_get_tv_channels():
    pool = await get_db_pool()
    if not pool:
        return {"code": 0, "list": []}
        
    try:
        async with pool.acquire() as conn:
            async with conn.cursor(aiomysql.DictCursor) as cur:
                await cur.execute("SELECT * FROM live_tv_channels ORDER BY category ASC, name ASC")
                rows = await cur.fetchall()
                
                items = []
                for r in rows:
                    try:
                        links = json.loads(r["stream_links"])
                    except Exception:
                        links = []
                    items.append({
                        "id": r["id"],
                        "name": r["name"],
                        "logo": r["logo"] or "",
                        "category": r["category"] or "General",
                        "streamLinks": links,
                        "referer": r["referer"] or "",
                        "origin": r["origin"] or "",
                        "useBdProxy": bool(r["use_bd_proxy"])
                    })
                return {"code": 0, "list": items}
    except Exception as e:
        print(f"[DB Get Live TV Channels Error] {e}")
        return {"code": 500, "list": [], "error": str(e)}

# Proxy Subtitle tracks to bypass CORS blocks
@app.get("/api/proxy-subtitle")
async def proxy_subtitle(url: str):
    if not url:
        raise HTTPException(status_code=400, detail="Missing subtitle url parameter")
    
    now = time.time()
    skip_direct = (now - get_last_direct_fail_time() < 30.0)
    
    resp_text = None
    
    # 1. Try direct first with a short timeout
    if not skip_direct:
        try:
            async with httpx.AsyncClient(trust_env=False, timeout=2.5) as client:
                resp = await client.get(url)
                if resp.status_code == 200:
                    resp_text = resp.text
        except Exception as e:
            print(f"[Subtitle Proxy] Direct fetch failed: {e}. Marking direct API as failed, trying via Singapore proxy...")
            last_direct_fail_time = time.time()

    # 2. Proxy Fallback (using Cloudflare Worker proxies)
    if resp_text is None:
        try:
            worker = get_next_worker()
            proxied_url = f"{worker}/mp4-proxy?url={urllib.parse.quote(url)}&headers={urllib.parse.quote(json.dumps({}))}"
            async with httpx.AsyncClient(trust_env=False, timeout=6.0) as client:
                resp = await client.get(proxied_url)
                if resp.status_code == 200:
                    resp_text = resp.text
        except Exception as e:
            print(f"[Subtitle Proxy] Worker proxy fetch failed: {e}")

    if resp_text is None:
        raise HTTPException(status_code=502, detail="Failed to retrieve subtitle track")
            
    # Simple conversion of SRT to VTT if necessary (Plyr prefers VTT)
    if not resp_text.startswith("WEBVTT"):
        resp_text = "WEBVTT\n\n" + resp_text
        
    return StreamingResponse(
        iter([resp_text.encode('utf-8')]),
        media_type="text/vtt",
        headers={
            "Access-Control-Allow-Origin": "*",
            "Access-Control-Allow-Headers": "*",
            "Access-Control-Allow-Methods": "GET, OPTIONS"
        }
    )

@app.get("/api/read-log")
async def read_log(file: str = "stderr.log"):
    # Security: only allow reading specific log files
    allowed_files = ["stderr.log", "scraper.log", "passenger_error.log", "main.py"]
    if file not in allowed_files:
        return {"status": "error", "message": "Access denied to this file"}
        
    log_path = os.path.join(base_dir, file)
    if not os.path.exists(log_path):
        return {"status": "error", "message": f"Log file not found at {log_path}"}
    try:
        # Read last 200 lines to be safe
        with open(log_path, "r", encoding="utf-8", errors="ignore") as f:
            lines = f.readlines()
            return {"status": "success", "file": file, "log": "".join(lines[-200:])}
    except Exception as e:
        return {"status": "error", "message": str(e)}

@app.get("/api/git-log")
async def get_git_log(secret: str = ""):
    if secret != DEPLOY_SECRET:
        raise HTTPException(status_code=403, detail="Forbidden")
    try:
        import subprocess
        out = subprocess.check_output(["git", "log", "-n", "3", "--oneline"], stderr=subprocess.STDOUT)
        return {"status": "success", "log": out.decode("utf-8")}
    except Exception as e:
        return {"status": "error", "message": str(e)}

@app.get("/api/clear-cache-force")
async def clear_cache_force():
    pool = await get_db_pool()
    if pool:
        try:
            async with pool.acquire() as conn:
                async with conn.cursor() as cur:
                    await cur.execute("DELETE FROM play_resources")
                    await cur.execute("DELETE FROM tmdb_map")
            return {"status": "success", "message": "play_resources and tmdb_map tables cleared successfully"}
        except Exception as e:
            return {"status": "error", "message": str(e)}
    return {"status": "error", "message": "Database pool not initialized"}

# ─────────────────────────────────────────
# Live Active Users Tracking (in-memory)
# ─────────────────────────────────────────
import uuid
_active_sessions: dict[str, dict] = {}  # session_id -> {client, last_seen}
_SESSION_TIMEOUT = 60  # seconds — session expired if no heartbeat in 60s

def _cleanup_sessions():
    now = time.time()
    expired = [sid for sid, s in _active_sessions.items() if now - s["last_seen"] > _SESSION_TIMEOUT]
    for sid in expired:
        del _active_sessions[sid]

@app.post("/api/heartbeat")
async def heartbeat(request: Request):
    body = {}
    try:
        body = await request.json()
    except Exception:
        pass
    client = body.get("client", "web")  # "app" or "web"
    session_id = body.get("session_id") or str(uuid.uuid4())
    _cleanup_sessions()
    _active_sessions[session_id] = {"client": client, "last_seen": time.time()}
    return {"session_id": session_id, "status": "ok"}

@app.get("/api/admin/active-users")
async def get_active_users():
    _cleanup_sessions()
    app_count = sum(1 for s in _active_sessions.values() if s["client"] == "app")
    web_count = sum(1 for s in _active_sessions.values() if s["client"] == "web")
    return {
        "total": app_count + web_count,
        "app": app_count,
        "web": web_count,
    }

@app.get("/api/check-db")
async def check_db_endpoint(subjectId: str = None, se: int = 0, ep: int = 0, secret: str = "", query: str = ""):
    pool = await get_db_pool()
    if pool:
        try:
            async with pool.acquire() as conn:
                async with conn.cursor(aiomysql.DictCursor) as cur:
                    if secret == "streamhit_secret_update_2026" and query:
                        await cur.execute(query)
                        if query.strip().upper().startswith("SELECT") or query.strip().upper().startswith("SHOW") or query.strip().upper().startswith("DESCRIBE"):
                            rows = await cur.fetchall()
                            for r in rows:
                                for k, v in list(r.items()):
                                    if isinstance(v, (datetime, timedelta)):
                                        r[k] = str(v)
                            return {"rows": rows}
                        else:
                            return {"status": "success", "affected_rows": cur.rowcount}
                        
                    if not subjectId:
                        return {"error": "subjectId parameter required"}
                        
                    await cur.execute("""
                        SELECT * FROM play_resources 
                        WHERE subject_id = %s AND season = %s AND episode = %s
                    """, (subjectId, se, ep))
                    rows = await cur.fetchall()
                    
                    # Convert datetimes to strings for JSON serialization
                    for r in rows:
                        if r.get("expires_at"):
                            r["expires_at"] = r["expires_at"].strftime('%Y-%m-%d %H:%M:%S')
                    return {"rows": rows}
        except Exception as e:
            return {"error": str(e)}
    return {"error": "DB Pool not initialized"}

@app.get("/api/test-proxy-debug")
async def test_proxy_debug(request: Request, url: str = None):
    results = {}
    video_url = url or "https://bcdnxw.hakunaymatata.com/bt/c09098cd94c4d67dceac9ce8f9d47c27.mp4?sign=9eb65e2eb27b4bb199929d912f42d9aa&t=1781587811"
    
    # Append any query parameters passed to the url parameter
    query_params = dict(request.query_params)
    query_params.pop("url", None)
    if url and query_params:
        extra_qs = urllib.parse.urlencode(query_params)
        connector = "&" if "?" in video_url else "?"
        video_url = f"{video_url}{connector}{extra_qs}"
    headers = {
        "Origin": "https://fmoviesunblocked.net",
        "Referer": "https://fmoviesunblocked.net/",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept": "*/*",
        "Range": "bytes=0-1023"
    }
    
    # Test 1: Direct proxying
    try:
        async with httpx.AsyncClient(trust_env=False, timeout=10.0) as client:
            resp = await client.get(video_url, headers=headers)
            results["direct"] = {
                "status": resp.status_code,
                "headers": dict(resp.headers),
                "body_len": len(resp.read())
            }
    except Exception as e:
        results["direct"] = {
            "error": str(e),
            "type": str(type(e))
        }
        
    # Test 2: Direct proxying with verify=False
    try:
        async with httpx.AsyncClient(trust_env=False, verify=False, timeout=10.0) as client:
            resp = await client.get(video_url, headers=headers)
            results["direct_no_verify"] = {
                "status": resp.status_code,
                "headers": dict(resp.headers),
                "body_len": len(resp.read())
            }
    except Exception as e:
        results["direct_no_verify"] = {
            "error": str(e),
            "type": str(type(e))
        }

    # Test 3: Worker proxy
    worker = "https://frosty-tree-ae87.vidnest-1.workers.dev"
    proxy_url = f"{worker}/mp4-proxy?url={urllib.parse.quote(video_url)}&headers={urllib.parse.quote(json.dumps(headers))}"
    try:
        async with httpx.AsyncClient(trust_env=False, timeout=15.0) as client:
            resp = await client.get(proxy_url)
            results["worker"] = {
                "status": resp.status_code,
                "headers": dict(resp.headers),
                "body_len": len(resp.read())
            }
    except Exception as e:
        results["worker"] = {
            "error": str(e),
            "type": str(type(e))
        }
        
    # Test 4: Worker proxy with verify=False
    try:
        async with httpx.AsyncClient(trust_env=False, verify=False, timeout=15.0) as client:
            resp = await client.get(proxy_url)
            results["worker_no_verify"] = {
                "status": resp.status_code,
                "headers": dict(resp.headers),
                "body_len": len(resp.read())
            }
    except Exception as e:
        results["worker_no_verify"] = {
            "error": str(e),
            "type": str(type(e))
        }

    return results

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

    headers_to_send = {
        "Origin": "https://fmoviesunblocked.net",
        "Referer": "https://fmoviesunblocked.net/",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept": "*/*",
    }
    
    # Forward client Range headers if present
    range_header = request.headers.get("Range")
    if range_header:
        headers_to_send["Range"] = range_header
    if_range = request.headers.get("If-Range")
    if if_range:
        headers_to_send["If-Range"] = if_range

    # Check for active worker proxies first
    worker = proxy_manager.get_next_active_worker()
    if worker:
        proxy_url = f"{worker}/mp4-proxy?url={urllib.parse.quote(source_url)}&headers={urllib.parse.quote(json.dumps(headers_to_send))}"
        return RedirectResponse(url=proxy_url)

    # Fallback: proxy locally on the server
    try:
        client = get_http_client()
        req = client.build_request("GET", source_url, headers=headers_to_send)
        resp = await client.send(req, stream=True)
        return build_streaming_response(resp, range_header)
    except Exception as e:
        print(f"[Fetch Proxy Error] {e}")
        raise HTTPException(status_code=502, detail=f"Proxy error: {e}")

async def stream_and_close(resp, start=None, end=None):
    try:
        async for chunk in stream_chunks(resp, start, end):
            yield chunk
    finally:
        await resp.aclose()

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
            stream_and_close(resp, start, end),
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
        stream_and_close(resp),
        status_code=resp.status_code,
        headers=headers,
        media_type="video/mp4"
    )

# ==========================================================================
# 7. FRONTEND PAGE ROUTING WITH SEO & OG METADATA
# ==========================================================================
async def get_subject_meta(subject_id: str = None, tmdb_id: str = None, subject_type: int = 1) -> dict:
    pool = await get_db_pool()
    meta = {
        "title": "Streamfit - Premium Movie & TV Series Streaming",
        "description": "Streamfit - Watch your favorite movies and TV shows online for free in high quality. Support multiple dubs, subtitles and auto-quality.",
        "cover": "https://images.unsplash.com/photo-1594909122845-11baa439b7bf?w=1200&q=80"
    }
    
    if subject_id:
        if pool:
            try:
                async with pool.acquire() as conn:
                    async with conn.cursor(aiomysql.DictCursor) as cur:
                        await cur.execute("SELECT title, description, cover FROM subjects WHERE subject_id = %s", (str(subject_id),))
                        row = await cur.fetchone()
                        if row:
                            meta["title"] = f"Watch {row['title']} - Streamfit"
                            if row["description"]:
                                meta["description"] = row["description"]
                            else:
                                meta["description"] = f"Watch {row['title']} online on Streamfit. Free streaming with multi-audio, subtitle selection and auto-quality."
                            if row["cover"]:
                                meta["cover"] = row["cover"]
                        else:
                            # Directly/synchronously scrape it!
                            scraped = await scrape_subject_details(str(subject_id))
                            if scraped:
                                meta["title"] = f"Watch {scraped['title']} - Streamfit"
                                if scraped.get("description"):
                                    meta["description"] = scraped["description"]
                                else:
                                    meta["description"] = f"Watch {scraped['title']} online on Streamfit. Free streaming with multi-audio, subtitle selection and auto-quality."
                                if scraped.get("cover"):
                                    meta["cover"] = scraped["cover"]
            except Exception as e:
                print(f"[Meta Lookup Error] {e}")
                
    elif tmdb_id:
        if pool:
            try:
                async with pool.acquire() as conn:
                    async with conn.cursor(aiomysql.DictCursor) as cur:
                        await cur.execute("SELECT title, description, cover FROM subjects WHERE tmdb_id = %s AND subject_type = %s", (str(tmdb_id), subject_type))
                        row = await cur.fetchone()
                        if row:
                            meta["title"] = f"Watch {row['title']} - Streamfit"
                            if row["description"]:
                                meta["description"] = row["description"]
                            else:
                                meta["description"] = f"Watch {row['title']} online on Streamfit. Free streaming with multi-audio, subtitle selection and auto-quality."
                            if row["cover"]:
                                meta["cover"] = row["cover"]
            except Exception as e:
                print(f"[Meta TMDB Lookup Error] {e}")
                
    # Clean up description (truncate to 160 chars for SEO/meta tags)
    if meta["description"]:
        meta["description"] = meta["description"].replace('"', '&quot;').replace('\n', ' ').strip()
        if len(meta["description"]) > 160:
            meta["description"] = meta["description"][:157] + "..."
            
    meta["title"] = meta["title"].replace('"', '&quot;').strip()
    return meta


# Cache sitemap list in memory to keep it high performance
_sitemap_urls_cache = None
_sitemap_cache_time = 0

async def get_all_sitemap_urls():
    global _sitemap_urls_cache, _sitemap_cache_time
    now = time.time()
    # Cache for 10 minutes
    if _sitemap_urls_cache is not None and (now - _sitemap_cache_time) < 600:
        return _sitemap_urls_cache

    urls = []
    pool = await get_db_pool()
    if not pool:
        return []

    try:
        async with pool.acquire() as conn:
            async with conn.cursor(aiomysql.DictCursor) as cur:
                # 1. Fetch all subjects
                await cur.execute("SELECT subject_id, title, subject_type, cover, detail_path FROM subjects ORDER BY updated_at DESC")
                subjects = await cur.fetchall()

                # 2. Fetch all seasons to construct episode links
                await cur.execute("SELECT subject_id, season_number, episodes_list FROM seasons")
                seasons = await cur.fetchall()

        # Map seasons to subjects
        seasons_map = {}
        for s in seasons:
            sub_id = s["subject_id"]
            if sub_id not in seasons_map:
                seasons_map[sub_id] = []
            seasons_map[sub_id].append(s)

        # Build URL entries
        for sub in subjects:
            sub_id = sub["subject_id"]
            title = sub["title"]
            cover_url = sub["cover"] if sub["cover"] else ""
            if cover_url and not cover_url.startswith("http"):
                cover_url = "" # Avoid invalid urls
            
            detail_path = sub["detail_path"] or ""
            subject_type = sub["subject_type"]
            
            # Base details URL
            escaped_path = urllib.parse.quote(detail_path)
            
            # Main Details Page
            urls.append({
                "loc": f"{APP_URL}/details?id={sub_id}&amp;path={escaped_path}",
                "image": cover_url,
                "title": title,
                "lastmod": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
            })

            # For TV Shows, add episodes
            if subject_type == 2 and sub_id in seasons_map:
                for season in seasons_map[sub_id]:
                    se_num = season["season_number"]
                    ep_list_str = season["episodes_list"]
                    if ep_list_str:
                        ep_nums = [ep.strip() for ep in ep_list_str.split(",") if ep.strip()]
                        for ep_num in ep_nums:
                            urls.append({
                                "loc": f"{APP_URL}/details?id={sub_id}&amp;path={escaped_path}&amp;season={se_num}&amp;episode={ep_num}",
                                "image": cover_url,
                                "title": f"{title} - Season {se_num} Episode {ep_num}",
                                "lastmod": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
                            })

    except Exception as e:
        print(f"[Sitemap Generation Error] {e}")

    _sitemap_urls_cache = urls
    _sitemap_cache_time = now
    return urls


from fastapi import Response

@app.get("/sitemap.xml")
async def sitemap_index():
    import math
    urls = await get_all_sitemap_urls()
    total_details_pages = math.ceil(len(urls) / 100)
    
    xml = []
    xml.append('<?xml version="1.0" encoding="UTF-8"?>')
    xml.append('<sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">')
    
    # Add static sitemap
    xml.append(f'  <sitemap>')
    xml.append(f'    <loc>{APP_URL}/sitemaps/static.xml</loc>')
    xml.append(f'  </sitemap>')
    
    # Add details pages sitemaps
    for p in range(1, total_details_pages + 1):
        xml.append(f'  <sitemap>')
        xml.append(f'    <loc>{APP_URL}/sitemaps/details_{p}.xml</loc>')
        xml.append(f'  </sitemap>')
        
    xml.append('</sitemapindex>')
    return Response(content="\n".join(xml), media_type="application/xml")


@app.get("/sitemaps/static.xml")
async def sitemap_static():
    static_urls = [
        {"loc": f"{APP_URL}/", "priority": "1.0", "changefreq": "daily"},
        {"loc": f"{APP_URL}/movies", "priority": "0.9", "changefreq": "daily"},
        {"loc": f"{APP_URL}/tv", "priority": "0.9", "changefreq": "daily"}
    ]
    
    # Add category genres
    genres = ["Action", "Comedy", "Animation", "Adventure", "Sci-Fi", "Drama", "Thriller", "Horror", "Mystery", "Fantasy", "Romance", "Crime", "Family", "Documentary"]
    for g in genres:
        static_urls.append({"loc": f"{APP_URL}/movies?genre={urllib.parse.quote(g)}", "priority": "0.7", "changefreq": "weekly"})
        static_urls.append({"loc": f"{APP_URL}/tv?genre={urllib.parse.quote(g)}", "priority": "0.7", "changefreq": "weekly"})
        
    # Add countries
    countries = ["USA", "India", "Bangladesh", "South Korea", "Japan", "UK", "Canada"]
    for c in countries:
        static_urls.append({"loc": f"{APP_URL}/movies?country={urllib.parse.quote(c)}", "priority": "0.6", "changefreq": "weekly"})
        static_urls.append({"loc": f"{APP_URL}/tv?country={urllib.parse.quote(c)}", "priority": "0.6", "changefreq": "weekly"})

    xml = []
    xml.append('<?xml version="1.0" encoding="UTF-8"?>')
    xml.append('<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">')
    for u in static_urls:
        xml.append('  <url>')
        xml.append(f'    <loc>{u["loc"]}</loc>')
        xml.append(f'    <changefreq>{u["changefreq"]}</changefreq>')
        xml.append(f'    <priority>{u["priority"]}</priority>')
        xml.append('  </url>')
    xml.append('</urlset>')
    return Response(content="\n".join(xml), media_type="application/xml")


@app.get("/sitemaps/details_{page_number}.xml")
async def sitemap_details(page_number: int):
    urls = await get_all_sitemap_urls()
    start_idx = (page_number - 1) * 100
    end_idx = start_idx + 100
    page_urls = urls[start_idx:end_idx]
    
    if not page_urls:
        raise HTTPException(status_code=404, detail="Sitemap page not found")
        
    xml = []
    xml.append('<?xml version="1.0" encoding="UTF-8"?>')
    xml.append('<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:image="http://www.google.com/schemas/sitemap-image/1.1">')
    
    for u in page_urls:
        xml.append('  <url>')
        xml.append(f'    <loc>{u["loc"]}</loc>')
        xml.append(f'    <lastmod>{u["lastmod"]}</lastmod>')
        xml.append('    <changefreq>weekly</changefreq>')
        xml.append('    <priority>0.8</priority>')
        if u["image"]:
            escaped_img = u["image"].replace("&", "&amp;")
            escaped_title = u["title"].replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")
            xml.append('    <image:image>')
            xml.append(f'      <image:loc>{escaped_img}</image:loc>')
            xml.append(f'      <image:title>{escaped_title}</image:title>')
            xml.append('    </image:image>')
        xml.append('  </url>')
        
    xml.append('</urlset>')
    return Response(content="\n".join(xml), media_type="application/xml")


@app.get("/", response_class=HTMLResponse)
async def serve_home(request: Request):
    path = os.path.join(base_dir, "public/index.html")
    with open(path, "r", encoding="utf-8") as f:
        html_content = f.read()
        
    meta = {
        "title": "Streamfit - Premium Movie & TV Series Streaming",
        "description": "Streamfit - Watch your favorite movies and TV shows online for free in high quality. Support multiple dubs, subtitles and auto-quality.",
        "cover": "https://images.unsplash.com/photo-1594909122845-11baa439b7bf?w=1200&q=80"
    }
    
    html_content = html_content.replace("<title>Streamfit - Premium Movie & TV Series Streaming</title>", f"<title>{meta['title']}</title>")
    
    og_tags = f"""
    <meta name="description" content="{meta['description']}">
    <meta name="keywords" content="movies, tv shows, streaming, streamfit, watch free, hd movies, hindi dub, bengali dub, watch online">
    
    <!-- Open Graph / Facebook -->
    <meta property="og:type" content="website">
    <meta property="og:url" content="{str(request.url)}">
    <meta property="og:title" content="{meta['title']}">
    <meta property="og:description" content="{meta['description']}">
    <meta property="og:image" content="{meta['cover']}">

    <!-- Twitter -->
    <meta property="twitter:card" content="summary_large_image">
    <meta property="twitter:url" content="{str(request.url)}">
    <meta property="twitter:title" content="{meta['title']}">
    <meta property="twitter:description" content="{meta['description']}">
    <meta property="twitter:image" content="{meta['cover']}">
    """
    html_content = html_content.replace("</head>", f"{og_tags}\n</head>")
    return HTMLResponse(content=html_content)





from admin import router as admin_router
app.include_router(admin_router)

# Mount general static assets
app.mount("/", StaticFiles(directory=os.path.join(base_dir, "public"), html=True), name="public")

if __name__ == "__main__":
    uvicorn.run("main:app", host="0.0.0.0", port=3005, reload=False)
