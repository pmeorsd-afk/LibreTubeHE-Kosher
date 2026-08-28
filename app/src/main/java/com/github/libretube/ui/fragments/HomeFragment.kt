package com.github.libretube.ui.fragments

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.NavDirections
import com.github.libretube.R
import com.github.libretube.api.obj.Playlists
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.FragmentHomeBinding
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.db.obj.SearchHistoryItem
import com.github.libretube.db.obj.PlaylistBookmark
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.activities.SettingsActivity
import com.github.libretube.ui.adapters.CarouselPlaylist
import com.github.libretube.ui.adapters.CarouselPlaylistAdapter
import com.github.libretube.ui.adapters.VideoCardsAdapter
import com.github.libretube.ui.models.HomeViewModel
import com.github.libretube.ui.models.SubscriptionsViewModel
import com.google.android.material.carousel.CarouselLayoutManager
import com.google.android.material.carousel.CarouselSnapHelper
import com.google.android.material.carousel.UncontainedCarouselStrategy
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale


class HomeFragment : Fragment(R.layout.fragment_home) {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val homeViewModel: HomeViewModel by activityViewModels()
    private val subscriptionsViewModel: SubscriptionsViewModel by activityViewModels()

    private val feedAdapter = VideoCardsAdapter()
    private val watchingAdapter = VideoCardsAdapter(columnWidthDp = 250f)
    private val bookmarkAdapter = CarouselPlaylistAdapter()
    private val playlistAdapter = CarouselPlaylistAdapter()

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val spokenQuery = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()

        if (spokenQuery.isNotBlank()) {
            _binding?.starterSearchInput?.setText(spokenQuery)
            submitSearch(spokenQuery)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentHomeBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        binding.bookmarksRV.layoutManager = CarouselLayoutManager(UncontainedCarouselStrategy())
        binding.playlistsRV.layoutManager = CarouselLayoutManager(UncontainedCarouselStrategy())

        val bookmarksSnapHelper = CarouselSnapHelper()
        bookmarksSnapHelper.attachToRecyclerView(binding.bookmarksRV)

        val playlistsSnapHelper = CarouselSnapHelper()
        playlistsSnapHelper.attachToRecyclerView(binding.playlistsRV)

        binding.featuredRV.adapter = feedAdapter
        binding.featuredRV.layoutManager = GridLayoutManager(requireContext(), HOME_GRID_COLUMNS)
        binding.bookmarksRV.adapter = bookmarkAdapter
        binding.playlistsRV.adapter = playlistAdapter
        binding.playlistsRV.adapter?.registerAdapterDataObserver(object :
            RecyclerView.AdapterDataObserver() {
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                super.onItemRangeRemoved(positionStart, itemCount)
                if (itemCount == 0) {
                    binding.playlistsRV.isGone = true
                    binding.playlistsTV.isGone = true
                }
            }
        })
        binding.watchingRV.adapter = watchingAdapter

        with(homeViewModel) {
            feed.observe(viewLifecycleOwner, ::showFeed)
            continueWatching.observe(viewLifecycleOwner, ::showContinueWatching)
            isLoading.observe(viewLifecycleOwner, ::updateLoading)
            isPaging.observe(viewLifecycleOwner) { isPaging ->
                binding.paginationProgress.isVisible = isPaging
            }
        }

