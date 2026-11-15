import requests

u = 'https://raw.githubusercontent.com/drmlive/sliv-live-events/refs/heads/main/sonyliv.m3u'
r = requests.get(u)
print(r.text)

