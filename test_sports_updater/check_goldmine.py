import requests

urls = {
    'FANCODE': 'https://raw.githubusercontent.com/doctor-8trange/zyphx8/refs/heads/main/data/fancode.m3u',
    'SONYLIV': 'https://raw.githubusercontent.com/drmlive/sliv-live-events/refs/heads/main/sonyliv.m3u',
    'WILLOW': 'https://raw.githubusercontent.com/srhady/willow-event/refs/heads/main/live_sports.m3u'
}

for name, u in urls.items():
    try:
        r = requests.get(u, timeout=5)
        print(f"=== {name} (Status: {r.status_code}, Length: {len(r.text)}) ===")
        if r.status_code == 200:
            for l in r.text.splitlines()[:10]:
                print(' ', l[:120])
    except Exception as e:
        print(f"=== {name} Error: {e} ===")

