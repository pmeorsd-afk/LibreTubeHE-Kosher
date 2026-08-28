package com.github.libretube.helpers

import android.content.Context
import android.util.Log
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.github.aedev.flow.FlowApplication
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.models.YouTubeLocale
import io.github.aedev.flow.player.DeepFlowManager
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

object FlowBridgeInitializer {
    private const val TAG = "FlowBridge"
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true

        val appContext = context.applicationContext
        val preferences = PlayerPreferences(appContext)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        FlowApplication.installContext(appContext)

        runCatching {
            EnhancedMusicPlayerManager.initialize(appContext)
            DeepFlowManager.initialize(appContext)
            FlowDownloadBridge.init(appContext)
        }.onFailure {
            Log.w(TAG, "Flow runtime init failed: ${it.message}")
        }

        scope.launch {
            runCatching {
                FlowNeuroEngine.initialize(appContext)
            }.onFailure {
                Log.w(TAG, "Flow neuro init failed: ${it.message}")
            }

            runCatching { preferences.migrateDefaultTrendingRegionToIsrael() }
            YouTube.locale = YouTubeLocale(gl = "IL", hl = "he")

            val sharedPrefs = appContext.getSharedPreferences("flow_prefs", Context.MODE_PRIVATE)
            val cachedVisitorData = sharedPrefs.getString("visitor_data", null)
            if (!cachedVisitorData.isNullOrBlank()) {
                YouTube.visitorData = cachedVisitorData
            } else {
                YouTube.visitorData().onSuccess { visitorData ->
                    if (!visitorData.isNullOrBlank()) {
                        sharedPrefs.edit().putString("visitor_data", visitorData).apply()
                        YouTube.visitorData = visitorData
                    }
                }.onFailure {
                    Log.w(TAG, "Visitor data init failed: ${it.message}")
                }
            }
        }

        scope.launch {
            combine(
                preferences.appLanguage,
                preferences.trendingRegion
            ) { language, region ->
                YouTubeLocale(
                    gl = normalizeYouTubeCountry(region),
                    hl = normalizeYouTubeLanguage(language)
                )
            }.collectLatest { locale ->
                YouTube.locale = locale
            }
        }
    }

    private fun normalizeYouTubeCountry(region: String): String {
        val normalized = region.trim().uppercase(Locale.US)
        return normalized
            .takeIf { it.matches(Regex("[A-Z]{2}")) }
            ?: "IL"
    }

    private fun normalizeYouTubeLanguage(languageTag: String): String {
        val candidate = languageTag.trim().takeUnless {
            it.isBlank() || it.equals("system", ignoreCase = true)
        } ?: Locale.getDefault().toLanguageTag()
        val tag = Locale.forLanguageTag(candidate.replace('_', '-')).toLanguageTag()
        return tag.takeUnless { it.isBlank() || it.equals("und", ignoreCase = true) } ?: "he"
    }
}
