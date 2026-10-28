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

def get_db():
    try:
        import pymysql
        from dotenv import load_dotenv
        env_path = os.path.join(BASE_DIR, ".env")
        if os.path.exists(env_path):
            load_dotenv(env_path)
        host = os.getenv("DB_HOST", "127.0.0.1")
        port = int(os.getenv("DB_PORT", "3306"))
        user = os.getenv("DB_USER", "lcsyxfen_streamhit")
        password = os.getenv("DB_PASSWORD", "lcsyxfen_streamhit")
        db = os.getenv("DB_NAME", "lcsyxfen_streamhit")
        return pymysql.connect(
            host=host, port=port, user=user, password=password, database=db,
            autocommit=True, connect_timeout=4, cursorclass=pymysql.cursors.DictCursor
        )
    except Exception:
        return None

def load_queue_data() -> Dict[str, Any]:
    # 1. Try MySQL First
    conn = get_db()
    if conn:
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT * FROM automation_jobs ORDER BY sort_order ASC, created_at ASC")
                rows = cur.fetchall()
                jobs = []
                for r in rows:
                    j = {
                        "id": r["id"],
                        "subject_id": r["subject_id"],
                        "title": r["title"],
                        "type": r["type"],
                        "cover": r.get("cover") or "",
                        "detail_path": r.get("detail_path") or "",
                        "status": r.get("status") or "queued",
                        "current_part": r.get("current_part") or 1,
                        "total_planned_parts": r.get("total_planned_parts") or 40,
                        "completed_parts_count": r.get("completed_parts_count") or 0,
                        "current_season": r.get("current_season") or 1,
                        "current_episode": r.get("current_episode") or 1,
                        "total_episodes_in_season": r.get("total_episodes_in_season") or 1,
                        "parts_per_episode": r.get("parts_per_episode") or 3,
                        "part_duration_seconds": r.get("part_duration_seconds") or 55,
                        "top_text": r.get("top_text") or "",
                        "retry_count": r.get("retry_count") or 0,
                        "last_error": r.get("last_error"),
                        "seasons": json.loads(r["seasons_json"]) if r.get("seasons_json") else [1],
                        "season_episodes_map": json.loads(r["season_episodes_map_json"]) if r.get("season_episodes_map_json") else {"1": 1},
                        "platforms": json.loads(r["platforms_json"]) if r.get("platforms_json") else {"facebook": True, "youtube": False, "tiktok": False},
                        "pending_uploads": json.loads(r["pending_uploads_json"]) if r.get("pending_uploads_json") else [],
                        "history": json.loads(r["history_json"]) if r.get("history_json") else [],
                        "sort_order": r.get("sort_order") or 0
                    }
                    jobs.append(j)

                # Fetch state
                cur.execute("SELECT v FROM automation_state WHERE k='state'")
                st_row = cur.fetchone()
                st = json.loads(st_row["v"]) if st_row and st_row.get("v") else {}
                default_state = {
                    "is_busy": False,
                    "step": "idle",
                    "current_job_id": None,
                    "current_title": "",
                    "current_part_label": "",
                    "progress_percent": 0,
                    "step_label": "",
                    "busy_since": 0,
                    "last_run_timestamp": 0,
                    "next_run_timestamp": int(time.time()) + 600,
                    "active_node_id": "idle"
                }
                default_state.update(st)
                
                # Check stale busy (> 180s)
                now_ts = int(time.time())
                if default_state.get("is_busy"):
                    busy_since = default_state.get("busy_since") or default_state.get("last_run_timestamp") or 0
                    if now_ts - busy_since > 180:
                        default_state["is_busy"] = False
                        default_state["step"] = "idle"
                        default_state["busy_since"] = 0
                        cur.execute("REPLACE INTO automation_state (k, v) VALUES ('state', %s)", (json.dumps(default_state),))
                        cur.execute("UPDATE automation_jobs SET status='queued' WHERE status='processing'")
                        for j in jobs:
                            if j.get("status") == "processing":
                                j["status"] = "queued"

                data = {"jobs": jobs, "finished": [], "state": default_state}
                # Sync mirror file
                try:
                    os.makedirs(os.path.dirname(QUEUE_FILE), exist_ok=True)
                    with open(QUEUE_FILE, "w", encoding="utf-8") as f:
                        json.dump(data, f, indent=2, ensure_ascii=False)
                except Exception:
                    pass
                return data
        except Exception as e:
            print(f"[AutoQueue] MySQL read notice: {e}")
        finally:
            conn.close()

    # 2. Fallback to JSON file if MySQL offline
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
            "step_label": "",
            "busy_since": 0,
            "last_run_timestamp": 0,
            "next_run_timestamp": int(time.time()) + 600,
            "active_node_id": "idle"
        }
    }

