// Global Plyr player instance
let playerInstance = null;
let hlsInstance = null;
let bufferingCount = 0;
let lastBufferingTime = 0;
let isSwitchingQuality = false;
let userSelectedQuality = false;
let isProgrammaticQualityChange = false;

// App State
const state = {
    currentPage: 1,
    currentQuery: "",
    subjectType: 0, // 0 (All), 1 (Movie), 2 (TV Show), 7 (Animation)
    activeGenre: "*",
    activeCountry: "*",
    activeYear: "*",
    activeLang: "*",
    activeSort: "ForYou",
    selectedSubject: null,
    selectedSeason: 1,
    selectedEpisode: 1,
    playingSeason: null,
    playingEpisode: null,
    pendingResumeTime: null,
    nextEpisodeTimer: null,
    availableResources: [],
    availableCaptions: [],
    directMp4Url: "",
    userInteracted: false,
    hasAutounmuted: false,
    hasMore: false,
    loadingMore: false,
    bufferingTimeout: null
};

// Determine current page route
const routes = {
    isHome: false,
    isMovies: false,
    isTv: false,
    isLiveTv: false,
    isDetails: false,
    isWatch: false
};

function detectRoute() {
    const path = window.location.pathname.toLowerCase();
    if (path.startsWith("/watch/")) {
        routes.isWatch = true;
    } else if (path.startsWith("/movie/") || path.match(/^\/tv\//)) {
        routes.isDetails = true;
    } else if (path.startsWith("/movies")) {
        routes.isMovies = true;
        state.subjectType = 1;
    } else if (path.startsWith("/tv") && !path.match(/^\/tv\//)) {
        routes.isTv = true;
        state.subjectType = 2;
    } else if (path.startsWith("/live-tv")) {
        routes.isLiveTv = true;
    } else {
        routes.isHome = true;
        state.subjectType = 0;
    }
    highlightActiveMobileNav();
}

function highlightActiveMobileNav() {
    const homeNav = document.getElementById("mobileNavHome");
    const moviesNav = document.getElementById("mobileNavMovies");
    const tvNav = document.getElementById("mobileNavTv");
    const liveTvNav = document.getElementById("mobileNavLiveTv");

    if (homeNav) homeNav.classList.remove("active");
    if (moviesNav) moviesNav.classList.remove("active");
    if (tvNav) tvNav.classList.remove("active");
    if (liveTvNav) liveTvNav.classList.remove("active");

    if (routes.isMovies && moviesNav) {
        moviesNav.classList.add("active");
    } else if (routes.isTv && tvNav) {
        tvNav.classList.add("active");
    } else if (routes.isLiveTv && liveTvNav) {
        liveTvNav.classList.add("active");
    } else if (routes.isHome && homeNav) {
        homeNav.classList.add("active");
    }
}

function isTvSubject(item) {
    if (!item) return false;
    if (item.subjectType === 1) return false;
    if (item.subjectType === 2) return true;
    return Boolean(item.seNum && item.seNum > 1);
}

// Utility function to enable mouse drag-to-scroll and mouse wheel horizontal scrolling
function enableDragScroll(el) {
    if (!el) return;
    let isDown = false;
    let startX;
    let scrollLeft;

    el.addEventListener('mousedown', (e) => {
        // Only trigger on left-click
        if (e.button !== 0) return;
        isDown = true;
        el.classList.add('dragging');
        startX = e.pageX - el.offsetLeft;
        scrollLeft = el.scrollLeft;
        el.style.scrollBehavior = 'auto';
    });

    el.addEventListener('mouseleave', () => {
        isDown = false;
        el.classList.remove('dragging');
    });

    el.addEventListener('mouseup', () => {
        isDown = false;
        el.classList.remove('dragging');
    });

    el.addEventListener('mousemove', (e) => {
        if (!isDown) return;
        e.preventDefault();
        const x = e.pageX - el.offsetLeft;
        const walk = (x - startX) * 1.5; // Drag speed multiplier
        el.scrollLeft = scrollLeft - walk;
    });

    el.addEventListener('wheel', (e) => {
        if (e.deltaY !== 0) {
            e.preventDefault();
            el.style.scrollBehavior = 'auto';
            el.scrollLeft += e.deltaY;
        }
    }, { passive: false });
}

// Dynamic Multi-Domain Brand Configuration
function getBrandConfig() {
    const host = (window.location.hostname || "").toLowerCase();
    if (host.includes("nxly")) {
        return {
            name: "NXLY",
            nameHtml: 'NX<span>LY</span>',
            domain: "nxly.online",
            fullUrl: "https://nxly.online",
            favicon: "/favicon-nxly.svg",
            logoIconHtml: '<span class="logo-nx-glyph">NX</span>',
            byBrand: "By NXLY"
        };
    }
    return {
        name: "Streamfit",
        nameHtml: 'Stream<span>fit</span>',
        domain: "streamfit.ehealthfinder.com",
        fullUrl: "https://streamfit.ehealthfinder.com",
        favicon: "/favicon.svg",
        logoIconHtml: '<i class="fa-solid fa-play"></i>',
        byBrand: "By Streamfit"
    };
}

function applyBrandTheme() {
    try {
        const brand = getBrandConfig();

        // Update favicon
        const favicons = document.querySelectorAll("link[rel*='icon']");
        favicons.forEach(el => el.href = brand.favicon);

        // Update all .logo-text elements
        document.querySelectorAll(".logo-text").forEach(el => {
            el.innerHTML = brand.nameHtml;
        });

        // Update all .play-logo icons
        document.querySelectorAll(".play-logo").forEach(el => {
            el.innerHTML = brand.logoIconHtml;
        });

        // Update document title if it mentions the other brand
        if (document.title.includes("Streamfit") && brand.name !== "Streamfit") {
            document.title = document.title.replace(/Streamfit/g, brand.name);
        } else if (document.title.includes("NXLY") && brand.name !== "NXLY") {
            document.title = document.title.replace(/NXLY/g, brand.name);
        }

        // Update "Resources By Streamfit"
        document.querySelectorAll(".upload-by").forEach(el => {
            el.textContent = brand.byBrand;
        });

        if (document.body) {
            document.body.dataset.brand = brand.name.toLowerCase();
        }
    } catch (e) {
        console.error("Error applying brand theme:", e);
    }
}

// Initial Load
document.addEventListener("DOMContentLoaded", () => {
    applyBrandTheme();
    detectRoute();
    bindCommonEvents();
    initWebNotifications();

    // Set userInteracted to true on any user click on the document to capture gesture
    document.addEventListener("click", () => {
        state.userInteracted = true;
        if (playerInstance && playerInstance.muted && !state.hasAutounmuted) {
            playerInstance.muted = false;
            playerInstance.volume = 1.0;
            state.hasAutounmuted = true;
            const loaderOverlay = document.getElementById("playerLoaderOverlay");
            if (loaderOverlay) {
                loaderOverlay.classList.remove("visible");
            }
        }
    }, { once: false, passive: true });

    if (routes.isHome) {
        initHomePage();
    } else if (routes.isMovies || routes.isTv) {
        initFilterPage();
    } else if (routes.isLiveTv) {
        initLiveTvPage();
    } else if (routes.isDetails) {
        initDetailsPage();
    } else if (routes.isWatch) {
        initWatchPage();
    }
});

// ── Live Active Users Heartbeat ──────────────────────────────────────────────
(function initHeartbeat() {
    const HEARTBEAT_INTERVAL = 30000; // 30 seconds
    let _sessionId = sessionStorage.getItem('sf_session_id') || null;

    async function sendHeartbeat() {
        try {
            const resp = await fetch('/api/heartbeat', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ client: 'web', session_id: _sessionId }),
                keepalive: true
            });
            if (resp.ok) {
                const data = await resp.json();
                if (data.session_id) {
                    _sessionId = data.session_id;
                    sessionStorage.setItem('sf_session_id', _sessionId);
                }
            }
        } catch (_) { /* silent fail — don't disrupt UX */ }
    }

    sendHeartbeat(); // immediate on load
    setInterval(sendHeartbeat, HEARTBEAT_INTERVAL);
})();

// ==========================================================================
// API REQUEST HELPERS
// ==========================================================================
async function apiGet(path) {
    try {
        const response = await fetch(path);
        if (!response.ok) throw new Error(`HTTP error: ${response.status}`);
        return await response.json();
    } catch (err) {
        console.error("API GET Error:", err);
        return null;
    }
}

async function apiPost(path, body, timeoutMs = 5000, retries = 2) {
    for (let attempt = 1; attempt <= retries + 1; attempt++) {
        const controller = new AbortController();
        const id = setTimeout(() => controller.abort(), timeoutMs);
        try {
            const response = await fetch(path, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body),
                signal: controller.signal
            });
            clearTimeout(id);
            if (!response.ok) throw new Error(`HTTP error: ${response.status}`);
            return await response.json();
        } catch (err) {
            clearTimeout(id);
            console.warn(`[API POST] Attempt ${attempt} failed for ${path}:`, err);
            if (attempt > retries) {
                console.error("API POST Error after retries:", err);
                return null;
            }
            // Wait 1 second before retrying
            await new Promise(r => setTimeout(r, 1000));
        }
    }
    return null;
}

// Eagerly start fetching home data immediately as the script is loaded
let eagerHomePromise = null;
let eagerBannersPromise = null;

const initialPath = window.location.pathname.toLowerCase();
const isInitialHome = !initialPath.includes("/movies") && !initialPath.includes("/tv") && !initialPath.includes("/details") && !initialPath.includes("/watch") && !new URLSearchParams(window.location.search).get("keyword");

if (isInitialHome) {
    eagerBannersPromise = apiGet("/api/banners");
    eagerHomePromise = apiGet("/api/home?page=1&tabId=0");
}

// ==========================================================================
// HOME PAGE LOGIC
// ==========================================================================
async function initHomePage() {
    renderContinueWatchingSection();
    // Check if query string contains keyword (forwarded search)
    const urlParams = new URLSearchParams(window.location.search);
    const keyword = urlParams.get("keyword");
    if (keyword) {
        document.getElementById("searchInput").value = keyword;
        state.currentQuery = keyword;
        loadSearchResults();
        return;
    }

    showShimmers(true);
    
    // Use eagerly started fetch promises or start them if not already started
    if (!eagerBannersPromise) eagerBannersPromise = apiGet("/api/banners");
    if (!eagerHomePromise) eagerHomePromise = apiGet("/api/home?page=1&tabId=0");
    
    // Fetch banner and home data feed concurrently using Promise.all
    const [bannersResult, result] = await Promise.all([eagerBannersPromise, eagerHomePromise]);

    if (bannersResult && bannersResult.data && bannersResult.data.list && bannersResult.data.list.length > 0) {
        renderHeroBanner(bannersResult.data.list);
    }
    
    if (result && result.data && result.data.items) {
        const items = result.data.items;
        
        // Discover fresh banners from API and update local/UI display
        const bannerSection = items.find(sec => sec.type === "BANNER" && sec.banner && sec.banner.items && sec.banner.items.length > 0);
        if (bannerSection) {
            const apiBanners = bannerSection.banner.items.map(item => {
                const sub = item.subject || {};
                const imgUrl = item.image ? (item.image.url || item.image) : (sub.cover ? (sub.cover.url || sub.cover) : "");
                return {
                    subjectId: item.subjectId || sub.subjectId,
                    title: item.title || sub.title || item.content,
                    image: imgUrl,
                    detailPath: item.detailPath || sub.detailPath,
                    subjectType: item.subjectType || sub.subjectType,
                    subject: sub
                };
            });
            renderHeroBanner(apiBanners);
        }
        
        // Render Trending Now section
        renderTrending(items);
        
        // Populate dynamic explore grid
        renderExploreGrid(items);
    }
    showShimmers(false);
    initCustomCategorySliders();
    initHomePageSportsAndTv();
}

let heroSliderInterval = null;

function renderHeroBanner(banners) {
    const slider = document.getElementById("heroSlider");
    if (!slider) return;

    if (!banners || banners.length === 0) {
        slider.style.display = "none";
        return;
    }

    slider.style.display = "block";
    slider.innerHTML = "";

    // Clear old interval if exists
    if (heroSliderInterval) {
        clearInterval(heroSliderInterval);
        heroSliderInterval = null;
    }

    // Create slider container
    const container = document.createElement("div");
    container.className = "slider-container";
    container.style.width = "100%";
    container.style.height = "100%";
    container.style.position = "relative";
    slider.appendChild(container);

    // Create dots container
    const dotsContainer = document.createElement("div");
    dotsContainer.className = "hero-dots";
    slider.appendChild(dotsContainer);

    const maxBanners = Math.min(banners.length, 10); // show up to 10 banners

    for (let i = 0; i < maxBanners; i++) {
        const item = banners[i];
        const sub = item.subject || {};
        
        const genres = sub.genre ? (Array.isArray(sub.genre) ? sub.genre.slice(0, 2).join(', ') : sub.genre.split(',').slice(0, 2).join(', ')) : 'Trending';
        const rating = sub.imdbRatingValue || item.rating || '7.5';
        const releaseDate = sub.releaseDate || item.releaseDate || '2026';
        const releaseYear = releaseDate.split('-')[0];
        const country = sub.countryName || item.country || 'USA';
        const bannerUrl = item.image || "";
        const title = item.title || item.content || sub.title;

        // Slide element
        const slide = document.createElement("div");
        slide.className = `hero-slide ${i === 0 ? 'active' : ''}`;
        slide.style.backgroundImage = `url('${bannerUrl}')`;
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
                    <button class="btn-primary" onclick="window.location.href=\'/\' + (item.subjectType === 2 ? \'tv\' : \'movie\') + \'/\' + encodeURIComponent(item.detailPath || sub.detailPath || \'\')">
                        <i class="fa-solid fa-play"></i> Watch Now
                    </button>
                </div>
            </div>
        `;
        container.appendChild(slide);

        // Dot element
        const dot = document.createElement("div");
        dot.className = `hero-dot ${i === 0 ? 'active' : ''}`;
        dot.onclick = () => {
            goToSlide(i);
        };
        dotsContainer.appendChild(dot);
    }

    let currentSlide = 0;

    function goToSlide(index) {
        const slides = container.querySelectorAll(".hero-slide");
        const dots = dotsContainer.querySelectorAll(".hero-dot");
        if (slides.length === 0) return;

        slides[currentSlide].classList.remove("active");
        dots[currentSlide].classList.remove("active");

        currentSlide = index;

        slides[currentSlide].classList.add("active");
        dots[currentSlide].classList.add("active");
    }

    // Auto rotate every 5 seconds
    if (maxBanners > 1) {
        heroSliderInterval = setInterval(() => {
            let nextSlide = (currentSlide + 1) % maxBanners;
            goToSlide(nextSlide);
        }, 5000);
    }
}

function renderTrending(items) {
    const grid = document.getElementById("trendingGrid");
    if (!grid) return;
    grid.innerHTML = "";

    let trendingItems = [];
    const trendingSection = items.find(sec => sec.type === "SUBJECTS_MOVIE" || (sec.title && sec.title.includes("Trending")));
    
    if (trendingSection && trendingSection.subjects) {
        trendingItems = trendingSection.subjects;
    } else {
        const fallbackSection = items.find(sec => sec.subjects && sec.subjects.length > 0);
        if (fallbackSection) {
            trendingItems = fallbackSection.subjects;
        }
    }

    trendingItems.slice(0, 10).forEach(item => {
        const card = createContentCard(item);
        grid.appendChild(card);
    });
    setupSliderNavigation(grid);
}

// Wrap a content slider in left/right nav arrow buttons
function setupSliderNavigation(gridEl) {
    const parent = gridEl.parentNode;
    if (!parent || parent.classList.contains('slider-wrapper')) return;
    const wrapper = document.createElement('div');
    wrapper.className = 'slider-wrapper';
    parent.insertBefore(wrapper, gridEl);
    wrapper.appendChild(gridEl);

    const prevBtn = document.createElement('button');
    prevBtn.className = 'slider-nav-btn prev-btn';
    prevBtn.innerHTML = '<i class="fa-solid fa-chevron-left"></i>';
    prevBtn.onclick = () => gridEl.scrollBy({ left: -800, behavior: 'smooth' });

    const nextBtn = document.createElement('button');
    nextBtn.className = 'slider-nav-btn next-btn';
    nextBtn.innerHTML = '<i class="fa-solid fa-chevron-right"></i>';
    nextBtn.onclick = () => gridEl.scrollBy({ left: 800, behavior: 'smooth' });

    wrapper.appendChild(prevBtn);
    wrapper.appendChild(nextBtn);

    // Apply drag scroll to slider
    enableDragScroll(gridEl);
}

function createCustomSliderSection(title, id, iconClass, seeMoreUrl) {
    const section = document.createElement('section');
    section.className = 'content-section custom-slider-section';
    section.id = id + 'Section';
    section.innerHTML = `
        <div class="section-header">
            <h2><i class="${iconClass}"></i> ${title}</h2>
            ${seeMoreUrl ? `<a href="${seeMoreUrl}" class="see-more-btn">See More <i class="fa-solid fa-arrow-right"></i></a>` : ''}
        </div>
        <div class="content-slider" id="${id}Grid">
            <div class="card-shimmer"></div>
            <div class="card-shimmer"></div>
            <div class="card-shimmer"></div>
            <div class="card-shimmer"></div>
            <div class="card-shimmer"></div>
        </div>
    `;
    return section;
}

async function loadSliderData(gridEl, filterPayload) {
    const result = await apiPost('/api/filter', filterPayload);
    gridEl.innerHTML = '';
    if (result && result.data) {
        const raw = result.data.items || [];
        const subjects = (raw.length > 0 && raw[0].subjects !== undefined)
            ? raw.flatMap(sec => sec.subjects || [])
            : raw;
        subjects.slice(0, 12).forEach(item => {
            gridEl.appendChild(createContentCard(item));
        });
    }
    if (gridEl.children.length === 0) {
        gridEl.innerHTML = `<div style="color:var(--text-muted);padding:20px 0;">No content found.</div>`;
    } else {
        setupSliderNavigation(gridEl);
    }
}

async function initCustomCategorySliders() {
    const dynamicSection = document.getElementById('dynamicSection');
    if (!dynamicSection) return;

    const categories = [
        {
            title: 'Bollywood Hits',
            id: 'bollywood',
            iconClass: 'fa-solid fa-fire icon-bollywood',
            seeMoreUrl: '/movies?country=India',
            filter: { country: 'India', genre: '*', year: '*', sort: 'Hottest', subjectType: 1, page: 1, perPage: 12 }
        },
        {
            title: 'Hollywood Favorites',
            id: 'hollywood',
            iconClass: 'fa-solid fa-film icon-hollywood',
            seeMoreUrl: '/movies',
            filter: { country: 'United States', genre: '*', year: '*', sort: 'Hottest', subjectType: 1, page: 1, perPage: 12 }
        },
        {
            title: 'Horror & Thriller',
            id: 'horror',
            iconClass: 'fa-solid fa-ghost icon-horror',
            seeMoreUrl: '/movies?genre=Horror',
            filter: { country: '*', genre: 'Horror', year: '*', sort: 'Hottest', subjectType: 0, page: 1, perPage: 12 }
        }
    ];

    for (const cat of categories) {
        const section = createCustomSliderSection(cat.title, cat.id, cat.iconClass, cat.seeMoreUrl);
        dynamicSection.parentNode.insertBefore(section, dynamicSection);
        const gridEl = section.querySelector(`#${cat.id}Grid`);
        loadSliderData(gridEl, cat.filter);
    }
}

