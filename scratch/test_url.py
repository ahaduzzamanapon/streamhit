import urllib.request
import urllib.error

url = "https://streamhit.lc-synergy.ltd/"
print(f"Testing URL: {url}")
try:
    with urllib.request.urlopen(url, timeout=5) as response:
        print(f"Status Code: {response.status}")
        print("Success! The page loads.")
except urllib.error.HTTPError as e:
    print(f"HTTP Error: {e.code} - {e.reason}")
except Exception as e:
    print(f"Error: {e}")

url_watch = "https://streamhit.lc-synergy.ltd/watch?id=2987820995479752632&path=king-the-land-23DiL8Cfly3"
print(f"\nTesting URL watch page: {url_watch}")
try:
    with urllib.request.urlopen(url_watch, timeout=5) as response:
        print(f"Status Code: {response.status}")
        print("Success! Watch page loads successfully now!")
except urllib.error.HTTPError as e:
    print(f"HTTP Error: {e.code} - {e.reason}")
except Exception as e:
    print(f"Error: {e}")
