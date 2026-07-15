import re

with open(r'E:\P\movie bot\public\app.js', 'r', encoding='utf-8') as f:
    content = f.read()

# ─── 1. Fix detectRoute() to recognize /movie/ and /tv/ paths ────────────────
old_route = '''function detectRoute() {
    const path = window.location.pathname.toLowerCase();
    if (path.includes("/movies")) {
        routes.isMovies = true;
        state.subjectType = 1;
    } else if (path.includes("/tv")) {
        routes.isTv = true;
        state.subjectType = 2;
    } else if (path.includes("/live-tv")) {
        routes.isLiveTv = true;
    } else if (path.includes("/details")) {
        routes.isDetails = true;
    } else if (path.includes("/watch")) {
        routes.isWatch = true;
    } else {
        routes.isHome = true;
        state.subjectType = 0;
    }
    highlightActiveMobileNav();
}'''

new_route = '''function detectRoute() {
    const path = window.location.pathname.toLowerCase();
    if (path.startsWith("/watch/")) {
        routes.isWatch = true;
    } else if (path.startsWith("/movie/") || path.startsWith("/tv/")) {
        routes.isDetails = true;
    } else if (path.startsWith("/movies")) {
        routes.isMovies = true;
        state.subjectType = 1;
    } else if (path.startsWith("/tv") && !path.startsWith("/tv/")) {
        routes.isTv = true;
        state.subjectType = 2;
    } else if (path.startsWith("/live-tv")) {
        routes.isLiveTv = true;
    } else {
        routes.isHome = true;
        state.subjectType = 0;
    }
    highlightActiveMobileNav();
}'''

content = content.replace(old_route, new_route)

# ─── 2. Fix isInitialHome check ─────────────────────────────────────────────
old_initial = "const isInitialHome = !initialPath.includes(\"/movies\") && !initialPath.includes(\"/tv\") && !initialPath.includes(\"/details\") && !initialPath.includes(\"/watch\") && !new URLSearchParams(window.location.search).get(\"keyword\");"
new_initial = "const isInitialHome = !initialPath.startsWith(\"/movies\") && !initialPath.startsWith(\"/tv/\") && !initialPath.match(/^\\/(movie|tv)\\//) && !initialPath.startsWith(\"/watch/\") && !new URLSearchParams(window.location.search).get(\"keyword\");"
content = content.replace(old_initial, new_initial)

# ─── 3. Fix initDetailsPage() to use slug from URL pathname ──────────────────
old_details_init = '''async function initDetailsPage() {
    const urlParams = new URLSearchParams(window.location.search);
    let subjectId = urlParams.get("id");
    const tmdbId = urlParams.get("tmdb");
    const type = urlParams.get("type") || "movie";
    const reqSeason = urlParams.get("season") ? parseInt(urlParams.get("season")) : 1;
    const reqEpisode = urlParams.get("episode") ? parseInt(urlParams.get("episode")) : 1;
    
    if (!subjectId && !tmdbId) {
        window.location.href = "/";
        return;
    }

    const loading = document.getElementById("watchPageLoading");
    const content = document.getElementById("watchWrapper");

    if (tmdbId) {
        if (loading) loading.innerHTML = `<div class="loading-spinner"></div><p style="margin-top: 15px;">Resolving TMDB ID ${tmdbId} via Streamfit...</p>`;
        const resolution = await apiGet(`/api/resolve-tmdb?tmdbId=${tmdbId}&type=${type}&season=${reqSeason}&episode=${reqEpisode}`);
        if (resolution && resolution.code === 0 && resolution.data && resolution.data.subjectId) {
            subjectId = resolution.data.subjectId;
            state.selectedSeason = resolution.data.season;
            state.selectedEpisode = resolution.data.episode;
        } else {
            if (loading) loading.innerHTML = "<p><i class=\'fa-solid fa-triangle-exclamation\'></i> Failed to resolve TMDB ID. Go back home.</p>";
            return;
        }
    } else {
        state.selectedSeason = reqSeason;
        state.selectedEpisode = reqEpisode;
    }

    const detailPath = urlParams.get("path") || "";'''

