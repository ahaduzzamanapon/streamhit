import requests

u = 'https://raw.githubusercontent.com/srhady/willow-event/refs/heads/main/live_sports.m3u'
r = requests.get(u)
print(r.text)

