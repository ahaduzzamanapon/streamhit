import httpx
import urllib.parse
import json
import time

def test_proxy_post():
    proxy_url = "http://194.127.178.223/"
    target_url = "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/search"
    
    payload = {
        "keyword": "Wednesday",
        "page": 1,
        "perPage": 10,
        "subjectType": 2
    }
    
    # We need cookies for this to work typically, but we just want to see if the proxy forwards the POST request
    full_url = f"{proxy_url}?url={urllib.parse.quote(target_url)}"
    
    print(f"Testing proxy POST: {full_url}")
    try:
        start = time.time()
        # In main.py, it uses client.post(proxied_url, json=body_dict, headers=headers)
        # Note: headers in main.py includes X-Forwarded-For etc.
        resp = httpx.post(full_url, json=payload, timeout=10.0)
        end = time.time()
        print(f"Status Code: {resp.status_code}")
        print(f"Time taken: {end - start:.2f}s")
        print(f"Response: {resp.text[:500]}")
    except Exception as e:
        print(f"Proxy POST test failed: {e}")

if __name__ == "__main__":
    test_proxy_post()