// ==========================================================================
// MOVIES & TV EXPLORATION FILTER PAGE LOGIC
// ==========================================================================
async function initFilterPage() {
    // Read query strings (e.g. pre-selected genre `/movies?genre=Action`)
    const urlParams = new URLSearchParams(window.location.search);
    const genre = urlParams.get("genre");
    const country = urlParams.get("country");
    const year = urlParams.get("year");
    const language = urlParams.get("language");
    const sort = urlParams.get("sort");
    const keyword = urlParams.get("keyword");
    const typeParam = urlParams.get("type");
    
    if (typeParam !== null) {
        state.subjectType = parseInt(typeParam);
    }

    if (keyword) {
        document.getElementById("searchInput").value = keyword;
        state.currentQuery = keyword;

        // UI adjustments for search mode
        const sidebarActive = document.querySelector(".sidebar .nav-menu li.active");
        if (sidebarActive) {
            sidebarActive.classList.remove("active");
        }
        const mobileActive = document.querySelector(".mobile-navigation-bar a.nav-item.active");
        if (mobileActive) {
            mobileActive.classList.remove("active");
        }
        const filterPanel = document.getElementById("filterPanel");
        if (filterPanel) {
            filterPanel.classList.remove("open");
        }
        const filterToggleBtn = document.getElementById("filterToggleBtn");
        if (filterToggleBtn) {
            filterToggleBtn.classList.remove("active");
        }
        const catTab = document.querySelector(".cat-tab");
        if (catTab) {
            catTab.textContent = `Search Results for "${keyword}"`;
        }
    }

    // Helper to highlight pre-selected filter value from URL
    const setSelectedFilterOption = (containerId, val) => {
        const container = document.getElementById(containerId);
        if (!container) return;
        const options = container.querySelectorAll(".filter-opt");
        options.forEach(opt => {
            if (opt.getAttribute("data-value") === val) {
                options.forEach(o => o.classList.remove("active"));
                opt.classList.add("active");
            }
        });
    };

    if (genre) {
        state.activeGenre = genre;
        setSelectedFilterOption("filterGenreOpts", genre);
    }
    if (country) {
        state.activeCountry = country;
        setSelectedFilterOption("filterCountryOpts", country);
    }
    if (year) {
        state.activeYear = year;
        setSelectedFilterOption("filterYearOpts", year);
    }
    if (language) {
        state.activeLang = language;
        setSelectedFilterOption("filterLangOpts", language);
    }
    if (sort) {
        state.activeSort = sort;
        setSelectedFilterOption("filterSortOpts", sort);
    }

    // Helper to setup dynamic tag filter click handlers (auto-apply)
    const setupFilterOptions = (containerId, stateKey) => {
        const container = document.getElementById(containerId);
        if (!container) return;
        const options = container.querySelectorAll(".filter-opt");
        options.forEach(opt => {
            opt.onclick = () => {
                options.forEach(o => o.classList.remove("active"));
                opt.classList.add("active");
                state[stateKey] = opt.getAttribute("data-value");
                state.currentPage = 1;
                loadSearchResults();
            };
        });
    };

    setupFilterOptions("filterGenreOpts", "activeGenre");
    setupFilterOptions("filterCountryOpts", "activeCountry");
    setupFilterOptions("filterYearOpts", "activeYear");
    setupFilterOptions("filterLangOpts", "activeLang");
    setupFilterOptions("filterSortOpts", "activeSort");

    // Infinite scroll listener - highly robust across devices/browsers
    const handleScroll = () => {
        if (state.loadingMore || !state.hasMore) return;
        
        const scrollHeight = document.documentElement.scrollHeight || document.body.scrollHeight;
        const scrollTop = window.pageYOffset || document.documentElement.scrollTop || document.body.scrollTop;
        const clientHeight = window.innerHeight || document.documentElement.clientHeight || document.body.clientHeight;
        
        if (scrollHeight - (scrollTop + clientHeight) < 400) {
            state.currentPage++;
            loadSearchResults(true);
        }
    };
    window.addEventListener("scroll", handleScroll, { passive: true });
    window.addEventListener("touchmove", handleScroll, { passive: true });

    loadSearchResults();
}

// Load List data
async function loadSearchResults(isAppend = false) {
    if (isAppend) {
        state.loadingMore = true;
        const loader = document.getElementById("infiniteLoader");
        if (loader) loader.style.display = "block";
    } else {
        showShimmers(true);
    }
    
    let endpoint = "/api/search";
    let payload = {};

    if (state.currentQuery) {
        endpoint = "/api/search";
        payload = {
            keyword: state.currentQuery,
            page: state.currentPage,
            perPage: 20,
            subjectType: state.subjectType,
            genre: state.activeGenre,
            country: state.activeCountry,
            year: state.activeYear,
            language: state.activeLang,
            sort: state.activeSort
        };
    } else {
        endpoint = "/api/filter";
        payload = {
            genre: state.activeGenre,
            country: state.activeCountry,
            year: state.activeYear,
            language: state.activeLang,
            sort: state.activeSort,
            subjectType: state.subjectType,
            page: state.currentPage,
            perPage: 20
        };
    }

    const result = await apiPost(endpoint, payload);
    
    if (result && result.data) {
        const items = result.data.items || (result.data.results && result.data.results[0] && result.data.results[0].subjects) || [];
        renderExploreGrid(items, isAppend);
        
        // Handle pagination controls
        state.hasMore = result.data.pager ? result.data.pager.hasMore : false;
        
        const prevBtn = document.getElementById("prevPageBtn");
        const nextBtn = document.getElementById("nextPageBtn");
        const pageIndicator = document.getElementById("pageIndicator");
        if (prevBtn) prevBtn.disabled = state.currentPage <= 1;
        if (nextBtn) nextBtn.disabled = !state.hasMore;
        if (pageIndicator) pageIndicator.textContent = `Page ${state.currentPage}`;
    } else {
        state.hasMore = false;
        if (!isAppend) {
            const grid = document.getElementById("dynamicGrid");
            if (grid) grid.innerHTML = `<div class="no-results"><i class="fa-solid fa-face-frown"></i> No results found. Try adjusting filters.</div>`;
        }
        const prevBtn = document.getElementById("prevPageBtn");
        const nextBtn = document.getElementById("nextPageBtn");
        if (prevBtn) prevBtn.disabled = true;
        if (nextBtn) nextBtn.disabled = true;
    }
    
    if (isAppend) {
        state.loadingMore = false;
        const loader = document.getElementById("infiniteLoader");
        if (loader) loader.style.display = "none";
    } else {
        showShimmers(false);
    }
}

function renderExploreGrid(items, isAppend = false) {
    const grid = document.getElementById("dynamicGrid");
    if (!grid) return;

    let existingIds = new Set();
    if (!isAppend) {
        grid.innerHTML = "";
    } else {
        grid.querySelectorAll(".content-card").forEach(c => {
            if (c.dataset.id) existingIds.add(c.dataset.id);
        });
    }

    let subjects = [];
    if (items && items.length > 0) {
        if (items[0].subjects !== undefined) {
            items.forEach(sec => {
                if (sec.subjects && sec.type !== "BANNER" && sec.type !== "FILTER") {
                    subjects = subjects.concat(sec.subjects);
                }
            });
            const seen = new Set();
            subjects = subjects.filter(sub => {
                const duplicate = seen.has(sub.subjectId) || existingIds.has(String(sub.subjectId));
                seen.add(sub.subjectId);
                return !duplicate;
            });
        } else {
            subjects = items.filter(sub => !existingIds.has(String(sub.subjectId)));
        }
    }

    if (!subjects || subjects.length === 0) {
        if (!isAppend) {
            grid.innerHTML = `<div class="no-results"><i class="fa-solid fa-face-frown"></i> No results found. Try adjusting your query.</div>`;
        }
        return;
    }

    // Update Section Title
    if (!isAppend) {
        let titleText = "Latest Releases";
        if (state.currentQuery) {
            titleText = `Search Results for "${state.currentQuery}"`;
        } else if (routes.isMovies) {
            titleText = "Movies Feed";
        } else if (routes.isTv) {
            titleText = "TV Shows Feed";
        }
        
        const titleEl = document.getElementById("dynamicTitle");
        if (titleEl) titleEl.textContent = titleText;
    }

    subjects.forEach(item => {
        const card = createContentCard(item);
        grid.appendChild(card);
    });
}

function createContentCard(item) {
    const card = document.createElement("a");
    card.className = "content-card";
    card.dataset.id = item.subjectId;
    const pathValue = item.detailPath || '';
    const idParam = item.subjectId ? `?id=${encodeURIComponent(item.subjectId)}` : '';
    card.href = (item.subjectType === 2 ? '/tv/' : '/movie/') + encodeURIComponent(pathValue) + idParam;
    card.style.textDecoration = 'none';

    const title = item.title || 'Unknown Title';
    const coverUrl = item.cover ? (item.cover.url || item.cover) : "/default-cover.png";
    const rating = item.imdbRatingValue || '7.5';
    const releaseDate = item.releaseDate || '2026';
    const releaseYear = releaseDate.split('-')[0];
    
    let langBadge = "";
    if (title.toLowerCase().includes("[hindi]")) {
        langBadge = `<span class="card-badge lang">Hindi</span>`;
    } else if (title.toLowerCase().includes("[bengali]")) {
        langBadge = `<span class="card-badge lang">Bengali</span>`;
    }

    const upcomingBadge = item.hasResource === false ? `<span class="card-badge upcoming" style="background:#e11d48;color:#fff;">Upcoming</span>` : "";
    const camBadge = item.isCam ? `<span class="card-badge cam">CAM</span>` : "";
    const typeText = item.subjectType === 2 ? "TV Show" : "Movie";

    card.innerHTML = `
        <div class="card-poster">
            <img src="${coverUrl}" alt="${title}" onerror="this.onerror=null; this.src='/default-cover.png';" loading="lazy">
            <div class="card-badges">
                ${upcomingBadge}
                ${langBadge}
                ${camBadge}
            </div>
            <div class="card-rating"><i class="fa-solid fa-star"></i> ${rating}</div>
        </div>
        <div class="card-info">
            <h3 class="card-title" title="${title}">${title}</h3>
            <div class="card-meta">
                <span>${releaseYear}</span>
                <span>${typeText}</span>
            </div>
        </div>
    `;
    return card;
}

function showShimmers(show) {
    const trendingSection = document.getElementById('trendingSection');
    const dynamicGrid = document.getElementById('dynamicGrid');
    const customSections = document.querySelectorAll('.custom-slider-section');

    if (show) {
        if (trendingSection) trendingSection.style.display = 'none';
        customSections.forEach(s => s.style.display = 'none');
        if (dynamicGrid) {
            dynamicGrid.innerHTML = `
                <div class="card-shimmer"></div>
                <div class="card-shimmer"></div>
                <div class="card-shimmer"></div>
                <div class="card-shimmer"></div>
                <div class="card-shimmer"></div>
                <div class="card-shimmer"></div>
                <div class="card-shimmer"></div>
                <div class="card-shimmer"></div>
            `;
        }
    } else {
        if (trendingSection) trendingSection.style.display = 'block';
        customSections.forEach(s => s.style.display = 'block');
    }
}

// ==========================================================================
// WATCH / DEDICATED PLAYER PAGE LOGIC
// ==========================================================================
function triggerAutoplay(player) {
    if (!player) return;
    player.volume = 1.0;
    player.muted = false;
    player.play().then(() => {
        console.log("[Autoplay] Played successfully unmuted");
    }).catch(e => {
        console.log("[Autoplay] Unmuted playback prevented, attempting muted play", e);
        player.muted = true;
        player.volume = 0;
        player.play().then(() => {
            console.log("[Autoplay] Played successfully muted");
        }).catch(err => {
            console.log("[Autoplay] Muted playback also prevented", err);
            const loaderEl = document.getElementById("playerLoaderOverlay");
            if (loaderEl) {
                const statusTxt = loaderEl.querySelector("span");
                if (statusTxt) statusTxt.innerHTML = '<i class="fa-solid fa-circle-play" style="margin-right: 8px; font-size: 1.2em;"></i> Click to Play';
                loaderEl.style.background = "transparent";
                loaderEl.classList.add("visible");
            }
        });
    });
}

async function initDetailsPage() {
    // Extract slug from /movie/<slug> or /tv/<slug>
    const pathParts = window.location.pathname.split("/");
    const detailPath = decodeURIComponent(pathParts[pathParts.length - 1] || "");
    const urlParams = new URLSearchParams(window.location.search);
    const reqSeason = urlParams.get("season") ? parseInt(urlParams.get("season")) : 1;
    const reqEpisode = urlParams.get("episode") ? parseInt(urlParams.get("episode")) : 1;
    const reqId = urlParams.get("id") || "";

    if (!detailPath) {
        window.location.href = "/";
        return;
    }

    const loading = document.getElementById("watchPageLoading");
    const content = document.getElementById("watchWrapper");

    state.selectedSeason = reqSeason;
    state.selectedEpisode = reqEpisode;

    // Fetch Details
    const result = await apiGet(`/api/detail?detailPath=${encodeURIComponent(detailPath)}&subjectId=${encodeURIComponent(reqId)}`);
    if (result && result.data) {
        const detail = result.data;
        const subjectId = detail.subjectId;
        state.selectedSubject = detail;

        // Set Details UI
        const detailsTitle = document.getElementById("detailsTitle");
        const detailsPoster = document.getElementById("detailsPoster");
        const detailsHeroBackdrop = document.getElementById("detailsHeroBackdrop");
        const detailsRating = document.getElementById("detailsRating");
        const detailsYear = document.getElementById("detailsYear");
        const detailsCountry = document.getElementById("detailsCountry");
        const detailsDuration = document.getElementById("detailsDuration");
        const detailsGenresList = document.getElementById("detailsGenresList");
        const watchDescription = document.getElementById("watchDescription");

        if (detailsTitle) detailsTitle.textContent = detail.title;
        if (detailsPoster) detailsPoster.src = detail.cover ? (detail.cover.url || detail.cover) : "/default-cover.png";
        if (detailsHeroBackdrop) {
            const backdropUrl = detail.cover ? (detail.cover.url || detail.cover) : "";
            detailsHeroBackdrop.style.backgroundImage = `url('${backdropUrl}')`;
        }
        if (detailsRating) detailsRating.innerHTML = `<i class="fa-solid fa-star"></i> ${detail.imdbRatingValue || '--'}`;
        if (detailsYear) detailsYear.textContent = detail.releaseDate ? detail.releaseDate.split('-')[0] : '----';
        if (detailsCountry) detailsCountry.textContent = detail.countryName || 'USA';
        if (detailsDuration) detailsDuration.textContent = detail.duration || '-- min';
        
        if (watchDescription) watchDescription.textContent = detail.description || "No description available.";

        // Render genres
        if (detailsGenresList && detail.genre) {
            const genres = Array.isArray(detail.genre) ? detail.genre : detail.genre.split(',');
            detailsGenresList.innerHTML = genres.map(g => `<span>${g.trim()}</span>`).join('');
        }

        // Watch Online button click handler
        const btnDetailsPlay = document.getElementById("btnDetailsPlay");
        if (btnDetailsPlay) {
            btnDetailsPlay.onclick = () => {
                const typeSegment = isTvSubject(detail) ? 'tv' : 'movie';
                let url = `/watch/${typeSegment}/${encodeURIComponent(detailPath)}`;
                const q = [];
                if (isTvSubject(detail)) {
                    q.push(`season=${state.selectedSeason}`);
                    q.push(`episode=${state.selectedEpisode}`);
                }
                const sid = subjectId || (detail && detail.subjectId) || reqId;
                if (sid) {
                    q.push(`id=${encodeURIComponent(sid)}`);
                }
                if (q.length > 0) {
                    url += `?${q.join("&")}`;
                }
                window.location.href = url;
            };
        }

        // TV / Show episode selector
        const isTv = isTvSubject(detail);
        const tvSelector = document.getElementById("watchTvSelector");
        
        if (isTv) {
            if (tvSelector) tvSelector.style.display = "block";
            await loadSeasonEpisodes(subjectId, detailPath);
        } else {
            if (tvSelector) tvSelector.style.display = "none";
        }

        // Setup Dub / Language selector dropdown
        const dubSelectorGroup = document.getElementById("watchDubSelectorGroup");
        const dubSelect = document.getElementById("dubLanguageSelect");
        if (dubSelectorGroup && dubSelect) {
            if (detail.dubs && detail.dubs.length > 1) {
                dubSelectorGroup.style.display = "block";
                dubSelect.innerHTML = "";
                
                detail.dubs.forEach(dub => {
                    const opt = document.createElement("option");
                    opt.value = dub.subjectId;
                    opt.textContent = dub.lanName || (dub.original ? "Original Audio" : (dub.lanCode ? dub.lanCode.toUpperCase() : "Unknown Dub"));
                    if (String(dub.subjectId) === String(subjectId)) {
                        opt.selected = true;
                    }
                    opt.dataset.path = dub.detailPath || "";
                    dubSelect.appendChild(opt);
                });
                
                dubSelect.onchange = (e) => {
                    const selectedOpt = dubSelect.options[dubSelect.selectedIndex];
                    const targetSubjectId = selectedOpt.value;
                    const targetDetailPath = selectedOpt.dataset.path || "";
                    
                    let newUrl = `/details?id=${targetSubjectId}&path=${encodeURIComponent(targetDetailPath)}`;
                    if (state.selectedSubject && state.selectedSubject.subjectType === 2) {
                        newUrl += `&season=${state.selectedSeason}&episode=${state.selectedEpisode}`;
                    }
                    window.location.href = newUrl;
                };
            } else {
                dubSelectorGroup.style.display = "none";
            }
        }

        // Setup Action Buttons
        const actionAddList = document.getElementById("actionAddList");
        if (actionAddList) {
            actionAddList.onclick = () => {
                alert("Added to list!");
            };
        }
        const actionShare = document.getElementById("actionShare");
        if (actionShare) {
            actionShare.onclick = () => {
                navigator.clipboard.writeText(window.location.href);
                alert("Link copied to clipboard!");
            };
        }
        const actionDownload = document.getElementById("actionDownload");
        if (actionDownload) {
            actionDownload.onclick = () => {
                alert("To download, click 'Watch Online' and use the download option on the player page.");
            };
        }
        const actionViewDoc = document.getElementById("actionViewDoc");
        if (actionViewDoc) {
            actionViewDoc.onclick = () => {
                const genresStr = detail.genre ? (Array.isArray(detail.genre) ? detail.genre.join(', ') : detail.genre) : '';
                alert("Title: " + detail.title + "\nYear: " + (detail.releaseDate ? detail.releaseDate.split('-')[0] : '----') + "\nRating: " + (detail.imdbRatingValue || '--') + "\nCountry: " + (detail.countryName || 'USA') + "\nGenre: " + genresStr);
            };
        }

        // Tabs Logic
        const tabBtnForYou = document.getElementById("tabBtnForYou");
        const tabBtnComments = document.getElementById("tabBtnComments");
        const tabPaneForYou = document.getElementById("tabPaneForYou");
        const tabPaneComments = document.getElementById("tabPaneComments");

        if (tabBtnForYou && tabBtnComments && tabPaneForYou && tabPaneComments) {
            tabBtnForYou.onclick = () => {
                tabBtnForYou.classList.add("active");
                tabBtnComments.classList.remove("active");
                tabPaneForYou.classList.add("active");
                tabPaneComments.classList.remove("active");
            };
            tabBtnComments.onclick = () => {
                tabBtnComments.classList.add("active");
                tabBtnForYou.classList.remove("active");
                tabPaneComments.classList.add("active");
                tabPaneForYou.classList.remove("active");
            };
        }

        // Load recommendations
        loadRecommendations(detail);

        if (loading) loading.style.display = "none";
        if (content) content.style.display = "block";
    } else {
        if (loading) loading.innerHTML = "<p><i class='fa-solid fa-triangle-exclamation'></i> Failed to load media details. Go back home.</p>";
    }
}

