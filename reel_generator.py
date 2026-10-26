import os
import re
import json
import time
import uuid
import asyncio
import subprocess
import sys
from typing import Dict, Any, List, Optional

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
REELS_DIR = os.path.join(BASE_DIR, "public", "reels")
HISTORY_FILE = os.path.join(REELS_DIR, "reels_history.json")

try:
    os.makedirs(REELS_DIR, exist_ok=True)
except Exception:
    pass

JOBS_FILE = os.path.join(REELS_DIR, "reels_jobs.json")

# In-memory and persistent job tracker
active_reel_jobs: Dict[str, Dict[str, Any]] = {}

def load_jobs_state() -> Dict[str, Dict[str, Any]]:
    if not os.path.exists(JOBS_FILE):
        return {}
    try:
        with open(JOBS_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}

def save_job_state(job_id: str, job_data: Dict[str, Any]):
    global active_reel_jobs
    active_reel_jobs[job_id] = job_data
    try:
        all_jobs = load_jobs_state()
        all_jobs[job_id] = dict(job_data)
        if len(all_jobs) > 25:
            sorted_keys = sorted(all_jobs.keys(), key=lambda k: all_jobs[k].get("created_at", 0), reverse=True)
            all_jobs = {k: all_jobs[k] for k in sorted_keys[:25]}
        with open(JOBS_FILE, "w", encoding="utf-8") as f:
            json.dump(all_jobs, f, indent=2, ensure_ascii=False)
    except Exception as e:
        print(f"Error saving job state {job_id}: {e}")

def get_job_state(job_id: str) -> Optional[Dict[str, Any]]:
    if job_id in active_reel_jobs:
        return active_reel_jobs[job_id]
    all_jobs = load_jobs_state()
    return all_jobs.get(job_id)

def get_latest_or_active_job() -> Optional[Dict[str, Any]]:
    now = time.time()
    # Check in-memory first for active (expire zombie jobs older than 15 mins)
    for j in list(active_reel_jobs.values()):
        if j.get("status") in ("processing", "pending"):
            if now - j.get("created_at", 0) > 900:
                j["status"] = "failed"
                j["error"] = "Job timed out after 15 minutes"
                save_job_state(j["job_id"], j)
            else:
                return j
    all_jobs = load_jobs_state()
    for j in list(all_jobs.values()):
        if j.get("status") in ("processing", "pending"):
            if now - j.get("created_at", 0) > 900:
                j["status"] = "failed"
                j["error"] = "Job timed out after 15 minutes"
                save_job_state(j["job_id"], j)
            else:
                return j
    if all_jobs:
        sorted_jobs = sorted(all_jobs.values(), key=lambda x: x.get("created_at", 0), reverse=True)
        return sorted_jobs[0]
    return None

def cancel_all_active_jobs() -> Dict[str, Any]:
    global active_reel_jobs
    all_jobs = load_jobs_state()
    cancelled_count = 0
    for j in all_jobs.values():
        if j.get("status") in ("processing", "pending"):
            j["status"] = "cancelled"
            j["current_step"] = "Cancelled by admin"
            j["error"] = "Cancelled by admin"
            cancelled_count += 1
    active_reel_jobs = {}
    try:
        with open(JOBS_FILE, "w", encoding="utf-8") as f:
            json.dump(all_jobs, f, indent=2, ensure_ascii=False)
    except Exception as e:
        print(f"Error resetting jobs file: {e}")
    return {"code": 0, "cancelled": cancelled_count, "message": "All active jobs cleared successfully!"}

try:
    from PIL import Image, ImageDraw, ImageFont
    HAS_PIL = True
except ImportError:
    Image = None
    ImageDraw = None
    ImageFont = None
    HAS_PIL = False

def ensure_pil():
    global Image, ImageDraw, ImageFont, HAS_PIL
    if HAS_PIL:
        return True
    try:
        subprocess.run([sys.executable, "-m", "pip", "install", "Pillow", "--quiet"], check=True, timeout=60)
        from PIL import Image as _Img, ImageDraw as _IDraw, ImageFont as _IFont
        Image = _Img
        ImageDraw = _IDraw
        ImageFont = _IFont
        HAS_PIL = True
        return True
    except Exception as e:
        print(f"[ReelEngine] Notice: Pillow not available ({e})")
        return False

