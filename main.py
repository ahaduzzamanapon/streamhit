import os
import re
import json
import httpx
import urllib.parse
import asyncio
import random
from fastapi import FastAPI, Request, HTTPException, Query
from fastapi.responses import HTMLResponse, StreamingResponse, JSONResponse, RedirectResponse
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from contextlib import asynccontextmanager

# ==========================================================================
# CONFIGURATION
# ==========================================================================
base_dir = os.path.dirname(os.path.abspath(__file__))
API_BASE = "https://h5-api.aoneroom.com"

WORKER_PROXIES = [
    "https://frosty-tree-ae87.vidnest-1.workers.dev",
    "https://summer-hat-3d00.vidnest-2.workers.dev",
    "https://misty-salad-49cf.vidnest-3.workers.dev",
    "https://dry-darkness-c431.vudnest-4.workers.dev",
]

DEFAULT_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36",
    "Referer": "https://moviebox.ph/",
    "Origin": "https://moviebox.ph",
    "Accept": "application/json",
}

PLAYER_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36",
    "Accept": "application/json",
}

worker_index = 0
def get_next_worker():
    global worker_index
    w = WORKER_PROXIES[worker_index]
    worker_index = (worker_index + 1) % len(WORKER_PROXIES)
    return w

_bearer_token = None
http_client = httpx.AsyncClient(timeout=25.0)

SINGAPORE_IP_RANGES = [
    ((1, 21, 224, 0), (1, 21, 255, 255)),
    ((1, 32, 128, 0), (1, 32, 191, 255)),
    ((101, 100, 160, 0), (101, 100, 255, 255)),
    ((101, 127, 0, 0), (101, 127, 255, 255)),
    ((101, 32, 104, 0), (101, 32, 175, 255)),
    ((103, 1, 136, 0), (103, 1, 139, 255)),
    ((103, 10, 100, 0), (103, 10, 103, 255)),
    ((103, 11, 188, 0), (103, 11, 191, 255)),
    ((103, 14, 212, 0), (103, 14, 215, 255)),
    ((103, 15, 100, 0), (103, 15, 103, 255)),
]

def get_random_singapore_ip():
    start_ip, end_ip = random.choice(SINGAPORE_IP_RANGES)
    start_long = (start_ip[0] << 24) | (start_ip[1] << 16) | (start_ip[2] << 8) | start_ip[3]
    end_long = (end_ip[0] << 24) | (end_ip[1] << 16) | (end_ip[2] << 8) | end_ip[3]
    random_long = random.randint(start_long, end_long)
    return f"{(random_long >> 24) & 255}.{(random_long >> 16) & 255}.{(random_long >> 8) & 255}.{random_long & 255}"

_h5_cookie = None

async def _get_h5_cookie() -> str:
    global _h5_cookie
    if _h5_cookie:
        return _h5_cookie
    ip = get_random_singapore_ip()
    headers = {
        "X-Forwarded-For": ip,
        "CF-Connecting-IP": ip,
        "X-Real-IP": ip,
        "Accept-Language": "en-US,en;q=0.5",
        "Accept": "application/json",
        "User-Agent": "okhttp/4.12.0",
        "Referer": "https://h5.aoneroom.com"
    }
    cookie_url = "https://h5.aoneroom.com/wefeed-h5-bff/app/get-latest-app-pkgs?app_name=moviebox"
    try:
        async with httpx.AsyncClient(trust_env=False, timeout=15.0) as client:
            resp = await client.get(cookie_url, headers=headers)
            cookie_headers = resp.headers.get_list("Set-Cookie")
            parsed = []
            for cookie in cookie_headers:
                parsed.append(cookie.split(";")[0])
            _h5_cookie = "; ".join(parsed)
    except Exception as e:
        print(f"Error getting H5 cookie: {e}")
    return _h5_cookie or ""

