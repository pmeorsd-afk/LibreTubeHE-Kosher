package com.github.libretube.ui.models

import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.extensions.runSafely
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.toVideoIDFromUrl
import com.github.libretube.extensions.updateIfChanged
import com.github.libretube.helpers.BlocklistHelper
import com.github.libretube.helpers.FlowHistoryBridge
import com.github.libretube.helpers.PlayerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class HomeViewModel : ViewModel() {
    val feed: MutableLiveData<List<StreamItem>> = MutableLiveData(null)
    val continueWatching: MutableLiveData<List<StreamItem>> = MutableLiveData(null)
    val isLoading: MutableLiveData<Boolean> = MutableLiveData(true)
    val isPaging: MutableLiveData<Boolean> = MutableLiveData(false)
    val loadedSuccessfully: MutableLiveData<Boolean> = MutableLiveData(false)

    private val sections get() = listOf(feed, continueWatching)

    private var loadHomeJob: Job? = null
    private var currentSeedOffset = 0
    private val usedSeedVideoIds = mutableSetOf<String>()
    private val loadedVideoIds = mutableSetOf<String>()

    fun loadHomeFeed(
        context: Context,
        subscriptionsViewModel: SubscriptionsViewModel,
        visibleItems: Set<String>,
        onUnusualLoadTime: () -> Unit
    ) {
        currentSeedOffset = 0
        usedSeedVideoIds.clear()
        loadedVideoIds.clear()
        isLoading.value = true
        isPaging.value = false

        loadHomeJob?.cancel()
        loadHomeJob = viewModelScope.launch {
            val result = async {
                awaitAll(
                    async { loadFeed(subscriptionsViewModel) },
                    async { loadVideosToContinueWatching() }
                )
                loadedSuccessfully.value = sections.any { it.value != null }
                isLoading.value = false
            }

            withContext(Dispatchers.IO) {
                delay(UNUSUAL_LOAD_TIME_MS)
                if (result.isActive) {
                    onUnusualLoadTime.invoke()
                }
            }
        }
    }

    fun loadMoreHomeFeed(subscriptionsViewModel: SubscriptionsViewModel) {
        if (isLoading.value == true || isPaging.value == true) return
        isPaging.value = true

        viewModelScope.launch {
            val moreVideos = withContext(Dispatchers.IO) {
                fetchNextBatchOfVideos()
            }

            if (moreVideos.isNotEmpty()) {
                val currentFeed = feed.value.orEmpty()
                val filteredNew = moreVideos.filter { item ->
                    val id = item.url.toVideoIDFromUrl() ?: item.url.orEmpty().toID()
                    id.isNotBlank() && !loadedVideoIds.contains(id) && !BlocklistHelper.isVideoBlocked(id)
                }

                filteredNew.forEach { item ->
                    val id = item.url.toVideoIDFromUrl() ?: item.url.orEmpty().toID()
                    if (id.isNotBlank()) loadedVideoIds.add(id)
                }

                if (filteredNew.isNotEmpty()) {
                    feed.value = currentFeed + filteredNew
                }
            }

            isPaging.value = false
        }
    }

    private suspend fun loadFeed(subscriptionsViewModel: SubscriptionsViewModel) {
        runSafely(
            onSuccess = { videos ->
                videos?.forEach { item ->
                    val id = item.url.toVideoIDFromUrl() ?: item.url.orEmpty().toID()
                    if (id.isNotBlank()) loadedVideoIds.add(id)
                }
                feed.updateIfChanged(videos)
            },
            ioBlock = { tryLoadFeed(subscriptionsViewModel) }
        )
    }

    private suspend fun loadVideosToContinueWatching() {
        if (!PlayerHelper.watchHistoryEnabled) return
        runSafely(
            onSuccess = { videos -> continueWatching.updateIfChanged(videos) },
            ioBlock = ::loadWatchingFromDB
        )
    }

    private suspend fun loadWatchingFromDB(): List<StreamItem> {
        val videos = (
            DatabaseHelper.getWatchHistoryPage(1, 20) +
                FlowHistoryBridge.getWatchHistoryPage(1, 20)
            ).distinctBy { it.videoId }

        return DatabaseHelper
            .filterUnwatched(videos.map { it.toStreamItem() })
            .homeVideosOnly()
    }

    private suspend fun tryLoadFeed(subscriptionsViewModel: SubscriptionsViewModel): List<StreamItem> {
        val subFeed = loadSubscriptionFeed(subscriptionsViewModel)
        val initialPersonalized = loadPersonalizedRelatedFeed(0)

        val combined = (subFeed + initialPersonalized)
            .filter { item ->
                val id = item.url.toVideoIDFromUrl() ?: item.url.orEmpty().toID()
                id.isNotBlank() && !BlocklistHelper.isVideoBlocked(id)
            }
            .distinctBy { it.url.orEmpty() }

        // If user has no history and no subscriptions yet, provide initial discovery items
        if (combined.size < 12) {
            val fallback = loadTrendingFallback()
            return (combined + fallback)
                .filter { item ->
                    val id = item.url.toVideoIDFromUrl() ?: item.url.orEmpty().toID()
                    id.isNotBlank() && !BlocklistHelper.isVideoBlocked(id)
                }
                .distinctBy { it.url.orEmpty() }
                .take(HOME_FEED_LIMIT)
        }

        return combined.take(HOME_FEED_LIMIT)
    }

    private suspend fun loadSubscriptionFeed(
        subscriptionsViewModel: SubscriptionsViewModel
    ): List<StreamItem> {
        val cachedFeed = subscriptionsViewModel.videoFeed.value.orEmpty()
        if (cachedFeed.isNotEmpty()) return cachedFeed.homeVideosOnly()

        return withContext(Dispatchers.IO) {
            runCatching {
                SubscriptionHelper.getFeed(forceRefresh = false)
            }.getOrDefault(emptyList())
        }.homeVideosOnly()
    }

    private suspend fun fetchNextBatchOfVideos(): List<StreamItem> {
        // 1. Try next seeds from history
        val nextOffset = currentSeedOffset + RELATED_SEED_LIMIT
        val historyVideos = loadPersonalizedRelatedFeed(nextOffset)
        if (historyVideos.isNotEmpty()) {
            currentSeedOffset = nextOffset
            return historyVideos
        }

        // 2. If history is exhausted, use existing loaded feed items as seeds (recommendation expansion)
        val candidateSeeds = feed.value.orEmpty()
            .mapNotNull { it.url.toVideoIDFromUrl() ?: it.url?.toID() }
            .filter { it.isNotBlank() && !usedSeedVideoIds.contains(it) }
            .take(RELATED_SEED_LIMIT)

        if (candidateSeeds.isNotEmpty()) {
            candidateSeeds.forEach { usedSeedVideoIds.add(it) }
            val expandedVideos = fetchRelatedForSeedIds(candidateSeeds)
            if (expandedVideos.isNotEmpty()) {
                return expandedVideos
            }
        }

        // 3. Fallback to trending discovery
        return loadTrendingFallback()
    }

    private suspend fun loadPersonalizedRelatedFeed(offset: Int = 0): List<StreamItem> {
        val historyItems = (
            DatabaseHelper.getWatchHistoryPage(1, offset + RELATED_SEED_LIMIT) +
                FlowHistoryBridge.getWatchHistoryPage(1, offset + RELATED_SEED_LIMIT)
            )
            .distinctBy { it.videoId }
            .filter { !it.isLive && !it.isShort && it.videoId.isNotBlank() }
            .drop(offset)
            .take(RELATED_SEED_LIMIT)

        if (historyItems.isEmpty()) return emptyList()

        val seedIds = historyItems.map { it.videoId }.filter { !usedSeedVideoIds.contains(it) }
        seedIds.forEach { usedSeedVideoIds.add(it) }

        return fetchRelatedForSeedIds(seedIds)
    }

    private suspend fun fetchRelatedForSeedIds(seedIds: List<String>): List<StreamItem> {
        if (seedIds.isEmpty()) return emptyList()

        return supervisorScope {
            seedIds.map { seedId ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(RELATED_REQUEST_TIMEOUT_MS) {
                        runCatching {
                            MediaServiceRepository.instance
                                .getStreams(seedId)
                                .relatedStreams
                                .homeVideosOnly()
                        }.getOrDefault(emptyList())
                    }.orEmpty()
                }
            }.awaitAll()
                .flatten()
                .distinctBy { it.url.orEmpty() }
                .filter { item ->
                    val id = item.url.toVideoIDFromUrl() ?: item.url.orEmpty().toID()
                    id.isNotBlank() && !loadedVideoIds.contains(id) && !BlocklistHelper.isVideoBlocked(id)
                }
        }
    }

    private suspend fun loadTrendingFallback(): List<StreamItem> {
        return withContext(Dispatchers.IO) {
            runCatching {
                MediaServiceRepository.instance
                    .getTrending(region = "IL")
                    .homeVideosOnly()
                    .filter { item ->
                        val id = item.url.toVideoIDFromUrl() ?: item.url.orEmpty().toID()
                        id.isNotBlank() && !loadedVideoIds.contains(id) && !BlocklistHelper.isVideoBlocked(id)
                    }
            }.getOrDefault(emptyList())
        }
    }

    private fun List<StreamItem>.homeVideosOnly(): List<StreamItem> = filter { item ->
        val isStream = item.type == null || item.type == StreamItem.TYPE_STREAM
        isStream && !item.isLive && !item.isUpcoming && !item.isShort && !item.title.isNullOrBlank()
    }

    companion object {
        private const val UNUSUAL_LOAD_TIME_MS = 10000L
        private const val HOME_FEED_LIMIT = 100
        private const val RELATED_SEED_LIMIT = 6
        private const val RELATED_REQUEST_TIMEOUT_MS = 6000L
    }
}
