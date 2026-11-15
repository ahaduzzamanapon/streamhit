import requests

u1 = "https://raw.githubusercontent.com/azamstv00-cpu/Public_Iptv_Channels/main/merge.py"
r1 = requests.get(u1)
print("=== azamstv00-cpu/Public_Iptv_Channels/main/merge.py ===")
print(r1.text[:2000])
print("="*60)

u2 = "https://raw.githubusercontent.com/sportlive18/All-Playlist/main/script/merge.py"
r2 = requests.get(u2)
print("=== sportlive18/All-Playlist/main/script/merge.py ===")
print(r2.text[:2000])

