import httpx
import json

def run_query(query: str):
    url = "https://streamhit.lc-synergy.ltd/api/check-db"
    params = {
        "secret": "streamhit_secret_update_2026",
        "query": query
    }
    resp = httpx.get(url, params=params, timeout=15.0)
    if resp.status_code == 200:
        return resp.json()
    else:
        raise Exception(f"HTTP {resp.status_code}: {resp.text}")

def main():
    # 1. Fetch 5 movies from live database
    print("Fetching 5 movies from live database...")
    try:
        res = run_query("SELECT subject_id, title, detail_path FROM subjects WHERE subject_type = 1 LIMIT 5")
        movies = res.get("rows", [])
        for m in movies:
            print(f"ID: {m['subject_id']}, Title: {m['title']}, Path: {m['detail_path']}")
    except Exception as e:
        print("Error fetching movies:", e)
        return
        
    if not movies:
        print("No movies found in database.")
        return
        
    # 2. Try fetching resources for each movie to see if they save
    for m in movies[:3]:
        subject_id = m['subject_id']
        path = m['detail_path'] or ""
        url = f"https://streamhit.lc-synergy.ltd/api/resource?subjectId={subject_id}&detailPath={path}"
        print(f"\nQuerying resources for: {m['title']} (URL: {url})")
        try:
            resp = httpx.get(url, timeout=15.0)
            print("  Status:", resp.status_code)
            print("  Response JSON:", json.dumps(resp.json(), indent=2))
        except Exception as e:
            print("  Failed:", e)
            
    # 3. Check count of play_resources again
    print("\nChecking play_resources count again...")
    try:
        count_res = run_query("SELECT COUNT(*) as cnt FROM play_resources")
        print(json.dumps(count_res, indent=2))
    except Exception as e:
        print("Error getting count:", e)

if __name__ == "__main__":
    main()