async def _fetch_download_resources(subject_id: str, se: int = 1, ep: int = 1, detail_path: str = "") -> dict:
    cookie = await _get_h5_cookie()
    for attempt in range(3):
        ip = get_random_singapore_ip()
        headers = {
            "X-Forwarded-For": ip,
            "CF-Connecting-IP": ip,
            "X-Real-IP": ip,
            "Accept-Language": "en-US,en;q=0.5",
            "Accept": "application/json",
            "User-Agent": "okhttp/4.12.0",
            "Origin": "https://123movienow.cc",
            "Referer": f"https://123movienow.cc/spa/videoPlayPage/movies/{detail_path}?id={subject_id}&type=/movie/detail" if detail_path else f"https://123movienow.cc/spa/videoPlayPage/movies/details?id={subject_id}&type=/movie/detail",
            "Cookie": cookie
        }
        url = f"https://h5.aoneroom.com/wefeed-h5-bff/web/subject/download?subjectId={subject_id}&se={se}&ep={ep}"
        try:
            async with httpx.AsyncClient(trust_env=False, timeout=15.0) as client:
                resp = await client.get(url, headers=headers)
                if resp.status_code == 200:
                    data = resp.json().get("data")
                    if data and (data.get("downloads") or data.get("captions")):
                        return data
        except Exception as e:
            print(f"Error fetching VOD resources (attempt {attempt+1}): {e}")
        await asyncio.sleep(0.5)
    return {}

async def _get_bearer_token() -> str:
    global _bearer_token
    if _bearer_token: return _bearer_token
    try:
        resp = await http_client.get(f"{API_BASE}/wefeed-h5api-bff/home?host=moviebox.ph", headers=DEFAULT_HEADERS)
        x_user = resp.headers.get("x-user")
        if x_user: _bearer_token = json.loads(x_user).get("token")
    except: pass
    return _bearer_token or ""

async def _make_request(url: str, method: str = "GET", payload: dict = None, custom_headers: dict = None) -> dict:
    global _bearer_token
    token = await _get_bearer_token()
    headers = {**DEFAULT_HEADERS, "Authorization": f"Bearer {token}" if token else "", **(custom_headers or {})}
    try:
        if method == "POST": resp = await http_client.post(url, headers=headers, json=payload)
        else: resp = await http_client.get(url, headers=headers)
        x_user = resp.headers.get("x-user")
        if x_user:
            new_token = json.loads(x_user).get("token")
            if new_token: _bearer_token = new_token
        return resp.json()
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"Request failed: {str(e)}")

@asynccontextmanager
async def lifespan(app: FastAPI):
    yield
    await http_client.aclose()

app = FastAPI(lifespan=lifespan)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_credentials=True, allow_methods=["*"], allow_headers=["*"])

@app.middleware("http")
async def add_no_cache_headers(request: Request, call_next):
    response = await call_next(request)
    if request.url.path.startswith("/api/") or request.url.path.startswith("/fetch"):
        response.headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
        response.headers["Pragma"] = "no-cache"
        response.headers["Expires"] = "0"
    return response

async def get_subject_meta(slug: str):
    meta = {
        "title": "Streamfit - Watch Free Movies & TV Shows",
        "description": "Streamfit offers the best free high-quality streaming.",
        "cover": "https://images.unsplash.com/photo-1594909122845-11baa439b7bf?w=1200&q=80",
        "url": f"https://streamfit.ehealthfinder.com/movie/{slug}",
        "schema": ""
    }
    if not slug: return meta
    url = f"{API_BASE}/wefeed-h5api-bff/detail?detailPath={slug}"
    try:
        data = await _make_request(url)
        inner = data.get("data", {}).get("subject", {})
        if inner:
            meta["title"] = f"Watch {inner.get('title', 'Movie')} - Streamfit"
            meta["description"] = inner.get("description", meta["description"]).replace('"', '\\"')
            meta["cover"] = inner.get("cover", {}).get("url", meta["cover"])
            schema_obj = {
                "@context": "https://schema.org",
                "@type": "VideoObject" if inner.get("subjectType") == 1 else "TVSeries",
                "name": inner.get("title", ""),
                "description": inner.get("description", ""),
                "image": meta["cover"],
                "url": meta["url"],
            }
            if inner.get("subjectType") == 1:
                schema_obj["thumbnailUrl"] = [meta["cover"]]
                schema_obj["uploadDate"] = inner.get("releaseDate", "2026-01-01")
                schema_obj["duration"] = "PT120M"
            
            meta["schema"] = f'<script type="application/ld+json">{json.dumps(schema_obj)}</script>'
    except: pass
    return meta

