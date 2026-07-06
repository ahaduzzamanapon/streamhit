import httpx

workers = [
    "https://frosty-tree-ae87.vidnest-1.workers.dev",
    "https://summer-hat-3d00.vidnest-2.workers.dev",
    "https://misty-salad-49cf.vidnest-3.workers.dev",
    "https://dry-darkness-c431.vudnest-4.workers.dev",
]

for w in workers:
    print(f"\nTesting worker: {w}")
    try:
        resp = httpx.get(w, timeout=5.0)
        print(f"  Root status: {resp.status_code}")
    except Exception as e:
        print(f"  Root error: {e}")
        
    try:
        resp2 = httpx.get(f"{w}/mp4-proxy?url=https%3A//google.com", timeout=5.0)
        print(f"  /mp4-proxy status: {resp2.status_code}")
    except Exception as e:
        print(f"  /mp4-proxy error: {e}")
