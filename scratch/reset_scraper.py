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
    print("Resetting scraper progress to page 2...")
    try:
        res = run_query("UPDATE scraper_progress SET current_page = 2")
        print("Update result:")
        print(json.dumps(res, indent=2))
        
        # Verify the new state
        print("\nVerifying scraper progress state:")
        verify_res = run_query("SELECT * FROM scraper_progress")
        print(json.dumps(verify_res, indent=2))
    except Exception as e:
        print("Error:", e)

if __name__ == "__main__":
    main()