async function initWatchPage() {
    // Dynamic overflow visibility override to ensure CSS position: sticky functions correctly on mobile
    document.documentElement.style.overflow = 'visible';
    document.documentElement.style.overflowX = 'visible';
    document.body.style.overflow = 'visible';
    document.body.style.overflowX = 'visible';

    // Theater Mode toggle
    const theaterBtn = document.getElementById("theaterModeBtn");
    const theaterIcon = document.getElementById("theaterModeIcon");
    const watchWrapper = document.getElementById("watchWrapper");
    if (theaterBtn && watchWrapper) {
        theaterBtn.onclick = () => {
            watchWrapper.classList.toggle("theater-mode");
            const isTheater = watchWrapper.classList.contains("theater-mode");
            if (theaterIcon) {
                theaterIcon.className = isTheater ? "fa-solid fa-compress" : "fa-solid fa-expand";
            }
            theaterBtn.title = isTheater ? "Exit Theater Mode" : "Theater Mode";
        };
    }

    // Extract slug from /watch/movie/<slug> or /watch/tv/<slug>
    const watchPathParts = window.location.pathname.split("/");
    // [0]="", [1]="watch", [2]="movie"|"tv", [3]=slug
    const watchSlug = decodeURIComponent(watchPathParts[3] || "");
    const watchTypeSegment = watchPathParts[2] || "movie";
    const urlParams = new URLSearchParams(window.location.search);
    let subjectId = urlParams.get("id") || "";
    const tmdbId = urlParams.get("tmdb");
    const isLiveStream = (window.location.pathname.toLowerCase() === "/watch") && (urlParams.get("type") === "sports" || urlParams.get("type") === "tv");
    const type = isLiveStream ? urlParams.get("type") : watchTypeSegment;
    const reqSeason = urlParams.get("season") ? parseInt(urlParams.get("season")) : 1;
    const reqEpisode = urlParams.get("episode") ? parseInt(urlParams.get("episode")) : 1;
    
    const oldStyle = document.getElementById('plyr-live-custom-css');
    if (oldStyle) oldStyle.remove();
    if (isLiveStream) {
        const style = document.createElement('style');
        style.id = 'plyr-live-custom-css';
        style.innerHTML = `
            .plyr__progress, 
            .plyr__controls [data-plyr="rewind"], 
            .plyr__controls [data-plyr="fast-forward"],
            .plyr__time--current,
            .plyr__time--duration {
                display: none !important;
            }
        `;
        document.head.appendChild(style);
    }
    
    if (!isLiveStream && !watchSlug && !tmdbId) {
        window.location.href = "/";
        return;
    }

    // Initialize Plyr player with minimalist modern controls matching reference image
    const isMobile = window.innerWidth <= 768;
    const playerControls = [
        'play', 
        'progress', 
        'current-time', 
        'duration', 
        'mute', 
        'volume',
        'settings', 
        'pip', 
        'fullscreen'
    ];

    playerInstance = new Plyr('#player', {
        controls: playerControls,
        settings: ['quality', 'speed'],
        quality: { default: 0, options: [0, 4320, 2880, 2160, 1440, 1080, 720, 576, 480, 360, 240] },
        keyboard: { global: true, focused: true },
        captions: { active: true, update: true },
        volume: 1,
        muted: false
    });

    // Helper to manage dynamic resolution badge (e.g. 480P / 720P / 1080P) matching Image 2
    window.updateQualityBadgeText = function(q) {
        const badge = document.getElementById('plyrQualityBadge');
        if (!badge) return;
        if (!q || q === 0 || q === '0') {
            if (state.availableResources && state.availableResources.length > 0) {
                const res = state.availableResources.find(r => r.resolution && r.resolution > 0);
                badge.textContent = res ? `${res.resolution}P` : 'Auto';
            } else {
                badge.textContent = 'Auto';
            }
        } else {
            badge.textContent = `${q}P`;
        }
    };

    // Watchlist Bookmark Helper
    window.initWatchlistButton = function() {
        const btn = document.getElementById('watchBookmarkBtn');
        if (!btn) return;

        const updateBtnState = () => {
            const list = JSON.parse(localStorage.getItem('streamfit_watchlist') || '[]');
            const isSaved = list.some(item => (state.selectedSubject && item.id === state.selectedSubject.id) || (state.selectedSubject && item.id === state.selectedSubject.subjectId));
            if (isSaved) {
                btn.innerHTML = '<i class="fa-solid fa-bookmark" style="color:#00e676;"></i> <span>Saved</span>';
                btn.classList.add('active');
            } else {
                btn.innerHTML = '<i class="fa-regular fa-bookmark"></i> <span>Watchlist</span>';
                btn.classList.remove('active');
            }
        };

        updateBtnState();

        btn.onclick = () => {
            if (!state.selectedSubject) return;
            let list = JSON.parse(localStorage.getItem('streamfit_watchlist') || '[]');
            const subj = state.selectedSubject;
            const targetId = subj.id || subj.subjectId;
            const idx = list.findIndex(item => item.id === targetId);
            if (idx !== -1) {
                list.splice(idx, 1);
            } else {
                list.unshift({
                    id: targetId,
                    title: subj.title,
                    poster: subj.coverImage || subj.cover?.url,
                    rating: subj.imdbRatingValue || subj.rating,
                    year: subj.releaseDate ? subj.releaseDate.split('-')[0] : subj.year,
                    type: (subj.seNum > 0 || subj.subjectType === 2) ? 'tv' : 'movie'
                });
            }
            localStorage.setItem('streamfit_watchlist', JSON.stringify(list));
            updateBtnState();
        };
    };

    // Native Share / Clipboard Share Helper
    window.initShareButton = function() {
        const btn = document.getElementById('watchShareBtn');
        if (!btn || btn._wired) return;
        btn._wired = true;
        btn.onclick = async () => {
            const shareData = {
                title: document.title || 'Streamfit',
                text: `Watch ${document.getElementById('watchMetaTitle')?.textContent || 'this title'} online for free!`,
                url: window.location.href
            };
            if (navigator.share && /mobile|android|iphone|ipad/i.test(navigator.userAgent)) {
                try {
                    await navigator.share(shareData);
                    return;
                } catch (e) {}
            }
            try {
                await navigator.clipboard.writeText(window.location.href);
                btn.innerHTML = '<i class="fa-solid fa-check" style="color:#00e676;"></i> <span>Copied!</span>';
                setTimeout(() => {
                    btn.innerHTML = '<i class="fa-solid fa-share-nodes"></i> <span>Share</span>';
                }, 2000);
            } catch (err) {
                prompt('Copy this link:', window.location.href);
            }
        };
    };

    window.setupProgressDragHandler = function(progressEl) {
        if (!progressEl || progressEl._dragBound) return;
        progressEl._dragBound = true;

        const rangeInput = progressEl.querySelector('input[type="range"]');
        let isDragging = false;

        const getPercent = (clientX) => {
            const rect = progressEl.getBoundingClientRect();
            const x = clientX - rect.left;
            return Math.max(0, Math.min(1, x / rect.width));
        };

        const applySeek = (percent, commit = false) => {
            if (!playerInstance || isNaN(playerInstance.duration) || playerInstance.duration <= 0) return;
            const targetTime = percent * playerInstance.duration;
            if (rangeInput) {
                rangeInput.value = percent * 100;
                rangeInput.style.setProperty('--value', `${(percent * 100).toFixed(2)}%`);
            }
            if (commit) {
                playerInstance.currentTime = targetTime;
            } else {
                const tooltip = progressEl.querySelector('.plyr__tooltip');
                if (tooltip) {
                    const mins = Math.floor(targetTime / 60);
                    const secs = Math.floor(targetTime % 60);
                    tooltip.textContent = `${mins}:${secs < 10 ? '0' : ''}${secs}`;
                    tooltip.style.left = `${(percent * 100).toFixed(2)}%`;
                }
            }
        };

        progressEl.addEventListener('touchstart', (e) => {
            if (e.touches.length !== 1) return;
            isDragging = true;
            progressEl.classList.add('is-dragging');
            const pct = getPercent(e.touches[0].clientX);
            applySeek(pct, false);
        }, { passive: false });

        window.addEventListener('touchmove', (e) => {
            if (!isDragging || e.touches.length !== 1) return;
            e.preventDefault();
            const pct = getPercent(e.touches[0].clientX);
            applySeek(pct, false);
        }, { passive: false });

        window.addEventListener('touchend', (e) => {
            if (!isDragging) return;
            isDragging = false;
            progressEl.classList.remove('is-dragging');
            if (e.changedTouches && e.changedTouches.length > 0) {
                const pct = getPercent(e.changedTouches[0].clientX);
                applySeek(pct, true);
            }
        });

        window.addEventListener('touchcancel', () => {
            if (isDragging) {
                isDragging = false;
                progressEl.classList.remove('is-dragging');
            }
        });

        // Mouse drag support for desktop
        progressEl.addEventListener('mousedown', (e) => {
            if (e.button !== 0) return;
            isDragging = true;
            progressEl.classList.add('is-dragging');
            const pct = getPercent(e.clientX);
            applySeek(pct, false);

            const onMouseMove = (ev) => {
                if (!isDragging) return;
                const p = getPercent(ev.clientX);
                applySeek(p, false);
            };

            const onMouseUp = (ev) => {
                if (!isDragging) return;
                isDragging = false;
                progressEl.classList.remove('is-dragging');
                window.removeEventListener('mousemove', onMouseMove);
                window.removeEventListener('mouseup', onMouseUp);
                const p = getPercent(ev.clientX);
                applySeek(p, true);
            };

            window.addEventListener('mousemove', onMouseMove);
            window.addEventListener('mouseup', onMouseUp);
        });
    };

    // Episode navigation helpers
    window.playNextEpisode = function() {
        const episodeButtons = Array.from(document.querySelectorAll("#watchEpisodeGrid .episode-btn"));
        const currentIdx = episodeButtons.findIndex(b => b.classList.contains("active"));
        if (currentIdx !== -1 && currentIdx < episodeButtons.length - 1) {
            episodeButtons[currentIdx + 1].click();
        } else {
            const seasonTabs = Array.from(document.querySelectorAll("#watchSeasonTabs .season-tab"));
            const currentSeasonIdx = seasonTabs.findIndex(t => t.classList.contains("active"));
            if (currentSeasonIdx !== -1 && currentSeasonIdx < seasonTabs.length - 1) {
                seasonTabs[currentSeasonIdx + 1].click();
                setTimeout(() => {
                    const firstEp = document.querySelector("#watchEpisodeGrid .episode-btn");
                    if (firstEp) firstEp.click();
                }, 500);
            }
        }
    };

    window.playPreviousEpisode = function() {
        const episodeButtons = Array.from(document.querySelectorAll("#watchEpisodeGrid .episode-btn"));
        const currentIdx = episodeButtons.findIndex(b => b.classList.contains("active"));
        if (currentIdx > 0) {
            episodeButtons[currentIdx - 1].click();
        }
    };

    // Fullscreen Aspect Ratio Toggle (Fit vs Zoom Fill)
    window.toggleAspectRatio = function() {
        const plyrContainer = playerInstance?.elements?.container || document.querySelector('.plyr');
        const aspectBtn = document.getElementById('fsAspectBtn');
        if (!plyrContainer) return;

        const isFill = plyrContainer.classList.toggle('video-aspect-fill');
        if (aspectBtn) {
            const text = aspectBtn.querySelector('.action-btn-text');
            if (text) text.textContent = isFill ? 'Fill' : 'Fit';
            aspectBtn.title = isFill ? 'Aspect Ratio: Zoom Fill (Click for Fit)' : 'Aspect Ratio: Fit (Click for Fill)';
            if (isFill) {
                aspectBtn.classList.add('active');
            } else {
                aspectBtn.classList.remove('active');
            }
        }
    };


    // Fullscreen Episodes Drawer
    window.openFsEpisodesDrawer = function() {
        const drawer = document.getElementById('fsEpisodesDrawer');
        const drawerGrid = document.getElementById('fsDrawerEpisodeGrid');
        const seasonPicker = document.getElementById('fsDrawerSeasonPicker');
        if (!drawer || !drawerGrid) return;

        // Render season buttons inside drawer if multiple seasons exist
        if (seasonPicker) {
            seasonPicker.innerHTML = '';
            if (state.loadedSeasons && state.loadedSeasons.length > 1) {
                seasonPicker.style.display = 'flex';
                state.loadedSeasons.forEach(season => {
                    const pill = document.createElement('button');
                    const isActive = season.se === state.selectedSeason;
                    pill.className = `season-tab ${isActive ? 'active' : ''}`;
                    pill.textContent = `S${season.se}`;
                    pill.onclick = () => {
                        const seasonSelect = document.getElementById('seasonSelect');
                        if (seasonSelect) {
                            seasonSelect.value = season.se;
                            seasonSelect.dispatchEvent(new Event('change'));
                        }
                        setTimeout(() => window.openFsEpisodesDrawer(), 250);
                    };
                    seasonPicker.appendChild(pill);
                });
            } else {
                seasonPicker.style.display = 'none';
            }
        }

        drawerGrid.innerHTML = '';
        const mainButtons = document.querySelectorAll('#watchEpisodeGrid .episode-btn');
        mainButtons.forEach(btn => {
            const cloned = document.createElement('button');
            cloned.className = btn.className;
            cloned.innerHTML = btn.innerHTML;
            cloned.onclick = () => {
                btn.click();
                drawer.classList.remove('open');
            };
            drawerGrid.appendChild(cloned);
        });

        drawer.classList.add('open');
        setTimeout(() => {
            const active = drawerGrid.querySelector('.episode-btn.active');
            if (active) active.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }, 120);
    };

    // Center Play/Pause Splash feedback
    window.triggerCenterSplash = function(type) {
        const splash = document.getElementById('centerPlaybackSplash');
        if (!splash) return;
        splash.innerHTML = type === 'pause' ? '<i class="fa-solid fa-pause"></i>' : '<i class="fa-solid fa-play"></i>';
        splash.classList.remove('show');
        void splash.offsetWidth;
        splash.classList.add('show');
        clearTimeout(splash._timer);
        splash._timer = setTimeout(() => {
            splash.classList.remove('show');
        }, 450);
    };

    window.organizePlyrControlsLayout = function() {
        const controls = document.querySelector('.plyr__controls');
        if (!controls) return;

        // 0. Ensure all overlays are inside player container so they are visible in fullscreen
        const plyrContainer = playerInstance?.elements?.container || document.querySelector('.plyr');
        if (plyrContainer) {
            const overlays = [
                document.getElementById('seekFeedbackLeft'),
                document.getElementById('seekFeedbackRight'),
                document.getElementById('playerLoaderOverlay'),
                document.getElementById('playerHeaderOverlay'),
                document.getElementById('nextEpisodeOverlay'),
                document.getElementById('centerPlaybackSplash'),
                document.getElementById('fsEpisodesDrawer')
            ];
            overlays.forEach(el => {
                if (el && el.parentElement !== plyrContainer) {
                    plyrContainer.appendChild(el);
                }
            });
        }

        // 1. Progress Bar: Must always be the first child (Top Row)
        const progress = controls.querySelector('.plyr__progress');
        if (progress) {
            if (controls.firstElementChild !== progress) {
                controls.insertBefore(progress, controls.firstElementChild);
            }
            window.setupProgressDragHandler(progress);
        }

        // 2. Bottom Controls Row Container
        let bottomRow = controls.querySelector('.plyr__controls-bottom-row');
        if (!bottomRow) {
            bottomRow = document.createElement('div');
            bottomRow.className = 'plyr__controls-bottom-row';

            const leftGroup = document.createElement('div');
            leftGroup.className = 'plyr__controls-left-group';

            const rightGroup = document.createElement('div');
            rightGroup.className = 'plyr__controls-right-group';

            bottomRow.appendChild(leftGroup);
            bottomRow.appendChild(rightGroup);
            controls.appendChild(bottomRow);
        }

        const leftGroup = bottomRow.querySelector('.plyr__controls-left-group');
        const rightGroup = bottomRow.querySelector('.plyr__controls-right-group');

        // Move Left Group items: Play, Next-Ep, Volume, Current-time, Duration
        const playBtn = controls.querySelector(':scope > [data-plyr="play"]') || controls.querySelector('[data-plyr="play"]');
        const vol = controls.querySelector(':scope > .plyr__volume') || controls.querySelector('.plyr__volume');
        const curTime = controls.querySelector(':scope > .plyr__time--current') || controls.querySelector('.plyr__time--current');
        const durTime = controls.querySelector(':scope > .plyr__time--duration') || controls.querySelector('.plyr__time--duration');

        if (playBtn && playBtn.parentElement !== leftGroup) leftGroup.appendChild(playBtn);

        // Next Episode Button in Player Control Bar
        const isTv = state.selectedSubject && (state.selectedSubject.seNum > 0 || state.selectedSubject.subjectType === 2);
        let plyrNext = controls.querySelector('#plyrNextBtn');
        if (isTv) {
            if (!plyrNext) {
                plyrNext = document.createElement('button');
                plyrNext.type = 'button';
                plyrNext.className = 'plyr__control plyr__control--next';
                plyrNext.id = 'plyrNextBtn';
                plyrNext.title = 'Next Episode';
                plyrNext.innerHTML = '<i class="fa-solid fa-forward-step" style="font-size:14px;"></i>';
                plyrNext.onclick = (e) => {
                    e.stopPropagation();
                    window.playNextEpisode();
                };
            }
            if (playBtn && playBtn.nextElementSibling !== plyrNext) {
                leftGroup.insertBefore(plyrNext, playBtn.nextElementSibling);
            }
        } else if (plyrNext) {
            plyrNext.remove();
        }

        if (vol && vol.parentElement !== leftGroup) leftGroup.appendChild(vol);

        // Wire Volume Range Slider
        const volInput = (vol || controls).querySelector('input[data-plyr="volume"]');
        if (volInput) {
            const currentVol = playerInstance ? (playerInstance.muted ? 0 : Math.round((playerInstance.volume ?? 1) * 100)) : 100;
            volInput.style.setProperty('--value', `${currentVol}%`);
            if (!volInput._wired) {
                volInput._wired = true;
                const updateVolVar = () => {
                    const val = Math.round(Number(volInput.value) * 100);
                    volInput.style.setProperty('--value', `${val}%`);
                };
                volInput.addEventListener('input', updateVolVar);
                volInput.addEventListener('change', updateVolVar);
            }
        }

        if (curTime && curTime.parentElement !== leftGroup) leftGroup.appendChild(curTime);
        if (durTime && durTime.parentElement !== leftGroup) leftGroup.appendChild(durTime);

        // Quality Badge
        let badge = document.getElementById('plyrQualityBadge');
        const settingsBtn = document.querySelector('.plyr__controls [data-plyr="settings"]');
        if (!badge) {
            badge = document.createElement('button');
            badge.type = 'button';
            badge.className = 'plyr__control plyr__custom-quality-badge';
            badge.id = 'plyrQualityBadge';
            badge.textContent = 'Auto';
            badge.title = 'Video Quality';
            badge.onclick = (e) => {
                e.stopPropagation();
                if (settingsBtn) settingsBtn.click();
            };
        }

        const menu = controls.querySelector(':scope > .plyr__menu') || controls.querySelector('.plyr__menu');
        const captions = controls.querySelector(':scope > [data-plyr="captions"]') || controls.querySelector('[data-plyr="captions"]');
        const pip = controls.querySelector(':scope > [data-plyr="pip"]') || controls.querySelector('[data-plyr="pip"]');
        const fs = controls.querySelector(':scope > [data-plyr="fullscreen"]') || controls.querySelector('[data-plyr="fullscreen"]');

        if (badge && badge.parentElement !== rightGroup) rightGroup.appendChild(badge);
        if (menu && menu.parentElement !== rightGroup) rightGroup.appendChild(menu);
        if (captions && captions.parentElement !== rightGroup) rightGroup.appendChild(captions);
        if (pip && pip.parentElement !== rightGroup) rightGroup.appendChild(pip);
        if (fs && fs.parentElement !== rightGroup) rightGroup.appendChild(fs);

        if (playerInstance) {
            window.updateQualityBadgeText(playerInstance.quality);
        }

        // Wire Header Buttons
        const backBtn = document.getElementById('playerBackBtn');
        if (backBtn && !backBtn._wired) {
            backBtn._wired = true;
            backBtn.onclick = () => {
                if (playerInstance && playerInstance.fullscreen && playerInstance.fullscreen.active) {
                    playerInstance.fullscreen.exit();
                } else {
                    window.history.back();
                }
            };
        }
        const aspectBtn = document.getElementById('fsAspectBtn');
        if (aspectBtn && !aspectBtn._wired) {
            aspectBtn._wired = true;
            aspectBtn.onclick = () => window.toggleAspectRatio();
        }
        const fsEpBtn = document.getElementById('fsEpisodesBtn');
        if (fsEpBtn && !fsEpBtn._wired) {
            fsEpBtn._wired = true;
            fsEpBtn.onclick = () => window.openFsEpisodesDrawer();
        }
        const fsEpClose = document.getElementById('fsEpisodesDrawerClose');
        if (fsEpClose && !fsEpClose._wired) {
            fsEpClose._wired = true;
            fsEpClose.onclick = () => {
                const d = document.getElementById('fsEpisodesDrawer');
                if (d) d.classList.remove('open');
            };
        }
    };

    window.ensureQualityBadge = window.organizePlyrControlsLayout;

    // Double Tap Seek with Cumulative Counter
    window.setupDoubleTapSeek = function() {
        const plyrContainer = playerInstance?.elements?.container || document.querySelector('.plyr');
        const wrapper = document.querySelector('.player-wrapper');
        const targets = [plyrContainer, wrapper].filter(Boolean);

        const fbLeft = document.getElementById('seekFeedbackLeft');
        const fbRight = document.getElementById('seekFeedbackRight');
        let seekTimerLeft = null;
        let seekTimerRight = null;
        let leftAccumulator = 0;
        let rightAccumulator = 0;

        const triggerSeek = (direction) => {
            if (!playerInstance) return;
            if (direction === 'left') {
                leftAccumulator += 10;
                playerInstance.currentTime = Math.max(0, playerInstance.currentTime - 10);
                if (fbLeft) {
                    const txt = fbLeft.querySelector('.seek-seconds-text') || fbLeft.querySelector('span');
                    if (txt) txt.textContent = `-${leftAccumulator}s`;
                    fbLeft.classList.add('active');
                    clearTimeout(seekTimerLeft);
                    seekTimerLeft = setTimeout(() => {
                        fbLeft.classList.remove('active');
                        leftAccumulator = 0;
                    }, 650);
                }
            } else {
                rightAccumulator += 10;
                playerInstance.currentTime = Math.min(playerInstance.duration || 0, playerInstance.currentTime + 10);
                if (fbRight) {
                    const txt = fbRight.querySelector('.seek-seconds-text') || fbRight.querySelector('span');
                    if (txt) txt.textContent = `+${rightAccumulator}s`;
                    fbRight.classList.add('active');
                    clearTimeout(seekTimerRight);
                    seekTimerRight = setTimeout(() => {
                        fbRight.classList.remove('active');
                        rightAccumulator = 0;
                    }, 650);
                }
            }
        };

        targets.forEach(targetEl => {
            if (targetEl._seekBound) return;
            targetEl._seekBound = true;

            let lastTapTime = 0;
            targetEl.addEventListener('touchstart', (e) => {
                if (e.touches.length !== 1) return;
                if (e.target.closest('.plyr__controls') || e.target.closest('.player-header-overlay') || e.target.closest('.plyr__menu') || e.target.closest('.next-episode-overlay') || e.target.closest('.fs-episodes-drawer')) return;

                const now = Date.now();
                const delay = now - lastTapTime;
                const rect = targetEl.getBoundingClientRect();
                const touchX = e.touches[0].clientX - rect.left;

                if (delay < 350 && delay > 40) {
                    if (touchX < rect.width * 0.45) {
                        triggerSeek('left');
                        e.preventDefault();
                        e.stopPropagation();
                    } else if (touchX > rect.width * 0.55) {
                        triggerSeek('right');
                        e.preventDefault();
                        e.stopPropagation();
                    }
                    lastTapTime = 0;
                    return;
                }
                lastTapTime = now;
            }, { passive: false });

            targetEl.addEventListener('dblclick', (e) => {
                if (e.target.closest('.plyr__controls') || e.target.closest('.player-header-overlay') || e.target.closest('.plyr__menu') || e.target.closest('.next-episode-overlay') || e.target.closest('.fs-episodes-drawer')) return;
                const rect = targetEl.getBoundingClientRect();
                const clickX = e.clientX - rect.left;
                if (clickX < rect.width * 0.45) {
                    triggerSeek('left');
                } else if (clickX > rect.width * 0.55) {
                    triggerSeek('right');
                }
            });
        });
    };

    // Global Keyboard Shortcuts for Watch Page
    window.setupWatchKeyboardShortcuts = function() {
        if (window._watchKeyBound) return;
        window._watchKeyBound = true;

        document.addEventListener('keydown', (e) => {
            const activeEl = document.activeElement;
            if (activeEl && (activeEl.tagName === 'INPUT' || activeEl.tagName === 'TEXTAREA' || activeEl.isContentEditable)) {
                return;
            }
            if (!playerInstance) return;

            const key = e.key.toLowerCase();

            // Space or K: Play / Pause
            if (e.code === 'Space' || key === 'k') {
                e.preventDefault();
                playerInstance.togglePlay();
                return;
            }

            // ArrowRight or L: Seek forward 10s
            if (e.key === 'ArrowRight' || key === 'l') {
                e.preventDefault();
                playerInstance.currentTime = Math.min(playerInstance.duration || 0, playerInstance.currentTime + 10);
                const fbRight = document.getElementById('seekFeedbackRight');
                if (fbRight) {
                    const txt = fbRight.querySelector('.seek-seconds-text') || fbRight.querySelector('span');
                    if (txt) txt.textContent = '+10s';
                    fbRight.classList.add('active');
                    setTimeout(() => fbRight.classList.remove('active'), 550);
                }
                return;
            }

            // ArrowLeft or J: Seek backward 10s
            if (e.key === 'ArrowLeft' || key === 'j') {
                e.preventDefault();
                playerInstance.currentTime = Math.max(0, playerInstance.currentTime - 10);
                const fbLeft = document.getElementById('seekFeedbackLeft');
                if (fbLeft) {
                    const txt = fbLeft.querySelector('.seek-seconds-text') || fbLeft.querySelector('span');
                    if (txt) txt.textContent = '-10s';
                    fbLeft.classList.add('active');
                    setTimeout(() => fbLeft.classList.remove('active'), 550);
                }
                return;
            }

            // F: Fullscreen Toggle
            if (key === 'f') {
                e.preventDefault();
                if (playerInstance.fullscreen) playerInstance.fullscreen.toggle();
                return;
            }

            // M: Mute Toggle
            if (key === 'm') {
                e.preventDefault();
                playerInstance.muted = !playerInstance.muted;
                return;
            }

            // T: Theater Mode Toggle
            if (key === 't') {
                e.preventDefault();
                window.toggleTheaterMode();
                return;
            }

            // N: Next Episode
            if (key === 'n') {
                e.preventDefault();
                window.playNextEpisode();
                return;
            }

            // ArrowUp: Volume up 10%
            if (e.key === 'ArrowUp') {
                e.preventDefault();
                playerInstance.volume = Math.min(1, (playerInstance.volume || 0) + 0.1);
                return;
            }

            // ArrowDown: Volume down 10%
            if (e.key === 'ArrowDown') {
                e.preventDefault();
                playerInstance.volume = Math.max(0, (playerInstance.volume || 0) - 0.1);
                return;
            }
        });
    };

    playerInstance.on('ready', () => {
        window.organizePlyrControlsLayout();
        window.setupDoubleTapSeek();
        window.setupWatchKeyboardShortcuts();
        setTimeout(window.organizePlyrControlsLayout, 50);
        setTimeout(window.organizePlyrControlsLayout, 200);
    });
    playerInstance.on('controlsshown', () => {
        window.organizePlyrControlsLayout();
        window.setupDoubleTapSeek();
    });
    playerInstance.on('canplay', window.organizePlyrControlsLayout);
    playerInstance.on('play', () => window.triggerCenterSplash('play'));
    playerInstance.on('pause', () => window.triggerCenterSplash('pause'));
    playerInstance.on('volumechange', () => {
        const volInput = document.querySelector('.plyr__controls input[data-plyr="volume"]');
        if (volInput) {
            const val = playerInstance.muted ? 0 : Math.round((playerInstance.volume ?? 1) * 100);
            volInput.style.setProperty('--value', `${val}%`);
            volInput.value = playerInstance.muted ? 0 : (playerInstance.volume ?? 1);
        }
    });

    playerInstance.on('enterfullscreen', () => {
        window.organizePlyrControlsLayout();
        window.setupDoubleTapSeek();
        if (screen.orientation && screen.orientation.lock) {
            screen.orientation.lock('landscape').catch(() => {});
        }
    });
    playerInstance.on('exitfullscreen', () => {
        window.organizePlyrControlsLayout();
        window.setupDoubleTapSeek();
    });

    // Initial triggers to ensure styling applies as soon as DOM mounts
    setTimeout(() => {
        window.organizePlyrControlsLayout();
        window.setupDoubleTapSeek();
    }, 100);
    setTimeout(() => {
        window.organizePlyrControlsLayout();
        window.setupDoubleTapSeek();
    }, 400);
    setTimeout(() => {
        window.organizePlyrControlsLayout();
        window.setupDoubleTapSeek();
    }, 1000);

    // Start background poller to rewrite "0p" label to "Auto" in settings menu and buttons
    setInterval(() => {
        const menuItems = document.querySelectorAll('.plyr__menu__container button[value="0"] span, .plyr__menu__container span.plyr__menu__value');
        menuItems.forEach(el => {
            if (el.textContent.trim() === '0p') {
                el.textContent = 'Auto';
            }
        });
        
        const plyrEls = document.querySelectorAll('.plyr [data-plyr="quality"], .plyr .plyr__menu__value, .plyr button');
        plyrEls.forEach(el => {
            if (el.textContent.trim() === '0p') {
                const span = el.querySelector('span');
                if (span) {
                    span.textContent = 'Auto';
                } else {
                    el.textContent = 'Auto';
                }
            }
        });

        // Keep quality badge present and updated
        if (typeof window.ensureQualityBadge === 'function') {
            window.ensureQualityBadge();
        }
    }, 300);

    // Custom Player Loading Overlay event bindings
    const loaderOverlay = document.getElementById("playerLoaderOverlay");
    
    const showLoader = () => {
        if (loaderOverlay) {
            const statusTxt = loaderOverlay.querySelector("span");
            if (statusTxt) statusTxt.innerHTML = '<i class="fa-solid fa-spinner fa-spin" style="margin-right:8px;"></i>Loading stream...';
            const spinner = loaderOverlay.querySelector(".player-spinner");
            if (spinner) spinner.style.display = "";
            loaderOverlay.style.background = "transparent";
            loaderOverlay.style.pointerEvents = "none";
            loaderOverlay.classList.add("visible");
            const pWrap = document.getElementById("playerWrapperEl");
            if (pWrap) pWrap.classList.add("is-loading");
        }
    };
    
    const hideLoader = () => {
        if (loaderOverlay) {
            const pWrap = document.getElementById("playerWrapperEl");
            if (pWrap) pWrap.classList.remove("is-loading");
            if (playerInstance && playerInstance.muted && !state.userInteracted) {
                const statusTxt = loaderOverlay.querySelector("span");
                if (statusTxt) statusTxt.innerHTML = '<i class="fa-solid fa-volume-high" style="margin-right:8px; font-size: 1.2em;"></i> Click to Unmute';
                loaderOverlay.style.background = "transparent";
                loaderOverlay.classList.add("visible");
            } else {
                loaderOverlay.classList.remove("visible");
            }
        }
    };
    
    if (loaderOverlay) {
        loaderOverlay.style.cursor = "pointer";
        loaderOverlay.onclick = () => {
            state.userInteracted = true;
            if (playerInstance) {
                if (playerInstance.muted) {
                    playerInstance.muted = false;
                    playerInstance.volume = 1.0;
                }
                playerInstance.play().catch(e => console.log("[Player] Overlay play failed:", e));
                hideLoader();
            }
        };
    }

    // Plyr events
    playerInstance.on('waiting', () => {
        showLoader();
        if (userSelectedQuality || isSwitchingQuality) return;
        const now = Date.now();
        if (now - lastBufferingTime < 15000) {
            bufferingCount++;
        } else {
            bufferingCount = 1;
        }
        lastBufferingTime = now;
        if (bufferingCount >= 3) {
            bufferingCount = 0;
            autoDowngradeQuality();
        }
    });
    
    playerInstance.on('qualitychange', (event) => {
        const selectedQ = event.detail.quality;
        if (typeof window.updateQualityBadgeText === 'function') {
            window.updateQualityBadgeText(selectedQ);
        }
        if (!isProgrammaticQualityChange) {
            if (selectedQ === 0) {
                userSelectedQuality = false;
                localStorage.removeItem("streamfit_preferred_quality");
            } else {
                userSelectedQuality = true;
                localStorage.setItem("streamfit_preferred_quality", selectedQ);
            }
        }
    });

    playerInstance.on('seeking', showLoader);
    playerInstance.on('seeked', hideLoader);
    playerInstance.on('playing', hideLoader);
    playerInstance.on('playing', () => {
        if (state.pendingResumeTime !== null) {
            const seekTime = state.pendingResumeTime;
            state.pendingResumeTime = null; // Clear immediately
            
            const duration = playerInstance.duration;
            if (duration && (seekTime / duration) < 0.95) {
                console.log("[Player Resume] Seeking to pending resume time:", seekTime);
                playerInstance.currentTime = seekTime;
                
                // Show toast prompt
                const resumeToast = document.getElementById("resumeToast");
                const toastText = document.getElementById("resumeToastText");
                if (resumeToast) {
                    const mins = Math.floor(seekTime / 60);
                    const secs = Math.floor(seekTime % 60).toString().padStart(2, '0');
                    if (toastText) toastText.textContent = `Resumed from ${mins}:${secs}`;
                    resumeToast.classList.add("visible");
                    
                    const startOverBtn = document.getElementById("resumeToastStartOverBtn");
                    if (startOverBtn) {
                         startOverBtn.onclick = (e) => {
                             e.stopPropagation();
                             playerInstance.currentTime = 0;
                             
                             const subId = state.selectedSubject.subjectId;
                             const isTv = state.selectedSubject.seNum > 0 || state.selectedSubject.subjectType === 2;
                             const season = isTv ? state.selectedSeason : 0;
                             const episode = isTv ? state.selectedEpisode : 0;
                             const progressKey = `streamfit_progress_${subId}_${season}_${episode}`;
                             localStorage.removeItem(progressKey);
                             
                             resumeToast.classList.remove("visible");
                         };
                    }
                    
                    const dismissBtn = document.getElementById("resumeToastDismissBtn");
                    if (dismissBtn) {
                         dismissBtn.onclick = (e) => {
                             e.stopPropagation();
                             resumeToast.classList.remove("visible");
                         };
                    }
                    
                    setTimeout(() => {
                        resumeToast.classList.remove("visible");
                    }, 6000);
                }
            }
        }
    });
    playerInstance.on('play', hideLoader);
    playerInstance.on('play', () => { 
        state.userInteracted = true; 
        if (playerInstance && playerInstance.muted && !state.hasAutounmuted) {
            playerInstance.muted = false;
            playerInstance.volume = 1.0;
            state.hasAutounmuted = true;
        }
    });
    playerInstance.on('volumechange', () => {
        if (playerInstance && !playerInstance.muted) {
            state.userInteracted = true;
            hideLoader();
        }
    });

    playerInstance.on('enterfullscreen', () => {
        if (window.screen && window.screen.orientation && window.screen.orientation.lock) {
            window.screen.orientation.lock('landscape').catch(err => {
                console.warn('Failed to lock orientation:', err);
            });
        }
    });

    playerInstance.on('exitfullscreen', () => {
        if (window.screen && window.screen.orientation && window.screen.orientation.unlock) {
            window.screen.orientation.unlock();
        }
    });

    // PiP mode — prevent video from pausing when tab becomes hidden
    let isInPiP = false;
    playerInstance.on('ready', () => {
        const videoEl = playerInstance.media;
        if (!videoEl) return;

        videoEl.addEventListener('enterpictureinpicture', () => {
            isInPiP = true;
            // Override pause() so nothing (including Plyr's visibilitychange handler)
            // can pause the video while PiP is active
            const origPause = videoEl.pause.bind(videoEl);
            videoEl._origPause = origPause;
            videoEl.pause = () => {
                if (isInPiP) return; // silently block
                origPause();
            };
        });

        videoEl.addEventListener('leavepictureinpicture', () => {
            isInPiP = false;
            // Restore original pause
            if (videoEl._origPause) {
                videoEl.pause = videoEl._origPause;
                delete videoEl._origPause;
            }
        });
    });
 
    // Playback progress event listeners
    let progressSaveTimeout = null;
    
    const saveProgress = () => {
        const video = playerInstance ? playerInstance.media : null;
        if (!video || !state.selectedSubject) return;
        const currentTime = video.currentTime;
        const duration = video.duration;
        if (!duration || duration <= 0) return;
        
        const subjectId = state.selectedSubject.subjectId;
        const isTv = state.selectedSubject.seNum > 0 || state.selectedSubject.subjectType === 2;
        const season = isTv ? state.selectedSeason : 0;
        const episode = isTv ? state.selectedEpisode : 0;
        const progressKey = `streamfit_progress_${subjectId}_${season}_${episode}`;
        
        localStorage.setItem(progressKey, currentTime);
        
        // Update global Continue Watching history
        let history = [];
        try {
            history = JSON.parse(localStorage.getItem("streamfit_history")) || [];
        } catch (e) {}
        
        const actualDetailPath = (state.selectedSubject && state.selectedSubject.detailPath) 
            ? state.selectedSubject.detailPath 
            : (window.location.pathname.split("/").pop() || "");
        
        // Remove any previous entry for this show/subject to avoid duplicates
        history = history.filter(item => String(item.subjectId) !== String(subjectId) && item.detailPath !== actualDetailPath);
        
        const progressPercent = Math.round((currentTime / duration) * 100);
        if (currentTime > 5 && progressPercent < 95) {
            const newItem = {
                subjectId: subjectId,
                title: state.selectedSubject.title,
                cover: state.selectedSubject.cover ? (state.selectedSubject.cover.url || state.selectedSubject.cover) : "",
                season: season,
                episode: episode,
                currentTime: currentTime,
                duration: duration,
                progressPercent: progressPercent,
                detailPath: actualDetailPath,
                subjectType: state.selectedSubject.subjectType || (isTv ? 2 : 1),
                lastWatched: Date.now()
            };
            history.unshift(newItem);
            if (history.length > 12) history = history.slice(0, 12);
            localStorage.setItem("streamfit_history", JSON.stringify(history));
        } else if (progressPercent >= 95) {
            localStorage.setItem("streamfit_history", JSON.stringify(history));
            localStorage.removeItem(progressKey);
        }
    };

    playerInstance.on('timeupdate', () => {
        if (!progressSaveTimeout) {
            progressSaveTimeout = setTimeout(() => {
                saveProgress();
                progressSaveTimeout = null;
            }, 5000);
        }
    });

    playerInstance.on('pause', saveProgress);

    window.addEventListener('beforeunload', saveProgress);

    // Auto-Play next episode event listener
    // Only trigger if video actually played (duration > 0) to avoid firing on failed streams
    playerInstance.on('ended', () => {
        saveProgress();
        const currentDuration = playerInstance ? playerInstance.duration : 0;
        if (!currentDuration || currentDuration < 5) return; // not a real playback end
        const isTv = state.selectedSubject && (state.selectedSubject.seNum > 0 || state.selectedSubject.subjectType === 2);
        if (isTv) {
            const nextEp = state.selectedEpisode + 1;
            const episodeButtons = document.querySelectorAll("#watchEpisodeGrid .episode-btn");
            let nextBtn = null;
            episodeButtons.forEach(btn => {
                if (parseInt(btn.textContent.trim()) === nextEp) {
                    nextBtn = btn;
                }
            });
            
            if (nextBtn) {
                showNextEpisodeCountdown(nextBtn);
            } else {
                const seasonTabs = document.querySelectorAll("#watchSeasonTabs .season-tab");
                let nextSeasonBtn = null;
                seasonTabs.forEach((tab, index) => {
                    if (tab.classList.contains("active") && index < seasonTabs.length - 1) {
                        nextSeasonBtn = seasonTabs[index + 1];
                    }
                });
                if (nextSeasonBtn) {
                    showNextEpisodeCountdown(null, nextSeasonBtn);
                }
            }
        }
    });

    // Helper functions for countdown overlay
    const showNextEpisodeCountdown = (nextBtn, nextSeasonBtn = null) => {
        const overlay = document.getElementById("nextEpisodeOverlay");
        const titleEl = document.getElementById("nextEpisodeTitle");
        const textEl = document.getElementById("nextCountdownText");
        const circleProgress = document.getElementById("nextCountdownCircleProgress");
        const playBtn = document.getElementById("nextEpisodePlayBtn");
        const cancelBtn = document.getElementById("nextEpisodeCancelBtn");
        
        if (!overlay || !titleEl) return;
        if (state.nextEpisodeTimer) clearInterval(state.nextEpisodeTimer);
        
        let nextTitle = "";
        if (nextBtn) {
            nextTitle = `Season ${state.selectedSeason} - Episode ${state.selectedEpisode + 1}`;
        } else if (nextSeasonBtn) {
            nextTitle = `Season ${state.selectedSeason + 1} - Episode 01`;
        }
        
        titleEl.textContent = nextTitle;
        overlay.classList.add("visible");
        
        if (circleProgress) {
            circleProgress.style.transition = "none";
            circleProgress.style.strokeDashoffset = "0";
            circleProgress.getBoundingClientRect();
            circleProgress.style.transition = "stroke-dashoffset 10s linear";
            circleProgress.style.strokeDashoffset = "226";
        }
        
        let secondsLeft = 10;
        if (textEl) textEl.textContent = secondsLeft;
        
        const triggerNextPlay = () => {
            overlay.classList.remove("visible");
            if (state.nextEpisodeTimer) clearInterval(state.nextEpisodeTimer);
            state.nextEpisodeTimer = null;
            if (nextBtn) {
                nextBtn.click();
            } else if (nextSeasonBtn) {
                nextSeasonBtn.click();
                setTimeout(() => {
                    const firstEpBtn = document.querySelector("#watchEpisodeGrid .episode-btn");
                    if (firstEpBtn) firstEpBtn.click();
                }, 600);
            }
        };
        
        state.nextEpisodeTimer = setInterval(() => {
            secondsLeft--;
            if (textEl) textEl.textContent = secondsLeft;
            if (secondsLeft <= 0) {
                triggerNextPlay();
            }
        }, 1000);
        
        if (playBtn) {
            playBtn.onclick = () => {
                triggerNextPlay();
            };
        }
        if (cancelBtn) {
            cancelBtn.onclick = () => {
                overlay.classList.remove("visible");
                if (state.nextEpisodeTimer) clearInterval(state.nextEpisodeTimer);
                state.nextEpisodeTimer = null;
            };
        }
    };

    const loading = document.getElementById("watchPageLoading");
    const content = document.getElementById("watchWrapper");

    if (isLiveStream) {
        const sportTitle = urlParams.get("title") || "Live Stream";
        const sportUrl = urlParams.get("url") || "";
        const fallbackLinksRaw = urlParams.get("links") || "";
        const startIndex = parseInt(urlParams.get("index") || "0");
        
        state.fallbackLinks = [];
        state.currentFallbackIndex = startIndex;
        
        if (fallbackLinksRaw) {
            try {
                state.fallbackLinks = JSON.parse(decodeURIComponent(fallbackLinksRaw));
            } catch(e) {
                console.error("Failed to parse fallback links:", e);
            }
        }
        
        state.selectedSubject = {
            subjectId: "sports",
            title: sportTitle,
            cover: "https://images.unsplash.com/photo-1508098682722-e99c43a406b2?w=120&q=80",
            seNum: 0,
            subjectType: 1
        };
        
        const tvSelector = document.getElementById("watchTvSelector");
        if (tvSelector) tvSelector.style.display = "none";
        const dubSelectorGroup = document.getElementById("watchDubSelectorGroup");
        if (dubSelectorGroup) dubSelectorGroup.style.display = "none";
        
        const recSection = document.querySelector(".watch-sidebar");
        if (recSection) recSection.style.display = "none";
        
        const mainWatchArea = document.querySelector(".watch-main-content");
        if (mainWatchArea) {
            mainWatchArea.style.width = "100%";
            mainWatchArea.style.maxWidth = "100%";
            mainWatchArea.style.flex = "1";
        }
        
        const categoryEl = document.getElementById("playerMediaCategory");
        const titleEl = document.getElementById("playerMediaTitle");
        if (categoryEl) categoryEl.textContent = type === "sports" ? "Live Sports" : "Live TV";
        if (titleEl) titleEl.textContent = sportTitle;
        
        const metaTitle = document.getElementById("watchMetaTitle");
        const metaRating = document.getElementById("watchMetaRating");
        const metaYear = document.getElementById("watchMetaYear");
        const metaCountry = document.getElementById("watchMetaCountry");
        const metaDuration = document.getElementById("watchMetaDuration");
        if (metaTitle) metaTitle.textContent = sportTitle;
        if (metaRating) metaRating.innerHTML = `<i class="fa-solid fa-satellite-dish"></i> LIVE`;
        if (metaYear) metaYear.innerHTML = `<i class="fa-regular fa-clock"></i> Stream`;
        if (metaCountry) metaCountry.innerHTML = `<i class="fa-solid fa-earth-americas"></i> World`;
        if (metaDuration) metaDuration.innerHTML = `<i class="fa-solid fa-globe"></i> Global`;
        
        const descEl = document.getElementById("watchDescription");
        if (descEl) descEl.textContent = "Enjoy the high quality live broadcast on Streamfit. Free online streaming with BD region-lock bypass.";
        
        const dlBtn = document.getElementById("downloadBtn");
        if (dlBtn) dlBtn.style.display = "none";
        
        state.availableResources = [{
            resourceId: "sports_stream",
            resolution: 0,
            size: 0,
            resourceLink: sportUrl
        }];
        
        // If stream is HTTP but page is HTTPS, redirect to HTTP version of this page.
        // HTTP page can load HTTP streams without mixed content restriction.
        let rawUrl = sportUrl;
        if (sportUrl.includes('/api/sports/proxy?url=')) {
            try {
                rawUrl = decodeURIComponent(sportUrl.split('url=')[1].split('&')[0]);
            } catch(e) {}
        }
        if (rawUrl.startsWith('http://') && window.location.protocol === 'https:') {
            window.location.replace(window.location.href.replace('https://', 'http://'));
            return;
        }
        
        renderLiveStreamLinks();
        playResources();
        
        if (loading) loading.style.display = "none";
        if (content) content.style.display = "block";
        return;
    }

    if (tmdbId) {
        if (loading) loading.innerHTML = `<div class="loading-spinner"></div><p style="margin-top: 15px;">Resolving TMDB ID ${tmdbId} via Streamfit...</p>`;
        const resolution = await apiGet(`/api/resolve-tmdb?tmdbId=${tmdbId}&type=${type}&season=${reqSeason}&episode=${reqEpisode}`);
        if (resolution && resolution.code === 0 && resolution.data && resolution.data.subjectId) {
            subjectId = resolution.data.subjectId;
            state.selectedSeason = resolution.data.season;
            state.selectedEpisode = resolution.data.episode;
        } else {
            if (loading) loading.innerHTML = "<p><i class='fa-solid fa-triangle-exclamation'></i> Failed to resolve TMDB ID. Go back home.</p>";
            return;
        }
    } else {
        state.selectedSeason = reqSeason;
        state.selectedEpisode = reqEpisode;
    }

    const detailPath = watchSlug;
    let result = await apiGet(`/api/detail?detailPath=${encodeURIComponent(detailPath)}&subjectId=${encodeURIComponent(subjectId)}`);
    if (result && result.data) {
        let detail = result.data;

        // If a specific dub was requested via ?id=, resolve that exact dub's details
        if (subjectId && String(detail.subjectId) !== String(subjectId) && detail.dubs && detail.dubs.length > 0) {
            const targetDub = detail.dubs.find(d => String(d.subjectId) === String(subjectId));
            if (targetDub) {
                const dubDetailRes = await apiGet(`/api/detail?subjectId=${encodeURIComponent(subjectId)}&detailPath=${encodeURIComponent(targetDub.detailPath || '')}`);
                if (dubDetailRes && dubDetailRes.data && dubDetailRes.data.title) {
                    detail = dubDetailRes.data;
                }
            }
        }

        state.selectedSubject = detail;
        subjectId = detail.subjectId;

        // Set player header overlay details
        const mediaTypeLabel = detail.seNum > 0 || detail.subjectType === 2 ? "TV Show" : "Movie";
        const categoryEl = document.getElementById("playerMediaCategory");
        const titleEl = document.getElementById("playerMediaTitle");
        if (categoryEl) categoryEl.textContent = mediaTypeLabel;
        if (titleEl) titleEl.textContent = detail.title;

        // Populate cinema metadata section on the page
        const metaTitle = document.getElementById("watchMetaTitle");
        const metaRating = document.getElementById("watchMetaRating");
        const metaYear = document.getElementById("watchMetaYear");
        const metaCountry = document.getElementById("watchMetaCountry");
        const metaDuration = document.getElementById("watchMetaDuration");

        if (metaTitle) metaTitle.textContent = detail.title;
        if (metaRating) metaRating.innerHTML = `<i class="fa-solid fa-star"></i> ${detail.imdbRatingValue || '--'}`;
        if (metaYear) metaYear.innerHTML = `<i class="fa-regular fa-calendar"></i> ${detail.releaseDate ? detail.releaseDate.split('-')[0] : '----'}`;
        if (metaCountry) metaCountry.innerHTML = `<i class="fa-solid fa-earth-americas"></i> ${detail.countryName || 'USA'}`;
        if (metaDuration) metaDuration.innerHTML = `<i class="fa-regular fa-clock"></i> ${detail.duration || '-- min'}`;

        // Populate description
        const descEl = document.getElementById("watchDescription");
        if (descEl) descEl.textContent = detail.description || "No description available.";

        // Initialize Action Buttons (Watchlist & Native Share)
        window.initWatchlistButton();
        window.initShareButton();

        // Populate genre tags
        const genreEl = document.getElementById("watchGenres");
        if (genreEl && detail.genre) {
            const genres = Array.isArray(detail.genre) ? detail.genre : detail.genre.split(',');
            genreEl.innerHTML = genres.map(g => `<span class="tag">${g.trim()}</span>`).join('');
            genreEl.style.display = "flex";
        }

        // Setup Dub / Language selector
        const dubSelectorGroup = document.getElementById("watchDubSelectorGroup");
        const dubSelect = document.getElementById("dubLanguageSelect");
        if (dubSelectorGroup && dubSelect) {
            if (detail.dubs && detail.dubs.length > 1) {
                dubSelectorGroup.style.display = "block";
                dubSelect.innerHTML = "";
                detail.dubs.forEach(dub => {
                    const opt = document.createElement("option");
                    opt.value = dub.subjectId;
                    opt.textContent = dub.lanName || (dub.original ? "Original Audio" : (dub.lanCode ? dub.lanCode.toUpperCase() : "Unknown Dub"));
                    if (String(dub.subjectId) === String(subjectId)) opt.selected = true;
                    opt.dataset.path = dub.detailPath || "";
                    dubSelect.appendChild(opt);
                });
                dubSelect.onchange = async () => {
                    const selectedOpt = dubSelect.options[dubSelect.selectedIndex];
                    const targetSubjectId = selectedOpt.value;
                    const targetDetailPath = selectedOpt.dataset.path || "";
                    
                    showToastNotification("Switching language track...");
                    
                    // Update active subject ID
                    subjectId = targetSubjectId;
                    
                    const isTv = (state.selectedSubject && state.selectedSubject.subjectType === 2) || (watchTypeSegment === 'tv');
                    const typeSeg = isTv ? 'tv' : 'movie';
                    const effectiveSlug = targetDetailPath || watchSlug;
                    const newUrl = `/watch/${typeSeg}/${encodeURIComponent(effectiveSlug)}?id=${encodeURIComponent(targetSubjectId)}&season=${state.selectedSeason}&episode=${state.selectedEpisode}`;
                    history.pushState({}, "", newUrl);
                    
                    const result = await apiGet(`/api/detail?subjectId=${targetSubjectId}&detailPath=${encodeURIComponent(targetDetailPath)}`);
                    if (result && result.data) {
                        const newDetail = result.data;
                        state.selectedSubject = newDetail;
                        
                        // Update UI texts
                        const titleEl = document.getElementById("playerMediaTitle");
                        if (titleEl) titleEl.textContent = newDetail.title;
                        const watchMetaTitle = document.getElementById("watchMetaTitle");
                        if (watchMetaTitle) watchMetaTitle.textContent = newDetail.title;
                        const descEl = document.getElementById("watchDescription");
                        if (descEl) descEl.textContent = newDetail.description || "No description available.";
                        
                        const isTvNow = newDetail.subjectType !== 1 && (newDetail.seNum > 0 || newDetail.subjectType === 2);
                        const tvSelector = document.getElementById("watchTvSelector");
                        if (isTvNow) {
                            if (tvSelector) tvSelector.style.display = "block";
                            await loadSeasonEpisodes(targetSubjectId, targetDetailPath || effectiveSlug);
                            await loadPlayResources(targetSubjectId, state.selectedSeason, state.selectedEpisode);
                        } else {
                            if (tvSelector) tvSelector.style.display = "none";
                            await loadPlayResources(targetSubjectId);
                        }
                        
                        // Update recommendations
                        loadRecommendations(newDetail);
                    }
                };
            } else {
                dubSelectorGroup.style.display = "none";
            }
        }

        // Setup Tabs
        const tabBtnForYou = document.getElementById("tabBtnForYou");
        const tabBtnComments = document.getElementById("tabBtnComments");
        const tabPaneForYou = document.getElementById("tabPaneForYou");
        const tabPaneComments = document.getElementById("tabPaneComments");
        if (tabBtnForYou && tabBtnComments && tabPaneForYou && tabPaneComments) {
            tabBtnForYou.onclick = () => {
                tabBtnForYou.classList.add("active"); tabBtnComments.classList.remove("active");
                tabPaneForYou.classList.add("active"); tabPaneComments.classList.remove("active");
            };
            tabBtnComments.onclick = () => {
                tabBtnComments.classList.add("active"); tabBtnForYou.classList.remove("active");
                tabPaneComments.classList.add("active"); tabPaneForYou.classList.remove("active");
            };
        }

        // TV Show episode selector
        const isTv = isTvSubject(detail);
        const tvSelector = document.getElementById("watchTvSelector");
        if (isTv) {
            if (tvSelector) tvSelector.style.display = "block";
            await loadSeasonEpisodes(subjectId, detailPath);
        } else {
            if (tvSelector) tvSelector.style.display = "none";
        }

        // Load stream resources
        if (isTv) {
            await loadPlayResources(subjectId, state.selectedSeason, state.selectedEpisode);
        } else {
            await loadPlayResources(subjectId);
        }

        // Load recommendations
        loadRecommendations(detail);

        if (loading) loading.style.display = "none";
        if (content) content.style.display = "block";
    } else {
        if (loading) loading.innerHTML = "<p><i class='fa-solid fa-triangle-exclamation'></i> Failed to load player resources. Go back home.</p>";
    }
}

