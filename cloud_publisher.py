#!/usr/bin/env python3
"""
Streamhit Cloud Worker (True GitHub Actions Video Engine)
---------------------------------------------------------
Executes FFmpeg rendering and Facebook upload DIRECTLY on the GitHub Actions
Cloud Runner (2-Core CPU, 7GB RAM, 1Gbps Network).
ZERO CPU usage and ZERO video memory pressure on your cPanel server.
"""
import os
import sys
import time
import json
import re
import hmac
import hashlib
import asyncio
import subprocess
import urllib.request
import urllib.parse
from pathlib import Path

# Safe terminal logging (Windows CP1252 / Linux UTF-8 safe)
def log(msg: str):
    clean = re.sub(r'[^\x00-\x7F]+', ' ', str(msg))
    print(f"[{time.strftime('%Y-%m-%d %H:%M:%S')}] {clean}", flush=True)

SERVER_URL = os.environ.get("SERVER_URL", "https://nxly.online").rstrip("/")
DEPLOY_SECRET = os.environ.get("DEPLOY_SECRET", "streamhit_secret_update_2026")

def get_auth_token():
    expires = int(time.time()) + 86400
    payload = f"admin:{expires}"
    sig = hmac.new(DEPLOY_SECRET.encode(), payload.encode(), hashlib.sha256).hexdigest()
    return f"{payload}.{sig}"

def server_api(path: str, data: dict = None, method: str = "GET") -> dict:
    url = f"{SERVER_URL}{path}"
    headers = {
        "Authorization": f"Bearer {get_auth_token()}",
        "Content-Type": "application/json",
        "User-Agent": "StreamhitCloudWorker/2.0"
    }
    body = json.dumps(data).encode("utf-8") if data else None
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=30) as res:
        return json.loads(res.read().decode("utf-8"))

async def upload_to_facebook(video_path: str, caption: str, comment_text: str, page_id: str, page_token: str) -> dict:
    import httpx
    file_size = os.path.getsize(video_path)
    log(f"[FacebookCloud] Initializing Reels upload for {file_size} bytes...")
    
    init_url = f"https://graph.facebook.com/v19.0/{page_id}/video_reels"
    async with httpx.AsyncClient(timeout=120) as client:
        # Phase 1: Init
        r_init = await client.post(init_url, data={
            "upload_phase": "start",
            "access_token": page_token
        })
        d_init = r_init.json()
        video_id = d_init.get("video_id")
        upload_url = d_init.get("upload_url")
        if not video_id or not upload_url:
            return {"success": False, "error": f"Init failed: {d_init}"}
            
        log(f"[FacebookCloud] Video ID {video_id}. Uploading binary to Meta...")
        
        # Phase 2: Binary Upload
        with open(video_path, "rb") as vf:
            r_up = await client.post(
                upload_url,
                headers={
                    "Authorization": f"OAuth {page_token}",
                    "offset": "0",
                    "file_size": str(file_size)
                },
                content=vf.read()
            )
        d_up = r_up.json()
        if not d_up.get("success", False):
            return {"success": False, "error": f"Binary upload failed: {d_up}"}
            
        log(f"[FacebookCloud] Binary uploaded. Finalizing publish...")
        
        # Phase 3: Finish Publish
        safe_caption = re.sub(r'https?://\S+', '', caption or '').strip()
        safe_caption = re.sub(r'(👉|🔗|Link:)\s*\n', '', safe_caption).strip()
        r_fin = await client.post(
            init_url,
            data={
                "upload_phase": "finish",
                "access_token": page_token,
                "video_id": video_id,
                "video_state": "PUBLISHED",
                "description": safe_caption
            },
            headers={"Content-Type": "application/x-www-form-urlencoded; charset=utf-8"}
        )
        d_fin = r_fin.json()
        if "error" in d_fin:
            return {"success": False, "error": d_fin["error"].get("message", "Finish failed")}
            
        reel_url = f"https://www.facebook.com/reel/{video_id}"
        log(f"[FacebookCloud] SUCCESS! Published: {reel_url}")
        
        # Phase 4: Auto-Comment
        if comment_text:
            log(f"[FacebookCloud] Posting first comment on Reel {video_id}...")
            await asyncio.sleep(4)
            try:
                await client.post(
                    f"https://graph.facebook.com/v19.0/{video_id}/comments",
                    data={"access_token": page_token, "message": comment_text}
                )
            except Exception as ce:
                log(f"[FacebookCloud] Comment note: {ce}")
                
        return {"success": True, "video_id": video_id, "reel_url": reel_url}

