"""
Programmatic SEO (pSEO) Engine & Multi-Sitemap Index Architecture
for Streamhit / NXLY Stream

Features:
1. Dynamic SEO Landing Pages targeting Watch & Download intents:
   - /how-to-watch-{slug}-online
   - /how-to-download-{slug}-in-hindi
   - /watch-{slug}-full-movie-hd
   - /{slug}-download-and-watch-online
2. Anti-Thin Content Structure with dynamic specifications, how-to guides, and FAQs.
3. Schema Markup (JSON-LD) for Movie/TVSeries, FAQPage, and BreadcrumbList.
4. Google-compliant Sitemap Index Architecture:
   - /sitemap.xml (Master Sitemap Index)
   - /sitemaps/sitemap-static.xml (Static URLs)
   - /sitemaps/sitemap-movies-{page}.xml (Main movie & series URLs)
   - /sitemaps/sitemap-seo-guides-{page}.xml (Dynamic pSEO URLs)
"""

import os
import re
import json
import time
import datetime
import html
from typing import Dict, Any, List, Optional, Tuple
import aiomysql
from fastapi import APIRouter, Request, Response, HTTPException
from fastapi.responses import HTMLResponse

router = APIRouter()

# In-memory cache for sitemaps and movies to prevent DB overload
_SITEMAP_CACHE: Dict[str, Tuple[float, str]] = {}
_SITEMAP_TTL = 3600  # 1 hour cache in RAM

_MOVIE_CACHE: Dict[str, Tuple[float, Dict[str, Any]]] = {}
_MOVIE_CACHE_TTL = 900  # 15 minutes cache in RAM

# Database helper
_db_pool = None

async def get_seo_db_pool():
    global _db_pool
    if _db_pool is not None:
        return _db_pool
    try:
        from main import get_db_pool as main_get_db_pool
        _db_pool = await main_get_db_pool()
        if _db_pool:
            return _db_pool
    except Exception:
        pass

    db_host = os.getenv("DB_HOST", "127.0.0.1")
    db_port = int(os.getenv("DB_PORT", "3306"))
    db_user = os.getenv("DB_USER", "root")
    db_password = os.getenv("DB_PASSWORD", "")
    db_name = os.getenv("DB_NAME", "moviebox")
    try:
        _db_pool = await aiomysql.create_pool(
            host=db_host,
            port=db_port,
            user=db_user,
            password=db_password,
            db=db_name,
            autocommit=True,
            minsize=1,
            maxsize=5
        )
        return _db_pool
    except Exception as e:
        print(f"[SEO Engine] Database connection error: {e}")
        return None


def get_base_domain(host: str = "") -> str:
    host_lower = (host or "").lower()
    if "nxly" in host_lower:
        return "https://nxly.online"
    if "streamfit" in host_lower:
        return "https://streamfit.ehealthfinder.com"
    return "https://nxly.online"


def get_brand_info(host: str = "") -> Dict[str, str]:
    host_lower = (host or "").lower()
    if "nxly" in host_lower:
        return {
            "name": "NXLY Stream",
            "short_name": "NXLY",
            "domain": "nxly.online",
            "base_url": "https://nxly.online",
            "app_download_url": "https://nxly.online/download"
        }
    return {
        "name": "Streamfit",
        "short_name": "Streamfit",
        "domain": "streamfit.ehealthfinder.com",
        "base_url": "https://streamfit.ehealthfinder.com",
        "app_download_url": "https://streamfit.ehealthfinder.com/download"
    }


