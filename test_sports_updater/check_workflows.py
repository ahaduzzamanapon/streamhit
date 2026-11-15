import requests
import json

repos = [
    'byte-capsule/FanCode-Hls-Fetcher',
    'sportlive18/crichd',
    'sayanpal514-hue/FANCODE-AUTO-UPDATED-PLAYLIST',
    'sayanpal514-hue/SONYLIV-AUTO-UPDATED-PLAYLIST',
    'drmlive/fancode-live-events',
    'azamstv00-cpu/Public_Iptv_Channels',
    'sportlive18/All-Playlist'
]

headers = {'User-Agent': 'Mozilla/5.0'}

for r in repos:
    api_url = f'https://api.github.com/repos/{r}/contents/.github/workflows'
    resp = requests.get(api_url, headers=headers)
    if resp.status_code == 200:
        files = [f['name'] for f in resp.json()]
        print(f"=== {r} workflows: {files} ===")
        for fname in files:
            raw_url = f"https://raw.githubusercontent.com/{r}/main/.github/workflows/{fname}"
            wf_resp = requests.get(raw_url, headers=headers)
            if wf_resp.status_code == 200:
                print(f"--- File: {fname} (length: {len(wf_resp.text)}) ---")
                lines = wf_resp.text.splitlines()
                # Check for inline python or run scripts
                for i, l in enumerate(lines):
                    if any(k in l.lower() for k in ['python', 'script', 'requests', 'curl', 'token', 'http']):
                        print(f"  L{i+1}: {l.strip()[:100]}")
    else:
        print(f"=== {r} (status {resp.status_code}) ===")