        binding.featuredTV.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_subscriptionsFragment)
        }

        binding.watchingTV.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_watchHistoryFragment)
        }

        binding.playlistsTV.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_libraryFragment)
        }

        binding.bookmarksTV.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_libraryFragment)
        }

        binding.refresh.setOnRefreshListener {
            binding.refresh.isRefreshing = true
            fetchHomeFeed()
        }

        binding.trendingRegion.isGone = true
        binding.trendingCategory.isGone = true
        binding.trendingTV.isGone = true
        binding.trendingRV.isGone = true

        binding.refreshButton.setOnClickListener {
            fetchHomeFeed()
        }

        binding.changeInstance.setOnClickListener {
            redirectToIntentSettings()
        }

        setupStarterHome()
        setupHomeCategories()

        binding.scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val child = binding.scroll.getChildAt(0)
            if (child != null) {
                val diff = child.bottom - (binding.scroll.height + scrollY)
                if (diff <= 800) {
                    homeViewModel.loadMoreHomeFeed(subscriptionsViewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (homeViewModel.loadedSuccessfully.value == false) {
            fetchHomeFeed()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun fetchHomeFeed() {
        binding.nothingHere.isGone = true
        binding.starterHome.isGone = true
        binding.categoryOverlay.isGone = true
        val defaultItems = resources.getStringArray(R.array.homeTabItemsValues)
        val visibleItems = PreferenceHelper.getStringSet(
            PreferenceKeys.HOME_TAB_CONTENT,
            defaultItems.toSet()
        )

        homeViewModel.loadHomeFeed(
            context = requireContext(),
            subscriptionsViewModel = subscriptionsViewModel,
            visibleItems = visibleItems,
            onUnusualLoadTime = ::showChangeInstanceSnackBar
        )
    }

    private fun showFeed(streamItems: List<StreamItem>?) {
        if (streamItems == null) return
        if (streamItems.isEmpty()) {
            binding.featuredRV.isGone = true
            binding.featuredTV.isGone = true
            binding.homeCategoryContainer.isGone = true
            feedAdapter.submitList(emptyList())
            updateStarterVisibility()
            return
        }

        binding.featuredTV.isGone = true
        makeVisible(binding.featuredRV, binding.homeCategoryContainer)

        feedAdapter.submitList(streamItems)
        updateStarterVisibility()
    }

    private fun showBookmarks(bookmarks: List<PlaylistBookmark>?) {
        if (bookmarks == null) return

        makeVisible(binding.bookmarksTV, binding.bookmarksRV)
        bookmarkAdapter.submitList(bookmarks.map { bookmark ->
            CarouselPlaylist(
                id = bookmark.playlistId,
                title = bookmark.playlistName,
                thumbnail = bookmark.thumbnailUrl
            )
        })
    }

    private fun showPlaylists(playlists: List<Playlists>?) {
        if (playlists == null) return

        makeVisible(binding.playlistsRV, binding.playlistsTV)
        playlistAdapter.submitList(playlists.map { playlist ->
            CarouselPlaylist(
                id = playlist.id!!,
                thumbnail = playlist.thumbnail,
                title = playlist.name
            )
        })
    }

    private fun showContinueWatching(unwatchedVideos: List<StreamItem>?) {
        if (unwatchedVideos == null) return
        binding.watchingRV.isGone = true
        binding.watchingTV.isGone = true
        watchingAdapter.submitList(emptyList())
        updateStarterVisibility()
    }

    private fun updateLoading(isLoading: Boolean) {
        if (isLoading) {
            showLoading()
        } else {
            hideLoading()
        }
    }

    private fun showLoading() {
        binding.progress.isVisible = !binding.refresh.isRefreshing
        binding.nothingHere.isVisible = false
        binding.starterHome.isVisible = false
        binding.categoryOverlay.isVisible = false
        binding.scroll.alpha = 0.3f
    }

    private fun hideLoading() {
        binding.progress.isVisible = false
        binding.refresh.isRefreshing = false

        val hasContent = homeViewModel.loadedSuccessfully.value == true
        if (hasContent) {
            showContent()
        } else {
            showNothingHere()
        }
        binding.scroll.alpha = 1.0f
    }

    private fun showNothingHere() {
        binding.starterHome.isVisible = true
        binding.nothingHere.isVisible = false
        binding.scroll.isVisible = false
    }

    private fun showContent() {
        binding.nothingHere.isVisible = false
        val hasHomeContent = hasHomeContent()
        binding.starterHome.isVisible = !hasHomeContent
        binding.scroll.isVisible = hasHomeContent
        if (!hasHomeContent) {
            binding.categoryOverlay.isVisible = false
        }
    }

    private fun updateStarterVisibility() {
        if (homeViewModel.isLoading.value == true) return
        showContent()
    }

    private fun showChangeInstanceSnackBar() {
        val root = _binding?.root ?: return
        Snackbar
            .make(root, R.string.suggest_change_instance, Snackbar.LENGTH_LONG)
            .apply {
                setAction(R.string.change) {
                    redirectToIntentSettings()
                }
                show()
            }
    }

    private fun redirectToIntentSettings() {
        val settingsIntent = Intent(context, SettingsActivity::class.java).apply {
            putExtra(SettingsActivity.REDIRECT_KEY, SettingsActivity.REDIRECT_TO_INTENT_SETTINGS)
        }
        startActivity(settingsIntent)
    }

    private fun setupStarterHome() {
        binding.starterSearchInput.setOnEditorActionListener { textView, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch(textView.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }

        binding.starterVoiceSearch.setOnClickListener {
            startVoiceSearch()
        }

        binding.starterCategory.setOnClickListener {
            openCategoryPanel()
        }

        binding.categoryOverlay.setOnClickListener {
            closeCategoryPanel()
        }

        binding.categoryPanel.setOnClickListener {
            // Keep taps inside the panel from closing it before the category row handles them.
        }

        binding.categoryMusic.setOnClickListener { openLibreMusic() }
        binding.categoryGames.setOnClickListener { submitCategorySearch("משחקים") }
        binding.categoryNews.setOnClickListener { submitCategorySearch("חדשות") }
        binding.categorySports.setOnClickListener { submitCategorySearch("ספורט") }
        binding.categoryPodcasts.setOnClickListener { submitCategorySearch("פודקאסטים") }
        
        binding.categoryCloseBtn.setOnClickListener { closeCategoryPanel() }
        binding.categorySettingsPrivacy.setOnClickListener {
            closeCategoryPanel(animate = false)
            val settingsIntent = Intent(context, SettingsActivity::class.java)
            startActivity(settingsIntent)
        }
        binding.categorySettingsApp.setOnClickListener {
            closeCategoryPanel(animate = false)
            val settingsIntent = Intent(context, SettingsActivity::class.java)
            startActivity(settingsIntent)
        }
        binding.categorySettingsAbout.setOnClickListener {
            closeCategoryPanel(animate = false)
            val aboutIntent = Intent(context, com.github.libretube.ui.activities.AboutActivity::class.java)
            startActivity(aboutIntent)
        }
    }

    private fun setupHomeCategories() {
        binding.homeCategoryCompass.setOnClickListener {
            openCategoryPanel()
        }

        binding.homeChipAll.setOnClickListener {
            binding.homeChipAll.isChecked = true
        }
        binding.homeChipPodcasts.setOnClickListener { submitCategorySearch("פודקאסטים") }
        binding.homeChipNews.setOnClickListener { submitCategorySearch("חדשות") }
        binding.homeChipMusic.setOnClickListener { submitCategorySearch("מוזיקה") }
        binding.homeChipGames.setOnClickListener { submitCategorySearch("משחקים") }
        binding.homeChipSports.setOnClickListener { submitCategorySearch("ספורט") }
    }

    private fun startVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.forLanguageTag("he-IL").toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.home_start_search_hint))
        }

        runCatching {
            speechLauncher.launch(intent)
        }.onFailure {
            Snackbar.make(
                binding.root,
                R.string.home_voice_search_unavailable,
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    private fun submitCategorySearch(query: String) {
        closeCategoryPanel(animate = false)
        submitSearch(query)
    }

    private fun openLibreMusic() {
        closeCategoryPanel(animate = false)
        findNavController().navigate(R.id.musicFragment)
    }

    private fun openCategoryPanel() {
        val panel = binding.categoryPanel
        binding.categoryOverlay.apply {
            alpha = 0f
            isVisible = true
        }

        panel.animate().cancel()
        binding.categoryOverlay.animate().cancel()
        panel.post {
            val panelWidth = panel.width.takeIf { it > 0 } ?: return@post
            panel.translationX = panelWidth.toFloat()
            binding.categoryOverlay.animate()
                .alpha(1f)
                .setDuration(CATEGORY_PANEL_ANIMATION_MS)
                .start()
            panel.animate()
                .translationX(0f)
                .setDuration(CATEGORY_PANEL_ANIMATION_MS)
                .start()
        }
    }

    private fun closeCategoryPanel(animate: Boolean = true) {
        if (!binding.categoryOverlay.isVisible) return

        val panel = binding.categoryPanel
        panel.animate().cancel()
        binding.categoryOverlay.animate().cancel()

        if (!animate) {
            panel.translationX = 0f
            binding.categoryOverlay.alpha = 1f
            binding.categoryOverlay.isGone = true
            return
        }

        val panelWidth = panel.width.takeIf { it > 0 } ?: CATEGORY_PANEL_FALLBACK_WIDTH_PX
        binding.categoryOverlay.animate()
            .alpha(0f)
            .setDuration(CATEGORY_PANEL_ANIMATION_MS)
            .start()
        panel.animate()
            .translationX(panelWidth.toFloat())
            .setDuration(CATEGORY_PANEL_ANIMATION_MS)
            .withEndAction {
                val binding = _binding ?: return@withEndAction
                binding.categoryPanel.translationX = 0f
                binding.categoryOverlay.alpha = 1f
                binding.categoryOverlay.isGone = true
            }
            .start()
    }

    private fun submitSearch(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty()) return

        binding.starterSearchInput.setText(query)
        addSearchQueryToHistory(query)
        findNavController().navigate(NavDirections.showSearchResults(query))
    }

    private fun addSearchQueryToHistory(query: String) {
        val searchHistoryEnabled =
            PreferenceHelper.getBoolean(PreferenceKeys.SEARCH_HISTORY_TOGGLE, true)
        if (!searchHistoryEnabled) return

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            DatabaseHelper.addToSearchHistory(SearchHistoryItem(query))
        }
    }

    private fun hasHomeContent(): Boolean {
        val hasFeed = !homeViewModel.feed.value.isNullOrEmpty()
        return hasFeed
    }

    private fun makeVisible(vararg views: View) {
        views.forEach { it.isVisible = true }
    }

    private companion object {
        const val HOME_GRID_COLUMNS = 2
        const val HOME_FEED_VISIBLE_LIMIT = 80
        const val CATEGORY_PANEL_ANIMATION_MS = 180L
        const val CATEGORY_PANEL_FALLBACK_WIDTH_PX = 900
    }
}