def serve_html(filename: str, meta_replacements=None):
    path = os.path.join(base_dir, filename)
    if not os.path.exists(path): return HTMLResponse("Not Found", status_code=404)
    with open(path, "r", encoding="utf-8") as f: html = f.read()
    if meta_replacements:
        for k, v in meta_replacements.items(): html = html.replace(k, v)
    return HTMLResponse(content=html)

@app.get("/", response_class=HTMLResponse)
async def index(): return serve_html("public/index.html")

@app.get("/movies", response_class=HTMLResponse)
async def movies(): return serve_html("public/movies.html", {"<title>Explore Movies - Streamfit</title>": "<title>Explore Movies - Streamfit</title>"})

@app.get("/tv", response_class=HTMLResponse)
async def tv(): return serve_html("public/tv.html", {"<title>Explore TV Series - Streamfit</title>": "<title>Explore TV Series - Streamfit</title>"})

@app.get("/live-tv", response_class=HTMLResponse)
async def livetv(): return serve_html("public/live-tv.html")

@app.get("/movie/{slug}", response_class=HTMLResponse)
@app.get("/tv/{slug}", response_class=HTMLResponse)
async def details(slug: str):
    meta = await get_subject_meta(slug)
    reps = {
        '<title>Details - Streamfit</title>': f'<title>{meta["title"]}</title>',
        'id="detailsTitle">Title': f'id="detailsTitle">{meta["title"]}',
        'id="watchDescription">Description loading...': f'id="watchDescription">{meta["description"]}',
        'src="/default-cover.png"': f'src="{meta["cover"]}"',
        'content="Watch free movies, TV shows, anime and live sports online."': f'content="{meta["description"]}"',
        'content="Streamfit - Free Movies, TV Shows & Anime Streaming"': f'content="{meta["title"]}"',
        'content="https://streamfit.ehealthfinder.com/"': f'content="{meta["url"]}"',
        'content="https://images.unsplash.com/photo-1594909122845-11baa439b7bf?w=1200&q=80"': f'content="{meta["cover"]}"',
        'content="website"': 'content="video.movie"',
        '<!-- SCHEMA_PLACEHOLDER -->': meta.get("schema", "")
    }
    return serve_html("public/details.html", reps)

@app.get("/watch/movie/{slug}", response_class=HTMLResponse)
@app.get("/watch/tv/{slug}", response_class=HTMLResponse)
async def watch(slug: str):
    meta = await get_subject_meta(slug)
    reps = {
        '<title>Watch Online - Streamfit</title>': f'<title>Watching {meta["title"]}</title>',
        'content="Watch free movies, TV shows, anime and live sports online."': f'content="{meta["description"]}"',
        'content="Streamfit - Free Movies, TV Shows & Anime Streaming"': f'content="{meta["title"]}"',
        'content="https://streamfit.ehealthfinder.com/"': f'content="{meta["url"]}"',
        'content="https://images.unsplash.com/photo-1594909122845-11baa439b7bf?w=1200&q=80"': f'content="{meta["cover"]}"',
        'content="website"': 'content="video.movie"',
        '<!-- SCHEMA_PLACEHOLDER -->': meta.get("schema", "")
    }
    return serve_html("public/watch.html", reps)

# backward compatibility redirects
@app.get("/details")
async def old_details(id: str = None, path: str = None):
    if path: return RedirectResponse(url=f"/movie/{path}", status_code=301)
    return RedirectResponse(url="/", status_code=301)

@app.get("/watch")
async def old_watch(request: Request, id: str = None, path: str = None, type: str = None):
    if type in ["sports", "tv"]:
        return serve_html("public/watch.html")
    if path: return RedirectResponse(url=f"/watch/movie/{path}", status_code=301)
    return RedirectResponse(url="/", status_code=301)

# API
@app.get("/api/home")
async def get_home(page: int = 1, tabId: int = 0):
    url = f"{API_BASE}/wefeed-h5api-bff/tab-operating?page={page}&tabId={tabId}"
    data = await _make_request(url)
    if "data" in data and "operatingList" in data["data"]: data["data"]["items"] = data["data"]["operatingList"]
    return data

