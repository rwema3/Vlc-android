/*****************************************************************************
 * AudioBrowserFragment.java
 *
 * Copyright © 2011-2017 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 */

package org.videolan.vlc.gui.audio

import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.util.SparseArray
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagedList
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.resources.CTX_PLAY_ALL
import org.videolan.resources.KEY_AUDIO_CURRENT_TAB
import org.videolan.resources.KEY_AUDIO_LAST_PLAYLIST
import org.videolan.resources.util.waitForML
import org.videolan.tools.*
import org.videolan.vlc.R
import org.videolan.vlc.gui.AudioPlayerContainerActivity
import org.videolan.vlc.gui.ContentActivity
import org.videolan.vlc.gui.HeaderMediaListActivity
import org.videolan.vlc.gui.SecondaryActivity
import org.videolan.vlc.gui.dialogs.*
import org.videolan.vlc.gui.helpers.UiTools
import org.videolan.vlc.gui.view.EmptyLoadingState
import org.videolan.vlc.gui.view.EmptyLoadingStateView
import org.videolan.vlc.gui.view.FastScroller
import org.videolan.vlc.media.MediaUtils
import org.videolan.vlc.providers.medialibrary.MedialibraryProvider
import org.videolan.vlc.util.Permissions
import org.videolan.vlc.util.isTalkbackIsEnabled
import org.videolan.vlc.viewmodels.mobile.AudioBrowserViewModel
import org.videolan.vlc.viewmodels.mobile.getViewModel

class AudioBrowserFragment : BaseAudioBrowser<AudioBrowserViewModel>() {

    private lateinit var audioPagerAdapter: AudioPagerAdapter
    private lateinit var songsAdapter: AudioBrowserAdapter
    private lateinit var artistsAdapter: AudioBrowserAdapter
    private lateinit var albumsAdapter: AudioBrowserAdapter
    private lateinit var genresAdapter: AudioBrowserAdapter
    private lateinit var playlistAdapter: AudioBrowserAdapter
    private lateinit var emptyView: EmptyLoadingStateView
    private lateinit var fastScroller: FastScroller

    private val lists = mutableListOf<RecyclerView>()
    private lateinit var settings: SharedPreferences
    override val hasTabs = true
    private var spacing = 0
    private var restorePositions: SparseArray<Int> = SparseArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        spacing = requireActivity().resources.getDimension(R.dimen.kl_small).toInt()

