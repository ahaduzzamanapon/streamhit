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
    queries = [
        ("Count of resources", "SELECT COUNT(*) as cnt FROM play_resources"),
        ("Count of subjects", "SELECT COUNT(*) as cnt FROM subjects"),
        ("Count of seasons", "SELECT COUNT(*) as cnt FROM seasons"),
        ("Scraper progress state", "SELECT * FROM scraper_progress"),
        ("Latest resources saved", "SELECT subject_id, season, episode, resolution, expires_at FROM play_resources ORDER BY id DESC LIMIT 10")
    ]
    
    for desc, q in queries:
        print(f"\n=== {desc} ===")
        try:
            res = run_query(q)
            print(json.dumps(res, indent=2))
        except Exception as e:
            print("Error:", e)

if __name__ == "__main__":
    main()
