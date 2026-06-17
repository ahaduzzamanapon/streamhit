import httpx

def main():
    url = "https://streamhit.lc-synergy.ltd/api/read-log"
    print("Fetching live log from", url)
    try:
        resp = httpx.get(url, timeout=10.0)
        if resp.status_code == 200:
            data = resp.json()
            if data.get("status") == "success":
                output_file = "scratch/live_stderr.log"
                with open(output_file, "w", encoding="utf-8") as f:
                    f.write(data.get("log"))
                print("Successfully wrote live log to", output_file)
            else:
                print("Error from API:", data.get("message"))
        else:
            print("HTTP Error:", resp.status_code)
    except Exception as e:
        print("Fetch failed:", e)

if __name__ == "__main__":
    main()
