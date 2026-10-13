import os
import asyncio

async def update_version():
    try:
        from main import get_db_pool
        pool = await get_db_pool()
        if pool:
            async with pool.acquire() as conn:
                async with conn.cursor() as cur:
                    await cur.execute("""
                        INSERT INTO app_versions (id, version_code, version_name, apk_url, must_update, release_notes)
                        VALUES (1, 9, '1.0.8', 'https://nxly.online/uploads/apks/NXLY-Stream.apk', 0, 'Compact modern navigation, press-and-hold 2X speed, fixed landscape gestures, series next episode button, recent search history, and in-app download engine.')
                        ON DUPLICATE KEY UPDATE
                            version_code = VALUES(version_code),
                            version_name = VALUES(version_name),
                            apk_url = VALUES(apk_url),
                            must_update = VALUES(must_update),
                            release_notes = VALUES(release_notes)
                    """)
                    print("Updated database app_versions table to v1.0.8")
        else:
            print("Database pool not available for version update")
    except Exception as e:
        print("Version update notice:", e)

if __name__ == "__main__":
    asyncio.run(update_version())
