import os
import re
import json
import httpx
import asyncio
import random

API_BASE = "https://h5-api.aoneroom.com"

GENRES = [
    "ALL", "Action", "Adventure", "Animation", "Comedy", "Crime", 
    "Documentary", "Drama", "Family", "Fantasy", "History", "Horror", 
    "Music", "Mystery", "Romance", "Sci-Fi", "Thriller", "War"
]

DEFAULT_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36",
    "Referer": "https://moviebox.ph/",
    "Origin": "https://moviebox.ph",
    "Accept": "application/json",
}

SINGAPORE_IP_RANGES = [
    ((1, 21, 224, 0), (1, 21, 255, 255)),
    ((1, 32, 128, 0), (1, 32, 191, 255)),
    ((101, 100, 160, 0), (101, 100, 255, 255)),
    ((101, 127, 0, 0), (101, 127, 255, 255)),
    ((101, 32, 104, 0), (101, 32, 175, 255)),
    ((103, 1, 136, 0), (103, 1, 139, 255)),
    ((103, 10, 100, 0), (103, 10, 103, 255)),
    ((103, 11, 188, 0), (103, 11, 191, 255)),
    ((103, 14, 212, 0), (103, 14, 215, 255)),
    ((103, 15, 100, 0), (103, 15, 103, 255)),
]

def get_random_singapore_ip():
    start_ip, end_ip = random.choice(SINGAPORE_IP_RANGES)
    start_long = (start_ip[0] << 24) | (start_ip[1] << 16) | (start_ip[2] << 8) | start_ip[3]
    end_long = (end_ip[0] << 24) | (end_ip[1] << 16) | (end_ip[2] << 8) | end_ip[3]
    random_long = random.randint(start_long, end_long)
    return f"{(random_long >> 24) & 255}.{(random_long >> 16) & 255}.{(random_long >> 8) & 255}.{random_long & 255}"

async def get_bearer_token(client):
    ip = get_random_singapore_ip()
    headers = {
        **DEFAULT_HEADERS,
        "X-Forwarded-For": ip,
        "CF-Connecting-IP": ip,
        "X-Real-IP": ip,
        "Accept-Language": "en-US,en;q=0.9",
    }
    try:
        resp = await client.get(f"{API_BASE}/wefeed-h5api-bff/home?host=moviebox.ph", headers=headers)
        x_user = resp.headers.get("x-user")
        if x_user:
            return json.loads(x_user).get("token") or ""
    except Exception as e:
        print(f"Error getting token: {e}")
    return ""

async def make_filter_request(client, token, sem, tab_id, filter_value, year, page, filter_type="genre"):
    async with sem:
        ip = get_random_singapore_ip()
        headers = {
            **DEFAULT_HEADERS,
            "Authorization": f"Bearer {token}" if token else "",
            "X-Forwarded-For": ip,
            "CF-Connecting-IP": ip,
            "X-Real-IP": ip,
        }
        url = f"{API_BASE}/wefeed-h5api-bff/subject/filter"
        if filter_type == "country":
            filter_obj = {"sort": "RECOMMEND", "genre": "ALL", "country": filter_value, "year": str(year), "language": "ALL"}
        else:
            filter_obj = {"sort": "RECOMMEND", "genre": filter_value.upper(), "country": "ALL", "year": str(year), "language": "ALL"}
        payload = {
            "tabId": tab_id,
            "filter": filter_obj,
            "page": page,
            "perPage": 24
        }
        for attempt in range(2):
            try:
                resp = await client.post(url, headers=headers, json=payload, timeout=10.0)
                if resp.status_code == 200:
                    data = resp.json()
                    if data.get("code") == 0:
                        return data.get("data", {}).get("items", []) or []
            except Exception:
                pass
            await asyncio.sleep(0.1)
        return []

