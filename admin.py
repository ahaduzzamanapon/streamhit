import os
import re
import json
import httpx
import time
import asyncio
import secrets
import hashlib
import hmac
import urllib.parse
import aiomysql
from fastapi import APIRouter, Request, HTTPException, Response, UploadFile, File
from fastapi.responses import RedirectResponse, JSONResponse
from dotenv import load_dotenv

# Load env variables independently to prevent circular dependency loops
base_dir = os.path.dirname(os.path.abspath(__file__))
env_path = os.path.join(base_dir, ".env")
load_dotenv(env_path)

DB_HOST = os.getenv("DB_HOST", "127.0.0.1")
DB_PORT = int(os.getenv("DB_PORT", "3306"))
DB_USER = os.getenv("DB_USER", "root")
DB_PASSWORD = os.getenv("DB_PASSWORD", "")
DB_NAME = os.getenv("DB_NAME", "moviebox")
DEPLOY_SECRET = os.getenv("DEPLOY_SECRET", "streamhit_secret_update_2026")

router = APIRouter()

def generate_signed_token(username: str) -> str:
    expires = int(time.time()) + 86400  # 1 day
    payload = f"{username}:{expires}"
    sig = hmac.new(DEPLOY_SECRET.encode(), payload.encode(), hashlib.sha256).hexdigest()
    return f"{payload}.{sig}"