def _create_fallback_transparent_png(output_path: str):
    import zlib, struct
    width, height = 1080, 1920
    raw_scanline = b'\x00' + (b'\x00\x00\x00\x00' * width)
    raw_data = raw_scanline * height
    compressed = zlib.compress(raw_data)
    
    png = bytearray(b'\x89PNG\r\n\x1a\n')
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    png += struct.pack(">I", 13) + b'IHDR' + ihdr + struct.pack(">I", zlib.crc32(b'IHDR' + ihdr))
    png += struct.pack(">I", len(compressed)) + b'IDAT' + compressed + struct.pack(">I", zlib.crc32(b'IDAT' + compressed))
    png += struct.pack(">I", 0) + b'IEND' + struct.pack(">I", zlib.crc32(b'IEND'))
    
    with open(output_path, "wb") as f:
        f.write(png)

def get_font(size: int, bold: bool = True):
    if not HAS_PIL or ImageFont is None:
        return None
    font_paths = [
        os.path.join(BASE_DIR, "fonts", "bold.ttf" if bold else "regular.ttf"),
        "/home/lcsyxfen/nxly.online/fonts/bold.ttf" if bold else "/home/lcsyxfen/nxly.online/fonts/regular.ttf",
        "/home/lcsyxfen/streamhit.lc-synergy.ltd/fonts/bold.ttf" if bold else "/home/lcsyxfen/streamhit.lc-synergy.ltd/fonts/regular.ttf",
        "/usr/share/fonts/google-droid/DroidSans-Bold.ttf" if bold else "/usr/share/fonts/google-droid/DroidSans.ttf",
        "/usr/share/fonts/dejavu/DejaVuSansMono-Bold.ttf" if bold else "/usr/share/fonts/dejavu/DejaVuSansMono.ttf",
        "C:/Windows/Fonts/arialbd.ttf" if bold else "C:/Windows/Fonts/arial.ttf",
        "C:/Windows/Fonts/segoeuib.ttf" if bold else "C:/Windows/Fonts/segoeui.ttf",
    ]
    for p in font_paths:
        if p and os.path.exists(p):
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                pass
    try:
        return ImageFont.load_default()
    except Exception:
        return None

def parse_time_to_seconds(time_val) -> int:
    if isinstance(time_val, (int, float)):
        return max(0, int(time_val))
    s = str(time_val).strip()
    if not s:
        return 0
    if s.isdigit():
        return int(s)
    parts = s.split(":")
    try:
        if len(parts) == 2:
            return int(parts[0]) * 60 + int(float(parts[1]))
        elif len(parts) == 3:
            return int(parts[0]) * 3600 + int(parts[1]) * 60 + int(float(parts[2]))
        return int(float(s))
    except Exception:
        return 0

def format_seconds_to_time(secs: int) -> str:
    h = secs // 3600
    m = (secs % 3600) // 60
    s = secs % 60
    if h > 0:
        return f"{h:02d}:{m:02d}:{s:02d}"
    return f"{m:02d}:{s:02d}"