async function loadRecommendations(detail) {
    const grid = document.getElementById("watchRecommendationsGrid");
    if (!grid) return;
    grid.innerHTML = '<div class="card-shimmer"></div><div class="card-shimmer"></div><div class="card-shimmer"></div>';

    // Query filter API with the same genre/subject type
    let firstGenre = "*";
    if (detail.genre) {
        const genres = Array.isArray(detail.genre) ? detail.genre : detail.genre.split(',');
        if (genres.length > 0) {
            firstGenre = genres[0].trim();
        }
    }
    const payload = {
        genre: firstGenre,
        country: "*",
        year: "*",
        language: "*",
        sort: "Hottest",
        subjectType: detail.subjectType || 1,
        page: 1,
        perPage: 6
    };

    const result = await apiPost('/api/filter', payload);
    grid.innerHTML = "";
    if (result && result.data) {
        // filter API returns data.list or data.items depending on endpoint
        const raw = result.data.list || result.data.items || [];
        const subjects = (raw.length > 0 && raw[0].subjects !== undefined)
            ? raw.flatMap(sec => sec.subjects || [])
            : raw;
        
        // Filter out current subject and deduplicate
        const seen = new Set();
        const filtered = subjects.filter(item => {
            const id = String(item.subjectId);
            if (id === String(detail.subjectId) || seen.has(id)) return false;
            seen.add(id);
            return true;
        }).slice(0, 6);
        if (filtered.length > 0) {
            filtered.forEach(item => {
                grid.appendChild(createContentCard(item));
            });
            return;
        }
    }
    
    // Fallback: load trending items
    const homeResult = await apiGet("/api/home?page=1&tabId=0");
    if (homeResult && homeResult.data && homeResult.data.items) {
        let subjects = [];
        homeResult.data.items.forEach(sec => {
            if (sec.subjects) subjects = subjects.concat(sec.subjects);
        });
        const filtered = subjects.filter(item => String(item.subjectId) !== String(detail.subjectId)).slice(0, 6);
        filtered.forEach(item => {
            grid.appendChild(createContentCard(item));
        });
    }
    
    if (grid.children.length === 0) {
        grid.innerHTML = '<div style="color:var(--text-muted);padding:10px 0;">No suggestions available.</div>';
    }


}

