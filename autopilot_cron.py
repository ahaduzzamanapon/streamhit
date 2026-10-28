import os
import sys
import time
import asyncio

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
if BASE_DIR not in sys.path:
    sys.path.insert(0, BASE_DIR)

import facebook_bot
import autopilot_queue

async def main():
    try:
        cfg = facebook_bot.load_fb_config()
        if not cfg.get("auto_pilot_enabled", True):
            return

        # 1-Hour Active Surveillance Scan (monitors posts up to 60m after publishing)
        try:
            await facebook_bot.cleanup_copyrighted_videos()
        except Exception:
            pass

        interval_mins = cfg.get("post_interval_minutes")
        if interval_mins is not None:
            interval_sec = max(60, int(interval_mins) * 60)
        else:
            interval_sec = int(cfg.get("post_interval_hours", 0.166) * 3600)
            if interval_sec <= 0:
                interval_sec = 600

        now = int(time.time())
        last_post = int(cfg.get("last_post_time", 0))

        # Single-instance file lock to prevent parallel executions
        try:
            import fcntl
            lock_f = open("/tmp/autopilot_cron_py.lock", "w")
            fcntl.flock(lock_f, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except Exception:
            # Another cron execution is actively in progress
            return

        q_data = autopilot_queue.load_queue_data()
        st = q_data.get("state", {})
        if st.get("is_busy"):
            # Check for stale lock (> 600 seconds)
            busy_since = st.get("busy_since") or 0
            if now - busy_since > 600:
                facebook_bot.bot_log("[CronDaemon] ⚠️ Releasing stale lock (>600s) and resetting job.")
                st["is_busy"] = False
                st["step"] = "idle"
                autopilot_queue.save_queue_data(q_data)
            else:
                return

        # Check if due for next post
        if now - last_post >= interval_sec:
            jobs_list = q_data.get("jobs", [])
            has_pending = any(it.get("status") in ("queued", "active", "processing") for it in jobs_list)
            if not has_pending:
                return

            facebook_bot.bot_log(f"[CronDaemon] ⏰ Scheduled interval reached ({interval_sec // 60}m). Auto-triggering next video in queue...")
            
            # Prefer 100% cloud rendering on GitHub Actions to maintain 0% cPanel CPU load
            try:
                import admin
                gh_ok, gh_msg = admin.dispatch_to_github_actions()
                if gh_ok:
                    facebook_bot.bot_log("[CronDaemon] 🚀 Dispatched cycle to GitHub Actions Cloud Runner (0% server load).")
                    return
                else:
                    facebook_bot.bot_log(f"[CronDaemon] Cloud dispatch note: {gh_msg}. Proceeding locally...")
            except Exception as ge:
                facebook_bot.bot_log(f"[CronDaemon] Cloud dispatch fallback: {ge}")

            res = await autopilot_queue.process_next_queue_job()
            facebook_bot.bot_log(f"[CronDaemon] Finished auto-cycle: success={res.get('success')}, title={res.get('title')}, error={res.get('error')}")
    except Exception as e:
        facebook_bot.bot_log(f"[CronDaemon] ❌ Exception in cron execution: {e}")

if __name__ == "__main__":
    asyncio.run(main())
