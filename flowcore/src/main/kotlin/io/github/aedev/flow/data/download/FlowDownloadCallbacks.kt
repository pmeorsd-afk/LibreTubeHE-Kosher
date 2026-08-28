package io.github.aedev.flow.data.download

data class FlowPlaylistTrackInfo(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationSec: Long,
    val thumbnailUrl: String? = null
)

object FlowDownloadCallbacks {
    var onDownloadRequested: ((videoId: String) -> Unit)? = null

    var onDownloadCompleted: ((
        videoId: String,
        title: String,
        channelName: String,
        durationSec: Long,
        filePath: String,
        fileSize: Long,
        isAudio: Boolean,
        thumbnailUrl: String?
    ) -> Unit)? = null

    var onPlaylistDownloadRegistered: ((
        playlistId: String,
        title: String,
        thumbnailUrl: String?,
        tracks: List<FlowPlaylistTrackInfo>
    ) -> Unit)? = null
}
