import urllib.request
import time

url = "https://streamhit.lc-synergy.ltd/"
print(f"Testing simple HTTP request to: {url}")
try:
    start = time.time()
    with urllib.request.urlopen(url, timeout=10) as response:
        duration = time.time() - start
        print(f"Success! Status: {response.status} (took {duration:.2f}s)")
        print("Headers:")
        for k, v in response.getheaders():
            print(f"  {k}: {v}")
except Exception as e:
    print("Error:", e)
