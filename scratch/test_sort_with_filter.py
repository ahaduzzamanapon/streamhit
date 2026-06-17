import httpx
import urllib.parse
import re
import sys

sys.stdout.reconfigure(encoding='utf-8')

url = 'https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/filter'

def is_educational_content(title) -> bool:
    if not title:
        return False
    title_lower = title.lower()
    bangla_keywords = ['\u0eb6\u0bb0\u0eb6\u0ba3\u0eb6\u0bbf', 'শ্রেণি', 'শ্রেণী', 'অধ্যায়', 'অধ্যায়', 'পাঠ্য', 'শিক্ষা', 'গণিত', 'বিজ্ঞান', 'ব্যাকরণ']
    for kw in bangla_keywords:
        if kw in title_lower:
            return True
    if re.search(r'\bclass\s*(\d+|one|two|three|four|five|six|seven|eight|nine|ten)\b', title_lower):
        return True
    edu_keywords = ['english 1st paper', 'english 2nd paper', 'ssc 20', 'hsc 20', 'jsc 20', 'nctb']
    for kw in edu_keywords:
        if kw in title_lower:
            return True
    return False

sort_options = ['Latest', 'Hottest', 'Rating', 'ForYou']
for sort_opt in sort_options:
    payload = {
        'page': 1,
        'perPage': 40,
        'genre': '',
        'country': 'Bangladesh',
        'year': '',
        'language': '',
        'sort': 'Rating' if sort_opt == 'Hottest' else sort_opt,
        'subjectType': 0
    }
    proxied_url = f'http://194.127.178.223/?url={urllib.parse.quote(url)}'
    try:
        resp = httpx.post(proxied_url, json=payload, timeout=15.0)
        data = resp.json()
        items = data.get('data', {}).get('items', [])
        if not items and data.get('data', {}).get('results'):
            items = data.get('data', {}).get('results', [{}])[0].get('subjects', [])
        
        filtered = [item for item in items if not is_educational_content(item.get('title'))]
        print(f'\nSort: {sort_opt} | Total returned: {len(items)} | After filter: {len(filtered)}')
        for idx, item in enumerate(filtered[:15]):
            print(f"  {idx+1}. Rating: {item.get('imdbRatingValue')} | Year: {item.get('releaseDate')} | Title: {item.get('title')}")
    except Exception as e:
        print(f'Error for sort {sort_opt}: {e}')
