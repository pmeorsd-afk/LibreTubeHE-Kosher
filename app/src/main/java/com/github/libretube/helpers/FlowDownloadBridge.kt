package com.github.libretube.helpers

import android.content.Context
import android.util.Log
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.Download
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.db.obj.DownloadPlaylist
import com.github.libretube.db.obj.DownloadPlaylistVideosCrossRef
import com.github.libretube.enums.FileType
import io.github.aedev.flow.data.download.FlowDownloadCallbacks
import io.github.aedev.flow.data.download.FlowPlaylistTrackInfo
import io.github.aedev.flow.data.local.AppDatabase
import io.github.aedev.flow.data.local.entity.DownloadFileType
import io.github.aedev.flow.data.local.entity.DownloadItemStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Paths

object FlowDownloadBridge {
    private const val TAG = "FlowDownloadBridge"

    fun init(context: Context) {
        FlowDownloadCallbacks.onDownloadCompleted = { videoId, title, channelName, durationSec, filePath, fileSize, isAudio, thumbnailUrl ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    registerCompletedDownload(
                        videoId = videoId,
                        title = title,
                        channelName = channelName,
                        durationSec = durationSec,
                        filePath = filePath,
                        fileSize = fileSize,
                        isAudio = isAudio,
                        thumbnailUrl = thumbnailUrl
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to register download from callback: $videoId", e)
                }
            }
        }

        FlowDownloadCallbacks.onPlaylistDownloadRegistered = { playlistId, title, thumbnailUrl, tracks ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    registerPlaylist(playlistId, title, thumbnailUrl, tracks)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to register playlist from callback: $playlistId", e)
                }
            }
        }

        // Initial background sync
        CoroutineScope(Dispatchers.IO).launch {
            try {
                syncFlowDownloadsToLibreTube(context)
            } catch (e: Exception) {
                Log.e(TAG, "Initial sync failed", e)
            }
        }
    }

    suspend fun registerCompletedDownload(
        videoId: String,
        title: String,
        channelName: String,
        durationSec: Long,
        filePath: String,
        fileSize: Long,
        isAudio: Boolean,
        thumbnailUrl: String? = null
    ) {
        if (videoId.isBlank() || filePath.isBlank()) return
        val file = File(filePath)
        if (!file.exists()) return

        val actualSize = if (fileSize > 0) fileSize else file.length()
        val path = Paths.get(filePath)

        val download = Download(
            videoId = videoId,
            title = title.ifBlank { file.nameWithoutExtension },
            description = "",
            uploader = channelName,
            duration = durationSec.takeIf { it > 0 },
            uploadDate = null,
            thumbnailPath = null,
            uploaderUrl = null,
            views = 0,
            likes = 0,
            dislikes = -1
        )
        Database.downloadDao().insertDownload(download)

        val downloadItem = DownloadItem(
            type = if (isAudio) FileType.AUDIO else FileType.VIDEO,
            videoId = videoId,
            fileName = file.name,
            path = path,
            format = file.extension.ifBlank { if (isAudio) "m4a" else "mp4" },
            quality = if (isAudio) "Audio" else "Video",
            downloadSize = actualSize
        )
        Database.downloadDao().insertDownloadItem(downloadItem)
        Log.d(TAG, "Registered download in LibreTube DB: $videoId ($filePath)")
    }

    suspend fun registerPlaylist(
        playlistId: String,
        title: String,
        thumbnailUrl: String?,
        tracks: List<FlowPlaylistTrackInfo>
    ) {
        if (playlistId.isBlank() || title.isBlank()) return

        val downloadPlaylist = DownloadPlaylist(
            playlistId = playlistId,
            title = title,
            thumbnailPath = null,
            description = null
        )
        Database.downloadDao().insertPlaylist(downloadPlaylist)

        for (track in tracks) {
            if (track.videoId.isNotBlank()) {
                if (!Database.downloadDao().exists(track.videoId)) {
                    val download = Download(
                        videoId = track.videoId,
                        title = track.title.ifBlank { "Track" },
                        description = "",
                        uploader = track.artist,
                        duration = track.durationSec.takeIf { it > 0 },
                        uploadDate = null,
                        thumbnailPath = null,
                        uploaderUrl = null,
                        views = 0,
                        likes = 0,
                        dislikes = -1
                    )
                    Database.downloadDao().insertDownload(download)
                }

                val crossRef = DownloadPlaylistVideosCrossRef(
                    playlistId = playlistId,
                    videoId = track.videoId
                )
                Database.downloadDao().insertPlaylistVideoConnection(crossRef)
            }
        }
        Log.d(TAG, "Registered playlist in LibreTube DB: $playlistId ($title) with ${tracks.size} tracks")
    }

    suspend fun syncFlowDownloadsToLibreTube(context: Context) {
        try {
            val flowDb = AppDatabase.getDatabase(context)
            val flowDownloads = flowDb.downloadDao().getAllDownloadsWithItemsOnce()

            for (flowDl in flowDownloads) {
                val videoId = flowDl.download.videoId
                val completedItem = flowDl.items.firstOrNull { it.status == DownloadItemStatus.COMPLETED }
                if (completedItem != null && completedItem.filePath.isNotBlank()) {
                    val file = File(completedItem.filePath)
                    if (file.exists()) {
                        val isAudio = completedItem.fileType == DownloadFileType.AUDIO
                        registerCompletedDownload(
                            videoId = videoId,
                            title = flowDl.download.title,
                            channelName = flowDl.download.uploader,
                            durationSec = flowDl.download.duration,
                            filePath = completedItem.filePath,
                            fileSize = completedItem.totalBytes.takeIf { it > 0 } ?: file.length(),
                            isAudio = isAudio,
                            thumbnailUrl = flowDl.download.thumbnailUrl
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "syncFlowDownloadsToLibreTube error", e)
        }
    }
}
