import asyncio
import re
import time
from main import _make_request, API_BASE, get_latest_content, request_moviebox_mobile, _slug_to_id

_movies_pool_cache = []
_movies_pool_cache_time = 0

_tv_pool_cache = []
_tv_pool_cache_time = 0

async def get_movies_pool():
    global _movies_pool_cache, _movies_pool_cache_time
    now = time.time()
    if _movies_pool_cache and (now - _movies_pool_cache_time < 900):
        return _movies_pool_cache

    seen = set()
    pool = []

    # 1. Fresh latest movies from MovieBox Mobile (2026, 2025, daily recs)
    try:
        latest_movies = await get_latest_content(subject_type=1)
        for m in latest_movies:
            sid = str(m.get("subjectId") or "")
            if sid and sid not in seen:
                seen.add(sid)
                pool.append(m)
    except Exception as e:
        print(f"Error fetching latest movies for pool: {e}")

    # 2. Operating list from official Movie tab (tabId=2)
    try:
        url = f"{API_BASE}/wefeed-h5api-bff/tab-operating?page=1&tabId=2"
        d = await _make_request(url)
        op_list = d.get("data", {}).get("operatingList", [])
        for op in op_list:
            for s in op.get("subjects", []):
                sid = str(s.get("subjectId") or "")
                st = s.get("subjectType", 1)
                if st == 1 and sid and sid not in seen:
                    seen.add(sid)
                    title = s.get("title") or ""
                    sid_str = str(sid)
                    dpath = s.get("detailPath") or ""
                    clean_slug = re.sub(r'[^a-zA-Z0-9]+', '-', title.lower()).strip('-')
                    if not dpath:
                        dpath = f"{clean_slug}-{sid_str}" if sid_str else clean_slug
                    s["detailPath"] = dpath
                    _slug_to_id[dpath] = sid_str
                    _slug_to_id[sid_str] = sid_str
                    if s.get("detailUrl"):
                        raw_slug = s["detailUrl"].rstrip("/").split("/")[-1]
                        _slug_to_id[raw_slug] = sid_str
                    pool.append(s)
    except Exception as e:
        print(f"Error fetching tabId=2 operating movies: {e}")

    _movies_pool_cache = pool
    _movies_pool_cache_time = now
    return pool

async def get_tv_pool():
    global _tv_pool_cache, _tv_pool_cache_time
    now = time.time()
    if _tv_pool_cache and (now - _tv_pool_cache_time < 900):
        return _tv_pool_cache

    seen = set()
    pool = []

    # 1. Fresh latest TV series from MovieBox Mobile
    try:
        latest_tv = await get_latest_content(subject_type=2)
        for m in latest_tv:
            sid = str(m.get("subjectId") or "")
            if sid and sid not in seen:
                seen.add(sid)
                pool.append(m)
    except Exception as e:
        print(f"Error fetching latest TV for pool: {e}")

    # 2. Operating list from Home tab (tabId=0)
    try:
        url = f"{API_BASE}/wefeed-h5api-bff/tab-operating?page=1&tabId=0"
        d = await _make_request(url)
        op_list = d.get("data", {}).get("operatingList", [])
        for op in op_list:
            for s in op.get("subjects", []):
                sid = str(s.get("subjectId") or "")
                st = s.get("subjectType", 2)
                if st == 2 and sid and sid not in seen:
                    seen.add(sid)
                    title = s.get("title") or ""
                    sid_str = str(sid)
                    dpath = s.get("detailPath") or ""
                    clean_slug = re.sub(r'[^a-zA-Z0-9]+', '-', title.lower()).strip('-')
                    if not dpath:
                        dpath = f"{clean_slug}-{sid_str}" if sid_str else clean_slug
                    s["detailPath"] = dpath
                    _slug_to_id[dpath] = sid_str
                    _slug_to_id[sid_str] = sid_str
                    if s.get("detailUrl"):
                        raw_slug = s["detailUrl"].rstrip("/").split("/")[-1]
                        _slug_to_id[raw_slug] = sid_str
                    pool.append(s)
    except Exception as e:
        print(f"Error fetching tabId=0 operating TV: {e}")

    _tv_pool_cache = pool
    _tv_pool_cache_time = now
    return pool

