/*****************************************************************************
 * PlaybackService.kt
 * Copyright © 2011-2018 VLC authors and VideoLAN
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

package org.videolan.vlc

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.*
import android.appwidget.AppWidgetManager
import android.content.*
import android.content.res.Configuration
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.*
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.core.os.bundleOf
import androidx.lifecycle.*
import androidx.lifecycle.Observer
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.flow.MutableStateFlow
import org.videolan.libvlc.FactoryManager
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.RendererItem
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.interfaces.IMediaFactory
import org.videolan.libvlc.interfaces.IVLCVout
import org.videolan.libvlc.util.AndroidUtil
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.resources.*
import org.videolan.resources.util.VLCCrashHandler
import org.videolan.resources.util.getFromMl
import org.videolan.resources.util.launchForeground
import org.videolan.tools.*
import org.videolan.vlc.gui.dialogs.VideoTracksDialog
import org.videolan.vlc.gui.dialogs.adapters.VlcTrack
import org.videolan.vlc.gui.helpers.AudioUtil
import org.videolan.vlc.gui.helpers.NotificationHelper
import org.videolan.vlc.gui.helpers.getBitmapFromDrawable
import org.videolan.vlc.gui.video.PopupManager
import org.videolan.vlc.gui.video.VideoPlayerActivity
import org.videolan.vlc.media.*
import org.videolan.vlc.util.*
import org.videolan.vlc.widget.MiniPlayerAppWidgetProvider
import org.videolan.vlc.widget.VLCAppWidgetProvider
import org.videolan.vlc.widget.VLCAppWidgetProviderBlack
import org.videolan.vlc.widget.VLCAppWidgetProviderWhite
import videolan.org.commontools.LiveEvent
import java.util.*
import kotlin.math.abs

private const val TAG = "VLC/PlaybackService"

class PlaybackService : MediaBrowserServiceCompat(), LifecycleOwner, CoroutineScope {
    override val coroutineContext = Dispatchers.IO + SupervisorJob()

    private var position: Long = -1L
    private val dispatcher = ServiceLifecycleDispatcher(this)

    internal var enabledActions = PLAYBACK_BASE_ACTIONS
    lateinit var playlistManager: PlaylistManager
        private set
    val mediaplayer: MediaPlayer
        get() = playlistManager.player.mediaplayer
    private lateinit var keyguardManager: KeyguardManager
    internal lateinit var settings: SharedPreferences
    private val binder = LocalBinder()
    internal lateinit var medialibrary: Medialibrary
    private lateinit var artworkMap: MutableMap<String, Uri>

    private val callbacks = mutableListOf<Callback>()
    private val subtitleMessage = ArrayDeque<String>(1)
    private lateinit var cbActor: SendChannel<CbAction>
    private var detectHeadset = true
    private lateinit var wakeLock: PowerManager.WakeLock
    private val audioFocusHelper by lazy { VLCAudioFocusHelper(this) }
    private lateinit var browserCallback: MediaBrowserCallback
    var sleepTimerJob: Job? = null
    var waitForMediaEnd = false
    private var mediaEndReached = false

    // Playback management
    internal lateinit var mediaSession: MediaSessionCompat

    @Volatile
    private var notificationShowing = false
    private var prevUpdateInCarMode = true
    private var lastTime = 0L
    private var lastLength = 0L
    private var lastChapter = 0
    private var lastChaptersCount = 0
    private var lastParentId = ""
    private var widget = 0

    /**
     * Last widget position update timestamp
     */
    private var widgetPositionTimestamp = System.currentTimeMillis()
    private var popupManager: PopupManager? = null

    private val mediaFactory = FactoryManager.getFactory(IMediaFactory.factoryId) as IMediaFactory
    private lateinit var carConnectionHandler:CarConnectionHandler

    private val receiver = object : BroadcastReceiver() {
        private var wasPlaying = false
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            val state = intent.getIntExtra("state", 0)

            if (action == CarConnectionHandler.RECEIVER_ACTION) {
                carConnectionHandler.query()
                return
            }

            // skip all headsets events if there is a call
            if ((context.getSystemService(AUDIO_SERVICE) as AudioManager).mode == AudioManager.MODE_IN_CALL) return

            /*
             * Launch the activity if needed
             */
            if (action.startsWith(ACTION_REMOTE_GENERIC) && !isPlaying && !playlistManager.hasCurrentMedia()) {
                packageManager.getLaunchIntentForPackage(packageName)?.let { context.startActivity(it) }
            }

            /*
             * Remote / headset control events
             */
            when (action) {
                CUSTOM_ACTION -> intent.getStringExtra(EXTRA_CUSTOM_ACTION_ID)?.let { actionId ->
                    mediaSession.controller.transportControls.sendCustomAction(actionId, null)
                    executeUpdate()
                    showNotification()
                }
                VLCAppWidgetProvider.ACTION_WIDGET_INIT -> updateWidget()
                VLCAppWidgetProvider.ACTION_WIDGET_ENABLED, VLCAppWidgetProvider.ACTION_WIDGET_DISABLED -> updateHasWidget()
                VLCAppWidgetProvider.ACTION_WIDGET_ENABLED, VLCAppWidgetProvider.ACTION_WIDGET_DISABLED -> updateHasWidget()
                MiniPlayerAppWidgetProvider.ACTION_WIDGET_INIT -> updateWidget()
                MiniPlayerAppWidgetProvider.ACTION_WIDGET_ENABLED, VLCAppWidgetProvider.ACTION_WIDGET_DISABLED -> updateHasWidget()
                MiniPlayerAppWidgetProvider.ACTION_WIDGET_ENABLED, VLCAppWidgetProvider.ACTION_WIDGET_DISABLED -> updateHasWidget()
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> if (detectHeadset) {
                    if (BuildConfig.DEBUG) Log.i(TAG, "Becoming noisy")
                    wasPlaying = isPlaying
                    if (wasPlaying && playlistManager.hasCurrentMedia())
                        pause()
                }
                Intent.ACTION_HEADSET_PLUG -> if (detectHeadset && state != 0) {
                    if (BuildConfig.DEBUG) Log.i(TAG, "Headset Inserted.")
                    if (wasPlaying && playlistManager.hasCurrentMedia() && settings.getBoolean("enable_play_on_headset_insertion", false))
                        play()
                }
            }/*
             * headset plug events
             */
        }
    }

    private val mediaPlayerListener = MediaPlayer.EventListener { event ->
        when (event.type) {
            MediaPlayer.Event.Playing -> {
                if (BuildConfig.DEBUG) Log.i(TAG, "MediaPlayer.Event.Playing")
                executeUpdate(true)
                lastTime = getTime()
                audioFocusHelper.changeAudioFocus(true)
                if (!wakeLock.isHeld) wakeLock.acquire()
                showNotification()
                handler.nbErrors = 0
            }
            MediaPlayer.Event.Paused -> {
                if (BuildConfig.DEBUG) Log.i(TAG, "MediaPlayer.Event.Paused")
                executeUpdate(true)
                showNotification()
                if (wakeLock.isHeld) wakeLock.release()
            }
            MediaPlayer.Event.EncounteredError -> executeUpdate()
            MediaPlayer.Event.LengthChanged -> {
                lastChaptersCount = getChapters(-1)?.size ?: 0
                if (lastLength == 0L) {
                    executeUpdate(true)
                }
            }
            MediaPlayer.Event.PositionChanged -> {
                if (length == 0L) position = (NO_LENGTH_PROGRESS_MAX.toLong() * event.positionChanged).toLong()
                if (getTime() < 1000L && getTime() < lastTime) publishState()
                lastTime = getTime()
                if (widget != 0) updateWidgetPosition(event.positionChanged)
                val curChapter = chapterIdx
                if (lastChapter != curChapter) {
                    executeUpdate()
                    showNotification()
                }
                lastChapter = curChapter
            }
            MediaPlayer.Event.ESAdded -> if (event.esChangedType == IMedia.Track.Type.Video && (playlistManager.videoBackground || !playlistManager.switchToVideo())) {
                /* CbAction notification content intent: resume video or resume audio activity */
                updateMetadata()
            }
            MediaPlayer.Event.MediaChanged -> if (BuildConfig.DEBUG) Log.d(TAG, "onEvent: MediaChanged")
            MediaPlayer.Event.EndReached -> mediaEndReached = true
        }
        cbActor.trySend(CbMediaPlayerEvent(event))
    }

    private val handler = PlaybackServiceHandler(this)

    val sessionPendingIntent: PendingIntent
        get() {
            return when {
                playlistManager.player.isVideoPlaying() -> {//PIP
                    val notificationIntent = Intent(this, VideoPlayerActivity::class.java).apply { putExtra(VideoPlayerActivity.FROM_EXTERNAL, true) }
                    PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                }
                playlistManager.videoBackground || canSwitchToVideo() && !currentMediaHasFlag(MediaWrapper.MEDIA_FORCE_AUDIO) -> {//resume video playback
                    /* Resume VideoPlayerActivity from ACTION_REMOTE_SWITCH_VIDEO intent */
                    val notificationIntent = Intent(ACTION_REMOTE_SWITCH_VIDEO)
                    PendingIntent.getBroadcast(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                }
                else -> { /* Show audio player */
                    val notificationIntent = Intent(this, StartActivity::class.java)
                    PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                }
            }
        }

    @Volatile
    private var isForeground = false

    private var currentWidgetCover: String? = null

    val isPodcastMode: Boolean
        @MainThread
        get() = playlistManager.getMediaListSize() == 1 && playlistManager.getCurrentMedia()?.isPodcast == true

    val speed: Float
        @MainThread
        get() = playlistManager.player.speed.value ?: 1.0F

    val isPlaying: Boolean
        @MainThread
        get() = playlistManager.player.isPlaying()

    val isSeekable: Boolean
        @MainThread
        get() = playlistManager.player.seekable

    val isPausable: Boolean
        @MainThread
        get() = playlistManager.player.pausable

    val isShuffling: Boolean
        @MainThread
        get() = playlistManager.shuffling

    var shuffleType: Int
        @MainThread
        get() = if (playlistManager.shuffling) PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE
        @MainThread
        set(shuffleType) {
            when {
                shuffleType == PlaybackStateCompat.SHUFFLE_MODE_ALL && !isShuffling -> shuffle()
                shuffleType == PlaybackStateCompat.SHUFFLE_MODE_NONE && isShuffling -> shuffle()
                shuffleType == PlaybackStateCompat.SHUFFLE_MODE_GROUP && !isShuffling -> shuffle()
                shuffleType == PlaybackStateCompat.SHUFFLE_MODE_GROUP && isShuffling -> publishState()
            }
        }

    var repeatType: Int
        @MainThread
        get() = playlistManager.repeating
        @MainThread
        set(repeatType) {
            playlistManager.setRepeatType(if (repeatType == PlaybackStateCompat.REPEAT_MODE_GROUP) PlaybackStateCompat.REPEAT_MODE_ALL else repeatType)
            publishState()
        }

    val isVideoPlaying: Boolean
        @MainThread
        get() = playlistManager.player.isVideoPlaying()

    val album: String?
        @MainThread
        get() {
            val media = playlistManager.getCurrentMedia()
            return if (media != null) MediaUtils.getMediaAlbum(this@PlaybackService, media) else null
        }

    val albumPrev: String?
        @MainThread
        get() {
            val prev = playlistManager.getPrevMedia()
            return if (prev != null) MediaUtils.getMediaAlbum(this@PlaybackService, prev) else null
        }

    val albumNext: String?
        @MainThread
        get() {
            val next = playlistManager.getNextMedia()
            return if (next != null) MediaUtils.getMediaAlbum(this@PlaybackService, next) else null
        }

    val artist: String?
        @MainThread
        get() {
            val media = playlistManager.getCurrentMedia()
            return if (media != null) MediaUtils.getMediaArtist(this@PlaybackService, media) else null
        }

    val artistPrev: String?
        @MainThread
        get() {
            val prev = playlistManager.getPrevMedia()
            return if (prev != null) MediaUtils.getMediaArtist(this@PlaybackService, prev) else null
        }

    val artistNext: String?
        @MainThread
        get() {
            val next = playlistManager.getNextMedia()
            return if (next != null) MediaUtils.getMediaArtist(this@PlaybackService, next) else null
        }

    val title: String?
        @MainThread
        get() {
            val media = playlistManager.getCurrentMedia()
            return if (media != null) if (media.nowPlaying != null) media.nowPlaying else media.title else null
        }

    val titlePrev: String?
        @MainThread
        get() {
            val prev = playlistManager.getPrevMedia()
            return prev?.title
        }

    val titleNext: String?
        @MainThread
        get() {
            val next = playlistManager.getNextMedia()
            return next?.title
        }

    val coverArt: String?
        @MainThread
        get() {
            val media = playlistManager.getCurrentMedia()
            return media?.artworkMrl
        }

    val prevCoverArt: String?
        @MainThread
        get() {
            val prev = playlistManager.getPrevMedia()
            return prev?.artworkMrl
        }

    val nextCoverArt: String?
        @MainThread
        get() {
            val next = playlistManager.getNextMedia()
            return next?.artworkMrl
        }

    suspend fun getCurrentChapter(formatted: Boolean = false): String? {
        val currentChapter = withContext(Dispatchers.IO) {
            val chapters = getChapters(-1)
            if (chapters?.size ?: 0 > 0 && chapterIdx >= 0) chapters?.get(chapterIdx)?.name else null
        }
        return if (currentChapter == null) null else if (formatted) TextUtils.formatChapterTitle(this, currentChapter) else currentChapter
    }

    suspend fun trackInfo(): String? {
        val mediaWrapper = playlistManager.getCurrentMedia() ?: return null
        val media = withContext(Dispatchers.IO) {
            val libVlc = VLCInstance.getInstance(this@PlaybackService)
            mediaFactory.getFromUri(libVlc, mediaWrapper.uri).apply { parse() }
        }
        val tracks = media.getAudioTracks()
        media.release()
        return if (tracks.size == 1) tracks.first().formatTrackInfoString(this) else null
    }

    suspend fun prevTrackInfo(): String? {
        val mediaWrapper = playlistManager.getPrevMedia() ?: return null
        val media = withContext(Dispatchers.IO) {
            val libVlc = VLCInstance.getInstance(this@PlaybackService)
            mediaFactory.getFromUri(libVlc, mediaWrapper.uri).apply { parse() }
        }
        val tracks = media.getAudioTracks()
        media.release()
        return if (tracks.size == 1) tracks.first().formatTrackInfoString(this) else null
    }

    suspend fun nextTrackInfo(): String? {
        val mediaWrapper = playlistManager.getNextMedia() ?: return null
        val media = withContext(Dispatchers.IO) {
            val libVlc = VLCInstance.getInstance(this@PlaybackService)
            mediaFactory.getFromUri(libVlc, mediaWrapper.uri).apply { parse() }
        }
        val tracks = media.getAudioTracks()
        media.release()
        return if (tracks.size == 1) tracks.first().formatTrackInfoString(this) else null
    }

    fun IMedia.AudioTrack.formatTrackInfoString(context: Context): String {
        val trackInfo = mutableListOf<String>()
        if (bitrate > 0)
            trackInfo.add(context.getString(R.string.track_bitrate_info, bitrate.toLong().readableSize()))
        trackInfo.add(context.getString(R.string.track_codec_info, codec))
        trackInfo.add(context.getString(R.string.track_samplerate_info, rate))
        return TextUtils.separatedString(trackInfo.toTypedArray()).replace("\n", "")
    }



    val length: Long
        @MainThread
        get() = playlistManager.player.getLength()

    val lastStats: IMedia.Stats?
        get() = playlistManager.player.previousMediaStats

    val isPlayingPopup: Boolean
        @MainThread
        get() = popupManager != null

    val mediaListSize: Int
        get() = playlistManager.getMediaListSize()

    val media: List<MediaWrapper>
        @MainThread
        get() = playlistManager.getMediaList()

    val previousTotalTime
        @MainThread
        get() = playlistManager.previousTotalTime()

    val mediaLocations: List<String>
        @MainThread
        get() {
            return mutableListOf<String>().apply { for (mw in playlistManager.getMediaList()) add(mw.location) }
        }

    val currentMediaLocation: String?
        @MainThread
        get() = playlistManager.getCurrentMedia()?.location

    val currentMediaPosition: Int
        @MainThread
        get() = playlistManager.currentIndex

    val currentMediaWrapper: MediaWrapper?
        @MainThread
        get() = this@PlaybackService.playlistManager.getCurrentMedia()

    val rate: Float
        @MainThread
        get() = playlistManager.player.getRate()

    val titles: Array<out MediaPlayer.Title>?
        @MainThread
        get() = playlistManager.player.getTitles()

    var chapterIdx: Int
        @MainThread
        get() = playlistManager.player.getChapterIdx()
        @MainThread
        set(chapter) {
            playlistManager.player.setChapterIdx(chapter)
            getChapters(-1)?.let {
                publishState(it[chapter].timeOffset)
            }
        }

    var titleIdx: Int
        @MainThread
        get() = playlistManager.player.getTitleIdx()
        @MainThread
        set(title) = playlistManager.player.setTitleIdx(title)

    val volume: Int
        @MainThread
        get() = playlistManager.player.getVolume()

    val audioTracksCount: Int
        @MainThread
        get() = playlistManager.player.getAudioTracksCount()

    val audioTracks: Array<out VlcTrack>?
        @MainThread
        get() = playlistManager.player.getAudioTracks()

    val audioTrack: String
        @MainThread
        get() = playlistManager.player.getAudioTrack()

    val videoTracksCount: Int
        @MainThread
        get() = if (hasMedia()) playlistManager.player.getVideoTracksCount() else 0

    val videoTracks: Array<out VlcTrack>?
        @MainThread
        get() = playlistManager.player.getVideoTracks()

    val currentVideoTrack: VlcTrack?
        @MainThread
        get() = playlistManager.player.getCurrentVideoTrack()

    val videoTrack: String
        @MainThread
        get() = playlistManager.player.getVideoTrack()

    val spuTracks: Array<out VlcTrack>?
        @MainThread
        get() = playlistManager.player.getSpuTracks()

    val spuTrack: String
        @MainThread
        get() = playlistManager.player.getSpuTrack() ?: "-1"

    val spuTracksCount: Int
        @MainThread
        get() = playlistManager.player.getSpuTracksCount()

    val audioDelay: Long
        @MainThread
        get() = playlistManager.player.getAudioDelay()

    val spuDelay: Long
        @MainThread
        get() = playlistManager.player.getSpuDelay()

    interface Callback {
        fun update()
        fun onMediaEvent(event: IMedia.Event)
        fun onMediaPlayerEvent(event: MediaPlayer.Event)
    }

    private inner class LocalBinder : Binder() {
        internal val service: PlaybackService
            get() = this@PlaybackService
    }

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.getContextWithLocale(AppContextProvider.locale))
    }

    override fun getApplicationContext(): Context {
        return super.getApplicationContext().getContextWithLocale(AppContextProvider.locale)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        dispatcher.onServicePreSuperOnCreate()
        setupScope()
        forceForeground()
        super.onCreate()
        NotificationHelper.createNotificationChannels(applicationContext)
        settings = Settings.getInstance(this)
        playlistManager = PlaylistManager(this)
        Util.checkCpuCompatibility(this)

        medialibrary = Medialibrary.getInstance()
        artworkMap = HashMap<String, Uri>()

        browserCallback = MediaBrowserCallback(this)
        browserCallback.registerMediaCallback { if (lastParentId.isNotEmpty()) notifyChildrenChanged(lastParentId) }
        browserCallback.registerHistoryCallback {
            when (lastParentId) {
                MediaSessionBrowser.ID_HOME, MediaSessionBrowser.ID_HISTORY -> notifyChildrenChanged(lastParentId)
            }
        }

        detectHeadset = settings.getBoolean("enable_headset_detection", true)

        // Make sure the audio player will acquire a wake-lock while playing. If we don't do
        // that, the CPU might go to sleep while the song is playing, causing playback to stop.
        val pm = applicationContext.getSystemService<PowerManager>()!!
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)

        updateHasWidget()
        if (!this::mediaSession.isInitialized) initMediaSession()

        val filter = IntentFilter().apply {
            priority = Integer.MAX_VALUE
            addAction(VLCAppWidgetProvider.ACTION_WIDGET_INIT)
            addAction(VLCAppWidgetProvider.ACTION_WIDGET_ENABLED)
            addAction(VLCAppWidgetProvider.ACTION_WIDGET_DISABLED)
            addAction(MiniPlayerAppWidgetProvider.ACTION_WIDGET_INIT)
            addAction(MiniPlayerAppWidgetProvider.ACTION_WIDGET_ENABLED)
            addAction(MiniPlayerAppWidgetProvider.ACTION_WIDGET_DISABLED)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (CarConnectionHandler.preferCarConnectionHandler()) addAction(CarConnectionHandler.RECEIVER_ACTION)
            addAction(CUSTOM_ACTION)
        }
        registerReceiver(receiver, filter)
        if (CarConnectionHandler.preferCarConnectionHandler()) {
            carConnectionHandler = CarConnectionHandler(contentResolver)
            carConnectionHandler.connectionType.observeForever {
                if (it != null) publishState()
            }
        }

        keyguardManager = getSystemService()!!
        renderer.observe(this, Observer { setRenderer(it) })
        restartPlayer.observe(this, Observer { restartPlaylistManager() })
        headSetDetection.observe(this, Observer { detectHeadset(it) })
        equalizer.observe(this, Observer { setEqualizer(it) })
        serviceFlow.value = this
    }

    @OptIn(ObsoleteCoroutinesApi::class)
    private fun setupScope() {
        cbActor = lifecycleScope.actor(capacity = Channel.UNLIMITED) {
            for (update in channel) when (update) {
                CbUpdate -> for (callback in callbacks) callback.update()
                is CbMediaEvent -> for (callback in callbacks) callback.onMediaEvent(update.event)
                is CbMediaPlayerEvent -> for (callback in callbacks) callback.onMediaPlayerEvent(update.event)
                is CbRemove -> callbacks.remove(update.cb)
                is CbAdd -> callbacks.add(update.cb)
                ShowNotification -> showNotificationInternal()
                is HideNotification -> hideNotificationInternal(update.remove)
                UpdateMeta -> updateMetadataInternal()
            }
        }
    }

    private fun updateHasWidget() {
        val manager = AppWidgetManager.getInstance(this) ?: return
        widget = when {
            manager.getAppWidgetIds(ComponentName(this, VLCAppWidgetProviderWhite::class.java)).isNotEmpty() -> 1
            manager.getAppWidgetIds(ComponentName(this, VLCAppWidgetProviderBlack::class.java)).isNotEmpty() -> 2
            manager.getAppWidgetIds(ComponentName(this, MiniPlayerAppWidgetProvider::class.java)).isNotEmpty() -> 3
            else -> 0
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        forceForeground(intent?.extras?.getBoolean("foreground", false) ?: false)
        dispatcher.onServicePreSuperOnStart()
        setupScope()
        when (intent?.action) {
            Intent.ACTION_MEDIA_BUTTON -> {
                if (AndroidDevices.hasTsp || AndroidDevices.hasPlayServices) MediaButtonReceiver.handleIntent(mediaSession, intent)
            }
            ACTION_REMOTE_PLAYPAUSE,
            ACTION_REMOTE_PLAY,
            ACTION_REMOTE_LAST_PLAYLIST -> {
                if (playlistManager.hasCurrentMedia()) {
                    if (isPlaying) pause()
                    else play()
                } else loadLastAudioPlaylist()
            }
            ACTION_REMOTE_BACKWARD -> previous(false)
            ACTION_REMOTE_FORWARD -> next()
            ACTION_REMOTE_STOP -> stop()
            ACTION_REMOTE_SEEK_FORWARD -> seek(getTime() + intent.getLongExtra(EXTRA_SEEK_DELAY, 0L) * 1000L)
            ACTION_REMOTE_SEEK_BACKWARD -> seek(getTime() - intent.getLongExtra(EXTRA_SEEK_DELAY, 0L) * 1000L)
            ACTION_PLAY_FROM_SEARCH -> {
                if (!this::mediaSession.isInitialized) initMediaSession()
                intent.getBundleExtra(EXTRA_SEARCH_BUNDLE)?.let {
                    mediaSession.controller.transportControls
                            .playFromSearch(it.getString(SearchManager.QUERY), it)
                }
            }
            ACTION_REMOTE_SWITCH_VIDEO -> {
                removePopup()
                if (hasMedia()) {
                    currentMediaWrapper!!.removeFlags(MediaWrapper.MEDIA_FORCE_AUDIO)
                    playlistManager.switchToVideo()
                }
            }
        }
        return Service.START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent) {
        if (settings.getBoolean("audio_task_removed", false)) stop()
    }

    override fun onDestroy() {
        serviceFlow.value = null
        dispatcher.onServicePreSuperOnDestroy()
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        browserCallback.removeCallbacks()
        if (!settings.getBoolean(AUDIO_RESUME_PLAYBACK, true)) (getSystemService(NOTIFICATION_SERVICE)as NotificationManager).cancel(3)
        if (this::mediaSession.isInitialized) mediaSession.release()
        //Call it once mediaSession is null, to not publish playback state
        stop(systemExit = true)

        unregisterReceiver(receiver)
        playlistManager.onServiceDestroyed()
    }

    override fun onBind(intent: Intent): IBinder? {
        dispatcher.onServicePreSuperOnBind()
        return if (SERVICE_INTERFACE == intent.action) super.onBind(intent) else binder
    }

    val vout: IVLCVout?
        get() {
            return playlistManager.player.getVout()
        }

    @TargetApi(Build.VERSION_CODES.O)
    private fun forceForeground(launchedInForeground: Boolean = false) {
        if (!AndroidUtil.isOOrLater || isForeground) return
        val ctx = applicationContext
        val stopped = PlayerController.playbackState == PlaybackStateCompat.STATE_STOPPED || PlayerController.playbackState == PlaybackStateCompat.STATE_NONE
        if (stopped && !launchedInForeground) {
            if (BuildConfig.DEBUG) Log.i("PlaybackService", "Service not in foreground and player is stopped. Skipping the notification")
            return
        }
        val notification = if (this::notification.isInitialized && !stopped) notification
        else {
            val pi = if (::playlistManager.isInitialized) sessionPendingIntent else null
            NotificationHelper.createPlaybackNotification(ctx, false,
                    ctx.resources.getString(R.string.loading), "", "", null, false, true,
                    true, speed, isPodcastMode, false, enabledActions, null, pi)
        }
        startForeground(3, notification)
        isForeground = true
        if (stopped) lifecycleScope.launch { hideNotification(true) }
    }

    private fun sendStartSessionIdIntent() {
        val sessionId = VLCOptions.audiotrackSessionId
        if (sessionId == 0) return

        val intent = Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
        intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
        intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
        if (isVideoPlaying) intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MOVIE)
        else intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        sendBroadcast(intent)
    }

    private fun sendStopSessionIdIntent() {
        val sessionId = VLCOptions.audiotrackSessionId
        if (sessionId == 0) return

        val intent = Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
        intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
        sendBroadcast(intent)
    }

    fun setBenchmark() {
        playlistManager.isBenchmark = true
    }

    fun setHardware() {
        playlistManager.isHardware = true
    }

    fun setTime(time: Long, fast: Boolean = false) {
        val shouldFast = fast || (!playlistManager.isBenchmark && settings.getBoolean("always_fast_seek", false))
        playlistManager.player.setTime(time, shouldFast)
        publishState(time)
    }

    fun getTime() = playlistManager.player.getCurrentTime()

    fun onMediaPlayerEvent(event: MediaPlayer.Event) = mediaPlayerListener.onEvent(event)

    fun onPlaybackStopped(systemExit: Boolean) {
        if (!systemExit) hideNotification(isForeground)
        removePopup()
        if (wakeLock.isHeld) wakeLock.release()
        audioFocusHelper.changeAudioFocus(false)
        // We must publish state before resetting mCurrentIndex
        publishState()
        executeUpdate()
    }

    private fun canSwitchToVideo() = playlistManager.player.canSwitchToVideo()

    fun onMediaEvent(event: IMedia.Event) = cbActor.trySend(CbMediaEvent(event))

    fun executeUpdate(pubState: Boolean = false) {
        cbActor.trySend(CbUpdate)
        updateWidget()
        updateMetadata()
        broadcastMetadata()
        if (pubState)
            publishState()
    }

    private class PlaybackServiceHandler(owner: PlaybackService) : WeakHandler<PlaybackService>(owner) {

        var currentToast: Toast? = null
        var lastErrorTime = 0L
        var nbErrors = 0

        @SuppressLint("ShowToast")
        override fun handleMessage(msg: Message) {
            val service = owner ?: return
            when (msg.what) {
                SHOW_TOAST -> {
                    val bundle = msg.data
                    var text = bundle.getString("text")
                    val duration = bundle.getInt("duration")
                    val isError = bundle.getBoolean("isError")
                    if (isError) {
                        when {
                            nbErrors > 2 && System.currentTimeMillis() - lastErrorTime < 500 -> return
                            nbErrors >= 2 -> text = service.getString(R.string.playback_multiple_errors)
                        }
                        currentToast?.cancel()
                        nbErrors++
                        lastErrorTime = System.currentTimeMillis()
                    }
                    currentToast = Toast.makeText(AppContextProvider.appContext, text, duration)
                    currentToast?.show()
                }
                END_MEDIASESSION -> if (service::mediaSession.isInitialized) service.mediaSession.isActive = false
            }
        }
    }

    fun showNotification(): Boolean {
        notificationShowing = true
        return cbActor.trySend(ShowNotification).isSuccess
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun showNotificationInternal() {
        if (!AndroidDevices.isAndroidTv && Settings.showTvUi) return
        if (isPlayingPopup || !hasRenderer() && isVideoPlaying) {
            hideNotificationInternal(true)
            return
        }
        val mw = playlistManager.getCurrentMedia()
        if (mw != null) {
            val coverOnLockscreen = settings.getBoolean(LOCKSCREEN_COVER, true)
            val seekInCompactView = settings.getBoolean(SHOW_SEEK_IN_COMPACT_NOTIFICATION, false)
            val playing = isPlaying
            val sessionToken = mediaSession.sessionToken
            val ctx = this
            val metaData = mediaSession.controller.metadata
            lifecycleScope.launch(Dispatchers.Default) {
                delay(100)
                if (isPlayingPopup || !notificationShowing) return@launch
                try {
                    val title = if (metaData == null) mw.title else metaData.getString(MediaMetadataCompat.METADATA_KEY_TITLE)
                    val artist = if (metaData == null) mw.artist else metaData.getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST)
                    val album = if (metaData == null) mw.album else metaData.getString(MediaMetadataCompat.METADATA_KEY_ALBUM)
                    var cover = if (coverOnLockscreen && metaData != null)
                        metaData.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART) else null
                    if (coverOnLockscreen && cover == null)
                        cover = AudioUtil.readCoverBitmap(Uri.decode(mw.artworkMrl), 256)
                    if (cover == null || cover.isRecycled)
                        cover = ctx.getBitmapFromDrawable(R.drawable.ic_no_media)

                    notification = NotificationHelper.createPlaybackNotification(ctx,
                            canSwitchToVideo(), title, artist, album, cover, playing, isPausable,
                            isSeekable, speed, isPodcastMode, seekInCompactView, enabledActions,
                            sessionToken, sessionPendingIntent)
                    if (isPlayingPopup) return@launch
                    if (!AndroidUtil.isLolliPopOrLater || playing || audioFocusHelper.lossTransient) {
                        if (!isForeground) {
                            this@PlaybackService.startForeground(3, notification)
                            isForeground = true
                        } else
                            NotificationManagerCompat.from(ctx).notify(3, notification)
                    } else {
                        if (isForeground) {
                            ServiceCompat.stopForeground(this@PlaybackService, ServiceCompat.STOP_FOREGROUND_DETACH)
                            isForeground = false
                        }
                        NotificationManagerCompat.from(ctx).notify(3, notification)
                    }
                } catch (e: IllegalArgumentException) {
                    // On somme crappy firmwares, shit can happen
                    Log.e(TAG, "Failed to display notification", e)
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "Failed to display notification", e)
                } catch (e: RuntimeException) {
                    Log.e(TAG, "Failed to display notification", e)
                } catch (e: ArrayIndexOutOfBoundsException) {
                    // Happens on Android 7.0 (Xperia L1 (G3312))
                    Log.e(TAG, "Failed to display notification", e)
                }
            }
        }
    }

    private lateinit var notification: Notification

    private fun currentMediaHasFlag(flag: Int): Boolean {
        val mw = playlistManager.getCurrentMedia()
        return mw != null && mw.hasFlag(flag)
    }

    private fun hideNotification(remove: Boolean): Boolean {
        notificationShowing = false
        return if (::cbActor.isInitialized) cbActor.trySend(HideNotification(remove)).isSuccess else false
    }

    private fun hideNotificationInternal(remove: Boolean) {
        if (!isPlayingPopup && isForeground) {
            ServiceCompat.stopForeground(this@PlaybackService, if (remove) ServiceCompat.STOP_FOREGROUND_REMOVE else ServiceCompat.STOP_FOREGROUND_DETACH)
            isForeground = false
        }
        NotificationManagerCompat.from(this@PlaybackService).cancel(3)
    }

    fun onNewPlayback() = mediaSession.setSessionActivity(sessionPendingIntent)

    fun onPlaylistLoaded() {
        notifyTrackChanged()
        updateMediaQueue()
    }

    @MainThread
    fun pause() = playlistManager.pause()

    @MainThread
    fun play() = playlistManager.play()

    @MainThread
    @JvmOverloads
    fun stop(systemExit: Boolean = false, video: Boolean = false) {
        playlistManager.stop(systemExit, video)
    }

    private fun initMediaSession() {
        val mbrIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE)
        val mbrName = ComponentName(this, MediaButtonReceiver::class.java)
        val playbackState = PlaybackStateCompat.Builder()
                .setActions(enabledActions)
                .setState(PlaybackStateCompat.STATE_NONE, 0, 0f)
                .build()
        mediaSession = MediaSessionCompat(this, "VLC", mbrName, mbrIntent).apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(MediaSessionCallback(this@PlaybackService))
            setPlaybackState(playbackState)
        }
        try {
            mediaSession.isActive = true
        } catch (e: NullPointerException) {
            // Some versions of KitKat do not support AudioManager.registerMediaButtonIntent
            // with a PendingIntent. They will throw a NullPointerException, in which case
            // they should be able to activate a MediaSessionCompat with only transport
            // controls.
            mediaSession.isActive = false
            mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            mediaSession.isActive = true
        }

        sessionToken = mediaSession.sessionToken
    }

    private fun updateMetadata() {
        cbActor.trySend(UpdateMeta)
    }

    private suspend fun updateMetadataInternal() {
        val media = playlistManager.getCurrentMedia() ?: return
        if (!this::mediaSession.isInitialized) initMediaSession()
        val ctx = this
        val length = length
        lastLength = length
        val chapterTitle = if (lastChaptersCount > 0) getCurrentChapter(true) else null
        val displayMsg = subtitleMessage.poll()
        val bob = withContext(Dispatchers.Default) {
            val carMode = isCarMode()
            val title = media.nowPlaying ?: media.title
            val coverOnLockscreen = settings.getBoolean(LOCKSCREEN_COVER, true)
            val bob = MediaMetadataCompat.Builder().apply {
                putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, MediaSessionBrowser.generateMediaId(media))
                putString(MediaMetadataCompat.METADATA_KEY_GENRE, MediaUtils.getMediaGenre(ctx, media))
                putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, media.trackNumber.toLong())
                putString(MediaMetadataCompat.METADATA_KEY_ARTIST, MediaUtils.getMediaArtist(ctx, media))
                putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, MediaUtils.getMediaReferenceArtist(ctx, media))
                putString(MediaMetadataCompat.METADATA_KEY_ALBUM, MediaUtils.getMediaAlbum(ctx, media))
                putLong(MediaMetadataCompat.METADATA_KEY_DURATION, if (length != 0L) length else -1L)
            }
            if (carMode) {
                bob.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, chapterTitle ?: title)
                bob.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, displayMsg
                        ?: MediaUtils.getDisplaySubtitle(ctx, media, currentMediaPosition, mediaListSize))
                bob.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, MediaUtils.getMediaAlbum(ctx, media))
            }
            if (Permissions.canReadStorage(this@PlaybackService) && coverOnLockscreen) {
                val albumArtUri = when {
                    isSchemeHttpOrHttps(media.artworkMrl) -> {
                        //ArtworkProvider will cache remote images
                        ArtworkProvider.buildUri(ctx, Uri.Builder()
                                .appendPath(ArtworkProvider.REMOTE)
                                .appendQueryParameter(ArtworkProvider.PATH, media.artworkMrl)
                                .build())
                    }
                    else -> {
                        //The media id may be 0 on resume
                        val mw = getFromMl { findMedia(media) }
                        val mediaId = MediaSessionBrowser.generateMediaId(mw)
                        artworkMap[mediaId] ?: ArtworkProvider.buildMediaUri(ctx, mw)
                    }
                }
                bob.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, albumArtUri.toString())
                if (!carMode) {
                    val cover = AudioUtil.readCoverBitmap(Uri.decode(media.artworkMrl), 512)
                    if (cover?.config != null)
                    //In case of format not supported
                        bob.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cover.copy(cover.config, false))
                    else
                        bob.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, ctx.getBitmapFromDrawable(R.drawable.ic_no_media, 512, 512))
                }
            }
            return@withContext bob.build()
        }
        if (this@PlaybackService::mediaSession.isInitialized) mediaSession.setMetadata(bob)
    }

    private fun publishState(position: Long? = null) {
        if (!this::mediaSession.isInitialized) return
        if (AndroidDevices.isAndroidTv) handler.removeMessages(END_MEDIASESSION)
        val pscb = PlaybackStateCompat.Builder()
        var actions = PLAYBACK_BASE_ACTIONS
        val hasMedia = playlistManager.hasCurrentMedia()
        var time = position ?: getTime()
        var state = PlayerController.playbackState
        when (state) {
            PlaybackStateCompat.STATE_PLAYING -> actions = actions or (PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP)
            PlaybackStateCompat.STATE_PAUSED -> actions = actions or (PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_STOP)
            else -> {
                actions = actions or PlaybackStateCompat.ACTION_PLAY
                val media = if (AndroidDevices.isAndroidTv && !AndroidUtil.isOOrLater && hasMedia) playlistManager.getCurrentMedia() else null
                if (media != null) { // Hack to show a now paying card on Android TV
                    val length = media.length
                    time = media.time
                    val progress = if (length <= 0L) 0f else time / length.toFloat()
                    if (progress < 0.95f) {
                        state = PlaybackStateCompat.STATE_PAUSED
                        handler.sendEmptyMessageDelayed(END_MEDIASESSION, 900_000L)
                    }
                }
            }
        }
        pscb.setState(state, time, playlistManager.player.getRate())
        pscb.setActiveQueueItemId(playlistManager.currentIndex.toLong())
        val repeatType = playlistManager.repeating
        val podcastMode = playlistManager.getMediaListSize() == 1 && playlistManager.getCurrentMedia()?.isPodcast == true
        if (repeatType != PlaybackStateCompat.REPEAT_MODE_NONE || hasNext())
            actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        if (repeatType != PlaybackStateCompat.REPEAT_MODE_NONE || hasPrevious() || (isSeekable && !podcastMode))
            actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS

        when {
            podcastMode -> {
                addCustomSeekActions(pscb)
                addCustomSpeedActions(pscb)
                pscb.addCustomAction(CUSTOM_ACTION_BOOKMARK, getString(R.string.add_bookmark), R.drawable.ic_bookmark_add)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                if (!isCarMode()) {
                    addCustomSeekActions(pscb)
                } else {
                    actions = manageAutoActions(actions, pscb, repeatType)
                }
            }
            else -> {
                actions = manageAutoActions(actions, pscb, repeatType)
            }
        }
        actions = actions or PlaybackStateCompat.ACTION_FAST_FORWARD or PlaybackStateCompat.ACTION_REWIND or PlaybackStateCompat.ACTION_SEEK_TO
        pscb.setActions(actions)
        mediaSession.setRepeatMode(repeatType)
        mediaSession.setShuffleMode(if (isShuffling) PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE)
        mediaSession.setExtras(Bundle().apply {
            putBoolean(WEARABLE_RESERVE_SLOT_SKIP_TO_NEXT, !podcastMode)
            putBoolean(WEARABLE_RESERVE_SLOT_SKIP_TO_PREV, !podcastMode)
            putBoolean(PLAYBACK_SLOT_RESERVATION_SKIP_TO_NEXT, !podcastMode)
            putBoolean(PLAYBACK_SLOT_RESERVATION_SKIP_TO_PREV, !podcastMode)
        })
        val mediaIsActive = state != PlaybackStateCompat.STATE_STOPPED
        val update = mediaSession.isActive != mediaIsActive
        updateMediaQueueSlidingWindow()
        mediaSession.setPlaybackState(pscb.build())
        enabledActions = actions
        mediaSession.isActive = mediaIsActive
        mediaSession.setQueueTitle(getString(R.string.music_now_playing))
        if (update) {
            if (mediaIsActive) sendStartSessionIdIntent()
            else sendStopSessionIdIntent()
        }
    }

    private fun manageAutoActions(actions: Long, pscb: PlaybackStateCompat.Builder, repeatType: Int): Long {
        var resultActions = actions
        if (playlistManager.canRepeat())
            resultActions = resultActions or PlaybackStateCompat.ACTION_SET_REPEAT_MODE
        if (playlistManager.canShuffle())
            resultActions = resultActions or PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE
        /* Always add the icons, regardless of the allowed actions */
        val shuffleResId = when {
            isShuffling -> R.drawable.ic_auto_shuffle_enabled
            else -> R.drawable.ic_auto_shuffle_disabled
        }
        pscb.addCustomAction(CUSTOM_ACTION_SHUFFLE, getString(R.string.shuffle_title), shuffleResId)
        val repeatResId = when (repeatType) {
            PlaybackStateCompat.REPEAT_MODE_ALL -> R.drawable.ic_auto_repeat_pressed
            PlaybackStateCompat.REPEAT_MODE_ONE -> R.drawable.ic_auto_repeat_one_pressed
            else -> R.drawable.ic_auto_repeat_normal
        }
        pscb.addCustomAction(CUSTOM_ACTION_REPEAT, getString(R.string.repeat_title), repeatResId)
        addCustomSpeedActions(pscb, settings.getBoolean("enable_android_auto_speed_buttons", false))
        addCustomSeekActions(pscb, settings.getBoolean("enable_android_auto_seek_buttons", false))
        return resultActions
    }

    private fun addCustomSeekActions(pscb: PlaybackStateCompat.Builder, showSeekActions: Boolean = true) {
        if (!showSeekActions) return
        val ctx = applicationContext
        /* Rewind */
        pscb.addCustomAction(PlaybackStateCompat.CustomAction.Builder(CUSTOM_ACTION_REWIND,
                getString(R.string.playback_rewind),
                DrawableCache.getDrawableFromMemCache(ctx, "ic_auto_rewind_${Settings.audioJumpDelay}", R.drawable.ic_auto_rewind))
                .setExtras(Bundle().apply { putBoolean(WEARABLE_SHOW_CUSTOM_ACTION, true) })
                .build())
        /* Fast Forward */
        pscb.addCustomAction(PlaybackStateCompat.CustomAction.Builder(CUSTOM_ACTION_FAST_FORWARD,
                getString(R.string.playback_forward),
                DrawableCache.getDrawableFromMemCache(ctx, "ic_auto_forward_${Settings.audioJumpDelay}", R.drawable.ic_auto_forward))
                .setExtras(Bundle().apply { putBoolean(WEARABLE_SHOW_CUSTOM_ACTION, true) })
                .build())
    }

    private fun addCustomSpeedActions(pscb: PlaybackStateCompat.Builder, showSpeedActions: Boolean = true) {
        if (speed != 1.0F || showSpeedActions) {
            val speedIcons = hashMapOf(
                    0.50f to R.drawable.ic_auto_speed_0_50,
                    0.80f to R.drawable.ic_auto_speed_0_80,
                    1.00f to R.drawable.ic_auto_speed_1_00,
                    1.10f to R.drawable.ic_auto_speed_1_10,
                    1.20f to R.drawable.ic_auto_speed_1_20,
                    1.50f to R.drawable.ic_auto_speed_1_50,
                    2.00f to R.drawable.ic_auto_speed_2_00
            )
            val speedResId = speedIcons[speedIcons.keys.minByOrNull { abs(speed - it) }]
                    ?: R.drawable.ic_auto_speed
            pscb.addCustomAction(CUSTOM_ACTION_SPEED, getString(R.string.playback_speed), speedResId)
        }
    }

    fun notifyTrackChanged() {
        updateMetadata()
        updateWidget()
        broadcastMetadata()
    }

    fun onMediaListChanged() {
        executeUpdate()
        updateMediaQueue()
    }

    @MainThread
    fun next(force: Boolean = true) = playlistManager.next(force)

    @MainThread
    fun previous(force: Boolean) = playlistManager.previous(force)

    @MainThread
    fun shuffle() {
        playlistManager.shuffle()
        publishState()
        browserCallback.onShuffleChanged()
    }

    private fun updateWidget() {
        if (widget != 0 && !isVideoPlaying) {
            updateWidgetState()
        }
    }

    private fun sendWidgetBroadcast(intent: Intent) {
        intent.component = ComponentName(this@PlaybackService, if (widget == 1) VLCAppWidgetProviderWhite::class.java else if (widget == 3) MiniPlayerAppWidgetProvider::class.java else VLCAppWidgetProviderBlack::class.java)
        sendBroadcast(intent)
    }

    fun updateWidgetState() {
        val widgetIntents = arrayOf(Intent(VLCAppWidgetProvider.ACTION_WIDGET_UPDATE), Intent(MiniPlayerAppWidgetProvider.ACTION_WIDGET_UPDATE))
        lifecycleScope.launch(Dispatchers.Default) { widgetIntents.forEach { sendWidgetBroadcast(it) } }
    }

    private fun updateWidgetPosition(pos: Float) {
        val mw = playlistManager.getCurrentMedia()
        if (mw == null || widget == 0 || isVideoPlaying) return
        // no more than one widget mUpdateMeta for each 1/50 of the song
        val timestamp = System.currentTimeMillis()
        if (BuildConfig.DEBUG) Log.d("AppWidget", "PositionChanged // $widgetPositionTimestamp")
        if (!playlistManager.hasCurrentMedia() || timestamp - widgetPositionTimestamp < 500)
            return
        widgetPositionTimestamp = timestamp
        sendWidgetBroadcast(Intent(MiniPlayerAppWidgetProvider.ACTION_WIDGET_UPDATE_POSITION)
                .putExtra("position", pos))
    }

    private fun broadcastMetadata() {
        val media = playlistManager.getCurrentMedia()
        if (isVideoPlaying) return
        if (lifecycleScope.isActive) lifecycleScope.launch(Dispatchers.Default) {
            sendBroadcast(Intent("com.android.music.metachanged")
                    .putExtra("track", media?.nowPlaying ?: media?.title ?: null)
                    .putExtra("artist", if (media != null) MediaUtils.getMediaArtist(this@PlaybackService, media) else null)
                    .putExtra("album", if (media != null) MediaUtils.getMediaAlbum(this@PlaybackService, media) else null)
                    .putExtra("duration", media?.length ?: 0)
                    .putExtra("playing", isPlaying)
                    .putExtra("package", "org.videolan.vlc")
                    .apply {
                        if (lastChaptersCount > 0) getCurrentChapter()?.let { putExtra("chapter", it) }
                    })
        }
    }

    private fun loadLastAudioPlaylist() {
        if (!AndroidDevices.isAndroidTv) loadLastPlaylist(PLAYLIST_TYPE_AUDIO)
    }

    fun loadLastPlaylist(type: Int) {
        forceForeground(true)
        if (!playlistManager.loadLastPlaylist(type)) {
            Toast.makeText(this, getString(R.string.resume_playback_error), Toast.LENGTH_LONG).show()
            stopService(Intent(applicationContext, PlaybackService::class.java))
        }
    }

    fun showToast(text: String, duration: Int, isError: Boolean = false) {
        val msg = handler.obtainMessage().apply {
            what = SHOW_TOAST
            data = bundleOf("text" to text, "duration" to duration, "isError" to isError)
        }
        handler.removeMessages(SHOW_TOAST)
        handler.sendMessage(msg)
    }

    @MainThread
    fun canShuffle() = playlistManager.canShuffle()

    @MainThread
    fun hasMedia() = PlaylistManager.hasMedia()

    @MainThread
    fun hasPlaylist() = playlistManager.hasPlaylist()

    @MainThread
    fun addCallback(cb: Callback) = cbActor.trySend(CbAdd(cb))

    @MainThread
    fun removeCallback(cb: Callback) = cbActor.trySend(CbRemove(cb))

    private fun restartPlaylistManager() = playlistManager.restart()
    fun restartMediaPlayer() = playlistManager.player.restart()

    fun saveMediaMeta() = playlistManager.saveMediaMeta()

    fun isValidIndex(positionInPlaylist: Int) = playlistManager.isValidPosition(positionInPlaylist)

    /**
     * Loads a selection of files (a non-user-supplied collection of media)
     * into the primary or "currently playing" playlist.
     *
     * @param mediaPathList A list of locations to load
     * @param position The position to start playing at
     */
    @MainThread
    private fun loadLocations(mediaPathList: List<String>, position: Int) = playlistManager.loadLocations(mediaPathList, position)

    @MainThread
    fun loadUri(uri: Uri?) = loadLocation(uri!!.toString())

    @MainThread
    fun loadLocation(mediaPath: String) = loadLocations(listOf(mediaPath), 0)

    @MainThread
    fun load(mediaList: Array<MediaWrapper>?, position: Int) {
        mediaList?.let { load(it.toList(), position) }
    }

    @MainThread
    fun load(mediaList: List<MediaWrapper>, position: Int) = lifecycleScope.launch { playlistManager.load(mediaList, position) }

    private fun updateMediaQueue() = lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
        if (!this@PlaybackService::mediaSession.isInitialized) initMediaSession()
        artworkMap = HashMap<String, Uri>().also {
            val ctx = this@PlaybackService
            val artworkToUriCache = HashMap<String, Uri>()
            for (media in playlistManager.getMediaList()) {
                try {
                    val artworkMrl = media.artworkMrl
                    if (!artworkMrl.isNullOrEmpty() && isPathValid(artworkMrl)) {
                        val artworkUri = artworkToUriCache.getOrPut(artworkMrl) { ArtworkProvider.buildMediaUri(ctx, media) }
                        val key = MediaSessionBrowser.generateMediaId(media)
                        it[key] = artworkUri
                    }
                } catch (e: java.lang.NullPointerException) {
                    Log.e("PlaybackService", "Caught NullPointerException", e)
                    VLCCrashHandler.saveLog(e, "NullPointerException in PlaybackService updateMediaQueue")
                }
            }
            artworkToUriCache.clear()
        }
        updateMediaQueueSlidingWindow(true)
    }

    /**
     * Set the mediaSession queue to a sliding window of fifteen tracks max, with the current song
     * centered in the queue (when possible). Fifteen tracks are used instead of seventeen to
     * prevent the "Search By Name" bar from appearing on the top of the window.
     * If Android Auto is exited, set the entire queue on the next update so that Bluetooth
     * headunits that report the track number show the correct value in the playlist.
     */
    private fun updateMediaQueueSlidingWindow(mediaListChanged: Boolean = false) = lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
        if (isCarMode()) {
            val mediaList = playlistManager.getMediaList()
            val halfWindowSize = 7
            val windowSize = 2 * halfWindowSize + 1
            val songNum = currentMediaPosition + 1
            var fromIndex = 0
            var toIndex = (mediaList.size).coerceAtMost(windowSize)
            if (songNum > halfWindowSize) {
                toIndex = (songNum + halfWindowSize).coerceAtMost(mediaList.size)
                fromIndex = (toIndex - windowSize).coerceAtLeast(0)
            }
            //The on-screen queue icon will disappear if an empty queue is passed.
            if (mediaList.isNotEmpty()) buildQueue(mediaList, fromIndex, toIndex)
            prevUpdateInCarMode = true
        } else if (mediaListChanged || prevUpdateInCarMode) {
            buildQueue(playlistManager.getMediaList())
            prevUpdateInCarMode = false
        }
    }

    private fun buildQueue(mediaList: List<MediaWrapper>, fromIndex: Int = 0, toIndex: Int = mediaList.size) = lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
        if (!this@PlaybackService.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) return@launch
        val ctx = this@PlaybackService
        val queue = withContext(Dispatchers.Default) {
            ArrayList<MediaSessionCompat.QueueItem>(toIndex - fromIndex).also {
                for ((position, media) in mediaList.subList(fromIndex, toIndex).withIndex()) {
                    val title: String? = media.nowPlaying ?: media.title
                    val mediaId = MediaSessionBrowser.generateMediaId(media)
                    val iconUri = when {
                        isSchemeHttpOrHttps(media.artworkMrl) -> {
                            //ArtworkProvider will cache remote images
                            ArtworkProvider.buildUri(ctx, Uri.Builder()
                                    .appendPath(ArtworkProvider.REMOTE)
                                    .appendQueryParameter(ArtworkProvider.PATH, media.artworkMrl)
                                    .build())
                        }
                        ThumbnailsProvider.isMediaVideo(media) -> ArtworkProvider.buildMediaUri(ctx, media)
                        else -> artworkMap[mediaId] ?: ctx.resources.getResourceUri(R.drawable.ic_auto_nothumb)
                    }
                    val mediaDesc = MediaDescriptionCompat.Builder()
                            .setTitle(title)
                            .setSubtitle(MediaUtils.getMediaArtist(ctx, media))
                            .setDescription(MediaUtils.getMediaAlbum(ctx, media))
                            .setIconUri(iconUri)
                            .setMediaUri(media.uri)
                            .setMediaId(mediaId)
                            .build()
                    it.add(MediaSessionCompat.QueueItem(mediaDesc, (fromIndex + position).toLong()))
                }
            }
        }
        mediaSession.setQueue(queue)
    }

    fun displayPlaybackError(@StringRes resId: Int) {
        if (!this@PlaybackService::mediaSession.isInitialized) initMediaSession()
        val ctx = this@PlaybackService
        if (isPlaying) {
            stop()
        }
        val playbackState = PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_ERROR, 0, 0f)
                .setErrorMessage(PlaybackStateCompat.ERROR_CODE_NOT_SUPPORTED, ctx.getString(resId))
                .build()
        mediaSession.setPlaybackState(playbackState)
    }

    fun displayPlaybackMessage(@StringRes resId: Int, vararg formatArgs: String) {
        val ctx = this@PlaybackService
        subtitleMessage.push(ctx.getString(resId, *formatArgs))
        updateMetadata()
    }

    @MainThread
    fun load(media: MediaWrapper) = load(listOf(media), 0)

    /**
     * Play a media from the media list (playlist)
     *
     * @param index The index of the media
     * @param flags LibVLC.MEDIA_* flags
     */
    @JvmOverloads
    fun playIndex(index: Int, flags: Int = 0) {
        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) { playlistManager.playIndex(index, flags) }
    }

    fun playIndexOrLoadLastPlaylist(index: Int) {
        if (hasMedia()) playIndex(index)
        else {
            settings.edit {
                putLong(POSITION_IN_SONG, 0L)
                putInt(POSITION_IN_AUDIO_LIST, index)
            }
            loadLastPlaylist(PLAYLIST_TYPE_AUDIO)
        }
    }

    @MainThread
    fun flush() {
        /* HACK: flush when activating a video track. This will force an
         * I-Frame to be displayed right away. */
        if (isSeekable) {
            val time = getTime()
            if (time > 0)
                seek(time)
        }
    }

    /**
     * Use this function to show an URI in the audio interface WITHOUT
     * interrupting the stream.
     *
     * Mainly used by VideoPlayerActivity in response to loss of video track.
     */

    @MainThread
    fun showWithoutParse(index: Int, forPopup: Boolean = false) {
        playlistManager.setVideoTrackEnabled(false)
        val media = playlistManager.getMedia(index) ?: return
        // Show an URI without interrupting/losing the current stream
        if (BuildConfig.DEBUG) Log.v(TAG, "Showing index " + index + " with playing URI " + media.uri)
        playlistManager.currentIndex = index
        notifyTrackChanged()
        PlaylistManager.showAudioPlayer.value = !isVideoPlaying && !forPopup
        showNotification()
    }

    fun setVideoTrackEnabled(enabled: Boolean) = playlistManager.setVideoTrackEnabled(enabled)

    fun switchToVideo() = playlistManager.switchToVideo()

    @MainThread
    fun switchToPopup(index: Int) {
        playlistManager.saveMediaMeta()
        showWithoutParse(index, true)
        showPopup()
    }

    @MainThread
    fun removePopup() {
        popupManager?.removePopup()
        popupManager = null
    }

    @MainThread
    private fun showPopup() {
        if (popupManager == null) popupManager = PopupManager(this)
        popupManager!!.showPopup()
        hideNotification(true)
    }

    /**
     * Append to the current existing playlist
     */

    @MainThread
    fun append(mediaList: Array<MediaWrapper>, index: Int = 0) = append(mediaList.toList(), index)

    @MainThread
    fun append(mediaList: List<MediaWrapper>, index: Int = 0) = lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
        playlistManager.append(mediaList, index)
        onMediaListChanged()
    }

    @MainThread
    fun append(media: MediaWrapper) = append(listOf(media))

    /**
     * Insert into the current existing playlist
     */

    @MainThread
    fun insertNext(mediaList: Array<MediaWrapper>) = insertNext(mediaList.toList())

    @MainThread
    private fun insertNext(mediaList: List<MediaWrapper>) {
        playlistManager.insertNext(mediaList)
        onMediaListChanged()
    }

    @MainThread
    fun insertNext(media: MediaWrapper) = insertNext(listOf(media))

    /**
     * Move an item inside the playlist.
     */
    @MainThread
    fun moveItem(positionStart: Int, positionEnd: Int) = playlistManager.moveItem(positionStart, positionEnd)

    @MainThread
    fun insertItem(position: Int, mw: MediaWrapper) = playlistManager.insertItem(position, mw)

    @MainThread
    fun remove(position: Int) = playlistManager.remove(position)

    @MainThread
    fun removeLocation(location: String) = playlistManager.removeLocation(location)

    @MainThread
    operator fun hasNext() = playlistManager.hasNext()

    @MainThread
    fun hasPrevious() = playlistManager.hasPrevious()

    @MainThread
    fun detectHeadset(enable: Boolean) {
        detectHeadset = enable
    }

    @MainThread
    fun setRate(rate: Float, save: Boolean) {
        playlistManager.player.setRate(rate, save)
        publishState()
    }

    @MainThread
    fun increaseRate() {
        if (rate < 4) setRate(rate + 0.2F, true)
    }

    @MainThread
    fun decreaseRate() {
        if (rate > 0.4) setRate(rate - 0.2F, true)
    }

    @MainThread
    fun resetRate() {
        setRate(1F, true)
    }

    @MainThread
    fun navigate(where: Int) = playlistManager.player.navigate(where)

    @MainThread
    fun getChapters(title: Int) = playlistManager.player.getChapters(title)

    @MainThread
    fun setVolume(volume: Int) = playlistManager.player.setVolume(volume)

    @MainThread
    @JvmOverloads
    fun seek(time: Long, length: Double = this.length.toDouble(), fromUser: Boolean = false, fast: Boolean = false) {
        if (length > 0.0) this.setTime(time, fast) else setPosition((time.toFloat() / NO_LENGTH_PROGRESS_MAX.toFloat()))
        if (fromUser) {
            publishState(time)
        }
    }

    @MainThread
    fun updateViewpoint(yaw: Float, pitch: Float, roll: Float, fov: Float, absolute: Boolean): Boolean {
        return playlistManager.player.updateViewpoint(yaw, pitch, roll, fov, absolute)
    }

    @MainThread
    fun saveStartTime(time: Long) {
        playlistManager.savedTime = time
    }

    @MainThread
    private fun setPosition(pos: Float) = playlistManager.player.setPosition(pos)

    @MainThread
    fun setAudioTrack(index: String) = playlistManager.player.setAudioTrack(index)

    @MainThread
    fun unselectTrackType(trackType: VideoTracksDialog.TrackType) {
        playlistManager.player.unselectTrackType(trackType)
    }

    @MainThread
    fun setAudioDigitalOutputEnabled(enabled: Boolean) = playlistManager.player.setAudioDigitalOutputEnabled(enabled)

    @MainThread
    fun setVideoTrack(index: String) = playlistManager.player.setVideoTrack(index)

    @MainThread
    fun addSubtitleTrack(path: String, select: Boolean) = playlistManager.player.addSubtitleTrack(path, select)

    @MainThread
    fun addSubtitleTrack(uri: Uri, select: Boolean) = playlistManager.player.addSubtitleTrack(uri, select)

    @MainThread
    fun setSpuTrack(index: String) = playlistManager.setSpuTrack(index)

    @MainThread
    fun setAudioDelay(delay: Long) = playlistManager.setAudioDelay(delay)

    @MainThread
    fun setSpuDelay(delay: Long) = playlistManager.setSpuDelay(delay)

    @MainThread
    fun hasRenderer() = playlistManager.player.hasRenderer

    @MainThread
    fun setRenderer(item: RendererItem?) {
        val wasOnRenderer = hasRenderer()
        if (wasOnRenderer && !hasRenderer() && canSwitchToVideo())
            VideoPlayerActivity.startOpened(applicationContext,
                    playlistManager.getCurrentMedia()!!.uri, playlistManager.currentIndex)
        playlistManager.setRenderer(item)
        if (!wasOnRenderer && item != null) audioFocusHelper.changeAudioFocus(false)
        else if (wasOnRenderer && item == null && isPlaying) audioFocusHelper.changeAudioFocus(true)
    }

    @MainThread
    fun setEqualizer(equalizer: MediaPlayer.Equalizer?) = playlistManager.player.setEqualizer(equalizer)

    @MainThread
    fun setVideoScale(scale: Float) = playlistManager.player.setVideoScale(scale)

    @MainThread
    fun setVideoAspectRatio(aspect: String?) = playlistManager.player.setVideoAspectRatio(aspect)

    override fun getLifecycle(): Lifecycle = dispatcher.lifecycle

    /*
     * Browsing
     */

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        AccessControl.logCaller(clientUid, clientPackageName)
        if (!Permissions.canReadStorage(this@PlaybackService)) {
            Log.w(TAG, "Returning null MediaBrowserService root. READ_EXTERNAL_STORAGE permission not granted.")
            return null
        }
        return when {
            rootHints?.containsKey(BrowserRoot.EXTRA_SUGGESTED) == true -> BrowserRoot(MediaSessionBrowser.ID_SUGGESTED, null)
            else -> {
                val rootId = when (clientPackageName) {
                    "com.google.android.googlequicksearchbox" -> MediaSessionBrowser.ID_ROOT_NO_TABS
                    else -> MediaSessionBrowser.ID_ROOT
                }
                val extras = MediaSessionBrowser.getContentStyle().apply {
                    putBoolean(EXTRA_MEDIA_SEARCH_SUPPORTED, true)
                }
                BrowserRoot(rootId, extras)
            }
        }
    }

    override fun onLoadChildren(parentId: String, result: Result<List<MediaBrowserCompat.MediaItem>>) {
        result.detach()
        val reload = parentId == MediaSessionBrowser.ID_LAST_ADDED && parentId != lastParentId
        lastParentId = parentId
        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            awaitMedialibraryStarted()
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    result.sendResult(MediaSessionBrowser.browse(applicationContext, parentId, isShuffling))
                    if (reload && !medialibrary.isWorking) applicationContext.reloadLibrary()
                } catch (e: RuntimeException) {
                    Log.e(TAG, "Failed to load children for $parentId", e)
                }
            }
        }
    }

    override fun onSearch(query: String, extras: Bundle?, result: Result<List<MediaBrowserCompat.MediaItem>>) {
        result.detach()
        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            awaitMedialibraryStarted()
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    result.sendResult(MediaSessionBrowser.search(applicationContext, query))
                } catch (e: RuntimeException) {
                    Log.e(TAG, "Failed to search for $query", e)
                }
            }
        }
    }

    /**
     * Start the loop that checks for the sleep timer consumption
     */
    private fun startSleepTimerJob() {
        stopSleepTimerJob()
        sleepTimerJob = launch {
            while (isActive) {
                playerSleepTime.value?.let {
                    val timerExpired = System.currentTimeMillis() > it.timeInMillis
                    val shouldStop = if (waitForMediaEnd) timerExpired && mediaEndReached else timerExpired
                    if (shouldStop) {
                        withContext(Dispatchers.Main) { if (isPlaying) stop() else setSleepTimer(null) }
                    }
                }
                if (mediaEndReached) mediaEndReached = false
                delay(1000)
            }
        }
    }

    private fun stopSleepTimerJob() {
        if (BuildConfig.DEBUG) Log.d("SleepTimer", "stopSleepTimerJob")
        sleepTimerJob?.cancel()
        sleepTimerJob = null
    }

    /**
     * Change the sleep timer time
     * @param time a [Calendar] object for the new sleep timer time. Set to null to cancel the sleep timer
     */
    fun setSleepTimer(time: Calendar?) {
        if (time != null && time.timeInMillis < System.currentTimeMillis()) return
        playerSleepTime.value = time
        if (time == null) stopSleepTimerJob() else startSleepTimerJob()
    }

    companion object {
        val serviceFlow = MutableStateFlow<PlaybackService?>(null)
        val instance: PlaybackService?
            get() = serviceFlow.value

        val renderer = RendererLiveData()
        val restartPlayer = LiveEvent<Boolean>()
        val headSetDetection = LiveEvent<Boolean>()
        val equalizer = LiveEvent<MediaPlayer.Equalizer>()

        private const val SHOW_TOAST = 1
        private const val END_MEDIASESSION = 2

        val playerSleepTime by lazy(LazyThreadSafetyMode.NONE) { MutableLiveData<Calendar?>().apply { value = null } }

        fun start(context: Context) {
            if (instance != null) return
            val serviceIntent = Intent(context, PlaybackService::class.java)
            context.launchForeground(serviceIntent)
        }

        fun loadLastAudio(context: Context) {
            val i = Intent(ACTION_REMOTE_LAST_PLAYLIST, null, context, PlaybackService::class.java)
            context.launchForeground(i)
        }

        fun hasRenderer() = renderer.value != null

        private const val PLAYBACK_BASE_ACTIONS = (PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
                or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or PlaybackStateCompat.ACTION_PLAY_FROM_URI
                or PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM)
    }

    fun getTime(realTime: Long): Int {
        playlistManager.abRepeat.value?.let {
            if (it.start != -1L && it.stop != -1L) return when {
                playlistManager.abRepeatOn.value!! -> {
                    val start = it.start
                    val end = it.stop
                    when {
                        start != -1L && realTime < start -> {
                            start.toInt()
                        }
                        end != -1L && realTime > it.stop -> {
                            end.toInt()
                        }
                        else -> realTime.toInt()
                    }
                }
                else -> realTime.toInt()
            }
        }

        return if (length == 0L) position.toInt() else realTime.toInt()
    }

    fun isCarMode(): Boolean {
        return if (CarConnectionHandler.preferCarConnectionHandler()) {
            carConnectionHandler.connectionType.value?.let { it > CarConnectionHandler.CONNECTION_TYPE_NOT_CONNECTED } ?: false
        } else {
            (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_CAR
        }
    }
}