def to_iso8601(dt_val) -> str:
    """Formats datetime or timestamp to strict W3C ISO 8601 format."""
    if isinstance(dt_val, (datetime.datetime, datetime.date)):
        if isinstance(dt_val, datetime.date) and not isinstance(dt_val, datetime.datetime):
            dt_val = datetime.datetime.combine(dt_val, datetime.time(0, 0, 0))
        return dt_val.strftime("%Y-%m-%dT%H:%M:%S+00:00")
    if isinstance(dt_val, (int, float)):
        return datetime.datetime.fromtimestamp(dt_val, tz=datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S+00:00")
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S+00:00")


# ==============================================================================
# DATABASE FETCHERS
# ==============================================================================

async def fetch_movie_by_slug(slug: str) -> Optional[Dict[str, Any]]:
    """Retrieves movie data by detail_path slug from cache or MySQL."""
    if not slug:
        return None
    slug = slug.strip()

    now = time.time()
    if slug in _MOVIE_CACHE:
        cached_ts, cached_data = _MOVIE_CACHE[slug]
        if now - cached_ts < _MOVIE_CACHE_TTL:
            return cached_data

    pool = await get_seo_db_pool()
    movie = None
    if pool:
        try:
            async with pool.acquire() as conn:
                async with conn.cursor(aiomysql.DictCursor) as cur:
                    await cur.execute("""
                        SELECT subject_id, title, subject_type, cover, backdrop, rating, 
                               release_date, country, genre, description, is_cam, detail_path, 
                               tmdb_id, dubs, updated_at
                        FROM subjects 
                        WHERE detail_path = %s
                        LIMIT 1
                    """, (slug,))
                    row = await cur.fetchone()
                    if row:
                        movie = dict(row)
        except Exception as e:
            print(f"[SEO Engine] Error querying movie '{slug}': {e}")

    # Fallback to upstream API if not in database
    if not movie:
        try:
            from main import _make_request, API_BASE
            url = f"{API_BASE}/wefeed-h5api-bff/detail?detailPath={slug}"
            data = await _make_request(url)
            inner = data.get("data", {}).get("subject", {})
            if inner and isinstance(inner, dict):
                cover_val = inner.get("cover")
                cover_url = cover_val.get("url", "") if isinstance(cover_val, dict) else str(cover_val or "")
                movie = {
                    "subject_id": str(inner.get("subjectId", "")),
                    "title": inner.get("title", ""),
                    "subject_type": inner.get("subjectType", 1),
                    "cover": cover_url,
                    "backdrop": cover_url,
                    "rating": float(inner.get("imdbRatingValue") or inner.get("rating") or 7.0),
                    "release_date": inner.get("releaseDate") or "2026-01-01",
                    "country": inner.get("country") or "United States",
                    "genre": inner.get("genre") or "Action,Drama",
                    "description": inner.get("description") or "",
                    "is_cam": 0,
                    "detail_path": slug,
                    "tmdb_id": None,
                    "dubs": None,
                    "updated_at": datetime.datetime.now()
                }
        except Exception:
            pass

    if not movie:
        try:
            from main import resolve_subject_id, request_moviebox_mobile
            sid = await resolve_subject_id(slug)
            if sid:
                mob_res = await request_moviebox_mobile("/wefeed-mobile-bff/subject-api/get", params={"subjectId": sid})
                mob_data = mob_res.get("data") or {}
                inner = mob_data.get("subject") or mob_data
                if inner and isinstance(inner, dict):
                    cover_val = inner.get("cover")
                    cover_url = cover_val.get("url", "") if isinstance(cover_val, dict) else str(cover_val or "")
                    movie = {
                        "subject_id": str(sid),
                        "title": inner.get("title", ""),
                        "subject_type": inner.get("subjectType", 1),
                        "cover": cover_url,
                        "backdrop": cover_url,
                        "rating": float(inner.get("imdbRatingValue") or inner.get("rating") or 7.2),
                        "release_date": inner.get("releaseDate") or "2026-01-01",
                        "country": inner.get("country") or "International",
                        "genre": inner.get("genre") or "Action, Drama",
                        "description": inner.get("description") or "",
                        "is_cam": 0,
                        "detail_path": slug,
                        "tmdb_id": None,
                        "dubs": None,
                        "updated_at": datetime.datetime.now()
                    }
        except Exception:
            pass

    # If still not found, synthesize gracefully from slug words so Google never encounters a 404
    if not movie and slug:
        words = slug.rsplit('-', 1)[0] if '-' in slug else slug
        words_clean = ' '.join(re.sub(r'[^a-zA-Z0-9]+', ' ', words).split()).title()
        if words_clean:
            movie = {
                "subject_id": "",
                "title": words_clean,
                "subject_type": 1,
                "cover": "https://images.unsplash.com/photo-1594909122845-11baa439b7bf?w=1200&q=80",
                "backdrop": "https://images.unsplash.com/photo-1594909122845-11baa439b7bf?w=1200&q=80",
                "rating": 7.5,
                "release_date": "2026",
                "country": "International",
                "genre": "Action, Drama",
                "description": "",
                "is_cam": 0,
                "detail_path": slug,
                "tmdb_id": None,
                "dubs": None,
                "updated_at": datetime.datetime.now()
            }

    if movie:
        # Normalize fields
        if not movie.get("rating"):
            movie["rating"] = 7.0
        else:
            try:
                movie["rating"] = float(movie["rating"])
            except Exception:
                movie["rating"] = 7.0
        movie["rating"] = round(movie["rating"], 1)

        if not movie.get("genre"):
            movie["genre"] = "Action, Drama"

        if not movie.get("country"):
            movie["country"] = "International"

        if not movie.get("cover"):
            movie["cover"] = "https://images.unsplash.com/photo-1594909122845-11baa439b7bf?w=1200&q=80"
        if not movie.get("backdrop"):
            movie["backdrop"] = movie["cover"]

        _MOVIE_CACHE[slug] = (now, movie)

    return movie


async def fetch_related_movies(exclude_slug: str, limit: int = 8) -> List[Dict[str, Any]]:
    """Fetches high-rated related movies for internal linking."""
    pool = await get_seo_db_pool()
    if not pool:
        return []
    try:
        async with pool.acquire() as conn:
            async with conn.cursor(aiomysql.DictCursor) as cur:
                await cur.execute("""
                    SELECT title, detail_path, cover, rating, release_date, genre, subject_type
                    FROM subjects
                    WHERE detail_path IS NOT NULL AND detail_path != '' AND detail_path != %s
                    ORDER BY rating DESC, updated_at DESC
                    LIMIT %s
                """, (exclude_slug, limit))
                rows = await cur.fetchall()
                return [dict(r) for r in rows]
    except Exception as e:
        print(f"[SEO Engine] Error fetching related movies: {e}")
        return []


# ==============================================================================
# INTENT SPECIFICATIONS & CONTENT BUILDERS
# ==============================================================================

INTENT_CONFIGS = {
    "watch_online": {
        "slug_pattern": "how-to-watch-{slug}-online",
        "badge": "1080p Full HD • Free Streaming",
        "title_template": "How to Watch {title} Online in Full HD (Free Stream) - {brand_name}",
        "desc_template": "Learn how to watch {title} online in 1080p Full HD with Hindi Dubbed audio on {brand_name}. Direct high-speed streaming without subscription or lag.",
        "h1_template": "How to Watch {title} Online in Full HD",
        "h2_template": "Complete Streaming Guide for {title}",
        "primary_btn_text": "▶ Watch {title} Online Now",
        "primary_btn_sub": "Instant high-speed 1080p player",
        "intent_type": "streaming"
    },
    "download_hindi": {
        "slug_pattern": "how-to-download-{slug}-in-hindi",
        "badge": "Hindi Dubbed • Fast Offline Download",
        "title_template": "How to Download {title} in Hindi Dubbed (1080p/720p HD) - {brand_name}",
        "desc_template": "Step-by-step guide to download {title} in Hindi Dubbed audio (480p, 720p, 1080p HD). Download for offline playback on Mobile, PC, and Android APK.",
        "h1_template": "How to Download {title} in Hindi Dubbed (HD)",
        "h2_template": "Fast Direct Download Guide for {title}",
        "primary_btn_text": "⬇ Download {title} in Hindi (HD)",
        "primary_btn_sub": "Direct high-speed download link",
        "intent_type": "download"
    },
    "full_movie_hd": {
        "slug_pattern": "watch-{slug}-full-movie-hd",
        "badge": "Cinema Quality • 1080p Uncut",
        "title_template": "Watch {title} Full Movie HD Free Online - {brand_name}",
        "desc_template": "Watch {title} full movie in crystal-clear 1080p HD online for free. Enjoy uninterrupted streaming with multi-language subtitles and Hindi audio on {brand_name}.",
        "h1_template": "Watch {title} Full Movie HD Online Free",
        "h2_template": "Stream {title} Full Length in 1080p HD",
        "primary_btn_text": "▶ Play Full Movie in 1080p",
        "primary_btn_sub": "100% Free • No Subscription Needed",
        "intent_type": "streaming"
    },
    "download_and_watch": {
        "slug_pattern": "{slug}-download-and-watch-online",
        "badge": "All-In-One Stream & Download Portal",
        "title_template": "{title} - Download & Watch Online Free (1080p HD) | {brand_name}",
        "desc_template": "Watch or download {title} online in high definition. Access fast cloud streaming servers and direct Android APK download for {title} on {brand_name}.",
        "h1_template": "{title} - Download & Watch Online Free",
        "h2_template": "Stream Live or Download for Offline Viewing",
        "primary_btn_text": "▶ Watch Online / Download",
        "primary_btn_sub": "Stream online or save for offline",
        "intent_type": "hybrid"
    }
}


def build_anti_thin_description(movie: Dict[str, Any], brand_name: str) -> str:
    """Generates an informative, rich description to prevent thin-content penalties."""
    existing_desc = (movie.get("description") or "").strip()
    if existing_desc and len(existing_desc) > 80:
        return existing_desc

    title = movie.get("title", "Movie")
    country = movie.get("country", "International")
    genre = movie.get("genre", "Drama, Action")
    rating = movie.get("rating", 7.0)
    year = str(movie.get("release_date") or "2026")[:4]
    s_type = "movie" if movie.get("subject_type") == 1 else "series"

    return (
        f"{title} ({year}) is a highly anticipated {country} {genre} {s_type} that has garnered "
        f"wide international acclaim with an audience rating of {rating}/10. Delivering cinematic storytelling, "
        f"dynamic pacing, and immersive visuals, {title} is available for high-definition streaming and fast offline "
        f"downloading with Hindi Dubbed audio and multi-language subtitles on {brand_name}."
    )


def generate_seo_faqs(movie: Dict[str, Any], brand_name: str, domain: str) -> List[Dict[str, str]]:
    """Generates structured FAQ items for on-page display and FAQPage JSON-LD schema."""
    title = movie.get("title", "this movie")
    s_type = "movie" if movie.get("subject_type") == 1 else "web series"
    slug = movie.get("detail_path", "")
    watch_link = f"https://{domain}/movie/{slug}"
    download_link = f"https://{domain}/download"

    return [
        {
            "question": f"Where can I watch {title} online for free?",
            "answer": (
                f"You can watch {title} online in 1080p Full HD for free on {brand_name}. "
                f"Simply click the Watch Online button above to begin streaming instantly with zero buffering "
                f"and no subscription fees required."
            )
        },
        {
            "question": f"Is {title} available in Hindi Dubbed audio?",
            "answer": (
                f"Yes, {title} is available with Hindi dubbed audio track as well as original audio with multi-language "
                f"subtitles. You can switch audio tracks and toggle English subtitles inside the video player."
            )
        },
        {
            "question": f"How can I download {title} for offline viewing?",
            "answer": (
                f"To download {title} to your mobile device, laptop, or tablet, use the Download in HD button or "
                f"install the free official {brand_name} Android app. The app supports background downloading, multiple resolution options "
                f"(480p, 720p, 1080p), and smooth offline playback."
            )
        },
        {
            "question": f"What video qualities are supported for streaming {title}?",
            "answer": (
                f"{brand_name} delivers adaptive video streaming for {title} in 480p SD, 720p HD, and 1080p Full HD. "
                f"The player automatically adjusts playback quality based on your current internet connection speed."
            )
        },
        {
            "question": f"Can I watch {title} on Smart TV or FireStick?",
            "answer": (
                f"Yes, you can stream {title} on your Smart TV, Android TV, Amazon FireStick, PC, and Mac by opening "
                f"our website directly in your browser or casting via ChromeCast."
            )
        }
    ]


# ==============================================================================
# HTML TEMPLATE RENDERER
# ==============================================================================

def render_pseo_html(
    movie: Dict[str, Any],
    intent_key: str,
    related: List[Dict[str, Any]],
    host: str = ""
) -> str:
    brand = get_brand_info(host)
    intent = INTENT_CONFIGS[intent_key]

    title = html.escape(movie.get("title", "Movie"))
    slug = movie.get("detail_path", "")
    rating = movie.get("rating", 7.0)
    year = str(movie.get("release_date") or "2026")[:4]
    country = html.escape(movie.get("country", "International"))
    genre = html.escape(movie.get("genre", "Action, Drama"))
    cover = movie.get("cover") or "/default-cover.png"
    backdrop = movie.get("backdrop") or cover
    s_type = "Movie" if movie.get("subject_type") == 1 else "TV Series"
    description = html.escape(build_anti_thin_description(movie, brand["name"]))

    # Dynamic target URLs
    canonical_pattern = intent["slug_pattern"].format(slug=slug)
    canonical_url = f"{brand['base_url']}/{canonical_pattern}"
    direct_watch_url = f"{brand['base_url']}/movie/{slug}"
    app_download_url = brand["app_download_url"]

    # Dynamic SEO tags
    page_title = intent["title_template"].format(title=title, brand_name=brand["name"])
    page_desc = intent["desc_template"].format(title=title, brand_name=brand["name"])
    h1_text = intent["h1_template"].format(title=title)
    h2_text = intent["h2_template"].format(title=title)
    badge_text = intent["badge"]

    # Generate FAQs
    faqs = generate_seo_faqs(movie, brand["name"], brand["domain"])

    # Schema JSON-LD Markup
    schema_movie = {
        "@context": "https://schema.org",
        "@type": "Movie" if movie.get("subject_type") == 1 else "TVSeries",
        "name": title,
        "headline": page_title,
        "description": description,
        "image": cover,
        "datePublished": str(movie.get("release_date") or "2026-01-01"),
        "genre": [g.strip() for g in genre.split(",") if g.strip()],
        "aggregateRating": {
            "@type": "AggregateRating",
            "ratingValue": str(rating),
            "bestRating": "10",
            "ratingCount": "1480"
        },
        "potentialAction": {
            "@type": "WatchAction",
            "target": direct_watch_url
        }
    }

    schema_faq = {
        "@context": "https://schema.org",
        "@type": "FAQPage",
        "mainEntity": [
            {
                "@type": "Question",
                "name": item["question"],
                "acceptedAnswer": {
                    "@type": "Answer",
                    "text": item["answer"]
                }
            }
            for item in faqs
        ]
    }

    schema_breadcrumb = {
        "@context": "https://schema.org",
        "@type": "BreadcrumbList",
        "itemListElement": [
            {
                "@type": "ListItem",
                "position": 1,
                "name": "Home",
                "item": f"{brand['base_url']}/"
            },
            {
                "@type": "ListItem",
                "position": 2,
                "name": "Movies" if s_type == "Movie" else "TV Series",
                "item": f"{brand['base_url']}/{'movies' if s_type == 'Movie' else 'tv'}"
            },
            {
                "@type": "ListItem",
                "position": 3,
                "name": title,
                "item": canonical_url
            }
        ]
    }

    # Render Related Movies HTML
    related_html_cards = []
    for r in related:
        r_title = html.escape(r.get("title", ""))
        r_slug = r.get("detail_path", "")
        r_cover = r.get("cover") or "/default-cover.png"
        r_rating = r.get("rating", 7.0)
        r_year = str(r.get("release_date") or "2026")[:4]
        related_html_cards.append(f"""
        <div class="pseo-related-card">
          <a href="/movie/{r_slug}" title="Watch {r_title}">
            <div class="pseo-related-poster">
              <img src="{r_cover}" alt="{r_title} Poster" loading="lazy" onerror="this.onerror=null;this.src='/default-cover.png';">
              <span class="pseo-rating-tag"><i class="fa-solid fa-star"></i> {r_rating}</span>
            </div>
            <div class="pseo-related-info">
              <h4>{r_title}</h4>
              <div class="pseo-related-meta">
                <span>{r_year}</span>
                <span class="pseo-pill-mini">HD</span>
              </div>
            </div>
          </a>
          <div class="pseo-related-links">
            <a href="/how-to-watch-{r_slug}-online" class="pseo-guide-link">Watch Guide</a>
            <a href="/how-to-download-{r_slug}-in-hindi" class="pseo-guide-link">Download</a>
          </div>
        </div>
        """)
    related_section_html = "\n".join(related_html_cards)

    # Render FAQ HTML
    faq_html_items = []
    for idx, f in enumerate(faqs, 1):
        faq_html_items.append(f"""
        <div class="pseo-faq-item">
          <details class="pseo-faq-details" { 'open' if idx == 1 else '' }>
            <summary class="pseo-faq-summary">
              <span><strong>Q{idx}.</strong> {f['question']}</span>
              <i class="fa-solid fa-chevron-down"></i>
            </summary>
            <div class="pseo-faq-answer">
              <p>{f['answer']}</p>
            </div>
          </details>
        </div>
        """)
    faq_section_html = "\n".join(faq_html_items)

    return f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>{page_title}</title>
  <meta name="description" content="{page_desc}">
  <meta name="robots" content="index, follow, max-image-preview:large, max-snippet:-1, max-video-preview:-1">
  <link rel="canonical" href="{canonical_url}">
  
  <!-- Open Graph / Facebook -->
  <meta property="og:type" content="video.movie">
  <meta property="og:url" content="{canonical_url}">
  <meta property="og:title" content="{page_title}">
  <meta property="og:description" content="{page_desc}">
  <meta property="og:image" content="{cover}">
  <meta property="og:site_name" content="{brand['name']}">
  
  <!-- Twitter Cards -->
  <meta name="twitter:card" content="summary_large_image">
  <meta name="twitter:url" content="{canonical_url}">
  <meta name="twitter:title" content="{page_title}">
  <meta name="twitter:description" content="{page_desc}">
  <meta name="twitter:image" content="{cover}">

  <!-- Fonts & Icons -->
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Outfit:wght@400;500;600;700;800;900&family=Plus+Jakarta+Sans:wght@400;500;600;700&display=swap" rel="stylesheet">
  <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/css/all.min.css">
  <link rel="stylesheet" href="/style.css?v=7.0">

  <!-- JSON-LD Structured Data Schema Markup -->
  <script type="application/ld+json">
  {json.dumps(schema_movie, indent=2)}
  </script>
  <script type="application/ld+json">
  {json.dumps(schema_faq, indent=2)}
  </script>
  <script type="application/ld+json">
  {json.dumps(schema_breadcrumb, indent=2)}
  </script>

  <style>
    :root {{
      --pseo-accent: #00e676;
      --pseo-accent-glow: rgba(0, 230, 118, 0.35);
      --pseo-bg: #08080d;
      --pseo-surface: #0f0f18;
      --pseo-card: #151522;
      --pseo-border: rgba(255, 255, 255, 0.08);
      --pseo-text: #f4f4f8;
      --pseo-muted: #8b8b9f;
    }}
    body.pseo-body {{
      font-family: 'Plus Jakarta Sans', sans-serif;
      background: var(--pseo-bg);
      color: var(--pseo-text);
      line-height: 1.6;
      margin: 0;
      padding: 0;
    }}
    .pseo-nav-top {{
      background: rgba(15, 15, 24, 0.95);
      backdrop-filter: blur(12px);
      border-bottom: 1px solid var(--pseo-border);
      position: sticky;
      top: 0;
      z-index: 100;
      padding: 12px 24px;
      display: flex;
      justify-content: space-between;
      align-items: center;
    }}
    .pseo-brand-logo {{
      display: flex;
      align-items: center;
      gap: 10px;
      font-family: 'Outfit', sans-serif;
      font-size: 20px;
      font-weight: 800;
      color: #fff;
      text-decoration: none;
    }}
    .pseo-brand-logo span {{ color: var(--pseo-accent); }}
    .pseo-nav-links {{ display: flex; gap: 20px; align-items: center; }}
    .pseo-nav-links a {{ color: var(--pseo-muted); text-decoration: none; font-size: 14px; font-weight: 500; transition: color 0.2s; }}
    .pseo-nav-links a:hover {{ color: var(--pseo-accent); }}
    .pseo-nav-btn {{
      background: var(--pseo-accent);
      color: #000 !important;
      padding: 8px 16px;
      border-radius: 8px;
      font-weight: 700 !important;
    }}
    .pseo-container {{
      max-width: 1100px;
      margin: 0 auto;
      padding: 24px 20px 60px;
    }}
    .pseo-breadcrumbs {{
      font-size: 13px;
      color: var(--pseo-muted);
      margin-bottom: 24px;
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
    }}
    .pseo-breadcrumbs a {{ color: var(--pseo-muted); text-decoration: none; }}
    .pseo-breadcrumbs a:hover {{ color: var(--pseo-accent); }}
    
    /* Hero cinema section */
    .pseo-hero {{
      background: linear-gradient(180deg, rgba(21, 21, 34, 0.8) 0%, rgba(15, 15, 24, 0.95) 100%);
      border: 1px solid var(--pseo-border);
      border-radius: 18px;
      padding: 32px;
      margin-bottom: 40px;
      box-shadow: 0 20px 50px rgba(0, 0, 0, 0.6);
      position: relative;
      overflow: hidden;
    }}
    .pseo-hero-backdrop {{
      position: absolute;
      top: 0; left: 0; right: 0; bottom: 0;
      background-image: url('{backdrop}');
      background-size: cover;
      background-position: center;
      opacity: 0.12;
      filter: blur(20px);
      pointer-events: none;
    }}
    .pseo-hero-grid {{
      position: relative;
      z-index: 1;
      display: grid;
      grid-template-columns: 280px 1fr;
      gap: 36px;
      align-items: center;
    }}
    @media (max-width: 800px) {{
      .pseo-hero-grid {{ grid-template-columns: 1fr; justify-items: center; text-align: center; }}
    }}
    .pseo-poster {{
      width: 100%;
      max-width: 280px;
      border-radius: 14px;
      box-shadow: 0 12px 30px rgba(0, 0, 0, 0.8), 0 0 20px var(--pseo-accent-glow);
      border: 1px solid rgba(255, 255, 255, 0.15);
      transition: transform 0.3s ease;
    }}
    .pseo-poster:hover {{ transform: scale(1.02); }}
    .pseo-badge {{
      display: inline-flex;
      align-items: center;
      gap: 6px;
      background: rgba(0, 230, 118, 0.12);
      border: 1px solid rgba(0, 230, 118, 0.35);
      color: var(--pseo-accent);
      padding: 6px 14px;
      border-radius: 20px;
      font-size: 12px;
      font-weight: 700;
      margin-bottom: 14px;
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }}
    .pseo-hero h1 {{
      font-family: 'Outfit', sans-serif;
      font-size: 32px;
      font-weight: 800;
      line-height: 1.25;
      margin-bottom: 12px;
      color: #fff;
    }}
    .pseo-meta-row {{
      display: flex;
      flex-wrap: wrap;
      gap: 12px;
      margin-bottom: 18px;
      font-size: 13px;
      color: #d1d1e0;
    }}
    .pseo-pill {{
      background: rgba(255, 255, 255, 0.06);
      border: 1px solid var(--pseo-border);
      padding: 4px 10px;
      border-radius: 6px;
    }}
    .pseo-pill.rating {{ color: #ffd54f; font-weight: 700; }}
    .pseo-btn-group {{
      display: flex;
      gap: 14px;
      flex-wrap: wrap;
      margin-top: 24px;
    }}
    .pseo-btn-primary {{
      background: var(--pseo-accent);
      color: #000;
      padding: 14px 28px;
      border-radius: 12px;
      font-weight: 800;
      font-size: 16px;
      text-decoration: none;
      display: inline-flex;
      align-items: center;
      gap: 10px;
      box-shadow: 0 4px 20px var(--pseo-accent-glow);
      transition: all 0.25s;
    }}
    .pseo-btn-primary:hover {{
      background: #00c853;
      transform: translateY(-2px);
      box-shadow: 0 8px 25px rgba(0, 230, 118, 0.5);
    }}
    .pseo-btn-secondary {{
      background: rgba(255, 255, 255, 0.08);
      color: #fff;
      padding: 14px 24px;
      border-radius: 12px;
      font-weight: 700;
      font-size: 15px;
      text-decoration: none;
      display: inline-flex;
      align-items: center;
      gap: 8px;
      border: 1px solid var(--pseo-border);
      transition: all 0.25s;
    }}
    .pseo-btn-secondary:hover {{
      background: rgba(255, 255, 255, 0.15);
      border-color: rgba(255, 255, 255, 0.3);
      transform: translateY(-2px);
    }}

    /* Highlights grid */
    .pseo-grid-4 {{
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 18px;
      margin-bottom: 40px;
    }}
    .pseo-highlight-card {{
      background: var(--pseo-surface);
      border: 1px solid var(--pseo-border);
      border-radius: 14px;
      padding: 20px;
      text-align: center;
      transition: transform 0.2s, border-color 0.2s;
    }}
    .pseo-highlight-card:hover {{
      transform: translateY(-3px);
      border-color: rgba(0, 230, 118, 0.4);
    }}
    .pseo-highlight-icon {{
      font-size: 26px;
      color: var(--pseo-accent);
      margin-bottom: 10px;
    }}
    .pseo-highlight-card h4 {{
      font-family: 'Outfit', sans-serif;
      font-size: 16px;
      margin-bottom: 6px;
      color: #fff;
    }}
    .pseo-highlight-card p {{
      font-size: 13px;
      color: var(--pseo-muted);
      margin: 0;
    }}

    /* Section styling */
    .pseo-section {{
      background: var(--pseo-surface);
      border: 1px solid var(--pseo-border);
      border-radius: 16px;
      padding: 28px;
      margin-bottom: 32px;
    }}
    .pseo-section h2 {{
      font-family: 'Outfit', sans-serif;
      font-size: 24px;
      font-weight: 700;
      color: #fff;
      margin-bottom: 18px;
      display: flex;
      align-items: center;
      gap: 10px;
    }}
    .pseo-section h2 i {{ color: var(--pseo-accent); }}

    /* Spec table */
    .pseo-table {{
      width: 100%;
      border-collapse: collapse;
      margin-top: 16px;
      font-size: 14px;
    }}
    .pseo-table tr {{ border-bottom: 1px solid var(--pseo-border); }}
    .pseo-table tr:last-child {{ border-bottom: none; }}
    .pseo-table td {{ padding: 12px 10px; }}
    .pseo-table td:first-child {{
      color: var(--pseo-muted);
      width: 35%;
      font-weight: 600;
    }}
    .pseo-table td:last-child {{ color: #fff; }}

    /* How to steps */
    .pseo-steps {{ display: flex; flex-direction: column; gap: 16px; margin-top: 18px; }}
    .pseo-step-item {{
      display: flex;
      gap: 16px;
      background: var(--pseo-card);
      border: 1px solid var(--pseo-border);
      border-radius: 12px;
      padding: 16px;
      align-items: flex-start;
    }}
    .pseo-step-num {{
      background: var(--pseo-accent);
      color: #000;
      font-weight: 800;
      font-size: 16px;
      width: 36px;
      height: 36px;
      border-radius: 50%;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
    }}
    .pseo-step-body h4 {{ color: #fff; font-size: 16px; margin-bottom: 4px; }}
    .pseo-step-body p {{ color: var(--pseo-muted); font-size: 13px; margin: 0; }}

    /* FAQ accordion */
    .pseo-faq-item {{ margin-bottom: 12px; }}
    .pseo-faq-details {{
      background: var(--pseo-card);
      border: 1px solid var(--pseo-border);
      border-radius: 10px;
      overflow: hidden;
      transition: border-color 0.2s;
    }}
    .pseo-faq-details[open] {{ border-color: rgba(0, 230, 118, 0.4); }}
    .pseo-faq-summary {{
      padding: 16px 20px;
      cursor: pointer;
      display: flex;
      justify-content: space-between;
      align-items: center;
      color: #fff;
      font-weight: 600;
      font-size: 15px;
      list-style: none;
    }}
    .pseo-faq-summary::-webkit-details-marker {{ display: none; }}
    .pseo-faq-answer {{
      padding: 0 20px 16px;
      color: #b0b0c4;
      font-size: 14px;
      line-height: 1.6;
    }}

    /* Related movies grid */
    .pseo-related-grid {{
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
      gap: 16px;
      margin-top: 18px;
    }}
    .pseo-related-card {{
      background: var(--pseo-card);
      border: 1px solid var(--pseo-border);
      border-radius: 12px;
      overflow: hidden;
      transition: transform 0.25s, border-color 0.25s;
      display: flex;
      flex-direction: column;
    }}
    .pseo-related-card:hover {{
      transform: translateY(-4px);
      border-color: rgba(0, 230, 118, 0.4);
    }}
    .pseo-related-poster {{
      position: relative;
      aspect-ratio: 2/3;
      overflow: hidden;
    }}
    .pseo-related-poster img {{
      width: 100%;
      height: 100%;
      object-fit: cover;
      transition: transform 0.3s;
    }}
    .pseo-related-card:hover .pseo-related-poster img {{ transform: scale(1.05); }}
    .pseo-rating-tag {{
      position: absolute;
      top: 8px; right: 8px;
      background: rgba(0, 0, 0, 0.75);
      color: #ffd54f;
      padding: 3px 7px;
      border-radius: 6px;
      font-size: 11px;
      font-weight: 700;
    }}
    .pseo-related-info {{ padding: 10px 12px; flex-grow: 1; }}
    .pseo-related-info h4 {{
      font-size: 13px;
      font-weight: 700;
      color: #fff;
      margin-bottom: 4px;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }}
    .pseo-related-meta {{
      display: flex;
      justify-content: space-between;
      color: var(--pseo-muted);
      font-size: 11px;
    }}
    .pseo-pill-mini {{
      background: rgba(0, 230, 118, 0.15);
      color: var(--pseo-accent);
      padding: 1px 5px;
      border-radius: 4px;
      font-weight: 700;
    }}
    .pseo-related-links {{
      display: flex;
      border-top: 1px solid var(--pseo-border);
      background: rgba(0, 0, 0, 0.2);
    }}
    .pseo-guide-link {{
      flex: 1;
      text-align: center;
      padding: 6px 0;
      font-size: 10px;
      font-weight: 600;
      color: var(--pseo-muted);
      text-decoration: none;
      transition: color 0.2s;
    }}
    .pseo-guide-link:hover {{ color: var(--pseo-accent); }}
    .pseo-guide-link:first-child {{ border-right: 1px solid var(--pseo-border); }}

    /* Footer */
    .pseo-footer {{
      border-top: 1px solid var(--pseo-border);
      padding: 32px 20px;
      text-align: center;
      font-size: 13px;
      color: var(--pseo-muted);
      margin-top: 40px;
    }}
    .pseo-footer a {{ color: var(--pseo-muted); text-decoration: none; margin: 0 8px; }}
    .pseo-footer a:hover {{ color: var(--pseo-accent); }}
  </style>
</head>
<body class="pseo-body">

  <!-- Header Navigation -->
  <header class="pseo-nav-top">
    <a href="/" class="pseo-brand-logo">
      <i class="fa-solid fa-play" style="color:var(--pseo-accent);"></i>
      <span>{brand['short_name']}</span>Stream
    </a>
    <nav class="pseo-nav-links">
      <a href="/"><i class="fa-solid fa-house"></i> Home</a>
      <a href="/movies"><i class="fa-solid fa-film"></i> Movies</a>
      <a href="/tv"><i class="fa-solid fa-tv"></i> TV Series</a>
      <a href="/download" class="pseo-nav-btn"><i class="fa-brands fa-android"></i> App</a>
    </nav>
  </header>

  <main class="pseo-container">
    <!-- Breadcrumbs -->
    <nav class="pseo-breadcrumbs" aria-label="Breadcrumb">
      <a href="/">Home</a> <span>/</span>
      <a href="{'/' + ('movies' if s_type == 'Movie' else 'tv')}">{s_type}</a> <span>/</span>
      <a href="/movie/{slug}">{title}</a> <span>/</span>
      <span style="color:#fff;">{h1_text}</span>
    </nav>

    <!-- Cinema Hero Section -->
    <section class="pseo-hero">
      <div class="pseo-hero-backdrop"></div>
      <div class="pseo-hero-grid">
        <div>
          <img class="pseo-poster" src="{cover}" alt="{title} Poster" onerror="this.onerror=null;this.src='/default-cover.png';">
        </div>
        <div>
          <span class="pseo-badge"><i class="fa-solid fa-bolt"></i> {badge_text}</span>
          <h1>{h1_text}</h1>
          <div class="pseo-meta-row">
            <span class="pseo-pill rating"><i class="fa-solid fa-star"></i> {rating}/10 IMDb</span>
            <span class="pseo-pill">{year}</span>
            <span class="pseo-pill">{s_type}</span>
            <span class="pseo-pill">{country}</span>
            <span class="pseo-pill">1080p Full HD</span>
            <span class="pseo-pill">Hindi Dubbed</span>
          </div>
          <p style="color:#b8b8cc;font-size:14px;line-height:1.7;margin-bottom:18px;">
            {description}
          </p>
          <div class="pseo-btn-group">
            <a href="{direct_watch_url}" class="pseo-btn-primary" id="btnPseoWatchNow">
              <i class="fa-solid fa-circle-play"></i> {intent['primary_btn_text']}
            </a>
            <a href="{app_download_url}" class="pseo-btn-secondary" id="btnPseoDownloadApp">
              <i class="fa-brands fa-android"></i> Download via Official App
            </a>
          </div>
        </div>
      </div>
    </section>

    <!-- Key Feature Highlights Grid -->
    <section class="pseo-grid-4">
      <div class="pseo-highlight-card">
        <div class="pseo-highlight-icon"><i class="fa-solid fa-gauge-high"></i></div>
        <h4>Zero Buffering Stream</h4>
        <p>Ultra-fast cloud delivery network with optimized adaptive bitrate playback.</p>
      </div>
      <div class="pseo-highlight-card">
        <div class="pseo-highlight-icon"><i class="fa-solid fa-language"></i></div>
        <h4>Hindi Dubbed & Subs</h4>
        <p>Multi-audio tracks including Hindi Dubbed and multi-language subtitle tracks.</p>
      </div>
      <div class="pseo-highlight-card">
        <div class="pseo-highlight-icon"><i class="fa-solid fa-download"></i></div>
        <h4>High-Speed Offline Download</h4>
        <p>Save directly in 480p, 720p, or 1080p HD for uninterrupted offline viewing.</p>
      </div>
      <div class="pseo-highlight-card">
        <div class="pseo-highlight-icon"><i class="fa-solid fa-tv"></i></div>
        <h4>Cross-Device Support</h4>
        <p>Watch smoothly on Android Phones, iPhones, Windows, Mac, and Smart TV.</p>
      </div>
    </section>

    <!-- Movie Synopsis & Specifications Table -->
    <section class="pseo-section">
      <h2><i class="fa-solid fa-circle-info"></i> {title} Details & Specifications</h2>
      <table class="pseo-table">
        <tbody>
          <tr>
            <td>Official Title</td>
            <td><strong>{title}</strong></td>
          </tr>
          <tr>
            <td>Category / Type</td>
            <td>{s_type}</td>
          </tr>
          <tr>
            <td>Release Year</td>
            <td>{year} ({movie.get('release_date', '2026')})</td>
          </tr>
          <tr>
            <td>Genres</td>
            <td>{genre}</td>
          </tr>
          <tr>
            <td>Country of Origin</td>
            <td>{country}</td>
          </tr>
          <tr>
            <td>Rating</td>
            <td><strong style="color:#ffd54f;">★ {rating} / 10</strong> based on verified user reviews</td>
          </tr>
          <tr>
            <td>Supported Audio</td>
            <td>Hindi Dubbed, Original Audio, Dual Audio Tracks</td>
          </tr>
          <tr>
            <td>Streaming Qualities</td>
            <td>360p, 480p, 720p HD, 1080p Full HD</td>
          </tr>
          <tr>
            <td>Official Stream Portal</td>
            <td><a href="{direct_watch_url}" style="color:var(--pseo-accent);font-weight:700;">{direct_watch_url}</a></td>
          </tr>
        </tbody>
      </table>
    </section>

    <!-- Step by Step How-To Guide -->
    <section class="pseo-section">
      <h2><i class="fa-solid fa-list-check"></i> {h2_text}</h2>
      <div class="pseo-steps">
        <div class="pseo-step-item">
          <div class="pseo-step-num">1</div>
          <div class="pseo-step-body">
            <h4>Click the Watch Online button</h4>
            <p>Navigate directly to our web player by clicking the green <strong>Watch Online</strong> button above. No signup, registration, or credit card is required.</p>
          </div>
        </div>
        <div class="pseo-step-item">
          <div class="pseo-step-num">2</div>
          <div class="pseo-step-body">
            <h4>Choose your preferred resolution (1080p / 720p)</h4>
            <p>Our intelligent player automatically matches your network speed. You can manually lock 1080p Full HD for maximum visual fidelity.</p>
          </div>
        </div>
        <div class="pseo-step-item">
          <div class="pseo-step-num">3</div>
          <div class="pseo-step-body">
            <h4>Select Hindi Dubbed audio or subtitles</h4>
            <p>Toggle audio tracks directly inside the player settings to enjoy Hindi dubbed voices or enable English subtitles.</p>
          </div>
        </div>
        <div class="pseo-step-item">
          <div class="pseo-step-num">4</div>
          <div class="pseo-step-body">
            <h4>Optional: Download for Offline Travel Playback</h4>
            <p>Using the official {brand['short_name']} Android App, click the download icon to save {title} directly to your device storage for offline travel.</p>
          </div>
        </div>
      </div>
    </section>

    <!-- FAQ Accordion Section -->
    <section class="pseo-section">
      <h2><i class="fa-solid fa-circle-question"></i> Frequently Asked Questions</h2>
      <div class="pseo-faq-list">
        {faq_section_html}
      </div>
    </section>

    <!-- Related & Recommended Movies Grid -->
    <section class="pseo-section">
      <h2><i class="fa-solid fa-fire"></i> Recommended Movies & Series</h2>
      <p style="color:var(--pseo-muted);font-size:13px;margin-bottom:14px;">Browse trending titles with similar storylines, high ratings, and Hindi dubs:</p>
      <div class="pseo-related-grid">
        {related_section_html}
      </div>
    </section>
  </main>

  <!-- Footer -->
  <footer class="pseo-footer">
    <p>© 2026 {brand['name']} — Free HD Movies & Series Streaming Platform.</p>
    <p style="margin-top:8px;">
      <a href="/">Home</a> •
      <a href="/movies">Movies</a> •
      <a href="/tv">TV Shows</a> •
      <a href="/download">Android App</a> •
      <a href="/sitemap.xml">Sitemap Index</a>
    </p>
  </footer>

</body>
</html>"""


# ==============================================================================
# SITEMAP GENERATION ENGINE
# ==============================================================================

async def get_total_subjects_count() -> int:
    """Returns total active subjects with detail_path from DB."""
    pool = await get_seo_db_pool()
    if not pool:
        return 0
    try:
        async with pool.acquire() as conn:
            async with conn.cursor() as cur:
                await cur.execute("SELECT COUNT(*) FROM subjects WHERE detail_path IS NOT NULL AND detail_path != ''")
                row = await cur.fetchone()
                return row[0] if row else 0
    except Exception as e:
        print(f"[SEO Engine] Error counting subjects: {e}")
        return 0


async def get_latest_subject_update_time() -> str:
    """Returns latest updated_at from subjects table in ISO 8601."""
    pool = await get_seo_db_pool()
    if not pool:
        return to_iso8601(time.time())
    try:
        async with pool.acquire() as conn:
            async with conn.cursor() as cur:
                await cur.execute("SELECT MAX(updated_at) FROM subjects")
                row = await cur.fetchone()
                if row and row[0]:
                    return to_iso8601(row[0])
    except Exception:
        pass
    return to_iso8601(time.time())


async def build_master_sitemap_index(host: str = "") -> str:
    """Generates strictly Google-compliant <sitemapindex> pointing to child sitemaps."""
    cache_key = f"master_{host}"
    now = time.time()
    if cache_key in _SITEMAP_CACHE:
        ts, content = _SITEMAP_CACHE[cache_key]
        if now - ts < _SITEMAP_TTL:
            return content

    domain = get_base_domain(host)
    total_subjects = await get_total_subjects_count()
    latest_update = await get_latest_subject_update_time()

    # Pagination sizing (1,000 URLs per sub-sitemap)
    # Movie sitemaps: 1 URL per subject
    movie_pages = max(1, (total_subjects + 999) // 1000)

    # SEO Guide sitemaps: 4 URLs per subject (250 subjects = 1000 URLs)
    seo_pages = max(1, (total_subjects + 249) // 250)

    xml = ['<?xml version="1.0" encoding="UTF-8"?>']
    xml.append('<sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">')

    # Static sitemap
    xml.append('  <sitemap>')
    xml.append(f'    <loc>{domain}/sitemaps/sitemap-static.xml</loc>')
    xml.append(f'    <lastmod>{latest_update}</lastmod>')
    xml.append('  </sitemap>')

    # Movies sitemaps
    for p in range(1, movie_pages + 1):
        xml.append('  <sitemap>')
        xml.append(f'    <loc>{domain}/sitemaps/sitemap-movies-{p}.xml</loc>')
        xml.append(f'    <lastmod>{latest_update}</lastmod>')
        xml.append('  </sitemap>')

    # SEO Guides sitemaps
    for p in range(1, seo_pages + 1):
        xml.append('  <sitemap>')
        xml.append(f'    <loc>{domain}/sitemaps/sitemap-seo-guides-{p}.xml</loc>')
        xml.append(f'    <lastmod>{latest_update}</lastmod>')
        xml.append('  </sitemap>')

    xml.append('</sitemapindex>')
    result = "\n".join(xml)
    _SITEMAP_CACHE[cache_key] = (now, result)
    return result


async def build_static_sitemap(host: str = "") -> str:
    """Generates <urlset> for static landing and category pages."""
    cache_key = f"static_{host}"
    now = time.time()
    if cache_key in _SITEMAP_CACHE:
        ts, content = _SITEMAP_CACHE[cache_key]
        if now - ts < _SITEMAP_TTL:
            return content

    domain = get_base_domain(host)
    latest_update = await get_latest_subject_update_time()

    static_routes = [
        ("", "1.0", "daily"),
        ("/movies", "0.9", "daily"),
        ("/tv", "0.9", "daily"),
        ("/live-tv", "0.8", "daily"),
        ("/download", "0.85", "weekly"),
        ("/app", "0.85", "weekly")
    ]

    genres = ["Action", "Comedy", "Horror", "Animation", "Thriller", "Romance", "Sci-Fi", "Drama"]

    xml = ['<?xml version="1.0" encoding="UTF-8"?>']
    xml.append('<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">')

    for path, prio, freq in static_routes:
        xml.append('  <url>')
        xml.append(f'    <loc>{domain}{path}</loc>')
        xml.append(f'    <lastmod>{latest_update}</lastmod>')
        xml.append(f'    <changefreq>{freq}</changefreq>')
        xml.append(f'    <priority>{prio}</priority>')
        xml.append('  </url>')

    for g in genres:
        xml.append('  <url>')
        xml.append(f'    <loc>{domain}/movies?genre={g}</loc>')
        xml.append(f'    <lastmod>{latest_update}</lastmod>')
        xml.append('    <changefreq>weekly</changefreq>')
        xml.append('    <priority>0.70</priority>')
        xml.append('  </url>')

    xml.append('</urlset>')
    result = "\n".join(xml)
    _SITEMAP_CACHE[cache_key] = (now, result)
    return result


async def build_movies_sitemap(page: int = 1, host: str = "") -> str:
    """Generates <urlset> for main movie/series pages chunked at 1,000 per page."""
    cache_key = f"movies_{page}_{host}"
    now = time.time()
    if cache_key in _SITEMAP_CACHE:
        ts, content = _SITEMAP_CACHE[cache_key]
        if now - ts < _SITEMAP_TTL:
            return content

    domain = get_base_domain(host)
    page_size = 1000
    offset = max(0, (page - 1) * page_size)

    pool = await get_seo_db_pool()
    rows = []
    if pool:
        try:
            async with pool.acquire() as conn:
                async with conn.cursor() as cur:
                    await cur.execute("""
                        SELECT detail_path, subject_type, updated_at
                        FROM subjects
                        WHERE detail_path IS NOT NULL AND detail_path != ''
                        ORDER BY rating DESC, updated_at DESC
                        LIMIT %s OFFSET %s
                    """, (page_size, offset))
                    rows = await cur.fetchall()
        except Exception as e:
            print(f"[SEO Engine] Error querying movies sitemap page {page}: {e}")

    xml = ['<?xml version="1.0" encoding="UTF-8"?>']
    xml.append('<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">')

    for r in rows:
        slug = (r[0] or "").strip()
        if not slug:
            continue
        s_type = r[1]
        up_date = to_iso8601(r[2])
        type_seg = "movie" if s_type == 1 else "tv"

        xml.append('  <url>')
        xml.append(f'    <loc>{domain}/movie/{slug}</loc>')
        xml.append(f'    <lastmod>{up_date}</lastmod>')
        xml.append('    <changefreq>weekly</changefreq>')
        xml.append('    <priority>0.80</priority>')
        xml.append('  </url>')

    xml.append('</urlset>')
    result = "\n".join(xml)
    _SITEMAP_CACHE[cache_key] = (now, result)
    return result


async def build_seo_guides_sitemap(page: int = 1, host: str = "") -> str:
    """Generates <urlset> for dynamic pSEO landing pages (250 subjects * 4 = 1,000 URLs per file)."""
    cache_key = f"seoguides_{page}_{host}"
    now = time.time()
    if cache_key in _SITEMAP_CACHE:
        ts, content = _SITEMAP_CACHE[cache_key]
        if now - ts < _SITEMAP_TTL:
            return content

    domain = get_base_domain(host)
    page_size = 250  # 250 * 4 = 1,000 URLs per child sitemap
    offset = max(0, (page - 1) * page_size)

    pool = await get_seo_db_pool()
    rows = []
    if pool:
        try:
            async with pool.acquire() as conn:
                async with conn.cursor() as cur:
                    await cur.execute("""
                        SELECT detail_path, updated_at
                        FROM subjects
                        WHERE detail_path IS NOT NULL AND detail_path != ''
                        ORDER BY rating DESC, updated_at DESC
                        LIMIT %s OFFSET %s
                    """, (page_size, offset))
                    rows = await cur.fetchall()
        except Exception as e:
            print(f"[SEO Engine] Error querying seo guides sitemap page {page}: {e}")

    xml = ['<?xml version="1.0" encoding="UTF-8"?>']
    xml.append('<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">')

    for r in rows:
        slug = (r[0] or "").strip()
        if not slug:
            continue
        up_date = to_iso8601(r[1])

        # 4 target keyword variations per movie
        url_variations = [
            (f"{domain}/how-to-watch-{slug}-online", "0.75"),
            (f"{domain}/how-to-download-{slug}-in-hindi", "0.75"),
            (f"{domain}/watch-{slug}-full-movie-hd", "0.75"),
            (f"{domain}/{slug}-download-and-watch-online", "0.75")
        ]

        for u, prio in url_variations:
            xml.append('  <url>')
            xml.append(f'    <loc>{u}</loc>')
            xml.append(f'    <lastmod>{up_date}</lastmod>')
            xml.append('    <changefreq>weekly</changefreq>')
            xml.append(f'    <priority>{prio}</priority>')
            xml.append('  </url>')

    xml.append('</urlset>')
    result = "\n".join(xml)
    _SITEMAP_CACHE[cache_key] = (now, result)
    return result


def flush_sitemap_cache():
    """Clears in-memory sitemap cache to force immediate regeneration."""
    _SITEMAP_CACHE.clear()


# ==============================================================================
# FASTAPI ROUTE HANDLERS
# ==============================================================================

# ── Sitemap Index & Child Sitemaps ──────────────────────────────────────────

@router.get("/sitemap.xml")
async def master_sitemap_index_endpoint(request: Request):
    """Google Master Sitemap Index endpoint."""
    host = request.headers.get("host", "")
    xml_content = await build_master_sitemap_index(host=host)
    return Response(
        content=xml_content,
        media_type="application/xml; charset=utf-8",
        headers={
            "Content-Type": "application/xml; charset=utf-8",
            "X-Robots-Tag": "noindex, follow",
            "Cache-Control": "public, max-age=3600"
        }
    )


@router.get("/sitemaps/sitemap-static.xml")
async def sitemap_static_endpoint(request: Request):
    """Static routes child sitemap."""
    host = request.headers.get("host", "")
    xml_content = await build_static_sitemap(host=host)
    return Response(
        content=xml_content,
        media_type="application/xml; charset=utf-8",
        headers={
            "Content-Type": "application/xml; charset=utf-8",
            "Cache-Control": "public, max-age=3600"
        }
    )


@router.get("/sitemaps/sitemap-movies-{page}.xml")
async def sitemap_movies_endpoint(page: int, request: Request):
    """Paginated movie pages child sitemap."""
    if page < 1:
        raise HTTPException(status_code=404, detail="Invalid sitemap page")
    host = request.headers.get("host", "")
    xml_content = await build_movies_sitemap(page=page, host=host)
    return Response(
        content=xml_content,
        media_type="application/xml; charset=utf-8",
        headers={
            "Content-Type": "application/xml; charset=utf-8",
            "Cache-Control": "public, max-age=3600"
        }
    )


@router.get("/sitemaps/sitemap-seo-guides-{page}.xml")
async def sitemap_seo_guides_endpoint(page: int, request: Request):
    """Paginated dynamic pSEO landing pages child sitemap."""
    if page < 1:
        raise HTTPException(status_code=404, detail="Invalid sitemap page")
    host = request.headers.get("host", "")
    xml_content = await build_seo_guides_sitemap(page=page, host=host)
    return Response(
        content=xml_content,
        media_type="application/xml; charset=utf-8",
        headers={
            "Content-Type": "application/xml; charset=utf-8",
            "Cache-Control": "public, max-age=3600"
        }
    )


# ── Dynamic Programmatic SEO (pSEO) Movie Landing Pages ─────────────────────

async def handle_pseo_request(slug: str, intent_key: str, request: Request) -> HTMLResponse:
    """Core controller for handling and rendering dynamic pSEO pages."""
    if not slug:
        raise HTTPException(status_code=404, detail="Movie slug not specified")

    movie = await fetch_movie_by_slug(slug)
    if not movie:
        raise HTTPException(status_code=404, detail="Movie not found")

    host = request.headers.get("host", "")
    related = await fetch_related_movies(exclude_slug=slug, limit=8)
    html_page = render_pseo_html(movie=movie, intent_key=intent_key, related=related, host=host)

    return HTMLResponse(
        content=html_page,
        headers={
            "Cache-Control": "public, max-age=86400, stale-while-revalidate=43200",
            "X-Robots-Tag": "index, follow"
        }
    )


@router.get("/how-to-watch-{slug}-online", response_class=HTMLResponse)
async def pseo_how_to_watch(slug: str, request: Request):
    """Target: 'How to Watch {Movie} Online' search intent."""
    return await handle_pseo_request(slug=slug, intent_key="watch_online", request=request)


@router.get("/how-to-download-{slug}-in-hindi", response_class=HTMLResponse)
async def pseo_how_to_download_hindi(slug: str, request: Request):
    """Target: 'How to Download {Movie} in Hindi' search intent."""
    return await handle_pseo_request(slug=slug, intent_key="download_hindi", request=request)


@router.get("/watch-{slug}-full-movie-hd", response_class=HTMLResponse)
async def pseo_watch_full_movie_hd(slug: str, request: Request):
    """Target: 'Watch {Movie} Full Movie HD Free Online' search intent."""
    return await handle_pseo_request(slug=slug, intent_key="full_movie_hd", request=request)


@router.get("/{slug}-download-and-watch-online", response_class=HTMLResponse)
async def pseo_download_and_watch_online(slug: str, request: Request):
    """Target: '{Movie} Download and Watch Online' search intent."""
    return await handle_pseo_request(slug=slug, intent_key="download_and_watch", request=request)
