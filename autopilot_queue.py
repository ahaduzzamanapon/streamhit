import os
import json
import time
import asyncio
import re
import urllib.parse
from typing import Dict, Any, List, Optional
import httpx

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
QUEUE_FILE = os.path.join(BASE_DIR, "public", "reels", "autopilot_queue.json")
CONFIG_FILE = os.path.join(BASE_DIR, "facebook_config.json")

def load_queue_data() -> Dict[str, Any]:
    if os.path.exists(QUEUE_FILE):
        try:
            with open(QUEUE_FILE, "r", encoding="utf-8") as f:
                d = json.load(f)
                if "jobs" not in d: d["jobs"] = []
                if "finished" not in d: d["finished"] = []
                if "state" not in d: d["state"] = {}
                return d
        except Exception:
            pass
    return {
        "jobs": [],
        "finished": [],
        "state": {
            "is_busy": False,
            "step": "idle",
            "current_job_id": None,
            "current_title": "",
            "current_part_label": "",
            "progress_percent": 0,
            "last_run_timestamp": 0,
            "next_run_timestamp": int(time.time()) + 600,
            "active_node_id": "idle"
        }
    }

def save_queue_data(data: Dict[str, Any]):
    try:
        os.makedirs(os.path.dirname(QUEUE_FILE), exist_ok=True)
        with open(QUEUE_FILE, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
    except Exception as e:
        print(f"[AutoQueue] Error saving queue: {e}")

# ── PLATFORM HEALTH & CONNECTION VERIFICATION ─────────────────────────────────
async def check_platform_connections() -> Dict[str, Any]:
    from facebook_bot import load_fb_config
    import youtube_bot
    import tiktok_bot

    fb_cfg = load_fb_config()
    yt_cfg = youtube_bot.load_yt_config()
    tt_cfg = tiktok_bot.load_tt_config()

    # 1. Facebook check
    fb_connected = False
    fb_name = ""
    fb_error = ""
    page_id = fb_cfg.get("page_id", "").strip()
    page_token = fb_cfg.get("page_token", "").strip()

    if page_id and page_token:
        try:
            async with httpx.AsyncClient(timeout=6.0) as client:
                res = await client.get(f"https://graph.facebook.com/v19.0/{page_id}?fields=name,id&access_token={page_token}")
                d = res.json()
                if "name" in d:
                    fb_connected = True
                    fb_name = d["name"]
                else:
                    fb_error = d.get("error", {}).get("message", "Token expired or invalid permissions")
        except Exception as e:
            fb_error = str(e)
    else:
        fb_error = "Page ID or Access Token missing"

    # 2. YouTube check
    yt_conn = await youtube_bot.verify_youtube_connection()
    yt_connected = yt_conn.get("connected", False)

    # 3. TikTok check
    tt_conn = await tiktok_bot.verify_tiktok_connection()
    tt_connected = tt_conn.get("connected", False)

    return {
        "facebook": {
            "connected": fb_connected,
            "name": fb_name or fb_cfg.get("page_name", "Facebook Page"),
            "page_id": page_id,
            "auto_pilot_enabled": fb_cfg.get("auto_pilot_enabled", True),
            "error": fb_error,
            "guide": {
                "title": "How to Connect Facebook Page",
                "steps": [
                    "Go to Meta Graph API Explorer (developers.facebook.com/tools/explorer).",
                    "Select your Facebook App, choose your Page under User or Page, and grant permissions: pages_show_list, pages_read_engagement, pages_manage_posts.",
                    "Generate a User Token, copy it, and use our 1-click 'Exchange Token' tool below to generate a Permanent Never-Expiring Page Token.",
                    "Paste your Page ID and Token, then click Save."
                ]
            }
        },
        "youtube": {
            "connected": yt_connected,
            "channel_title": yt_conn.get("channel_title") or yt_cfg.get("channel_title", ""),
            "auto_pilot_enabled": yt_cfg.get("auto_pilot_enabled", False),
            "error": yt_conn.get("error", ""),
            "guide": {
                "title": "How to Connect YouTube Shorts",
                "steps": [
                    "Open Google Cloud Console (console.cloud.google.com) and create or select a project.",
                    "Enable 'YouTube Data API v3' in APIs & Services.",
                    "Under Credentials, create an 'OAuth 2.0 Client ID' (Application type: Web application or Desktop app).",
                    "Add redirect URI: https://developers.google.com/oauthplayground",
                    "Open OAuth 2.0 Playground, authorize scope: https://www.googleapis.com/auth/youtube.upload, and exchange authorization code for Refresh Token.",
                    "Paste your Client ID, Client Secret, and Refresh Token below."
                ]
            }
        },
        "tiktok": {
            "connected": tt_connected,
            "username": tt_conn.get("username") or tt_cfg.get("username", ""),
            "auto_pilot_enabled": tt_cfg.get("auto_pilot_enabled", False),
            "error": tt_conn.get("error", ""),
            "guide": {
                "title": "How to Connect TikTok Video API",
                "steps": [
                    "Log into TikTok for Developers (developers.tiktok.com).",
                    "Create an app and apply for 'Content Posting API' (Direct Post v2).",
                    "Copy Client Key and Client Secret.",
                    "Generate User Access Token with 'video.upload' scope.",
                    "Paste credentials below."
                ]
            }
        }
    }

# ── JOB & QUEUE MANAGEMENT ───────────────────────────────────────────────────
def add_bulk_jobs(items: List[Dict[str, Any]], global_config: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    data = load_queue_data()
    added_count = 0
    now_str = time.strftime("%Y-%m-%d %H:%M:%S")
    now_ts = int(time.time())

    cfg = global_config or {}
    default_movie_parts = int(cfg.get("movie_parts") or 8)
    default_part_duration = int(cfg.get("part_duration") or 75)
    default_parts_per_ep = int(cfg.get("parts_per_episode") or 3)
    enabled_platforms = cfg.get("platforms") or {"facebook": True, "youtube": True, "tiktok": True}

    for idx, raw in enumerate(items):
        title = str(raw.get("title", "")).strip()
        if not title:
            continue
        sub_id = str(raw.get("subject_id") or raw.get("subjectId") or "")
        detail_path = str(raw.get("detail_path") or raw.get("detailPath") or "")
        sub_type = int(raw.get("subject_type") or raw.get("subjectType") or 1)
        is_series = (sub_type == 2) or (raw.get("type") == "series")

        job_id = f"job_{now_ts}_{idx+1}_{raw.get('id', '')}"
        
        if is_series:
            seasons = raw.get("seasons") or [1]
            if isinstance(seasons, int):
                seasons = list(range(1, seasons + 1))
            total_eps = int(raw.get("total_episodes") or raw.get("episodes") or 10)
            parts_per_ep = int(raw.get("parts_per_episode") or default_parts_per_ep)
            total_planned_videos = len(seasons) * total_eps * parts_per_ep

            job_entry = {
                "id": job_id,
                "type": "series",
                "title": title,
                "subject_id": sub_id,
                "detail_path": detail_path,
                "cover": raw.get("cover", ""),
                "seasons": seasons,
                "current_season": int(raw.get("current_season") or seasons[0] if seasons else 1),
                "current_episode": int(raw.get("current_episode") or 1),
                "total_episodes_in_season": total_eps,
                "current_part": 1,
                "parts_per_episode": parts_per_ep,
                "total_planned_parts": total_planned_videos,
                "completed_parts_count": 0,
                "part_duration_seconds": int(raw.get("part_duration") or default_part_duration),
                "status": "queued",  # queued, processing, paused, finished, failed
                "platforms": enabled_platforms,
                "added_at": now_str,
                "last_posted_at": None,
                "history": []
            }
        else:
            total_parts = int(raw.get("total_parts") or raw.get("parts") or default_movie_parts)
            job_entry = {
                "id": job_id,
                "type": "movie",
                "title": title,
                "subject_id": sub_id,
                "detail_path": detail_path,
                "cover": raw.get("cover", ""),
                "total_parts": total_parts,
                "total_planned_parts": total_parts,
                "current_part": 1,
                "completed_parts_count": 0,
                "part_duration_seconds": int(raw.get("part_duration") or default_part_duration),
                "status": "queued",
                "platforms": enabled_platforms,
                "added_at": now_str,
                "last_posted_at": None,
                "history": []
            }

        data["jobs"].append(job_entry)
        added_count += 1

    save_queue_data(data)
    return {"code": 0, "added_count": added_count, "total_jobs": len(data["jobs"])}

def execute_job_action(job_id: str, action: str, direction: Optional[str] = None) -> Dict[str, Any]:
    data = load_queue_data()
    jobs = data["jobs"]
    idx = next((i for i, j in enumerate(jobs) if j.get("id") == job_id), -1)

    if action == "delete":
        if idx != -1:
            removed = jobs.pop(idx)
            save_queue_data(data)
            return {"code": 0, "message": f"Job '{removed.get('title')}' deleted"}
        return {"code": 404, "message": "Job not found"}

    if action == "pause":
        if idx != -1:
            jobs[idx]["status"] = "paused"
            save_queue_data(data)
            return {"code": 0, "status": "paused"}
        return {"code": 404, "message": "Job not found"}

    if action == "resume":
        if idx != -1:
            jobs[idx]["status"] = "queued"
            save_queue_data(data)
            return {"code": 0, "status": "queued"}
        return {"code": 404, "message": "Job not found"}

    if action == "reorder" and direction:
        if idx == -1: return {"code": 404, "message": "Job not found"}
        if direction == "up" and idx > 0:
            jobs[idx], jobs[idx-1] = jobs[idx-1], jobs[idx]
            save_queue_data(data)
            return {"code": 0, "message": "Moved up"}
        elif direction == "down" and idx < len(jobs) - 1:
            jobs[idx], jobs[idx+1] = jobs[idx+1], jobs[idx]
            save_queue_data(data)
            return {"code": 0, "message": "Moved down"}
        return {"code": 400, "message": "Cannot move further"}

    if action == "retry":
        if idx != -1:
            jobs[idx]["status"] = "queued"
            save_queue_data(data)
            return {"code": 0, "message": "Job set to retry"}
        return {"code": 404, "message": "Job not found"}

    if action == "toggle_platform":
        target_plat = (direction or "").lower().strip()
        if idx != -1 and target_plat in ("facebook", "youtube", "tiktok"):
            if "platforms" not in jobs[idx] or not isinstance(jobs[idx]["platforms"], dict):
                jobs[idx]["platforms"] = {"facebook": True, "youtube": True, "tiktok": True}
            current_val = jobs[idx]["platforms"].get(target_plat, True)
            jobs[idx]["platforms"][target_plat] = not current_val
            save_queue_data(data)
            return {"code": 0, "platforms": jobs[idx]["platforms"], "message": f"{target_plat.capitalize()} switch set to {not current_val}"}
        return {"code": 400, "message": "Invalid platform or job not found"}

    return {"code": 400, "message": "Unknown action"}

def requeue_finished(job_id: str) -> bool:
    data = load_queue_data()
    idx = next((i for i, j in enumerate(data["finished"]) if j.get("id") == job_id), -1)
    if idx != -1:
        job = data["finished"].pop(idx)
        job["status"] = "queued"
        job["current_part"] = 1
        job["completed_parts_count"] = 0
        if job.get("type") == "series":
            job["current_season"] = job["seasons"][0] if job.get("seasons") else 1
            job["current_episode"] = 1
        data["jobs"].append(job)
        save_queue_data(data)
        return True
    return False

def delete_finished(job_id: str) -> bool:
    data = load_queue_data()
    orig_len = len(data["finished"])
    data["finished"] = [j for j in data["finished"] if j.get("id") != job_id]
    if len(data["finished"]) < orig_len:
        save_queue_data(data)
        return True
    return False

# ── DASHBOARD & WORKFLOW STATE BUILDERS ───────────────────────────────────────
async def get_dashboard_summary() -> Dict[str, Any]:
    from facebook_bot import load_fb_config
    fb_cfg = load_fb_config()
    data = load_queue_data()
    connections = await check_platform_connections()

    now = int(time.time())
    interval_min = int(fb_cfg.get("post_interval_minutes", 10))
    interval_sec = interval_min * 60
    last_post = int(fb_cfg.get("last_post_time", 0))
    next_post = last_post + interval_sec if last_post > 0 else (now + interval_sec)
    remaining_sec = max(0, next_post - now)

    jobs = data["jobs"]
    finished = data["finished"]
    state = data.get("state", {})

    total_jobs = len(jobs) + len(finished)
    in_queue_count = sum(1 for j in jobs if j.get("status") in ("queued", "active"))
    processing_count = sum(1 for j in jobs if j.get("status") == "processing") or (1 if state.get("is_busy") else 0)
    failed_count = sum(1 for j in jobs if j.get("status") == "failed")
    finished_count = len(finished)

    # Active running item details for live card
    active_item = None
    for j in jobs:
        if j.get("status") in ("processing", "active", "queued"):
            active_item = j
            break

    # Format human readable next post string: "12:40 PM - In 10 min"
    mins_left = round(remaining_sec / 60)
    next_post_time_str = time.strftime("%I:%M %p", time.localtime(next_post))
    next_post_display = f"{next_post_time_str} - In {mins_left} min" if remaining_sec > 0 else "Due right now"

    return {
        "code": 0,
        "metrics": {
            "total_jobs": total_jobs,
            "in_queue": in_queue_count,
            "processing": processing_count,
            "ready_to_publish": 1 if (remaining_sec == 0 and in_queue_count > 0) else 0,
            "scheduled": in_queue_count,
            "publishing": 1 if state.get("step") == "distributing" else 0,
            "failed": failed_count,
            "finished": finished_count
        },
        "daemon": {
            "status": "Running" if fb_cfg.get("auto_pilot_enabled", True) else "Paused",
            "auto_pilot_enabled": fb_cfg.get("auto_pilot_enabled", True),
            "interval_minutes": interval_min,
            "next_post_timestamp": next_post,
            "next_post_display": next_post_display,
            "remaining_seconds": remaining_sec,
            "is_busy": state.get("is_busy", False),
            "current_step": state.get("step", "idle")
        },
        "active_item": active_item,
        "connections": connections,
        "jobs": jobs,
        "finished": finished
    }

def get_workflow_graph_state() -> Dict[str, Any]:
    """Returns actual backend execution state mapped to the n8n-style visual nodes."""
    from facebook_bot import load_fb_config
    fb_cfg = load_fb_config()
    data = load_queue_data()
    state = data.get("state", {})
    jobs = data["jobs"]

    is_busy = state.get("is_busy", False)
    current_step = state.get("step", "idle")
    current_title = state.get("current_title", "")
    current_part = state.get("current_part_label", "")

    active_job = None
    for j in jobs:
        if j.get("status") in ("processing", "active", "queued"):
            active_job = j
            break

    is_series = active_job.get("type") == "series" if active_job else False

    # Node states: pending, running, waiting, completed, failed
    def get_node_status(node_id: str) -> str:
        if not is_busy:
            return "waiting" if len(jobs) > 0 else "pending"
        if node_id == "node_input":
            return "completed" if current_step in ("rendering", "distributing", "done") else "running"
        if node_id == "node_prep":
            return "completed" if current_step in ("rendering", "distributing", "done") else ("running" if current_step == "fetching_stream" else "waiting")
        if node_id == "node_render":
            return "completed" if current_step in ("distributing", "done") else ("running" if current_step == "rendering" else "waiting")
        if node_id == "node_schedule":
            return "running" if current_step == "distributing" else "waiting"
        if node_id in ("node_fb", "node_yt", "node_tt"):
            return "running" if current_step == "distributing" else "waiting"
        return "pending"

    nodes = [
        {
            "id": "node_input",
            "title": "Movie / Series Input",
            "subtitle": f"{len(jobs)} items in queue • Bulk selection",
            "status": get_node_status("node_input"),
            "details": f"Active Title: {current_title or (active_job.get('title') if active_job else 'None')}"
        },
        {
            "id": "node_prep",
            "title": "Content Preparation",
            "subtitle": f"{'Series Season/Episode Queue' if is_series else 'Movie Parts Slicer'}",
            "status": get_node_status("node_prep"),
            "details": f"Target: {current_part or 'Sequential parts'}"
        },
        {
            "id": "node_render",
            "title": "Video Generation",
            "subtitle": "75s 9:16 Vertical • Anti-Copyright Safe Engine",
            "status": get_node_status("node_render"),
            "details": "Audio Pitch Shift • Zoom Filter • Search Banner"
        },
        {
            "id": "node_schedule",
            "title": "Scheduled Publishing",
            "subtitle": f"{fb_cfg.get('post_interval_minutes', 10)}-minute interval • Auto Scheduler",
            "status": get_node_status("node_schedule"),
            "details": f"Next cycle in {round(max(0, state.get('next_run_timestamp', 0) - time.time()) / 60)} min"
        },
        {
            "id": "node_fb",
            "title": "Facebook Reels",
            "subtitle": "Auto 1st comment stream link",
            "status": get_node_status("node_fb"),
            "details": "Published directly to Page NXLY"
        },
        {
            "id": "node_yt",
            "title": "YouTube Shorts",
            "subtitle": "#Shorts auto-tag & pinned link",
            "status": get_node_status("node_yt"),
            "details": "YouTube Data API v3 Resumable Upload"
        },
        {
            "id": "node_tt",
            "title": "TikTok Auto-Pilot",
            "subtitle": "Content API v2 direct posting",
            "status": get_node_status("node_tt"),
            "details": "Automated hashtags & search banner"
        }
    ]

    return {
        "code": 0,
        "is_busy": is_busy,
        "current_step": current_step,
        "current_title": current_title,
        "current_part_label": current_part,
        "nodes": nodes
    }

def get_publishing_calendar() -> List[Dict[str, Any]]:
    """Calculates chronological scheduled posts timeline based on queue sequence."""
    from facebook_bot import load_fb_config
    fb_cfg = load_fb_config()
    data = load_queue_data()

    now = int(time.time())
    interval_sec = int(fb_cfg.get("post_interval_minutes", 10)) * 60
    last_post = int(fb_cfg.get("last_post_time", 0))
    next_time = (last_post + interval_sec) if (last_post + interval_sec > now) else now

    timeline = []
    accum_time = next_time

    for job in data["jobs"]:
        if job.get("status") == "paused":
            continue
        
        is_series = job.get("type") == "series"
        title = job.get("title", "")
        
        if is_series:
            curr_s = job.get("current_season", 1)
            curr_ep = job.get("current_episode", 1)
            curr_p = job.get("current_part", 1)
            parts_per_ep = job.get("parts_per_episode", 3)
            
            # Project remaining parts for current episode
            for p in range(curr_p, parts_per_ep + 1):
                part_label = f"S{curr_s}E{curr_ep} Part {p}"
                time_str = time.strftime("%I:%M %p", time.localtime(accum_time))
                date_str = time.strftime("%b %d", time.localtime(accum_time))
                countdown_sec = max(0, accum_time - now)
                
                timeline.append({
                    "job_id": job.get("id"),
                    "title": title,
                    "cover": job.get("cover"),
                    "part_label": part_label,
                    "scheduled_timestamp": accum_time,
                    "scheduled_time": time_str,
                    "scheduled_date": date_str,
                    "countdown_display": f"In {round(countdown_sec / 60)} min" if countdown_sec > 60 else "Due now",
                    "platforms": job.get("platforms", {}),
                    "status": "scheduled"
                })
                accum_time += interval_sec
        else:
            curr_p = job.get("current_part", 1)
            total_p = job.get("total_parts", 8)
            
            for p in range(curr_p, total_p + 1):
                part_label = f"Part {p} of {total_p}"
                time_str = time.strftime("%I:%M %p", time.localtime(accum_time))
                date_str = time.strftime("%b %d", time.localtime(accum_time))
                countdown_sec = max(0, accum_time - now)
                
                timeline.append({
                    "job_id": job.get("id"),
                    "title": title,
                    "cover": job.get("cover"),
                    "part_label": part_label,
                    "scheduled_timestamp": accum_time,
                    "scheduled_time": time_str,
                    "scheduled_date": date_str,
                    "countdown_display": f"In {round(countdown_sec / 60)} min" if countdown_sec > 60 else "Due now",
                    "platforms": job.get("platforms", {}),
                    "status": "scheduled"
                })
                accum_time += interval_sec

    return timeline

# ── CORE SCHEDULER EXECUTION CYCLE ────────────────────────────────────────────
async def process_next_queue_job() -> Dict[str, Any]:
    """Extracts next due video part, cuts clip with FFmpeg, renders Google Search safe zone, and auto-posts."""
    from facebook_bot import bot_log, upload_reel_to_facebook, load_fb_config, save_fb_config
    import reel_generator
    import youtube_bot
    import tiktok_bot

    data = load_queue_data()
    jobs = data["jobs"]

    active_job = None
    active_idx = -1
    for i, j in enumerate(jobs):
        if j.get("status") in ("queued", "active", "processing"):
            active_job = j
            active_idx = i
            break

    if not active_job:
        bot_log("[AutoPilot] ℹ️ Queue is empty or all titles paused. Standing by for content.")
        return {"success": False, "reason": "queue_empty"}

    title = active_job.get("title", "Unknown Title")
    is_series = active_job.get("type") == "series"
    part_duration = active_job.get("part_duration_seconds", 75)
    active_job["status"] = "processing"

    # Compute sequence labels and stream parameters
    if is_series:
        season = active_job.get("current_season", 1)
        episode = active_job.get("current_episode", 1)
        part = active_job.get("current_part", 1)
        parts_per_ep = active_job.get("parts_per_episode", 3)
        part_label = f"S{season}E{episode} • Part {part}/{parts_per_ep}"
        top_text = f"{title} • S{season}E{episode} • Part {part}"
        start_seconds = (part - 1) * part_duration + 120
        se_arg = season
        ep_arg = episode
    else:
        part = active_job.get("current_part", 1)
        total_parts = active_job.get("total_parts", 8)
        part_label = f"Part {part}/{total_parts}"
        top_text = f"{title} • Part {part}"
        start_seconds = (part - 1) * part_duration + 600
        se_arg = 0
        ep_arg = 0

    bottom_text = "SEARCH ON GOOGLE: \"NXLY STREAM\" • First Result • Free 1080p"

    bot_log(f"[AutoPilot] ⚡ Step 1: Processing Job ({active_idx+1}/{len(jobs)}): '{title}' [{part_label}]")
    data["state"]["is_busy"] = True
    data["state"]["step"] = "fetching_stream"
    data["state"]["current_job_id"] = active_job.get("id")
    data["state"]["current_title"] = title
    data["state"]["current_part_label"] = part_label
    save_queue_data(data)

    # 1. Fetch direct video stream
    stream_candidates = []
    try:
        from main import api_resource
        res = await api_resource(
            se=se_arg,
            ep=ep_arg,
            detailPath=active_job.get("detail_path", ""),
            subjectId=active_job.get("subject_id", "")
        )
        items = res.get("data", {}).get("list") or []
        for it in items:
            link = it.get("url") or it.get("resourceLink") or ""
            if "source_url=" in link:
                try:
                    link = urllib.parse.unquote(link.split("source_url=")[-1])
                except Exception:
                    pass
            if link:
                stream_candidates.append(link)
    except Exception as e:
        bot_log(f"[AutoPilot] Warning: failed to fetch stream: {e}")

    if not stream_candidates:
        bot_log(f"[AutoPilot] ❌ No playable video stream found for '{title}' [{part_label}]. Pausing job.")
        active_job["status"] = "paused"
        data["state"]["is_busy"] = False
        data["state"]["step"] = "error_no_stream"
        save_queue_data(data)
        return {"success": False, "error": "No working video stream"}

    # 2. Slice and Render Video Clip
    data["state"]["step"] = "rendering"
    save_queue_data(data)

    sanitized = re.sub(r'[^a-zA-Z0-9_\-]', '_', title)[:30]
    timestamp = int(time.time())
    output_filename = f"fb_reel_{sanitized}_P{part}_{timestamp}.mp4"
    output_path = os.path.join(BASE_DIR, "public", "reels", output_filename)
    overlay_path = os.path.join(BASE_DIR, "public", "reels", f"fb_overlay_{sanitized}_{timestamp}.png")

    try:
        total_p_calc = active_job.get("parts_per_episode", 3) if is_series else active_job.get("total_parts", 8)
        reel_generator.generate_overlay_image(
            title=title,
            part_num=part,
            total_parts=total_p_calc,
            top_text_custom=top_text,
            bottom_text_custom='FULL DEKHAR JONNE GOOGLE SEARCH: "NXLY STREAM"',
            output_path=overlay_path
        )
    except Exception as oe:
        bot_log(f"[AutoPilot] Overlay generation note: {oe}")

    render_success = False
    for s_url in stream_candidates[:2]:
        bot_log(f"[AutoPilot] ⚙️ Slicing {part_duration}s at {start_seconds // 60}:{start_seconds % 60:02d} from stream...")
        render_success = await reel_generator.render_single_reel_part(
            stream_url=s_url,
            start_seconds=start_seconds,
            duration_seconds=part_duration,
            overlay_png_path=overlay_path,
            output_mp4_path=output_path,
            anti_copyright=True
        )
        if render_success and os.path.exists(output_path) and os.path.getsize(output_path) > 1000:
            break

    if os.path.exists(overlay_path):
        try: os.remove(overlay_path)
        except Exception: pass

    if not render_success or not os.path.exists(output_path) or os.path.getsize(output_path) < 1000:
        bot_log(f"[AutoPilot] ❌ Video rendering failed for '{title}' [{part_label}].")
        active_job["status"] = "failed"
        data["state"]["is_busy"] = False
        data["state"]["step"] = "error_render_failed"
        save_queue_data(data)
        return {"success": False, "error": "Rendering failed"}

    file_size_mb = round(os.path.getsize(output_path) / (1024 * 1024), 2)
    bot_log(f"[AutoPilot] ✅ Render complete: {output_filename} ({file_size_mb} MB)")

    # 3. Multi-Platform Distribution
    data["state"]["step"] = "distributing"
    save_queue_data(data)

    movie_web_url = f"https://nxly.online/movie/{active_job.get('detail_path')}" if active_job.get("detail_path") else "https://nxly.online"
    caption = (
        f"🔥 {top_text} 🎬🍿\n"
        f"━━━━━━━━━━━━━━━━━━\n"
        f"🔍 Full দেখার জন্য Google-এ Search করুন: NXLY Stream\n"
        f"🌐 অথবা Visit করুন: nxly.online\n"
        f"👉 ১ম যে রেজাল্ট আসবে সেটাই! (First result on Google)\n\n"
        f"💬 ফুল ভিডিও ডিরেক্ট লিংক ১ম কমেন্টে দেওয়া আছে (1st Comment)! 👇\n"
        f"📲 Watch Free in 1080p Full HD on NXLY Stream!\n\n"
        f"#reels #shorts #viral #movies #trending #nxly"
    )
    comment_msg = (
        f"🍿 Full দেখার জন্য Google Search করুন: \"NXLY Stream\" অথবা সরাসরি Visit করুন: https://nxly.online\n\n"
        f"👉 ডিরেক্ট ওয়াচ লিংক:\n{movie_web_url}\n\n"
        f"📲 Download Free Official Android App:\n👉 https://nxly.online/download"
    )

    platforms_cfg = active_job.get("platforms") or {"facebook": True, "youtube": True, "tiktok": True}
    fb_cfg = load_fb_config()
    yt_cfg = youtube_bot.load_yt_config()
    tt_cfg = tiktok_bot.load_tt_config()

    # A. Facebook Reels
    fb_res = {}
    if platforms_cfg.get("facebook", True) and fb_cfg.get("auto_pilot_enabled", True):
        try:
            bot_log(f"[FacebookBot] Uploading reel to Facebook Page: '{top_text}'...")
            fb_res = await upload_reel_to_facebook(
                video_path=output_path,
                caption=caption,
                comment_text=comment_msg
            )
            bot_log(f"[FacebookBot] Publish response: success={fb_res.get('success')}, url={fb_res.get('reel_url')}")
        except Exception as fbe:
            bot_log(f"[FacebookBot] Error: {fbe}")
    else:
        bot_log(f"[FacebookBot] ⏸️ Facebook posting skipped (Switch is OFF)")

    # B. YouTube Shorts
    yt_res = {}
    if platforms_cfg.get("youtube", True) and yt_cfg.get("auto_pilot_enabled", False):
        try:
            bot_log(f"[YouTubeBot] Auto-distributing to YouTube Shorts: '{top_text}'...")
            yt_res = await youtube_bot.upload_shorts_to_youtube(
                video_path=output_path,
                title=f"{top_text} #Shorts",
                description=caption,
                comment_text=comment_msg
            )
            bot_log(f"[YouTubeBot] YouTube Shorts result: success={yt_res.get('success')}")
        except Exception as yte:
            bot_log(f"[YouTubeBot] Error: {yte}")
    else:
        bot_log(f"[YouTubeBot] ⏸️ YouTube Shorts posting skipped (Switch is OFF)")

    # C. TikTok
    tt_res = {}
    if platforms_cfg.get("tiktok", True) and tt_cfg.get("auto_pilot_enabled", False):
        try:
            bot_log(f"[TikTokBot] Auto-distributing to TikTok: '{top_text}'...")
            tt_caption = f"🔥 {top_text} 🍿 Google Search: NXLY Stream or Visit nxly.online #shorts #movies #fyp #viral #nxly"
            tt_res = await tiktok_bot.upload_video_to_tiktok(
                video_path=output_path,
                caption=tt_caption
            )
            bot_log(f"[TikTokBot] TikTok result: success={tt_res.get('success')}")
        except Exception as tte:
            bot_log(f"[TikTokBot] Error: {tte}")
    else:
        bot_log(f"[TikTokBot] ⏸️ TikTok posting skipped (Switch is OFF)")

    # 4. Advance Sequence Progress & Check Completion
    now_str = time.strftime("%Y-%m-%d %H:%M:%S")
    active_job["last_posted_at"] = now_str
    active_job["completed_parts_count"] = active_job.get("completed_parts_count", 0) + 1
    active_job["history"].append({
        "part_label": part_label,
        "posted_at": now_str,
        "video_filename": output_filename,
        "fb_url": fb_res.get("reel_url"),
        "yt_url": yt_res.get("video_url"),
        "file_size_mb": file_size_mb
    })

    job_finished = False

    if is_series:
        active_job["current_part"] += 1
        if active_job["current_part"] > active_job.get("parts_per_episode", 3):
            active_job["current_part"] = 1
            active_job["current_episode"] += 1
            bot_log(f"[AutoPilot] 📌 '{title}' completed episode! Advancing to Episode {active_job['current_episode']}.")

            if active_job["current_episode"] > active_job.get("total_episodes_in_season", 10):
                seasons = active_job.get("seasons") or [1]
                curr_s_idx = seasons.index(active_job["current_season"]) if active_job["current_season"] in seasons else -1
                if curr_s_idx != -1 and curr_s_idx + 1 < len(seasons):
                    active_job["current_season"] = seasons[curr_s_idx + 1]
                    active_job["current_episode"] = 1
                    bot_log(f"[AutoPilot] 🌟 '{title}' advanced to Season {active_job['current_season']}!")
                else:
                    job_finished = True
    else:
        active_job["current_part"] += 1
        if active_job["current_part"] > active_job.get("total_parts", 8):
            job_finished = True

    if job_finished:
        bot_log(f"[AutoPilot] 🏆 ALL PLANNED VIDEOS COMPLETED for '{title}'! Moving to Finished List.")
        active_job["status"] = "finished"
        active_job["finished_at"] = now_str
        data["finished"].insert(0, active_job)
        data["jobs"].pop(active_idx)
    else:
        active_job["status"] = "queued"
        bot_log(f"[AutoPilot] 📊 Progress for '{title}': {active_job['completed_parts_count']} parts completed.")

    # Save state & update last post timestamp
    fb_cfg = load_fb_config()
    fb_cfg["last_post_time"] = int(time.time())
    save_fb_config(fb_cfg)

    data["state"]["is_busy"] = False
    data["state"]["step"] = "idle"
    data["state"]["last_run_timestamp"] = int(time.time())
    data["state"]["next_run_timestamp"] = int(time.time()) + (int(fb_cfg.get("post_interval_minutes", 10)) * 60)
    save_queue_data(data)

    bot_log(f"[AutoPilot] ⏱️ Next scheduled video post in {fb_cfg.get('post_interval_minutes', 10)} minutes.")

    return {
        "success": True,
        "title": title,
        "part_label": part_label,
        "finished": job_finished,
        "facebook": fb_res,
        "youtube": yt_res,
        "tiktok": tt_res
    }