def save_queue_data(data: Dict[str, Any]):
    # 1. Update MySQL
    conn = get_db()
    if conn:
        try:
            with conn.cursor() as cur:
                # Save state
                if "state" in data:
                    cur.execute("REPLACE INTO automation_state (k, v) VALUES ('state', %s)", (json.dumps(data["state"]),))
                
                # Upsert jobs
                jobs = data.get("jobs", [])
                job_ids = [j.get("id") for j in jobs if j.get("id")]
                if job_ids:
                    format_strings = ','.join(['%s'] * len(job_ids))
                    cur.execute(f"DELETE FROM automation_jobs WHERE id NOT IN ({format_strings})", tuple(job_ids))
                else:
                    cur.execute("DELETE FROM automation_jobs")

                for idx, j in enumerate(jobs):
                    cur.execute("""
                        INSERT INTO automation_jobs (
                            id, subject_id, title, type, cover, detail_path, status,
                            current_part, total_planned_parts, completed_parts_count,
                            current_season, current_episode, total_episodes_in_season,
                            parts_per_episode, part_duration_seconds, top_text,
                            seasons_json, season_episodes_map_json, platforms_json,
                            pending_uploads_json, history_json, last_error, retry_count, sort_order
                        ) VALUES (
                            %s, %s, %s, %s, %s, %s, %s,
                            %s, %s, %s,
                            %s, %s, %s,
                            %s, %s, %s,
                            %s, %s, %s,
                            %s, %s, %s, %s, %s
                        ) ON DUPLICATE KEY UPDATE
                            status=VALUES(status),
                            current_part=VALUES(current_part),
                            total_planned_parts=VALUES(total_planned_parts),
                            completed_parts_count=VALUES(completed_parts_count),
                            current_season=VALUES(current_season),
                            current_episode=VALUES(current_episode),
                            total_episodes_in_season=VALUES(total_episodes_in_season),
                            pending_uploads_json=VALUES(pending_uploads_json),
                            history_json=VALUES(history_json),
                            last_error=VALUES(last_error),
                            retry_count=VALUES(retry_count),
                            sort_order=VALUES(sort_order)
                    """, (
                        j.get("id"), str(j.get("subject_id", "0")), j.get("title", "Unknown"),
                        j.get("type", "movie"), j.get("cover", ""), j.get("detail_path", ""),
                        j.get("status", "queued"), j.get("current_part", 1), j.get("total_planned_parts", 40),
                        j.get("completed_parts_count", 0), j.get("current_season", 1), j.get("current_episode", 1),
                        j.get("total_episodes_in_season", 1), j.get("parts_per_episode", 3),
                        j.get("part_duration_seconds", 55), j.get("top_text", ""),
                        json.dumps(j.get("seasons", [1])), json.dumps(j.get("season_episodes_map", {"1": 1})),
                        json.dumps(j.get("platforms", {"facebook": True, "youtube": False, "tiktok": False})),
                        json.dumps(j.get("pending_uploads", [])), json.dumps(j.get("history", [])),
                        j.get("last_error"), j.get("retry_count", 0), idx
                    ))
        except Exception as e:
            print(f"[AutoQueue] MySQL save notice: {e}")
        finally:
            conn.close()

    # 2. Mirror to JSON file
    try:
        os.makedirs(os.path.dirname(QUEUE_FILE), exist_ok=True)
        with open(QUEUE_FILE, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
    except Exception as e:
        print(f"[AutoQueue] Error saving queue to file: {e}")

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

# ── SERIES METADATA & MULTI-SEASON RESOLUTION ────────────────────────────────
async def resolve_series_metadata(detail_path: str, subject_id: str) -> Dict[str, Any]:
    """Fetches real seasons and episodes count dynamically from api_detail."""
    seasons = [1]
    season_episodes_map = {"1": 10}
    try:
        from main import api_detail
        res = await api_detail(detailPath=detail_path, subjectId=subject_id)
        subj = res.get("data") or {}
        raw_seasons = subj.get("seasons") or []
        if raw_seasons:
            seasons = []
            season_episodes_map = {}
            for s in raw_seasons:
                s_num = int(s.get("se") or 1)
                max_ep = int(s.get("maxEp") or 0)
                if max_ep <= 0 and s.get("allEp"):
                    max_ep = len([x for x in s.get("allEp").split(",") if x.strip()])
                if max_ep <= 0:
                    max_ep = 10
                seasons.append(s_num)
                season_episodes_map[str(s_num)] = max_ep
        elif subj.get("seNum"):
            se_num = int(subj.get("seNum") or 1)
            seasons = list(range(1, se_num + 1))
            season_episodes_map = {str(s): 10 for s in seasons}
    except Exception as e:
        print(f"[AutoPilot] resolve_series_metadata note: {e}")

    if not seasons:
        seasons = [1]
        season_episodes_map = {"1": 10}

    return {
        "seasons": seasons,
        "season_episodes_map": season_episodes_map
    }

async def ensure_series_metadata(job: Dict[str, Any]) -> bool:
    """Ensures a series job has accurate seasons list and episode counts for continuous execution."""
    if job.get("type") != "series":
        return False

    s_map = job.get("season_episodes_map")
    if s_map and isinstance(s_map, dict) and sum(s_map.values()) > 1 and job.get("total_planned_parts", 0) > 3:
        return False

    meta = await resolve_series_metadata(job.get("detail_path", ""), job.get("subject_id", ""))
    job["seasons"] = meta["seasons"]
    job["season_episodes_map"] = meta["season_episodes_map"]

    curr_s = job.get("current_season") or (meta["seasons"][0] if meta["seasons"] else 1)
    job["current_season"] = curr_s
    job["total_episodes_in_season"] = meta["season_episodes_map"].get(str(curr_s), 10)

    parts_per_ep = job.get("parts_per_episode") or 3
    job["parts_per_episode"] = parts_per_ep
    total_planned = sum(ep_cnt * parts_per_ep for ep_cnt in meta["season_episodes_map"].values())
    job["total_planned_parts"] = total_planned
    return True

# ── JOB & QUEUE MANAGEMENT ───────────────────────────────────────────────────
async def add_bulk_jobs_async(items: List[Dict[str, Any]], global_config: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    data = load_queue_data()
    added_count = 0
    now_str = time.strftime("%Y-%m-%d %H:%M:%S")
    now_ts = int(time.time())

    cfg = global_config or {}
    default_movie_parts = int(cfg.get("movie_parts") or 8)
    default_part_duration = int(cfg.get("part_duration") or 55)
    default_parts_per_ep = int(cfg.get("series_parts_per_ep") or cfg.get("parts_per_episode") or 3)
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
            meta = await resolve_series_metadata(detail_path, sub_id)
            seasons = meta["seasons"]
            season_episodes_map = meta["season_episodes_map"]
            curr_s = seasons[0] if seasons else 1
            parts_per_ep = int(raw.get("parts_per_episode") or default_parts_per_ep)
            total_planned_videos = sum(ep_cnt * parts_per_ep for ep_cnt in season_episodes_map.values())

            job_entry = {
                "id": job_id,
                "type": "series",
                "title": title,
                "subject_id": sub_id,
                "detail_path": detail_path,
                "cover": raw.get("cover", ""),
                "seasons": seasons,
                "season_episodes_map": season_episodes_map,
                "current_season": curr_s,
                "current_episode": 1,
                "total_episodes_in_season": season_episodes_map.get(str(curr_s), 10),
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
            auto_calc = cfg.get("auto_calculate", False) or not (raw.get("total_parts") or raw.get("parts"))
            part_duration = int(raw.get("part_duration") or default_part_duration or 55)
            
            total_parts = int(raw.get("total_parts") or raw.get("parts") or 0)
            if auto_calc or total_parts <= 0:
                movie_duration_seconds = 0
                try:
                    from main import api_detail
                    res = await api_detail(detailPath=detail_path, subjectId=sub_id)
                    d = res.get("data") or {}
                    movie_duration_seconds = int(d.get("duration") or 0)
                except Exception:
                    pass
                
                if movie_duration_seconds > 600:
                    usable_seconds = max(600, movie_duration_seconds - 900)
                    total_parts = max(6, int(usable_seconds // part_duration))
                else:
                    total_parts = int(cfg.get("movie_parts") or 50)

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
                "part_duration_seconds": part_duration,
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

def add_bulk_jobs(items: List[Dict[str, Any]], global_config: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    try:
        loop = asyncio.get_event_loop()
        if loop.is_running():
            asyncio.create_task(add_bulk_jobs_async(items, global_config))
            return {"code": 0, "added_count": len(items), "total_jobs": len(items)}
        return loop.run_until_complete(add_bulk_jobs_async(items, global_config))
    except Exception:
        return {"code": 0, "added_count": len(items), "total_jobs": len(items)}

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

    if action == "run_now":
        if idx != -1:
            jobs[idx]["status"] = "queued"
            save_queue_data(data)
            asyncio.create_task(process_next_queue_job(target_job_id=job_id))
            return {"code": 0, "message": f"Started rendering Part {jobs[idx].get('current_part', 1)} for '{jobs[idx].get('title')}'"}
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

    if action == "set_part":
        try:
            part_num = int(direction) if direction and str(direction).isdigit() else 1
            if idx != -1:
                jobs[idx]["current_part"] = part_num
                jobs[idx]["retry_count"] = 0
                jobs[idx]["last_error"] = None
                jobs[idx]["status"] = "queued"
                save_queue_data(data)
                return {"code": 0, "message": f"Job '{jobs[idx].get('title')}' reset to Part {part_num}"}
            return {"code": 404, "message": "Job not found"}
        except Exception as e:
            return {"code": 500, "message": str(e)}

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
    interval_min = int(fb_cfg.get("post_interval_minutes", 45))
    interval_sec = interval_min * 60
    last_post = int(fb_cfg.get("last_post_time", 0))
    next_post = last_post + interval_sec if last_post > 0 else (now + interval_sec)
    remaining_sec = max(0, next_post - now)

    jobs = data["jobs"]
    finished = data["finished"]
    state = data.get("state", {})

    total_jobs = len(jobs) + len(finished)
    in_queue_count = sum(1 for j in jobs if j.get("status") not in ("finished", "paused"))
    processing_count = sum(1 for j in jobs if j.get("status") == "processing") or (1 if state.get("is_busy") else 0)
    failed_count = sum(1 for j in jobs if j.get("status") == "failed")
    finished_count = len(finished)

    # Active running item details for live card
    active_item = None
    for j in jobs:
        if j.get("status") in ("processing", "active", "queued", "waiting_upload", "waiting"):
            active_item = j
            break

    # Format human readable next post string in Bangladesh Time (UTC+6): "04:40 PM - In 10 min"
    mins_left = round(remaining_sec / 60)
    bd_next = time.gmtime(next_post + 6 * 3600)
    next_post_time_str = time.strftime("%I:%M %p", bd_next)
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
            "current_step": state.get("step", "idle"),
            "progress_percent": state.get("progress_percent", 0),
            "step_label": state.get("step_label", "")
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
            "subtitle": f"{fb_cfg.get('post_interval_minutes', 45)}-minute interval • Auto Scheduler",
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
    interval_sec = int(fb_cfg.get("post_interval_minutes", 45)) * 60
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
            s_map = job.get("season_episodes_map") or {}
            seasons = job.get("seasons") or [curr_s]

            # Project upcoming parts across current and future episodes/seasons (up to 25 items per job)
            s_idx = seasons.index(curr_s) if curr_s in seasons else 0
            ep = curr_ep
            p = curr_p
            projected = 0

            while s_idx < len(seasons) and projected < 25:
                s_num = seasons[s_idx]
                max_eps = s_map.get(str(s_num), job.get("total_episodes_in_season", 10))
                while ep <= max_eps and projected < 25:
                    while p <= parts_per_ep and projected < 25:
                        part_label = f"S{s_num}E{ep} Part {p}"
                        bd_accum = time.gmtime(accum_time + 6 * 3600)
                        time_str = time.strftime("%I:%M %p", bd_accum)
                        date_str = time.strftime("%b %d", bd_accum)
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
                        projected += 1
                        p += 1
                    p = 1
                    ep += 1
                ep = 1
                s_idx += 1
        else:
            curr_p = job.get("current_part", 1)
            total_p = job.get("total_planned_parts") or job.get("total_parts", 132)

            for p in range(curr_p, total_p + 1):
                part_label = f"Part {p} of {total_p}"
                bd_accum = time.gmtime(accum_time + 6 * 3600)
                time_str = time.strftime("%I:%M %p", bd_accum)
                date_str = time.strftime("%b %d", bd_accum)
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

def advance_job_progression(active_job: Dict[str, Any], is_series: bool, data: Dict[str, Any], active_idx: int, now_str: str) -> bool:
    from facebook_bot import bot_log
    title = active_job.get("title", "Unknown Title")
    job_finished = False

    if is_series:
        active_job["current_part"] += 1
        if active_job["current_part"] > active_job.get("parts_per_episode", 3):
            active_job["current_part"] = 1
            active_job["current_episode"] += 1
            bot_log(f"[AutoPilot] 📌 '{title}' completed episode! Advancing to Episode {active_job['current_episode']}.")

            s_map = active_job.get("season_episodes_map") or {}
            total_eps_this_season = s_map.get(str(active_job["current_season"]), active_job.get("total_episodes_in_season", 10))

            if active_job["current_episode"] > total_eps_this_season:
                seasons = active_job.get("seasons") or [1]
                try:
                    curr_s_idx = seasons.index(active_job["current_season"])
                except ValueError:
                    curr_s_idx = -1

                if curr_s_idx != -1 and curr_s_idx + 1 < len(seasons):
                    next_season = seasons[curr_s_idx + 1]
                    active_job["current_season"] = next_season
                    active_job["current_episode"] = 1
                    active_job["total_episodes_in_season"] = s_map.get(str(next_season), 10)
                    bot_log(f"[AutoPilot] 🌟 '{title}' advanced to Season {next_season} (Episodes: {active_job['total_episodes_in_season']})!")
                else:
                    job_finished = True
    else:
        active_job["current_part"] += 1
        max_parts = active_job.get("total_planned_parts") or active_job.get("total_parts", 40)
        if active_job["current_part"] > max_parts:
            job_finished = True

    if job_finished:
        bot_log(f"[AutoPilot] 🏆 ALL PLANNED VIDEOS COMPLETED for '{title}'! Moving to Finished List.")
        active_job["status"] = "finished"
        active_job["finished_at"] = now_str
        data["finished"].insert(0, active_job)
        if 0 <= active_idx < len(data["jobs"]):
            data["jobs"].pop(active_idx)
    else:
        active_job["status"] = "queued"
        bot_log(f"[AutoPilot] 📊 Progress for '{title}': {active_job['completed_parts_count']}/{active_job.get('total_planned_parts', 1)} parts completed.")

    return job_finished

# ── CORE SCHEDULER EXECUTION CYCLE ────────────────────────────────────────────
async def process_next_queue_job(target_job_id: Optional[str] = None) -> Dict[str, Any]:
    """Extracts next due video part, cuts clip with FFmpeg, renders Google Search safe zone, and auto-posts."""
    from facebook_bot import bot_log, upload_reel_to_facebook, load_fb_config, save_fb_config
    import reel_generator
    import youtube_bot
    import tiktok_bot
    import traceback

    data = load_queue_data()
    jobs = data["jobs"]

    fb_cfg = load_fb_config()
    yt_cfg = youtube_bot.load_yt_config()
    tt_cfg = tiktok_bot.load_tt_config()

    active_job = None
    active_idx = -1

    if target_job_id:
        for i, j in enumerate(jobs):
            if j.get("id") == target_job_id:
                active_job = j
                active_idx = i
                break

    if not active_job:
        # First priority: check if any active job has pending rendered uploads that need publishing
        pending_candidates = [
            (i, j) for i, j in enumerate(jobs)
            if j.get("status") in ("queued", "active", "processing", "waiting_upload", "waiting")
            and (j.get("pending_uploads") or [])
        ]
        if pending_candidates:
            import random
            active_idx, active_job = random.choice(pending_candidates)
            bot_log(f"[AutoPilot] 📦 Found pending rendered uploads for '{active_job.get('title')}'. Resuming publish first.")
        else:
            # Pick randomly from all active queued jobs (so page alternates variety instead of staying stuck on #1)
            eligible_candidates = [
                (i, j) for i, j in enumerate(jobs)
                if j.get("status") in ("queued", "active", "processing", "waiting_upload", "waiting")
            ]
            if eligible_candidates:
                import random
                last_job_id = data.get("state", {}).get("current_job_id")
                # Avoid picking the exact same title consecutively if other active queue items are available
                different_candidates = [c for c in eligible_candidates if c[1].get("id") != last_job_id]
                selection_pool = different_candidates if different_candidates else eligible_candidates
                active_idx, active_job = random.choice(selection_pool)
                bot_log(f"[AutoPilot] 🎲 Random Queue Dispatcher selected job #{active_idx + 1}: '{active_job.get('title')}' (from {len(eligible_candidates)} active titles in queue).")

    if not active_job:
        bot_log("[AutoPilot] ℹ️ Queue is empty or all titles paused. Standing by for content.")
        return {"success": False, "reason": "queue_empty"}

    title = active_job.get("title", "Unknown Title")
    is_series = active_job.get("type") == "series"
    part_duration = active_job.get("part_duration_seconds", 55)
    platforms_cfg = active_job.get("platforms") or {"facebook": True, "youtube": True, "tiktok": True}
    fb_is_active = platforms_cfg.get("facebook", True) and fb_cfg.get("auto_pilot_enabled", True)
    yt_is_active = platforms_cfg.get("youtube", True) and yt_cfg.get("auto_pilot_enabled", False)
    tt_is_active = platforms_cfg.get("tiktok", True) and tt_cfg.get("auto_pilot_enabled", False)

    now_ts = int(time.time())

    # ── 0. CHECK FOR PENDING RENDERED VIDEOS WAITING FOR PUBLISH ─────────────────
    pending_uploads = active_job.get("pending_uploads") or []
    if pending_uploads:
        pending_item = pending_uploads[0]
        p_label = pending_item.get("part_label", "Video")
        p_filename = pending_item.get("video_filename", "")
        p_path = os.path.join(BASE_DIR, "public", "reels", p_filename)

        bot_log(f"[AutoPilot] ⏳ Found pending video waiting to publish: '{p_label}' ({p_filename}). Checking Facebook connection...")

        if fb_is_active:
            from facebook_bot import check_fb_token_validity
            fb_valid, fb_err = await check_fb_token_validity(fb_cfg.get("page_token", ""), fb_cfg.get("page_id", ""))
            if not fb_valid:
                bot_log(f"[FacebookBot] ⏸️ Facebook connection not ready ({fb_err}).")
                bot_log(f"[AutoPilot] ⏳ Waiting for Facebook token: '{p_label}' remains saved in queue without re-rendering.")
                active_job["status"] = "waiting_upload"
                data["state"]["is_busy"] = False
                data["state"]["step"] = "idle"
                data["state"]["step_label"] = f"Waiting to publish {p_label} (FB Token Needed)"
                data["state"]["progress_percent"] = 0
                data["state"]["next_run_timestamp"] = int(time.time()) + (int(fb_cfg.get("post_interval_minutes", 45)) * 60)
                save_queue_data(data)
                return {"success": False, "waiting": True, "reason": "fb_token_expired"}

        # Token is valid! Re-upload immediately
        if os.path.exists(p_path):
            bot_log(f"[AutoPilot] 🚀 Facebook is READY! Re-uploading pending video: '{p_label}' ({p_filename})...")
            data["state"]["is_busy"] = True
            data["state"]["busy_since"] = now_ts
            data["state"]["step"] = "distributing"
            data["state"]["progress_percent"] = 85
            data["state"]["step_label"] = f"Re-uploading {p_label} to Facebook..."
            save_queue_data(data)

            fb_res = await upload_reel_to_facebook(
                video_path=p_path,
                caption=pending_item.get("caption", ""),
                comment_text=pending_item.get("comment_text", "")
            )

            if fb_res.get("success"):
                bot_log(f"[FacebookBot] 🎉 Successfully published pending Reel: {fb_res.get('reel_url')}!")
                try:
                    from facebook_bot import share_reel_to_groups
                    m_url = f"https://nxly.online/movie/{active_job.get('detail_path')}" if active_job.get("detail_path") else "https://nxly.online"
                    await share_reel_to_groups(
                        reel_url=fb_res.get("reel_url"),
                        title=f"{title} • {p_label}",
                        watch_url=m_url
                    )
                except Exception as _ge:
                    bot_log(f"[FacebookBot] Group share note: {_ge}")
                p_path = os.path.join(reel_generator.REELS_DIR, p_filename)
                if os.path.exists(p_path):
                    try:
                        os.remove(p_path)
                        bot_log(f"[AutoPilot] 🧹 Cleaned up published video file: {p_filename}")
                    except Exception:
                        pass
                pending_uploads.pop(0)
                active_job["pending_uploads"] = pending_uploads
                now_str = time.strftime("%Y-%m-%d %H:%M:%S")
                active_job["last_posted_at"] = now_str
                active_job["completed_parts_count"] = active_job.get("completed_parts_count", 0) + 1
                active_job["history"].append({
                    "part_label": p_label,
                    "posted_at": now_str,
                    "video_filename": p_filename,
                    "fb_url": fb_res.get("reel_url"),
                    "yt_url": None,
                    "file_size_mb": pending_item.get("file_size_mb", 0)
                })
                advance_job_progression(active_job, is_series, data, active_idx, now_str)

                fb_cfg["last_post_time"] = int(time.time())
                save_fb_config(fb_cfg)
                data["state"]["is_busy"] = False
                data["state"]["step"] = "idle"
                data["state"]["progress_percent"] = 100
                data["state"]["step_label"] = f"Published {p_label} successfully"
                data["state"]["last_run_timestamp"] = int(time.time())
                data["state"]["next_run_timestamp"] = int(time.time()) + (int(fb_cfg.get("post_interval_minutes", 45)) * 60)
                save_queue_data(data)
                return {"success": True, "reuploaded": True, "reel_url": fb_res.get("reel_url")}
            else:
                bot_log(f"[FacebookBot] ❌ Re-upload failed: {fb_res.get('error')}. Video remains in pending queue.")
                active_job["status"] = "waiting_upload"
                data["state"]["is_busy"] = False
                data["state"]["step"] = "idle"
                data["state"]["step_label"] = f"Upload failed: {fb_res.get('error')}"
                save_queue_data(data)
                return {"success": False, "error": fb_res.get("error")}
        else:
            bot_log(f"[AutoPilot] ⚠️ Video file '{p_filename}' not found on disk. Removing from pending list.")
            pending_uploads.pop(0)
            active_job["pending_uploads"] = pending_uploads
            save_queue_data(data)

    active_job["status"] = "processing"

    if is_series:
        await ensure_series_metadata(active_job)
        save_queue_data(data)

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
        total_parts = active_job.get("total_planned_parts") or active_job.get("total_parts", 132)
        part_label = f"Part {part}/{total_parts}"
        top_text = f"{title} • Part {part}"
        start_seconds = (part - 1) * part_duration + 600
        se_arg = 0
        ep_arg = 0

    bot_log(f"[AutoPilot] ⚡ Step 1: Processing Job ({active_idx+1}/{len(jobs)}): '{title}' [{part_label}]")
    data["state"]["is_busy"] = True
    data["state"]["busy_since"] = now_ts
    data["state"]["step"] = "fetching_stream"
    data["state"]["progress_percent"] = 25
    data["state"]["step_label"] = f"Resolving high-speed stream for {part_label}..."
    data["state"]["current_job_id"] = active_job.get("id")
    data["state"]["current_title"] = title
    data["state"]["current_part_label"] = part_label
    save_queue_data(data)

    try:
        # Pre-flight Check: Validate Platform Credentials before heavy ffmpeg work
        if fb_is_active:
            from facebook_bot import check_fb_token_validity, load_fb_config
            fb_cfg = load_fb_config()
            fb_valid, fb_err = await check_fb_token_validity(fb_cfg.get("page_token", ""), fb_cfg.get("page_id", ""))
            if not fb_valid:
                bot_log(f"[FacebookBot] ⚠️ Pre-flight notice: {fb_err}. Continuing with upload attempt...")

        # 1. Fetch direct video stream
        stream_candidates = []
        try:
            from main import api_resource, resolve_subject_id
            sid = active_job.get("subject_id", "")
            dpath = active_job.get("detail_path", "")
            if not sid and dpath:
                try:
                    sid = await resolve_subject_id(dpath)
                    if sid:
                        active_job["subject_id"] = str(sid)
                except Exception:
                    pass

            is_retry = active_job.get("retry_count", 0) > 0
            bot_log(f"[AutoPilot] 🔍 Resolving stream for '{title}' (detailPath={dpath}, S{se_arg}E{ep_arg}, force_fresh={is_retry})...")
            res = await api_resource(
                se=se_arg,
                ep=ep_arg,
                detailPath=dpath,
                subjectId=sid,
                force_fresh=is_retry
            )
            items = res.get("data", {}).get("list") or []
            for it in items:
                direct_link = it.get("url") or ""
                proxy_link = it.get("resourceLink") or ""
                for lk in [direct_link, proxy_link]:
                    if "source_url=" in lk and lk == direct_link:
                        try:
                            lk = urllib.parse.unquote(lk.split("source_url=")[-1])
                        except Exception:
                            pass
                    if lk and lk not in stream_candidates:
                        link_low = lk.lower()
                        if not any(bad in link_low for bad in ["vegamovies.pet", "vegamovies.cx", "asiafilm.org", "fzmovies", "southfreak"]):
                            stream_candidates.append(lk)
        except Exception as e:
            bot_log(f"[AutoPilot] Warning: failed to fetch stream: {e}")

        if not stream_candidates:
            bot_log(f"[AutoPilot] ⚠️ No playable video stream found for '{title}' [{part_label}].")
            if is_series:
                s_map = active_job.get("season_episodes_map") or {}
                max_eps_this_s = s_map.get(str(active_job["current_season"]), active_job.get("total_episodes_in_season", 10))
                if active_job["current_episode"] < max_eps_this_s:
                    bot_log(f"[AutoPilot] ⏭️ Skipping unavailable S{season}E{episode} -> advancing to Episode {episode + 1}...")
                    active_job["current_episode"] += 1
                    active_job["current_part"] = 1
                    active_job["status"] = "queued"
                    save_queue_data(data)
                else:
                    active_job["status"] = "paused"
            else:
                active_job["status"] = "paused"
            data["state"]["is_busy"] = False
            data["state"]["step"] = "error_no_stream"
            save_queue_data(data)
            return {"success": False, "error": "No working video stream"}

        # 2. Slice and Render Video Clip
        data["state"]["step"] = "rendering"
        save_queue_data(data)

        os.makedirs(os.path.join(BASE_DIR, "public", "reels"), exist_ok=True)
        sanitized = re.sub(r'[^a-zA-Z0-9_\-]', '_', title)[:30]
        timestamp = int(time.time())
        output_filename = f"fb_reel_{sanitized}_P{part}_{timestamp}.mp4"
        output_path = os.path.join(BASE_DIR, "public", "reels", output_filename)
        overlay_path = os.path.join(BASE_DIR, "public", "reels", f"fb_overlay_{sanitized}_{timestamp}.png")

        try:
            total_p_calc = active_job.get("parts_per_episode", 3) if is_series else active_job.get("total_parts", 8)
            import random
            hook_msg = random.choice(reel_generator.VIRAL_HOOKS)
            reel_generator.generate_overlay_image(
                title=title,
                part_num=part,
                total_parts=total_p_calc,
                top_text_custom=top_text,
                bottom_text_custom='TO WATCH FULL SEARCH GOOGLE: "NXLY STREAM"',
                output_path=overlay_path,
                hook_text=hook_msg
            )
        except Exception as oe:
            bot_log(f"[AutoPilot] Overlay generation note: {oe}")

        render_success = False
        for s_url in stream_candidates[:3]:
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
            if os.path.exists(output_path):
                try: os.remove(output_path)
                except Exception: pass
            retry_count = active_job.get("retry_count", 0) + 1
            active_job["retry_count"] = retry_count
            active_job["last_error"] = f"Render failed at {part_label}"
            active_job["status"] = "queued"
            bot_log(f"[AutoPilot] ⚠️ Video rendering for '{title}' [{part_label}] encountered an issue (Attempt {retry_count}). Retrying this exact video part in next cycle without skipping...")

            data["state"]["is_busy"] = False
            data["state"]["step"] = "idle"
            save_queue_data(data)
            return {"success": False, "error": f"Rendering failed for {title}"}

        file_size_mb = round(os.path.getsize(output_path) / (1024 * 1024), 2)
        bot_log(f"[AutoPilot] ✅ Render complete: {output_filename} ({file_size_mb} MB)")

        # 3. Multi-Platform Distribution
        data["state"]["step"] = "distributing"
        save_queue_data(data)

        movie_web_url = f"https://nxly.online/movie/{active_job.get('detail_path')}" if active_job.get("detail_path") else "https://nxly.online"
        import random
        eng_question = random.choice(reel_generator.ENGAGEMENT_QUESTIONS)

        # Dynamic high-reach algorithm tags
        clean_tag = re.sub(r'[^a-zA-Z0-9]', '', title.lower())
        caption = (
            f"🔥 {title} • Part {part} 🎬🍿\n"
            f"━━━━━━━━━━━━━━━━━━\n"
            f"💬 {eng_question}\n\n"
            f"🍿 পুরো মুভি/সিরিজ দেখতে Google-এ Search করুন: NXLY Stream\n"
            f"👉 ১ম যে ওয়েবসাইট আসবে সেটাই! (First result is the one)\n\n"
            f"🔗 ডিরেক্ট ওয়াচ লিংক ১ম কমেন্টে দেওয়া আছে (Check 1st Comment)! 👇\n"
            f"📲 Watch all movies & web series in Full HD for FREE on NXLY Stream!\n\n"
            f"#reelsfb #reelsviral #viralreels #fbreels #fbviral #movieclips #hindidubbed #trendingreels #fyp #{clean_tag[:20]}"
        )
        comment_msg = (
            f"🍿 পুরো মুভি/সিরিজ দেখতে Google-এ Search করুন: \"NXLY Stream\"\n"
            f"🌐 অথবা সরাসরি লিঙ্ক: {movie_web_url}\n\n"
            f"📲 Official Android App: https://nxly.online/download\n\n"
            f"👇 এই দৃশ্যটি কেমন লাগলো? ১ থেকে ১০ এর মধ্যে রেটিং দিন! 🔥"
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
                if fb_res.get("success") and fb_res.get("reel_url"):
                    try:
                        from facebook_bot import share_reel_to_groups
                        await share_reel_to_groups(
                            reel_url=fb_res.get("reel_url"),
                            title=top_text,
                            watch_url=movie_web_url
                        )
                    except Exception as _ge:
                        bot_log(f"[FacebookBot] Group share note: {_ge}")
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
        any_success = bool(fb_res.get("success") or yt_res.get("success") or tt_res.get("success"))
        active_platforms_count = sum([1 for act in [fb_is_active, yt_is_active, tt_is_active] if act])

        now_str = time.strftime("%Y-%m-%d %H:%M:%S")
        if active_platforms_count > 0 and not any_success:
            bot_log(f"[AutoPilot] ❌ Upload failed on all active platforms ({fb_res.get('error') or 'Disconnect'}).")
            bot_log(f"[AutoPilot] ⏳ Video '{output_filename}' saved to waiting queue.")
            bot_log(f"[AutoPilot] 📌 Job will stay in waiting queue and automatically re-upload Part {part} once Facebook is connected!")

            pending_list = active_job.get("pending_uploads") or []
            pending_list.append({
                "part_label": part_label,
                "season": season if is_series else 1,
                "episode": episode if is_series else 1,
                "part": part,
                "video_filename": output_filename,
                "caption": caption,
                "comment_text": comment_msg,
                "file_size_mb": file_size_mb,
                "rendered_at": now_str,
                "status": "waiting_upload",
                "reason": fb_res.get("error") or "Upload failed"
            })
            active_job["pending_uploads"] = pending_list
            active_job["status"] = "waiting_upload"

            data["state"]["is_busy"] = False
            data["state"]["step"] = "idle"
            data["state"]["step_label"] = f"Waiting: {part_label} pending publish (Facebook Token Expired)"
            data["state"]["progress_percent"] = 0
            data["state"]["next_run_timestamp"] = int(time.time()) + (int(fb_cfg.get("post_interval_minutes", 45)) * 60)
            save_queue_data(data)
            return {"success": False, "waiting_upload": True, "error": "Upload failed, saved to pending queue"}

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

        job_finished = advance_job_progression(active_job, is_series, data, active_idx, now_str)

        # Clean up local video file immediately upon successful distribution
        if os.path.exists(output_path):
            try:
                os.remove(output_path)
                bot_log(f"[AutoPilot] 🧹 Cleaned up published video file from storage: {output_filename}")
            except Exception as _ce:
                bot_log(f"[AutoPilot] Note on video cleanup: {_ce}")

        # Routine purge of any orphaned or stale video files in storage
        try:
            reel_generator.cleanup_old_reels_storage()
        except Exception:
            pass

        # Save state & update last post timestamp
        fb_cfg = load_fb_config()
        fb_cfg["last_post_time"] = int(time.time())
        save_fb_config(fb_cfg)

        data["state"]["is_busy"] = False
        data["state"]["step"] = "idle"
        data["state"]["progress_percent"] = 100
        data["state"]["step_label"] = "Published successfully"
        data["state"]["last_run_timestamp"] = int(time.time())
        data["state"]["next_run_timestamp"] = int(time.time()) + (int(fb_cfg.get("post_interval_minutes", 45)) * 60)
        save_queue_data(data)

        bot_log(f"[AutoPilot] ⏱️ Next scheduled video post in {fb_cfg.get('post_interval_minutes', 45)} minutes.")

        return {
            "success": True,
            "title": title,
            "part_label": part_label,
            "finished": job_finished,
            "facebook": fb_res,
            "youtube": yt_res,
            "tiktok": tt_res
        }
    except Exception as e:
        bot_log(f"[AutoPilot] ❌ FATAL error in cycle: {e}")
        bot_log(traceback.format_exc())
        active_job["status"] = "queued"
        return {"success": False, "error": str(e)}
    finally:
        f_data = load_queue_data()
        f_data["state"]["is_busy"] = False
        f_data["state"]["busy_since"] = 0
        f_data["state"]["step"] = "idle"
        f_data["state"]["progress_percent"] = 0
        save_queue_data(f_data)
