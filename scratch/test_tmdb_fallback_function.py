import asyncio
import httpx
import urllib.parse
import json

TMDB_API_KEY = "52c48694824d7f57b4179fc097ec03d3"
TMDB_BASE_URL = "https://api.themoviedb.org/3"

async def resolve_details_via_tmdb(subject_id: str, path_to_use: str, db_row: dict = None) -> dict:
    if not path_to_use or "subjectId" in path_to_use or "details" in path_to_use:
        return None
        
    path_clean = path_to_use.split("?")[0]
    parts = path_clean.split("-")
    if len(parts) > 1:
        last_part = parts[-1]
        # Check if last part is alphanumeric hash of length >= 8
        if len(last_part) >= 8 and any(c.isupper() for c in last_part) and any(c.islower() for c in last_part):
            title_parts = parts[:-1]
        else:
            title_parts = parts
        title_str = " ".join(title_parts).strip()
    else:
        title_str = path_to_use
        
    title_str = title_str.replace("_", " ").title()
    if not title_str:
        return None
        
    is_tv = True
    if db_row:
        is_tv = (db_row.get("subject_type") == 2)
    else:
        is_tv = any(k in path_to_use.lower() for k in ["season", "series", "episode", "got", "thrones"])
        
    print(f"[TMDB Details Fallback] Searching TMDB for '{title_str}' (is_tv={is_tv})")
    
    search_type = "tv" if is_tv else "movie"
    url = f"{TMDB_BASE_URL}/search/{search_type}?api_key={TMDB_API_KEY}&query={urllib.parse.quote(title_str)}"
    try:
        async with httpx.AsyncClient(trust_env=False, timeout=5.0) as client:
            resp = await client.get(url)
            if resp.status_code == 200:
                results = resp.json().get("results", [])
                if not results and not db_row:
                    search_type = "movie" if is_tv else "tv"
                    url2 = f"{TMDB_BASE_URL}/search/{search_type}?api_key={TMDB_API_KEY}&query={urllib.parse.quote(title_str)}"
                    resp2 = await client.get(url2)
                    if resp2.status_code == 200:
                        results = resp2.json().get("results", [])
                        if results:
                            is_tv = not is_tv
                            
                if results:
                    match = results[0]
                    tmdb_id = match.get("id")
                    
                    details_path = f"/tv/{tmdb_id}" if is_tv else f"/movie/{tmdb_id}"
                    details_url = f"{TMDB_BASE_URL}{details_path}?api_key={TMDB_API_KEY}"
                    resp_details = await client.get(details_url)
                    if resp_details.status_code == 200:
                        td = resp_details.json()
                        
                        title = td.get("name") or td.get("title")
                        release_date = td.get("first_air_date") or td.get("release_date") or ""
                        rating = td.get("vote_average", 7.5)
                        genres = [g.get("name") for g in td.get("genres", [])]
                        genres_str = ",".join(genres)
                        description = td.get("overview") or "No description available."
                        
                        poster_path = td.get("poster_path")
                        cover_url = f"https://image.tmdb.org/t/p/w500{poster_path}" if poster_path else ""
                        
                        countries = td.get("production_countries", [])
                        country = countries[0].get("name") if countries else (td.get("origin_country", ["USA"])[0] if td.get("origin_country") else "USA")
                        
                        episode_run_time = td.get("episode_run_time", [])
                        duration = episode_run_time[0] if (is_tv and episode_run_time) else td.get("runtime", 0)
                        duration_str = f"{duration} min" if duration else "-- min"
                        
                        return {
                            "subjectId": str(subject_id),
                            "title": title,
                            "subjectType": 2 if is_tv else 1,
                            "cover": {"url": cover_url},
                            "imdbRatingValue": str(rating),
                            "releaseDate": release_date,
                            "countryName": country,
                            "genre": genres,
                            "description": description,
                            "isCam": False,
                            "duration": duration_str,
                            "dubs": []
                        }
    except Exception as e:
        print(f"[TMDB Details Fallback Error] {e}")
    return None

async def main():
    result = await resolve_details_via_tmdb("2036860087691609120", "game-of-thrones-AQP5Q48Qsq2")
    print("\nResolution Result:")
    print(json.dumps(result, indent=2))

if __name__ == "__main__":
    asyncio.run(main())
