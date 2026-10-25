import os
import re
import json
import time
import uuid
import asyncio
import subprocess
from typing import Dict, Any, List, Optional
from PIL import Image, ImageDraw, ImageFont

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
REELS_DIR = os.path.join(BASE_DIR, "public", "reels")
HISTORY_FILE = os.path.join(REELS_DIR, "reels_history.json")

os.makedirs(REELS_DIR, exist_ok=True)

# In-memory job tracker
active_reel_jobs: Dict[str, Dict[str, Any]] = {}

def get_font(size: int, bold: bool = True):
    font_paths = [
        "C:/Windows/Fonts/arialbd.ttf" if bold else "C:/Windows/Fonts/arial.ttf",
        "C:/Windows/Fonts/segoeuib.ttf" if bold else "C:/Windows/Fonts/segoeui.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
    ]
    for p in font_paths:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                pass
    return ImageFont.load_default()

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
    Generates a crisp 1080x1920 transparent PNG with sleek glassmorphic
    top & bottom banners tailored for Reels / TikTok / Shorts.
    """
    img = Image.new("RGBA", (1080, 1920), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    clean_title = (title or "FEATURED MOVIE").upper().strip()
    if len(clean_title) > 38:
        clean_title = clean_title[:35] + "..."

    # ── TOP BANNER ──────────────────────────────────────────────────────────
    # Container box: Y: 100 to 320
    draw.rounded_rectangle([50, 90, 1030, 310], radius=28, fill=(14, 18, 28, 235), outline=(0, 198, 255, 210), width=4)

    # Calculate optimal font size for title
    font_size = 46 if len(clean_title) < 24 else (38 if len(clean_title) < 32 else 32)
    font_title = get_font(font_size, bold=True)
    t_box = draw.textbbox((0, 0), clean_title, font=font_title)
    t_width = t_box[2] - t_box[0]
    draw.text(((1080 - t_width) // 2, 130), clean_title, fill=(255, 255, 255), font=font_title)

    # Part badge
    if top_text_custom:
        part_text = top_text_custom.replace("{title}", clean_title).replace("{part_num}", str(part_num)).replace("{total_parts}", str(total_parts))
    else:
        part_text = f"🎬 PART {part_num} 🍿" if total_parts <= 1 else f"🎬 PART {part_num} / {total_parts} 🍿"
    
    font_part = get_font(34, bold=True)
    p_box = draw.textbbox((0, 0), part_text, font=font_part)
    p_width = p_box[2] - p_box[0]
    p_height = p_box[3] - p_box[1]
    
    badge_x1 = max(70, (1080 - p_width) // 2 - 28)
    badge_x2 = min(1010, badge_x1 + p_width + 56)
    draw.rounded_rectangle([badge_x1, 215, badge_x2, 280], radius=18, fill=(255, 42, 84, 245), outline=(255, 100, 120, 200), width=2)
    draw.text(((1080 - p_width) // 2, 230), part_text, fill=(255, 255, 255), font=font_part)

    # ── BOTTOM BANNER ───────────────────────────────────────────────────────
    # Container box: Y: 1610 to 1830
    draw.rounded_rectangle([50, 1610, 1030, 1830], radius=28, fill=(14, 18, 28, 235), outline=(255, 42, 84, 210), width=4)

    # Bottom line 1: Call to Action (Yellow gold / bright)
    if bottom_text_custom:
        b1_text = bottom_text_custom.replace("{title}", clean_title).replace("{part_num}", str(part_num))
        b2_text = "📲 Watch Full HD on NXLY Stream App"
    else:
        b1_text = "👇 FULL MOVIE LINK IN 1ST COMMENT 👇"
        b2_text = "📲 Watch in Full HD on NXLY Stream App"

    font_b1 = get_font(40, bold=True)
    b1_box = draw.textbbox((0, 0), b1_text, font=font_b1)
    b1_w = b1_box[2] - b1_box[0]
    draw.text(((1080 - b1_w) // 2, 1650), b1_text, fill=(255, 225, 40), font=font_b1)

    # Bottom line 2: App Branding (Electric Cyan)
    font_b2 = get_font(34, bold=True)
    b2_box = draw.textbbox((0, 0), b2_text, font=font_b2)
    b2_w = b2_box[2] - b2_box[0]
    draw.text(((1080 - b2_w) // 2, 1735), b2_text, fill=(0, 240, 255), font=font_b2)

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
    if anti_copyright:
        # Anti-copyright:
        # 1. Video tempo 1.03x (breaks visual frame hash matching)
        # 2. Contrast/saturation tweak (+4% contrast, +6% saturation)
        # 3. Audio tempo 1.03x + subtle EQ cut at 1000Hz (breaks acoustic fingerprint)
        filter_complex = (
            "[0:v]scale=360:640:force_original_aspect_ratio=increase,crop=360:640,boxblur=8:1,scale=1080:1920,eq=brightness=-0.12:contrast=1.08,setpts=PTS/1.03[bg];"
            "[0:v]scale=1040:-2:flags=fast_bilinear,eq=contrast=1.04:saturation=1.06,setpts=PTS/1.03[fg];"
            "[bg][fg]overlay=(W-w)/2:(H-h)/2[merged];"
            "[merged][1:v]overlay=0:0[vfinal];"
            "[0:a]atempo=1.03,equalizer=f=1000:t=q:w=1:g=-1[afinal]"
        )
        map_args = ["-map", "[vfinal]", "-map", "[afinal]"]
    else:
        filter_complex = (
            "[0:v]scale=360:640:force_original_aspect_ratio=increase,crop=360:640,boxblur=8:1,scale=1080:1920,eq=brightness=-0.10[bg];"
            "[0:v]scale=1040:-2:flags=fast_bilinear[fg];"
            "[bg][fg]overlay=(W-w)/2:(H-h)/2[merged];"
            "[merged][1:v]overlay=0:0[vfinal]"
        )
        map_args = ["-map", "[vfinal]", "-map", "0:a?"]

    cmd = [
        "ffmpeg", "-y", "-nostdin",
        "-ss", str(start_seconds),
        "-t", str(duration_seconds),
        "-i", stream_url,
        "-loop", "1", "-t", str(duration_seconds), "-i", overlay_png_path,
        "-filter_complex", filter_complex,
        *map_args,
        "-c:v", "libx264",
        "-preset", "ultrafast",
        "-crf", "23",
        "-c:a", "aac",
        "-b:a", "128k",
        "-t", str(duration_seconds),
        "-movflags", "+faststart",
        output_mp4_path
    ]

    print(f"[ReelEngine] Running FFmpeg for {output_mp4_path} from {start_seconds}s for {duration_seconds}s...")
    
    def _run_sync():
        return subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)

    res = await asyncio.to_thread(_run_sync)
    
    if res.returncode != 0:
        err_msg = res.stderr[-800:] if res.stderr else "Unknown error"
        print(f"[ReelEngine] FFmpeg error: {err_msg}")
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

        # Job complete
        job["status"] = "completed"
        job["progress"] = 100
        job["current_step"] = f"All {num_parts} parts generated successfully!"

        # Save to persistent history
        existing_history = load_reels_history()
        existing_history = completed_reels + existing_history
        save_reels_history(existing_history[:100]) # keep latest 100

    except Exception as e:
        print(f"[ReelEngine] Job {job_id} error: {e}")
        job["status"] = "failed"
        job["error"] = str(e)
        job["current_step"] = f"Failed: {str(e)}"

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
    active_reel_jobs[job_id] = {
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