async def run_filter(payload):
    subject_type = int(payload.get("subjectType") or (2 if payload.get("tabId") == 2 else 1))
    page = int(payload.get("page", 1))
    per_page = int(payload.get("perPage", 24))

    genre = payload.get("genre", "ALL")
    country = payload.get("country", "ALL")
    year = payload.get("year", "ALL")
    sort = payload.get("sort", "RECOMMEND")

    if genre in ("*", "all"): genre = "ALL"
    if country in ("*", "all"): country = "ALL"
    if year in ("*", "all"): year = "ALL"

    pool = await (get_movies_pool() if subject_type == 1 else get_tv_pool())
    filtered = list(pool)

    # 1. Filter by genre
    if genre != "ALL":
        g_lower = genre.lower()
        filtered = [
            m for m in filtered
            if g_lower in (
                ", ".join(m.get("genre", [])) if isinstance(m.get("genre"), list) else str(m.get("genre") or "")
            ).lower()
        ]

    # 2. Filter by country
    if country != "ALL":
        c_lower = country.lower()
        filtered = [
            m for m in filtered
            if c_lower in str(m.get("countryName") or m.get("country") or "").lower()
        ]

    # 3. Filter by year
    if year != "ALL":
        y_str = str(year)
        filtered = [
            m for m in filtered
            if y_str in str(m.get("releaseDate") or m.get("year") or "")
        ]

    # If filter results are too few and a specific filter was requested, query Mobile search
    if len(filtered) < per_page and (genre != "ALL" or year != "ALL" or country != "ALL"):
        query = genre if genre != "ALL" else (year if year != "ALL" else country)
        try:
            mob_res = await request_moviebox_mobile(
                "/wefeed-mobile-bff/subject-api/search",
                method="POST",
                data={"keyword": query, "q": query, "page": page, "pageSize": per_page, "type": subject_type}
            )
            raw_items = mob_res.get("data", {}).get("items") or []
            existing_ids = {str(m.get("subjectId")) for m in filtered}
            for it in raw_items:
                sid = str(it.get("subjectId") or "")
                if sid and sid not in existing_ids and it.get("subjectType", subject_type) == subject_type:
                    existing_ids.add(sid)
                    title = it.get("title") or ""
                    sid_str = str(sid)
                    dpath = it.get("detailPath") or ""
                    clean_slug = re.sub(r'[^a-zA-Z0-9]+', '-', title.lower()).strip('-')
                    if not dpath:
                        dpath = f"{clean_slug}-{sid_str}" if sid_str else clean_slug
                    it["detailPath"] = dpath
                    _slug_to_id[dpath] = sid_str
                    _slug_to_id[sid_str] = sid_str
                    filtered.append(it)
        except Exception as e:
            print(f"Error querying mobile search for filter: {e}")

    # Sort
    sort_upper = sort.upper()
    if sort_upper in ("RATING", "TOP RATED"):
        def parse_rating(item):
            try:
                return float(item.get("imdbRatingValue") or 0)
            except:
                return 0.0
        filtered.sort(key=parse_rating, reverse=True)
    elif sort_upper in ("NEWEST", "LATEST", "HOTTEST"):
        def parse_date(item):
            return str(item.get("releaseDate") or item.get("year") or "")
        filtered.sort(key=parse_date, reverse=True)

    start = (page - 1) * per_page
    end = start + per_page
    items = filtered[start:end]
    has_more = len(filtered) > end

    return {
        "code": 0,
        "message": "ok",
        "data": {
            "items": items,
            "pager": {
                "hasMore": has_more,
                "page": page,
                "perPage": per_page,
                "totalCount": len(filtered)
            }
        }
    }

async def main():
    # Test 1: Movies general
    res1 = await run_filter({"subjectType": 1, "page": 1, "perPage": 6})
    items1 = res1["data"]["items"]
    print(f"=== Movies Page 1 (Count: {len(items1)}, Total: {res1['data']['pager']['totalCount']}) ===")
    for m in items1[:4]:
        t = str(m.get("title") or "").encode("ascii", "ignore").decode()
        print("  ", m.get("subjectId"), "|", t, "|", m.get("genre"), "|", m.get("imdbRatingValue"))

    # Test 2: Movies Action
    res2 = await run_filter({"subjectType": 1, "genre": "Action", "page": 1, "perPage": 6})
    items2 = res2["data"]["items"]
    print(f"\n=== Movies Action (Count: {len(items2)}, Total: {res2['data']['pager']['totalCount']}) ===")
    for m in items2[:4]:
        t = str(m.get("title") or "").encode("ascii", "ignore").decode()
        print("  ", m.get("subjectId"), "|", t, "|", m.get("genre"), "|", m.get("imdbRatingValue"))

    # Test 3: Movies Rating Sort
    res3 = await run_filter({"subjectType": 1, "sort": "Rating", "page": 1, "perPage": 6})
    items3 = res3["data"]["items"]
    print(f"\n=== Movies Sorted by Rating (Count: {len(items3)}) ===")
    for m in items3[:4]:
        t = str(m.get("title") or "").encode("ascii", "ignore").decode()
        print("  ", m.get("subjectId"), "|", t, "| Rating:", m.get("imdbRatingValue"))

asyncio.run(main())