async def main():
    log("=== Streamhit Cloud Video Worker (GitHub Actions Runner) ===")
    log(f"Server Target: {SERVER_URL}")
    
    # 1. Fetch next task from server
    try:
        task_data = server_api("/api/admin/automation/cloud-task")
    except Exception as te:
        log(f"Failed to fetch cloud-task from server: {te}")
        sys.exit(1)
        
    if not task_data.get("has_task"):
        log("No pending tasks in queue. Everything is up to date!")
        return

    job_id = task_data["job_id"]
    title = task_data["title"]
    part = task_data["part"]
    total_parts = task_data["total_parts"]
    duration_seconds = task_data.get("part_duration", 75)
    start_seconds = task_data.get("start_seconds", 600)
    stream_candidates = task_data.get("stream_candidates", [])
    fb_info = task_data.get("facebook", {})
    
    log(f"Target Job: '{title}' [Part {part}/{total_parts}]")
    log(f"Slicing: {duration_seconds}s at offset {start_seconds}s")
    
    if not stream_candidates:
        log("ERROR: No stream candidates provided by server!")
        sys.exit(1)

    # 2. Build Overlay Banner PNG
    import reel_generator
    overlay_path = os.path.join(os.getcwd(), f"overlay_p{part}_{int(time.time())}.png")
    top_text = f"{title} Part {part}"
    reel_generator.generate_overlay_image(
        title=title,
        part_num=part,
        total_parts=total_parts,
        top_text_custom=top_text,
        output_path=overlay_path
    )
    log(f"Overlay banner created: {overlay_path}")

    # 3. Render 16:9 Widescreen + Ambient Blurred 9:16 Video using FFmpeg
    output_mp4 = os.path.join(os.getcwd(), f"cloud_reel_{int(time.time())}.mp4")
    
    filter_complex = (
        "[0:v]scale=180:320:force_original_aspect_ratio=increase,crop=180:320,boxblur=8:2,scale=1080:1920,eq=brightness=-0.15:contrast=1.06[bg];"
        "[0:v]crop=trunc(in_w*0.96/2)*2:trunc(in_h*0.96/2)*2,scale=1080:-2,eq=contrast=1.08:brightness=0.01:saturation=1.12[fg];"
        "[bg][fg]overlay=(W-w)/2:(H-h)/2[merged];"
        "[merged][1:v]overlay=0:0[vfinal]"
    )
    
    render_ok = False
    for idx, clean_url in enumerate(stream_candidates):
        log(f"Attempting render with stream #{idx+1} on GitHub runner...")
        cmd = [
            "ffmpeg", "-y", "-nostdin",
            "-user_agent", "okhttp/4.12.0",
            "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5",
            "-ss", str(start_seconds),
            "-t", str(duration_seconds),
            "-i", clean_url,
            "-loop", "1", "-t", str(duration_seconds), "-i", overlay_path,
            "-filter_complex", filter_complex,
            "-map", "[vfinal]", "-map", "0:a?",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "22",
            "-c:a", "aac", "-b:a", "128k", "-af", "volume=1.20,dynaudnorm=p=0.95:m=10:s=12",
            "-threads", "2",
            "-t", str(duration_seconds),
            "-movflags", "+faststart",
            output_mp4
        ]
        
        res = subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
        if res.returncode == 0 and os.path.exists(output_mp4) and os.path.getsize(output_mp4) > 1000:
            render_ok = True
            log("FFmpeg cloud render succeeded!")
            break
        else:
            err = res.stderr[-300:] if res.stderr else "Unknown error"
            log(f"Stream #{idx+1} failed: {err.strip()}")

    if not render_ok or not os.path.exists(output_mp4):
        log("FATAL: All FFmpeg render attempts failed on cloud runner.")
        sys.exit(1)

    file_size_mb = round(os.path.getsize(output_mp4) / (1024 * 1024), 2)
    log(f"Rendered video size: {file_size_mb} MB")

    # 4. Upload to Facebook Page
    clean_tag = re.sub(r'[^a-zA-Z0-9]', '', title.lower())
    caption = (
        f"🔥 {title} • Part {part} 🎬🍿\n"
        f"━━━━━━━━━━━━━━━━━━\n"
        f"💬 Rate this scene from 1 to 10 in the comments below! 👇🔥\n\n"
        f"🍿 পুরো মুভি/সিরিজ দেখতে Google-এ Search করুন: NXLY Stream\n"
        f"👉 ১ম যে ওয়েবসাইট আসবে সেটাই!\n\n"
        f"🔗 ডিরেক্ট ওয়াচ লিংক ১ম কমেন্টে দেওয়া আছে (Check 1st Comment)! 👇\n"
        f"📲 Watch all movies & web series in Full HD for FREE on NXLY Stream!\n\n"
        f"#reelsfb #reelsviral #viralreels #fbreels #fbviral #movieclips #hindidubbed #trendingreels #fyp #{clean_tag[:20]}"
    )
    comment = (
        f"🍿 পুরো মুভি/সিরিজ দেখতে Google Search করুন: \"NXLY Stream\"\n"
        f"🌐 অথবা সরাসরি লিঙ্ক: https://nxly.online/movie/{task_data.get('detail_path')}\n\n"
        f"📲 Official Android App: https://nxly.online/download\n\n"
        f"👇 এই দৃশ্যটি কেমন লাগলো? ১ থেকে ১০ এর মধ্যে রেটিং দিন! 🔥"
    )

    page_id = fb_info.get("page_id")
    page_token = fb_info.get("page_token")
    if not page_id or not page_token:
        log("Facebook credentials missing in task. Cannot upload.")
        sys.exit(1)

    fb_res = await upload_to_facebook(
        video_path=output_mp4,
        caption=caption,
        comment_text=comment,
        page_id=page_id,
        page_token=page_token
    )

    if not fb_res.get("success"):
        log(f"Facebook upload failed: {fb_res.get('error')}")
        sys.exit(1)

    reel_url = fb_res.get("reel_url", "")

    # 5. Report Done to Server
    log("Reporting successful completion to server...")
    try:
        done_res = server_api("/api/admin/automation/cloud-done", {
            "job_id": job_id,
            "part": part,
            "fb_url": reel_url,
            "file_size_mb": file_size_mb
        }, method="POST")
        log(f"Server response: {done_res}")
    except Exception as de:
        log(f"Report done warning: {de}")

    # 6. Cleanup local files on runner
    for p in [overlay_path, output_mp4]:
        if os.path.exists(p):
            try: os.remove(p)
            except Exception: pass

    log("=== Cloud Publisher Run Finished with 100% SUCCESS! ===")

if __name__ == "__main__":
    asyncio.run(main())
