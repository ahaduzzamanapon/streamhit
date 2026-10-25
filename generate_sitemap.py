import asyncio
import main

async def build_sitemap():
    pool = await main.get_db_pool()
    async with pool.acquire() as conn:
        async with conn.cursor() as cur:
            await cur.execute('''
                SELECT title, detail_path, subject_type, rating, updated_at 
                FROM subjects 
                WHERE detail_path IS NOT NULL AND detail_path != '' 
                ORDER BY rating DESC, updated_at DESC
            ''')
            rows = await cur.fetchall()
            print(f"Total subjects with detail_path: {len(rows)}")
            
            domain = "https://nxly.online"
            
            xml = ['<?xml version="1.0" encoding="UTF-8"?>']
            xml.append('<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">')
            
            # Static pages
            static_pages = [
                ("", "1.0", "daily"),
                ("/movies", "0.9", "daily"),
                ("/tv", "0.9", "daily"),
                ("/live-tv", "0.8", "daily"),
                ("/download", "0.8", "weekly"),
                ("/app", "0.8", "weekly"),
            ]
            
            for p, prio, freq in static_pages:
                xml.append("  <url>")
                xml.append(f"    <loc>{domain}{p}</loc>")
                xml.append(f"    <changefreq>{freq}</changefreq>")
                xml.append(f"    <priority>{prio}</priority>")
                xml.append("  </url>")
                
            genres = ["Action", "Comedy", "Horror", "Animation", "Thriller", "Romance", "Sci-Fi", "Drama"]
            for g in genres:
                xml.append("  <url>")
                xml.append(f"    <loc>{domain}/movies?genre={g}</loc>")
                xml.append("    <changefreq>weekly</changefreq>")
                xml.append("    <priority>0.7</priority>")
                xml.append("  </url>")
            
            count = 0
            for r in rows:
                title, detail_path, s_type, rating, updated_at = r
                slug = detail_path.strip()
                if not slug:
                    continue
                count += 1
                type_seg = "movie" if s_type == 1 else "tv"
                
                # Detail page
                xml.append("  <url>")
                xml.append(f"    <loc>{domain}/movie/{slug}</loc>")
                xml.append("    <changefreq>weekly</changefreq>")
                xml.append("    <priority>0.8</priority>")
                xml.append("  </url>")
                
                # Watch page
                xml.append("  <url>")
                xml.append(f"    <loc>{domain}/watch/{type_seg}/{slug}</loc>")
                xml.append("    <changefreq>weekly</changefreq>")
                xml.append("    <priority>0.7</priority>")
                xml.append("  </url>")
                
            xml.append("</urlset>")
            
            with open("public/sitemap.xml", "w", encoding="utf-8") as f:
                f.write("\n".join(xml))
                
            total_urls = len(static_pages) + len(genres) + (count * 2)
            print(f"Generated public/sitemap.xml with {total_urls} indexed URLs!")

if __name__ == "__main__":
    asyncio.run(build_sitemap())
