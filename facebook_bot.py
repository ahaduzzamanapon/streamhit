import os
import json
import time
import asyncio
import httpx
import urllib.parse
import aiomysql
from typing import Dict, Any, Optional

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
FB_CONFIG_FILE = os.path.join(BASE_DIR, "facebook_config.json")
FB_HISTORY_FILE = os.path.join(BASE_DIR, "public", "reels", "facebook_history.json")

DEFAULT_PAGE_ID = os.environ.get("FB_PAGE_ID", "1367186939808744")
DEFAULT_PAGE_TOKEN = os.environ.get("FB_PAGE_TOKEN", "")

def load_fb_config() -> Dict[str, Any]:
    if os.path.exists(FB_CONFIG_FILE):
        try:
            with open(FB_CONFIG_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return {
        "page_id": DEFAULT_PAGE_ID,
        "page_token": DEFAULT_PAGE_TOKEN,
        "page_name": "NXLY",
        "auto_pilot_enabled": True,
        "post_interval_hours": 6,
        "last_post_time": 0,
        "auto_comment_template": "🍿 Watch Full Movie/Show in 1080p HD on NXLY Stream:\n👉 {movie_url}\n\n📲 Download Free Official Android App:\n👉 https://nxly.online/download"
    }

def save_fb_config(cfg: Dict[str, Any]):
    try:
        with open(FB_CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2, ensure_ascii=False)
    except Exception as e:
        print(f"[FacebookBot] Error saving config: {e}")

def load_fb_history() -> list:
    if os.path.exists(FB_HISTORY_FILE):
        try:
            with open(FB_HISTORY_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return []

def save_fb_history(entry: Dict[str, Any]):
    try:
        os.makedirs(os.path.dirname(FB_HISTORY_FILE), exist_ok=True)
        history = load_fb_history()
        history.insert(0, entry)
        with open(FB_HISTORY_FILE, "w", encoding="utf-8") as f:
            json.dump(history[:100], f, indent=2, ensure_ascii=False)
    except Exception as e:
        print(f"[FacebookBot] Error saving history: {e}")

async def verify_facebook_connection(page_id: str, page_token: str) -> Dict[str, Any]:
    url = f"https://graph.facebook.com/v19.0/{page_id}?access_token={page_token}&fields=id,name,link,picture,followers_count"
    async with httpx.AsyncClient(timeout=10.0) as client:
        resp = await client.get(url)
        data = resp.json()
        if "error" in data:
            return {"success": False, "error": data["error"].get("message", "Unknown error")}
        return {
            "success": True,
            "id": data.get("id"),
            "name": data.get("name"),
            "link": data.get("link"),
            "followers_count": data.get("followers_count", 0),
            "picture": data.get("picture", {}).get("data", {}).get("url")
        }

async def upload_reel_to_facebook(
    video_path: str,
    caption: str,
    comment_text: Optional[str] = None
) -> Dict[str, Any]:
    """
    Publishes a vertical MP4 video to Facebook Reels using the Graph API Reels Publishing flow.
    Automatically posts a comment with the site/app link once published.
    """
    if not os.path.exists(video_path):
        return {"success": False, "error": f"Video file not found: {video_path}"}

    cfg = load_fb_config()
    page_id = cfg.get("page_id", DEFAULT_PAGE_ID)
    page_token = cfg.get("page_token", DEFAULT_PAGE_TOKEN)

    file_size = os.path.getsize(video_path)
    print(f"[FacebookBot] Starting Reels upload ({file_size} bytes)...")

    async with httpx.AsyncClient(timeout=180.0) as client:
        # Phase 1: Initialize session
        init_url = f"https://graph.facebook.com/v19.0/{page_id}/video_reels"
        init_resp = await client.post(init_url, data={
            "upload_phase": "start",
            "access_token": page_token
        })
        init_data = init_resp.json()
        if "error" in init_data:
            return {"success": False, "error": init_data["error"].get("message", "Init failed")}

        video_id = init_data.get("video_id")
        upload_url = init_data.get("upload_url")
        if not video_id or not upload_url:
            return {"success": False, "error": "Invalid init response from Facebook"}

        print(f"[FacebookBot] Initialized video_id: {video_id}")

        # Phase 2: Upload Binary
        with open(video_path, "rb") as vf:
            video_bytes = vf.read()

        headers = {
            "Authorization": f"OAuth {page_token}",
            "offset": "0",
            "file_size": str(file_size)
        }
        upload_resp = await client.post(upload_url, headers=headers, content=video_bytes)
        if upload_resp.status_code not in (200, 201):
            return {"success": False, "error": f"Binary upload failed: {upload_resp.text[:300]}"}

        print(f"[FacebookBot] Binary uploaded successfully.")

        # Phase 3: Finish and Publish
        finish_resp = await client.post(init_url, data={
            "upload_phase": "finish",
            "access_token": page_token,
            "video_id": video_id,
            "video_state": "PUBLISHED",
            "description": caption
        })
        finish_data = finish_resp.json()
        if "error" in finish_data:
            return {"success": False, "error": finish_data["error"].get("message", "Finish failed")}

        reel_url = f"https://www.facebook.com/reel/{video_id}"
        print(f"[FacebookBot] Reel published successfully: {reel_url}")

        # Phase 4: Wait 6s for processing and post 1st Comment
        comment_id = None
        if comment_text:
            print("[FacebookBot] Waiting 6 seconds before posting auto-comment...")
            await asyncio.sleep(6)
            try:
                comment_url = f"https://graph.facebook.com/v19.0/{video_id}/comments"
                c_resp = await client.post(comment_url, data={
                    "access_token": page_token,
                    "message": comment_text
                })
                c_data = c_resp.json()
                if "id" in c_data:
                    comment_id = c_data["id"]
                    print(f"[FacebookBot] Auto-comment posted: {comment_id}")
                else:
                    print(f"[FacebookBot] Comment note: {c_data}")
            except Exception as ce:
                print(f"[FacebookBot] Comment exception: {ce}")

        return {
            "success": True,
            "video_id": video_id,
            "reel_url": reel_url,
            "comment_id": comment_id
        }

async def publish_existing_reel(
    filename: str,
    caption: str = "",
    title: str = "",
    subject_id: str = ""
) -> Dict[str, Any]:
    """
    Publishes an existing reel MP4 file to Facebook Reels and posts the first comment.
    """
    import reel_generator
    video_path = os.path.join(reel_generator.REELS_DIR, filename)
    if not os.path.exists(video_path):
        return {"success": False, "error": f"Reel file not found on server: {filename}"}

    cfg = load_fb_config()
    template = cfg.get("auto_comment_template", "")
    movie_url = f"https://nxly.online/watch?id={subject_id}" if subject_id else "https://nxly.online"
    comment_text = template.replace("{movie_url}", movie_url).replace("{title}", title or "this title")

    final_caption = caption or f"🔥 {title or 'Trending Cinema'} • Watch Full HD on NXLY Stream 🍿\n\n#nxlystream #reels #viral #trending #shorts"

    res = await upload_reel_to_facebook(
        video_path=video_path,
        caption=final_caption,
        comment_text=comment_text
    )
    if res.get("success"):
        save_fb_history({
            "title": title or filename,
            "video_id": res.get("video_id"),
            "reel_url": res.get("reel_url"),
            "timestamp": int(time.time()),
            "status": "published",
            "type": "manual_reel"
        })
    return res

async def trigger_auto_trending_reel(custom_title: Optional[str] = None, custom_url: Optional[str] = None) -> Dict[str, Any]:
    """
    Automated workflow:
    1. Selects highest trending movie or series from DB or API
    2. Uses reel_generator to create a 60s anti-copyright viral reel
    3. Uploads reel to Facebook Page NXLY
    4. Auto-posts comment with direct movie watch link and app download link
    """
    import main
    import reel_generator

    print("[FacebookBot] Initiating Auto-Trending Reel creation...")
    pool = await main.get_db_pool()
    selected_subject = None

    if custom_title and custom_url:
        target_title = custom_title
        stream_url = custom_url
        detail_path = ""
        subject_id = ""
    else:
        # Fetch trending items from DB
        items = []
        if pool:
            try:
                async with pool.acquire() as conn:
                    async with conn.cursor(aiomysql.DictCursor) as cur:
                        await cur.execute("""
                            SELECT subject_id, title, detail_path, subject_type 
                            FROM subjects 
                            WHERE detail_path IS NOT NULL AND detail_path != ''
                            ORDER BY updated_at DESC LIMIT 30
                        """)
                        items = await cur.fetchall()
            except Exception as dbe:
                print(f"[FacebookBot] DB query fallback: {dbe}")

        if not items:
            # Fallback to homepage trending items
            try:
                home_data = await main.fetch_home_data()
                banner_list = home_data.get("data", {}).get("banners", [])
                for b in banner_list:
                    items.append({
                        "subject_id": str(b.get("subjectId") or ""),
                        "title": b.get("title") or "Popular Cinema",
                        "detail_path": b.get("detailPath") or "",
                        "subject_type": b.get("subjectType", 1)
                    })
            except Exception as he:
                print(f"[FacebookBot] Home data error: {he}")

        if not items:
            return {"success": False, "error": "No media subjects available for reels generation"}

        # Select an item that hasn't been posted recently
        history = load_fb_history()
        posted_titles = {h.get("title") for h in history}
        candidates = [it for it in items if it["title"] not in posted_titles]
        selected_subject = candidates[0] if candidates else items[0]

        target_title = selected_subject["title"]
        subject_id = selected_subject["subject_id"]
        detail_path = selected_subject["detail_path"]

        # Resolve streaming resource URL
        res_data = await main.api_resource(se=1, ep=1, detailPath=detail_path, subjectId=subject_id)
        res_list = res_data.get("data", {}).get("list", [])
        if not res_list:
            return {"success": False, "error": f"No playable streams found for '{target_title}'"}

        raw_link = res_list[0].get("url") or res_list[0].get("resourceLink") or ""
        if "source_url=" in raw_link:
            import urllib.parse
            stream_url = urllib.parse.unquote(raw_link.split("source_url=")[-1])
        else:
            stream_url = raw_link

        if not stream_url:
            return {"success": False, "error": f"Invalid stream url for '{target_title}'"}

    # Generate 60s Reel Video
    slug = "".join(c for c in target_title if c.isalnum() or c in (" ", "_")).strip().replace(" ", "_")[:25]
    timestamp = int(time.time())
    output_filename = f"fb_reel_{slug}_{timestamp}.mp4"
    output_path = os.path.join(reel_generator.REELS_DIR, output_filename)

    # Create viral overlay banner
    overlay_filename = f"fb_overlay_{slug}_{timestamp}.png"
    overlay_path = os.path.join(reel_generator.REELS_DIR, overlay_filename)
    reel_generator.generate_overlay_image(
        title=target_title,
        part_num=1,
        total_parts=1,
        top_text_custom=f"🔥 {target_title} 🍿",
        bottom_text_custom="👇 FULL MOVIE LINK IN 1ST COMMENT 👇",
        output_path=overlay_path
    )

    # Cut 60 seconds starting from 300s (5:00 min) where action usually peaks
    start_seconds = 300
    duration_seconds = 60
    print(f"[FacebookBot] Rendering 60s clip for '{target_title}'...")

    success = await reel_generator.render_single_reel_part(
        stream_url=stream_url,
        start_seconds=start_seconds,
        duration_seconds=duration_seconds,
        overlay_png_path=overlay_path,
        output_mp4_path=output_path,
        anti_copyright=True
    )

    if os.path.exists(overlay_path):
        try: os.remove(overlay_path)
        except Exception: pass

    if not success or not os.path.exists(output_path):
        return {"success": False, "error": "FFmpeg Reel generation failed"}

    # Prepare Caption & Comment
    watch_url = f"https://nxly.online/watch?id={subject_id}" if subject_id else "https://nxly.online"
    caption = (
        f"🔥 {target_title} 🎬🍿\n"
        f"━━━━━━━━━━━━━━━━━━\n"
        f"🍿 Watch Full in 1080p HD: 👉 {watch_url}\n"
        f"📲 Free Android App Download: 👉 https://nxly.online/download\n\n"
        f"#reels #shorts #viral #movies #trending #nxly #{slug.lower()}"
    )

    cfg = load_fb_config()
    comment_tpl = cfg.get("auto_comment_template", "🍿 Watch Full in 1080p HD on NXLY:\n👉 {movie_url}\n\n📲 Download Official App:\n👉 https://nxly.online/download")
    comment_msg = comment_tpl.replace("{movie_url}", watch_url).replace("{title}", target_title)

    # Upload to Facebook
    upload_res = await upload_reel_to_facebook(
        video_path=output_path,
        caption=caption,
        comment_text=comment_msg
    )

    if upload_res.get("success"):
        cfg["last_post_time"] = timestamp
        save_fb_config(cfg)

        history_entry = {
            "title": target_title,
            "video_id": upload_res.get("video_id"),
            "reel_url": upload_res.get("reel_url"),
            "comment_id": upload_res.get("comment_id"),
            "file_size_mb": round(os.path.getsize(output_path) / (1024 * 1024), 2),
            "created_at": time.strftime("%Y-%m-%d %H:%M:%S")
        }
        save_fb_history(history_entry)

    return {
        "success": upload_res.get("success", False),
        "title": target_title,
        "reel_url": upload_res.get("reel_url"),
        "error": upload_res.get("error")
    }

# Background auto-pilot scheduler loop
async def fb_auto_pilot_loop():
    print("[FacebookBot] Auto-Pilot background daemon initialized.")
    while True:
        try:
            await asyncio.sleep(120) # check every 2 mins
            cfg = load_fb_config()
            if not cfg.get("auto_pilot_enabled", False):
                continue

            interval_sec = int(cfg.get("post_interval_hours", 6)) * 3600
            now = int(time.time())
            last_post = int(cfg.get("last_post_time", 0))

            if now - last_post >= interval_sec:
                print("[FacebookBot] Triggering scheduled Auto-Pilot Reel...")
                await trigger_auto_trending_reel()
        except Exception as e:
            print(f"[FacebookBot] Auto-pilot loop error: {e}")
            await asyncio.sleep(60)