async function loadSeasonEpisodes(subjectId, detailPath = "") {
    if (!isTvSubject(state.selectedSubject)) {
        const tvSelector = document.getElementById("watchTvSelector");
        if (tvSelector) tvSelector.style.display = "none";
        const seasonGroup = document.getElementById("watchSeasonSelectorGroup");
        if (seasonGroup) seasonGroup.style.display = "none";
        return;
    }
    const seasonTabs = document.getElementById("watchSeasonTabs");
    const seasonGroup = document.getElementById("watchSeasonSelectorGroup");
    const seasonSelect = document.getElementById("seasonSelect");
    const episodeGrid = document.getElementById("watchEpisodeGrid");
    
    if (seasonTabs) seasonTabs.innerHTML = "<span>Loading seasons...</span>";
    if (episodeGrid) episodeGrid.innerHTML = "";

    const result = await apiGet(`/api/season-info?detailPath=${encodeURIComponent(detailPath)}&subjectId=${encodeURIComponent(subjectId || '')}`);
    if (result && result.data && result.data.seasons && result.data.seasons.length > 0) {
        const seasons = result.data.seasons;
        state.loadedSeasons = seasons;
        
        // Populate Season Select Dropdown (side-by-side with Dub selector matching Image 1)
        if (seasonGroup && seasonSelect) {
            seasonGroup.style.display = "block";
            seasonSelect.innerHTML = "";
            seasons.forEach((season) => {
                const opt = document.createElement("option");
                opt.value = season.se;
                opt.textContent = `Season ${String(season.se).padStart(2, '0')}`;
                if (season.se === state.selectedSeason) opt.selected = true;
                seasonSelect.appendChild(opt);
            });
            seasonSelect.onchange = async () => {
                const newSe = Number(seasonSelect.value);
                state.selectedSeason = newSe;
                const targetSeason = seasons.find(s => s.se === newSe) || seasons[0];
                let firstEp = 1;
                if (targetSeason.allEp) {
                    firstEp = Number(targetSeason.allEp.split(',')[0]) || 1;
                }
                state.selectedEpisode = firstEp;
                renderEpisodes(targetSeason);
                
                // Update URL and play episode 1 of chosen season
                const _epPathParts = window.location.pathname.split("/");
                const _epSlug = _epPathParts[_epPathParts.length - 1] || "";
                const _epType = _epPathParts[2] || "tv";
                const urlParams = new URLSearchParams(window.location.search);
                urlParams.set("season", state.selectedSeason);
                urlParams.set("episode", state.selectedEpisode);
                history.pushState({}, "", `/watch/${_epType}/${_epSlug}?${urlParams.toString()}`);
                
                await loadPlayResources(state.selectedSubject.subjectId, state.selectedSeason, state.selectedEpisode);
            };
        }

        // Fallback season tabs
        if (seasonTabs) {
            seasonTabs.innerHTML = "";
            seasons.forEach((season) => {
                const btn = document.createElement("button");
                const isActive = season.se === state.selectedSeason;
                btn.className = `season-tab ${isActive ? 'active' : ''}`;
                btn.textContent = `Season ${season.se}`;
                btn.onclick = () => {
                    document.querySelectorAll(".season-tab").forEach(b => b.classList.remove("active"));
                    btn.classList.add("active");
                    state.selectedSeason = season.se;
                    if (seasonSelect) seasonSelect.value = season.se;
                    state.selectedEpisode = season.allEp ? Number(season.allEp.split(',')[0]) : 1;
                    renderEpisodes(season);
                };
                seasonTabs.appendChild(btn);
            });
        }

        const activeSeasonObj = seasons.find(s => s.se === state.selectedSeason) || seasons[0];
        state.selectedSeason = activeSeasonObj.se;
        if (seasonSelect) seasonSelect.value = activeSeasonObj.se;
        
        // Ensure state.selectedEpisode exists in the selected season
        let epsArr = [];
        if (activeSeasonObj.allEp) {
            epsArr = activeSeasonObj.allEp.split(",").map(Number);
        } else if (activeSeasonObj.maxEp) {
            for (let i = 1; i <= activeSeasonObj.maxEp; i++) epsArr.push(i);
        }
        
        if (epsArr.length > 0) {
            if (!epsArr.includes(state.selectedEpisode)) {
                state.selectedEpisode = epsArr[0] || 1;
            }
        } else {
            state.selectedEpisode = 1;
        }

        renderEpisodes(activeSeasonObj);

        // Apply drag scroll to season tabs if visible
        if (seasonTabs && seasonTabs.children.length > 0) {
            enableDragScroll(seasonTabs);
        }
    } else {
        if (seasonGroup) seasonGroup.style.display = "none";
        if (seasonTabs) {
            seasonTabs.innerHTML = `<div style="padding:12px;background:rgba(255,255,255,0.03);border:1px solid rgba(255,255,255,0.08);border-radius:8px;color:#9ca3af;font-size:13px;display:flex;align-items:center;gap:8px;">
                <i class="fa-solid fa-clock" style="color:#f87171;font-size:15px;"></i>
                <span>Upcoming release — episodes will be available soon.</span>
            </div>`;
        }
    }
}

