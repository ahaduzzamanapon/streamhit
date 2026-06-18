import urllib.request
import urllib.parse
import json

SECRET = "streamhit_secret_update_2026"
BASE_URL = "https://streamhit.lc-synergy.ltd"

def run_query(query, label=""):
    params = urllib.parse.urlencode({"secret": SECRET, "query": query})
    url = f"{BASE_URL}/api/check-db?{params}"
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=30) as resp:
            result = json.loads(resp.read().decode('utf-8'))
            print(f"[{label}] {result}")
            return result
    except Exception as e:
        print(f"[{label}] ERROR: {e}")
        return None

# Also delete Gameplay and Volleyball
extra = ["Gameplay", "Volleyball","Mobile Game","PC Game"]
for genre in extra:
    q = f"DELETE FROM subjects WHERE genre LIKE '%{genre}%'"
    run_query(q, f"DELETE {genre}")

print("\n=== Final count ===")
run_query("SELECT COUNT(*) as cnt FROM subjects", "subjects remaining")
run_query("SELECT genre, COUNT(*) as cnt FROM subjects GROUP BY genre ORDER BY cnt DESC LIMIT 30", "genre breakdown")
