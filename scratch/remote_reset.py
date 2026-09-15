import httpx
import json

def run_remote_query(query: str):
    url = "https://streamhit.lc-synergy.ltd/api/check-db"
    params = {
        "secret": "streamhit_secret_update_2026",
        "query": query
    }
    resp = httpx.get(url, params=params, timeout=30.0)
    if resp.status_code == 200:
        return resp.json()
    else:
        return f"Error {resp.status_code}: {resp.text}"

def main():
    print("Resetting scraper_progress on live DB...")
    # Resetting all types to page 2 (as page 1 is often handled by incremental scraper)
    res = run_remote_query("UPDATE scraper_progress SET current_page = 2")
    print("Result:", json.dumps(res, indent=2))

    print("\nVerifying updated progress...")
    verify = run_remote_query("SELECT * FROM scraper_progress")
    print("Current State:", json.dumps(verify, indent=2))

    print("\nChecking latest 5 subjects to see when they were last updated...")
    latest = run_remote_query("SELECT subject_id, title, updated_at FROM subjects ORDER BY updated_at DESC LIMIT 5")
    print("Latest Subjects:", json.dumps(latest, indent=2))

if __name__ == "__main__":
    main()