new_details_init = '''async function initDetailsPage() {
    const pathParts = window.location.pathname.split("/");
    const detailPath = decodeURIComponent(pathParts[pathParts.length - 1] || "");
    let subjectId = "";
    const urlParams = new URLSearchParams(window.location.search);
    const reqSeason = urlParams.get("season") ? parseInt(urlParams.get("season")) : 1;
    const reqEpisode = urlParams.get("episode") ? parseInt(urlParams.get("episode")) : 1;

    if (!detailPath) {
        window.location.href = "/";
        return;
    }

    const loading = document.getElementById("watchPageLoading");
    const content = document.getElementById("watchWrapper");

    state.selectedSeason = reqSeason;
    state.selectedEpisode = reqEpisode;'''

content = content.replace(old_details_init, new_details_init)

# ─── 4. Fix Watch Online button in initDetailsPage ───────────────────────────
old_watch_btn = '''            btnDetailsPlay.onclick = () => {
                let url = `/watch?id=${subjectId}&path=${encodeURIComponent(detailPath)}`;
                if (detail.subjectType === 2) {
                    url += `&season=${state.selectedSeason}&episode=${state.selectedEpisode}`;
                }
                window.location.href = url;
            };'''
new_watch_btn = '''            btnDetailsPlay.onclick = () => {
                const typeSegment = detail.subjectType === 2 ? "tv" : "movie";
                let url = `/watch/${typeSegment}/${encodeURIComponent(detailPath)}`;
                if (detail.subjectType === 2) {
                    url += `?season=${state.selectedSeason}&episode=${state.selectedEpisode}`;
                }
                window.location.href = url;
            };'''
content = content.replace(old_watch_btn, new_watch_btn)

# ─── 5. Fix dub selector in initDetailsPage ───────────────────────────────────
old_dub_details = '''                    let newUrl = `/details?id=${targetSubjectId}&path=${encodeURIComponent(targetDetailPath)}`;
                    if (state.selectedSubject && state.selectedSubject.subjectType === 2) {
                        newUrl += `&season=${state.selectedSeason}&episode=${state.selectedEpisode}`;
                    }
                    window.location.href = newUrl;
                };'''
new_dub_details = '''                    const typeSegment = state.selectedSubject && state.selectedSubject.subjectType === 2 ? "tv" : "movie";
                    let newUrl = `/${typeSegment}/${encodeURIComponent(targetDetailPath)}`;
                    if (state.selectedSubject && state.selectedSubject.subjectType === 2) {
                        newUrl += `?season=${state.selectedSeason}&episode=${state.selectedEpisode}`;
                    }
                    window.location.href = newUrl;
                };'''
content = content.replace(old_dub_details, new_dub_details)

# ─── 6. Fix initWatchPage() URL parsing ──────────────────────────────────────
old_watch_params = '''    const urlParams = new URLSearchParams(window.location.search);
    let subjectId = urlParams.get("id");
    const tmdbId = urlParams.get("tmdb");
    const type = urlParams.get("type") || "movie";
    const reqSeason = urlParams.get("season") ? parseInt(urlParams.get("season")) : 1;
    const reqEpisode = urlParams.get("episode") ? parseInt(urlParams.get("episode")) : 1;
    
    const oldStyle = document.getElementById(\'plyr-live-custom-css\');'''

new_watch_params = '''    // Extract slug from URL path: /watch/movie/<slug> or /watch/tv/<slug>
    const watchPathParts = window.location.pathname.split("/");
    // watchPathParts = ["", "watch", "movie"|"tv", "<slug>"]
    const watchSlug = decodeURIComponent(watchPathParts[3] || "");
    const watchTypeSegment = watchPathParts[2] || "movie"; // "movie" or "tv"
    const urlParams = new URLSearchParams(window.location.search);
    let subjectId = "";
    const tmdbId = urlParams.get("tmdb");
    const type = urlParams.get("type") || watchTypeSegment;
    const reqSeason = urlParams.get("season") ? parseInt(urlParams.get("season")) : 1;
    const reqEpisode = urlParams.get("episode") ? parseInt(urlParams.get("episode")) : 1;
    
    const oldStyle = document.getElementById(\'plyr-live-custom-css\');'''
content = content.replace(old_watch_params, new_watch_params)

