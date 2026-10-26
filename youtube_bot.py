import os
import json
import time
import httpx
from typing import Dict, Any, Optional

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
YT_CONFIG_FILE = os.path.join(BASE_DIR, "youtube_config.json")
YT_HISTORY_FILE = os.path.join(BASE_DIR, "public", "reels", "youtube_history.json")

def load_yt_config() -> Dict[str, Any]:
    if os.path.exists(YT_CONFIG_FILE):
        try:
            with open(YT_CONFIG_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return {
        "client_id": "",
        "client_secret": "",
        "refresh_token": "",
        "access_token": "",
        "token_expiry": 0,
        "channel_title": "",
        "auto_pilot_enabled": False,
        "default_privacy": "public",  # public, unlisted, private
        "last_post_time": 0
    }

def save_yt_config(cfg: Dict[str, Any]):
    try:
        with open(YT_CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2, ensure_ascii=False)
    except Exception as e:
        print(f"[YouTubeBot] Error saving config: {e}")

def load_yt_history() -> list:
    if os.path.exists(YT_HISTORY_FILE):
        try:
            with open(YT_HISTORY_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return []

def save_yt_history(entry: Dict[str, Any]):
    try:
        os.makedirs(os.path.dirname(YT_HISTORY_FILE), exist_ok=True)
        history = load_yt_history()
        history.insert(0, entry)
        with open(YT_HISTORY_FILE, "w", encoding="utf-8") as f:
            json.dump(history[:100], f, indent=2, ensure_ascii=False)
    except Exception as e:
        print(f"[YouTubeBot] Error saving history: {e}")

async def get_valid_access_token() -> Optional[str]:
    cfg = load_yt_config()
    client_id = cfg.get("client_id", "").strip()
    client_secret = cfg.get("client_secret", "").strip()
    refresh_token = cfg.get("refresh_token", "").strip()
    access_token = cfg.get("access_token", "").strip()
    token_expiry = cfg.get("token_expiry", 0)

    now = int(time.time())
    if access_token and token_expiry > now + 60:
        return access_token

    if not refresh_token:
        # Fall back to access_token if no refresh token
        return access_token or None

    # Refresh the OAuth2 access token
    try:
        async with httpx.AsyncClient(timeout=20.0) as client:
            token_url = "https://oauth2.googleapis.com/token"
            data = {
                "client_id": client_id,
                "client_secret": client_secret,
                "refresh_token": refresh_token,
                "grant_type": "refresh_token"
            }
            resp = await client.post(token_url, data=data)
            res_json = resp.json()
            if "access_token" in res_json:
                new_token = res_json["access_token"]
                expires_in = int(res_json.get("expires_in", 3600))
                cfg["access_token"] = new_token
                cfg["token_expiry"] = now + expires_in
                save_yt_config(cfg)
                return new_token
            else:
                print(f"[YouTubeBot] Token refresh failed: {res_json}")
                return None
    except Exception as e:
        print(f"[YouTubeBot] Error refreshing token: {e}")
        return None

async def verify_youtube_connection() -> Dict[str, Any]:
    token = await get_valid_access_token()
    if not token:
        return {"connected": False, "error": "No valid Access Token or Refresh Token"}

    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            url = "https://www.googleapis.com/youtube/v3/channels?part=snippet,statistics&mine=true"
            headers = {"Authorization": f"Bearer {token}"}
            r = await client.get(url, headers=headers)
            data = r.json()
            if "items" in data and len(data["items"]) > 0:
                item = data["items"][0]
                snippet = item.get("snippet", {})
                stats = item.get("statistics", {})
                channel_title = snippet.get("title", "My YouTube Channel")
                cfg = load_yt_config()
                cfg["channel_title"] = channel_title
                save_yt_config(cfg)
                return {
                    "connected": True,
                    "channel_id": item.get("id"),
                    "channel_title": channel_title,
                    "subscribers": stats.get("subscriberCount", "0"),
                    "custom_url": snippet.get("customUrl", "")
                }
            else:
                err_msg = data.get("error", {}).get("message", "Could not fetch channel details")
                return {"connected": False, "error": err_msg}
    except Exception as e:
        return {"connected": False, "error": str(e)}

async def upload_shorts_to_youtube(
    video_path: str,
    title: str,
    description: str,
    comment_text: str = ""
) -> Dict[str, Any]:
    """
    Uploads vertical video directly as YouTube Shorts via YouTube Data API v3 Resumable Upload.
    """
    if not os.path.exists(video_path):
        return {"success": False, "error": f"Video file not found: {video_path}"}

    token = await get_valid_access_token()
    if not token:
        return {"success": False, "error": "Missing or expired YouTube access token / credentials"}

    cfg = load_yt_config()
    privacy = cfg.get("default_privacy", "public")
    file_size = os.path.getsize(video_path)

    # Ensure title has #Shorts tag
    clean_title = title.strip()
    if "#shorts" not in clean_title.lower():
        clean_title = f"{clean_title[:85]} #Shorts"

    print(f"[YouTubeBot] Initiating Resumable Upload for Shorts: '{clean_title}' ({file_size} bytes)...")

    metadata = {
        "snippet": {
            "title": clean_title[:100],
            "description": description[:4500],
            "tags": ["shorts", "reels", "viral", "movies", "trending", "nxly", "cinema"],
            "categoryId": "1"  # Film & Animation
        },
        "status": {
            "privacyStatus": privacy,
            "selfDeclaredMadeForKids": False
        }
    }

    async with httpx.AsyncClient(timeout=180.0) as client:
        try:
            # Phase 1: Initialize Resumable Upload
            init_url = "https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status"
            init_headers = {
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json; charset=UTF-8",
                "X-Upload-Content-Length": str(file_size),
                "X-Upload-Content-Type": "video/mp4"
            }
            init_resp = await client.post(init_url, headers=init_headers, json=metadata)
            if init_resp.status_code not in (200, 201):
                err_text = init_resp.text[:300]
                print(f"[YouTubeBot] Resumable init failed: {err_text}")
                return {"success": False, "error": f"YouTube API init failed: {err_text}"}

            upload_location = init_resp.headers.get("Location")
            if not upload_location:
                return {"success": False, "error": "No resumable upload location header received"}

            # Phase 2: Binary Video Upload
            with open(video_path, "rb") as vf:
                video_data = vf.read()

            put_headers = {
                "Authorization": f"Bearer {token}",
                "Content-Type": "video/mp4",
                "Content-Length": str(file_size)
            }
            put_resp = await client.put(upload_location, headers=put_headers, content=video_data)
            put_data = put_resp.json()

            if "id" not in put_data:
                err_msg = put_data.get("error", {}).get("message", "Upload failed")
                return {"success": False, "error": err_msg}

            video_id = put_data["id"]
            video_url = f"https://www.youtube.com/shorts/{video_id}"
            print(f"[YouTubeBot] ✅ YouTube Shorts published successfully: {video_url}")

            # Phase 3: Post 1st Comment on YouTube Video
            comment_id = None
            if comment_text and video_id:
                try:
                    c_url = "https://www.googleapis.com/youtube/v3/commentThreads?part=snippet"
                    c_body = {
                        "snippet": {
                            "videoId": video_id,
                            "topLevelComment": {
                                "snippet": {
                                    "textOriginal": comment_text
                                }
                            }
                        }
                    }
                    c_resp = await client.post(c_url, headers={"Authorization": f"Bearer {token}"}, json=c_body)
                    c_json = c_resp.json()
                    comment_id = c_json.get("id")
                    if comment_id:
                        print(f"[YouTubeBot] ✅ Auto 1st comment posted on YouTube Shorts: {comment_id}")
                except Exception as ce:
                    print(f"[YouTubeBot] YouTube comment notice: {ce}")

            history_entry = {
                "title": clean_title,
                "video_id": video_id,
                "video_url": video_url,
                "comment_id": comment_id,
                "created_at": time.strftime("%Y-%m-%d %H:%M:%S")
            }
            save_yt_history(history_entry)

            cfg["last_post_time"] = int(time.time())
            save_yt_config(cfg)

            return {
                "success": True,
                "video_id": video_id,
                "video_url": video_url,
                "comment_id": comment_id
            }
        except Exception as e:
            print(f"[YouTubeBot] Upload exception: {e}")
            return {"success": False, "error": str(e)}
