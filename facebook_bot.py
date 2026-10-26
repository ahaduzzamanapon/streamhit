import os
import json
import time
import random
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
        "post_interval_minutes": 10,
        "post_interval_hours": 0.166,
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

async def exchange_for_permanent_page_token(
    app_id: str,
    app_secret: str,
    user_or_page_token: str,
    page_id: str = ""
) -> Dict[str, Any]:
    """
    Exchanges a short-lived token using App ID and App Secret into a
    Permanent (Never-Expiring) Page Access Token via Meta Graph API.
    """
    cfg = load_fb_config()
    target_page_id = page_id.strip() or cfg.get("page_id", DEFAULT_PAGE_ID)

    async with httpx.AsyncClient(timeout=20.0) as client:
        # Step 1: Exchange for long-lived user token (valid 60 days)
        exchange_url = "https://graph.facebook.com/v19.0/oauth/access_token"
        params = {
            "grant_type": "fb_exchange_token",
            "client_id": app_id.strip(),
            "client_secret": app_secret.strip(),
            "fb_exchange_token": user_or_page_token.strip()
        }
        resp = await client.get(exchange_url, params=params)
        data = resp.json()
        if "error" in data:
            return {"success": False, "error": data["error"].get("message", "Token exchange failed")}

        long_lived_user_token = data.get("access_token")
        if not long_lived_user_token:
            return {"success": False, "error": "Could not obtain long-lived token from Meta"}

        # Step 2: Fetch permanent Page Access Token using the long-lived user token
        page_url = f"https://graph.facebook.com/v19.0/{target_page_id}"
        p_resp = await client.get(page_url, params={
            "fields": "access_token,name",
            "access_token": long_lived_user_token
        })
        p_data = p_resp.json()
        if "error" in p_data:
            return {"success": False, "error": p_data["error"].get("message", "Page token fetch failed")}

        permanent_page_token = p_data.get("access_token")
        if not permanent_page_token:
            return {"success": False, "error": f"No page token returned for Page ID {target_page_id}. Ensure you are an Admin of this Page."}

        # Save to config automatically
        cfg["page_token"] = permanent_page_token
        cfg["app_id"] = app_id.strip()
        cfg["app_secret"] = app_secret.strip()
        cfg["page_id"] = target_page_id
        cfg["page_name"] = p_data.get("name", "NXLY")
        save_fb_config(cfg)

        return {
            "success": True,
            "message": f"Successfully generated & saved Permanent Never-Expiring Page Token for '{p_data.get('name')}'!",
            "page_name": p_data.get("name"),
            "token_preview": permanent_page_token[:15] + "..." + permanent_page_token[-10:]
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

        # Phase 4: Copyright Verification & Instant Auto-Delete Guard
        print(f"[FacebookBot] Verifying copyright compliance for Reel {video_id}...")
        has_copyright_violation = False
        copyright_reason = ""

        for check_idx in range(4):
            await asyncio.sleep(6)
            try:
                status_url = f"https://graph.facebook.com/v19.0/{video_id}?fields=id,status,copyright_check_status&access_token={page_token}"
                s_resp = await client.get(status_url)
                s_data = s_resp.json()
                if "error" in s_data:
                    err_msg = s_data["error"].get("message", "")
                    if "does not exist" in err_msg or "cannot be loaded" in err_msg or "blocked" in err_msg.lower():
                        has_copyright_violation = True
                        copyright_reason = f"Blocked/Restricted by Meta: {err_msg}"
                        break
                    continue
                
                status_obj = s_data.get("status", {})
                cp_check = status_obj.get("copyright_check_status") or s_data.get("copyright_check_status") or {}
                pub_phase = status_obj.get("publishing_phase", {})

                if cp_check.get("matches_found") is True:
                    has_copyright_violation = True
                    copyright_reason = "Rights Manager detected copyrighted audio/video"
                    break
                
                if pub_phase.get("publish_status") in ("blocked", "rejected", "restricted", "copyright_violation"):
                    has_copyright_violation = True
                    copyright_reason = f"Publishing rejected: {pub_phase.get('publish_status')}"
                    break

                if cp_check.get("status") == "complete" and cp_check.get("matches_found") is False:
                    print(f"[FacebookBot] Copyright check passed for {video_id} (0 matches)")
                    break
            except Exception as se:
                print(f"[FacebookBot] Copyright check note ({check_idx+1}): {se}")

        if has_copyright_violation:
            print(f"[FacebookBot] ⚠️ COPYRIGHT DETECTED for {video_id} ({copyright_reason})!")
            print(f"[FacebookBot] 🗑️ AUTO-DELETING REEL {video_id} IMMEDIATELY FROM FACEBOOK...")
            try:
                del_url = f"https://graph.facebook.com/v19.0/{video_id}?access_token={page_token}"
                del_resp = await client.delete(del_url)
                print(f"[FacebookBot] Deleted from FB: {del_resp.text}")
            except Exception as de:
                print(f"[FacebookBot] Delete call exception: {de}")
            
            # Clean up local video file
            if os.path.exists(video_path):
                try: os.remove(video_path)
                except Exception: pass

            return {
                "success": False,
                "error": f"Copyright detected ({copyright_reason}). Reel was automatically deleted from Facebook to protect the Page.",
                "video_id": video_id,
                "copyright_blocked": True
            }

        # Phase 5: Wait and post 1st Comment once the Reel is fully published
        comment_id = None
        if comment_text:
            print(f"[FacebookBot] Waiting for Reel {video_id} to finish processing before posting auto-comment...")
            # Try for up to 90 seconds (15 attempts * 6 seconds) to ensure video is ready for comments
            for comment_attempt in range(15):
                await asyncio.sleep(6)
                try:
                    # Attempt 1: Post to /{video_id}/comments
                    c_resp = await client.post(
                        f"https://graph.facebook.com/v19.0/{video_id}/comments",
                        data={"access_token": page_token, "message": comment_text}
                    )
                    c_data = c_resp.json()
                    if "id" in c_data:
                        comment_id = c_data["id"]
                        print(f"[FacebookBot] ✅ Auto-comment successfully posted on video {video_id}: {comment_id}")
                        break
                    
                    err_msg = c_data.get("error", {}).get("message", "")
                    print(f"[FacebookBot] Comment attempt {comment_attempt+1} note: {err_msg or c_data}")

                    # Attempt 2: Post to /{page_id}_{video_id}/comments (feed post ID)
                    fb_post_id = f"{page_id}_{video_id}"
                    c_resp2 = await client.post(
                        f"https://graph.facebook.com/v19.0/{fb_post_id}/comments",
                        data={"access_token": page_token, "message": comment_text}
                    )
                    c_data2 = c_resp2.json()
                    if "id" in c_data2:
                        comment_id = c_data2["id"]
                        print(f"[FacebookBot] ✅ Auto-comment successfully posted on post {fb_post_id}: {comment_id}")
                        break

                    # If token is invalid or expired, stop immediately and log
                    if c_data.get("error", {}).get("code") == 190:
                        print(f"[FacebookBot] ❌ Token expired or invalid! Cannot comment: {err_msg}")
                        break
                except Exception as ce:
                    print(f"[FacebookBot] Comment attempt {comment_attempt+1} exception: {ce}")

        return {
            "success": True,
            "video_id": video_id,
            "reel_url": reel_url,
            "comment_id": comment_id
        }

async def post_photo_to_facebook(
    image_path: str,
    caption: str,
    comment_text: str = ""
) -> Dict[str, Any]:
    """
    Uploads a photo/poster to Facebook Page with caption and automatic 1st comment.
    """
    if not os.path.exists(image_path):
        return {"success": False, "error": f"Image file not found: {image_path}"}

    cfg = load_fb_config()
    page_id = cfg.get("page_id", DEFAULT_PAGE_ID)
    page_token = cfg.get("page_token", DEFAULT_PAGE_TOKEN)

    if not page_token:
        return {"success": False, "error": "Missing Facebook Page Access Token"}

    print(f"[FacebookBot] Uploading photo poster to Page {page_id}...")

    async with httpx.AsyncClient(timeout=60.0) as client:
        try:
            with open(image_path, "rb") as f:
                photo_bytes = f.read()

            upload_url = f"https://graph.facebook.com/v19.0/{page_id}/photos"
            files = {
                "source": (os.path.basename(image_path), photo_bytes, "image/jpeg")
            }
            data = {
                "caption": caption,
                "access_token": page_token
            }
            resp = await client.post(upload_url, data=data, files=files)
            resp_data = resp.json()

            if "error" in resp_data:
                err_msg = resp_data["error"].get("message", "Photo upload failed")
                print(f"[FacebookBot] Photo upload error: {err_msg}")
                return {"success": False, "error": err_msg}

            photo_id = resp_data.get("id")
            post_id = resp_data.get("post_id") or photo_id
            post_url = f"https://www.facebook.com/{post_id}"
            print(f"[FacebookBot] ✅ Photo published successfully: {post_url} (Photo ID: {photo_id})")

            # Post 1st comment if provided
            comment_id = None
            if comment_text and post_id:
                try:
                    await asyncio.sleep(2)
                    c_resp = await client.post(
                        f"https://graph.facebook.com/v19.0/{post_id}/comments",
                        data={"message": comment_text, "access_token": page_token}
                    )
                    c_data = c_resp.json()
                    comment_id = c_data.get("id")
                    if comment_id:
                        print(f"[FacebookBot] ✅ Auto 1st comment posted on photo: {comment_id}")
                except Exception as ce:
                    print(f"[FacebookBot] Photo comment notice: {ce}")

            history_entry = {
                "title": "Meme Poster: Not All Heroes Stream",
                "post_id": post_id,
                "photo_id": photo_id,
                "post_url": post_url,
                "comment_id": comment_id,
                "timestamp": int(time.time()),
                "status": "published",
                "type": "photo_poster"
            }
            save_fb_history(history_entry)

            return {
                "success": True,
                "post_id": post_id,
                "photo_id": photo_id,
                "post_url": post_url,
                "comment_id": comment_id
            }
        except Exception as e:
            print(f"[FacebookBot] Photo upload exception: {e}")
            return {"success": False, "error": str(e)}

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

    raw_caption = caption or (
        f"🔥 {title or 'Trending Cinema'} • Watch Full HD on NXLY Stream 🍿\n"
        f"━━━━━━━━━━━━━━━━━━\n"
        f"🔍 Google-এ Search করুন: NXLY Stream\n"
        f"👉 ১ম যে ওয়েবসাইট আসবে সেটাই! (First result is the one)\n\n"
        f"💬 ফুল ভিডিও ডিরেক্ট লিংক ১ম কমেন্টে দেওয়া আছে (1st Comment)! 👇\n"
        f"📲 Watch Free in 1080p Full HD on NXLY Stream!\n\n"
        f"#nxlystream #reels #viral #trending #shorts"
    )
    # Ensure no external URLs in post caption to avoid Facebook reach throttling
    import re
    clean_caption = re.sub(r'https?://\S+', '', raw_caption).strip()

    res = await upload_reel_to_facebook(
        video_path=video_path,
        caption=clean_caption,
        comment_text=comment_text
    )
    if res.get("success"):
        reel_url = res.get("reel_url")
        if reel_url:
            await share_reel_to_groups(reel_url, title or filename, movie_url)
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
            # Fallback to homepage trending items from MovieBox
            try:
                banners = await main.get_latest_banners()
                for b in banners:
                    items.append({
                        "subject_id": str(b.get("subjectId") or ""),
                        "title": b.get("title") or "Popular Cinema",
                        "detail_path": b.get("detailPath") or "",
                        "subject_type": b.get("subjectType", 1)
                    })
            except Exception as he:
                print(f"[FacebookBot] Banners data fallback error: {he}")

        if not items:
            return {"success": False, "error": "No media subjects available for reels generation"}

        # Select candidates that haven't been posted recently
        history = load_fb_history()
        posted_titles = {h.get("title") for h in history}
        candidates = [it for it in items if it["title"] not in posted_titles]
        if not candidates:
            candidates = items

    success = False
    output_path = ""
    target_title = ""
    subject_id = ""

    # Try candidates until one renders successfully (try up to 12 candidates)
    for candidate in candidates[:12]:
        target_title = candidate["title"]
        subject_id = candidate.get("subject_id", "")
        detail_path = candidate.get("detail_path", "")

        try:
            res_data = await main.api_resource(se=1, ep=1, detailPath=detail_path, subjectId=subject_id)
            res_list = res_data.get("data", {}).get("list", [])
        except Exception as re_err:
            print(f"[FacebookBot] Stream fetch error for {target_title}: {re_err}")
            continue

        if not res_list:
            continue

        stream_candidates = []
        for r_item in res_list:
            u = r_item.get("url") or r_item.get("resourceLink") or ""
            # Preserve /fetch proxy routing so Cloudflare/CDN rate limits are bypassed
            if "source_url=" in u:
                if u.startswith("/fetch"):
                    u = "https://nxly.online" + u
            elif "hakunaymatata.com" in u or "aoneroom.com" in u:
                import urllib.parse
                u = f"https://nxly.online/fetch?source_url={urllib.parse.quote(u)}"
            elif u.startswith("/fetch"):
                u = "https://nxly.online" + u
            if u and u not in stream_candidates:
                stream_candidates.append(u)

        if not stream_candidates:
            continue

        slug = "".join(c for c in target_title if c.isalnum() or c in (" ", "_")).strip().replace(" ", "_")[:25]
        timestamp = int(time.time())
        output_filename = f"fb_reel_{slug}_{timestamp}.mp4"
        output_path = os.path.join(reel_generator.REELS_DIR, output_filename)

        overlay_filename = f"fb_overlay_{slug}_{timestamp}.png"
        overlay_path = os.path.join(reel_generator.REELS_DIR, overlay_filename)
        reel_generator.generate_overlay_image(
            title=target_title,
            part_num=1,
            total_parts=1,
            top_text_custom="PART 1",
            bottom_text_custom='GOOGLE SEARCH: "NXLY STREAM"',
            output_path=overlay_path
        )

        is_tv = (candidate.get("subject_type") == 2)
        if is_tv:
            start_seconds = random.randint(600, 1200)
        else:
            start_seconds = random.randint(1200, 2400)
        duration_seconds = 75  # 1m 15s optimal high-retention reel duration
        min_label = f"{start_seconds // 60}:{start_seconds % 60:02d}"
        print(f"[FacebookBot] Attempting {duration_seconds}s reel for '{target_title}' starting at {min_label} ({start_seconds}s)...")

        for s_url in stream_candidates:
            print(f"[FacebookBot] Slicing from stream: {s_url[:80]}...")
            success = await reel_generator.render_single_reel_part(
                stream_url=s_url,
                start_seconds=start_seconds,
                duration_seconds=duration_seconds,
                overlay_png_path=overlay_path,
                output_mp4_path=output_path,
                anti_copyright=True
            )
            if success and os.path.exists(output_path) and os.path.getsize(output_path) > 1000:
                print(f"[FacebookBot] Successfully rendered reel with stream: {s_url[:60]}")
                break

        if os.path.exists(overlay_path):
            try: os.remove(overlay_path)
            except Exception: pass

        if success:
            break

    if not success or not os.path.exists(output_path):
        return {"success": False, "error": "FFmpeg Reel generation failed for all candidates"}

    # Prepare Caption: SEO search prompt + High engagement (FB algorithm reach boost)
    caption = (
        f"🔥 {target_title} 🎬🍿\n"
        f"━━━━━━━━━━━━━━━━━━\n"
        f"🔍 Google-এ Search করুন: NXLY Stream\n"
        f"👉 ১ম যে ওয়েবসাইট আসবে সেটাই! (First result is the one)\n\n"
        f"💬 ফুল ভিডিও ডিরেক্ট লিংক ১ম কমেন্টে দেওয়া আছে (1st Comment)! 👇\n"
        f"📲 Watch Free in 1080p Full HD on NXLY Stream!\n\n"
        f"#reels #shorts #viral #movies #trending #nxly #{slug.lower()}"
    )

    # Prepare Comment: Contains ALL the links + Google Search hint
    watch_url = f"https://nxly.online/watch?id={subject_id}" if subject_id else "https://nxly.online"
    cfg = load_fb_config()
    default_tpl = (
        "🔍 Google Search: NXLY Stream (First result is the one!)\n\n"
        "🍿 Watch Full Movie/Show in 1080p HD:\n👉 {movie_url}\n\n"
        "📲 Download Free Official Android App:\n👉 https://nxly.online/download"
    )
    comment_tpl = cfg.get("auto_comment_template") or default_tpl
    comment_msg = comment_tpl.replace("{movie_url}", watch_url).replace("{title}", target_title)

    # Upload to Facebook
    upload_res = await upload_reel_to_facebook(
        video_path=output_path,
        caption=caption,
        comment_text=comment_msg
    )

    group_results = []
    if upload_res.get("success"):
        cfg["last_post_time"] = timestamp
        save_fb_config(cfg)

        # Share to configured Facebook groups and post comment
        reel_url = upload_res.get("reel_url")
        if reel_url:
            group_results = await share_reel_to_groups(reel_url, target_title, watch_url)

        history_entry = {
            "title": target_title,
            "video_id": upload_res.get("video_id"),
            "reel_url": upload_res.get("reel_url"),
            "comment_id": upload_res.get("comment_id"),
            "groups_shared": len([g for g in group_results if g.get("success")]),
            "file_size_mb": round(os.path.getsize(output_path) / (1024 * 1024), 2),
            "created_at": time.strftime("%Y-%m-%d %H:%M:%S")
        }
        save_fb_history(history_entry)

    return {
        "success": upload_res.get("success", False),
        "title": target_title,
        "reel_url": upload_res.get("reel_url"),
        "comment_id": upload_res.get("comment_id"),
        "groups_shared": group_results,
        "error": upload_res.get("error")
    }

async def share_reel_to_groups(reel_url: str, title: str, watch_url: str) -> list:
    """
    Shares reel to configured target Facebook groups and posts comment on each group post.
    """
    cfg = load_fb_config()
    groups = cfg.get("target_groups", [])
    if not groups:
        return []
    
    page_token = cfg.get("page_token", "")
    user_token = cfg.get("user_token", "")
    token_to_use = page_token or user_token
    results = []
    
    async with httpx.AsyncClient(timeout=30.0) as client:
        for grp_id in groups:
            grp_id = str(grp_id).strip()
            if not grp_id: continue
            try:
                # 1. Post to group feed
                feed_url = f"https://graph.facebook.com/v19.0/{grp_id}/feed"
                post_resp = await client.post(feed_url, data={
                    "message": f"🔥 {title} • New Viral Reel 🍿\n👉 Watch Reel: {reel_url}",
                    "link": reel_url,
                    "access_token": token_to_use
                })
                post_data = post_resp.json()
                post_id = post_data.get("id")
                
                # 2. Comment in the group post with links
                comment_id = None
                if post_id:
                    c_resp = await client.post(f"https://graph.facebook.com/v19.0/{post_id}/comments", data={
                        "message": f"🍿 Full Movie Link: 👉 {watch_url}\n📲 Free Official App: https://nxly.online/download",
                        "access_token": token_to_use
                    })
                    comment_id = c_resp.json().get("id")
                    
                results.append({"group_id": grp_id, "success": bool(post_id), "post_id": post_id, "comment_id": comment_id, "error": post_data.get("error")})
            except Exception as ge:
                results.append({"group_id": grp_id, "success": False, "error": str(ge)})
    return results

async def cleanup_copyrighted_videos():
    """
    Scans recent page videos for any copyright matches or enforcements,
    and immediately deletes them to protect the Facebook Page from strikes.
    """
    try:
        cfg = load_fb_config()
        page_id = cfg.get("page_id", DEFAULT_PAGE_ID)
        page_token = cfg.get("page_token", DEFAULT_PAGE_TOKEN)
        if not page_token:
            return
        
        async with httpx.AsyncClient(timeout=20.0) as client:
            url = f"https://graph.facebook.com/v19.0/{page_id}/videos?fields=id,title,status&limit=10&access_token={page_token}"
            r = await client.get(url)
            data = r.json()
            for v in data.get("data", []):
                vid = v.get("id")
                st = v.get("status", {})
                cp = st.get("copyright_check_status", {})
                pub = st.get("publishing_phase", {})
                if cp.get("matches_found") is True or pub.get("publish_status") in ("blocked", "rejected"):
                    print(f"[FacebookBot] Background Guard: Auto-deleting claimed video {vid}...")
                    del_res = await client.delete(f"https://graph.facebook.com/v19.0/{vid}?access_token={page_token}")
                    print(f"[FacebookBot] Background Guard delete response for {vid}: {del_res.text}")
    except Exception as e:
        print(f"[FacebookBot] Error in background copyright scan: {e}")

# Background auto-pilot scheduler loop
async def fb_auto_pilot_loop():
    print("[FacebookBot] Auto-Pilot background daemon initialized (10-minute cycle).")
    while True:
        try:
            await asyncio.sleep(30)  # check every 30s
            cfg = load_fb_config()
            
            # Periodically scan and clean up any newly claimed videos
            await cleanup_copyrighted_videos()

            if not cfg.get("auto_pilot_enabled", False):
                continue

            interval_mins = cfg.get("post_interval_minutes")
            if interval_mins is not None:
                interval_sec = max(60, int(interval_mins) * 60)
            else:
                interval_sec = int(cfg.get("post_interval_hours", 0.166) * 3600)
                if interval_sec <= 0:
                    interval_sec = 600

            now = int(time.time())
            last_post = int(cfg.get("last_post_time", 0))

            if now - last_post >= interval_sec:
                print(f"[FacebookBot] Triggering scheduled Auto-Pilot Reel (Interval: {interval_sec // 60}m)...")
                res = await trigger_auto_trending_reel()
                if not res.get("success"):
                    print(f"[FacebookBot] Scheduled reel attempt failed: {res.get('error')}. Retrying in 90s...")
                    # Set last_post so next retry happens in 90s instead of waiting a full cycle
                    cfg["last_post_time"] = now - interval_sec + 90
                    save_fb_config(cfg)
        except Exception as e:
            print(f"[FacebookBot] Auto-pilot loop error: {e}")
            await asyncio.sleep(30)
