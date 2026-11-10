package com.iptv.tvplayer

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.imageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 飞雪TV 主界面：深色海报墙 + 全屏播放双模式。
 *
 * 墙面：分组筛选（含收藏 / 最近观看）、卡片内低码率静音预览、长按 OK 收藏。
 * 播放：全屏画面 + 信息浮层，OK 呼出 XMLTV 节目单，BACK 回墙面但声音继续。
 */
@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    companion object {
        private const val MODE_WALL = 0
        private const val MODE_PLAYER = 1

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val OVERLAY_HIDE_DELAY = 5000L
        private const val NUMBER_TIMEOUT = 2500L
        private const val NUMBER_MAX_DIGITS = 4
        private val PROGRESSIVE_EXTENSIONS = listOf(
            ".mp4", ".ts", ".mkv", ".flv", ".mov", ".avi", ".wmv", ".m4v", ".3gp",
            ".webm", ".ogv", ".mpg", ".mpeg", ".m2ts", ".rmvb", ".rm", ".f4v"
        )
        private const val PREVIEW_DELAY = 700L
        private const val TICK_INTERVAL = 60_000L
        private const val EPG_CHECK_INTERVAL = 5 * 60_000L
        private const val REQUEST_EPG_GRID = 1201
    }

    // 墙面
    private lateinit var wallLayer: View
    private lateinit var gridView: RecyclerView
    private lateinit var groupListView: RecyclerView
    private lateinit var countView: TextView
    private lateinit var nowPlayingChip: TextView
    private lateinit var sourceLabelView: TextView
    private lateinit var emptyHint: TextView

    // 播放层
    private lateinit var playerView: PlayerView
    private lateinit var infoPanel: View
    private lateinit var channelNameView: TextView
    private lateinit var channelMetaView: TextView
    private lateinit var statusView: TextView
    private lateinit var hintView: TextView
    private lateinit var loadingView: ProgressBar
    private lateinit var errorView: TextView

    private lateinit var player: ExoPlayer
    private lateinit var httpFactory: DefaultHttpDataSource.Factory
    private lateinit var dataSourceFactory: DefaultDataSource.Factory
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var channelAdapter: ChannelAdapter

    /** 预览播放器按需创建：绝大多数盒子不该存在第二路解码器 */
    private var previewPlayer: ExoPlayer? = null

    /** 全局唯一的预览 surface，聚焦时挂到对应卡片的 previewHost */
    private var previewSurface: PlayerView? = null
    private var previewFailures = 0

    /** 收敛后台网络任务，避免每次刷新都新建 Thread */
    private val ioExecutor: ExecutorService = Executors.newFixedThreadPool(2)

    @Volatile
    private var epgLoading = false
    private var lastEpgAttempt = 0L
    private var lastEpgErrorShown: String? = null
    private lateinit var groupAdapter: GroupAdapter
    private lateinit var repository: PlaylistRepository
    private lateinit var tokens: TokenManager
    private lateinit var epg: EpgRepository
    private lateinit var prefs: Prefs
    private lateinit var settingsButton: View
    private lateinit var guideButton: View

    private val handler = Handler(Looper.getMainLooper())

    private var allChannels: List<Channel> = emptyList()
    private var currentChannel: Channel? = null
    private var activeGroup: String? = null

    private var lastLogoMode = -1
    private var currentLineIndex = 0
    private var lineSwitches = 0
    private var linePickerOpen = false
    private var timeshiftStart: Long? = null
    private var lastReminderKey: String? = null
    private var reservationTarget: String? = null
    private var playbackFailures = 0
    private var externalFallbackTried = false

    /** 因「音轨设备解不了」而切换线路的次数（与播放失败计数分开，避免来回死切） */
    private var audioSwitches = 0
    private var audioUnsupportedNotified = false
    private var audioSwitchPending = false

    /** 每个频道累计的播放失败次数，达到阈值（且开启过滤）后自动隐藏该频道 */
    private val channelFailures = HashMap<String, Int>()

    /** 已自动隐藏（播放失败）的频道稳定标识快照，用于 applyFilter 过滤，避免每次重建集合 */
    private var hiddenSnapshot: Set<String> = emptySet()

    private var mode = MODE_WALL
    private var numberBuffer = ""
    /** 数字选台输入浮层：正在输入的台号、匹配到的频道 */
    private lateinit var numberInputCard: View
    private lateinit var numberInputDigitsView: TextView
    private lateinit var numberInputMatchView: TextView
    /** 同一轮播放错误只排一次自动切线路，避免多个 postDelayed 叠加反复起播 */
    private var switchLinePending = false
    /** 上一次的「内置 / 外部」引擎状态（-1 未初始化、0 内置、1 外部），用于切换时即时重播 */
    private var lastEngineMode = -1
    private var loadingSource = false
    private var userPaused = false
    private var pendingReload = false
    private var fastBufferMode = false
    private var lastSettingsVersion = 0L
    private var lastCoverMode = -1
    private var currentResolution: String? = null
    private var lastTokenRefresh = 0L

    // 卡片预览
    private var activePreviewHost: FrameLayout? = null
    private var activePreviewUrl: String? = null
    private var previewRunnable: Runnable? = null
    /** 正在排队等待启动的预览宿主：鼠标 HOVER_MOVE 每帧回调都不能重新计时 */
    private var pendingPreviewHost: FrameLayout? = null
    /** 鼠标/飞鼠实际悬停的卡片；不依赖 ROM 的 View.isHovered（部分盒子实现不可靠） */
    private var hoveredHost: FrameLayout? = null
    /** 预览失败的线路：只跳过坏源，不因几个死链就把全局预览关掉 */
    private val previewFailedUrls = HashSet<String>()
    /** 当前预览的频道，失败时用来找备用线路继续出画面 */
    private var previewChannel: Channel? = null
    private var previewRetryPending = false

    // 右上角换台信息卡
    private lateinit var channelInfoCard: View
    private lateinit var infoNumberView: TextView
    private lateinit var infoNameView: TextView
    private lateinit var infoGroupView: TextView
    private lateinit var infoProgramView: TextView
    private lateinit var infoNextView: TextView
    private lateinit var infoExtraView: TextView
    private lateinit var infoTimeView: TextView
    private lateinit var reservationCard: TextView

    // 底部频道信息栏（半透明）：台标 / 线路 / 节目 / 进度 / 一排可直接切换当前画面的按钮
    private lateinit var bottomInfoBar: View
    private lateinit var bottomLogo: ImageView
    private lateinit var bottomLogoLetter: TextView
    private lateinit var bottomName: TextView
    private lateinit var bottomMeta: TextView
    private lateinit var bottomTime: TextView
    private lateinit var bottomRange: TextView
    private lateinit var bottomProgram: TextView
    private lateinit var bottomProgress: ProgressBar
    private lateinit var bottomProgressText: TextView
    private lateinit var bottomNext: TextView
    private lateinit var btnGuide: TextView
    private lateinit var btnLine: TextView
    private lateinit var btnQuality: TextView
    private lateinit var btnControl: TextView
    private lateinit var btnEngine: TextView
    private lateinit var btnSettings: TextView
    private lateinit var btnClear: TextView

    // 左侧节目单
    private lateinit var epgPanel: View
    private lateinit var epgListView: RecyclerView
    private lateinit var epgTimeView: TextView
    private lateinit var epgTitleView: TextView
    private lateinit var epgDetailView: TextView
    private lateinit var epgAdapter: EpgAdapter
    private var epgVisible = false
    private var epgIndex = 0

    private val commitNumberRunnable = Runnable { commitNumber() }
    private val hideOverlayRunnable = Runnable { hidePlayerOverlay() }
    private val hideInfoCardRunnable = Runnable {
        channelInfoCard.visibility = View.GONE
        bottomInfoBar.visibility = View.GONE
    }
    private val hideReservationRunnable = Runnable {
        reservationCard.visibility = View.GONE
        reservationTarget = null
    }

    /** 前台每分钟检查一次：按全局周期强制刷新 Token，并刷新 EPG 面板时间 */
    private val tokenTick = object : Runnable {
        override fun run() {
            handler.postDelayed(this, TICK_INTERVAL)
            val minutes = prefs.tokenRefreshMinutes
            if (minutes <= 0 || !repository.hasSources) return
            if (repository.sources.none { it.hasToken }) return
            if (System.currentTimeMillis() - lastTokenRefresh < minutes * 60_000L) return
            lastTokenRefresh = System.currentTimeMillis()
            ioExecutor.execute {
                val changed = tokens.forceRefresh(repository.sources, minutes)
                if (changed) runOnUiThread {
                    httpFactory.setDefaultRequestProperties(repository.allHeaders())
                    reloadPlaylist()
                }
            }
        }
    }

    private val epgTick = object : Runnable {
        override fun run() {
            handler.postDelayed(this, TICK_INTERVAL)
            checkReservations()
            if (epgVisible) {
                epgTimeView.text = "当前时间  " +
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                epgAdapter.refreshGuide()
                updateEpgDetail()
            }
            val now = System.currentTimeMillis()
            if (prefs.epgUrl.isNotBlank() && now - lastEpgAttempt >= EPG_CHECK_INTERVAL) {
                lastEpgAttempt = now
                loadEpgInBackground()
            }
        }
    }

    // ------------------------------------------------------------ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        bindViews()
        repository = PlaylistRepository(this)
        tokens = repository.tokens
        prefs = Prefs(this)
        epg = EpgRepository(this)
        lastSettingsVersion = prefs.version
        settingsButton.setOnClickListener { openSettings() }

        channelAdapter = ChannelAdapter(
            onSelect = { channel -> playChannel(channel) },
            prefs = prefs,
            onFocus = ::onCardFocus,
            onToggleFavorite = ::toggleFavorite,
            onRecycled = ::onHostRecycled
        )
        gridView.layoutManager = GridLayoutManager(this, calculateSpanCount())
        gridView.adapter = channelAdapter
        gridView.itemAnimator = null
        gridView.setHasFixedSize(true)
        gridView.setItemViewCacheSize(4)
        gridView.recycledViewPool.setMaxRecycledViews(0, 24)

        groupAdapter = GroupAdapter { group -> selectGroup(group) }
        groupListView.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        groupListView.adapter = groupAdapter
        groupListView.itemAnimator = null

        epgAdapter = EpgAdapter(
            onPick = ::pickEpgChannel,
            guide = { channel -> epg.guideLine(channel) },
            isFavorite = { channel -> prefs.isFavorite(channel.stableKey()) }
        )
        epgListView.layoutManager = LinearLayoutManager(this)
        epgListView.adapter = epgAdapter
        epgListView.itemAnimator = null

        setupPlayer()
        updateNowPlayingChip()

        handleIntent(intent)
        loadInitial()
        loadEpgInBackground()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        applyPlayerSettings()
        stopPreview()
        updateMeta()
        updateInfoProgram()
        if (prefs.version != lastSettingsVersion) {
            lastSettingsVersion = prefs.version
            if (repository.hasSources) reloadPlaylist()
            loadEpgInBackground()
            buildGroups()
            if (Library.isVirtual(activeGroup)) applyFilter(focusFirst = false)
        }
        // 台标 / 封面风格是静默写入（不触发重新拉源），返回时按变化重绘墙面
        channelAdapter.setCurrent(currentChannel?.url)
        if (prefs.logoMode != lastLogoMode || prefs.coverMode != lastCoverMode) {
            lastLogoMode = prefs.logoMode
            lastCoverMode = prefs.coverMode
            channelAdapter.notifyDataSetChanged()
        }
        handler.removeCallbacks(tokenTick)
        handler.removeCallbacks(epgTick)
        handler.post(tokenTick)
        handler.postDelayed(epgTick, TICK_INTERVAL)
        if (!previewAllowed()) releasePreviewPlayer()
    }

    override fun onStart() {
        super.onStart()
        // 墙面模式绝不自动恢复播放：盒子同时解两路视频会直接拖垮 UI
        if (mode == MODE_PLAYER && currentChannel != null && !userPaused) player.play()
    }

    override fun onStop() {
        super.onStop()
        player.pause()
        previewPlayer?.pause()
    }

    override fun onPause() {
        handler.removeCallbacks(tokenTick)
        handler.removeCallbacks(epgTick)
        // 切后台时清掉未确认的台号，避免 2.5 秒后在后台偷偷换台
        cancelNumberInput()
        stopPreview()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopPreview()
        releasePreviewPlayer()
        ioExecutor.shutdownNow()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    private fun bindViews() {
        wallLayer = findViewById(R.id.wallLayer)
        gridView = findViewById(R.id.channelGrid)
        groupListView = findViewById(R.id.groupList)
        countView = findViewById(R.id.countView)
        nowPlayingChip = findViewById(R.id.nowPlayingChip)
        sourceLabelView = findViewById(R.id.sourceLabel)
        emptyHint = findViewById(R.id.emptyHint)

        playerView = findViewById(R.id.playerView)
        infoPanel = findViewById(R.id.infoPanel)
        channelNameView = findViewById(R.id.channelName)
        channelMetaView = findViewById(R.id.channelMeta)
        statusView = findViewById(R.id.playbackStatus)
        hintView = findViewById(R.id.hintBar)
        loadingView = findViewById(R.id.loadingView)
        errorView = findViewById(R.id.errorView)
        settingsButton = findViewById(R.id.settingsButton)
        guideButton = findViewById(R.id.guideButton)
        guideButton.setOnClickListener { openEpgGrid() }

        channelInfoCard = findViewById(R.id.channelInfoCard)
        infoNumberView = findViewById(R.id.infoNumber)
        infoNameView = findViewById(R.id.infoName)
        infoGroupView = findViewById(R.id.infoGroup)
        infoProgramView = findViewById(R.id.infoProgram)
        infoNextView = findViewById(R.id.infoNext)
        infoExtraView = findViewById(R.id.infoExtra)
        infoTimeView = findViewById(R.id.infoTime)
        reservationCard = findViewById(R.id.reservationCard)

        bottomInfoBar = findViewById(R.id.bottomInfoBar)
        bottomLogo = findViewById(R.id.bottomLogo)
        bottomLogoLetter = findViewById(R.id.bottomLogoLetter)
        bottomName = findViewById(R.id.bottomName)
        bottomMeta = findViewById(R.id.bottomMeta)
        bottomTime = findViewById(R.id.bottomTime)
        bottomRange = findViewById(R.id.bottomRange)
        bottomProgram = findViewById(R.id.bottomProgram)
        bottomProgress = findViewById(R.id.bottomProgress)
        bottomProgressText = findViewById(R.id.bottomProgressText)
        bottomNext = findViewById(R.id.bottomNext)
        btnGuide = findViewById(R.id.btnGuide)
        btnLine = findViewById(R.id.btnLine)
        btnQuality = findViewById(R.id.btnQuality)
        btnControl = findViewById(R.id.btnControl)
        btnEngine = findViewById(R.id.btnEngine)
        btnSettings = findViewById(R.id.btnSettings)
        btnClear = findViewById(R.id.btnClear)
        setupBottomActions()

        numberInputCard = findViewById(R.id.numberInputCard)
        numberInputDigitsView = findViewById(R.id.numberInputDigits)
        numberInputMatchView = findViewById(R.id.numberInputMatch)

        epgPanel = findViewById(R.id.epgPanel)
        epgListView = findViewById(R.id.epgList)
        epgTimeView = findViewById(R.id.epgTime)
        epgTitleView = findViewById(R.id.epgTitle)
        epgDetailView = findViewById(R.id.epgDetail)
        // 启动即处于频道墙：先不创建全屏视频 surface，缩短首帧时间
        playerView.visibility = View.GONE
    }

    private fun loadInitial() {
        if (repository.hasSources) {
            reloadPlaylist()
        } else {
            bindChannels(emptyList())
            sourceLabelView.text = "未添加订阅"
            emptyHint.visibility = View.VISIBLE
            emptyHint.text = "还没有播放源　·　按 MENU 或右上角「设置」→ 粘贴一个聚合订阅链接即可"
            handler.postDelayed({
                if (!repository.hasSources && !isFinishing) openSettings()
            }, 1200)
        }
    }

    /** 按屏幕宽度决定海报墙列数：1080p 电视一般是 5 列 */
    private fun calculateSpanCount(): Int {
        val metrics = resources.displayMetrics
        val widthDp = metrics.widthPixels / metrics.density
        return (widthDp / 190f).toInt().coerceIn(3, 8)
    }

    // ------------------------------------------------------------ 播放器

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    if (mode == MODE_PLAYER) loadingView.visibility = View.VISIBLE
                }
                Player.STATE_READY -> {
                    loadingView.visibility = View.GONE
                    errorView.visibility = View.GONE
                    // 这条线路能播，记下来，下次起播直接用它
                    lineSwitches = 0
                    playbackFailures = 0
                    externalFallbackTried = false
                    switchLinePending = false
                    currentChannel?.let { prefs.setLineIndex(it.stableKey(), currentLineIndex) }
                }
                Player.STATE_ENDED -> {
                    loadingView.visibility = View.GONE
                    if (allChannels.size > 1) playNeighbor(1)
                }
                else -> Unit
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                currentResolution = "${videoSize.width}×${videoSize.height}"
                updateMeta()
            }
        }

        /**
         * 部分源「只有画面没声音」的根因：这些源用的是杜比 AC3/EAC3、AC4、DTS 音轨，
         * 国产盒子普遍没有对应解码授权，ExoPlayer 拿到的音轨 isSupported=false，
         * 于是画面照播、声音全无（也不会报播放错误）。
         * 这里检测到就自动切到该频道的下一条线路（多数聚合源备用线路是 AAC），
         * 都试过还不支持就明确提示，避免用户以为是 App 坏了。
         */
        override fun onTracksChanged(tracks: Tracks) {
            val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            if (audioGroups.isEmpty()) return
            val selected = audioGroups.firstOrNull { it.isSelected } ?: return
            if (selected.isSupported || selected.length == 0) {
                audioUnsupportedNotified = false
                return
            }
            val format = selected.getTrackFormat(0)
            val mime = format.sampleMimeType.orEmpty()
            val channel = currentChannel
            val lines = channel?.allUrls()?.size ?: 0
            Diagnostics.log(
                this@MainActivity, "AUDIO",
                "音轨不受支持 mime=$mime codecs=${format.codecs.orEmpty()} " +
                    "ch=${format.channelCount} | ${channel?.name} | 线路=${currentLineIndex + 1}/$lines"
            )
            if (channel != null && prefs.autoSwitchLine && lines > 1 &&
                audioSwitches < lines - 1 && !audioSwitchPending && !isFinishing
            ) {
                audioSwitches++
                audioSwitchPending = true
                nowPlayingChip.text = "● ${channel.name} · 音轨设备不支持，切换线路找有声源…"
                nowPlayingChip.visibility = View.VISIBLE
                handler.postDelayed({
                    audioSwitchPending = false
                    if (!isFinishing) switchLine(1, silent = true)
                }, 900)
                return
            }
            if (!audioUnsupportedNotified) {
                audioUnsupportedNotified = true
                toast("该源音轨为${audioLabel(mime)}，本机无法解码 → 只有画面没有声音")
            }
        }

        private fun audioLabel(mime: String): String = when {
            mime.contains("eac3", true) || mime.contains("ec-3", true) -> "杜比 E-AC3"
            mime.contains("ac3", true) || mime.contains("ac-3", true) -> "杜比 AC3"
            mime.contains("ac4", true) -> "杜比 AC4"
            mime.contains("truehd", true) -> "杜比 TrueHD"
            mime.contains("dts", true) -> "DTS"
            mime.isBlank() -> "未知格式"
            else -> mime
        }

        override fun onPlayerError(error: PlaybackException) {
            val channel = currentChannel
            val lines = channel?.allUrls()?.size ?: 0

            // 直播流的播放窗口会随真实时间向前推进；一旦播放位置落到窗口之外，
            // ExoPlayer 会抛 BehindLiveWindowException。这是直播常态，回到直播边缘即可续播，
            // 绝不能当「播放失败」处理，否则直播频道会频繁黑屏 / 报错误提示。
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                Diagnostics.log(this@MainActivity, "LIVE", "直播窗口后移，自动回到直播边缘继续")
                loadingView.visibility = View.VISIBLE
                errorView.visibility = View.GONE
                player.seekToDefaultPosition()
                player.playWhenReady = true
                return
            }

            // 多线路自动切换：一次失败就试下一条，直到所有线路都试过
            if (channel != null && prefs.autoSwitchLine && lines > 1 &&
                lineSwitches < lines - 1 && !isFinishing && !switchLinePending
            ) {
                switchLinePending = true
                lineSwitches++
                nowPlayingChip.text = "● ${channel.name} · 线路失败，切换备用源…"
                nowPlayingChip.visibility = View.VISIBLE
                errorView.visibility = View.GONE
                if (mode == MODE_PLAYER) loadingView.visibility = View.VISIBLE
                handler.postDelayed({
                    switchLinePending = false
                    if (!isFinishing) switchLine(1, silent = true)
                }, 400)
                return
            }

            loadingView.visibility = View.GONE
            val name = channel?.name.orEmpty()
            val failedUrl = channel?.allUrls()?.getOrNull(currentLineIndex).orEmpty()
            Diagnostics.log(
                this@MainActivity, "ERROR",
                "${error.errorCodeName} | $name | $failedUrl | ${error.message.orEmpty()}"
            )

            // 老盒子硬解码连续失败：自动换盒子上已装的外部播放器再解一次
            playbackFailures++
            if (
                prefs.externalFallback &&
                DeviceProfile.info(this@MainActivity).tier != DeviceProfile.Tier.MODERN &&
                playbackFailures >= 2 && !externalFallbackTried &&
                channel != null && channel.url.isNotBlank()
            ) {
                externalFallbackTried = true
                Diagnostics.log(this@MainActivity, "DECODER", "内置解码连续失败，自动尝试外部播放器")
                if (launchExternalPlayer(channel)) return
            }

            nowPlayingChip.text = "● 播放失败 · $name"
            nowPlayingChip.visibility = View.VISIBLE

            // 真正播放失败（所有线路都试过）：累计次数，达到阈值且开启过滤则自动隐藏该频道
            channel?.let { registerPlaybackFailure(it) }

            if (mode == MODE_PLAYER) {
                errorView.visibility = View.VISIBLE
                errorView.text = buildString {
                    append("播放失败  ")
                    append(error.errorCodeName)
                    append("\n\n")
                    append(name)
                    append("\n")
                    append(channel?.allUrls()?.getOrNull(currentLineIndex).orEmpty())
                    if (lines > 1) append("\n\n按 ← → 快速切线路，长按 OK 选择换源（共 $lines 条）")
                    else append("\n\n确认源是否可用，或在设置里检查接口地址")
                    append("\n\n订阅里若含组播 TS / 需登录的线路，内置播放器无法解析，请换线路或开启外部播放器回退")
                    if (timeshiftStart != null) append("\n回看失败多为源不支持时移，按快退键回到直播")
                    append("\n\n若全部频道都失败：设置 → 播放诊断 → 生成日志文件，可查 TLS / 解码 / 网络原因")
                    append("\n\n按 BACK 返回频道墙换台，MENU 打开设置")
                }
                showPlayerOverlay()
            }
        }
    }

    /**
     * 频道累计播放失败次数；开启「自动隐藏播放失败的频道」且失败 ≥ 3 次时，
     * 把该频道从墙面与筛选里移除（持久化），避免一打开就是满屏不能放的测试源 / 死链。
     */
    private fun registerPlaybackFailure(channel: Channel) {
        if (!prefs.hideFailedChannels) return
        val key = channel.stableKey()
        val count = (channelFailures[key] ?: 0) + 1
        channelFailures[key] = count
        if (count >= 3 && prefs.addHiddenChannel(key)) {
            hiddenSnapshot = prefs.hiddenChannels
            toast("已自动隐藏播放失败的频道：${channel.name}")
            buildGroups()
            if (Library.isVirtual(activeGroup)) applyFilter(focusFirst = false)
            else applyFilter(focusFirst = false)
        }
    }

    private fun setupPlayer() {
        if (!::httpFactory.isInitialized) {
            httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(10000)
                .setAllowCrossProtocolRedirects(true)
        }
        // 请求头（含实时 Token）每次都重新注入，预览播放器共用同一个 factory
        httpFactory.setDefaultRequestProperties(repository.allHeaders())
        dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)

        fastBufferMode = prefs.isFastEngine
        trackSelector = DefaultTrackSelector(this)
        // 老盒子硬解常缺 H.265 / 部分 High profile：允许软解兜底，「能播」优先于「极致流畅」。
        // 1.2 关掉软解回退后，部分盒子一个频道都起不来，这里恢复。
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true)
        Diagnostics.log(
            this, "PLAYER",
            Diagnostics.device().replace("\n", " | ") +
                " | 解码回退=开 | 画质=${prefs.quality} | 引擎=${prefs.engine}"
        )
        player = ExoPlayer.Builder(this, renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(buildLoadControl(fastBufferMode))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setHandleAudioBecomingNoisy(true)
            .build()

        playerView.player = player
        // 主播放器显式持有音频焦点（预览小窗不抢），保证切台/回前台后声音一定恢复
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus= */ true
        )
        player.addListener(playerListener)
        applyTrackParameters()
    }

    /**
     * 缓冲按盒子能力收敛：起播只需 0.4~0.8 秒，不再预取 50 秒内容。
     * 老盒子封顶更小的最大缓冲，避免内存抖动；起播阈值下调让首帧更快出现。
     */
    private fun buildLoadControl(fast: Boolean): DefaultLoadControl {
        val legacy = DeviceProfile.info(this).tier != DeviceProfile.Tier.MODERN
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (fast) 6000 else 12000,
                if (fast) (if (legacy) 16000 else 24000) else (if (legacy) 24000 else 32000),
                if (fast) 400 else 800,
                if (fast) 800 else 1600
            )
            // Media3 1.4.1 的 Builder 不再暴露 backBuffer 接口，缓冲量统一由上面四个参数控制
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    /**
     * 判断一个地址是不是 HLS 直播流。
     *
     * 不只看 .m3u8 后缀——大量聚合源的 HLS 入口没有扩展名（如 /play/123、/live?token=、
     * /index.m3u?...），还有 .m3u 形式。凡是「没有明确渐进式媒体容器扩展名」的，都按 HLS 处理。
     * 否则老盒子（useChunkless=false）会把 m3u8 当渐进流走 setMediaItem，导致全部频道起播失败。
     */
    private fun isHlsUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains(".m3u8") || lower.contains(".m3u?") || lower.contains("format=m3u8") ||
            lower.contains("format=hls")
        ) {
            return true
        }
        // 命中这些明确的渐进式容器扩展名才当普通文件，否则默认按 HLS 处理。
        // 列表是 companion 常量：换台热路径不再每次新建 ArrayList。
        return !PROGRESSIVE_EXTENSIONS.any { lower.contains(it) }
    }

    /**
     * HLS 一律走专用 factory 起播，与设备新旧无关——old 盒子只是关掉 chunkless preparation，
     * 不会再退化成「把 m3u8 当渐进流」导致全部黑屏。其它协议交回默认 factory 嗅探。
     */
    private fun preparePlayback(mediaItem: MediaItem, url: String) {
        val chunkless = DeviceProfile.useChunkless(this)
        if (isHlsUrl(url)) {
            val source = HlsMediaSource.Factory(dataSourceFactory)
                .setAllowChunklessPreparation(chunkless)
                .setTimestampAdjusterInitializationTimeoutMs(1200)
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(2))
                .createMediaSource(mediaItem)
            player.setMediaSource(source)
            Diagnostics.log(this, "PLAY", "起播(HLS) ${url.take(140)} · chunkless=$chunkless")
        } else {
            player.setMediaItem(mediaItem)
            Diagnostics.log(this, "PLAY", "起播(渐进) ${url.take(140)} · chunkless=$chunkless")
        }
        player.prepare()
        // 墙面模式（后台续播关闭时）不自动出声出画，避免又占一路解码器
        player.playWhenReady = mode == MODE_PLAYER && !userPaused
    }

    /** 画质设置：限制视频轨道上限，或强制选 HLS 最高码率 */
    private fun applyTrackParameters() {
        if (!::trackSelector.isInitialized) return
        val builder = trackSelector.buildUponParameters()
            .setForceHighestSupportedBitrate(prefs.quality == Prefs.QUALITY_HIGHEST)
            .setAllowVideoMixedMimeTypeAdaptiveness(true)
        when (prefs.quality) {
            // 自动档：新盒子不设上限，让 HLS 自适应挑到最高可用清晰度；
            // 老盒子硬解 4K 极易卡死，按设备能力封顶 1080p 保流畅。
            Prefs.QUALITY_AUTO -> {
                if (DeviceProfile.info(this).tier != DeviceProfile.Tier.MODERN) {
                    builder.setMaxVideoSize(1920, 1080)
                }
            }
            Prefs.QUALITY_HIGHEST -> {
                if (DeviceProfile.info(this).tier != DeviceProfile.Tier.MODERN) {
                    builder.setMaxVideoSize(1920, 1080)
                }
            }
            Prefs.QUALITY_1080 -> builder.setMaxVideoSize(1920, 1080)
            Prefs.QUALITY_720 -> builder.setMaxVideoSize(1280, 720)
            Prefs.QUALITY_480 -> builder.setMaxVideoSize(854, 480)
            Prefs.QUALITY_SAVER -> {
                builder.setMaxVideoSize(854, 480)
                builder.setMaxVideoBitrate(800_000)
            }
        }
        trackSelector.setParameters(builder)
    }

    /** 从设置页 / 手机后台返回时调用：画质、请求头即时生效，引擎变化即时重播 */
    private fun applyPlayerSettings() {
        if (!::player.isInitialized || !::trackSelector.isInitialized) return
        applyTrackParameters()
        httpFactory.setDefaultRequestProperties(repository.allHeaders())

        val externalNow = if (prefs.isExternalEngine) 1 else 0
        val externalChanged = lastEngineMode != -1 && lastEngineMode != externalNow
        lastEngineMode = externalNow
        val channel = currentChannel

        if (prefs.isFastEngine != fastBufferMode) {
            // 缓冲档位变化必须重建播放器：先解绑监听与 surface，避免旧实例残留回调
            runCatching { player.removeListener(playerListener) }
            playerView.player = null
            player.release()
            setupPlayer()
            channel?.let { playChannel(it, enterPlayer = mode == MODE_PLAYER) }
            return
        }
        // 修复：切到 VLC / MX / Kodi（或切回内置）后要等下次换台才生效的问题
        if (externalChanged && channel != null && mode == MODE_PLAYER) {
            playChannel(channel, enterPlayer = false)
        }
    }

    private fun buildMediaItem(url: String): MediaItem {
        val builder = MediaItem.Builder().setUri(Uri.parse(url))
        if (url.contains(".m3u8", ignoreCase = true)) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        if (prefs.isFastEngine) {
            builder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(3000L)
                    .setMinPlaybackSpeed(1.0f)
                    .setMaxPlaybackSpeed(1.06f)
                    .build()
            )
        }
        return builder.build()
    }

    // ------------------------------------------------------------ 卡片预览小窗

    /** 按需创建唯一的预览播放器与 surface；关闭预览时彻底释放解码器 */
    private fun ensurePreviewPlayer(): ExoPlayer? {
        if (!previewAllowed()) return null
        previewPlayer?.let { return it }

        val selector = DefaultTrackSelector(this)
        selector.setParameters(
            selector.buildUponParameters()
                .setForceLowestBitrate(true)
                .setMaxVideoSize(640, 360)
                .setMaxVideoBitrate(500_000)
                // 预览只出画面：彻底关掉音轨，避免和小窗播放器抢音频输出/音频焦点
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
        )
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(false)
        val p = ExoPlayer.Builder(this, renderers)
            .setTrackSelector(selector)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(2000, 6000, 400, 1000)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(this, httpFactory))
            )
            .build()
        // 预览是静音小窗：既不申请音频焦点、也不打开音频输出，
        // 否则会把主播放器的音频焦点抢走，导致正片画面正常但没声音
        p.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ false)
        p.volume = 0f
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // 只把「这一条线路」记为坏源，并尝试同一频道的备用线路继续出画面。
                // 旧版累计 3 次失败就 releasePreviewPlayer()，试过几个死链后所有频道的
                // 悬停预览都失效 —— 这正是「部分光标对到实时预览失效」的主因之一。
                activePreviewUrl?.let { previewFailedUrls.add(it) }
                val next = previewChannel?.allUrls()
                    ?.firstOrNull { it.isNotBlank() && it !in previewFailedUrls }
                if (next != null && activePreviewHost != null && !previewRetryPending) {
                    previewRetryPending = true
                    handler.post {
                        previewRetryPending = false
                        if (activePreviewHost != null) playPreviewUrl(next)
                    }
                    return
                }
                hideActivePreview()
                previewFailures++
                if (previewFailures >= 6) {
                    previewFailures = 0
                    toast("部分源预览不可用，已跳过这些线路；其它频道预览不受影响")
                }
            }

            /**
             * 首帧真正渲染出来后才把预览小窗显示出来。
             * 之前是聚焦就立刻铺上 PlayerView（此时是黑色 shutter），把卡片的静态封面盖掉了，
             * 表现为「鼠标一移到卡片上，封面就消失」；死链时更是永远黑块。
             */
            override fun onRenderedFirstFrame() {
                if (previewPlayer !== p) return
                previewFailures = 0
                val surface = previewSurface ?: return
                val host = activePreviewHost ?: return
                if (surface.parent !== host) return
                surface.animate().cancel()
                surface.animate().alpha(1f).setDuration(130).start()
                (host.parent as? View)?.findViewById<View>(R.id.previewTag)?.visibility = View.VISIBLE
            }
        })
        previewPlayer = p

        val surface = layoutInflater.inflate(
            R.layout.preview_surface, null, false
        ) as PlayerView
        // 透明遮罩 + 透明起始透明度：加载期间看到的是卡片自己的封面/台标，而不是黑块
        surface.setShutterBackgroundColor(Color.TRANSPARENT)
        surface.useController = false
        surface.alpha = 0f
        previewSurface = surface
        return p
    }

    private fun onCardFocus(channel: Channel?, host: FrameLayout?, fromHover: Boolean) {
        // 自己维护「鼠标停在哪张卡」，不依赖 ROM 的 View.isHovered
        if (fromHover) hoveredHost = host

        if (channel == null || host == null) {
            // 鼠标/飞鼠仍悬停在卡片上时，忽略遥控器焦点抖动导致的「失焦」回调
            if (!fromHover && hoveredHost != null) return
            hoveredHost = null
            cancelPendingPreview()
            stopPreview()
            return
        }

        // host 是 RecyclerView 复用的同一个 View 对象。若已被重新绑定给别的频道，
        // 即使对象相同也必须当成新卡重新排队，否则会命中旧的「已在播/已排队」直接跳过。
        // 配合 onHostRecycled 清状态，解决「滚动后部分卡片悬停预览失效」。
        val rebound = (host.tag as? String)?.let { it != channel.url } ?: false
        if (!rebound && (host === activePreviewHost || host === pendingPreviewHost)) return

        cancelPendingPreview()
        stopPreview()
        host.tag = channel.url
        if (mode != MODE_WALL || !previewAllowed() || channel.url.isBlank()) return

        val runnable = Runnable {
            pendingPreviewHost = null
            startPreview(channel, host)
        }
        previewRunnable = runnable
        pendingPreviewHost = host
        handler.postDelayed(runnable, PREVIEW_DELAY)
    }

    /** 卡片被回收：清掉所有指向这个 host 的状态，避免复用后预览永远起不来 */
    private fun onHostRecycled(host: FrameLayout) {
        if (hoveredHost === host) hoveredHost = null
        if (pendingPreviewHost === host) cancelPendingPreview()
        if (activePreviewHost === host) stopPreview()
    }

    /** 预览只看独立开关；封面模式只管静态封面，两者互不干扰 */
    private fun previewAllowed(): Boolean = prefs.previewEnabled

    private fun cancelPendingPreview() {
        previewRunnable?.let { handler.removeCallbacks(it) }
        previewRunnable = null
        pendingPreviewHost = null
        previewRetryPending = false
    }

    private fun startPreview(channel: Channel, host: FrameLayout) {
        if (mode != MODE_WALL || !previewAllowed() || !host.isAttachedToWindow) return
        if (host === activePreviewHost) return
        val parent = host.parent as? View
        // 遥控器场景看焦点；鼠标/飞鼠看我们显式记录的 hoveredHost。
        val staysOnCard = parent == null || parent.hasFocus() || host === hoveredHost
        if (!staysOnCard) return
        // 后台还在放直播时绝不开第二路解码器
        if (prefs.backgroundPlay && player.isPlaying) return

        val p = ensurePreviewPlayer() ?: return
        val surface = previewSurface ?: return

        // 订阅源里死链很多：跳过已确认预览失败的线路，直接用能出画面的那条
        val url = channel.allUrls()
            .firstOrNull { it.isNotBlank() && it !in previewFailedUrls } ?: return

        detachPreviewSurface()

        surface.alpha = 0f
        surface.player = p
        host.addView(surface)
        host.visibility = View.VISIBLE
        activePreviewHost = host
        activePreviewUrl = url
        previewChannel = channel
        channelAdapter.activePreviewHost = host
        playPreviewUrl(url)
    }

    /** 换一条线路继续预览：不摘 surface，避免反复闪出黑块 */
    private fun playPreviewUrl(url: String) {
        val p = previewPlayer ?: return
        activePreviewUrl = url
        p.stop()
        p.setMediaItem(buildMediaItem(url))
        p.prepare()
        p.playWhenReady = true
    }

    private fun detachPreviewSurface() {
        val surface = previewSurface
        if (surface != null) {
            runCatching { surface.player = null }
            surface.alpha = 0f
            (surface.parent as? FrameLayout)?.let { oldHost ->
                oldHost.removeView(surface)
                oldHost.visibility = View.GONE
                (oldHost.parent as? View)?.findViewById<View>(R.id.previewTag)?.visibility = View.GONE
            }
        }
        val host = activePreviewHost
        if (host != null) {
            host.visibility = View.GONE
            (host.parent as? View)?.findViewById<View>(R.id.previewTag)?.visibility = View.GONE
        }
        activePreviewHost = null
        activePreviewUrl = null
        channelAdapter.activePreviewHost = null
    }

    private fun stopPreview() {
        cancelPendingPreview()
        detachPreviewSurface()
        previewPlayer?.let {
            it.stop()
            it.clearMediaItems()
        }
    }

    private fun hideActivePreview() {
        detachPreviewSurface()
    }

    private fun releasePreviewPlayer() {
        stopPreview()
        previewPlayer?.release()
        previewPlayer = null
        previewSurface = null
        previewFailures = 0
    }

    // ------------------------------------------------------------ 数据

    private fun reloadPlaylist() {
        if (loadingSource) {
            pendingReload = true
            return
        }
        if (!repository.hasSources) {
            openSettings()
            return
        }
        loadingSource = true
        countView.text = "加载中…"
        sourceLabelView.text = repository.label()
        httpFactory.setDefaultRequestProperties(repository.allHeaders())
        emptyHint.visibility = View.VISIBLE
        emptyHint.text = "正在加载播放源 / 接口…"

        ioExecutor.execute {
            val result = runCatching { repository.load() }
            runOnUiThread {
                loadingSource = false
                // load() 里可能刚刷新过 Token，播放请求头要同步更新
                httpFactory.setDefaultRequestProperties(repository.allHeaders())
                result.onSuccess { list ->
                    bindChannels(list)
                }.onFailure { error ->
                    emptyHint.visibility = View.VISIBLE
                    emptyHint.text =
                        "加载播放源失败：${error.message ?: "未知错误"}\n\n按 MENU 打开设置检查地址，或确认盒子已联网"
                    countView.text = "${allChannels.size} 个频道"
                }
                if (pendingReload) {
                    pendingReload = false
                    reloadPlaylist()
                }
            }
        }
    }

    private fun bindChannels(list: List<Channel>) {
        // 订阅源里混杂组播 TS / 需登录接口等不可播线路：把 HLS 线路提到主线路，
        // 让默认第一条就尽量能播（备用线路全部保留，换源菜单仍可手动选）
        allChannels = list.map { it.withPreferredLine() }
        // 每次重新加载播放源都重新评估失败隐藏：清零累计次数并刷新隐藏快照
        channelFailures.clear()
        previewFailedUrls.clear()
        hiddenSnapshot = if (prefs.hideFailedChannels) prefs.hiddenChannels else emptySet()
        buildGroups()
        activeGroup = null
        applyFilter(focusFirst = false)
        updateNowPlayingChip()

        if (list.isNotEmpty()) {
            emptyHint.visibility = View.GONE
        }

        val target = allChannels.getOrNull(repository.lastIndex)
            ?: allChannels.firstOrNull() ?: return
        val position = channelAdapter.positionOf(target.url)
        if (position >= 0) {
            gridView.scrollToPosition(position)
            focusCard(position)
        }
    }

    private fun buildGroups() {
        val groups = LinkedHashSet<String>()
        if (prefs.favoriteKeys.isNotEmpty()) groups.add(Library.GROUP_FAVORITE)
        if (prefs.recentKeys.isNotEmpty()) groups.add(Library.GROUP_RECENT)
        for (channel in allChannels) {
            val group = channel.group
            if (!group.isNullOrBlank()) groups.add(group)
        }
        groupAdapter.submit(groups.toList())
        groupListView.visibility = if (groups.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun selectGroup(group: String?) {
        if (group == activeGroup) return
        activeGroup = group
        applyFilter(focusFirst = false)
        gridView.postDelayed({ focusCard(0) }, 150)
    }

    private fun applyFilter(focusFirst: Boolean) {
        // 开启过滤时，把累计播放失败已被隐藏的频道从列表里剔除
        val hidden = if (prefs.hideFailedChannels) hiddenSnapshot else emptySet()
        val visible = if (hidden.isEmpty()) allChannels
        else allChannels.filter { it.stableKey() !in hidden }
        val filtered = when (activeGroup) {
            null -> visible
            Library.GROUP_FAVORITE -> visible.filter { prefs.isFavorite(it.stableKey()) }
            Library.GROUP_RECENT -> {
                val order = HashMap<String, Int>()
                prefs.recentKeys.forEachIndexed { index, key -> order[key] = index }
                visible.filter { order.containsKey(it.stableKey()) }
                    .sortedBy { order[it.stableKey()] ?: Int.MAX_VALUE }
            }
            else -> visible.filter { it.group == activeGroup }
        }
        channelAdapter.submit(filtered)
        groupAdapter.setSelected(activeGroup)
        countView.text = "${filtered.size} 个频道"
        gridView.scrollToPosition(0)
        if (focusFirst) focusCard(0)
        if (filtered.isEmpty()) {
            emptyHint.visibility = View.VISIBLE
            emptyHint.text = when (activeGroup) {
                Library.GROUP_FAVORITE -> "收藏夹还是空的　·　在频道墙上长按 OK 收藏"
                Library.GROUP_RECENT -> "还没有观看记录　·　播放任意频道后自动生成"
                else -> "这个分组暂时没有频道"
            }
        } else if (allChannels.isNotEmpty()) {
            emptyHint.visibility = View.GONE
        }
    }

    private fun focusCard(position: Int) {
        gridView.postDelayed({
            val holder = gridView.findViewHolderForAdapterPosition(position)
            if (holder != null) holder.itemView.requestFocus() else gridView.requestFocus()
        }, 120)
    }

    private fun focusCurrentCard() {
        val channel = currentChannel ?: return
        val position = channelAdapter.positionOf(channel.url)
        val target = if (position >= 0) position else 0
        gridView.scrollToPosition(target)
        focusCard(target)
    }

    private fun focusedChannel(): Channel? {
        val child = gridView.focusedChild ?: return null
        val position = gridView.getChildAdapterPosition(child)
        if (position == RecyclerView.NO_POSITION) return null
        return channelAdapter.channelAt(position)
    }

    /** 长按 OK（或收藏键）切换收藏；虚拟分组会即时重排 */
    private fun toggleFavorite(channel: Channel) {
        val added = prefs.toggleFavorite(channel.stableKey())
        buildGroups()
        if (Library.isVirtual(activeGroup)) {
            applyFilter(focusFirst = false)
            focusCard(0)
        } else {
            channelAdapter.notifyDataSetChanged()
        }
        if (epgVisible) epgAdapter.refreshGuide()
        toast(if (added) "★ 已收藏：${channel.name}" else "已取消收藏：${channel.name}")
    }

    // ------------------------------------------------------------ 播放控制

    private fun playChannel(channel: Channel, enterPlayer: Boolean = true) {
        if (channel.url.isBlank()) {
            toast("该频道没有可用的播放地址")
            return
        }

        currentResolution = null
        showChannelInfo(channel)
        if (epgVisible) syncEpgToCurrent()

        if (prefs.isExternalEngine && launchExternalPlayer(channel)) {
            currentChannel = channel
            channelAdapter.setCurrent(channel.url)
            updateNowPlayingChip()
            repository.lastIndex = channel.number - 1
            prefs.recordRecent(channel.stableKey())
            return
        }

        currentChannel = channel
        currentLineIndex = prefs.lineIndexFor(channel.stableKey())
            .coerceIn(0, (channel.allUrls().size - 1).coerceAtLeast(0))
        lineSwitches = 0
        audioSwitches = 0
        audioSwitchPending = false
        audioUnsupportedNotified = false
        timeshiftStart = null
        channelAdapter.setCurrent(channel.url)
        updateNowPlayingChip()

        channelNameView.text = channel.name
        channelMetaView.text = metaOf(channel)
        statusView.visibility = View.GONE
        errorView.visibility = View.GONE

        userPaused = false
        startPlayback(channel)

        repository.lastIndex = channel.number - 1
        prefs.recordRecent(channel.stableKey())
        if (enterPlayer) enterPlayerMode() else if (mode == MODE_WALL) focusCurrentCard()
    }

    /** 用当前线路 + 当前时移状态起播；切线路、回看、自动重试都走这里 */
    private fun startPlayback(channel: Channel) {
        val urls = channel.allUrls()
        if (urls.isEmpty()) return
        currentLineIndex = currentLineIndex.coerceIn(0, urls.lastIndex)
        val liveUrl = urls[currentLineIndex]
        val start = timeshiftStart
        val url = if (start != null) Catchup.build(channel, start, liveUrl) ?: liveUrl else liveUrl
        preparePlayback(buildMediaItem(url), url)
        channelMetaView.text = metaOf(channel) + playbackSuffix(channel, start)
        updateInfoExtra()
    }

    private fun playbackSuffix(channel: Channel, start: Long?): String = buildString {
        if (channel.hasMultiLine()) {
            append("   ·   ").append(channel.lineLabel(currentLineIndex))
                .append(" ").append(currentLineIndex + 1).append("/")
                .append(channel.allUrls().size)
        }
        if (start != null) append("   ·   时移 ").append(Catchup.clock(start))
    }

    /** 手动 / 自动切到相邻线路 */
    private fun switchLine(delta: Int, silent: Boolean = false) {
        val channel = currentChannel ?: return
        val urls = channel.allUrls()
        if (urls.size < 2) {
            if (!silent) toast("${channel.name} 只有 1 条线路")
            return
        }
        currentLineIndex = ((currentLineIndex + delta) % urls.size + urls.size) % urls.size
        prefs.setLineIndex(channel.stableKey(), currentLineIndex)
        if (!silent) {
            toast("${channel.name}　${channel.lineLabel(currentLineIndex)}（${currentLineIndex + 1}/${urls.size}）")
        }
        errorView.visibility = View.GONE
        statusView.visibility = View.GONE
        startPlayback(channel)
    }

    /**
     * 换源菜单：多订阅里同名频道已合并成一张卡片，这里列出全部线路
     * （主线路在最前，其余为其他订阅的备用源），可直接选一个播。
     */
    private fun showLinePicker() {
        if (linePickerOpen) return
        val channel = currentChannel ?: return
        val lines = channel.lines()
        if (lines.size < 2) {
            toast("${channel.name} 只有 1 条线路")
            return
        }
        val labels = lines.mapIndexed { index, line ->
            val name = channel.lineLabel(index)
            val host = runCatching { android.net.Uri.parse(line.first).host.orEmpty() }
                .getOrDefault("")
            val mark = if (index == currentLineIndex) "●　" else "　　"
            "$mark${index + 1}. $name" + if (host.isNotBlank() && host != name) "   $host" else ""
        }.toTypedArray()

        linePickerOpen = true
        android.app.AlertDialog.Builder(this)
            .setTitle("换源 · ${channel.name}")
            .setItems(labels) { _, which ->
                currentLineIndex = which
                prefs.setLineIndex(channel.stableKey(), which)
                lineSwitches = 0
                errorView.visibility = View.GONE
                statusView.visibility = View.GONE
                startPlayback(channel)
                toast("已换到 ${channel.lineLabel(which)}（${which + 1}/${lines.size}）")
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener { linePickerOpen = false }
            .show()
    }

    /** 回看 / 时移菜单：快捷档位 + XMLTV 最近播过的节目 */
    private fun showTimeshiftMenu() {
        val channel = currentChannel
        if (channel == null) {
            toast("请先播放一个频道")
            return
        }
        if (!channel.canCatchup()) {
            toast("该频道没有可直连的 http 地址，无法时移")
            return
        }
        val options = Catchup.options(channel, epg)
        val labels = options.map { it.label }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("回看 / 时移 · ${channel.name}")
            .setMessage(Catchup.hint(channel))
            .setItems(labels) { _, which ->
                val picked = options.getOrNull(which) ?: return@setItems
                timeshiftStart = if (picked.start <= 0L) null else picked.start
                lineSwitches = 0
                startPlayback(channel)
                toast(
                    if (timeshiftStart == null) "已回到直播"
                    else "时移：${Catchup.clock(timeshiftStart!!)}　（源不支持会提示播放失败）"
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 打开 EPG 时间轴网格 */
    private fun openEpgGrid() {
        if (allChannels.isEmpty()) {
            toast("还没有频道，按 MENU 打开设置添加播放源")
            return
        }
        EpgGridActivity.pendingChannels = allChannels
        EpgGridActivity.pendingEpg = epg
        val index = currentChannel?.let { c ->
            allChannels.indexOfFirst { it.url == c.url }.takeIf { it >= 0 }
        } ?: epgIndex
        EpgGridActivity.pendingIndex = index.coerceIn(0, allChannels.size - 1)
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent(this, EpgGridActivity::class.java), REQUEST_EPG_GRID
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_EPG_GRID && resultCode == android.app.Activity.RESULT_OK) {
            val index = data?.getIntExtra(EpgGridActivity.EXTRA_PLAY_INDEX, -1) ?: -1
            allChannels.getOrNull(index)?.let { playChannel(it) }
        }
    }

    private fun playNeighbor(delta: Int) {
        val playable = allChannels.filter { it.url.isNotBlank() }
        if (playable.isEmpty()) {
            toast("还没有可直连的源，按 MENU 添加")
            return
        }
        val current = currentChannel
        val index = if (current == null) -1 else playable.indexOfFirst { it.url == current.url }
        val size = playable.size
        val next = if (index < 0) 0 else ((index + delta) % size + size) % size
        playChannel(playable[next], enterPlayer = mode == MODE_PLAYER)
    }

    private fun launchExternalPlayer(channel: Channel): Boolean {
        // 修复：外部播放器之前永远只拿主线路，用户手动选中的备用线路会被忽略
        val urls = channel.allUrls()
        val idx = prefs.lineIndexFor(channel.stableKey())
            .coerceIn(0, (urls.size - 1).coerceAtLeast(0))
        val target = urls.getOrNull(idx) ?: channel.url
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(target), "video/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pkg = prefs.externalPackage
        if (pkg != null) {
            intent.setPackage(pkg)
            if (pkg == Prefs.PACKAGE_MX_FREE && intent.resolveActivity(packageManager) == null) {
                intent.setPackage(Prefs.PACKAGE_MX_PRO)
            }
        }
        if (intent.resolveActivity(packageManager) == null) {
            toast("未找到所选播放器，已回退内置播放器")
            return false
        }
        return try {
            player.pause()
            startActivity(intent)
            toast("已交给外部播放器：${channel.name}")
            true
        } catch (ignored: Exception) {
            toast("外部播放器启动失败，已回退内置播放器")
            false
        }
    }

    private fun playByNumber(number: Int) {
        val channel = allChannels.firstOrNull { it.number == number }
        if (channel == null) {
            toast("没有 CH $number（当前共 ${allChannels.size} 个频道）")
            return
        }
        if (epgVisible) hideEpg()
        playChannel(channel)
    }

    // ------------------------------------------------------------ 信息卡 / 节目单

    /**
     * 换台信息卡：按常见 TV 播放器的信息栏组织内容 ——
     * 频道号/分组/线路 → 频道名 → 当前节目时间段与剩余 → 当前节目名 → 下一个节目 → 时间/清晰度。
     * showChannelInfo 在 currentChannel 赋值之前就会被调用，所以这里全部用入参 channel，
     * 不能读 currentChannel / currentLineIndex（会是上一个频道的值）。
     */
    private fun showChannelInfo(channel: Channel) {
        val urls = channel.allUrls()
        val lineIndex = prefs.lineIndexFor(channel.stableKey())
            .coerceIn(0, (urls.size - 1).coerceAtLeast(0))
        infoNumberView.text = buildString {
            append("CH ").append(channel.number.toString().padStart(2, '0'))
            channel.group?.takeIf { it.isNotBlank() }?.let { append("　·　").append(it) }
            if (urls.size > 1) {
                append("　·　线路 ").append(lineIndex + 1).append("/").append(urls.size)
            }
        }
        infoNameView.text = channel.name
        updateInfoExtra()
        updateInfoProgram(channel)
        infoTimeView.text = buildString {
            append(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()))
            currentResolution?.takeIf { it.isNotBlank() }?.let { append("　·　").append(it) }
        }
        channelInfoCard.visibility = View.VISIBLE
        // 底部信息栏同时显示；内部统一负责 8 秒后一起收起
        showBottomInfo(channel)
    }

    private fun updateInfoExtra() {
        val channel = currentChannel ?: return
        val tags = ArrayList<String>()
        if (channel.url.contains(".m3u8", true)) tags.add("HLS")
        tags.add(
            when {
                prefs.isExternalEngine -> "外部播放器"
                prefs.isFastEngine -> "内置加速"
                else -> "内置播放"
            }
        )
        currentResolution?.let { tags.add(it) }
        infoExtraView.text = tags.joinToString(" · ")
    }

    /** 当前节目（时间段 + 剩余分钟 + 节目名）与下一个节目，参考 TV 播放器信息栏 */
    private fun updateInfoProgram(channel: Channel? = currentChannel) {
        val ch = channel ?: return
        val now = epg.anchorNow()
        val current = epg.current(ch)
        val next = epg.upcoming(ch, 1).firstOrNull()

        infoGroupView.text = if (current != null) {
            val left = ((current.stop - now) / 60_000L).coerceAtLeast(0)
            "${current.timeRange()}　·　剩 ${left} 分钟"
        } else {
            "暂无节目单"
        }

        infoProgramView.visibility = View.VISIBLE
        infoProgramView.text = current?.title
            ?: next?.let { "即将  ${it.title}" }
            ?: "—"

        val nextLine = next?.let { "接下来  ${it.timeRange()}　${it.title}" }.orEmpty()
        infoNextView.visibility = if (nextLine.isBlank()) View.GONE else View.VISIBLE
        infoNextView.text = nextLine
        // 节目单异步加载完成后，底部栏的时间段 / 进度 / 下一个节目也跟着刷新
        updateBottomProgram(ch)
    }

    // ------------------------------------------------------------ 底部频道信息栏

    /** 换台时填充底部栏并显示；按钮按下、遥控器移动焦点时都会重置 8 秒隐藏计时 */
    private fun showBottomInfo(channel: Channel) {
        bindBottomLogo(channel)

        val urls = channel.allUrls()
        val lineIndex = prefs.lineIndexFor(channel.stableKey())
            .coerceIn(0, (urls.size - 1).coerceAtLeast(0))
        bottomName.text = channel.name
        bottomMeta.text = buildString {
            append("CH ").append(channel.number.toString().padStart(2, '0'))
            channel.group?.takeIf { it.isNotBlank() }?.let { append("  ·  ").append(it) }
            append("  ·  ").append(channel.lineLabel(lineIndex))
            append("  ").append(lineIndex + 1).append("/").append(urls.size)
        }
        bottomTime.text = buildString {
            append(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()))
            currentResolution?.takeIf { it.isNotBlank() }?.let { append("  ·  ").append(it) }
        }
        updateBottomProgram(channel)
        refreshBottomButtons(channel)
        bottomInfoBar.visibility = View.VISIBLE
        scheduleBottomInfoHide()
    }

    private fun bindBottomLogo(channel: Channel) {
        val logo = runCatching { LogoResolver.resolve(channel, prefs, true) }.getOrNull()
        when (logo) {
            null -> showBottomLetter(channel)
            is Int -> {
                bottomLogo.setImageResource(logo)
                bottomLogoLetter.visibility = View.GONE
            }
            else -> {
                bottomLogoLetter.visibility = View.GONE
                val request = ImageRequest.Builder(this)
                    .data(logo.toString())
                    .size(160, 96)
                    .target(bottomLogo)
                    .listener(object : ImageRequest.Listener {
                        override fun onError(request: ImageRequest, result: ErrorResult) {
                            showBottomLetter(channel)
                        }
                    })
                    .build()
                imageLoader.enqueue(request)
            }
        }
    }

    private fun showBottomLetter(channel: Channel) {
        bottomLogo.setImageDrawable(null)
        bottomLogoLetter.text = channel.name.trim().take(1).ifBlank { "TV" }
        bottomLogoLetter.visibility = View.VISIBLE
    }

    /** 当前节目时间段 / 剩余 / 节目名 / 进度 / 下一个节目 */
    private fun updateBottomProgram(channel: Channel? = currentChannel) {
        val ch = channel ?: return
        if (!::bottomRange.isInitialized) return
        val now = epg.anchorNow()
        val current = epg.current(ch)
        val next = epg.upcoming(ch, 1).firstOrNull()

        bottomRange.text = if (current != null) {
            val left = ((current.stop - now) / 60_000L).coerceAtLeast(0)
            "${current.timeRange()}  ·  剩 ${left} 分钟"
        } else "暂无节目单"
        bottomProgram.text = current?.title ?: next?.let { "即将  ${it.title}" } ?: "—"

        val pct = current?.let { epg.progressPercent(it) } ?: 0
        bottomProgress.progress = pct
        bottomProgressText.text = if (current != null) "已播 $pct%" else ""

        val nextLine = next?.let { "接下来  ${it.timeRange()}  ${it.title}" }.orEmpty()
        bottomNext.visibility = if (nextLine.isBlank()) View.GONE else View.VISIBLE
        bottomNext.text = nextLine
    }

    /** 按钮文字直接显示当前状态：线路、画质、播放状态、内核 */
    private fun refreshBottomButtons(channel: Channel? = currentChannel) {
        val ch = channel ?: return
        if (!::btnLine.isInitialized) return
        val urls = ch.allUrls()
        val idx = prefs.lineIndexFor(ch.stableKey())
            .coerceIn(0, (urls.size - 1).coerceAtLeast(0))
        btnLine.text = "线路 ${idx + 1}/${urls.size}"
        btnQuality.text = "画质 " + Prefs.QUALITY_LABELS
            .getOrElse(prefs.quality) { "自动" }.take(6)
        val playing = ::player.isInitialized && player.isPlaying
        btnControl.text = if (playing) "暂停" else "继续"
        btnControl.setCompoundDrawablesWithIntrinsicBounds(
            if (playing) R.drawable.ic_flat_pause else R.drawable.ic_flat_play, 0, 0, 0
        )
        val engine = Prefs.ENGINE_LABELS.getOrElse(prefs.engine) { "内置" }
        btnEngine.text = "内核 " + when {
            engine.contains("加速") -> "加速"
            engine.contains("标准") -> "标准"
            engine.contains("VLC") -> "VLC"
            engine.contains("MX") -> "MX"
            engine.contains("Kodi") -> "Kodi"
            else -> "系统"
        }
    }

    private fun scheduleBottomInfoHide() {
        handler.removeCallbacks(hideInfoCardRunnable)
        handler.postDelayed(hideInfoCardRunnable, 8000L)
    }

    private fun bottomActionsHasFocus(): Boolean {
        if (!::btnLine.isInitialized) return false
        return listOf(btnGuide, btnLine, btnQuality, btnControl, btnEngine, btnSettings, btnClear)
            .any { it.hasFocus() }
    }

    private fun setupBottomActions() {
        btnGuide.setOnClickListener { showEpg() }
        btnLine.setOnClickListener { pickBottomLine() }
        btnQuality.setOnClickListener { pickBottomQuality() }
        btnControl.setOnClickListener {
            togglePlayback()
            refreshBottomButtons()
            scheduleBottomInfoHide()
        }
        btnEngine.setOnClickListener { pickBottomEngine() }
        btnSettings.setOnClickListener { openSettings() }
        btnClear.setOnClickListener { clearBottomCaches() }

        // 遥控器在按钮间移动时重置隐藏计时，避免还没选完信息栏就消失
        val reset = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) scheduleBottomInfoHide()
        }
        listOf(btnGuide, btnLine, btnQuality, btnControl, btnEngine, btnSettings, btnClear)
            .forEach { it.onFocusChangeListener = reset }
    }

    /** 选线路后立即用所选线路重新起播当前频道 */
    private fun pickBottomLine() {
        val ch = currentChannel ?: return
        val lines = ch.lines()
        if (lines.isEmpty()) return
        val labels = lines.mapIndexed { i, line ->
            val mark = if (i == currentLineIndex) "● " else "   "
            val host = runCatching { Uri.parse(line.first).host.orEmpty() }.getOrDefault("")
            "$mark${i + 1}. ${ch.lineLabel(i)}" + if (host.isNotBlank()) "  $host" else ""
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择线路 · ${ch.name}")
            .setItems(labels) { _, which ->
                currentLineIndex = which
                prefs.setLineIndex(ch.stableKey(), which)
                startPlayback(ch)
                showBottomInfo(ch)
                toast("已切换 ${ch.lineLabel(which)}（${which + 1}/${lines.size}）")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 选分辨率后立即应用到轨道选择器，当前画面即时生效 */
    private fun pickBottomQuality() {
        AlertDialog.Builder(this)
            .setTitle("分辨率 / 画质")
            .setSingleChoiceItems(
                Prefs.QUALITY_LABELS.toTypedArray(), prefs.quality
            ) { dialog, which ->
                prefs.quality = which
                applyTrackParameters()
                refreshBottomButtons()
                toast("已切换：${Prefs.QUALITY_LABELS[which]}")
                scheduleBottomInfoHide()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 选播放器内核后重新起播：内置内核换缓冲策略，外部内核直接拉起对应播放器 */
    private fun pickBottomEngine() {
        AlertDialog.Builder(this)
            .setTitle("播放器内核")
            .setSingleChoiceItems(
                Prefs.ENGINE_LABELS.toTypedArray(), prefs.engine
            ) { dialog, which ->
                prefs.engine = which
                currentChannel?.let { playChannel(it, enterPlayer = false) }
                refreshBottomButtons()
                toast("已切换：${Prefs.ENGINE_LABELS[which]}，正在重新起播")
                scheduleBottomInfoHide()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearBottomCaches() {
        runCatching {
            imageLoader.memoryCache?.clear()
            imageLoader.diskCache?.clear()
        }
        previewFailedUrls.clear()
        toast("台标与预览缓存已清理")
        scheduleBottomInfoHide()
    }

    private fun loadEpgInBackground(force: Boolean = false) {
        if (epgLoading) return
        epgLoading = true
        lastEpgAttempt = System.currentTimeMillis()
        ioExecutor.execute {
            runCatching { epg.load(force) }
            runOnUiThread {
                epgLoading = false
                if (epgVisible) {
                    epgTitleView.text = if (epg.isLoaded) "节目单 · XMLTV" else "节目单 · 频道清单"
                    epgAdapter.refreshGuide()
                    updateEpgDetail()
                }
                updateInfoProgram()
                // 加载失败时给出明确提示（去重，避免反复弹），方便盒子侧定位是网络还是地址问题
                if (!epg.isLoaded && !epg.lastError.isNullOrBlank()) {
                    val err = epg.lastError
                    if (err != lastEpgErrorShown) {
                        lastEpgErrorShown = err
                        toast("节目单加载失败：$err　·　设置 → XMLTV 节目单检查地址")
                    }
                } else if (epg.isLoaded) {
                    lastEpgErrorShown = null
                }
            }
        }
    }

    /**
     * 节目预约提醒：应用在前台时每分钟检查，开播前 1 分钟弹浮层，按 OK 直接跳台。
     * 盒子多无通知栏 / 后台受限，因此用前台提醒，不依赖 AlarmManager。
     */
    private fun checkReservations() {
        val keys = prefs.reservations
        if (keys.isEmpty()) return
        val now = System.currentTimeMillis()
        for (key in keys) {
            val start = key.substringAfterLast('|').toLongOrNull() ?: continue
            if (start - now !in 0..60_000L) continue
            if (lastReminderKey == key) continue
            val channelKey = key.substringBeforeLast('|')
            val channel = allChannels.firstOrNull { it.stableKey() == channelKey } ?: continue
            lastReminderKey = key
            val title = epg.window(channel, start - 60_000L, start + 60_000L)
                .firstOrNull()?.title ?: "预约节目"
            reservationTarget = channelKey
            reservationCard.text = buildString {
                append("⏰ 预约提醒　").append(channel.name).append(" · ").append(title)
                append("\n").append(Catchup.clock(start)).append(" 即将开始　按 OK 立即跳转")
            }
            reservationCard.visibility = View.VISIBLE
            handler.removeCallbacks(hideReservationRunnable)
            handler.postDelayed(hideReservationRunnable, 60_000L)
            toast("⏰ 预约节目即将开始：${channel.name} $title")
            // 一次性提醒，触发后从预约表移除，避免反复弹出
            prefs.toggleReservation(key)
            return
        }
    }

    private fun showEpg() {
        if (allChannels.isEmpty()) {
            toast("还没有频道，按 MENU 打开设置添加播放源")
            return
        }
        epgVisible = true
        epgPanel.visibility = View.VISIBLE
        epgTitleView.text = if (epg.isLoaded) "节目单 · XMLTV" else "节目单 · 频道清单"
        val current = currentChannel
        epgIndex = current?.let { c -> allChannels.indexOfFirst { it.url == c.url } }
            ?.takeIf { it >= 0 } ?: 0
        epgAdapter.submit(allChannels, epgIndex, current?.url)
        epgTimeView.text = "当前时间  " +
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        epgListView.scrollToPosition(epgIndex)
        updateEpgDetail()
        if (prefs.epgUrl.isNotBlank()) loadEpgInBackground()
    }

    private fun hideEpg() {
        epgVisible = false
        epgPanel.visibility = View.GONE
    }

    private fun moveEpg(delta: Int) {
        if (allChannels.isEmpty()) return
        epgIndex = (epgIndex + delta).coerceIn(0, allChannels.size - 1)
        epgAdapter.updateSelection(epgIndex, currentChannel?.url)
        epgListView.scrollToPosition(epgIndex)
        updateEpgDetail()
    }

    private fun updateEpgDetail() {
        val channel = allChannels.getOrNull(epgIndex) ?: return
        epgDetailView.text = "【${channel.name}】\n" + epg.detail(channel)
    }

    private fun pickEpgChannel(index: Int) {
        val channel = allChannels.getOrNull(index) ?: return
        hideEpg()
        playChannel(channel)
    }

    private fun syncEpgToCurrent() {
        val current = currentChannel ?: return
        val index = allChannels.indexOfFirst { it.url == current.url }
        if (index >= 0) {
            epgIndex = index
            epgAdapter.updateSelection(index, current.url)
            epgListView.scrollToPosition(index)
            updateEpgDetail()
        }
    }

    private fun enterPlayerMode() {
        mode = MODE_PLAYER
        stopPreview()
        // 从墙面回来时重建全屏 surface：强制重绑 player，确保 surface 重建后能正确出画
        playerView.visibility = View.VISIBLE
        playerView.player = null
        playerView.player = player
        wallLayer.visibility = View.GONE
        // 兜底把主音量拉满：历史版本里预览小窗抢过音频焦点，ExoPlayer 会把主音量压到 0
        player.volume = 1f
        if (currentChannel != null && !userPaused) player.play()
        showPlayerOverlay()
    }

    private fun exitPlayerMode() {
        mode = MODE_WALL
        hideEpg()
        channelInfoCard.visibility = View.GONE
        bottomInfoBar.visibility = View.GONE
        handler.removeCallbacks(hideInfoCardRunnable)
        wallLayer.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlayRunnable)
        infoPanel.visibility = View.GONE
        hintView.visibility = View.GONE
        statusView.visibility = View.GONE
        errorView.visibility = View.GONE
        // 关键性能修复：回到频道墙就暂停并把全屏 SurfaceView 置 GONE，
        // 系统会销毁 surface 并释放硬件视频解码器，墙面滚动不再和直播抢资源
        if (!prefs.backgroundPlay && currentChannel != null) {
            player.pause()
            playerView.visibility = View.GONE
        }
        updateNowPlayingChip()
        buildGroups()
        if (Library.isVirtual(activeGroup)) applyFilter(focusFirst = false)
        focusCurrentCard()
    }

    private fun togglePlayback() {
        if (player.isPlaying) {
            userPaused = true
            player.pause()
            statusView.text = "已暂停    按播放键继续"
            statusView.visibility = View.VISIBLE
            showPlayerOverlay()
        } else if (currentChannel != null) {
            userPaused = false
            player.play()
            statusView.visibility = View.GONE
            showPlayerOverlay()
        }
    }

    // ------------------------------------------------------------ 按键

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 数字选台输入中：OK 立即跳转、DEL 退格、BACK 取消；
        // 必须放在播放态快捷键之前，否则上下/左右/OK 会把输入过程抢走。
        if (numberBuffer.isNotEmpty()) {
            when (keyCode) {
                KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_CLEAR -> {
                    backspaceNumber()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (event == null || event.repeatCount == 0) commitNumber()
                    return true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    cancelNumberInput()
                    return true
                }
            }
        }
        // 底部信息栏可见时，任何遥控器操作都算一次交互，重置 8 秒隐藏计时
        if (::bottomInfoBar.isInitialized && bottomInfoBar.visibility == View.VISIBLE) {
            scheduleBottomInfoHide()
            // 焦点落在按钮行时，屏蔽播放器默认的「上下换台 / 左右切线路 / OK 节目单」，
            // 否则遥控器还没选完按钮就换台了。左右键仍由系统做按钮间焦点导航。
            if (bottomActionsHasFocus()) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_BACK -> {
                        bottomInfoBar.visibility = View.GONE
                        handler.removeCallbacks(hideInfoCardRunnable)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> return true
                }
            }
        }
        // 让 OK 键参与长按跟踪：长按 OK = 打开换源菜单
        if (event != null && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            event.startTracking()
        }
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (epgVisible) {
                    hideEpg()
                    return true
                }
                if (mode == MODE_PLAYER) {
                    exitPlayerMode()
                    return true
                }
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                playNeighbor(1)
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                playNeighbor(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (epgVisible) {
                    moveEpg(-1)
                    return true
                }
                if (mode == MODE_PLAYER) {
                    playNeighbor(1)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (epgVisible) {
                    moveEpg(1)
                    return true
                }
                if (mode == MODE_PLAYER) {
                    playNeighbor(-1)
                    return true
                }
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlayback()
                return true
            }
            KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TV, KeyEvent.KEYCODE_INFO -> {
                openEpgGrid()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (mode == MODE_PLAYER && !epgVisible) {
                    lineSwitches = 0
                    switchLine(-1)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (mode == MODE_PLAYER && !epgVisible) {
                    lineSwitches = 0
                    switchLine(1)
                    return true
                }
            }
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                showTimeshiftMenu()
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                if (epgVisible) {
                    allChannels.getOrNull(epgIndex)?.let { toggleFavorite(it) }
                    return true
                }
                // 信息栏可见时，第一次 MENU 把焦点交给按钮行供选择；再按一次才进设置中心
                if (::bottomInfoBar.isInitialized && bottomInfoBar.visibility == View.VISIBLE &&
                    !bottomActionsHasFocus()
                ) {
                    btnLine.requestFocus()
                    scheduleBottomInfoHide()
                    toast("←→ 选择按钮，OK 确认；再按 MENU 打开设置")
                    return true
                }
                openSettings()
                return true
            }
            KeyEvent.KEYCODE_BOOKMARK -> {
                // 部分遥控器带收藏键：墙面直接收藏，EPG 面板收藏选中频道
                val channel = if (epgVisible) {
                    allChannels.getOrNull(epgIndex)
                } else {
                    focusedChannel()
                }
                channel?.let { toggleFavorite(it) }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // 播放器里长按 OK：直接打开换源菜单，在多个订阅的线路里挑一个
                if (event != null && (event.repeatCount >= 1 || event.isLongPress) &&
                    mode == MODE_PLAYER && !epgVisible
                ) {
                    showLinePicker()
                    return true
                }
                // 预约提醒浮层显示时，OK 直接跳到预约频道
                val reserved = reservationTarget
                if (reserved != null) {
                    reservationTarget = null
                    reservationCard.visibility = View.GONE
                    handler.removeCallbacks(hideReservationRunnable)
                    allChannels.firstOrNull { it.stableKey() == reserved }?.let {
                        playChannel(it)
                        return true
                    }
                }
                when {
                    epgVisible -> {
                        pickEpgChannel(epgIndex)
                        return true
                    }
                    mode == MODE_PLAYER -> {
                        showEpg()
                        return true
                    }
                    allChannels.isEmpty() -> {
                        openSettings()
                        return true
                    }
                }
            }
        }

        val digit = when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
            else -> -1
        }
        if (digit >= 0) {
            if (epgVisible) hideEpg()
            handleDigit(digit)
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
            mode == MODE_PLAYER && !epgVisible
        ) {
            showLinePicker()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    private fun handleDigit(digit: Int) {
        if (numberBuffer.length >= NUMBER_MAX_DIGITS) numberBuffer = ""
        numberBuffer += digit.toString()
        handler.removeCallbacks(commitNumberRunnable)
        updateNumberInputUi()
        handler.postDelayed(commitNumberRunnable, NUMBER_TIMEOUT)
    }

    /** 输入过程实时回显台号、精确命中的频道和前缀候选，电视上不再「盲按」 */
    private fun updateNumberInputUi() {
        if (!::numberInputCard.isInitialized) return
        numberInputCard.visibility = View.VISIBLE
        numberInputDigitsView.text = numberBuffer
            .padEnd(NUMBER_MAX_DIGITS, '_')
            .map { "$it " }
            .joinToString("")
            .trimEnd()

        val exact = allChannels.firstOrNull { it.number.toString() == numberBuffer }
        val candidates = allChannels.asSequence()
            .filter { it.number.toString().startsWith(numberBuffer) }
            .take(3)
            .toList()
            .joinToString("　") { "CH${it.number} ${it.name}" }
        numberInputMatchView.text = when {
            exact != null -> "▶ CH${exact.number}　${exact.name}"
            candidates.isNotBlank() -> candidates
            else -> "没有以 $numberBuffer 开头的频道"
        }
    }

    private fun backspaceNumber() {
        numberBuffer = numberBuffer.dropLast(1)
        handler.removeCallbacks(commitNumberRunnable)
        if (numberBuffer.isEmpty()) {
            cancelNumberInput()
            return
        }
        updateNumberInputUi()
        handler.postDelayed(commitNumberRunnable, NUMBER_TIMEOUT)
    }

    private fun cancelNumberInput() {
        numberBuffer = ""
        handler.removeCallbacks(commitNumberRunnable)
        if (::numberInputCard.isInitialized) numberInputCard.visibility = View.GONE
    }

    private fun commitNumber() {
        val target = numberBuffer.toIntOrNull()
        cancelNumberInput()
        if (target == null || target <= 0) return
        playByNumber(target)
    }

    // ------------------------------------------------------------ 设置入口

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    /**
     * adb / 深链一键导入：
     *   am start -a android.intent.action.VIEW -d "https://x/all.m3u"
     *   am start -a android.intent.action.VIEW -d "flysnow://import?url=https%3A%2F%2Fx%2Fall.txt"
     * 带 method=POST / body / tokenUrl / fields 时仍走完整接口配置。
     */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        val headers = intent.getStringExtra("headers").orEmpty()
        val fields = intent.getStringExtra("fields").orEmpty()
        val body = intent.getStringExtra("body").orEmpty()
        val tokenUrl = intent.getStringExtra("tokenUrl").orEmpty()
        val advanced = intent.getStringExtra("method")?.equals("POST", true) == true ||
            body.isNotBlank() || tokenUrl.isNotBlank() || fields.isNotBlank()

        val raw = if (data.scheme.equals("flysnow", true)) {
            data.getQueryParameter("url").orEmpty()
        } else {
            data.toString()
        }
        val urls = SubscriptionImporter.normalize(raw)
        if (urls.isEmpty()) return

        if (advanced) {
            for (url in urls) {
                repository.addSource(
                    SourceConfig(
                        url = url,
                        headersText = headers,
                        fieldsText = fields,
                        method = intent.getStringExtra("method")?.takeIf { it.isNotBlank() } ?: "GET",
                        body = body,
                        tokenUrl = tokenUrl,
                        tokenPath = intent.getStringExtra("tokenPath").orEmpty(),
                        refreshMinutes = intent.getStringExtra("refresh")?.toIntOrNull() ?: 0
                    )
                )
            }
            prefs.notifySourceChanged()
            toast("已添加 ${urls.size} 个接口")
            reloadPlaylist()
            return
        }

        val replace = data.getQueryParameter("replace") == "1" ||
            intent.getBooleanExtra("replace", false)
        toast("正在一键导入 ${urls.size} 个链接…")
        ioExecutor.execute {
            val items = SubscriptionImporter.import(
                this, repository, urls.joinToString("\n"), replace, headers
            )
            val ok = items.count { it.ok }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                toast("一键导入：成功 $ok/${items.size} 个链接，共 ${items.sumOf { it.channels }} 个频道")
                reloadPlaylist()
            }
        }
    }

    // ------------------------------------------------------------ UI 辅助

    private fun updateNowPlayingChip() {
        val channel = currentChannel
        if (channel == null) {
            nowPlayingChip.visibility = View.GONE
        } else {
            nowPlayingChip.text = "● 正在播放 · ${channel.name}"
            nowPlayingChip.visibility = View.VISIBLE
        }
    }

    private fun showPlayerOverlay() {
        infoPanel.visibility = View.VISIBLE
        hintView.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlayRunnable)
        handler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY)
    }

    private fun hidePlayerOverlay() {
        if (player.isPlaying) {
            infoPanel.visibility = View.GONE
            hintView.visibility = View.GONE
        }
    }

    private fun metaOf(channel: Channel): String {
        val parts = ArrayList<String>()
        parts.add("#${channel.number}")
        channel.group?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        parts.add(hostOf(channel.url))
        currentResolution?.let { parts.add(it) }
        return parts.joinToString("   ·   ")
    }

    private fun updateMeta() {
        val channel = currentChannel ?: return
        channelMetaView.text = metaOf(channel)
        updateInfoExtra()
    }

    private fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("").ifBlank { url }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
