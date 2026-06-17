import urllib.request
import urllib.parse
import json

SECRET = "streamhit_secret_update_2026"
BASE_URL = "https://streamhit.lc-synergy.ltd"

def read_log():
    url = f"{BASE_URL}/api/read-log?secret={SECRET}"
    try:
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=10) as response:
            return json.loads(response.read().decode('utf-8'))
    except Exception as e:
        return {"error": str(e)}

res = read_log()
if "log" in res:
    print("=== LIVE LOGS ===")
    print(res["log"])
else:
    print("Error getting log:", res)
