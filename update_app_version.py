import os
import sys
import asyncio

async def update_version(must_update_flag: int = 0):
    try:
        from main import get_db_pool
        pool = await get_db_pool()
        if pool:
            async with pool.acquire() as conn:
                async with conn.cursor() as cur:
                    notes = (
                        "⚡ ফিক্স ও আপডেট: মুভি ও টিভি সিরিজ সার্ভার সংযোগ ফিক্স করা হয়েছে এবং নতুন লাইভ স্পোর্টস ইভেন্ট (Cricket & Football) যুক্ত রয়েছে!\n\n"
                        "🎬 মুভি ও সিরিজ এখন ফুল স্পিডে বাফারিং ছাড়া চলবে।\n"
                        "🏏 লাইভ ক্রিকেট এবং ⚽ ফুটবল ম্যাচ সরাসরি উপভোগ করুন ফুল এইচডি কোয়ালিটিতে।\n"
                        "🎁 নতুন রেফারাল প্রোগ্রাম: বন্ধুদের ইনভাইট করে ৩ দিন ফ্রি VIP নো-অ্যাড পাস পান।"
                    )
                    await cur.execute("""
                        INSERT INTO app_versions (version_code, version_name, apk_url, must_update, release_notes)
                        VALUES (36, '1.0.36', 'https://nxly.online/uploads/apks/NXLY-Stream.apk', %s, %s)
                        ON DUPLICATE KEY UPDATE
                            version_name = VALUES(version_name),
                            apk_url = VALUES(apk_url),
                            must_update = VALUES(must_update),
                            release_notes = VALUES(release_notes)
                    """, (must_update_flag, notes))
                    print(f"Updated database app_versions table to v1.0.36 (code=36, must_update={must_update_flag})")
        else:
            print("Database pool not available for version update")
    except Exception as e:
        print("Version update notice:", e)

if __name__ == "__main__":
    force = 1 if ("--force" in sys.argv or "1" in sys.argv) else 0
    asyncio.run(update_version(force))
