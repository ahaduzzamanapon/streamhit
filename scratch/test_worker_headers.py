import httpx
import urllib.parse
import json

def test():
    worker = "https://frosty-tree-ae87.vidnest-1.workers.dev"
    source_url = "https://bcdnxw.hakunaymatata.com/resource/3801dac8e6ad876f5ea1b000ac107218.mp4?sign=d9473c616abdef66418c3de3d84f27ea&t=1783334264"
    
    headers_to_send = {
        "Origin": "https://fmoviesunblocked.net",
        "Referer": "https://fmoviesunblocked.net/",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept": "*/*",
        "Range": "bytes=0-100"
    }
    
    proxy_url = f"{worker}/mp4-proxy?url={urllib.parse.quote(source_url)}&headers={urllib.parse.quote(json.dumps(headers_to_send))}"
    
    print("Requesting:", proxy_url)
    try:
        resp = httpx.get(proxy_url, headers={"Range": "bytes=0-100"}, timeout=15.0)
        print("Status Code:", resp.status_code)
        print("Headers:")
        for k, v in resp.headers.items():
            print(f"  {k}: {v}")
        print("Content Snippet:", resp.content[:200])
    except Exception as e:
        print("Error:", e)

if __name__ == "__main__":
    test()
