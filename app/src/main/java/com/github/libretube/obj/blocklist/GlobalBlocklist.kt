package com.github.libretube.obj.blocklist

import kotlinx.serialization.Serializable

@Serializable
data class GlobalBlocklist(
    val blocked_videos: List<String> = emptyList()
)
