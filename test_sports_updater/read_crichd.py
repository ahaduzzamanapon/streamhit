import requests

u = "https://raw.githubusercontent.com/sportlive18/crichd/main/create_playlist.py"
r = requests.get(u)
print(r.text)

