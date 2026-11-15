import requests

url = 'https://raw.githubusercontent.com/sayanpal514-hue/FANCODE-AUTO-UPDATED-PLAYLIST/refs/heads/main/fancode.json'
r = requests.get(url)
d = r.json()
live_matches = [m for m in d.get('matches', []) if m.get('status') == 'LIVE']
print('Live matches count:', len(live_matches))
for m in live_matches:
    print('Live match:', m.get('title'), m.get('auto_streams'))