@app.get("/api/banners")
async def get_banners():
    url = f"{API_BASE}/wefeed-h5api-bff/home?host=moviebox.ph"
    data = await _make_request(url)
    items = []
    for op in data.get("data", {}).get("operatingList", []):
        if op.get("type") == "BANNER": items.extend(op.get("banner", {}).get("items", []))
    formatted = []
    for item in items:
        sub = item.get("subject") or {}
        formatted.append({
            "subject_id": sub.get("subjectId"),
            "title": item.get("title") or sub.get("title"),
            "image_url": item.get("image", {}).get("url") or sub.get("cover", {}).get("url"),
            "detail_path": item.get("detailPath") or sub.get("detailPath"),
            "subject_type": sub.get("subjectType", 1)
        })
    return {"code": 0, "data": {"list": formatted}}

@app.post("/api/filter")
async def api_filter(request: Request):
    payload = await request.json()
    tabId = payload.get("tabId", 1)
    filter_data = payload.get("filter", {"sort": "RECOMMEND", "genre": "ALL", "country": "ALL", "year": "ALL", "language": "ALL"})
    page = payload.get("page", 1)
    perPage = payload.get("perPage", 24)
    url = f"{API_BASE}/wefeed-h5api-bff/subject/filter"
    data = await _make_request(url, method="POST", payload={"tabId": tabId, "filter": filter_data, "page": page, "perPage": perPage})
    if "data" in data and "subjects" in data["data"]: data["data"]["items"] = data["data"]["subjects"]
    return data

@app.post("/api/search")
async def api_search(request: Request):
    payload = await request.json()
    keyword = payload.get("keyword", "")
    page = payload.get("page", 1)
    perPage = payload.get("perPage", 24)
    url = f"{API_BASE}/wefeed-h5api-bff/subject/search"
    data = await _make_request(url, method="POST", payload={"keyword": keyword, "page": page, "perPage": perPage})
    if "data" in data and "list" in data["data"]: data["data"]["items"] = data["data"]["list"]
    return data

@app.post("/api/heartbeat")
async def api_heartbeat():
    return {"code": 0, "message": "success"}

@app.get("/api/notifications/latest")
async def api_notifications():
    return {"code": 0, "data": []}

@app.get("/api/sports/live")
async def api_sports():
    return {"code": 0, "data": []}

@app.get("/api/search/suggest")
async def search_suggest(q: str = ""):
    url = f"{API_BASE}/wefeed-h5api-bff/subject/search-suggest"
    data = await _make_request(url, method="POST", payload={"keyword": q, "perPage": 10})
    if "data" in data and "items" in data["data"]:
        raw_items = data["data"]["items"]
        items = []
        for d in raw_items:
            sub = d.get("subject") or {}
            if sub:
                d["title"] = sub.get("title", "")
                d["subjectType"] = sub.get("subjectType", 1)
                d["cover"] = sub.get("cover", {})
                d["rating"] = sub.get("imdbRatingValue", "")
                d["detailPath"] = sub.get("detailPath", "")
                d["releaseDate"] = sub.get("releaseDate", "")
                d["isKeyword"] = False
            else:
                d["title"] = d.get("word", "")
                d["subjectType"] = 0
                d["cover"] = {}
                d["rating"] = ""
                d["detailPath"] = ""
                d["releaseDate"] = ""
                d["isKeyword"] = True
            if d["title"]:
                items.append(d)
        data["data"]["items"] = items
    return data

@app.get("/api/detail")
async def api_detail(detailPath: str = ""):
    url = f"{API_BASE}/wefeed-h5api-bff/detail?detailPath={detailPath}"
    data = await _make_request(url)
    if "data" in data and "subject" in data["data"]:
        subj = data["data"]["subject"]
        if "resource" in data["data"]:
            subj["seasons"] = data["data"]["resource"].get("seasons", [])
            subj["seNum"] = len(subj["seasons"])
        data["data"] = subj
    return data

@app.get("/api/season-info")
async def api_season(detailPath: str = ""):
    url = f"{API_BASE}/wefeed-h5api-bff/detail?detailPath={detailPath}"
    data = await _make_request(url)
    seasons = []
    if "data" in data and "resource" in data["data"]:
        seasons = data["data"]["resource"].get("seasons", [])
    return {"code": 0, "data": {"seasons": seasons}}

