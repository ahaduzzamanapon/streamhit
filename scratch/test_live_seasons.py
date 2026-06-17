import httpx
import json

def main():
    url = "https://streamhit.lc-synergy.ltd/api/season-info?subjectId=2036860087691609120&detailPath=game-of-thrones-AQP5Q48Qsq2"
    print("Querying live season info API:", url)
    try:
        resp = httpx.get(url, timeout=15.0)
        print("Status code:", resp.status_code)
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