        if (!::settings.isInitialized) settings = Settings.getInstance(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.audio_browser, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val appbar = view.rootView.findViewById<AppBarLayout>(R.id.appbar)
        val coordinator = view.rootView.findViewById<CoordinatorLayout>(R.id.coordinator)
        val fab = view.rootView.findViewById<FloatingActionButton>(R.id.fab)
        fastScroller = view.rootView.findViewById(R.id.songs_fast_scroller)
        emptyView = view.rootView.findViewById(R.id.audio_empty_loading)
        fastScroller.attachToCoordinator(appbar, coordinator, fab)
        emptyView.setOnNoMediaClickListener { requireActivity().setResult(RESULT_RESTART) }

        val views = ArrayList<View>(MODE_TOTAL)
        for (i in 0 until MODE_TOTAL) {
            viewPager.getChildAt(i).let {
                views.add(it)
                lists.add(it.findViewById(R.id.audio_list))
            }
        }
        val titles = arrayOf(getString(R.string.artists), getString(R.string.albums), getString(R.string.tracks), getString(R.string.genres), getString(R.string.playlists))
        viewPager.offscreenPageLimit = MODE_TOTAL - 1
        audioPagerAdapter = AudioPagerAdapter(views.toTypedArray(), titles)
        @Suppress("UNCHECKED_CAST")
        viewPager.adapter = audioPagerAdapter
        savedInstanceState?.getIntegerArrayList(KEY_LISTS_POSITIONS)?.withIndex()?.forEach {
            restorePositions.put(it.index, it.value)
        }

        if (!::songsAdapter.isInitialized) setupModels()
        if (viewModel.showResumeCard) {
            (requireActivity() as AudioPlayerContainerActivity).proposeCard()
            viewModel.showResumeCard = false
        }

        for (i in 0 until MODE_TOTAL) {
            @Suppress("UNCHECKED_CAST")
            setupLayoutManager(viewModel.providersInCard[i], lists[i], viewModel.providers[i] as MedialibraryProvider<MediaLibraryItem>, adapters[i], spacing)
            (lists[i].layoutManager as LinearLayoutManager).recycleChildrenOnDetach = true
            val list = lists[i]
            list.adapter = adapters[i]
            list.addOnScrollListener(scrollListener)
        }
        viewPager.setOnTouchListener(swipeFilter)
        swipeRefreshLayout.setOnRefreshListener {
            (requireActivity() as ContentActivity).closeSearchView()
            viewModel.refresh()
        }
        viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrollStateChanged(state: Int) {}

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

            override fun onPageSelected(position: Int) {
                activity?.invalidateOptionsMenu()
            }
        })
    }

    override fun onDisplaySettingChanged(key: String, value: Any) {
        when (key) {
            DISPLAY_IN_CARDS -> {
                viewModel.providersInCard[currentTab] = value as Boolean
                @Suppress("UNCHECKED_CAST")
                setupLayoutManager(viewModel.providersInCard[currentTab], lists[currentTab], viewModel.providers[currentTab] as MedialibraryProvider<MediaLibraryItem>, adapters[currentTab], spacing)
                lists[currentTab].adapter = adapters[currentTab]
                activity?.invalidateOptionsMenu()
                Settings.getInstance(requireActivity()).putSingle(viewModel.displayModeKeys[currentTab], value)
            }
            SHOW_ALL_ARTISTS -> {
                Settings.getInstance(requireActivity()).putSingle(KEY_ARTISTS_SHOW_ALL, value as Boolean)
                viewModel.artistsProvider.showAll = value
                viewModel.refresh()
            }
            ONLY_FAVS -> {
                viewModel.providers[currentTab].showOnlyFavs(value as Boolean)
                viewModel.refresh()
                updateTabs()
            }
            CURRENT_SORT -> {
                @Suppress("UNCHECKED_CAST") val sort = value as Pair<Int, Boolean>
                viewModel.providers[currentTab].sort = sort.first
                viewModel.providers[currentTab].desc = sort.second
                viewModel.refresh()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        for (i in 0 until MODE_TOTAL) {
            if (i >= lists.size || i >= adapters.size) continue
            if (lists[i].layoutManager is GridLayoutManager) {
                val gridLayoutManager = lists[i].layoutManager as GridLayoutManager
                gridLayoutManager.spanCount = nbColumns
                lists[i].layoutManager = gridLayoutManager
                adapters[i].notifyItemRangeChanged(gridLayoutManager.findFirstVisibleItemPosition(), gridLayoutManager.findLastVisibleItemPosition() - gridLayoutManager.findFirstVisibleItemPosition())
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val positions = ArrayList<Int>(MODE_TOTAL)
        for (i in 0 until MODE_TOTAL) {
            positions.add((lists[i].layoutManager as LinearLayoutManager).findFirstCompletelyVisibleItemPosition())
        }
        outState.putIntegerArrayList(KEY_LISTS_POSITIONS, positions)
        super.onSaveInstanceState(outState)
    }

    private fun setupModels() {
        viewModel = getViewModel()
        currentTab = viewModel.currentTab

        artistsAdapter = AudioBrowserAdapter(MediaLibraryItem.TYPE_ARTIST, this).apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }
        albumsAdapter = AudioBrowserAdapter(MediaLibraryItem.TYPE_ALBUM, this).apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }
        songsAdapter = AudioBrowserAdapter(MediaLibraryItem.TYPE_MEDIA, this).apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }
        genresAdapter = AudioBrowserAdapter(MediaLibraryItem.TYPE_GENRE, this).apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }
        playlistAdapter = AudioBrowserAdapter(MediaLibraryItem.TYPE_PLAYLIST, this).apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }
        adapters = arrayOf(artistsAdapter, albumsAdapter, songsAdapter, genresAdapter, playlistAdapter)
        setupProvider()
    }

    private fun setupProvider(index: Int = viewModel.currentTab) {
        val provider = viewModel.providers[index.coerceIn(0, viewModel.providers.size - 1)]
        if (provider.loading.hasObservers()) return
        lifecycleScope.launch {
            waitForML()
            provider.pagedList.observe(viewLifecycleOwner) { items ->
                @Suppress("UNCHECKED_CAST")
                if (items != null) adapters.getOrNull(index)?.submitList(items as PagedList<MediaLibraryItem>?)
                updateEmptyView()
                restorePositions.get(index)?.let {
                    lists[index].scrollToPosition(it)
                    restorePositions.delete(index)
                }
                setFabPlayShuffleAllVisibility(items.isNotEmpty())
            }
        }
        provider.loading.observe(viewLifecycleOwner) { loading ->
            if (loading == null || currentTab != index) return@observe
            setRefreshing(loading) { refresh ->
                if (refresh) updateEmptyView()
                else {
                    swipeRefreshLayout.isEnabled = (getCurrentRV().layoutManager as LinearLayoutManager).findFirstVisibleItemPosition() <= 0
                    fastScroller.setRecyclerView(getCurrentRV(), viewModel.providers[currentTab])
                }
            }
        }
        provider.liveHeaders.observe(viewLifecycleOwner) {
            lists[currentTab].invalidateItemDecorations()
        }
        lifecycleScope.launchWhenStarted {
            waitForML()
                provider.pagedList.observe(viewLifecycleOwner) { items ->
                    @Suppress("UNCHECKED_CAST")
                    if (items != null) adapters.getOrNull(index)?.submitList(items as PagedList<MediaLibraryItem>?)
                    updateEmptyView()
                    restorePositions.get(index)?.let {
                        lists[index].scrollToPosition(it)
                        restorePositions.delete(index)
                    }
                    setFabPlayShuffleAllVisibility(items.isNotEmpty())
                }
        }
    }

    override fun onStart() {
        super.onStart()
        setFabPlayShuffleAllVisibility()
        fabPlay?.setImageResource(R.drawable.ic_fab_shuffle)
        fabPlay?.contentDescription = getString(R.string.shuffle_play)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.findItem(R.id.ml_menu_last_playlist)?.isVisible = settings.contains(KEY_AUDIO_LAST_PLAYLIST)
        (viewModel.providers[currentTab]).run {
            menu.findItem(R.id.ml_menu_sortby).isVisible = false
            menu.findItem(R.id.ml_menu_display_options).isVisible = true
        }
        sortMenuTitles(currentTab)
        reopenSearchIfNeeded()
         if (requireActivity().isTalkbackIsEnabled()) menu.findItem(R.id.shuffle_all).isVisible = true
   }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.shuffle_all -> {
                onFabPlayClick(emptyView)
                true
            }
            R.id.ml_menu_display_options -> {
                //filter all sorts and keep only applicable ones
                val sorts = arrayListOf(Medialibrary.SORT_ALPHA, Medialibrary.SORT_FILENAME, Medialibrary.SORT_ARTIST, Medialibrary.SORT_ALBUM, Medialibrary.SORT_DURATION, Medialibrary.SORT_RELEASEDATE, Medialibrary.SORT_LASTMODIFICATIONDATE, Medialibrary.SORT_FILESIZE, Medialibrary.NbMedia).filter {
                    viewModel.providers[currentTab].canSortBy(it)
                }
                //Open the display settings Bottom sheet
                DisplaySettingsDialog.newInstance(
                        displayInCards = viewModel.providersInCard[currentTab],
                        showAllArtists = if (currentTab == 0) Settings.getInstance(requireActivity()).getBoolean(KEY_ARTISTS_SHOW_ALL, false) else null,
                        onlyFavs = viewModel.providers[currentTab].onlyFavs,
                        sorts = sorts,
                        currentSort = viewModel.providers[currentTab].sort,
                        currentSortDesc = viewModel.providers[currentTab].desc
                )
                        .show(requireActivity().supportFragmentManager, "DisplaySettingsDialog")
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun sortBy(sort: Int) {
        viewModel.providers[currentTab].sort(sort)
    }

    override fun onFabPlayClick(view: View) {
        MediaUtils.playAll(activity, viewModel.tracksProvider, 0, true)
    }

    private fun setFabPlayShuffleAllVisibility(force: Boolean = false) {
        setFabPlayVisibility(force || viewModel.providers[currentTab].pagedList.value?.size ?: 0 > 2)
    }

    override fun getTitle(): String = getString(R.string.audio)

    override fun enableSearchOption() = true

    private fun updateEmptyView() {
        swipeRefreshLayout.visibility = if (Medialibrary.getInstance().isInitiated) View.VISIBLE else View.GONE
        emptyView.emptyText = viewModel.filterQuery?.let {  getString(R.string.empty_search, it) } ?: getString(R.string.nomedia)
        emptyView.state = when {
            !Permissions.canReadStorage(requireActivity()) && empty -> EmptyLoadingState.MISSING_PERMISSION
            viewModel.providers[currentTab].loading.value == true && empty -> EmptyLoadingState.LOADING
            empty && viewModel.filterQuery?.isNotEmpty() == true -> EmptyLoadingState.EMPTY_SEARCH
            empty -> EmptyLoadingState.EMPTY
            else -> EmptyLoadingState.NONE

        }
    }

    override fun setupTabLayout() {
        super.setupTabLayout()
        updateTabs()
    }

    /**
     * Setup the tabs custom views
     *
     */
    private fun updateTabs() {
        for (i in 0 until tabLayout!!.tabCount) {
            val tab = tabLayout!!.getTabAt(i)
            val view = tab?.customView ?: View.inflate(requireActivity(), R.layout.audio_tab, null)
            val title = view.findViewById<TextView>(R.id.tab_title)
            val icon = view.findViewById<ImageView>(R.id.tab_icon)
            title.text = audioPagerAdapter.getPageTitle(i)
            if (viewModel.providers[i].onlyFavs) icon.setVisible() else icon.setGone()
            tab?.customView = view
        }
    }

    override fun onPageSelected(position: Int) {
        updateEmptyView()
        setFabPlayShuffleAllVisibility()
    }

    override fun onTabSelected(tab: TabLayout.Tab) {
        adapter = adapters[tab.position]
        viewModel.currentTab = tab.position
        setupProvider()
        super.onTabSelected(tab)
        fastScroller.setRecyclerView(lists[tab.position], viewModel.providers[tab.position])
        settings.putSingle(KEY_AUDIO_CURRENT_TAB, tab.position)
        if (Medialibrary.getInstance().isInitiated) setRefreshing(viewModel.providers[currentTab].isRefreshing)
        activity?.invalidateOptionsMenu()
    }

    override fun onTabUnselected(tab: TabLayout.Tab) {
        super.onTabUnselected(tab)
        onDestroyActionMode(lists[tab.position].adapter as AudioBrowserAdapter?)
        viewModel.restore()
    }

    override fun onTabReselected(tab: TabLayout.Tab) {
        lists[tab.position].smoothScrollToPosition(0)
    }

    override fun onCtxAction(position: Int, option: Long) {
        @Suppress("UNCHECKED_CAST")
        if (option == CTX_PLAY_ALL) MediaUtils.playAll(activity, viewModel.providers[currentTab] as MedialibraryProvider<MediaWrapper>, position, false)
        else super.onCtxAction(position, option)
    }

    override fun onClick(v: View, position: Int, item: MediaLibraryItem) {
        if (actionMode != null) {
            super.onClick(v, position, item)
            return
        }
        if (inSearchMode()) UiTools.setKeyboardVisibility(v, false)
        if (item.itemType == MediaLibraryItem.TYPE_MEDIA) {
            if (item is MediaWrapper && !item.isPresent) {
                UiTools.snackerMissing(requireActivity())
                return
            }
            MediaUtils.openMedia(activity, item as MediaWrapper)
            return
        }
        val i: Intent
        when (item.itemType) {
            MediaLibraryItem.TYPE_ARTIST, MediaLibraryItem.TYPE_GENRE -> {
                i = Intent(activity, SecondaryActivity::class.java)
                i.putExtra(SecondaryActivity.KEY_FRAGMENT, SecondaryActivity.ALBUMS_SONGS)
                i.putExtra(TAG_ITEM, item)
            }
            MediaLibraryItem.TYPE_ALBUM, MediaLibraryItem.TYPE_PLAYLIST -> {
                i = Intent(activity, HeaderMediaListActivity::class.java)
                i.putExtra(TAG_ITEM, item)
            }
            else -> return
        }
        startActivity(i)
    }

    override fun onUpdateFinished(adapter: RecyclerView.Adapter<*>) {
        super.onUpdateFinished(adapter)
        lifecycleScope.launchWhenStarted { // force a dispatch
            if (adapter === getCurrentAdapter()) {
                swipeRefreshLayout.isEnabled = (getCurrentRV().layoutManager as LinearLayoutManager).findFirstVisibleItemPosition() <= 0
                fastScroller.setRecyclerView(getCurrentRV(), viewModel.providers[currentTab])
            } else
                setFabPlayShuffleAllVisibility()
        }
    }

    override fun getCurrentRV() = lists[currentTab]

    override fun getCurrentAdapter() = adapters[currentTab]

    override fun allowedToExpand() = getCurrentRV().scrollState == RecyclerView.SCROLL_STATE_IDLE

    companion object {
        const val TAG = "VLC/AudioBrowserFragment"

        private const val KEY_LISTS_POSITIONS = "key_lists_position"
        private const val MODE_TOTAL = 5 // Number of audio lists

        const val TAG_ITEM = "ML_ITEM"
    }
}