function renderEpisodes(season) {
    const grid = document.getElementById("watchEpisodeGrid");
    if (!grid) return;
    grid.innerHTML = "";

    let eps = [];
    if (season.allEp) {
        eps = season.allEp.split(",");
    } else if (season.maxEp) {
        for (let i = 1; i <= season.maxEp; i++) eps.push(String(i));
    }

    if (eps.length > 0) {
        eps.forEach((epNum) => {
            const btn = document.createElement("button");
            const isActive = Number(epNum) === state.selectedEpisode;
            btn.className = `episode-btn ${isActive ? 'active' : ''}`;
            // Two-digit format matching Image 1 (01, 02, 03... 10)
            const paddedEp = String(epNum).trim().padStart(2, '0');
            btn.textContent = paddedEp;

            if (isActive) {
                const eq = document.createElement("span");
                eq.className = "playing-indicator";
                eq.innerHTML = "<span></span><span></span><span></span>";
                btn.appendChild(eq);
            }

            btn.onclick = async () => {
                state.selectedEpisode = Number(epNum);
                const _epPathParts = window.location.pathname.split("/");
                const _epSlug = _epPathParts[_epPathParts.length - 1] || "";
                const _epType = _epPathParts[2] || "tv";

                const urlParams = new URLSearchParams(window.location.search);
                urlParams.set("season", state.selectedSeason);
                urlParams.set("episode", state.selectedEpisode);

                // If on details page, redirect to watch page
                if (!routes.isWatch) {
                    const typeSegment = (state.selectedSubject && state.selectedSubject.subjectType === 2) ? 'tv' : 'movie';
                    window.location.href = `/watch/${typeSegment}/${encodeURIComponent(_epSlug)}?${urlParams.toString()}`;
                    return;
                }

                if (state.playingSeason === state.selectedSeason && Number(epNum) === state.playingEpisode) return; // already playing

                const newUrl = `/watch/${_epType}/${_epSlug}?${urlParams.toString()}`;
                history.pushState({}, "", newUrl);

                // Highlight active episode button
                grid.querySelectorAll(".episode-btn").forEach(b => {
                    b.classList.remove("active");
                    const ind = b.querySelector(".playing-indicator");
                    if (ind) ind.remove();
                });
                btn.classList.add("active");
                const eq = document.createElement("span");
                eq.className = "playing-indicator";
                eq.innerHTML = "<span></span><span></span><span></span>";
                btn.appendChild(eq);

                // Load the new episode stream directly — no reload
                await loadPlayResources(state.selectedSubject.subjectId, state.selectedSeason, state.selectedEpisode);
            };
            grid.appendChild(btn);
        });

        if (!eps.includes(String(state.selectedEpisode))) {
            state.selectedEpisode = Number(eps[0]);
        }

        // Update TV Quick Nav Bar
        const quickNav = document.getElementById("tvQuickNavBar");
        const quickLabel = document.getElementById("quickNavLabel");
        const prevBtn = document.getElementById("quickPrevEpBtn");
        const nextBtn = document.getElementById("quickNextEpBtn");

        if (quickNav) {
            quickNav.style.display = "flex";
            if (quickLabel) {
                quickLabel.textContent = `Season ${state.selectedSeason} • Ep ${String(state.selectedEpisode || 1).padStart(2, '0')}`;
            }
            if (prevBtn && !prevBtn._wired) {
                prevBtn._wired = true;
                prevBtn.onclick = () => window.playPreviousEpisode();
            }
            if (nextBtn && !nextBtn._wired) {
                nextBtn._wired = true;
                nextBtn.onclick = () => window.playNextEpisode();
            }
        }

        const fsEpBtn = document.getElementById("fsEpisodesBtn");
        if (fsEpBtn) fsEpBtn.style.display = "inline-flex";

        // Auto-scroll active episode into center of viewport
        setTimeout(() => {
            const activeBtn = grid.querySelector(".episode-btn.active");
            if (activeBtn) {
                activeBtn.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
            }
        }, 150);
    }
}

async function loadPlayResources(subjectId, season = null, episode = null) {
    // ── IMMEDIATELY stop current stream so old episode doesn't keep playing ──
    if (hlsInstance) {
        hlsInstance.destroy();
        hlsInstance = null;
    }
    if (playerInstance) {
        try { playerInstance.pause(); } catch(e) {}
        const _vid = playerInstance.media;
        if (_vid) {
            _vid.pause();
            _vid.removeAttribute("src");
            try { _vid.load(); } catch(e) {}
        }
    }
    state.availableResources = [];
    state.availableCaptions = [];

    // Show loading overlay immediately so user sees feedback right away
    const loaderOverlay = document.getElementById("playerLoaderOverlay");
    if (loaderOverlay) {
        const statusTxt = loaderOverlay.querySelector("span");
        if (statusTxt) statusTxt.innerHTML = '<i class="fa-solid fa-spinner fa-spin" style="margin-right:8px;"></i>Loading stream...';
        const spinner = loaderOverlay.querySelector(".player-spinner");
        if (spinner) spinner.style.display = "";
        loaderOverlay.style.background = "transparent";
        loaderOverlay.style.pointerEvents = "none";
        loaderOverlay.classList.add("visible");
        const pWrap = document.getElementById("playerWrapperEl");
        if (pWrap) pWrap.classList.add("is-loading");
    }

    // Reset auto-quality options on new stream loading
    userSelectedQuality = false;
    bufferingCount = 0;
    lastBufferingTime = 0;
    isSwitchingQuality = false;
    isProgrammaticQualityChange = false;

    // Clear any active next episode countdown timer and hide overlay
    if (state.nextEpisodeTimer) {
        clearInterval(state.nextEpisodeTimer);
        state.nextEpisodeTimer = null;
    }
    const nextEpisodeOverlay = document.getElementById("nextEpisodeOverlay");
    if (nextEpisodeOverlay) {
        nextEpisodeOverlay.classList.remove("visible");
    }

    // Hide resume toast immediately on loading new episode/movie
    const resumeToast = document.getElementById("resumeToast");
    if (resumeToast) {
        resumeToast.classList.remove("visible");
    }

    const urlParams = new URLSearchParams(window.location.search);
    let detailPath = urlParams.get("path") || "";
    if (!detailPath) {
        const _pathSegments = window.location.pathname.split("/");
        detailPath = decodeURIComponent(_pathSegments[_pathSegments.length - 1] || "");
    }

    // Check for saved playback progress for this resource
    const isTv = state.selectedSubject && (state.selectedSubject.seNum > 0 || state.selectedSubject.subjectType === 2);
    const se = isTv ? (season || 1) : 0;
    const ep = isTv ? (episode || 1) : 0;
    const progressKey = `streamfit_progress_${subjectId}_${se}_${ep}`;
    const savedTime = parseFloat(localStorage.getItem(progressKey) || "0");
    if (savedTime > 10) {
        state.pendingResumeTime = savedTime;
    } else {
        state.pendingResumeTime = null;
    }

    let url = `/api/resource?subjectId=${subjectId}&t=${Date.now()}`;
    if (detailPath) {
        url += `&detailPath=${encodeURIComponent(detailPath)}`;
    }
    if (season !== null && episode !== null) {
        state.playingSeason = season;
        state.playingEpisode = episode;
        url += `&se=${season}&ep=${episode}`;
        // Update player overlay title
        const titleEl = document.getElementById("playerMediaTitle");
        if (titleEl && state.selectedSubject) {
            titleEl.textContent = `${state.selectedSubject.title} - S${season}E${String(episode).padStart(2, '0')}`;
        }
        const quickNav = document.getElementById("tvQuickNavBar");
        const quickLabel = document.getElementById("quickNavLabel");
        if (quickNav) {
            quickNav.style.display = "flex";
            if (quickLabel) {
                quickLabel.textContent = `Season ${season} • Ep ${String(episode).padStart(2, '0')}`;
            }
        }
    } else {
        state.playingSeason = 0;
        state.playingEpisode = 0;
        const titleEl = document.getElementById("playerMediaTitle");
        if (titleEl && state.selectedSubject) {
            titleEl.textContent = state.selectedSubject.title;
        }
    }

    // Overlay already shown at function start; ensure visible during fetch
    if (loaderOverlay) loaderOverlay.classList.add("visible");

    const result = await apiGet(url);

    if (result && result.data && result.data.list && result.data.list.length > 0) {
        state.availableResources = result.data.list;

        // Auto select highest resolution for subtitle fetching
        const bestResource = state.availableResources.reduce((prev, current) => {
            return (prev.resolution > current.resolution) ? prev : current;
        });

        // Feed all resources to Play player first to play video immediately
        playResources();

        // Load subtitles in the background after 10 seconds of delay to avoid interfering with playback start
        setTimeout(() => {
            if (state.selectedSubject && state.availableResources && state.availableResources.length > 0) {
                const bestRes = state.availableResources.reduce((prev, current) => {
                    return (prev.resolution > current.resolution) ? prev : current;
                });
                console.log("[Player] Loading subtitles in background after playback start delay...");
                loadSubtitles(state.selectedSubject.subjectId, bestRes.resourceId).catch(err => {
                    console.error("[Player] Failed to load subtitles:", err);
                });
            }
        }, 10000);
    } else {
        console.error("No play resources found for this item");
        if (loaderOverlay) {
            // Hide spinner so it doesn't keep animating behind error message
            const spinner = loaderOverlay.querySelector(".player-spinner");
            if (spinner) spinner.style.display = "none";
            const statusTxt = loaderOverlay.querySelector("span");
            if (statusTxt) statusTxt.innerHTML = `
                <div style="display:flex;flex-direction:column;align-items:center;justify-content:center;gap:12px;padding:26px 20px;text-align:center;max-width:480px;margin:0 auto;">
                    <div style="display:inline-flex;align-items:center;gap:8px;background:rgba(239,68,68,0.18);color:#f87171;border:1px solid rgba(239,68,68,0.35);padding:5px 16px;border-radius:24px;font-size:12px;font-weight:700;text-transform:uppercase;letter-spacing:0.8px;">
                        <i class="fa-solid fa-clock"></i> Upcoming Title
                    </div>
                    <div style="font-size:18px;font-weight:700;color:#fff;line-height:1.3;">Not Yet Available for Streaming</div>
                    <div style="font-size:13px;color:#9ca3af;line-height:1.5;">This title has not been released or uploaded to streaming servers yet. Streams will appear as soon as they become available.</div>
                    <div style="display:flex;gap:12px;margin-top:6px;">
                        <button onclick="window.history.back()" style="background:rgba(255,255,255,0.12);color:#fff;border:1px solid rgba(255,255,255,0.25);padding:8px 18px;border-radius:8px;font-weight:600;cursor:pointer;font-size:13px;display:inline-flex;align-items:center;gap:6px;">
                            <i class="fa-solid fa-arrow-left"></i> Go Back
                        </button>
                        <button onclick="loadPlayResources('${subjectId}',${season !== null ? season : 'null'},${episode !== null ? episode : 'null'})" style="background:var(--accent,#00e676);color:#fff;border:none;padding:8px 18px;border-radius:8px;font-weight:600;cursor:pointer;font-size:13px;display:inline-flex;align-items:center;gap:6px;">
                            <i class="fa-solid fa-rotate-right"></i> Check Again
                        </button>
                    </div>
                </div>`;
            loaderOverlay.style.background = "rgba(0,0,0,0.88)";
            loaderOverlay.style.pointerEvents = "auto";
            loaderOverlay.classList.add("visible");
        }
    }
}