def generate_overlay_image(
    title: str,
    part_num: int,
    total_parts: int = 1,
    top_text_custom: str = "",
    bottom_text_custom: str = "",
    output_path: str = ""
) -> str:
    """
    Generates a high-impact, crisp 1080x1920 transparent PNG with bold
    top & bottom banners tailored for Reels / TikTok / Shorts.
    Uses large, readable TrueType fonts with high contrast and zero emoji glitches.
    """
    if not output_path:
        output_path = os.path.join(REELS_DIR, f"overlay_p{part_num}_{int(time.time()*1000)}.png")

    ensure_pil()
    if not HAS_PIL or Image is None or ImageDraw is None:
        _create_fallback_transparent_png(output_path)
        return output_path

    img = Image.new("RGBA", (1080, 1920), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    clean_title = (title or "FEATURED MOVIE").upper().strip()
    # Strip any emojis/non-ascii to avoid box □ glyph artifacts
    clean_title = re.sub(r'[^\x00-\x7F]+', '', clean_title).strip()
    if len(clean_title) > 32:
        clean_title = clean_title[:29] + "..."

    # ── TOP BANNER ──────────────────────────────────────────────────────────
    # Container box: Y: 60 to 360 (Height 300px)
    draw.rounded_rectangle([40, 60, 1040, 360], radius=32, fill=(10, 14, 24, 245), outline=(0, 210, 255, 240), width=6)

    # Large bold title font (72px down to 48px depending on length)
    font_size = 72 if len(clean_title) < 20 else (60 if len(clean_title) < 28 else 48)
    font_title = get_font(font_size, bold=True)
    t_box = draw.textbbox((0, 0), clean_title, font=font_title)
    t_width = t_box[2] - t_box[0]
    t_x = (1080 - t_width) // 2
    # Crisp drop shadow
    draw.text((t_x + 4, 105 + 4), clean_title, fill=(0, 0, 0, 230), font=font_title)
    draw.text((t_x, 105), clean_title, fill=(255, 255, 255), font=font_title)

    # Part badge (clean text without missing emoji glyphs)
    if top_text_custom:
        part_text = top_text_custom.replace("{title}", clean_title).replace("{part_num}", str(part_num)).replace("{total_parts}", str(total_parts))
        part_text = re.sub(r'[^\x00-\x7F]+', '', part_text).strip()
    else:
        part_text = f"PART {part_num}" if total_parts <= 1 else f"PART {part_num} / {total_parts}"
    
    font_part = get_font(52, bold=True)
    p_box = draw.textbbox((0, 0), part_text, font=font_part)
    p_width = p_box[2] - p_box[0]
    p_height = p_box[3] - p_box[1]
    
    badge_w = max(360, p_width + 80)
    badge_x1 = max(60, (1080 - badge_w) // 2)
    badge_x2 = min(1020, badge_x1 + badge_w)
    badge_y1 = 225
    badge_y2 = 315
    draw.rounded_rectangle([badge_x1, badge_y1, badge_x2, badge_y2], radius=24, fill=(255, 38, 78, 255), outline=(255, 130, 155, 240), width=4)
    
    p_x = (1080 - p_width) // 2
    p_y = badge_y1 + (badge_y2 - badge_y1 - p_height) // 2 - 3
    draw.text((p_x + 2, p_y + 2), part_text, fill=(0, 0, 0, 180), font=font_part)
    draw.text((p_x, p_y), part_text, fill=(255, 255, 255), font=font_part)

    # ── BOTTOM BANNER ───────────────────────────────────────────────────────
    # Positioned at Y: 1290 to 1510 (Height 220px) — completely ABOVE Facebook Reels
    # page name, title, follow button, caption, and progress bar (which cover Y >= 1500)
    draw.rounded_rectangle([40, 1290, 1040, 1510], radius=28, fill=(10, 14, 24, 245), outline=(255, 42, 84, 240), width=6)

    # Line 1: Call to Action (Big Bold Golden Yellow)
    if bottom_text_custom:
        b1_text = bottom_text_custom.replace("{title}", clean_title).replace("{part_num}", str(part_num))
        b1_text = re.sub(r'[^\x00-\x7F]+', '', b1_text).strip()
    else:
        b1_text = "FULL MOVIE LINK IN 1ST COMMENT"
    b2_text = "Watch Free in 1080p HD on NXLY App"

    font_b1 = get_font(52, bold=True)
    b1_box = draw.textbbox((0, 0), b1_text, font=font_b1)
    b1_w = b1_box[2] - b1_box[0]
    b1_x = (1080 - b1_w) // 2
    draw.text((b1_x + 3, 1335 + 3), b1_text, fill=(0, 0, 0, 220), font=font_b1)
    draw.text((b1_x, 1335), b1_text, fill=(255, 225, 40), font=font_b1)

    # Line 2: App Branding (Electric Cyan)
    font_b2 = get_font(40, bold=True)
    b2_box = draw.textbbox((0, 0), b2_text, font=font_b2)
    b2_w = b2_box[2] - b2_box[0]
    b2_x = (1080 - b2_w) // 2
    draw.text((b2_x + 3, 1425 + 3), b2_text, fill=(0, 0, 0, 220), font=font_b2)
    draw.text((b2_x, 1425), b2_text, fill=(0, 240, 255), font=font_b2)

    if not output_path:
        output_path = os.path.join(REELS_DIR, f"overlay_p{part_num}_{int(time.time()*1000)}.png")
    
    img.save(output_path, "PNG")
    return output_path

def generate_captions(title: str, part_num: int, total_parts: int = 1) -> Dict[str, str]:
    slug = re.sub(r"[^a-zA-Z0-9]+", "", title.lower())
    title_short = f"🔥 {title} - PART {part_num} 🎬🍿"
    
    caption = (
        f"🔥 {title} • PART {part_num} 🎬🍿\n"
        f"━━━━━━━━━━━━━━━━━━\n"
        f"💬 Full Movie / Series link is given in 1st comment! 👇\n"
        f"📲 Watch all movies & web series in Full HD for FREE on NXLY Stream App!\n\n"
        f"👉 Download Link in Comment & Bio 👈\n\n"
        f"#reels #shorts #viral #fyp #movie #nxlystream #trending #part{part_num} #{slug}"
    )
    
    return {
        "title": title_short,
        "caption": caption
    }

def load_reels_history() -> List[Dict[str, Any]]:
    if not os.path.exists(HISTORY_FILE):
        return []
    try:
        with open(HISTORY_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return []

def save_reels_history(history: List[Dict[str, Any]]):
    try:
        with open(HISTORY_FILE, "w", encoding="utf-8") as f:
            json.dump(history, f, indent=2, ensure_ascii=False)
    except Exception as e:
        print(f"Error saving reels history: {e}")

def get_ffmpeg_path() -> str:
    try:
        import imageio_ffmpeg
        p = imageio_ffmpeg.get_ffmpeg_exe()
        if p and os.path.exists(p):
            return p
    except Exception:
        pass
    home = os.path.expanduser("~")
    for candidate in [
        os.path.join(home, "bin", "ffmpeg"),
        os.path.join(home, ".local", "bin", "ffmpeg"),
        "/usr/local/bin/ffmpeg",
        "/usr/bin/ffmpeg",
        "ffmpeg"
    ]:
        if os.path.exists(candidate) and os.access(candidate, os.X_OK):
            return candidate
    return "ffmpeg"

async def render_single_reel_part(
    stream_url: str,
    start_seconds: int,
    duration_seconds: int,
    overlay_png_path: str,
    output_mp4_path: str,
    anti_copyright: bool = True
) -> bool:
    """
    Cuts video starting at start_seconds with duration_seconds,
    applies 9:16 layout with ambient blurred background + crisp center foreground,
    anti-copyright color/speed/audio EQ nudges, and top/bottom overlay banner.
    """
    clean_url = stream_url.strip()
    if "source_url=" in clean_url:
        if clean_url.startswith("/fetch"):
            clean_url = "https://nxly.online" + clean_url
    elif "hakunaymatata.com" in clean_url or "aoneroom.com" in clean_url:
        import urllib.parse
        clean_url = f"https://nxly.online/fetch?source_url={urllib.parse.quote(clean_url)}"
    elif clean_url.startswith("/fetch"):
        clean_url = "https://nxly.online" + clean_url

    if anti_copyright:
        filter_complex = (
            "[0:v]scale=360:640:force_original_aspect_ratio=increase,crop=360:640,boxblur=10:1,scale=1080:1920,eq=brightness=-0.15:contrast=1.08,setpts=PTS/1.03[bg];"
            "[0:v]crop=trunc(in_w*0.94/2)*2:trunc(in_h*0.94/2)*2,scale=1080:-2:flags=bicubic,eq=contrast=1.07:brightness=0.01:saturation=1.12,noise=alls=4:allf=t+u,setpts=PTS/1.03[fg];"
            "[bg][fg]overlay=(W-w)/2:(H-h)/2[merged];"
            "[merged][1:v]overlay=0:0[vfinal];"
            "[0:a]asetrate=44100*1.045,atempo=1.03/1.045,highpass=f=80,lowpass=f=12500,equalizer=f=1000:t=q:w=1.5:g=1.5,aecho=0.8:0.9:25:0.12[afinal]"
        )
        map_args = ["-map", "[vfinal]", "-map", "[afinal]"]
    else:
        filter_complex = (
            "[0:v]scale=360:640:force_original_aspect_ratio=increase,crop=360:640,boxblur=8:1,scale=1080:1920,eq=brightness=-0.10[bg];"
            "[0:v]scale=1080:-2:flags=bicubic[fg];"
            "[bg][fg]overlay=(W-w)/2:(H-h)/2[merged];"
            "[merged][1:v]overlay=0:0[vfinal]"
        )
        map_args = ["-map", "[vfinal]", "-map", "0:a?"]

    headers_str = "User-Agent: okhttp/4.12.0\r\nReferer: https://123movienow.cc/\r\n"
    cmd = [
        get_ffmpeg_path(), "-y", "-nostdin",
        "-headers", headers_str,
        "-reconnect", "1",
        "-reconnect_streamed", "1",
        "-reconnect_delay_max", "5",
        "-ss", str(start_seconds),
        "-t", str(duration_seconds),
        "-i", clean_url,
        "-loop", "1", "-t", str(duration_seconds), "-i", overlay_png_path,
        "-filter_complex", filter_complex,
        *map_args,
        "-c:v", "libx264",
        "-preset", "veryfast",
        "-crf", "22",
        "-c:a", "aac",
        "-b:a", "128k",
        "-ac", "2",
        "-ar", "44100",
        "-t", str(duration_seconds),
        "-movflags", "+faststart",
        output_mp4_path
    ]

    print(f"[ReelEngine] Running FFmpeg for {output_mp4_path} from {start_seconds}s for {duration_seconds}s...")
    
    def _run_sync(cmd_to_run):
        try:
            return subprocess.run(cmd_to_run, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True, timeout=240)
        except subprocess.TimeoutExpired:
            print("[ReelEngine] FFmpeg process timed out after 240 seconds!")
            return None
        except Exception as ex:
            print(f"[ReelEngine] FFmpeg execution error: {ex}")
            return None

    res = await asyncio.to_thread(_run_sync, cmd)
    
    if res is None or res.returncode != 0:
        err_msg = res.stderr[-800:] if (res and res.stderr) else "Timeout or Unknown error"
        print(f"[ReelEngine] FFmpeg error: {err_msg}")
        # Try fallback without audio filter in case audio stream was missing/corrupt or rate-shifted
        print("[ReelEngine] Retrying with fallback audio filter...")
        fallback_fc = (
            "[0:v]scale=360:640:force_original_aspect_ratio=increase,crop=360:640,boxblur=10:1,scale=1080:1920,eq=brightness=-0.15:contrast=1.08[bg];"
            "[0:v]crop=trunc(in_w*0.94/2)*2:trunc(in_h*0.94/2)*2,scale=1080:-2:flags=bicubic,eq=contrast=1.06:saturation=1.10[fg];"
            "[bg][fg]overlay=(W-w)/2:(H-h)/2[merged];"
            "[merged][1:v]overlay=0:0[vfinal]"
        )
        fallback_cmd = [
            get_ffmpeg_path(), "-y", "-nostdin",
            "-headers", headers_str,
            "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5",
            "-ss", str(start_seconds),
            "-t", str(duration_seconds),
            "-i", clean_url,
            "-loop", "1", "-t", str(duration_seconds), "-i", overlay_png_path,
            "-filter_complex", fallback_fc,
            "-map", "[vfinal]", "-map", "0:a?",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "22",
            "-c:a", "aac", "-b:a", "128k",
            "-t", str(duration_seconds), "-movflags", "+faststart",
            output_mp4_path
        ]
        fallback_res = await asyncio.to_thread(_run_sync, fallback_cmd)
        if fallback_res and fallback_res.returncode == 0 and os.path.exists(output_mp4_path) and os.path.getsize(output_mp4_path) > 1000:
            return True
        return False
        
    return os.path.exists(output_mp4_path) and os.path.getsize(output_mp4_path) > 1000

async def execute_batch_reel_job(
    job_id: str,
    title: str,
    stream_url: str,
    start_time_str: str,
    part_duration: int,
    num_parts: int,
    start_part_num: int,
    top_text_custom: str,
    bottom_text_custom: str,
    anti_copyright: bool
):
    """
    Background worker that executes sequential cutting and anti-copyright conversion.
    """
    start_sec = parse_time_to_seconds(start_time_str)
    job = active_reel_jobs.get(job_id)
    if not job:
        return

    job["status"] = "processing"
    job["progress"] = 5
    job["current_step"] = "Preparing Reel templates & filters..."
    save_job_state(job_id, job)

    slug = re.sub(r"[^a-zA-Z0-9]+", "_", title.strip())[:30].strip("_")
    timestamp = int(time.time())
    completed_reels = []

    try:
        for idx in range(num_parts):
            current_part_number = start_part_num + idx
            part_start_time = start_sec + (idx * part_duration)
            part_time_formatted = format_seconds_to_time(part_start_time)

            job["current_part"] = current_part_number
            job["current_step"] = f"Rendering Part {current_part_number} of {start_part_num + num_parts - 1} (Starting at {part_time_formatted})..."
            job["progress"] = int(10 + (idx / num_parts) * 80)
            save_job_state(job_id, job)

            # 1. Create transparent banner overlay PNG
            overlay_filename = f"overlay_{slug}_p{current_part_number}_{timestamp}.png"
            overlay_path = os.path.join(REELS_DIR, overlay_filename)
            generate_overlay_image(
                title=title,
                part_num=current_part_number,
                total_parts=start_part_num + num_parts - 1,
                top_text_custom=top_text_custom,
                bottom_text_custom=bottom_text_custom,
                output_path=overlay_path
            )

            # 2. Render MP4 with FFmpeg
            video_filename = f"{slug}_part_{current_part_number}_{timestamp}.mp4"
            video_path = os.path.join(REELS_DIR, video_filename)

            success = await render_single_reel_part(
                stream_url=stream_url,
                start_seconds=part_start_time,
                duration_seconds=part_duration,
                overlay_png_path=overlay_path,
                output_mp4_path=video_path,
                anti_copyright=anti_copyright
            )

            # Clean up temporary overlay PNG
            if os.path.exists(overlay_path):
                try:
                    os.remove(overlay_path)
                except Exception:
                    pass

            if not success:
                raise Exception(f"FFmpeg processing failed for Part {current_part_number}")

            file_size_bytes = os.path.getsize(video_path)
            file_size_mb = round(file_size_bytes / (1024 * 1024), 2)
            captions = generate_captions(title, current_part_number, start_part_num + num_parts - 1)

            part_data = {
                "id": str(uuid.uuid4())[:8],
                "part_number": current_part_number,
                "title": title,
                "title_suggestion": captions["title"],
                "caption": captions["caption"],
                "filename": video_filename,
                "video_url": f"/reels/{video_filename}",
                "start_time": part_time_formatted,
                "duration_seconds": part_duration,
                "file_size_mb": file_size_mb,
                "created_at": time.strftime("%Y-%m-%d %H:%M:%S")
            }

            completed_reels.append(part_data)
            job["results"] = completed_reels
            save_job_state(job_id, job)

        # Job complete
        job["status"] = "completed"
        job["progress"] = 100
        job["current_step"] = f"All {num_parts} parts generated successfully!"
        save_job_state(job_id, job)

        # Save to persistent history
        existing_history = load_reels_history()
        existing_history = completed_reels + existing_history
        save_reels_history(existing_history[:100]) # keep latest 100

    except Exception as e:
        print(f"[ReelEngine] Job {job_id} error: {e}")
        job["status"] = "failed"
        job["error"] = str(e)
        job["current_step"] = f"Failed: {str(e)}"
        save_job_state(job_id, job)

def start_reel_job(
    title: str,
    stream_url: str,
    start_time_str: str = "00:00",
    part_duration: int = 60,
    num_parts: int = 1,
    start_part_num: int = 1,
    top_text_custom: str = "",
    bottom_text_custom: str = "",
    anti_copyright: bool = True
) -> str:
    job_id = f"reel_job_{int(time.time()*1000)}"
    init_job = {
        "job_id": job_id,
        "title": title,
        "status": "pending",
        "progress": 0,
        "current_step": "Job queued...",
        "current_part": start_part_num,
        "total_parts": num_parts,
        "results": [],
        "error": None,
        "created_at": time.time()
    }
    save_job_state(job_id, init_job)

    asyncio.create_task(
        execute_batch_reel_job(
            job_id=job_id,
            title=title,
            stream_url=stream_url,
            start_time_str=start_time_str,
            part_duration=part_duration,
            num_parts=num_parts,
            start_part_num=start_part_num,
            top_text_custom=top_text_custom,
            bottom_text_custom=bottom_text_custom,
            anti_copyright=anti_copyright
        )
    )

    return job_id

def delete_reel_file(filename: str) -> bool:
    clean_name = os.path.basename(filename)
    target = os.path.join(REELS_DIR, clean_name)
    deleted = False
    if os.path.exists(target):
        try:
            os.remove(target)
            deleted = True
        except Exception as e:
            print(f"Error deleting reel file {target}: {e}")
    
    # Remove from history
    history = load_reels_history()
    new_history = [r for r in history if r.get("filename") != clean_name]
    if len(new_history) != len(history):
        save_reels_history(new_history)
        deleted = True
        
    return deleted
