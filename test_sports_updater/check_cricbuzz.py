import requests
from bs4 import BeautifulSoup

headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}
r = requests.get('https://www.cricbuzz.com/cricket-match/live-scores', headers=headers)
soup = BeautifulSoup(r.text, 'html.parser')

items = soup.select('.cb-mtchs-itm')
print(f"Total match items: {len(items)}")
for item in items:
    title = item.select_one('h3')
    state = item.select_one('.cb-text-live, .cb-text-complete, .cb-text-preview')
    if title:
        t_text = title.get_text(strip=True)
        s_text = state.get_text(strip=True) if state else ''
        print(f"Match: {t_text} | Status: {s_text}")

