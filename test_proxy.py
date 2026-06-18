import httpx
import urllib.parse
import time

def test_proxy():
    proxy_url = "http://194.127.178.223/"
    target_url = "https://h5.aoneroom.com/wefeed-h5-bff/app/get-latest-app-pkgs?app_name=moviebox"
    
    full_url = f"{proxy_url}?url={urllib.parse.quote(target_url)}"
    
    print(f"Testing proxy: {full_url}")
    try:
        start = time.time()
        resp = httpx.get(full_url, timeout=10.0)
        end = time.time()
        print(f"Status Code: {resp.status_code}")
        print(f"Time taken: {end - start:.2f}s")
        print(f"Response snippet: {resp.text[:200]}")
    except Exception as e:
        print(f"Proxy test failed: {e}")

if __name__ == "__main__":
    test_proxy()
