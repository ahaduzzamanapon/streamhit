import requests

candidates = [
    ('sayanpal514-hue', 'FANCODE-AUTO-UPDATED-PLAYLIST', ['main.yml', 'fancode.yml', 'update.yml', 'auto.yml', 'cron.yml']),
    ('sayanpal514-hue', 'SONYLIV-AUTO-UPDATED-PLAYLIST', ['main.yml', 'sony.yml', 'sonyliv.yml', 'update.yml', 'auto.yml']),
    ('drmlive', 'fancode-live-events', ['main.yml', 'update.yml', 'fancode.yml', 'cron.yml', 'run.yml']),
    ('azamstv00-cpu', 'Public_Iptv_Channels', ['main.yml', 'update.yml', 'playlist.yml', 'cron.yml', 'channels.yml']),
    ('sportlive18', 'All-Playlist', ['main.yml', 'update.yml', 'playlist.yml', 'cron.yml']),
    ('sportlive18', 'Sonyliv-Playlist-Autoupdate', ['main.yml', 'update.yml', 'sony.yml']),
    ('sportlive18', 'Fancode-New-Auto-Update', ['main.yml', 'update.yml', 'fancode.yml'])
]

headers = {'User-Agent': 'Mozilla/5.0'}

for owner, repo, files in candidates:
    for f in files:
        url = f"https://raw.githubusercontent.com/{owner}/{repo}/main/.github/workflows/{f}"
        r = requests.get(url, headers=headers, timeout=5)
        if r.status_code == 200:
            print(f"FOUND: {owner}/{repo}/.github/workflows/{f} ({len(r.text)} bytes)")
            # Print entire content if under 3000 bytes
            print(r.text)
            print("="*60)

