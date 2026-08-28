package com.github.libretube.db.obj

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "blockedVideo")
data class BlockedVideo(
    @PrimaryKey val videoId: String = "",
    @ColumnInfo val title: String? = null,
    @ColumnInfo val blockedAt: Long = 0L
)
