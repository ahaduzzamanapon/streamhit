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
                    await cur.execute("""
                        INSERT INTO app_versions (version_code, version_name, apk_url, must_update, release_notes)
                        VALUES (20, '1.0.19', 'https://nxly.online/uploads/apks/NXLY-Stream.apk', %s, 'UI Luxury Upgrade, Dub-specific episodes filter, Deep Linking & Firebase Push Notifications.')
                        ON DUPLICATE KEY UPDATE
                            version_name = VALUES(version_name),
                            apk_url = VALUES(apk_url),
                            must_update = VALUES(must_update),
                            release_notes = VALUES(release_notes)
                    """, (must_update_flag,))
                    print(f"Updated database app_versions table to v1.0.19 (must_update={must_update_flag})")
        else:
            print("Database pool not available for version update")
    except Exception as e:
        print("Version update notice:", e)

if __name__ == "__main__":
    force = 1 if ("--force" in sys.argv or "--must-update" in sys.argv or "1" in sys.argv) else 0
    asyncio.run(update_version(force))
