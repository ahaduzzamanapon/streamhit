import urllib.request
import urllib.parse
import json
import time

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

print("Waiting 30s for server to restart after deploy...")
time.sleep(30)

print("\n=== Step 1: Delete play_resources ===")
run_query("DELETE FROM play_resources", "DELETE play_resources")
time.sleep(2)

print("\n=== Step 2: Delete captions ===")
run_query("DELETE FROM captions", "DELETE captions")
time.sleep(2)

print("\n=== Step 3: Delete seasons ===")
run_query("DELETE FROM seasons", "DELETE seasons")
time.sleep(2)

print("\n=== Step 4: Delete subjects ===")
run_query("DELETE FROM subjects", "DELETE subjects")
time.sleep(2)

print("\n=== Step 5: Reset scraper progress to page 2 ===")
run_query("DELETE FROM scraper_progress", "DELETE progress")
time.sleep(1)
run_query("INSERT INTO scraper_progress (subject_type, current_page) VALUES (1, 2), (2, 2), (7, 2)", "INSERT progress")
time.sleep(1)

print("\n=== Step 6: Delete banners ===")
run_query("DELETE FROM banners", "DELETE banners")
time.sleep(1)

print("\n=== Verification ===")
r1 = run_query("SELECT COUNT(*) as cnt FROM subjects", "subjects count")
r2 = run_query("SELECT COUNT(*) as cnt FROM play_resources", "play_resources count")
r3 = run_query("SELECT * FROM scraper_progress", "scraper progress")

print("\n=== DONE! ===")
print("DB is clean. Scraper will now re-import everything from page 2.")
print("Subjects:", r1)
print("Play Resources:", r2)
print("Scraper Progress:", r3)
