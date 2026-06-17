import asyncio
import httpx
import urllib.parse
import json

TMDB_API_KEY = "52c48694824d7f57b4179fc097ec03d3"
TMDB_BASE_URL = "https://api.themoviedb.org/3"

async def search_tmdb(query: str, is_tv: bool = True):
    search_type = "tv" if is_tv else "movie"
    url = f"{TMDB_BASE_URL}/search/{search_type}?api_key={TMDB_API_KEY}&query={urllib.parse.quote(query)}"
    async with httpx.AsyncClient(trust_env=False, timeout=10.0) as client:
        resp = await client.get(url)
        print("Search status:", resp.status_code)
        if resp.status_code == 200:
            results = resp.json().get("results", [])
            if results:
                return results[0]
    return None

async def fetch_tmdb_details(tmdb_id: str, is_tv: bool = True):
    path = f"/tv/{tmdb_id}" if is_tv else f"/movie/{tmdb_id}"
    url = f"{TMDB_BASE_URL}{path}?api_key={TMDB_API_KEY}"
    async with httpx.AsyncClient(trust_env=False, timeout=10.0) as client:
        resp = await client.get(url)
        print("Details status:", resp.status_code)
        if resp.status_code == 200:
            return resp.json()
    return None

async def main():
    query = "Game Of Thrones"
    print(f"Searching TMDB TV for '{query}'...")
    match = await search_tmdb(query, is_tv=True)
    if match:
        print("Found Match:")
        print("  Title:", match.get("name"))
        print("  ID:", match.get("id"))
        print("  First Air Date:", match.get("first_air_date"))
        
        tmdb_id = match.get("id")
        details = await fetch_tmdb_details(tmdb_id, is_tv=True)
        if details:
            print("\nFetched Details:")
            print("  Original Name:", details.get("name"))
            print("  Overview:", details.get("overview")[:100] + "...")
            print("  Genres:", [g.get("name") for g in details.get("genres", [])])
            print("  Poster Path:", details.get("poster_path"))
            print("  Seasons Count:", len(details.get("seasons", [])))
            for s in details.get("seasons", []):
                print(f"    Season {s.get('season_number')}: {s.get('episode_count')} episodes")
    else:
        print("No match found.")

if __name__ == "__main__":
    asyncio.run(main())
