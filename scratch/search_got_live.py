import httpx
import json

def main():
    url = "https://streamhit.lc-synergy.ltd/api/search"
    payload = {
        "keyword": "Game of Thrones",
        "page": 1,
        "perPage": 20,
        "genre": "*",
        "country": "*",
        "year": "*",
        "sort": "Latest"
    }
    print("Searching live API via POST:", url)
    try:
        resp = httpx.post(url, json=payload, timeout=15.0)
        print("Status:", resp.status_code)
        try:
            data = resp.json()
            print("Response JSON:")
            print(json.dumps(data, indent=2))
        except Exception as je:
            print("Failed to parse JSON:", je)
            print("Raw text:", resp.text[:1000])
    except Exception as e:
        print("Request failed:", e)

if __name__ == "__main__":
    main()