// Actor actions sealed classes
private sealed class CbAction

private object CbUpdate : CbAction()
private class CbMediaEvent(val event: IMedia.Event) : CbAction()
private class CbMediaPlayerEvent(val event: MediaPlayer.Event) : CbAction()
private class CbAdd(val cb: PlaybackService.Callback) : CbAction()
private class CbRemove(val cb: PlaybackService.Callback) : CbAction()
private object ShowNotification : CbAction()
private class HideNotification(val remove: Boolean) : CbAction()
private object UpdateMeta : CbAction()

fun PlaybackService.manageAbRepeatStep(abRepeatReset: View, abRepeatStop: View, abRepeatContainer: View, abRepeatAddMarker: TextView) {
    when {
        playlistManager.abRepeatOn.value != true -> {
            abRepeatReset.visibility = View.GONE
            abRepeatStop.visibility = View.GONE
            abRepeatContainer.visibility = View.GONE
        }
        playlistManager.abRepeat.value?.start != -1L && playlistManager.abRepeat.value?.stop != -1L -> {
            abRepeatReset.visibility = View.VISIBLE
            abRepeatStop.visibility = View.VISIBLE
            abRepeatContainer.visibility = View.GONE
        }
        playlistManager.abRepeat.value?.start == -1L && playlistManager.abRepeat.value?.stop == -1L -> {
            abRepeatContainer.visibility = View.VISIBLE
            abRepeatAddMarker.text = getString(R.string.abrepeat_add_first_marker)
            abRepeatReset.visibility = View.GONE
            abRepeatStop.visibility = View.GONE
        }
        playlistManager.abRepeat.value?.start == -1L || playlistManager.abRepeat.value?.stop == -1L -> {
            abRepeatAddMarker.text = getString(R.string.abrepeat_add_second_marker)
            abRepeatContainer.visibility = View.VISIBLE
            abRepeatReset.visibility = View.GONE
            abRepeatStop.visibility = View.GONE
        }
    }
}

