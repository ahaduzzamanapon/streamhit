import asyncio
import httpx
import urllib.parse
import json

async def test_proxy_stream():
    source_url = "https://bcdnxw.hakunaymatata.com/resource/3801dac8e6ad876f5ea1b000ac107218.mp4?sign=d9473c616abdef66418c3de3d84f27ea&t=1783334264"
    
    headers_to_send = {
        "Origin": "https://fmoviesunblocked.net",
        "Referer": "https://fmoviesunblocked.net/",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept": "*/*",
        "Range": "bytes=0-100"
    }
    
    print("Testing streaming proxy connection...")
    async with httpx.AsyncClient(trust_env=False, timeout=15.0) as client:
        req = client.build_request("GET", source_url, headers=headers_to_send)
        resp = await client.send(req, stream=True)
        
        print("Status Code:", resp.status_code)
        print("Response Headers:")
        for k, v in resp.headers.items():
            print(f"  {k}: {v}")
            
        print("Reading first chunk...")
        chunks = []
        async for chunk in resp.aiter_bytes(chunk_size=100):
            chunks.append(chunk)
            print("Successfully read a chunk of size:", len(chunk))
            break
            
        await resp.aclose()

if __name__ == "__main__":
    asyncio.run(test_proxy_stream())
