package com.streamhit.app.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import com.streamhit.app.data.model.SubjectSummary
import com.streamhit.app.ui.components.AppShareDialog
import com.streamhit.app.ui.components.AppBrandHeader
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clipToBounds
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamhit.app.data.local.HomeCacheManager
import com.streamhit.app.data.local.RecentHistoryManager
import com.streamhit.app.data.local.WatchHistoryItem
import com.streamhit.app.data.model.BannerItem
import com.streamhit.app.data.model.HomeSection
import com.streamhit.app.data.repository.MovieRepository
import com.streamhit.app.ui.components.HeroBannerSlider
import com.streamhit.app.ui.components.MediaCard
import com.streamhit.app.ui.components.SectionHeader
import com.streamhit.app.ui.theme.*

@Composable
fun rememberShimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_anim"
    )
    return Brush.linearGradient(
        colors = listOf(
            Color(0xFF161C2C),
            Color(0xFF28344E),
            Color(0xFF161C2C)
        ),
        start = Offset(translateAnim - 350f, translateAnim - 350f),
        end = Offset(translateAnim, translateAnim)
    )
}

@Composable
fun HomeScreenShimmer(brush: Brush) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // Search bar skeleton
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(brush)
        )
        Spacer(modifier = Modifier.height(14.dp))

        // Category pills skeleton
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(5) {
                Box(
                    modifier = Modifier
                        .width(72.dp)
                        .height(30.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(brush)
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Hero banner skeleton
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(215.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(brush)
        )
        Spacer(modifier = Modifier.height(22.dp))

        // Section title skeleton
        Box(
            modifier = Modifier
                .width(140.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(brush)
        )
        Spacer(modifier = Modifier.height(14.dp))

        // Media cards skeleton row
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(4) {
                Column {
                    Box(
                        modifier = Modifier
                            .width(115.dp)
                            .height(165.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(brush)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .width(80.dp)
                            .height(12.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(brush)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: MovieRepository,
    onMediaClick: (detailPath: String, subjectId: String, subjectType: Int) -> Unit,
    onPlayDirect: (
        streamUrl: String,
        title: String,
        subjectId: String,
        detailPath: String,
        coverUrl: String,
        seasonNum: Int,
        episodeNum: Int,
        startPositionMs: Long,
        dubLanguage: String?,
        dubSubjectId: String?,
        dubDetailPath: String?,
        dubName: String?
    ) -> Unit = { _, _, _, _, _, _, _, _, _, _, _, _ -> },
    onNavigateToSearch: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var banners by remember { mutableStateOf<List<BannerItem>>(emptyList()) }
    var sections by remember { mutableStateOf<List<HomeSection>>(emptyList()) }
    var recentHistory by remember { mutableStateOf<List<WatchHistoryItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedCategoryIndex by remember { mutableStateOf(0) }
    var showShareDialog by remember { mutableStateOf(false) }

    val pullRefreshState = rememberPullToRefreshState()

    val categories = listOf("Trending", "Movies", "TV Shows", "Bengali", "Hindi Dub", "Anime")
    val categoryIcons = listOf("🔥", "🎬", "📺", "🇧🇩", "🇮🇳", "⚡")

    val listState = rememberLazyListState()
    val categoryData = remember { mutableStateMapOf<Int, List<SubjectSummary>>() }
    val categoryPageMap = remember { mutableStateMapOf<Int, Int>() }
    val canLoadMoreMap = remember { mutableStateMapOf<Int, Boolean>() }
    var isCategoryLoading by remember { mutableStateOf(false) }
    var isLoadingMoreCategory by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun fetchCategory(catIndex: Int, page: Int = 1, append: Boolean = false) {
        val cat = categories[catIndex]
        scope.launch {
            if (!append) isCategoryLoading = true else isLoadingMoreCategory = true
            val items = if (catIndex == 0) {
                repository.filterContent(page = page, sort = "recommend")
            } else {
                repository.getCategoryContent(cat, page = page)
            }
            if (append) {
                val cur = categoryData[catIndex] ?: emptyList()
                val combined = (cur + items).distinctBy { it.subjectId.ifEmpty { it.detailPath } }
                if (combined.size == cur.size || items.isEmpty()) {
                    canLoadMoreMap[catIndex] = false
                }
                categoryData[catIndex] = combined
            } else {
                categoryData[catIndex] = items
                canLoadMoreMap[catIndex] = items.isNotEmpty()
            }
            categoryPageMap[catIndex] = page
            isCategoryLoading = false
            isLoadingMoreCategory = false
        }
    }

    LaunchedEffect(selectedCategoryIndex) {
        listState.scrollToItem(0)
        if (!categoryData.containsKey(selectedCategoryIndex)) {
            fetchCategory(selectedCategoryIndex, page = 1)
        }
    }

    val shouldLoadMore = remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val canLoadMore = canLoadMoreMap[selectedCategoryIndex] ?: true
            totalItems > 0 && lastVisibleItemIndex >= totalItems - 3 && canLoadMore
        }
    }

    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value && !isCategoryLoading && !isLoadingMoreCategory) {
            val curPage = categoryPageMap[selectedCategoryIndex] ?: 1
            fetchCategory(selectedCategoryIndex, page = curPage + 1, append = true)
        }
    }

    suspend fun refreshHomeFeed() {
        if (selectedCategoryIndex > 0) {
            fetchCategory(selectedCategoryIndex, page = 1)
            return
        }
        try {
            coroutineScope {
                val histDef = async { RecentHistoryManager.getRecentHistory(context) }
                val bDef = async { repository.getBanners() }
                val sDef = async { repository.getHomeFeed() }
                recentHistory = histDef.await()
                val b = bDef.await()
                val s = sDef.await()
                val finalBanners = if (b.isNotEmpty()) b else (s.firstOrNull { it.banner != null && it.banner.items.isNotEmpty() }?.banner?.items ?: emptyList())
                if (finalBanners.isNotEmpty() || s.isNotEmpty()) {
                    banners = finalBanners
                    sections = s
                    HomeCacheManager.saveHomeCache(context, finalBanners, s)
                }
            }
        } catch (_: Exception) {}
    }

    if (pullRefreshState.isRefreshing) {
        LaunchedEffect(true) {
            val startTime = System.currentTimeMillis()
            refreshHomeFeed()
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed < 350) delay(350 - elapsed)
            pullRefreshState.endRefresh()
        }
    }

    // Live refresh when returning to HomeScreen from PlayerActivity
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                recentHistory = RecentHistoryManager.getRecentHistory(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        recentHistory = RecentHistoryManager.getRecentHistory(context)
        val cachedBanners = HomeCacheManager.getCachedBanners(context)
        val cachedSections = HomeCacheManager.getCachedSections(context)
        val isExpired = HomeCacheManager.isCacheExpired(context)

        // Show cache instantly if available
        if (cachedBanners.isNotEmpty() || cachedSections.isNotEmpty()) {
            banners = cachedBanners
            sections = cachedSections
            val seedItems = cachedSections.flatMap { it.subjects ?: emptyList() }
            if (seedItems.isNotEmpty() && !categoryData.containsKey(0)) {
                categoryData[0] = seedItems
            }
            isLoading = false
        }

        // Fetch fresh data once a day (or if cache is empty)
        if (isExpired || cachedSections.isEmpty()) {
            try {
                val b = repository.getBanners()
                val s = repository.getHomeFeed()
                val finalBanners = if (b.isNotEmpty()) b else (s.firstOrNull { it.banner != null && it.banner.items.isNotEmpty() }?.banner?.items ?: emptyList())
                if (finalBanners.isNotEmpty() || s.isNotEmpty()) {
                    banners = finalBanners
                    sections = s
                    val seedItems = s.flatMap { it.subjects ?: emptyList() }
                    if (seedItems.isNotEmpty() && (categoryData[0]?.isEmpty() != false)) {
                        categoryData[0] = seedItems
                    }
                    HomeCacheManager.saveHomeCache(context, finalBanners, s)
                }
            } catch (_: Exception) {}
            isLoading = false
        }

        if (!categoryData.containsKey(0)) {
            fetchCategory(0, page = 1)
        }
    }

    if (isLoading) {
        val shimmerBrush = rememberShimmerBrush()
        HomeScreenShimmer(shimmerBrush)
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        // Ultra-compact Luxury Cinema Header (Brand + Search Capsule + Share)
        Surface(
            color = Color(0xFF070B14).copy(alpha = 0.98f),
            border = androidx.compose.foundation.BorderStroke(0.8.dp, Color.White.copy(alpha = 0.05f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: Logo & Gradient Typography
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White)
                    ) {
                        Image(
                            painter = painterResource(id = com.streamhit.app.R.drawable.app_logo),
                            contentDescription = "NXLY",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "NXLY",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = " STREAM",
                            color = AccentEmerald,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                // Right: Integrated Search Capsule & Share
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                        modifier = Modifier.clickable { onNavigateToSearch() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = AccentCyan,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Search...",
                                color = TextMuted,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    val infiniteTransition = rememberInfiniteTransition(label = "homeSharePulse")
                    val pulseScale by infiniteTransition.animateFloat(
                        initialValue = 0.94f,
                        targetValue = 1.05f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1200, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "homePulse"
                    )

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = AccentEmerald.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AccentEmerald.copy(alpha = 0.50f)),
                        modifier = Modifier
                            .graphicsLayer {
                                scaleX = pulseScale
                                scaleY = pulseScale
                            }
                            .clickable { showShareDialog = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share",
                                tint = AccentEmerald,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Share",
                                color = AccentEmerald,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .nestedScroll(pullRefreshState.nestedScrollConnection)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(BgDark),
                contentPadding = PaddingValues(bottom = 90.dp)
            ) {
        // Category Filter Row (Directly below header - saves massive space)
        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                itemsIndexed(categories) { index, cat ->
                    val isSelected = selectedCategoryIndex == index
                    val interactionSource = remember { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()
                    val isFocused by interactionSource.collectIsFocusedAsState()

                    val chipScale by animateFloatAsState(
                        targetValue = when {
                            isPressed -> 0.92f
                            isFocused -> 1.14f
                            else -> 1.0f
                        },
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        label = "catChipScale"
                    )

                    val bgColor by animateColorAsState(
                        targetValue = when {
                            isFocused -> Color(0xFF00F0FF).copy(alpha = 0.30f)
                            isSelected -> AccentEmerald.copy(alpha = 0.22f)
                            else -> Color.White.copy(alpha = 0.04f)
                        },
                        label = "catBg"
                    )
                    val borderColor by animateColorAsState(
                        targetValue = when {
                            isFocused -> Color(0xFF00F0FF)
                            isSelected -> AccentEmerald
                            else -> Color.White.copy(alpha = 0.10f)
                        },
                        label = "catBorder"
                    )
                    val textColor by animateColorAsState(
                        targetValue = when {
                            isFocused -> Color.White
                            isSelected -> AccentEmerald
                            else -> TextSecondary
                        },
                        label = "catText"
                    )

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = bgColor,
                        border = androidx.compose.foundation.BorderStroke(if (isFocused) 2.5.dp else 1.dp, borderColor),
                        modifier = Modifier
                            .graphicsLayer {
                                scaleX = chipScale
                                scaleY = chipScale
                            }
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) { selectedCategoryIndex = index }
                            .focusable(interactionSource = interactionSource)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                text = categoryIcons[index],
                                fontSize = if (isFocused) 13.sp else 11.5.sp
                            )
                            Text(
                                text = cat,
                                color = textColor,
                                fontSize = if (isFocused) 12.5.sp else 11.5.sp,
                                fontWeight = if (isSelected || isFocused) FontWeight.Black else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        // Hero Banner
        if (banners.isNotEmpty() && selectedCategoryIndex == 0) {
            item {
                HeroBannerSlider(
                    banners = banners,
                    onBannerClick = { item ->
                        val path = item.detailPath.ifEmpty { item.subjectId }
                        onMediaClick(path, item.subjectId, item.subjectType)
                    }
                )
            }
        }

        // Continue Watching / Recently Viewed
        if (recentHistory.isNotEmpty() && selectedCategoryIndex == 0) {
            item {
                Column(modifier = Modifier.padding(vertical = 12.dp)) {
                    SectionHeader(title = "Continue Watching")

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(recentHistory) { item ->
                            ContinueWatchingCard(
                                item = item,
                                onClick = {
                                    val path = item.detailPath.ifEmpty { item.subjectId }
                                    onMediaClick(path, item.subjectId, item.subjectType)
                                },
                                onResumeClick = {
                                    val path = item.detailPath.ifEmpty { item.subjectId }
                                    onPlayDirect(
                                        item.streamUrl,
                                        item.title,
                                        item.subjectId,
                                        path,
                                        item.coverUrl,
                                        item.seasonNum,
                                        item.episodeNum,
                                        item.positionMs,
                                        item.dubLanguage,
                                        item.dubSubjectId,
                                        item.dubDetailPath,
                                        item.dubName
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }

        // Share App Invite Card
        if (selectedCategoryIndex == 0) {
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF0F1524),
                    border = androidx.compose.foundation.BorderStroke(
                        0.8.dp,
                        Brush.horizontalGradient(
                            listOf(
                                AccentEmerald.copy(alpha = 0.45f),
                                AccentCyan.copy(alpha = 0.45f),
                                Color.White.copy(alpha = 0.05f)
                            )
                        )
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .clickable { showShareDialog = true }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(AccentEmerald.copy(alpha = 0.25f), AccentCyan.copy(alpha = 0.15f))
                                    )
                                )
                                .border(1.dp, AccentEmerald.copy(alpha = 0.35f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                tint = AccentEmerald,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Invite Friends & Stream Free",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp
                            )
                            Text(
                                text = "Share NXLY with family & friends! 🍿",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    Brush.horizontalGradient(listOf(AccentEmerald, AccentCyan))
                                )
                                .clickable { showShareDialog = true }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "Share",
                                color = Color.Black,
                                fontWeight = FontWeight.Black,
                                fontSize = 11.5.sp
                            )
                        }
                    }
                }
            }
        }

        if (selectedCategoryIndex == 0) {
            // Home Sections (Trending Movies & Series)
            itemsIndexed(sections.filter { it.subjects != null && it.subjects.isNotEmpty() }) { sectionIndex, section ->
                val subjects = section.subjects ?: emptyList()
                val title = section.title ?: "Trending"
                val isRankedSection = sectionIndex == 0 || title.contains("Top", ignoreCase = true) || title.contains("Trending", ignoreCase = true)

                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    SectionHeader(title = title)

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(subjects) { index, subject ->
                            MediaCard(
                                item = subject,
                                rankNumber = if (isRankedSection && index < 10) (index + 1) else null,
                                onClick = {
                                    val path = subject.detailPath.ifEmpty { subject.subjectId }
                                    onMediaClick(path, subject.subjectId, subject.subjectType)
                                }
                            )
                        }
                    }
                }
            }

            if (sections.isEmpty() && (categoryData[0]?.isEmpty() != false)) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No content found. Check connection.",
                            color = TextMuted,
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                // OnStream / Cineb Infinite 3-Column Grid on Home Feed
                val homeGridItems = categoryData[0] ?: emptyList()
                if (homeGridItems.isNotEmpty()) {
                    item {
                        SectionHeader(title = "🔥 Popular & Latest Releases")
                    }

                    val chunked = homeGridItems.chunked(3)
                    items(chunked) { rowItems ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowItems.forEach { subject ->
                                Box(modifier = Modifier.weight(1f)) {
                                    MediaCard(
                                        item = subject,
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            val path = subject.detailPath.ifEmpty { subject.subjectId }
                                            onMediaClick(path, subject.subjectId, subject.subjectType)
                                        }
                                    )
                                }
                            }
                            repeat(3 - rowItems.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }

                    if (isLoadingMoreCategory) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 18.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = AccentEmerald,
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        text = "Loading more titles...",
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Dedicated Rich Category View (Bengali, Hindi Dub, Anime, Movies, TV Shows)
            val catItems = categoryData[selectedCategoryIndex] ?: emptyList()

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .width(3.5.dp)
                                .height(18.dp)
                                .background(
                                    Brush.verticalGradient(listOf(AccentCyan, AccentEmerald)),
                                    RoundedCornerShape(2.dp)
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${categories[selectedCategoryIndex]} Collection",
                            color = Color.White,
                            fontSize = 17.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (catItems.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = AccentEmerald.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AccentEmerald.copy(alpha = 0.35f))
                        ) {
                            Text(
                                text = "${catItems.size}+ Titles",
                                color = AccentEmerald,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            if (isCategoryLoading && catItems.isEmpty()) {
                // Category Shimmer 3-column Grid
                item {
                    val brush = rememberShimmerBrush()
                    Column(modifier = Modifier.padding(horizontal = 14.dp)) {
                        repeat(4) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                repeat(3) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(2f / 2.9f)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(brush)
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(0.8f)
                                                .height(10.dp)
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(brush)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (catItems.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 70.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "No ${categories[selectedCategoryIndex]} titles found.",
                                color = TextMuted,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { fetchCategory(selectedCategoryIndex, page = 1) },
                                border = androidx.compose.foundation.BorderStroke(1.dp, AccentEmerald.copy(alpha = 0.5f))
                            ) {
                                Text("Retry", color = AccentEmerald)
                            }
                        }
                    }
                }
            } else {
                // 3-Column Ultra-Compact Responsive Cineb/OnStream Grid
                val chunked = catItems.chunked(3)
                items(chunked) { rowItems ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems.forEach { subject ->
                            Box(modifier = Modifier.weight(1f)) {
                                MediaCard(
                                    item = subject,
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        val path = subject.detailPath.ifEmpty { subject.subjectId }
                                        onMediaClick(path, subject.subjectId, subject.subjectType)
                                    }
                                )
                            }
                        }
                        repeat(3 - rowItems.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                // Infinite Auto-loading indicator & End of catalog status
                if (isLoadingMoreCategory) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 18.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = AccentEmerald,
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = "Loading more ${categories[selectedCategoryIndex]}...",
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                } else if (canLoadMoreMap[selectedCategoryIndex] == false && catItems.isNotEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "✓ You've reached the end",
                                color = TextMuted,
                                fontSize = 11.5.sp
                            )
                        }
                    }
                }
            }
        }
    }

    if (pullRefreshState.isRefreshing || pullRefreshState.verticalOffset > 0.5f) {
        PullToRefreshContainer(
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter),
            containerColor = Color(0xFF0F172A),
            contentColor = AccentEmerald
        )
    }
}

if (showShareDialog) {
    AppShareDialog(onDismiss = { showShareDialog = false })
}
}
}

@Composable
fun ContinueWatchingCard(
    item: WatchHistoryItem,
    onClick: () -> Unit,
    onResumeClick: () -> Unit = onClick
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.94f
            isFocused -> 1.08f
            else -> 1.0f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "cwScale"
    )

    Card(
        modifier = Modifier
            .width(150.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = if (isFocused) 3.dp else 1.dp,
            color = if (isFocused) Color(0xFF00F0FF) else Color.White.copy(alpha = 0.08f)
        ),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101422))
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(85.dp)
                    .background(Color.Black)
            ) {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Play Icon Overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                            .border(1.5.dp, AccentEmerald, CircleShape)
                            .clickable(onClick = onResumeClick),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Resume",
                            tint = AccentEmerald,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Gradient Progress Line at bottom of thumbnail
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.5.dp)
                        .align(Alignment.BottomCenter)
                        .background(Color.White.copy(alpha = 0.15f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction = (item.progressPercent / 100f).coerceIn(0f, 1f))
                            .background(Brush.horizontalGradient(listOf(AccentEmerald, AccentCyan)))
                    )
                }
            }

            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (item.subjectType == 2) item.episodeBadge.ifBlank { "Series" } else "Movie",
                        color = AccentCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${item.progressPercent}%",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}
