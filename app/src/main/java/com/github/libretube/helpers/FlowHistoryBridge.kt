package com.github.libretube.helpers

import android.content.Context
import com.github.libretube.LibreTubeApp
import com.github.libretube.db.obj.WatchHistoryItem
import io.github.aedev.flow.data.local.VideoHistoryEntry
import io.github.aedev.flow.data.local.ViewHistory
import kotlinx.coroutines.flow.first

object FlowHistoryBridge {
    suspend fun recordWatch(context: Context, item: WatchHistoryItem) {
        if (item.videoId.isBlank()) return

        val durationMs = item.duration?.times(1000) ?: 0L
        ViewHistory.getInstance(context.applicationContext).touchHistoryEntry(
            videoId = item.videoId,
            title = item.title.orEmpty(),
            thumbnailUrl = item.thumbnailUrl.orEmpty(),
            channelName = item.uploader.orEmpty(),
            channelId = item.uploaderUrl.orEmpty().substringAfterLast("/"),
            duration = durationMs,
            isShort = item.isShort
        )
    }

    suspend fun getWatchHistoryPage(page: Int, pageSize: Int): List<WatchHistoryItem> {
        val offset = (page - 1).coerceAtLeast(0) * pageSize
        return ViewHistory.getInstance(LibreTubeApp.instance)
            .getVideoHistoryFlow()
            .first()
            .drop(offset)
            .take(pageSize)
            .map(VideoHistoryEntry::toWatchHistoryItem)
    }

    suspend fun getMusicHistoryPage(page: Int, pageSize: Int): List<WatchHistoryItem> {
        val offset = (page - 1).coerceAtLeast(0) * pageSize
        return ViewHistory.getInstance(LibreTubeApp.instance)
            .getMusicHistoryFlow()
            .first()
            .drop(offset)
            .take(pageSize)
            .map(VideoHistoryEntry::toWatchHistoryItem)
    }

    suspend fun getAllHistoryPage(page: Int, pageSize: Int): List<WatchHistoryItem> {
        val offset = (page - 1).coerceAtLeast(0) * pageSize
        return ViewHistory.getInstance(LibreTubeApp.instance)
            .getAllHistory()
            .first()
            .drop(offset)
            .take(pageSize)
            .map(VideoHistoryEntry::toWatchHistoryItem)
    }

    suspend fun removeWatch(videoId: String) {
        if (videoId.isBlank()) return
        ViewHistory.getInstance(LibreTubeApp.instance).clearVideoHistory(videoId)
    }

    suspend fun clearAll() {
        ViewHistory.getInstance(LibreTubeApp.instance).clearAllHistory()
    }
}

private fun VideoHistoryEntry.toWatchHistoryItem() = WatchHistoryItem(
    videoId = videoId,
    title = title.ifBlank { null },
    uploader = channelName.ifBlank { null },
    uploaderUrl = channelId.takeIf { it.isNotBlank() }?.let { "https://www.youtube.com/channel/$it" },
    thumbnailUrl = thumbnailUrl.ifBlank { null },
    duration = duration.takeIf { it > 0L }?.div(1000),
    isShort = isShort
)
