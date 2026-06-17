import httpx
import re

def main():
    try:
        resp = httpx.get("https://moviebox.ph/", headers={
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }, timeout=10.0)
        print("Status code:", resp.status_code)
        if resp.status_code == 200:
            html = resp.text
            match = re.search(r'baseUrl\s*:\s*"([^"]+)"', html)
            if match:
                print("Found baseUrl:", match.group(1))
            match2 = re.search(r'clientFetch\s*:\s*\{[^}]*baseUrl\s*:\s*"([^"]+)"', html)
            if match2:
                print("Found clientFetch baseUrl:", match2.group(1))
            
            # Print any url/base matching aoneroom or moviebox
            urls = re.findall(r'https?://[^\s"\']+', html)
            for u in urls:
                if "aoneroom" in u or "moviebox" in u:
                    print("Found relevant URL in HTML:", u)
    except Exception as e:
        print("Error:", e)

if __name__ == "__main__":
    main()
