import os
import re
import json
import httpx
import time
import secrets
import hashlib
import hmac
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
    from main import get_db_pool
    pool = await get_db_pool()
    if not pool: return {"list": []}
    
    async with pool.acquire() as conn:
        async with conn.cursor(aiomysql.DictCursor) as cur:
            await cur.execute("""
                SELECT subject_id, title, cover, detail_path, subject_type 
                FROM subjects 
                WHERE title LIKE %s 
                LIMIT 50
            """, (f"%{q}%",))
            rows = await cur.fetchall()
            
            items = []
            for r in rows:
                items.append({
                    "subjectId": r["subject_id"],
                    "title": r["title"],
                    "coverUrl": r["cover"],
                    "detailPath": r["detail_path"],
                    "subjectType": r["subject_type"]
                })
            return {"list": items}

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
    pool = await get_db_pool()
    if not pool: return {"version": None}
    async with pool.acquire() as conn:
        async with conn.cursor(aiomysql.DictCursor) as cur:
            await cur.execute("SELECT * FROM app_versions ORDER BY version_code DESC LIMIT 1")
            row = await cur.fetchone()
            if not row:
                return {"version": None}
            return {
                "version": {
                    "versionCode": row["version_code"],
                    "versionName": row["version_name"],
                    "apkUrl": row["apk_url"],
                    "mustUpdate": bool(row["must_update"]),
                    "releaseNotes": row["release_notes"]
                }
            }

@router.get("/api/app/download")
async def public_download_latest():
    from main import get_db_pool
    pool = await get_db_pool()
    if not pool: raise HTTPException(status_code=404, detail="No releases found")
    async with pool.acquire() as conn:
        async with conn.cursor(aiomysql.DictCursor) as cur:
            await cur.execute("SELECT * FROM app_versions ORDER BY version_code DESC LIMIT 1")
            row = await cur.fetchone()
            if not row:
                raise HTTPException(status_code=404, detail="No releases found")
            
            return RedirectResponse(url=row["apk_url"])

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
