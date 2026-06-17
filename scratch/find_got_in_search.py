import httpx

def main():
    url = "https://streamhit.lc-synergy.ltd/api/search"
    
    found = False
    for page in range(1, 6):
        payload = {
            "keyword": "Game of Thrones",
            "page": page,
            "perPage": 20
        }
        print(f"Searching page {page}...")
        resp = httpx.post(url, json=payload, timeout=15.0)
        if resp.status_code == 200:
            data = resp.json()
            items = data.get("data", {}).get("items", [])
            for item in items:
                title = item.get("title", "")
                stype = item.get("subjectType")
                sid = item.get("subjectId")
                path = item.get("detailPath")
                
                # Check for Game of Thrones series
                if "game of thrones" in title.lower():
                    print(f"MATCH: Title: '{title}', Type: {stype}, ID: {sid}, Path: {path}")
                    if stype == 2:
                        found = True
        else:
            print("Failed page", page, resp.status_code)
            break
            
    if not found:
        print("Game of Thrones Series NOT found in first 5 pages of search.")

if __name__ == "__main__":
    main()
