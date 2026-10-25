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

        # Phase 5: Wait and post 1st Comment with watch & download links
        comment_id = None
        if comment_text:
            print("[FacebookBot] Posting auto-comment with links...")
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

    raw_caption = caption or f"🔥 {title or 'Trending Cinema'} • Watch Full HD on NXLY Stream 🍿\n\n💬 Full Movie Link is in the 1st Comment! 👇\n\n#nxlystream #reels #viral #trending #shorts"
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

    # Try candidates until one renders successfully
    for candidate in candidates[:6]:
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
            if "source_url=" in u:
                import urllib.parse
                u = urllib.parse.unquote(u.split("source_url=")[-1])
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
            bottom_text_custom="FULL MOVIE LINK IN 1ST COMMENT",
            output_path=overlay_path
        )

        is_tv = (candidate.get("subject_type") == 2)
        if is_tv:
            start_seconds = random.randint(720, 1320)
        else:
            start_seconds = random.randint(1800, 3000)
        duration_seconds = 120
        min_label = f"{start_seconds // 60}:{start_seconds % 60:02d}"
        print(f"[FacebookBot] Attempting 2-minute (120s) reel for '{target_title}' starting at {min_label} ({start_seconds}s)...")

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
                print(f"[FacebookBot] Successfully rendered 2m reel with stream: {s_url[:60]}")
                break

        if os.path.exists(overlay_path):
            try: os.remove(overlay_path)
            except Exception: pass

        if success:
            break

    if not success or not os.path.exists(output_path):
        return {"success": False, "error": "FFmpeg Reel generation failed for all candidates"}

    # Prepare Caption: NO external links in post caption (keeps FB reach high)
    caption = (
        f"🔥 {target_title} 🎬🍿\n"
        f"━━━━━━━━━━━━━━━━━━\n"
        f"💬 Full Movie / Series link is in the 1st Comment! 👇\n"
        f"📲 Watch free in 1080p Full HD on NXLY Stream App!\n\n"
        f"#reels #shorts #viral #movies #trending #nxly #{slug.lower()}"
    )

    # Prepare Comment: Contains ALL the links
    watch_url = f"https://nxly.online/watch?id={subject_id}" if subject_id else "https://nxly.online"
    cfg = load_fb_config()
    comment_tpl = cfg.get("auto_comment_template", "🍿 Watch Full in 1080p HD on NXLY:\n👉 {movie_url}\n\n📲 Download Official App:\n👉 https://nxly.online/download")
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
    print("[FacebookBot] Auto-Pilot background daemon initialized.")
    while True:
        try:
            await asyncio.sleep(120) # check every 2 mins
            cfg = load_fb_config()
            
            # Periodically scan and clean up any newly claimed videos
            await cleanup_copyrighted_videos()

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
