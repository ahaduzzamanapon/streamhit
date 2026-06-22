import os
import time
import secrets
import hashlib
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

router = APIRouter()
ADMIN_SESSIONS = {}

def check_admin_auth(request: Request):
    token = request.headers.get("Authorization")
    if token and token.startswith("Bearer "):
        token = token[7:]
    else:
        token = request.cookies.get("admin_token")
        
    if not token or token not in ADMIN_SESSIONS:
        raise HTTPException(status_code=401, detail="Unauthorized")
        
    session = ADMIN_SESSIONS[token]
    if time.time() > session["expires"]:
        ADMIN_SESSIONS.pop(token, None)
        raise HTTPException(status_code=401, detail="Session expired")
        
    return session

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
    token = secrets.token_hex(32)
    ADMIN_SESSIONS[token] = {
        "username": username,
        "expires": time.time() + 86400
    }
    
    response.set_cookie(key="admin_token", value=token, max_age=86400, httponly=True)
    return {"code": 0, "token": token, "message": "Login successful"}

@router.post("/api/admin/logout")
async def admin_logout(request: Request, response: Response):
    token = request.cookies.get("admin_token")
    if token:
        ADMIN_SESSIONS.pop(token, None)
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