# ─── 7. Fix sports/live type check in initWatchPage ─────────────────────────
old_sports_check = "    if (type !== \"sports\" && type !== \"tv\" && !subjectId && !tmdbId) {"
new_sports_check = "    if (type !== \"sports\" && type !== \"tv\" && !watchSlug && !tmdbId) {"
content = content.replace(old_sports_check, new_sports_check)

# ─── 8. Fix the detailPath in initWatchPage ──────────────────────────────────
old_detail_path_watch = '''    } else {
        state.selectedSeason = reqSeason;
        state.selectedEpisode = reqEpisode;
    }

    const detailPath = urlParams.get("path") || "";
    const result = await apiGet(`/api/detail?detailPath=${encodeURIComponent(detailPath)}`);'''
new_detail_path_watch = '''    } else {
        state.selectedSeason = reqSeason;
        state.selectedEpisode = reqEpisode;
    }

    const detailPath = watchSlug;
    const result = await apiGet(`/api/detail?detailPath=${encodeURIComponent(detailPath)}`);'''
content = content.replace(old_detail_path_watch, new_detail_path_watch)

# ─── 9. Fix loadPlayResources detailPath extraction ──────────────────────────
old_resource_path = '''    const urlParams = new URLSearchParams(window.location.search);
    const detailPath = urlParams.get("path") || "";

    // Check for saved playback progress for this resource'''
new_resource_path = '''    // Extract detailPath from URL pathname (/watch/movie/<slug> or /movie/<slug>)
    const _pathParts = window.location.pathname.split("/");
    const detailPath = decodeURIComponent(_pathParts[_pathParts.length - 1] || "");

    // Check for saved playback progress for this resource'''
content = content.replace(old_resource_path, new_resource_path)

# ─── 10. Fix the /api/resource URL construction ──────────────────────────────
old_resource_url = '''    let url = `/api/resource?subjectId=${subjectId}`;
    if (detailPath) {
        url += `&detailPath=${encodeURIComponent(detailPath)}`;
    }'''
new_resource_url = '''    let url = `/api/resource?detailPath=${encodeURIComponent(detailPath)}`;'''
content = content.replace(old_resource_url, new_resource_url)

# ─── 11. Fix episode btn URL update (uses old /watch?id=... path format) ─────
old_ep_url = '''                const urlParams = new URLSearchParams(window.location.search);
                const detailPath = urlParams.get("path") || "";
                const newUrl = `/watch?id=${state.selectedSubject.subjectId}&path=${encodeURIComponent(detailPath)}&season=${state.selectedSeason}&episode=${state.selectedEpisode}`;
                history.pushState({}, "", newUrl);'''
new_ep_url = '''                const _epPathParts = window.location.pathname.split("/");
                const _epSlug = _epPathParts[_epPathParts.length - 1] || "";
                const _epType = _epPathParts[2] || "movie";
                const newUrl = `/watch/${_epType}/${_epSlug}?season=${state.selectedSeason}&episode=${state.selectedEpisode}`;
                history.pushState({}, "", newUrl);'''
content = content.replace(old_ep_url, new_ep_url)

# ─── 12. Fix dub selector URL update in initWatchPage ───────────────────────
old_dub_url = '''                    // Update URL silently
                    const urlParams = new URLSearchParams(window.location.search);
                    urlParams.set("id", targetSubjectId);
                    if (targetDetailPath) {
                        urlParams.set("path", targetDetailPath);
                    } else {
                        urlParams.delete("path");
                    }
                    history.pushState({}, "", `${window.location.pathname}?${urlParams.toString()}`);'''
new_dub_url = '''                    // Update URL silently
                    const _dubPathParts = window.location.pathname.split("/");
                    const _dubTypeSegment = _dubPathParts[2] || "movie";
                    history.pushState({}, "", `/watch/${_dubTypeSegment}/${encodeURIComponent(targetDetailPath || watchSlug)}`);'''
content = content.replace(old_dub_url, new_dub_url)

# ─── 13. Fix subtitle loading (still uses subjectId) ─────────────────────────
old_subtitle_load = "                loadSubtitles(state.selectedSubject.subjectId, bestRes.resourceId).catch(err => {"
new_subtitle_load = "                loadSubtitles(detailPath, bestRes.resourceId).catch(err => {"
content = content.replace(old_subtitle_load, new_subtitle_load)

