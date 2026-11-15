import requests

r = requests.get('https://raw.githubusercontent.com/srhady/willow-event/main/README.md')
print(r.text)
