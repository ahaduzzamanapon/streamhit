import urllib.request
import urllib.parse
import json

SECRET = "streamhit_secret_update_2026"
BASE_URL = "https://streamhit.lc-synergy.ltd"

def query_db(query):
    params = urllib.parse.urlencode({
        "secret": SECRET,
        "query": query
    })
    url = f"{BASE_URL}/api/check-db?{params}"
    try:
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req) as response:
            return json.loads(response.read().decode('utf-8'))
    except Exception as e:
        return {"error": str(e)}

def read_log():
    url = f"{BASE_URL}/api/read-log"
    try:
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req) as response:
            return json.loads(response.read().decode('utf-8'))
    except Exception as e:
        return {"error": str(e)}

print("=== scraper_progress ===")
print(json.dumps(query_db("SELECT * FROM scraper_progress"), indent=2))

print("=== count of subjects with description ===")
print(json.dumps(query_db("SELECT COUNT(*) FROM subjects WHERE description IS NOT NULL AND description != ''"), indent=2))

print("=== live logs (last 50 lines) ===")
log_res = read_log()
if "log" in log_res:
    lines = log_res["log"].split("\n")
    for line in lines[-50:]:
        print(line)
else:
    print(log_res)