# ─── 14. Fix hero banner watch now button (was using inline onclick with item variable context lost) ──
old_hero_btn = "                    <button class=\"btn-primary\" onclick=\"window.location.href=\\'/\\' + (item.subjectType === 2 ? \\'tv\\' : \\'movie\\') + \\'/\\' + encodeURIComponent(item.detailPath || sub.detailPath || \\'\\')\""
new_hero_btn = "                    <button class=\"btn-primary\" onclick=\"(function(){var dp='" + "' + (i + '') + '"; # placeholder - handled below

# Actually just fix the hero banner with a cleaner approach:
# Replace the entire slide onclick to use a data attribute approach
old_hero_btn2 = '''        slide.innerHTML = `
            <div class="hero-overlay"></div>
            <div class="hero-content">
                <span class="hero-genre-badge">${genres}</span>
                <h1 class="hero-title">${title}</h1>
                <div class="hero-meta">
                    <span class="rating"><i class="fa-solid fa-star"></i> ${rating}</span>
                    <span>${releaseYear}</span>
                    <span>${country}</span>
                </div>
                <div class="hero-buttons">
                    <button class="btn-primary" onclick="window.location.href=\\'\\/' + (item.subjectType === 2 ? \\'tv\\' : \\'movie\\') + \\'/\\' + encodeURIComponent(item.detailPath || sub.detailPath || \\'\\')">
                        <i class="fa-solid fa-play"></i> Watch Now
                    </button>
                </div>
            </div>
        `;'''
new_hero_btn2 = '''        const heroDetailPath = item.detailPath || sub.detailPath || "";
        const heroType = (item.subjectType || sub.subjectType) === 2 ? "tv" : "movie";
        slide.dataset.href = `/${heroType}/${encodeURIComponent(heroDetailPath)}`;
        slide.style.cursor = "pointer";
        slide.addEventListener("click", (e) => {
            if (!e.target.closest("button")) window.location.href = slide.dataset.href;
        });
        slide.innerHTML = `
            <div class="hero-overlay"></div>
            <div class="hero-content">
                <span class="hero-genre-badge">${genres}</span>
                <h1 class="hero-title">${title}</h1>
                <div class="hero-meta">
                    <span class="rating"><i class="fa-solid fa-star"></i> ${rating}</span>
                    <span>${releaseYear}</span>
                    <span>${country}</span>
                </div>
                <div class="hero-buttons">
                    <button class="btn-primary" onclick="window.location.href=this.closest(\'[data-href]\').dataset.href">
                        <i class="fa-solid fa-play"></i> Watch Now
                    </button>
                </div>
            </div>
        `;'''
content = content.replace(old_hero_btn2, new_hero_btn2)

# ─── 15. Fix search result card click (item.detailPath) ─────────────────────
old_search_redirect = "                                window.location.href = `/details?id=${item.subjectId}&path=${encodeURIComponent(item.detailPath)}`;"
new_search_redirect = "                                window.location.href = `/${item.subjectType === 2 ? 'tv' : 'movie'}/${encodeURIComponent(item.detailPath || '')}`;"
content = content.replace(old_search_redirect, new_search_redirect)

# ─── 16. Fix notification click ──────────────────────────────────────────────
old_noti_click = "window.location.href = `/details?id=${noti.subjectId}`;"
new_noti_click = "window.location.href = `/movie/${noti.detailPath || noti.subjectId}`;"
content = content.replace(old_noti_click, new_noti_click)

# ─── 17. Fix continue watching cards ─────────────────────────────────────────
old_cw_click = "window.location.href = `/watch?id="
new_cw_click = "window.location.href = `/watch/movie/"
# Don't do blind replace - just check what's there
import re
# Replace continue watching href generation
content = re.sub(
    r'window\.location\.href = `/watch\?id=\$\{item\.subjectId\}&path=\$\{encodeURIComponent\(item\.detailPath \|\| \'\'\)\}[^`]*`',
    "window.location.href = `/${(item.subjectType === 2 ? 'tv' : 'movie')}/${encodeURIComponent(item.detailPath || '')}`",
    content
)

with open(r'E:\P\movie bot\public\app.js', 'w', encoding='utf-8') as f:
    f.write(content)

print("Done! Fixed routing in app.js")
