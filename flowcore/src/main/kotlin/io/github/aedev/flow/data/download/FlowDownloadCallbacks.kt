package io.github.aedev.flow.data.download

object FlowDownloadCallbacks {
    var onDownloadCompleted: ((
        videoId: String,
        title: String,
        channelName: String,
        durationSec: Int,
        filePath: String,
        fileSize: Long,
        isAudio: Boolean,
        thumbnailUrl: String?
    ) -> Unit)? = null

    var onPlaylistDownloadRegistered: ((
        playlistId: String,
        title: String,
        thumbnailUrl: String?,
        trackIds: List<String>
    ) -> Unit)? = null
}
