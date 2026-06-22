import urllib.request
import json

endpoints = [
    "http://localhost:3005/api/notifications/latest",
    "http://localhost:3005/api/app/version"
]

for url in endpoints:
    print(f"Testing {url}...")
    try:
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req) as response:
            body = response.read().decode('utf-8')
            print(f"Response: {body}\n")
    except Exception as e:
        print(f"Error requesting {url}: {e}\n")
