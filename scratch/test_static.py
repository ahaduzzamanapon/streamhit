import urllib.request

url = "https://streamhit.lc-synergy.ltd/favicon.svg"
print(f"Testing static file: {url}")
try:
    with urllib.request.urlopen(url, timeout=5) as response:
        print("Success! Status:", response.status)
except Exception as e:
    print("Error:", e)
