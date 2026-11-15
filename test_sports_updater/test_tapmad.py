import requests
import json

headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
    'Origin': 'https://www.tapmad.com',
    'Referer': 'https://www.tapmad.com/',
}

url = 'https://backend-api.tapmad.com/api/getStreamEncrypt'

for method in ['GET', 'POST']:
    for payload in [
        {'contentId': 19115},
        {'ContentEntityId': 19115},
        {'id': 19115},
        {'channelId': 19115},
        {'entityId': 19115, 'entityType': 'CHANNEL'}
    ]:
        try:
            if method == 'GET':
                r = requests.get(url, params=payload, headers=headers, timeout=5)
            else:
                r = requests.post(url, json=payload, headers=headers, timeout=5)
            print(f"{method} {payload} -> {r.status_code}: {r.text[:200]}")
            if r.status_code == 200 and 'stream' in r.text.lower():
                break
        except Exception as e:
            print(e)