def verify_signed_token(token: str) -> str:
    if not token or "." not in token:
        return None
    try:
        payload, sig = token.rsplit(".", 1)
        expected_sig = hmac.new(DEPLOY_SECRET.encode(), payload.encode(), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(sig, expected_sig):
            return None
        username, expires_str = payload.split(":", 1)
        expires = int(expires_str)
        if time.time() > expires:
            return None
        return username
    except Exception:
        return None

def check_admin_auth(request: Request):
    token = request.headers.get("Authorization")
    if token and token.startswith("Bearer "):
        token = token[7:]
    else:
        token = request.cookies.get("admin_token")
        
    username = verify_signed_token(token)
    if not username:
        raise HTTPException(status_code=401, detail="Unauthorized")
        
    return {"username": username}

@router.post("/api/admin/login")
async def admin_login(data: dict, response: Response):
    username = data.get("username")
    password = data.get("password")
    
    if not username or not password:
        raise HTTPException(status_code=400, detail="Username and password are required")
        
    from main import get_db_pool
    pool = await get_db_pool()
    if not pool:
        raise HTTPException(status_code=500, detail="Database pool not available")
        
    async with pool.acquire() as conn:
        async with conn.cursor(aiomysql.DictCursor) as cur:
            await cur.execute("SELECT * FROM admins WHERE username = %s", (username,))
            admin = await cur.fetchone()
            
    if not admin:
        raise HTTPException(status_code=401, detail="Invalid username or password")
        
    # Verify hash
    salt = "streamfit_secure_salt_2026"
    h = hashlib.sha256((password + salt).encode('utf-8')).hexdigest()
    
    if admin["password_hash"] != h:
        raise HTTPException(status_code=401, detail="Invalid username or password")
        
    # Generate token
    token = generate_signed_token(username)
    
    response.set_cookie(key="admin_token", value=token, max_age=86400, httponly=True)
    return {"code": 0, "token": token, "message": "Login successful"}

@router.post("/api/admin/logout")
async def admin_logout(request: Request, response: Response):
    response.delete_cookie("admin_token")
    return {"code": 0, "message": "Logged out successfully"}

@router.get("/api/admin/subjects/search")
async def admin_search_subjects(q: str, request: Request):
    check_admin_auth(request)
    q_clean = q.strip()
    if not q_clean:
        return {"list": [], "items": []}

    from main import get_db_pool, request_moviebox_mobile
    items = []
    existing_ids = set()
    pool = await get_db_pool()

    if pool:
        try:
            async with pool.acquire() as conn:
                async with conn.cursor(aiomysql.DictCursor) as cur:
                    await cur.execute("""
                        SELECT subject_id, title, cover, detail_path, subject_type 
                        FROM subjects 
                        WHERE title LIKE %s 
                        LIMIT 50
                    """, (f"%{q_clean}%",))
                    rows = await cur.fetchall()
                    for r in rows:
                        s_id = str(r["subject_id"])
                        items.append({
                            "subjectId": s_id,
                            "subject_id": s_id,
                            "title": r["title"],
                            "coverUrl": r.get("cover") or "",
                            "cover": r.get("cover") or "",
                            "detailPath": r.get("detail_path") or "",
                            "detail_path": r.get("detail_path") or "",
                            "subjectType": r.get("subject_type", 1),
                            "subject_type": r.get("subject_type", 1)
                        })
                        existing_ids.add(s_id)
        except Exception as dbe:
            print(f"DB search error: {dbe}")

    # Fallback to online search if DB has fewer than 5 items (so Naruto, Anime, etc. all show up)
    if len(items) < 5:
        try:
            search_res = await request_moviebox_mobile(
                "/wefeed-mobile-bff/subject-api/search",
                params={"keyword": q_clean, "page": 1, "pageSize": 20}
            )
            online_items = search_res.get("data", {}).get("items", []) or []
            for it in online_items:
                s_id = str(it.get("subjectId") or "")
                if s_id and s_id not in existing_ids:
                    cover_val = it.get("cover", {})
                    cover_url = cover_val.get("url") if isinstance(cover_val, dict) else str(cover_val or "")
                    items.append({
                        "subjectId": s_id,
                        "subject_id": s_id,
                        "title": it.get("title") or "Title",
                        "coverUrl": cover_url,
                        "cover": cover_url,
                        "detailPath": it.get("detailPath") or "",
                        "detail_path": it.get("detailPath") or "",
                        "subjectType": it.get("subjectType", 1),
                        "subject_type": it.get("subjectType", 1)
                    })
                    existing_ids.add(s_id)
        except Exception as se:
            print(f"Online search error: {se}")

    return {"list": items, "items": items}

@router.get("/api/admin/banners")
async def admin_get_banners(request: Request):
    check_admin_auth(request)
    from main import get_db_pool
    pool = await get_db_pool()
    if not pool: return {"list": []}
    async with pool.acquire() as conn:
        async with conn.cursor(aiomysql.DictCursor) as cur:
            await cur.execute("SELECT * FROM banners ORDER BY created_at DESC")
            rows = await cur.fetchall()
            items = []
            for r in rows:
                items.append({
                    "subjectId": r["subject_id"],
                    "title": r["title"],
                    "coverUrl": r["image_url"],
                    "detailPath": r["detail_path"],
                    "subjectType": r["subject_type"]
                })
            return {"list": items}

@router.post("/api/admin/banners")
async def admin_add_banner(data: dict, request: Request):
    check_admin_auth(request)
    subject_id = data.get("subjectId")
    title = data.get("title")
    cover_url = data.get("coverUrl")
    detail_path = data.get("detailPath")
    subject_type = int(data.get("subjectType", 1))
    
    if not subject_id or not title:
        raise HTTPException(status_code=400, detail="Missing required parameters")
        
    from main import get_db_pool
    pool = await get_db_pool()
    if not pool: raise HTTPException(status_code=500, detail="DB Pool not ready")
    
    async with pool.acquire() as conn:
        async with conn.cursor() as cur:
            await cur.execute("""
                INSERT INTO banners (subject_id, title, image_url, detail_path, subject_type)
                VALUES (%s, %s, %s, %s, %s)
                ON DUPLICATE KEY UPDATE
                    title=VALUES(title),
                    image_url=VALUES(image_url),
                    detail_path=VALUES(detail_path),
                    subject_type=VALUES(subject_type)
            """, (subject_id, title, cover_url, detail_path, subject_type))
            
    return {"code": 0, "message": "Banner added/updated successfully"}

@router.delete("/api/admin/banners/{subject_id}")
async def admin_delete_banner(subject_id: str, request: Request):
    check_admin_auth(request)
    from main import get_db_pool
    pool = await get_db_pool()
    if not pool: raise HTTPException(status_code=500, detail="DB Pool not ready")
    async with pool.acquire() as conn:
        async with conn.cursor() as cur:
            await cur.execute("DELETE FROM banners WHERE subject_id = %s", (subject_id,))
    return {"code": 0, "message": "Banner deleted successfully"}

@router.post("/api/admin/notifications")
async def admin_send_notification(data: dict, request: Request):
    check_admin_auth(request)
    title = data.get("title")
    message = data.get("message")
    subject_id = data.get("subjectId")
    
    if not title or not message:
        raise HTTPException(status_code=400, detail="Title and message are required")
        
    from main import get_db_pool
    pool = await get_db_pool()
    if not pool: raise HTTPException(status_code=500, detail="DB Pool not ready")
    
    async with pool.acquire() as conn:
        async with conn.cursor() as cur:
            await cur.execute("""
                INSERT INTO notifications (title, message, subject_id)
                VALUES (%s, %s, %s)
            """, (title, message, subject_id))
            
    return {"code": 0, "message": "Notification broadcasted successfully"}

@router.get("/api/admin/notifications")
async def admin_get_notifications(request: Request):
    check_admin_auth(request)
    from main import get_db_pool
    pool = await get_db_pool()
    if not pool: return {"list": []}
    async with pool.acquire() as conn:
        async with conn.cursor(aiomysql.DictCursor) as cur:
            await cur.execute("SELECT * FROM notifications ORDER BY created_at DESC LIMIT 50")
            rows = await cur.fetchall()
            items = []
            for r in rows:
                items.append({
                    "id": r["id"],
                    "title": r["title"],
                    "message": r["message"],
                    "subjectId": r["subject_id"],
                    "createdAt": r["createdAt"].isoformat() if hasattr(r, "createdAt") and hasattr(r["createdAt"], "isoformat") else str(r["created_at"])
                })
            return {"list": items}

@router.get("/api/notifications/latest")
async def public_get_latest_notification():
    from main import get_db_pool
    pool = await get_db_pool()
    if not pool: return {"notification": None}
    async with pool.acquire() as conn:
        async with conn.cursor(aiomysql.DictCursor) as cur:
            await cur.execute("SELECT * FROM notifications ORDER BY id DESC LIMIT 1")
            row = await cur.fetchone()
            if not row:
                return {"notification": None}
            return {
                "notification": {
                    "id": row["id"],
                    "title": row["title"],
                    "message": row["message"],
                    "subjectId": row["subject_id"]
                }
            }

@router.post("/api/admin/version")
async def admin_update_version(data: dict, request: Request):
    check_admin_auth(request)
    version_code = int(data.get("versionCode"))
    version_name = data.get("versionName")
    apk_url = data.get("apkUrl")
    must_update = bool(data.get("mustUpdate", False))
    release_notes = data.get("releaseNotes", "")
    
    if not version_code or not version_name or not apk_url:
        raise HTTPException(status_code=400, detail="Version code, name and APK URL are required")
        
    from main import get_db_pool
    pool = await get_db_pool()
    if not pool: raise HTTPException(status_code=500, detail="DB Pool not ready")
    
    async with pool.acquire() as conn:
        async with conn.cursor() as cur:
            await cur.execute("""
                INSERT INTO app_versions (version_code, version_name, apk_url, must_update, release_notes)
                VALUES (%s, %s, %s, %s, %s)
                ON DUPLICATE KEY UPDATE
                    version_name=VALUES(version_name),
                    apk_url=VALUES(apk_url),
                    must_update=VALUES(must_update),
                    release_notes=VALUES(release_notes)
            """, (version_code, version_name, apk_url, must_update, release_notes))
            
    return {"code": 0, "message": "App version configuration saved"}

@router.post("/api/admin/version/upload")
async def admin_upload_apk(request: Request, file: UploadFile = File(...)):
    check_admin_auth(request)
    if not file.filename.endswith(".apk"):
        raise HTTPException(status_code=400, detail="Only APK files are allowed")
        
    upload_dir = os.path.join(base_dir, "public", "uploads", "apks")
    os.makedirs(upload_dir, exist_ok=True)
    
    filename = "app-release.apk"
    dest_path = os.path.join(upload_dir, filename)
    
    with open(dest_path, "wb") as f:
        while content := await file.read(1024 * 1024):
            f.write(content)
            
    apk_url = f"/uploads/apks/{filename}"
    return {"code": 0, "apkUrl": apk_url, "message": "APK uploaded successfully"}

@router.get("/api/app/version")
async def public_get_app_version():
    from main import get_db_pool
    default_version = {
        "versionCode": 20,
        "versionName": "1.0.19",
        "apkUrl": "https://nxly.online/uploads/apks/NXLY-Stream.apk",
        "mustUpdate": False,
        "releaseNotes": "AI Audio Translator: 100% dialogue completion guarantee (no premature dialogue cutoffs), zero-lag lead-time sync, and smart speech queueing."
    }
    pool = await get_db_pool()
    if not pool: return {"version": default_version}
    try:
        async with pool.acquire() as conn:
            async with conn.cursor(aiomysql.DictCursor) as cur:
                await cur.execute("SELECT * FROM app_versions ORDER BY version_code DESC LIMIT 1")
                row = await cur.fetchone()
                if not row:
                    return {"version": default_version}
                apk_url = row["apk_url"]
                if "app-release.apk" in apk_url:
                    apk_url = apk_url.replace("app-release.apk", "NXLY-Stream.apk")
                return {
                    "version": {
                        "versionCode": row["version_code"],
                        "versionName": row["version_name"],
                        "apkUrl": apk_url,
                        "mustUpdate": bool(row["must_update"]),
                        "releaseNotes": row["release_notes"]
                    }
                }
    except Exception:
        return {"version": default_version}

@router.get("/api/app/download")
async def public_download_latest():
    # Direct access is blocked to prevent bot scraping; redirect to human verification download page
    return RedirectResponse(url="/download")

import json

@router.get("/api/admin/sports")
async def admin_get_sports(request: Request):
    check_admin_auth(request)
    from main import get_db_pool
    pool = await get_db_pool()
    if not pool: return {"list": []}
    
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
            return {"list": items}

@router.post("/api/admin/sports")
async def admin_save_sport(data: dict, request: Request):
    check_admin_auth(request)
    sport_id = data.get("id")
    title = data.get("title")
    logo = data.get("logo")
    team1_name = data.get("team1Name")
    team1_logo = data.get("team1Logo")
    team2_name = data.get("team2Name")
    team2_logo = data.get("team2Logo")
    stream_links = data.get("streamLinks")
    referer = data.get("referer")
    origin = data.get("origin")
    use_bd_proxy = bool(data.get("useBdProxy", True))
    
    if not title or not stream_links:
        raise HTTPException(status_code=400, detail="Title and stream links are required")
        
    if isinstance(stream_links, (list, dict)):
        stream_links_str = json.dumps(stream_links)
    else:
        stream_links_str = str(stream_links)
        
    from main import get_db_pool
    pool = await get_db_pool()
    if not pool: raise HTTPException(status_code=500, detail="DB Pool not ready")
    
    async with pool.acquire() as conn:
        async with conn.cursor() as cur:
            if sport_id:
                await cur.execute("""
                    UPDATE live_sports 
                    SET title=%s, logo=%s, team1_name=%s, team1_logo=%s, team2_name=%s, team2_logo=%s, 
                        stream_links=%s, referer=%s, origin=%s, use_bd_proxy=%s
                    WHERE id=%s
                """, (title, logo, team1_name, team1_logo, team2_name, team2_logo, 
                      stream_links_str, referer, origin, use_bd_proxy, int(sport_id)))
            else:
                await cur.execute("""
                    INSERT INTO live_sports (title, logo, team1_name, team1_logo, team2_name, team2_logo, stream_links, referer, origin, use_bd_proxy)
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                """, (title, logo, team1_name, team1_logo, team2_name, team2_logo, 
                      stream_links_str, referer, origin, use_bd_proxy))
                      
    return {"code": 0, "message": "Live sport saved successfully"}

@router.delete("/api/admin/sports/{sport_id}")
async def admin_delete_sport(sport_id: int, request: Request):
    check_admin_auth(request)
    from main import get_db_pool
    pool = await get_db_pool()
    if not pool: raise HTTPException(status_code=500, detail="DB Pool not ready")
    async with pool.acquire() as conn:
        async with conn.cursor() as cur:
            await cur.execute("DELETE FROM live_sports WHERE id = %s", (sport_id,))
    return {"code": 0, "message": "Live sport deleted successfully"}

@router.get("/api/admin/tv-channels")
async def admin_get_tv_channels(request: Request):
    check_admin_auth(request)
    from main import get_db_pool
    pool = await get_db_pool()
    if not pool: return {"list": []}
    
    async with pool.acquire() as conn:
        async with conn.cursor(aiomysql.DictCursor) as cur:
            await cur.execute("SELECT * FROM live_tv_channels ORDER BY created_at DESC")
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
            return {"list": items}

@router.get("/api/sports/list")
@router.get("/api/sports/live")
async def public_get_sports():
    from main import get_db_pool
    pool = await get_db_pool()
    if not pool: return {"code": 0, "list": []}
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

@router.get("/api/tv/channels")
async def public_get_tv_channels():
    from main import get_db_pool
    pool = await get_db_pool()
    if not pool: return {"code": 0, "list": []}
    async with pool.acquire() as conn:
        async with conn.cursor(aiomysql.DictCursor) as cur:
            await cur.execute("SELECT * FROM live_tv_channels ORDER BY created_at DESC")
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

@router.post("/api/admin/tv-channels")
async def admin_save_tv_channel(data: dict, request: Request):
    check_admin_auth(request)
    channel_id = data.get("id")
    name = data.get("name")
    logo = data.get("logo")
    category = data.get("category") or "General"
    stream_links = data.get("streamLinks")
    referer = data.get("referer")
    origin = data.get("origin")
    use_bd_proxy = bool(data.get("useBdProxy", True))
    
    if not name or not stream_links:
        raise HTTPException(status_code=400, detail="Channel name and stream links are required")
        
    if isinstance(stream_links, (list, dict)):
        stream_links_str = json.dumps(stream_links)
    else:
        stream_links_str = str(stream_links)
        
    from main import get_db_pool
    pool = await get_db_pool()
    if not pool: raise HTTPException(status_code=500, detail="DB Pool not ready")
    
    async with pool.acquire() as conn:
        async with conn.cursor() as cur:
            if channel_id:
                await cur.execute("""
                    UPDATE live_tv_channels 
                    SET name=%s, logo=%s, category=%s, stream_links=%s, referer=%s, origin=%s, use_bd_proxy=%s
                    WHERE id=%s
                """, (name, logo, category, stream_links_str, referer, origin, use_bd_proxy, int(channel_id)))
            else:
                await cur.execute("""
                    INSERT INTO live_tv_channels (name, logo, category, stream_links, referer, origin, use_bd_proxy)
                    VALUES (%s, %s, %s, %s, %s, %s, %s)
                """, (name, logo, category, stream_links_str, referer, origin, use_bd_proxy))
                
    return {"code": 0, "message": "Live TV channel saved successfully"}

@router.delete("/api/admin/tv-channels/{channel_id}")
async def admin_delete_tv_channel(channel_id: int, request: Request):
    check_admin_auth(request)
    from main import get_db_pool
    pool = await get_db_pool()
    if not pool: raise HTTPException(status_code=500, detail="DB Pool not ready")
    async with pool.acquire() as conn:
        async with conn.cursor() as cur:
            await cur.execute("DELETE FROM live_tv_channels WHERE id = %s", (channel_id,))
    return {"code": 0, "message": "Live TV channel deleted successfully"}

def parse_m3u_text(text: str) -> list:
    channels = []
    lines = text.splitlines()
    
    current_extinf = None
    
    tvg_name_regex = re.compile(r'tvg-name=["\']([^"\']+)["\']', re.IGNORECASE)
    tvg_logo_regex = re.compile(r'tvg-logo=["\']([^"\']+)["\']', re.IGNORECASE)
    group_title_regex = re.compile(r'group-title=["\']([^"\']+)["\']', re.IGNORECASE)
    
    for line in lines:
        line_strip = line.strip()
        if not line_strip:
            continue
            
        if line_strip.startswith("#EXTINF:"):
            name = ""
            if "," in line_strip:
                name = line_strip.rsplit(",", 1)[1].strip()
                
            tvg_name_match = tvg_name_regex.search(line_strip)
            tvg_logo_match = tvg_logo_regex.search(line_strip)
            group_title_match = group_title_regex.search(line_strip)
            
            tvg_name = tvg_name_match.group(1).strip() if tvg_name_match else name
            logo = tvg_logo_match.group(1).strip() if tvg_logo_match else ""
            category = group_title_match.group(1).strip() if group_title_match else "General"
            
            if not tvg_name and name:
                tvg_name = name
            if not tvg_name:
                tvg_name = "Unknown Channel"
                
            current_extinf = {
                "name": tvg_name,
                "logo": logo,
                "category": category
            }
        elif line_strip.startswith("#"):
            continue
        else:
            if current_extinf:
                current_extinf["url"] = line_strip
                channels.append(current_extinf)
                current_extinf = None
                
    return channels

@router.post("/api/admin/tv-channels/parse-m3u")
async def admin_parse_m3u_endpoint(data: dict, request: Request):
    check_admin_auth(request)
    url = data.get("url", "").strip()
    content = data.get("content", "").strip()
    
    if not url and not content:
        raise HTTPException(status_code=400, detail="M3U URL or content is required")
        
    if url:
        try:
            async with httpx.AsyncClient(trust_env=False, timeout=15.0) as client:
                resp = await client.get(url)
                if resp.status_code != 200:
                    raise HTTPException(status_code=400, detail=f"Failed to fetch M3U: Status {resp.status_code}")
                content = resp.text
        except Exception as e:
            raise HTTPException(status_code=400, detail=f"Failed to fetch M3U URL: {e}")
            
    parsed = parse_m3u_text(content)
    return {"code": 0, "list": parsed}

@router.post("/api/admin/tv-channels/import")
async def admin_import_tv_channels(data: dict, request: Request):
    check_admin_auth(request)
    channels = data.get("channels")
    if not channels or not isinstance(channels, list):
        raise HTTPException(status_code=400, detail="Channels list is required")
        
    from main import get_db_pool
    pool = await get_db_pool()
    if not pool: raise HTTPException(status_code=500, detail="DB Pool not ready")
    
    skipped = 0
    added = 0
    updated = 0
    
    async with pool.acquire() as conn:
        async with conn.cursor(aiomysql.DictCursor) as cur:
            for ch in channels:
                name = ch.get("name", "").strip()
                logo = ch.get("logo", "").strip()
                category = ch.get("category", "").strip() or "General"
                url = ch.get("url", "").strip()
                
                if not name or not url:
                    continue
                    
                await cur.execute("SELECT * FROM live_tv_channels WHERE name = %s", (name,))
                existing = await cur.fetchone()
                
                if existing:
                    try:
                        links = json.loads(existing["stream_links"])
                    except Exception:
                        links = []
                        
                    has_url = any(l.get("url") == url for l in links)
                    if has_url:
                        skipped += 1
                        continue
                        
                    label = f"Link {len(links) + 1}"
                    links.append({"label": label, "url": url})
                    links_str = json.dumps(links)
                    
                    await cur.execute("""
                        UPDATE live_tv_channels 
                        SET stream_links = %s
                        WHERE id = %s
                    """, (links_str, existing["id"]))
                    updated += 1
                else:
                    links = [{"label": "Link 1", "url": url}]
                    links_str = json.dumps(links)
                    await cur.execute("""
                        INSERT INTO live_tv_channels (name, logo, category, stream_links, referer, origin, use_bd_proxy)
                        VALUES (%s, %s, %s, %s, %s, %s, %s)
                    """, (name, logo, category, links_str, "", "", True))
                    added += 1
                    
    return {
        "code": 0,
        "message": f"Import completed successfully. Added: {added}, Updated (link added): {updated}, Skipped (duplicate): {skipped}"
    }

@router.get("/api/admin/active-users")
async def get_active_users(request: Request):
    check_admin_auth(request)
    from main import get_active_users_stats
    return get_active_users_stats()

@router.get("/api/admin/app-users")
async def get_app_users_admin(request: Request):
    check_admin_auth(request)
    from main import get_app_users_list
    return get_app_users_list()

@router.post("/api/admin/clear-app-users")
async def clear_app_users_admin(request: Request):
    check_admin_auth(request)
    from main import clear_all_app_installations
    success = clear_all_app_installations()
    return {"code": 0 if success else 500, "status": "ok" if success else "failed"}

# ── Reels / Shorts Creator Endpoints ──────────────────────────────────────────
reel_generator = None
try:
    import reel_generator
except Exception as _rg_err:
    print(f"[Admin] Notice: reel_generator lazy load: {_rg_err}")

def _get_reel_generator():
    global reel_generator
    if reel_generator is None:
        try:
            import reel_generator as rg
            reel_generator = rg
        except Exception as e:
            raise HTTPException(status_code=500, detail=f"Reel engine error: {e}")
    return reel_generator

@router.get("/api/admin/reels/seasons")
async def admin_reels_seasons(request: Request, detailPath: str = "", subjectId: str = ""):
    check_admin_auth(request)
    from main import api_season
    res = await api_season(detailPath=detailPath, subjectId=subjectId)
    return res

@router.get("/api/admin/reels/stream-url")
async def admin_reels_stream_url(request: Request, subjectId: str = "", detailPath: str = "", se: int = 1, ep: int = 1):
    check_admin_auth(request)
    from main import api_resource
    res = await api_resource(se=se, ep=ep, detailPath=detailPath, subjectId=subjectId)
    
    items = res.get("data", {}).get("list") or []
    # Filter for direct playable links
    resolutions = []
    best_url = ""
    
    for it in items:
        link = it.get("url") or it.get("resourceLink") or ""
        # If wrapped in /fetch, extract source_url if direct access is better, or keep fetch
        clean_link = link
        if "source_url=" in link:
            try:
                clean_link = urllib.parse.unquote(link.split("source_url=")[-1])
            except Exception:
                clean_link = link
                
        if clean_link:
            resolutions.append({
                "resolution": it.get("resolution", 720),
                "size": it.get("size", 0),
                "url": clean_link
            })
            if not best_url:
                best_url = clean_link
                
    # Prioritize 1080p and 720p H.264 streams which avoid 429 rate limits on CDN
    h264_links = [r for r in resolutions if "h265" not in r["url"].lower()]
    if h264_links:
        h264_links.sort(key=lambda x: (x["resolution"] == 1080, x["resolution"] == 720, x["resolution"]), reverse=True)
        best_url = h264_links[0]["url"]
    elif resolutions:
        best_url = resolutions[0]["url"]
        
    return {
        "code": 0,
        "best_url": best_url,
        "resolutions": resolutions
    }

@router.post("/api/admin/reels/start")
async def admin_reels_start(request: Request, data: dict):
    check_admin_auth(request)
    title = data.get("title", "").strip()
    stream_url = data.get("stream_url", "").strip()
    start_time = data.get("start_time", "00:00").strip()
    part_duration = int(data.get("part_duration", 60))
    num_parts = int(data.get("num_parts", 1))
    start_part_num = int(data.get("start_part_num", 1))
    top_text = data.get("top_text", "").strip()
    bottom_text = data.get("bottom_text", "").strip()
    anti_copyright = bool(data.get("anti_copyright", True))
    
    if not title:
        raise HTTPException(status_code=400, detail="Movie or Series title is required")
    if not stream_url:
        raise HTTPException(status_code=400, detail="Direct video or stream URL is required")
    if num_parts < 1 or num_parts > 30:
        raise HTTPException(status_code=400, detail="Number of reels must be between 1 and 30")
    if part_duration < 10 or part_duration > 600:
        raise HTTPException(status_code=400, detail="Part duration must be between 10s and 600s")
        
    rg = _get_reel_generator()
    job_id = rg.start_reel_job(
        title=title,
        stream_url=stream_url,
        start_time_str=start_time,
        part_duration=part_duration,
        num_parts=num_parts,
        start_part_num=start_part_num,
        top_text_custom=top_text,
        bottom_text_custom=bottom_text,
        anti_copyright=anti_copyright
    )
    
    return {
        "code": 0,
        "job_id": job_id,
        "message": f"Started generating {num_parts} reel(s) for '{title}'"
    }

@router.get("/api/admin/reels/active-job")
async def admin_reels_active_job(request: Request):
    check_admin_auth(request)
    rg = _get_reel_generator()
    job = rg.get_latest_or_active_job()
    return {
        "code": 0,
        "job": job
    }

@router.post("/api/admin/reels/cancel-job")
async def admin_reels_cancel_job(request: Request):
    check_admin_auth(request)
    rg = _get_reel_generator()
    res = rg.cancel_all_active_jobs()
    return res

@router.get("/api/admin/reels/job-status")
async def admin_reels_job_status(request: Request, job_id: str):
    check_admin_auth(request)
    rg = _get_reel_generator()
    job = rg.get_job_state(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Job not found or expired")
    return {
        "code": 0,
        "job": job
    }

@router.get("/api/admin/reels/history")
async def admin_reels_history(request: Request):
    check_admin_auth(request)
    rg = _get_reel_generator()
    history = rg.load_reels_history()
    return {
        "code": 0,
        "list": history
    }

@router.delete("/api/admin/reels/delete")
async def admin_reels_delete(request: Request, filename: str):
    check_admin_auth(request)
    if not filename:
        raise HTTPException(status_code=400, detail="Filename is required")
    rg = _get_reel_generator()
    success = rg.delete_reel_file(filename)
    return {
        "code": 0 if success else 404,
        "status": "deleted" if success else "file not found"
    }

# ==========================================================================
# FIREBASE CLOUD MESSAGING (FCM) PUSH NOTIFICATION SYSTEM
# ==========================================================================
_fcm_access_token = None
_fcm_token_expiry = 0

async def _get_fcm_oauth_token() -> str:
    global _fcm_access_token, _fcm_token_expiry
    now = int(time.time())
    if _fcm_access_token and (now < _fcm_token_expiry - 120):
        return _fcm_access_token

    base_dir = os.path.dirname(os.path.abspath(__file__))
    sa_path = os.path.join(base_dir, "firebase-service-account.json")
    env_json = os.environ.get("FIREBASE_SERVICE_ACCOUNT_JSON")
    sa = None
    if env_json:
        try:
            sa = json.loads(env_json)
        except Exception:
            sa = None
    if not sa:
        if os.path.exists(sa_path):
            with open(sa_path, "r", encoding="utf-8") as f:
                sa = json.load(f)
        else:
            raise HTTPException(status_code=500, detail="firebase-service-account.json not configured on server")

    import jwt
    payload = {
        "iss": sa["client_email"],
        "sub": sa["client_email"],
        "aud": sa["token_uri"],
        "iat": now,
        "exp": now + 3600,
        "scope": "https://www.googleapis.com/auth/firebase.messaging"
    }
    signed_jwt = jwt.encode(payload, sa["private_key"], algorithm="RS256")

    async with httpx.AsyncClient(timeout=10.0) as client:
        resp = await client.post(
            sa["token_uri"],
            data={
                "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
                "assertion": signed_jwt
            }
        )
        if resp.status_code != 200:
            raise HTTPException(status_code=500, detail=f"Failed to authenticate with Firebase: {resp.text}")
        token_data = resp.json()
        _fcm_access_token = token_data.get("access_token")
        _fcm_token_expiry = now + int(token_data.get("expires_in", 3600))
        return _fcm_access_token

@router.get("/api/admin/push/stats")
async def admin_push_stats(request: Request):
    check_admin_auth(request)
    from main import get_db_pool
    pool = await get_db_pool()
    if not pool:
        return {"code": 0, "total_devices": 0}

    async with pool.acquire() as conn:
        async with conn.cursor() as cur:
            await cur.execute("SELECT COUNT(*) FROM fcm_tokens")
            row = await cur.fetchone()
            total_devices = row[0] if row else 0

    return {
        "code": 0,
        "total_devices": total_devices
    }

@router.post("/api/admin/push/send")
async def admin_push_send(data: dict, request: Request):
    check_admin_auth(request)
    title = str(data.get("title") or "").strip()
    body = str(data.get("body") or data.get("message") or "").strip()
    image = str(data.get("image") or data.get("cover") or "").strip()
    detail_path = str(data.get("detail_path") or data.get("detailPath") or "").strip()
    subject_id = str(data.get("subject_id") or data.get("subjectId") or "").strip()
    subject_type = int(data.get("subject_type") or data.get("subjectType") or 1)

    if not title or not body:
        raise HTTPException(status_code=400, detail="Title and message body are required")

    from main import get_db_pool
    pool = await get_db_pool()
    if not pool:
        raise HTTPException(status_code=500, detail="Database pool not available")

    # 1. Save in database notifications table for the in-app bell
    async with pool.acquire() as conn:
        async with conn.cursor() as cur:
            await cur.execute("""
                INSERT INTO notifications (title, message, subject_id)
                VALUES (%s, %s, %s)
            """, (title, body, subject_id))

            # 2. Fetch all registered FCM tokens
            await cur.execute("SELECT token FROM fcm_tokens")
            rows = await cur.fetchall()
            tokens = [r[0] for r in rows if r and r[0]]

    if not tokens:
        return {
            "code": 0,
            "success": True,
            "message": "Notification saved, but no Android devices are currently registered.",
            "sent_count": 0,
            "total_devices": 0
        }

    # 3. Obtain OAuth2 access token for FCM v1 REST API
    access_token = await _get_fcm_oauth_token()
    project_id = "nxly-3797c"
    fcm_endpoint = f"https://fcm.googleapis.com/v1/projects/{project_id}/messages:send"

    deep_link_url = f"https://nxly.online/movie/{detail_path}" if detail_path else ""

    headers = {
        "Authorization": f"Bearer {access_token}",
        "Content-Type": "application/json"
    }

    sent_count = 0
    failed_tokens = []

    async with httpx.AsyncClient(timeout=12.0) as client:
        # Send concurrently in chunks of 50
        chunk_size = 50
        for i in range(0, len(tokens), chunk_size):
            chunk = tokens[i:i + chunk_size]
            tasks = []
            for t in chunk:
                msg = {
                    "message": {
                        "token": t,
                        "notification": {
                            "title": title,
                            "body": body
                        },
                        "data": {
                            "title": title,
                            "body": body,
                            "image": image,
                            "detail_path": detail_path,
                            "subject_id": subject_id,
                            "subject_type": str(subject_type),
                            "url": deep_link_url
                        },
                        "android": {
                            "priority": "high",
                            "notification": {
                                "channel_id": "nxly_notifications",
                                "sound": "default"
                            }
                        }
                    }
                }
                if image:
                    msg["message"]["notification"]["image"] = image
                    msg["message"]["android"]["notification"]["image"] = image

                tasks.append(client.post(fcm_endpoint, json=msg, headers=headers))

            responses = await asyncio.gather(*tasks, return_exceptions=True)
            for t, resp in zip(chunk, responses):
                if isinstance(resp, httpx.Response):
                    if resp.status_code == 200:
                        sent_count += 1
                    elif resp.status_code in (404, 400):
                        # Token expired or app uninstalled
                        failed_tokens.append(t)
                else:
                    pass

    # Clean up any stale/unregistered tokens
    if failed_tokens:
        try:
            async with pool.acquire() as conn:
                async with conn.cursor() as cur:
                    format_strings = ','.join(['%s'] * len(failed_tokens))
                    await cur.execute(f"DELETE FROM fcm_tokens WHERE token IN ({format_strings})", tuple(failed_tokens))
        except Exception:
            pass

    return {
        "code": 0,
        "success": True,
        "sent_count": sent_count,
        "total_devices": len(tokens),
        "message": f"Push notification successfully sent to {sent_count} device(s)!"
    }

# ==========================================================================
# FACEBOOK REELS & AUTO-PILOT AUTOMATION ENDPOINTS
# ==========================================================================
@router.get("/api/admin/facebook/config")
async def admin_facebook_get_config(request: Request):
    check_admin_auth(request)
    import facebook_bot
    cfg = facebook_bot.load_fb_config()
    raw_token = cfg.get("page_token", "")
    masked_token = (raw_token[:8] + "..." + raw_token[-8:]) if len(raw_token) > 16 else raw_token
    conn_info = await facebook_bot.verify_facebook_connection(cfg.get("page_id", ""), raw_token)
    return {
        "code": 0,
        "config": {
            "page_id": cfg.get("page_id", ""),
            "page_name": conn_info.get("name") or cfg.get("page_name", "NXLY"),
            "masked_token": masked_token,
            "page_token": raw_token,
            "app_id": cfg.get("app_id", ""),
            "app_secret": cfg.get("app_secret", ""),
            "auto_pilot_enabled": cfg.get("auto_pilot_enabled", True),
            "post_interval_minutes": cfg.get("post_interval_minutes", 10),
            "post_interval_hours": cfg.get("post_interval_hours", 0.166),
            "last_post_time": cfg.get("last_post_time", 0),
            "auto_comment_template": cfg.get("auto_comment_template", ""),
            "target_groups": cfg.get("target_groups", [])
        },
        "connection": conn_info
    }

@router.post("/api/admin/facebook/config")
async def admin_facebook_save_config(data: dict, request: Request):
    check_admin_auth(request)
    import facebook_bot
    cfg = facebook_bot.load_fb_config()
    if "page_id" in data and data["page_id"]:
        cfg["page_id"] = str(data["page_id"]).strip()
    if "page_token" in data and data["page_token"] and "..." not in data["page_token"]:
        cfg["page_token"] = str(data["page_token"]).strip()
    if "auto_pilot_enabled" in data:
        cfg["auto_pilot_enabled"] = bool(data["auto_pilot_enabled"])
    if "post_interval_minutes" in data:
        cfg["post_interval_minutes"] = max(1, int(data["post_interval_minutes"]))
        cfg["post_interval_hours"] = round(cfg["post_interval_minutes"] / 60, 2)
    elif "post_interval_hours" in data:
        cfg["post_interval_hours"] = max(0.1, float(data["post_interval_hours"]))
        cfg["post_interval_minutes"] = max(1, int(cfg["post_interval_hours"] * 60))
    if "auto_comment_template" in data:
        cfg["auto_comment_template"] = str(data["auto_comment_template"])
    if "target_groups" in data:
        raw_groups = data["target_groups"]
        if isinstance(raw_groups, str):
            cfg["target_groups"] = [g.strip() for g in raw_groups.split(",") if g.strip()]
        elif isinstance(raw_groups, list):
            cfg["target_groups"] = [str(g).strip() for g in raw_groups if str(g).strip()]

    facebook_bot.save_fb_config(cfg)
    return {"code": 0, "message": "Facebook configuration saved successfully!"}

@router.post("/api/admin/facebook/trigger")
async def admin_facebook_trigger(request: Request):
    check_admin_auth(request)
    import facebook_bot
    asyncio.create_task(facebook_bot.run_live_bot_cycle())
    return {
        "code": 0,
        "success": True,
        "message": "Auto-Pilot cycle initiated in background! Check live terminal for progress."
    }

@router.get("/api/admin/facebook/history")
async def admin_facebook_history(request: Request):
    check_admin_auth(request)
    import facebook_bot
    history = facebook_bot.load_fb_history()
    return {"code": 0, "history": history}

@router.get("/api/admin/facebook/terminal-log")
@router.get("/api/admin/facebook/logs")
@router.get("/api/admin/automation/terminal-log")
@router.get("/api/admin/automation/logs")
async def admin_facebook_terminal_log(request: Request):
    check_admin_auth(request)
    import facebook_bot
    logs = facebook_bot.get_live_logs()
    cfg = facebook_bot.load_fb_config()
    return {
        "code": 0,
        "logs": logs,
        "auto_pilot_enabled": cfg.get("auto_pilot_enabled", False),
        "post_interval_minutes": cfg.get("post_interval_minutes", 10),
        "last_post_time": cfg.get("last_post_time", 0)
    }

@router.post("/api/admin/facebook/terminal-clear")
@router.post("/api/admin/automation/terminal-clear")
async def admin_facebook_terminal_clear(request: Request):
    check_admin_auth(request)
    import facebook_bot
    facebook_bot.clear_live_logs()
    return {"code": 0, "success": True, "message": "Live terminal logs cleared successfully."}


@router.post("/api/admin/facebook/terminal-run")
async def admin_facebook_terminal_run(request: Request):
    check_admin_auth(request)
    import facebook_bot
    asyncio.create_task(facebook_bot.run_live_bot_cycle())
    return {
        "code": 0,
        "success": True,
        "message": "Auto-Pilot cycle initiated in background! Check terminal output below.",
        "logs": facebook_bot.get_live_logs()
    }

@router.post("/api/admin/facebook/delete-reel")
async def admin_facebook_delete_reel(data: dict, request: Request):
    check_admin_auth(request)
    import facebook_bot
    video_id = data.get("video_id") or data.get("reel_url")
    if not video_id:
        raise HTTPException(status_code=400, detail="Missing video_id or reel_url")
    res = await facebook_bot.delete_facebook_reel(video_id)
    if not res.get("success"):
        raise HTTPException(status_code=400, detail=res.get("error", "Delete failed"))
    return {"code": 0, "success": True, "message": "Reel deleted from Facebook successfully."}

@router.post("/api/admin/facebook/cleanup-check")
async def admin_facebook_cleanup_check(request: Request):
    check_admin_auth(request)
    import facebook_bot
    res = await facebook_bot.cleanup_copyrighted_videos()
    return {"code": 0, "success": True, "result": res}

@router.post("/api/admin/facebook/publish-reel")
async def admin_facebook_publish_reel(data: dict, request: Request):
    check_admin_auth(request)
    import facebook_bot
    filename = data.get("filename")
    if not filename:
        raise HTTPException(status_code=400, detail="Missing reel filename")
    caption = data.get("caption", "")
    title = data.get("title", "")
    subject_id = data.get("subject_id", "")
    res = await facebook_bot.publish_existing_reel(
        filename=filename,
        caption=caption,
        title=title,
        subject_id=subject_id
    )
    if not res.get("success"):
        raise HTTPException(status_code=500, detail=res.get("error", "Failed to publish reel to Facebook"))
    return {
        "code": 0,
        "success": True,
        "message": "Reel successfully published to Facebook Page NXLY!",
        "reel_url": res.get("reel_url"),
        "video_id": res.get("video_id")
    }

@router.post("/api/admin/facebook/exchange-token")
async def admin_facebook_exchange_token(data: dict, request: Request):
    check_admin_auth(request)
    import facebook_bot
    app_id = data.get("app_id", "").strip()
    app_secret = data.get("app_secret", "").strip()
    short_token = data.get("token", "").strip()
    page_id = data.get("page_id", "").strip()

    if not app_id or not app_secret or not short_token:
        raise HTTPException(status_code=400, detail="App ID, App Secret, and Access Token are required")

    res = await facebook_bot.exchange_for_permanent_page_token(
        app_id=app_id,
        app_secret=app_secret,
        user_or_page_token=short_token,
        page_id=page_id
    )
    if not res.get("success"):
        raise HTTPException(status_code=400, detail=res.get("error", "Token exchange failed"))
    return {"code": 0, "success": True, "message": res.get("message"), "token_preview": res.get("token_preview")}

@router.post("/api/admin/facebook/test-connection")
async def admin_facebook_test_connection(data: dict, request: Request):
    check_admin_auth(request)
    import facebook_bot
    cfg = facebook_bot.load_fb_config()
    page_id = (data.get("page_id") or cfg.get("page_id", facebook_bot.DEFAULT_PAGE_ID)).strip()
    page_token = (data.get("page_token") or cfg.get("page_token", "")).strip()

    # If user mistakenly passed their App ID as Page ID, auto-correct to the actual page_id
    if page_id == cfg.get("app_id") or page_id == "1654390072739197":
        page_id = cfg.get("page_id") or facebook_bot.DEFAULT_PAGE_ID

    if not page_token or "..." in page_token:
        page_token = cfg.get("page_token", "")

    if not page_id or not page_token:
        return {"code": 400, "success": False, "error": "Both Facebook Page ID and Access Token are required to test connection."}

    async with httpx.AsyncClient(timeout=12.0) as client:
        try:
            resp = await client.get(f"https://graph.facebook.com/v19.0/{page_id}?fields=id,name,link&access_token={page_token}")
            p_data = resp.json()
            if "error" in p_data:
                err = p_data["error"]
                msg = err.get("message", "Token validation failed")
                code = err.get("code")
                return {
                    "code": 400,
                    "success": False,
                    "error_code": code,
                    "error": msg,
                    "is_expired": code == 190 or "expired" in msg.lower()
                }

            page_name = p_data.get("name", "NXLY")
            return {
                "code": 0,
                "success": True,
                "page_name": page_name,
                "page_id": p_data.get("id"),
                "link": p_data.get("link", f"https://www.facebook.com/{page_id}"),
                "message": f"Successfully connected to Facebook Page '{page_name}' (ID: {page_id})! Token is valid and ready to publish."
            }
        except Exception as e:
            return {"code": 500, "success": False, "error": f"Network error testing connection: {e}"}

@router.post("/api/admin/facebook/post-poster")
async def admin_facebook_post_poster(request: Request):
    check_admin_auth(request)
    import facebook_bot
    poster_path = os.path.join(base_dir, "public", "poster_nxly_meme.jpg")
    if not os.path.exists(poster_path):
        poster_path = os.path.join(base_dir, "public", "poster_nxly_clean.jpg")

    caption = (
        "NOT ALL HEROES STREAM. SOME DOWNLOAD. 😎🍿\n"
        "━━━━━━━━━━━━━━━━━━━━━━━━━\n"
        "Need Internet? Buffering? Data Gone? ❌\n"
        "Download & Watch Offline in 1080p Anytime! ✔️\n\n"
        "🔍 Google-এ Search করুন: NXLY Stream\n"
        "👉 ১ম যে ওয়েবসাইট আসবে সেটাই! (First result is the one)\n\n"
        "💬 সরাসরি ডাউনলোড ও দেখার লিংক ১ম কমেন্টে (1st Comment)! 👇\n"
        "📲 Free Official Android App: https://nxly.online\n\n"
        "#nxlystream #movies #offline #download #meme #viral #legends #trending"
    )

    comment = (
        "🔍 Google Search: NXLY Stream (First result is the one!)\n\n"
        "🍿 Watch & Download All Movies/Shows in 1080p HD:\n"
        "👉 https://nxly.online\n\n"
        "📲 Download Free Official Android App (APK):\n"
        "👉 https://nxly.online/download"
    )

    res = await facebook_bot.post_photo_to_facebook(
        image_path=poster_path,
        caption=caption,
        comment_text=comment
    )
    if not res.get("success"):
        raise HTTPException(status_code=500, detail=res.get("error", "Photo upload failed"))
    return {
        "code": 0,
        "success": True,
        "message": "Meme Poster successfully published to Facebook Page NXLY!",
        "post_url": res.get("post_url"),
        "photo_id": res.get("photo_id")
    }

# ── YOUTUBE SHORTS BOT ────────────────────────────────────────────────────────
@router.get("/api/admin/youtube/config")
async def admin_youtube_get_config(request: Request):
    check_admin_auth(request)
    import youtube_bot
    cfg = youtube_bot.load_yt_config()
    conn_info = await youtube_bot.verify_youtube_connection()
    masked_secret = (cfg.get("client_secret", "")[:4] + "..." + cfg.get("client_secret", "")[-4:]) if len(cfg.get("client_secret", "")) > 8 else ""
    masked_refresh = (cfg.get("refresh_token", "")[:6] + "..." + cfg.get("refresh_token", "")[-6:]) if len(cfg.get("refresh_token", "")) > 12 else ""
    return {
        "code": 0,
        "config": {
            "client_id": cfg.get("client_id", ""),
            "masked_secret": masked_secret,
            "masked_refresh": masked_refresh,
            "channel_title": conn_info.get("channel_title") or cfg.get("channel_title", "YouTube Channel"),
            "auto_pilot_enabled": cfg.get("auto_pilot_enabled", False),
            "default_privacy": cfg.get("default_privacy", "public"),
            "last_post_time": cfg.get("last_post_time", 0)
        },
        "connection": conn_info
    }

@router.post("/api/admin/youtube/config")
async def admin_youtube_save_config(data: dict, request: Request):
    check_admin_auth(request)
    import youtube_bot
    cfg = youtube_bot.load_yt_config()
    if "client_id" in data:
        cfg["client_id"] = str(data["client_id"]).strip()
    if "client_secret" in data and data["client_secret"] and "..." not in data["client_secret"]:
        cfg["client_secret"] = str(data["client_secret"]).strip()
    if "refresh_token" in data and data["refresh_token"] and "..." not in data["refresh_token"]:
        cfg["refresh_token"] = str(data["refresh_token"]).strip()
    if "access_token" in data and data["access_token"] and "..." not in data["access_token"]:
        cfg["access_token"] = str(data["access_token"]).strip()
    if "auto_pilot_enabled" in data:
        cfg["auto_pilot_enabled"] = bool(data["auto_pilot_enabled"])
    if "default_privacy" in data:
        cfg["default_privacy"] = str(data["default_privacy"]).strip()

    youtube_bot.save_yt_config(cfg)
    return {"code": 0, "message": "YouTube configuration saved successfully!"}

@router.get("/api/admin/youtube/history")
async def admin_youtube_history(request: Request):
    check_admin_auth(request)
    import youtube_bot
    history = youtube_bot.load_yt_history()
    return {"code": 0, "history": history}

# ── TIKTOK BOT ───────────────────────────────────────────────────────────────
@router.get("/api/admin/tiktok/config")
async def admin_tiktok_get_config(request: Request):
    check_admin_auth(request)
    import tiktok_bot
    cfg = tiktok_bot.load_tt_config()
    conn_info = await tiktok_bot.verify_tiktok_connection()
    masked_token = (cfg.get("access_token", "")[:6] + "..." + cfg.get("access_token", "")[-6:]) if len(cfg.get("access_token", "")) > 12 else ""
    return {
        "code": 0,
        "config": {
            "client_key": cfg.get("client_key", ""),
            "masked_token": masked_token,
            "username": conn_info.get("username") or cfg.get("username", "TikTok Account"),
            "display_name": conn_info.get("display_name") or cfg.get("display_name", ""),
            "auto_pilot_enabled": cfg.get("auto_pilot_enabled", False),
            "privacy_level": cfg.get("privacy_level", "PUBLIC_TO_EVERYONE"),
            "last_post_time": cfg.get("last_post_time", 0)
        },
        "connection": conn_info
    }

@router.post("/api/admin/tiktok/config")
async def admin_tiktok_save_config(data: dict, request: Request):
    check_admin_auth(request)
    import tiktok_bot
    cfg = tiktok_bot.load_tt_config()
    if "client_key" in data:
        cfg["client_key"] = str(data["client_key"]).strip()
    if "client_secret" in data and data["client_secret"] and "..." not in data["client_secret"]:
        cfg["client_secret"] = str(data["client_secret"]).strip()
    if "access_token" in data and data["access_token"] and "..." not in data["access_token"]:
        cfg["access_token"] = str(data["access_token"]).strip()
    if "refresh_token" in data and data["refresh_token"] and "..." not in data["refresh_token"]:
        cfg["refresh_token"] = str(data["refresh_token"]).strip()
    if "auto_pilot_enabled" in data:
        cfg["auto_pilot_enabled"] = bool(data["auto_pilot_enabled"])
    if "privacy_level" in data:
        cfg["privacy_level"] = str(data["privacy_level"]).strip()

    tiktok_bot.save_tt_config(cfg)
    return {"code": 0, "message": "TikTok configuration saved successfully!"}

@router.get("/api/admin/tiktok/history")
async def admin_tiktok_history(request: Request):
    check_admin_auth(request)
    import tiktok_bot
    history = tiktok_bot.load_tt_history()
    return {"code": 0, "history": history}

# ── UNIFIED CONTENT AUTOMATION & N8N PIPELINE ─────────────────────────────────
@router.get("/api/admin/automation/overview")
async def admin_automation_overview(request: Request):
    check_admin_auth(request)
    import autopilot_queue
    return await autopilot_queue.get_dashboard_summary()

@router.get("/api/admin/automation/workflow")
async def admin_automation_workflow(request: Request):
    check_admin_auth(request)
    import autopilot_queue
    return autopilot_queue.get_workflow_graph_state()

@router.get("/api/admin/automation/jobs")
async def admin_automation_jobs(request: Request, status: str = "", type: str = "", search: str = ""):
    check_admin_auth(request)
    import autopilot_queue
    data = autopilot_queue.load_queue_data()
    jobs = data.get("jobs", [])
    if status:
        jobs = [j for j in jobs if j.get("status") == status]
    if type:
        jobs = [j for j in jobs if j.get("type") == type]
    if search:
        s_low = search.lower()
        jobs = [j for j in jobs if s_low in j.get("title", "").lower()]
    return {"code": 0, "jobs": jobs, "total": len(jobs)}

@router.post("/api/admin/automation/jobs/bulk-create")
async def admin_automation_jobs_bulk_create(data: dict, request: Request):
    check_admin_auth(request)
    import autopilot_queue
    items = data.get("items", [])
    config = data.get("config", {})
    res = await autopilot_queue.add_bulk_jobs_async(items, config)
    return res

@router.post("/api/admin/automation/jobs/{job_id}/action")
async def admin_automation_job_action(job_id: str, data: dict, request: Request):
    check_admin_auth(request)
    import autopilot_queue
    action = data.get("action", "")
    direction = data.get("direction")
    return autopilot_queue.execute_job_action(job_id, action, direction)

@router.get("/api/admin/automation/calendar")
async def admin_automation_calendar(request: Request):
    check_admin_auth(request)
    import autopilot_queue
    calendar = autopilot_queue.get_publishing_calendar()
    return {"code": 0, "calendar": calendar, "total": len(calendar)}

@router.get("/api/admin/automation/finished")
async def admin_automation_finished(request: Request):
    check_admin_auth(request)
    import autopilot_queue
    data = autopilot_queue.load_queue_data()
    return {"code": 0, "finished": data.get("finished", []), "total": len(data.get("finished", []))}

@router.post("/api/admin/automation/finished/requeue")
async def admin_automation_finished_requeue(data: dict, request: Request):
    check_admin_auth(request)
    import autopilot_queue
    job_id = str(data.get("job_id") or data.get("item_id", ""))
    success = autopilot_queue.requeue_finished(job_id)
    return {"code": 0 if success else 404, "success": success}

@router.delete("/api/admin/automation/finished/delete")
async def admin_automation_finished_delete(request: Request, job_id: str = ""):
    check_admin_auth(request)
    import autopilot_queue
    success = autopilot_queue.delete_finished(job_id)
    return {"code": 0 if success else 404, "success": success}

@router.get("/api/admin/automation/platforms")
async def admin_automation_platforms(request: Request):
    check_admin_auth(request)
    import autopilot_queue
    conns = await autopilot_queue.check_platform_connections()
    return {"code": 0, "platforms": conns}

@router.post("/api/admin/automation/platforms/toggle")
async def admin_automation_platforms_toggle(data: dict, request: Request):
    check_admin_auth(request)
    platform = str(data.get("platform", "")).lower().strip()
    enabled = bool(data.get("enabled", True))
    
    if platform == "facebook":
        from facebook_bot import load_fb_config, save_fb_config
        cfg = load_fb_config()
        cfg["auto_pilot_enabled"] = enabled
        save_fb_config(cfg)
    elif platform == "youtube":
        import youtube_bot
        cfg = youtube_bot.load_yt_config()
        cfg["auto_pilot_enabled"] = enabled
        youtube_bot.save_yt_config(cfg)
    elif platform == "tiktok":
        import tiktok_bot
        cfg = tiktok_bot.load_tt_config()
        cfg["auto_pilot_enabled"] = enabled
        tiktok_bot.save_tt_config(cfg)
    else:
        raise HTTPException(status_code=400, detail="Invalid platform name")
        
    return {
        "code": 0,
        "platform": platform,
        "enabled": enabled,
        "message": f"{platform.capitalize()} auto-pilot switch turned {'ON' if enabled else 'OFF'}"
    }

@router.post("/api/admin/automation/run-now")
async def admin_automation_run_now(request: Request):
    check_admin_auth(request)
    target_job_id = None
    try:
        body = await request.json()
        target_job_id = body.get("job_id")
    except Exception:
        pass
    import autopilot_queue
    from facebook_bot import bot_log
    bot_log(f"[Terminal] ⚡ Manual Auto-Pilot cycle triggered by Admin{' (Target Job: ' + target_job_id + ')' if target_job_id else ''}...")
    
    async def _safe_run():
        try:
            await autopilot_queue.process_next_queue_job(target_job_id=target_job_id)
        except Exception as e:
            import traceback
            bot_log(f"[AutoPilot] ❌ Background run exception: {e}")
            bot_log(traceback.format_exc())

    asyncio.create_task(_safe_run())
    return {
        "code": 0,
        "success": True,
        "message": "Auto-Pilot cycle triggered! Watch live terminal and visual workflow."
    }

# Legacy aliases for backwards compatibility
@router.get("/api/admin/autopilot/status")
async def admin_autopilot_status(request: Request):
    check_admin_auth(request)
    import autopilot_queue
    return await autopilot_queue.get_dashboard_summary()

@router.post("/api/admin/autopilot/run-now")
async def admin_autopilot_run_now_legacy(request: Request):
    check_admin_auth(request)
    import autopilot_queue
    asyncio.create_task(autopilot_queue.process_next_queue_job())
    return {"code": 0, "success": True, "message": "Cycle triggered"}

# ── SYSTEM PROCESS MANAGER & CLEANUP ──────────────────────────────────────────
@router.get("/api/admin/system/processes")
async def admin_get_processes(request: Request):
    check_admin_auth(request)
    import subprocess
    try:
        user = os.getenv("USER", "lcsyxfen")
        res = subprocess.run(["ps", "-u", user, "-o", "pid,ppid,%cpu,%mem,etime,args"], capture_output=True, text=True, timeout=6)
        lines = res.stdout.strip().split("\n")
        processes = []
        if len(lines) > 1:
            for l in lines[1:]:
                parts = l.strip().split(None, 5)
                if len(parts) >= 6:
                    processes.append({
                        "pid": parts[0],
                        "ppid": parts[1],
                        "cpu": parts[2],
                        "mem": parts[3],
                        "etime": parts[4],
                        "cmd": parts[5]
                    })
        # Sort by CPU usage desc
        processes.sort(key=lambda x: float(x.get("cpu", 0) or 0), reverse=True)
        return {"code": 0, "total": len(processes), "processes": processes}
    except Exception as e:
        return {"code": 500, "error": str(e)}

@router.post("/api/admin/system/cleanup-processes")
async def admin_cleanup_processes(request: Request):
    """Safely terminates zombie/stuck bash updates, duplicate cp commands, and hung scripts."""
    check_admin_auth(request)
    import subprocess
    import signal
    killed = []
    try:
        user = os.getenv("USER", "lcsyxfen")
        res = subprocess.run(["ps", "-u", user, "-o", "pid,args"], capture_output=True, text=True, timeout=6)
        my_pid = str(os.getpid())
        for line in res.stdout.strip().split("\n")[1:]:
            parts = line.strip().split(None, 1)
            if len(parts) >= 2:
                pid_str, cmd = parts[0], parts[1]
                # Kill stale bash deploy commands or redundant cp commands or stuck ffmpeg
                is_stale = (
                    "bash -c echo" in cmd or 
                    "cp -rf" in cmd or 
                    "sitemap_generator.py" in cmd or 
                    "ffmpeg" in cmd
                )
                if pid_str != my_pid and is_stale:
                    try:
                        os.kill(int(pid_str), signal.SIGKILL)
                        killed.append({"pid": pid_str, "cmd": cmd})
                    except Exception:
                        pass
        return {"code": 0, "killed_count": len(killed), "killed": killed}
    except Exception as e:
        return {"code": 500, "error": str(e)}

@router.post("/api/admin/system/kill-pid")
async def admin_kill_pid(data: dict, request: Request):
    check_admin_auth(request)
    import signal
    pid = int(data.get("pid", 0))
    if pid <= 0 or pid == os.getpid():
        raise HTTPException(status_code=400, detail="Cannot kill own process or invalid pid")
    try:
        os.kill(pid, signal.SIGKILL)
        return {"code": 0, "message": f"Process {pid} terminated"}
    except Exception as e:
        return {"code": 500, "error": str(e)}






