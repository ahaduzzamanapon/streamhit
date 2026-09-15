import httpx

resp = httpx.get('https://raw.githubusercontent.com/solo12345689/moviebox-internal-api/main/moviebox_api_server.py')
with open('scratch/server.py', 'w', encoding='utf-8') as f:
    f.write(resp.text)
print("Saved scratch/server.py, total lines:", len(resp.text.splitlines()))
