import urllib.request
import urllib.error
import time

urls = [
    "https://streamhit.lc-synergy.ltd/",
    "https://streamhit.lc-synergy.ltd/watch?id=2987820995479752632&path=king-the-land-23DiL8Cfly3"
]

for url in urls:
    print(f"Testing URL: {url}")
    for attempt in range(1, 4):
        try:
            start_time = time.time()
            with urllib.request.urlopen(url, timeout=15) as response:
                duration = time.time() - start_time
                print(f"Attempt {attempt}: Success! Status Code: {response.status} (took {duration:.2f}s)")
                break
        except urllib.error.HTTPError as e:
            print(f"Attempt {attempt}: HTTP Error: {e.code} - {e.reason}")
            break
        except Exception as e:
            print(f"Attempt {attempt}: Error: {e}")
            if attempt < 3:
                print("Waiting 3 seconds before retry...")
                time.sleep(3)
