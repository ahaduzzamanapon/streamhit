with open("public/app.js", "r", encoding="utf-8") as f:
    lines = f.readlines()

def search_keywords(keywords):
    print(f"=== Searching app.js for: {keywords} ===")
    for idx, line in enumerate(lines):
        if any(kw in line for kw in keywords):
            print(f"{idx+1}: {line.strip()}")

search_keywords(["watch", "details", "scrape", "api/"])
