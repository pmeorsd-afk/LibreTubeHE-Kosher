package com.github.libretube.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.github.libretube.db.obj.BlockedVideo

@Dao
interface BlockedVideoDao {
    @Query("SELECT * FROM blockedVideo")
    suspend fun getAll(): List<BlockedVideo>

    @Query("SELECT videoId FROM blockedVideo")
    suspend fun getAllVideoIds(): List<String>

    @Query("SELECT * FROM blockedVideo WHERE videoId = :videoId LIMIT 1")
    suspend fun findById(videoId: String): BlockedVideo?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(blockedVideo: BlockedVideo)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(blockedVideos: List<BlockedVideo>)

    @Delete
    suspend fun delete(blockedVideo: BlockedVideo)

    @Query("DELETE FROM blockedVideo WHERE videoId = :videoId")
    suspend fun deleteByVideoId(videoId: String)

    @Query("DELETE FROM blockedVideo")
    suspend fun deleteAll()
}
