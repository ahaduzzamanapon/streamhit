import os
import json
import time
import httpx
from typing import Dict, Any, Optional

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
TT_CONFIG_FILE = os.path.join(BASE_DIR, "tiktok_config.json")
TT_HISTORY_FILE = os.path.join(BASE_DIR, "public", "reels", "tiktok_history.json")

def load_tt_config() -> Dict[str, Any]:
    if os.path.exists(TT_CONFIG_FILE):
        try:
            with open(TT_CONFIG_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return {
        "client_key": "",
        "client_secret": "",
        "access_token": "",
        "refresh_token": "",
        "token_expiry": 0,
        "username": "",
        "display_name": "",
        "auto_pilot_enabled": False,
        "privacy_level": "PUBLIC_TO_EVERYONE",  # PUBLIC_TO_EVERYONE, MUTUAL_FOLLOW_FRIENDS, SELF_ONLY
        "last_post_time": 0
    }

def save_tt_config(cfg: Dict[str, Any]):
    try:
        with open(TT_CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2, ensure_ascii=False)
    except Exception as e:
        print(f"[TikTokBot] Error saving config: {e}")

def load_tt_history() -> list:
    if os.path.exists(TT_HISTORY_FILE):
        try:
            with open(TT_HISTORY_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return []

def save_tt_history(entry: Dict[str, Any]):
    try:
        os.makedirs(os.path.dirname(TT_HISTORY_FILE), exist_ok=True)
        history = load_tt_history()
        history.insert(0, entry)
        with open(TT_HISTORY_FILE, "w", encoding="utf-8") as f:
            json.dump(history[:100], f, indent=2, ensure_ascii=False)
    except Exception as e:
        print(f"[TikTokBot] Error saving history: {e}")

async def get_valid_access_token() -> Optional[str]:
    cfg = load_tt_config()
    client_key = cfg.get("client_key", "").strip()
    client_secret = cfg.get("client_secret", "").strip()
    refresh_token = cfg.get("refresh_token", "").strip()
    access_token = cfg.get("access_token", "").strip()
    token_expiry = cfg.get("token_expiry", 0)

    now = int(time.time())
    if access_token and token_expiry > now + 60:
        return access_token

    if not refresh_token:
        return access_token or None

    try:
        async with httpx.AsyncClient(timeout=20.0) as client:
            token_url = "https://open.tiktokapis.com/v2/oauth/token/"
            headers = {"Content-Type": "application/x-www-form-urlencoded"}
            data = {
                "client_key": client_key,
                "client_secret": client_secret,
                "grant_type": "refresh_token",
                "refresh_token": refresh_token
            }
            resp = await client.post(token_url, headers=headers, data=data)
            res_json = resp.json()
            if "access_token" in res_json:
                new_token = res_json["access_token"]
                expires_in = int(res_json.get("expires_in", 86400))
                cfg["access_token"] = new_token
                cfg["token_expiry"] = now + expires_in
                if "refresh_token" in res_json:
                    cfg["refresh_token"] = res_json["refresh_token"]
                save_tt_config(cfg)
                return new_token
            else:
                print(f"[TikTokBot] Token refresh response: {res_json}")
                return access_token or None
    except Exception as e:
        print(f"[TikTokBot] Error refreshing token: {e}")
        return access_token or None

async def verify_tiktok_connection() -> Dict[str, Any]:
    token = await get_valid_access_token()
    if not token:
        return {"connected": False, "error": "No valid TikTok Access Token configured"}

    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            url = "https://open.tiktokapis.com/v2/user/info/?fields=open_id,union_id,avatar_url,display_name,username"
            headers = {"Authorization": f"Bearer {token}"}
            r = await client.get(url, headers=headers)
            res = r.json()
            data = res.get("data", {}).get("user", {})
            if data and (data.get("display_name") or data.get("username")):
                display_name = data.get("display_name") or data.get("username", "")
                username = data.get("username") or display_name
                cfg = load_tt_config()
                cfg["display_name"] = display_name
                cfg["username"] = username
                save_tt_config(cfg)
                return {
                    "connected": True,
                    "display_name": display_name,
                    "username": username,
                    "avatar_url": data.get("avatar_url", "")
                }
            else:
                err_msg = res.get("error", {}).get("message", "Could not fetch TikTok user info")
                return {"connected": False, "error": err_msg}
    except Exception as e:
        return {"connected": False, "error": str(e)}

async def upload_video_to_tiktok(
    video_path: str,
    caption: str
) -> Dict[str, Any]:
    """
    Uploads vertical video directly to TikTok via TikTok Content Posting API v2.
    """
    if not os.path.exists(video_path):
        return {"success": False, "error": f"Video file not found: {video_path}"}

    token = await get_valid_access_token()
    if not token:
        return {"success": False, "error": "Missing TikTok Access Token"}

    cfg = load_tt_config()
    privacy = cfg.get("privacy_level", "PUBLIC_TO_EVERYONE")
    file_size = os.path.getsize(video_path)

    # Ensure hashtags in caption
    clean_caption = caption.strip()
    if "#" not in clean_caption:
        clean_caption = f"{clean_caption} #shorts #movies #fyp #viral #nxly"

    print(f"[TikTokBot] Initiating Content Posting API v2 for '{clean_caption[:50]}' ({file_size} bytes)...")

    payload = {
        "post_info": {
            "title": clean_caption[:150],
            "privacy_level": privacy,
            "disable_duet": False,
            "disable_comment": False,
            "disable_stitch": False,
            "video_cover_timestamp_ms": 1000
        },
        "source_info": {
            "source": "FILE_UPLOAD",
            "video_size": file_size,
            "chunk_size": file_size,
            "total_chunk_count": 1
        }
    }

    async with httpx.AsyncClient(timeout=180.0) as client:
        try:
            # Phase 1: Initialize Direct Video Upload
            init_url = "https://open.tiktokapis.com/v2/post/publish/video/init/"
            init_headers = {
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json; charset=UTF-8"
            }
            init_resp = await client.post(init_url, headers=init_headers, json=payload)
            init_data = init_resp.json()

            if "error" in init_data and init_data["error"].get("code") != "ok":
                err_msg = init_data["error"].get("message", "Init failed")
                print(f"[TikTokBot] Init failed: {err_msg}")
                return {"success": False, "error": f"TikTok API error: {err_msg}"}

            data_obj = init_data.get("data", {})
            publish_id = data_obj.get("publish_id")
            upload_url = data_obj.get("upload_url")

            if not upload_url:
                return {"success": False, "error": "No upload_url received from TikTok API"}

            # Phase 2: Upload Binary Video
            with open(video_path, "rb") as vf:
                video_data = vf.read()

            put_headers = {
                "Content-Type": "video/mp4",
                "Content-Length": str(file_size),
                "Content-Range": f"bytes 0-{file_size - 1}/{file_size}"
            }
            put_resp = await client.put(upload_url, headers=put_headers, content=video_data)
            if put_resp.status_code not in (200, 201):
                return {"success": False, "error": f"Binary upload failed: {put_resp.text[:300]}"}

            print(f"[TikTokBot] ✅ TikTok Video uploaded successfully! Publish ID: {publish_id}")

            history_entry = {
                "title": clean_caption[:100],
                "publish_id": publish_id,
                "created_at": time.strftime("%Y-%m-%d %H:%M:%S")
            }
            save_tt_history(history_entry)

            cfg["last_post_time"] = int(time.time())
            save_tt_config(cfg)

            return {
                "success": True,
                "publish_id": publish_id
            }
        except Exception as e:
            print(f"[TikTokBot] Upload exception: {e}")
            return {"success": False, "error": str(e)}
