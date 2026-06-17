import re

with open("main.py", "r", encoding="utf-8") as f:
    lines = f.readlines()

def search_keywords(keywords):
    print(f"=== Searching for: {keywords} ===")
    for idx, line in enumerate(lines):
        if any(kw in line for kw in keywords):
            print(f"{idx+1}: {line.strip()}")

search_keywords(["startup", "on_event", "lifespan"])
