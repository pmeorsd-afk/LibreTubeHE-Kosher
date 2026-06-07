package com.github.libretube.ui.models

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.WatchHistoryItem
import com.github.libretube.helpers.FlowHistoryBridge
import com.github.libretube.helpers.PreferenceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WatchHistoryModel : ViewModel() {
    private val watchHistory = MutableLiveData<List<WatchHistoryItem>>()

    private var currentPage = 1
    private var isLoading = false

    private val selectedStatus = MutableStateFlow(
        PreferenceHelper.getInt(PreferenceKeys.SELECTED_HISTORY_STATUS_FILTER, 0)
    )

    val filteredWatchHistory =
        combine(watchHistory.asFlow(), selectedStatus) { history, _ -> history }
            .flowOn(Dispatchers.IO).map { history -> history.filter { it.shouldIncludeByFilters() } }
            .asLiveData()

    var selectedStatusFilter
        get() = selectedStatus.value
        set(value) {
            PreferenceHelper.putInt(PreferenceKeys.SELECTED_HISTORY_STATUS_FILTER, value)
            selectedStatus.value = value
        }

    private suspend fun WatchHistoryItem.shouldIncludeByFilters(): Boolean {
        // no watch position filter
        if (selectedStatusFilter == 0) return true

        return when (selectedStatusFilter) {
            1 -> DatabaseHelper.filterByWatchStatus(this)
            2 -> DatabaseHelper.filterByWatchStatus(this, false)
            else -> throw IllegalArgumentException()
        }
    }

    fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        if (isLoading) return@launch
        currentPage = 1
        watchHistory.postValue(emptyList())
        loadNextPage(replace = true)
    }

    fun fetchNextPage() = viewModelScope.launch(Dispatchers.IO) {
        loadNextPage(replace = false)
    }

    private suspend fun loadNextPage(replace: Boolean) {
        if (isLoading) return
        isLoading = true

        val newHistory = withContext(Dispatchers.IO) {
            (
                DatabaseHelper.getWatchHistoryPage(currentPage, HISTORY_PAGE_SIZE) +
                    FlowHistoryBridge.getWatchHistoryPage(currentPage, HISTORY_PAGE_SIZE)
                ).distinctBy { it.videoId }
        }

        isLoading = false
        currentPage++

        watchHistory.postValue(
            (if (replace) emptyList() else watchHistory.value.orEmpty()).toMutableList().apply {
                addAll(newHistory)
            }
        )
    }

    fun removeFromHistory(watchHistoryItem: WatchHistoryItem) =
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseHolder.Database.watchHistoryDao().delete(watchHistoryItem)
            FlowHistoryBridge.removeWatch(watchHistoryItem.videoId)

            watchHistory.postValue(
                watchHistory.value.orEmpty().filter { it != watchHistoryItem }
            )
        }

    companion object {
        private const val HISTORY_PAGE_SIZE = 10
    }
}