@app.get("/api/resource")
async def api_resource(se: int = 1, ep: int = 1, detailPath: str = "", subjectId: str = ""):
    if not subjectId and detailPath:
        try:
            detail_data = await _make_request(f"{API_BASE}/wefeed-h5api-bff/detail?detailPath={detailPath}")
            subjectId = detail_data.get("data", {}).get("subject", {}).get("subjectId", "")
        except:
            pass
            
    if not subjectId:
        return {"code": 0, "data": {"list": []}}
        
    data = await _fetch_download_resources(subjectId, se, ep, detailPath)
    downloads = data.get("downloads") or []
    
    items = []
    for d in downloads:
        url_val = d.get("url")
        if url_val:
            items.append({
                "resourceId": d.get("id"),
                "resolution": d.get("resolution", 720),
                "size": d.get("size", 0),
                "resourceLink": f"/fetch?source_url={urllib.parse.quote(url_val)}"
            })
    return {"code": 0, "data": {"list": items}}
 
@app.get("/api/captions")
async def api_captions(se: int = 1, ep: int = 1, detailPath: str = "", subjectId: str = ""):
    if not subjectId and detailPath:
        try:
            detail_data = await _make_request(f"{API_BASE}/wefeed-h5api-bff/detail?detailPath={detailPath}")
            subjectId = detail_data.get("data", {}).get("subject", {}).get("subjectId", "")
        except:
            pass
            
    if not subjectId:
        return {"code": 0, "data": {"list": []}}
        
    data = await _fetch_download_resources(subjectId, se, ep, detailPath)
    captions = data.get("captions") or []
    
    formatted = []
    for c in captions:
        formatted.append({
            "label": c.get("lanName") or c.get("lan", "English"),
            "lang": c.get("lan", "en"),
            "url": f"/api/proxy-subtitle?url={urllib.parse.quote(c.get('url', ''))}"
        })
    return {"code": 0, "data": {"list": formatted}}

@app.get("/fetch")
async def handle_fetch(request: Request, source_url: str):
    if not source_url:
        raise HTTPException(status_code=400, detail="Missing source_url")
        
    qp = dict(request.query_params)
    qp.pop("source_url", None)
    if qp:
        source_url += "&" + urllib.parse.urlencode(qp)
        
    headers_to_send = {
        "Origin": "https://fmoviesunblocked.net",
        "Referer": "https://fmoviesunblocked.net/",
        "User-Agent": DEFAULT_HEADERS["User-Agent"],
        "Accept": "*/*"
    }
    
    range_header = request.headers.get("Range")
    if range_header:
        headers_to_send["Range"] = range_header
        
    client = httpx.AsyncClient(trust_env=False, timeout=30.0)
    try:
        resp = await client.send(client.build_request("GET", source_url, headers=headers_to_send), stream=True)
        status_code = resp.status_code
        response_headers = {
            "Content-Type": resp.headers.get("Content-Type", "video/mp4"),
            "Access-Control-Allow-Origin": "*",
        }
        if resp.headers.get("Content-Range"):
            response_headers["Content-Range"] = resp.headers.get("Content-Range")
        if resp.headers.get("Content-Length"):
            response_headers["Content-Length"] = resp.headers.get("Content-Length")
        if resp.headers.get("Accept-Ranges"):
            response_headers["Accept-Ranges"] = resp.headers.get("Accept-Ranges")
            
        real_status = status_code if status_code in [200, 206] else 200
        
        async def stream_generator():
            try:
                async for chunk in resp.aiter_bytes(chunk_size=1024 * 64):
                    yield chunk
            finally:
                await resp.aclose()
                await client.aclose()
                
        return StreamingResponse(
            stream_generator(),
            status_code=real_status,
            headers=response_headers
        )
    except Exception as e:
        await client.aclose()
        raise HTTPException(status_code=502, detail=f"Failed to connect to video CDN: {e}")

@app.get("/api/proxy-subtitle")
async def proxy_subtitle(url: str):
    if not url: return "Missing URL"
    try:
        resp = await http_client.get(url, headers={"Referer": "https://netfilm.world/"})
        return StreamingResponse(resp.iter_bytes(), media_type="text/vtt", headers={"Access-Control-Allow-Origin": "*"})
    except: return ""

app.mount("/", StaticFiles(directory=os.path.join(base_dir, "public"), html=True), name="public")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)
