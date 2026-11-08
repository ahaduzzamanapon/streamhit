import os
import sys
import asyncio

async def update_version(must_update_flag: int = 1):
    try:
        from main import get_db_pool
        pool = await get_db_pool()
        if pool:
            async with pool.acquire() as conn:
                async with conn.cursor() as cur:
                    await cur.execute("""
                        INSERT INTO app_versions (version_code, version_name, apk_url, must_update, release_notes)
                        VALUES (29, '1.0.28', 'https://nxly.online/uploads/apks/NXLY-Stream.apk', %s, '🙏 বারবার আপডেটের জন্য আমরা আন্তরিকভাবে দুঃখিত! লাইভ টিভি স্মুথ করা, বাফারিং দূর করা, টিভি রিমোট কন্ট্রোল এবং ব্যাকগ্রাউন্ড ডাউনলোডের বড় ফিচারের জন্য এই জরুরি আপডেটটি দিতে হয়েছে। দয়া করে একবার আপডেট করে নিন।')
                        ON DUPLICATE KEY UPDATE
                            version_name = VALUES(version_name),
                            apk_url = VALUES(apk_url),
                            must_update = VALUES(must_update),
                            release_notes = VALUES(release_notes)
                    """, (must_update_flag,))
                    print(f"Updated database app_versions table to v1.0.28 (code=29, must_update={must_update_flag})")
        else:
            print("Database pool not available for version update")
    except Exception as e:
        print("Version update notice:", e)

if __name__ == "__main__":
    force = 0 if ("--optional" in sys.argv or "0" in sys.argv) else 1
    asyncio.run(update_version(force))
