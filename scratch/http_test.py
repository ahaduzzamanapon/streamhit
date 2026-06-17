import urllib.request

try:
    with urllib.request.urlopen("https://streamhit.lc-synergy.ltd/", timeout=5) as response:
        print("Home status:", response.status)
except Exception as e:
    print("Home error:", e)

try:
    with urllib.request.urlopen("https://streamhit.lc-synergy.ltd/watch?id=2987820995479752632&path=king-the-land-23DiL8Cfly3", timeout=5) as response:
        print("Watch status:", response.status)
except Exception as e:
    print("Watch error:", e)