async function playResources() {
    const videoElement = playerInstance ? playerInstance.media : document.getElementById("player");
    if (!state.availableResources || state.availableResources.length === 0) return;

    // Find highest quality resource for download button and fallback url
    const bestResource = state.availableResources.reduce((prev, current) => {
        return (prev.resolution > current.resolution) ? prev : current;
    });

    const streamUrl = bestResource.resourceLink;
    state.directMp4Url = streamUrl;

    // Set download button URL
    const dlBtn = document.getElementById("watchDownloadBtn") || document.getElementById("downloadBtn");
    if (dlBtn) {
        dlBtn.href = streamUrl;
        dlBtn.setAttribute("target", "_blank");
        dlBtn.setAttribute("download", "");
        dlBtn.style.display = "inline-flex";
    }

    if (typeof window.ensureQualityBadge === 'function') {
        window.ensureQualityBadge();
    }

    if (hlsInstance) {
        hlsInstance.destroy();
        hlsInstance = null;
    }

    // Reset video element src and load to clear any cached/active streams
    if (videoElement) {
        videoElement.pause();
        videoElement.removeAttribute("src");
        try {
            videoElement.load();
        } catch (e) {
            console.log("[Player] Error reloading video tag:", e);
        }

        console.log(`[Player] Loading sources for title: ${state.selectedSubject.title}`);
        videoElement.setAttribute("crossorigin", "anonymous");
    }

    // Format and sanitize Plyr native sources
    const uniqueResolutions = new Set();
    const sources = [];

    // Find highest quality resource to use as the default/Auto source
    if (state.availableResources && state.availableResources.length > 0) {
        const bestResource = state.availableResources.reduce((prev, current) => {
            return (prev.resolution > current.resolution) ? prev : current;
        });

        // Add Auto (size 0) source pointing to best resource
        sources.push({
            src: bestResource.resourceLink,
            type: 'video/mp4',
            size: 0
        });
    }

    state.availableResources
        .filter(res => res && res.resourceLink)
        .forEach(res => {
            const resVal = res.resolution || 720;
            if (!uniqueResolutions.has(resVal)) {
                uniqueResolutions.add(resVal);
                sources.push({
                    src: res.resourceLink,
                    type: 'video/mp4',
                    size: resVal
                });
            }
        });

    // Format and sanitize Plyr native captions
    const tracks = state.availableCaptions
        .filter(track => track && track.src)
        .map(track => ({
            kind: 'captions',
            label: track.label || 'Subtitle',
            srclang: track.srclang || 'en',
            src: track.src,
            default: !!track.default
        }));

    if (playerInstance) {
        playerInstance.off('error');
        playerInstance.on('error', (e) => {
            console.log("[Plyr Error] Media error detected:", e);
            handlePlaybackError();
        });
    }

    if (streamUrl.includes(".m3u8")) {
        if (Hls.isSupported()) {
            hlsInstance = new Hls({
                enableWorker: true,
                lowLatencyMode: true,
                liveSyncDurationCount: 3,
                liveMaxLatencyDurationCount: 6,
                maxBufferLength: 8,
                maxMaxBufferLength: 15,
                manifestLoadingMaxRetry: 10,
                manifestLoadingRetryDelay: 500,
                levelLoadingMaxRetry: 10,
                levelLoadingRetryDelay: 500,
                fragLoadingMaxRetry: 10,
                fragLoadingRetryDelay: 500
            });
            const antiCacheUrl = streamUrl + (streamUrl.includes("?") ? "&" : "?") + "_t=" + Date.now();
            hlsInstance.loadSource(antiCacheUrl);
            hlsInstance.attachMedia(videoElement);
            hlsInstance.on(Hls.Events.MANIFEST_PARSED, function() {
                triggerAutoplay(playerInstance);
            });
            hlsInstance.on(Hls.Events.ERROR, function(event, data) {
                if (data.fatal) {
                    console.log("[Hls Error] Fatal error:", data);
                    handlePlaybackError();
                }
            });
        } else if (videoElement.canPlayType('application/vnd.apple.mpegurl')) {
            videoElement.src = streamUrl;
            videoElement.load();
            triggerAutoplay(playerInstance);
        }
    } else {
        // Set the source natively on Plyr to show settings dropdown
        // Plyr destroys/recreates the <video> element here, so we MUST listen
        // for the Plyr-level 'canplay' event (which still fires on the instance)
        // rather than calling play() immediately after setting source.
        playerInstance.once('canplay', () => {
            const preferredQ = parseInt(localStorage.getItem("streamfit_preferred_quality") || "0");
            if (preferredQ > 0) {
                const availableQs = state.availableResources.map(r => r.resolution || 720);
                if (availableQs.includes(preferredQ)) {
                    isProgrammaticQualityChange = true;
                    playerInstance.quality = preferredQ;
                    setTimeout(() => { isProgrammaticQualityChange = false; }, 1000);
                }
            }
            triggerAutoplay(playerInstance);
        });

        playerInstance.source = {
            type: 'video',
            title: state.selectedSubject.title,
            sources: sources,
            tracks: tracks
        };

        // Force browser to load the new sources by initiating playback.
        // We trigger it immediately and inside a small timeout to account for Plyr's async DOM replacement.
        playerInstance.play().catch(() => {});
        setTimeout(() => {
            if (playerInstance) {
                playerInstance.play().catch(e => {
                    console.log("[Player] Play triggered to start stream loading:", e);
                });
            }
            if (typeof window.organizePlyrControlsLayout === 'function') {
                window.organizePlyrControlsLayout();
            }
        }, 150);
    }

    // Always apply any cached/loaded subtitles to the media element
    updatePlayerSubtitles();
}

async function loadSubtitles(subjectId, resourceId) {
    state.availableCaptions = [];

    const result = await apiGet(`/api/captions?subjectId=${subjectId}&resourceId=${resourceId}&t=${Date.now()}`);
    if (result && result.data && result.data.extCaptions) {
        const captions = result.data.extCaptions;
        
        captions.forEach(cap => {
            if (!cap || !cap.url) return;
            const proxiedUrl = cap.url;
            const track = {
                kind: 'captions',
                label: cap.lanName || 'Subtitle',
                srclang: cap.lan || 'en',
                src: proxiedUrl,
                default: cap.lan === 'en'
            };
            state.availableCaptions.push(track);
        });

        // Add them dynamically to the video element for Plyr to update
        updatePlayerSubtitles();
    }
}

function updatePlayerSubtitles() {
    const videoElement = playerInstance ? playerInstance.media : document.getElementById("player");
    if (!videoElement) return;

    console.log("[Player] Injecting dynamic subtitles:", state.availableCaptions);

    // Remove any existing tracks
    const existingTracks = videoElement.querySelectorAll("track");
    existingTracks.forEach(t => t.remove());

    // Add new tracks
    if (state.availableCaptions && state.availableCaptions.length > 0) {
        state.availableCaptions.forEach(track => {
            const trackEl = document.createElement("track");
            trackEl.kind = "captions";
            trackEl.label = track.label;
            trackEl.srclang = track.srclang;
            trackEl.src = track.src;
            if (track.default) {
                trackEl.default = true;
            }
            videoElement.appendChild(trackEl);
        });
    }
}

function loadDirectMP4Fallback() {
    if (hlsInstance) {
        hlsInstance.destroy();
        hlsInstance = null;
    }
    const video = playerInstance ? playerInstance.media : document.getElementById("player");
    if (video) {
        video.src = state.directMp4Url;
        video.load();
        playerInstance.play();
        console.log("[Player] Direct fallback played.");
    }
}

function autoDowngradeQuality() {
    if (!playerInstance || !state.availableResources || state.availableResources.length <= 1) return;
    if (userSelectedQuality) return;

    let currentQuality = playerInstance.quality;
    
    // Filter and sort available resolutions descending (e.g. [1080, 720, 480, 360])
    const resolutions = state.availableResources
        .map(r => r.resolution || 720)
        .filter((value, idx, self) => self.indexOf(value) === idx)
        .sort((a, b) => b - a);

    if (currentQuality === 0) {
        // If current quality is Auto (0), we are currently playing the highest resolution
        currentQuality = resolutions[0];
    }

    if (!currentQuality) return;

    const currentIndex = resolutions.indexOf(currentQuality);
    if (currentIndex !== -1 && currentIndex < resolutions.length - 1) {
        const nextLowerQuality = resolutions[currentIndex + 1];
        
        console.log(`[Player AutoQuality] Buffering threshold exceeded. Auto downgrading quality from ${currentQuality}p to ${nextLowerQuality}p...`);
        
        isSwitchingQuality = true;
        isProgrammaticQualityChange = true;
        
        showPlayerNotification(`Slow connection. Auto switched to ${nextLowerQuality}p.`);
        
        playerInstance.quality = nextLowerQuality;
        
        setTimeout(() => {
            isProgrammaticQualityChange = false;
            isSwitchingQuality = false;
        }, 1500);
    }
}

function showPlayerNotification(message) {
    let notification = document.getElementById("playerToast");
    if (!notification) {
        notification = document.createElement("div");
        notification.id = "playerToast";
        notification.style.position = "absolute";
        notification.style.top = "20px";
        notification.style.left = "50%";
        notification.style.transform = "translateX(-50%)";
        notification.style.backgroundColor = "rgba(24, 27, 34, 0.95)";
        notification.style.border = "1px solid var(--color-accent)";
        notification.style.color = "#ffffff";
        notification.style.padding = "8px 18px";
        notification.style.borderRadius = "20px";
        notification.style.fontSize = "12px";
        notification.style.fontWeight = "600";
        notification.style.zIndex = "100";
        notification.style.boxShadow = "var(--shadow-md)";
        notification.style.pointerEvents = "none";
        notification.style.transition = "opacity 0.3s ease";
        
        const playerContainer = document.querySelector(".plyr");
        if (playerContainer) {
            playerContainer.appendChild(notification);
        } else {
            document.body.appendChild(notification);
        }
    }
    
    notification.textContent = message;
    notification.style.opacity = "1";
    
    setTimeout(() => {
        if (notification) {
            notification.style.opacity = "0";
        }
    }, 4000);
}

// ==========================================================================
// COMMON EVENT BINDINGS
// ==========================================================================
function bindCommonEvents() {
    const searchBtn = document.getElementById("searchBtn");
    const searchInput = document.getElementById("searchInput");
    
    if (searchBtn && searchInput) {
        const handleSearch = () => {
            const query = searchInput.value.trim();
            if (!query) return;
            // Hide suggestion dropdown before navigating
            const dd = document.getElementById("searchSuggestDropdown");
            if (dd) dd.style.display = "none";
            // Always route search queries globally to /movies with type=0
            window.location.href = `/movies?keyword=${encodeURIComponent(query)}&type=0`;
        };

        searchBtn.onclick = handleSearch;
        searchInput.addEventListener("keypress", (e) => {
            if (e.key === "Enter") handleSearch();
        });

        // Add auto-suggestion dropdown
        let dropdown = document.getElementById("searchSuggestDropdown");
        if (!dropdown) {
            dropdown = document.createElement("div");
            dropdown.id = "searchSuggestDropdown";
            dropdown.className = "search-suggest-dropdown";
            const searchBox = searchInput.closest(".search-box");
            if (searchBox) {
                searchBox.appendChild(dropdown);
            }
        }

        let debounceTimeout;
        searchInput.addEventListener("input", () => {
            clearTimeout(debounceTimeout);
            const query = searchInput.value.trim();
            if (query.length < 1) {
                dropdown.style.display = "none";
                return;
            }

            debounceTimeout = setTimeout(async () => {
                try {
                    const res = await fetch(`/api/search/suggest?q=${encodeURIComponent(query)}`);
                    const data = await res.json();
                    if (data && data.code === 0 && data.data && data.data.items && data.data.items.length > 0) {
                        dropdown.innerHTML = "";
                        data.data.items.forEach(item => {
                            const itemEl = document.createElement("div");
                            itemEl.className = "suggest-item";

                            if (item.isKeyword) {
                                // Keyword suggestion — show search icon + word
                                itemEl.innerHTML = `
                                    <div class="suggest-icon"><i class="fa-solid fa-magnifying-glass"></i></div>
                                    <div class="suggest-info">
                                        <div class="suggest-title">${item.title}</div>
                                    </div>
                                `;
                                itemEl.onclick = (e) => {
                                    e.stopPropagation();
                                    dropdown.style.display = "none";
                                    window.location.href = `/movies?keyword=${encodeURIComponent(item.title)}&type=0`;
                                };
                            } else {
                                // Subject suggestion — show cover, type, year, rating
                                const typeText = item.subjectType === 2 ? "TV Series" : (item.subjectType === 1 ? "Movie" : "Anime");
                                const coverUrl = item.cover && item.cover.url ? item.cover.url : "/default-cover.png";
                                const year = item.releaseDate ? item.releaseDate.split("-")[0] : "";
                                const rating = item.rating || "7.5";
                                const upcomingBadge = item.hasResource === false ? `<span class="suggest-badge" style="background:rgba(225,29,72,0.2);color:#fb7185;border:1px solid rgba(225,29,72,0.4);">Upcoming</span>` : "";
                                itemEl.innerHTML = `
                                    <img class="suggest-cover" src="${coverUrl}" onerror="this.onerror=null; this.src='/default-cover.png';" alt="${item.title}">
                                    <div class="suggest-info">
                                        <div class="suggest-title">${item.title}</div>
                                        <div class="suggest-meta">
                                            ${upcomingBadge}
                                            <span class="suggest-badge">${typeText}</span>
                                            ${year ? `<span>${year}</span>` : ""}
                                            <span class="suggest-rating"><i class="fa-solid fa-star"></i> ${rating}</span>
                                        </div>
                                    </div>
                                `;
                                itemEl.onclick = (e) => {
                                    e.stopPropagation();
                                    dropdown.style.display = "none";
                                    const typeSeg = item.subjectType === 2 ? 'tv' : 'movie';
                                    const idParam = item.subjectId ? `?id=${encodeURIComponent(item.subjectId)}` : '';
                                    window.location.href = `/${typeSeg}/${encodeURIComponent(item.detailPath || '')}${idParam}`;
                                };
                            }
                            dropdown.appendChild(itemEl);
                        });
                        dropdown.style.display = "block";
                    } else {
                        dropdown.style.display = "none";
                    }
                } catch (e) {
                    console.error("Error loading suggestions", e);
                }
            }, 200);
        });

        // Close dropdown when clicking outside
        document.addEventListener("click", (e) => {
            if (!e.target.closest(".search-box")) {
                dropdown.style.display = "none";
            }
        });

        // Re-show dropdown if focused and has input
        searchInput.addEventListener("focus", () => {
            if (searchInput.value.trim().length >= 1 && dropdown.children.length > 0) {
                dropdown.style.display = "block";
            }
        });
    }

    // Toggle filter panel (on Movies / TV pages)
    const filterToggleBtn = document.getElementById("filterToggleBtn");
    const filterPanel = document.getElementById("filterPanel");
    
    if (filterToggleBtn && filterPanel) {
        filterToggleBtn.onclick = () => {
            filterToggleBtn.classList.toggle("active");
            filterPanel.classList.toggle("open");
        };
    }

    // Pagination Click Events
    const prevBtn = document.getElementById("prevPageBtn");
    const nextBtn = document.getElementById("nextPageBtn");

    if (prevBtn) {
        prevBtn.onclick = () => {
            if (state.currentPage > 1) {
                state.currentPage--;
                loadSearchResults();
            }
        };
    }

    if (nextBtn) {
        nextBtn.onclick = () => {
            state.currentPage++;
            loadSearchResults();
        };
    }
    // Wire all "Download App" buttons to the secure verification page
    document.querySelectorAll(".download-app-btn").forEach(btn => {
        btn.onclick = () => { window.location.href = "/download"; };
    });
}

function removeContinueWatchingItem(subjectId) {
    let history = [];
    try {
        history = JSON.parse(localStorage.getItem("streamfit_history")) || [];
    } catch(e) {}
    history = history.filter(item => String(item.subjectId) !== String(subjectId));
    localStorage.setItem("streamfit_history", JSON.stringify(history));
    renderContinueWatchingSection();
}

