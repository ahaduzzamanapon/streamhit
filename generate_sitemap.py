"""
Sitemap Generator Utility
Exports Google-compliant Sitemap Index architecture to public/ folder
"""

import os
import asyncio
import seo_engine

async def build_sitemap():
    base_dir = os.path.dirname(os.path.abspath(__file__))
    public_dir = os.path.join(base_dir, "public")
    sitemaps_dir = os.path.join(public_dir, "sitemaps")
    os.makedirs(sitemaps_dir, exist_ok=True)

    domain = "https://nxly.online"
    print(f"[Sitemap Generator] Generating Multi-Sitemap Index for {domain}...")

    # 1. Master index
    master_index = await seo_engine.build_master_sitemap_index("nxly.online")
    with open(os.path.join(public_dir, "sitemap.xml"), "w", encoding="utf-8") as f:
        f.write(master_index)
    print(" -> Generated public/sitemap.xml (Master Sitemap Index)")

    # 2. Static sitemap
    static_sm = await seo_engine.build_static_sitemap("nxly.online")
    with open(os.path.join(sitemaps_dir, "sitemap-static.xml"), "w", encoding="utf-8") as f:
        f.write(static_sm)
    print(" -> Generated public/sitemaps/sitemap-static.xml")

    # 3. Movies sitemaps
    total_subjects = await seo_engine.get_total_subjects_count()
    movie_pages = max(1, (total_subjects + 999) // 1000)
    for p in range(1, movie_pages + 1):
        mov_sm = await seo_engine.build_movies_sitemap(p, "nxly.online")
        with open(os.path.join(sitemaps_dir, f"sitemap-movies-{p}.xml"), "w", encoding="utf-8") as f:
            f.write(mov_sm)
        print(f" -> Generated public/sitemaps/sitemap-movies-{p}.xml")

    # 4. SEO guides sitemaps
    seo_pages = max(1, (total_subjects + 249) // 250)
    for p in range(1, seo_pages + 1):
        seo_sm = await seo_engine.build_seo_guides_sitemap(p, "nxly.online")
        with open(os.path.join(sitemaps_dir, f"sitemap-seo-guides-{p}.xml"), "w", encoding="utf-8") as f:
            f.write(seo_sm)
        print(f" -> Generated public/sitemaps/sitemap-seo-guides-{p}.xml")

    print(f"[Sitemap Generator] Complete! Indexed {total_subjects} movies across {1 + movie_pages + seo_pages} sub-sitemaps.")

if __name__ == "__main__":
    asyncio.run(build_sitemap())