def write_sitemap_file(filepath, urls):
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'
    ]
    for u in urls:
        lines.append("  <url>")
        lines.append(f"    <loc>{u['loc']}</loc>")
        lines.append(f"    <changefreq>{u['changefreq']}</changefreq>")
        lines.append(f"    <priority>{u['priority']}</priority>")
        lines.append("  </url>")
    lines.append("</urlset>")
    with open(filepath, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

async def main():
    print("Starting Sitemap Generation...")
    base_url = "https://streamfit.ehealthfinder.com"
    public_dir = "public"
    os.makedirs(public_dir, exist_ok=True)
    
    sem = asyncio.Semaphore(15)
    
    async with httpx.AsyncClient(timeout=15.0) as client:
        token = await get_bearer_token(client)
        if not token:
            print("Failed to get bearer token. Exiting.")
            return
            
        # Build task list: genre x year x page (up to 20 pages) + country x year x page
        COUNTRIES = ["ALL", "United States", "United Kingdom", "India", "China", "Japan", "South Korea", "France", "Germany", "Spain", "Italy", "Canada", "Australia", "Mexico", "Brazil"]
        
        tasks = []
        # Genre x Year x Page (movies)
        for genre in GENRES:
            for year in range(1970, 2027):
                for page in range(1, 21):  # up to 20 pages per combo
                    tasks.append((1, genre, year, page))
        # Country x Year x Page (movies)
        for country in COUNTRIES:
            for year in range(1970, 2027):
                for page in range(1, 11):
                    tasks.append((1, country, year, page, "country"))
        # Genre x Year x Page (TV)
        for genre in GENRES:
            for year in range(1970, 2027):
                for page in range(1, 21):
                    tasks.append((2, genre, year, page))
        # Country x Year x Page (TV)
        for country in COUNTRIES:
            for year in range(1970, 2027):
                for page in range(1, 11):
                    tasks.append((2, country, year, page, "country"))
                    
        print(f"Total scraping tasks created: {len(tasks)}")
        
        movie_slugs = set()
        tv_slugs = set()
        
        batch_size = 200
        for i in range(0, len(tasks), batch_size):
            batch = tasks[i:i+batch_size]
            print(f"Running batch {i // batch_size + 1} / {(len(tasks) + batch_size - 1) // batch_size}...")
            
            async_tasks = []
            for t in batch:
                tab_id, filter_value, year, page = t[0], t[1], t[2], t[3]
                filter_type = t[4] if len(t) > 4 else "genre"
                async_tasks.append(make_filter_request(client, token, sem, tab_id, filter_value, year, page, filter_type))
                
            results = await asyncio.gather(*async_tasks)
            
            for idx, items in enumerate(results):
                tab_id = batch[idx][0]
                for item in items:
                    slug = item.get("detailPath")
                    if slug:
                        if tab_id == 1:
                            movie_slugs.add(slug)
                        else:
                            tv_slugs.add(slug)
            
            print(f"Progress - Movies found: {len(movie_slugs)}, TV Series found: {len(tv_slugs)}")
            await asyncio.sleep(0.02)
            
        print(f"Extraction complete! Unique movies: {len(movie_slugs)}, Unique TV Series: {len(tv_slugs)}")
        
        # Generate Movies Sitemaps
        movie_slugs = sorted(list(movie_slugs))
        movie_sitemaps = []
        chunk_size = 1000
        for idx in range(0, len(movie_slugs), chunk_size):
            chunk = movie_slugs[idx : idx + chunk_size]
            urls = []
            for slug in chunk:
                urls.append({"loc": f"{base_url}/movie/{slug}", "changefreq": "weekly", "priority": "0.8"})
                urls.append({"loc": f"{base_url}/watch/movie/{slug}", "changefreq": "weekly", "priority": "0.7"})
            file_num = len(movie_sitemaps) + 1
            filename = f"sitemap_movies_{file_num}.xml"
            write_sitemap_file(os.path.join(public_dir, filename), urls)
            movie_sitemaps.append(filename)
            
        # Generate TV Sitemaps
        tv_slugs = sorted(list(tv_slugs))
        tv_sitemaps = []
        for idx in range(0, len(tv_slugs), chunk_size):
            chunk = tv_slugs[idx : idx + chunk_size]
            urls = []
            for slug in chunk:
                urls.append({"loc": f"{base_url}/tv/{slug}", "changefreq": "weekly", "priority": "0.8"})
                urls.append({"loc": f"{base_url}/watch/tv/{slug}", "changefreq": "weekly", "priority": "0.7"})
            file_num = len(tv_sitemaps) + 1
            filename = f"sitemap_tv_{file_num}.xml"
            write_sitemap_file(os.path.join(public_dir, filename), urls)
            tv_sitemaps.append(filename)
            
        # Generate Static Sitemap
        static_urls = [
            {"loc": f"{base_url}/", "changefreq": "daily", "priority": "1.0"},
            {"loc": f"{base_url}/movies", "changefreq": "daily", "priority": "0.9"},
            {"loc": f"{base_url}/tv", "changefreq": "daily", "priority": "0.9"},
            {"loc": f"{base_url}/live-tv", "changefreq": "daily", "priority": "0.8"},
            {"loc": f"{base_url}/download", "changefreq": "weekly", "priority": "0.6"},
        ]
        write_sitemap_file(os.path.join(public_dir, "sitemap_static.xml"), static_urls)
        
        # Generate Main Sitemap Index
        index_lines = [
            '<?xml version="1.0" encoding="UTF-8"?>',
            '<sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">',
            f'  <sitemap><loc>{base_url}/sitemap_static.xml</loc></sitemap>'
        ]
        for fn in movie_sitemaps:
            index_lines.append(f'  <sitemap><loc>{base_url}/{fn}</loc></sitemap>')
        for fn in tv_sitemaps:
            index_lines.append(f'  <sitemap><loc>{base_url}/{fn}</loc></sitemap>')
        index_lines.append('</sitemapindex>')
        
        with open(os.path.join(public_dir, "sitemap.xml"), "w", encoding="utf-8") as f:
            f.write("\n".join(index_lines))
            
    print("Sitemap generation completed successfully!")

if __name__ == "__main__":
    asyncio.run(main())