function renderContinueWatchingSection() {
    const section = document.getElementById("continueWatchingSection");
    const grid = document.getElementById("continueWatchingGrid");
    const clearBtn = document.getElementById("clearContinueWatchingBtn");
    if (!section || !grid) return;
    
    let history = [];
    try {
        history = JSON.parse(localStorage.getItem("streamfit_history")) || [];
    } catch(e) {}
    
    if (history.length === 0) {
        section.style.display = "none";
        return;
    }
    
    section.style.display = "block";
    grid.innerHTML = "";
    
    // Deduplicate by subjectId (keep most recent = first occurrence)
    const seen = new Set();
    const dedupedHistory = history.filter(item => {
        const key = String(item.subjectId);
        if (seen.has(key)) return false;
        seen.add(key);
        return true;
    });
    
    dedupedHistory.forEach(item => {
        const card = document.createElement("div");
        card.className = "continue-card";
        
        const isTv = item.subjectType === 2 || item.season > 0;
        const subTitle = (item.season > 0) ? `S${item.season} E${item.episode}` : "Movie";
        const cleanTitle = item.title || "Untitled";
        
        let langBadge = "";
        if (cleanTitle.toLowerCase().includes("[hindi]")) {
            langBadge = `<span class="continue-badge lang">Hindi</span>`;
        } else if (cleanTitle.toLowerCase().includes("[bengali]")) {
            langBadge = `<span class="continue-badge lang">Bengali</span>`;
        }
        
        card.onclick = (e) => {
            if (e.target.closest(".continue-remove-btn")) return;
            const typeSegment = isTv ? "tv" : "movie";
            const path = item.detailPath || "";
            let url = `/watch/${typeSegment}/${encodeURIComponent(path)}?id=${encodeURIComponent(item.subjectId)}`;
            if (item.season > 0) {
                url += `&season=${item.season}&episode=${item.episode}`;
            }
            window.location.href = url;
        };
        
        const coverUrl = item.cover || "/default-cover.png";
        
        card.innerHTML = `
            <div class="continue-poster-wrapper">
                <img src="${coverUrl}" alt="${cleanTitle}" onerror="this.onerror=null; this.src='/default-cover.png';" loading="lazy">
                <div class="continue-badges">
                    <span class="continue-badge ep">${subTitle}</span>
                    ${langBadge}
                </div>
                <button class="continue-remove-btn" title="Remove from Continue Watching" onclick="event.stopPropagation(); removeContinueWatchingItem('${item.subjectId}');">
                    <i class="fa-solid fa-xmark"></i>
                </button>
                <div class="continue-play-overlay">
                    <i class="fa-solid fa-play"></i>
                </div>
                <div class="continue-progress-container">
                    <div class="continue-progress-bar" style="width: ${item.progressPercent}%"></div>
                </div>
            </div>
            <div class="continue-info">
                <h3 class="continue-title" title="${cleanTitle}">${cleanTitle}</h3>
                <div class="continue-sub-title">
                    <span class="ep-tag">${subTitle}</span>
                    <span class="percent-tag"><i class="fa-solid fa-play" style="font-size:8px;"></i> ${item.progressPercent}%</span>
                </div>
            </div>
        `;
        grid.appendChild(card);
    });
    
    if (clearBtn) {
        clearBtn.onclick = (e) => {
            e.stopPropagation();
            if (confirm("Clear your Continue Watching history?")) {
                localStorage.removeItem("streamfit_history");
                // Clear all progress keys
                for (let i = 0; i < localStorage.length; i++) {
                    const key = localStorage.key(i);
                    if (key && key.startsWith("streamfit_progress_")) {
                        localStorage.removeItem(key);
                        i--;
                    }
                }
                renderContinueWatchingSection();
            }
        };
    }
}

// ==========================================================================
// PUSH NOTIFICATION PERMISSIONS & POLLING LOGIC
// ==========================================================================
function initWebNotifications() {
    if (!('Notification' in window)) {
        console.warn("This browser does not support desktop notifications.");
        return;
    }

    if (Notification.permission === "default") {
        // Request permission on startup
        Notification.requestPermission().then(permission => {
            if (permission === "granted") {
                console.log("Notification permission granted.");
                startNotificationPolling();
            }
        });
    } else if (Notification.permission === "granted") {
        startNotificationPolling();
    }
}

let notificationPollInterval = null;
function startNotificationPolling() {
    if (notificationPollInterval) return;
    
    // Poll immediately, then every 30 seconds
    checkLatestNotification();
    notificationPollInterval = setInterval(checkLatestNotification, 30000);
}

async function checkLatestNotification() {
    try {
        const data = await apiGet("/api/notifications/latest");
        if (data && data.notification) {
            const noti = data.notification;
            const savedId = localStorage.getItem("streamfit_latest_noti_id");
            
            // If there's no saved ID, we initialize it without showing the notification
            if (savedId === null) {
                localStorage.setItem("streamfit_latest_noti_id", noti.id);
                return;
            }
            
            // If the notification is newer than what we've seen
            if (parseInt(noti.id) > parseInt(savedId)) {
                localStorage.setItem("streamfit_latest_noti_id", noti.id);
                
                if (Notification.permission === "granted") {
                    const notification = new Notification(noti.title, {
                        body: noti.message,
                        icon: "/favicon.svg"
                    });
                    
                    notification.onclick = () => {
                        window.focus();
                        if (noti.subjectId) {
                            window.location.href = '/movie/' + (noti.detailPath || 'unknown');
                        }
                    };
                }
            }
        }
    } catch (e) {
        console.error("Error polling notifications:", e);
    }
}

// ==========================================================================
// TOAST NOTIFICATIONS
// ==========================================================================
function showToastNotification(message) {
    let container = document.getElementById("toast-notification-container");
    if (!container) {
        container = document.createElement("div");
        container.id = "toast-notification-container";
        container.style.position = "fixed";
        container.style.bottom = "80px";
        container.style.right = "20px";
        container.style.zIndex = "3000";
        container.style.display = "flex";
        container.style.flexDirection = "column";
        container.style.gap = "10px";
        document.body.appendChild(container);
    }
    
    const toast = document.createElement("div");
    toast.style.background = "rgba(16, 17, 20, 0.95)";
    toast.style.color = "#ffffff";
    toast.style.border = "1px solid var(--color-accent, #1dd171)";
    toast.style.padding = "12px 24px";
    toast.style.borderRadius = "8px";
    toast.style.boxShadow = "0 8px 30px rgba(0,0,0,0.5)";
    toast.style.fontSize = "13px";
    toast.style.fontWeight = "700";
    toast.style.fontFamily = "sans-serif";
    toast.style.animation = "slideIn 0.3s ease-out";
    toast.textContent = message;
    
    container.appendChild(toast);
    setTimeout(() => {
        toast.style.opacity = "0";
        toast.style.transition = "opacity 0.5s ease-out";
        setTimeout(() => toast.remove(), 500);
    }, 4000);
}

// ==========================================================================
// LIVE SPORTS & LIVE TV METHODS
// ==========================================================================
async function initHomePageSportsAndTv() {
    // 1. Init Sports
    const sportsGrid = document.getElementById("liveSportsGrid");
    const sportsSection = document.getElementById("liveSportsSection");
    if (sportsGrid && sportsSection) {
        const result = await apiGet("/api/sports/live");
        if (result && result.code === 0 && result.list && result.list.length > 0) {
            sportsGrid.innerHTML = "";
            result.list.forEach(sport => {
                const card = createSportsCard(sport);
                sportsGrid.appendChild(card);
            });
            sportsSection.style.display = "block";
            setupSliderNavigation(sportsGrid);
        } else {
            sportsSection.style.display = "none";
        }
    }

    // 2. Init Live TV
    const tvGrid = document.getElementById("liveTvGrid");
    const tvSection = document.getElementById("liveTvSection");
    if (tvGrid && tvSection) {
        const result = await apiGet("/api/tv/channels");
        if (result && result.code === 0 && result.list && result.list.length > 0) {
            tvGrid.innerHTML = "";
            result.list.slice(0, 12).forEach(chan => {
                const card = createTvCard(chan);
                tvGrid.appendChild(card);
            });
            tvSection.style.display = "block";
            setupSliderNavigation(tvGrid);
        } else {
            tvSection.style.display = "none";
        }
    }
}

function createSportsCard(sport) {
    const card = document.createElement("div");
    card.className = "sports-card";
    
    // Check if teams are configured
    const hasTeams = sport.team1Name && sport.team2Name;
    const eventLogo = sport.logo || "https://images.unsplash.com/photo-1508098682722-e99c43a406b2?w=120&q=80";
    
    let innerHTML = `<span class="sports-live-badge">LIVE</span>`;
    
    if (hasTeams) {
        innerHTML += `
            <div class="sports-card-teams">
                <div class="sports-card-team">
                    <img src="${sport.team1Logo || 'https://images.unsplash.com/photo-1508098682722-e99c43a406b2?w=120&q=80'}" onerror="this.src='https://images.unsplash.com/photo-1508098682722-e99c43a406b2?w=120&q=80'">
                    <span>${sport.team1Name}</span>
                </div>
                <div class="sports-card-vs">VS</div>
                <div class="sports-card-team">
                    <img src="${sport.team2Logo || 'https://images.unsplash.com/photo-1508098682722-e99c43a406b2?w=120&q=80'}" onerror="this.src='https://images.unsplash.com/photo-1508098682722-e99c43a406b2?w=120&q=80'">
                    <span>${sport.team2Name}</span>
                </div>
            </div>
        `;
    } else {
        innerHTML += `
            <div class="sports-card-event">
                <img src="${eventLogo}" onerror="this.src='https://images.unsplash.com/photo-1508098682722-e99c43a406b2?w=120&q=80'">
                <div class="sports-card-event-info">
                    <span class="event-name">${sport.title}</span>
                    <span class="event-subtitle">Live Broadcast</span>
                </div>
            </div>
        `;
    }
    
    innerHTML += `<div class="sports-card-title">${sport.title}</div>`;
    card.innerHTML = innerHTML;
    
    card.onclick = () => playLiveStreamSelector(sport, 'sports');
    return card;
}

function createTvCard(channel) {
    const card = document.createElement("div");
    card.className = "tv-card";
    
    const logoUrl = channel.logo || 'https://images.unsplash.com/photo-1598257006458-087169a1f08d?w=120&q=80';
    // Strip leading order prefix like "0. ", "1. ", "12. " from channel names
    const cleanName = (channel.name || '').replace(/^\d+\.\s*/, '').trim();
    
    card.innerHTML = `
        <div class="tv-card-logo-container">
            <img src="${logoUrl}" onerror="this.src='https://images.unsplash.com/photo-1598257006458-087169a1f08d?w=120&q=80'">
        </div>
        <div class="tv-card-title">${cleanName}</div>
    `;
    
    card.onclick = () => playLiveStreamSelector(channel, 'tv');
    return card;
}

function playLiveStreamSelector(item, mediaType) {
    const streamLinks = item.streamLinks || [];
    if (streamLinks.length === 0) {
        showToastNotification("No active stream links found for this channel.");
        return;
    }
    
    const title = item.title || item.name;
    
    if (streamLinks.length === 1) {
        // Play directly
        playLiveStream(title, streamLinks[0].url, streamLinks, 0, item.referer, item.origin, item.useBdProxy);
    } else {
        // Show selection modal
        const modalOverlay = document.getElementById("sportsModalOverlay");
        const modalTitle = document.getElementById("sportsModalTitle");
        const linksList = document.getElementById("sportsLinksList");
        const modalClose = document.getElementById("sportsModalClose");
        
        if (!modalOverlay || !linksList) return;
        
        modalTitle.textContent = title;
        linksList.innerHTML = "";
        
        streamLinks.forEach((link, idx) => {
            const btn = document.createElement("button");
            btn.className = "sports-link-btn";
            btn.innerHTML = `
                <span>${link.label || 'Stream Link ' + (idx + 1)}</span>
                <i class="fa-solid fa-play"></i>
            `;
            btn.onclick = () => {
                modalOverlay.classList.remove("show");
                // Individual link overrides
                const linkReferer = link.referer || item.referer || "";
                const linkOrigin = link.origin || item.origin || "";
                const linkUserAgent = link.userAgent || "";
                const linkUseBd = link.useBdProxy !== undefined ? link.useBdProxy : item.useBdProxy;
                
                playLiveStream(title, link.url, streamLinks, idx, linkReferer, linkOrigin, linkUseBd, linkUserAgent);
            };
            linksList.appendChild(btn);
        });
        
        modalOverlay.classList.add("show");
        
        modalClose.onclick = () => modalOverlay.classList.remove("show");
        modalOverlay.onclick = (e) => {
            if (e.target === modalOverlay) modalOverlay.classList.remove("show");
        };
    }
}

function playLiveStream(title, url, streamLinks, index, referer, origin, useBdProxy, userAgent = "") {
    // Encode full stream links fallback config in query params
    const linksConfig = streamLinks.map(l => ({
        url: l.url,
        label: l.label,
        referer: l.referer || referer || "",
        origin: l.origin || origin || "",
        userAgent: l.userAgent || userAgent || "",
        useBdProxy: l.useBdProxy !== undefined ? l.useBdProxy : useBdProxy
    }));
    
    // Proxy URL
    const proxiedUrl = `/api/sports/proxy?url=${encodeURIComponent(url)}&referer=${encodeURIComponent(referer || '')}&origin=${encodeURIComponent(origin || '')}&userAgent=${encodeURIComponent(userAgent || '')}&use_bd_proxy=${useBdProxy ? 'true' : 'false'}`;
    
    // Navigate to watch page
    window.location.href = `/watch?type=sports&title=${encodeURIComponent(title)}&url=${encodeURIComponent(proxiedUrl)}&links=${encodeURIComponent(JSON.stringify(linksConfig))}&index=${index}`;
}

async function initLiveTvPage() {
    const tvChannelsGrid = document.getElementById("tvChannelsGrid");
    const tvCategoryTabs = document.getElementById("tvCategoryTabs");
    if (!tvChannelsGrid) return;
    
    tvChannelsGrid.innerHTML = '<div class="card-shimmer"></div><div class="card-shimmer"></div><div class="card-shimmer"></div>';
    
    const result = await apiGet("/api/tv/channels");
    tvChannelsGrid.innerHTML = "";
    
    if (result && result.code === 0 && result.list && result.list.length > 0) {
        window.tvPageChannels = result.list;
        
        // Extract unique categories
        const categories = new Set();
        result.list.forEach(c => {
            if (c.category) categories.add(c.category);
        });
        
        // Render tabs
        if (tvCategoryTabs) {
            tvCategoryTabs.innerHTML = '<button class="cat-tab active" data-category="*">All Channels</button>';
            categories.forEach(cat => {
                const btn = document.createElement("button");
                btn.className = "cat-tab";
                btn.dataset.category = cat;
                btn.textContent = cat;
                tvCategoryTabs.appendChild(btn);
            });
            
            // Connect tabs click
            tvCategoryTabs.querySelectorAll(".cat-tab").forEach(tab => {
                tab.onclick = () => {
                    tvCategoryTabs.querySelectorAll(".cat-tab").forEach(b => b.classList.remove("active"));
                    tab.classList.add("active");
                    renderFilteredTvChannels(tab.dataset.category);
                };
            });
        }
        
        renderFilteredTvChannels("*");
    } else {
        tvChannelsGrid.innerHTML = '<div style="color:var(--text-muted);padding:20px;">No Live TV channels available at the moment.</div>';
    }
    
    // Connect search
    const searchInput = document.getElementById("tvSearchInput");
    if (searchInput) {
        searchInput.oninput = () => {
            const query = searchInput.value.trim().toLowerCase();
            renderFilteredTvChannels(null, query);
        };
    }
}

function renderFilteredTvChannels(category, searchQuery = "") {
    const grid = document.getElementById("tvChannelsGrid");
    if (!grid || !window.tvPageChannels) return;
    grid.innerHTML = "";
    
    const activeTab = document.querySelector("#tvCategoryTabs .cat-tab.active");
    const activeCat = category !== null ? category : (activeTab ? activeTab.dataset.category : "*");
    
    const filtered = window.tvPageChannels.filter(c => {
        const matchesCategory = activeCat === "*" || c.category === activeCat;
        const matchesSearch = !searchQuery || c.name.toLowerCase().includes(searchQuery);
        return matchesCategory && matchesSearch;
    });
    
    if (filtered.length > 0) {
        filtered.forEach(chan => {
            grid.appendChild(createTvCard(chan));
        });
    } else {
        grid.innerHTML = '<div style="color:var(--text-muted);padding:20px;grid-column:1/-1;text-align:center;">No channels found.</div>';
    }
}

function handlePlaybackError() {
    if (state.fallbackLinks && state.fallbackLinks.length > 0 && state.currentFallbackIndex < state.fallbackLinks.length - 1) {
        state.currentFallbackIndex++;
        const nextLink = state.fallbackLinks[state.currentFallbackIndex];
        console.log(`[Auto-Fallback] Stream failed. Trying backup Link ${state.currentFallbackIndex + 1}: ${nextLink.label || nextLink.name}`);
        
        // Notify the user via toast
        showToastNotification(`Stream offline. Trying backup link: ${nextLink.label || nextLink.name || 'Backup'}`);
        
        // Re-route URL parameters
        const urlParams = new URLSearchParams(window.location.search);
        urlParams.set("url", nextLink.url);
        history.replaceState({}, "", `${window.location.pathname}?${urlParams.toString()}`);
        
        // Proxy URL
        const proxiedUrl = `/api/sports/proxy?url=${encodeURIComponent(nextLink.url)}&referer=${encodeURIComponent(nextLink.referer || '')}&origin=${encodeURIComponent(nextLink.origin || '')}&userAgent=${encodeURIComponent(nextLink.userAgent || '')}&use_bd_proxy=${nextLink.useBdProxy ? 'true' : 'false'}`;
        
        state.availableResources = [{
            resourceId: "sports_stream",
            resolution: 0,
            size: 0,
            resourceLink: proxiedUrl
        }];
        
        // Update stream links grid visual active state
        renderLiveStreamLinks();

        // Play resources
        playResources();
    }
}

function renderLiveStreamLinks() {
    const container = document.getElementById("watchLiveStreamSelector");
    const grid = document.getElementById("watchStreamLinksGrid");
    if (!container || !grid) return;

    if (state.fallbackLinks && state.fallbackLinks.length > 0) {
        container.style.display = "block";
        grid.innerHTML = "";
        
        state.fallbackLinks.forEach((link, idx) => {
            const btn = document.createElement("button");
            btn.className = "season-tab";
            if (idx === state.currentFallbackIndex) {
                btn.className += " active";
            }
            btn.textContent = link.label || `Link ${idx + 1}`;
            btn.onclick = () => {
                if (idx === state.currentFallbackIndex) return;
                
                // Switch to this link
                state.currentFallbackIndex = idx;
                
                // Show loader toast
                showToastNotification(`Switching to: ${link.label || 'Link ' + (idx + 1)}`);
                
                // Re-route URL parameters
                const urlParams = new URLSearchParams(window.location.search);
                urlParams.set("url", link.url);
                urlParams.set("index", idx);
                history.replaceState({}, "", `${window.location.pathname}?${urlParams.toString()}`);
                
                // Proxy URL
                const proxiedUrl = `/api/sports/proxy?url=${encodeURIComponent(link.url)}&referer=${encodeURIComponent(link.referer || '')}&origin=${encodeURIComponent(link.origin || '')}&userAgent=${encodeURIComponent(link.userAgent || '')}&use_bd_proxy=${link.useBdProxy ? 'true' : 'false'}`;
                
                state.availableResources = [{
                    resourceId: "sports_stream",
                    resolution: 0,
                    size: 0,
                    resourceLink: proxiedUrl
                }];
                
                // Re-render links to update active state
                renderLiveStreamLinks();
                
                // Play
                playResources();
            };
            grid.appendChild(btn);
        });
    } else {
        container.style.display = "none";
    }
}

// Active user heartbeat ping for admin metrics
try {
    fetch('/api/ping', { cache: 'no-store' }).catch(() => {});
    setInterval(() => {
        fetch('/api/ping', { cache: 'no-store' }).catch(() => {});
    }, 30000);
} catch (_) {}

