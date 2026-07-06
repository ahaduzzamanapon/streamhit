import httpx

def test_live_proxy():
    url = "https://streamfit.ehealthfinder.com/fetch?source_url=https%3A//bcdnxw.hakunaymatata.com/resource/3801dac8e6ad876f5ea1b000ac107218.mp4%3Fsign%3Dd9473c616abdef66418c3de3d84f27ea%26t%3D1783334264"
    print("Requesting live fetch proxy:", url)
    
    try:
        # Request only headers first using HEAD or streaming GET
        with httpx.Client(trust_env=False, timeout=15.0) as client:
            resp = client.get(url, headers={"Range": "bytes=0-100"})
            print("Status Code:", resp.status_code)
            print("Headers:")
            for k, v in resp.headers.items():
                print(f"  {k}: {v}")
            print("Content Snippet Length:", len(resp.content))
    except Exception as e:
        print("Error:", e)

if __name__ == "__main__":
    test_live_proxy()
