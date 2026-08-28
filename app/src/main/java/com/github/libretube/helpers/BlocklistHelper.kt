package com.github.libretube.helpers

import android.content.Context
import android.util.Log
import com.github.libretube.R
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.BlockedVideo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

object BlocklistHelper {
    private const val TAG = "BlocklistHelper"
    private val blockedVideoIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * Initializes the blocklist by loading local database entries and triggering
     * a background fetch of the global blocklist from GitHub.
     */
    fun init(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Load from local database
                val localBlocks = Database.blockedVideoDao().getAllVideoIds()
                blockedVideoIds.addAll(localBlocks)
                Log.d(TAG, "Loaded ${localBlocks.size} local blocked videos.")

                // 2. Fetch and merge global blocklist from GitHub
                fetchGlobalBlocklist()
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing blocklist", e)
            }
        }
    }

    /**
     * Fetches the global blocklist from the GitHub repository and merges it in-memory.
     */
    private suspend fun fetchGlobalBlocklist() {
        withContext(Dispatchers.IO) {
            try {
                val globalBlocklist = RetrofitInstance.externalApi.getGlobalBlocklist()
                val globalIds = globalBlocklist.blocked_videos
                blockedVideoIds.addAll(globalIds)
                Log.d(TAG, "Fetched ${globalIds.size} global blocked videos.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch global blocklist from GitHub", e)
            }
        }
    }

    /**
     * Synchronously/thread-safely checks if a video ID is blocked.
     */
    fun isVideoBlocked(videoId: String): Boolean {
        if (videoId.isEmpty()) return false
        return blockedVideoIds.contains(videoId)
    }

    /**
     * Blocks a video locally, persists it in Room DB, updates in-memory cache,
     * and reports it to the admin via Telegram Bot in the background.
     */
    suspend fun blockVideoLocally(context: Context, videoId: String, title: String) {
        withContext(Dispatchers.IO) {
            try {
                // Save to local database
                Database.blockedVideoDao().insert(
                    BlockedVideo(
                        videoId = videoId,
                        title = title,
                        blockedAt = System.currentTimeMillis()
                    )
                )
                // Add to in-memory blocklist
                blockedVideoIds.add(videoId)
                Log.d(TAG, "Video $videoId blocked locally.")

                // Send report to Telegram in background
                reportToTelegram(context, videoId, title)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to block video locally: $videoId", e)
            }
        }
    }

    /**
     * Unblocks a video locally (useful for undo action).
     */
    suspend fun unblockVideoLocally(videoId: String) {
        withContext(Dispatchers.IO) {
            try {
                Database.blockedVideoDao().deleteByVideoId(videoId)
                blockedVideoIds.remove(videoId)
                Log.d(TAG, "Video $videoId unblocked locally.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unblock video: $videoId", e)
            }
        }
    }

    /**
     * Sends a POST request to the Telegram Bot API to notify the admin about the blocked video.
     */
    private fun reportToTelegram(context: Context, videoId: String, title: String) {
        val token = context.getString(R.string.telegram_bot_token)
        val chatId = context.getString(R.string.telegram_chat_id)

        // Only send if the user has configured custom token/chatId properties
        if (token.isEmpty() || token == "YOUR_TELEGRAM_BOT_TOKEN" ||
            chatId.isEmpty() || chatId == "YOUR_TELEGRAM_CHAT_ID") {
            Log.d(TAG, "Telegram reporting skipped: credentials not configured.")
            return
        }

        val url = "https://api.telegram.org/bot$token/sendMessage"
        val messageText = """
            🚨 <b>דיווח על סרטון לא הולם!</b>
            <b>שם הסרטון:</b> $title
            <b>מזהה:</b> <code>$videoId</code>
            <b>קישור ליוטיוב:</b> https://www.youtube.com/watch?v=$videoId
        """.trimIndent()

        val formBody = FormBody.Builder()
            .add("chat_id", chatId)
            .add("text", messageText)
            .add("parse_mode", "HTML")
            .build()

        val request = Request.Builder()
            .url(url)
            .post(formBody)
            .build()

        RetrofitInstance.httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Telegram report request failed", e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    Log.e(TAG, "Telegram response unsuccessful: ${response.code} - ${response.message}")
                } else {
                    Log.d(TAG, "Telegram report sent successfully.")
                }
                response.close()
            }
        })
    }
}
