package com.iptv.tvplayer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * 全局设置。会触发「重新加载播放源」的写入递增 [version]；
 * 画质、引擎、预览这类只影响播放行为的设置用 silentEdit，避免每次改设置都重新拉接口。
 *
 * 1.2 性能版：收藏 / 最近观看在进程内做静态缓存，避免列表滚动时反复解析 JSON。
 */
class Prefs(context: Context) {

    companion object {
        // 画质
        const val QUALITY_AUTO = 0
        const val QUALITY_HIGHEST = 1
        const val QUALITY_1080 = 2
        const val QUALITY_720 = 3
        const val QUALITY_480 = 4
        const val QUALITY_SAVER = 5

        val QUALITY_LABELS = listOf(
            "自动（跟随直播源）", "最高画质（强制最高码率）", "1080p 上限",
            "720p 上限", "480p 上限", "省流（≤800 kbps）"
        )
        val QUALITY_HINTS = listOf(
            "由播放器自动选择轨道，兼容性最好",
            "HLS 多码率流强制选最高码率，适合带宽充足",
            "限制视频宽高不超过 1920×1080",
            "限制视频宽高不超过 1280×720，卡顿时可降到这里",
            "限制视频宽高不超过 854×480",
            "同时限制分辨率与码率，弱网优先保流畅"
        )

        // 播放器引擎
        const val ENGINE_STANDARD = 0
        const val ENGINE_FAST = 1
        const val ENGINE_VLC = 2
        const val ENGINE_MX = 3
        const val ENGINE_KODI = 4
        const val ENGINE_SYSTEM = 5

        val ENGINE_LABELS = listOf(
            "内置播放器（标准）", "内置播放器（加速 · 推荐）", "VLC 播放器",
            "MX Player", "Kodi", "系统播放器 / 其他"
        )
        val ENGINE_HINTS = listOf(
            "Media3 / ExoPlayer，缓冲 15 秒，抗网络抖动",
            "缓冲 8 秒 + 直播追帧 + 快速失败，起播最快，推荐盒子使用",
            "调用 org.videolan.vlc，解码兼容性更好",
            "调用 com.mxtech.videoplayer.ad / pro",
            "调用 org.xbmc.kodi",
            "弹出系统选择器，交给盒子上任意播放器"
        )

        const val PACKAGE_VLC = "org.videolan.vlc"
        const val PACKAGE_MX_FREE = "com.mxtech.videoplayer.ad"
        const val PACKAGE_MX_PRO = "com.mxtech.videoplayer.pro"
        const val PACKAGE_KODI = "org.xbmc.kodi"

        // 台标来源
        const val LOGO_AUTO = 0
        const val LOGO_BUILTIN = 1
        const val LOGO_CUSTOM = 2
        const val LOGO_OFF = 3

        val LOGO_LABELS = listOf(
            "自动（接口台标优先）", "只用内置台标", "自定义台标地址", "关闭台标"
        )
        val LOGO_HINTS = listOf(
            "优先用 m3u 的 tvg-logo / JSON 的 logo 字段，没有就用内置台标",
            "忽略播放源里的台标，统一显示内置台标",
            "按你填的地址模板拼出台标 URL，支持 {num} 和 {name}",
            "不加载任何图片，只显示频道名首字"
        )

        // 频道封面（墙上海报）风格：实时画面 / 网络台标 / 首字母
        const val COVER_LIVE = 0
        const val COVER_LOGO = 1
        const val COVER_LETTER = 2

        val COVER_LABELS = listOf(
            "实时画面（聚焦显示直播）", "网络台标", "首字母文字"
        )
        val COVER_HINTS = listOf(
            "静态封面优先显示该频道台标，鼠标悬停 / 聚焦时叠加实时直播画面，最符合「看画面选台」",
            "使用订阅里的 tvg-logo 网络台标作封面（统一圆角底板居中，加载失败显示首字母）",
            "只显示频道名首字，最省流量、不加载任何图片"
        )

        // 卡片预览（与封面模式解耦，只控制悬停 / 聚焦时的实时画面）
        val PREVIEW_LABELS = listOf("开启（悬停后低码率预览）", "关闭（盒子推荐）")
        val PREVIEW_HINTS = listOf(
            "鼠标悬停或遥控器停在卡片上 0.7 秒后，静音播放 360p 实时画面；会额外占用一路解码器",
            "只显示静态封面，不额外拉流。低性能盒子可关闭"
        )

        // 返回频道墙时是否保留后台播放
        val BACKGROUND_LABELS = listOf("关闭（省资源 · 推荐）", "开启（仅保留声音）")
        val BACKGROUND_HINTS = listOf(
            "回到频道墙立即暂停并释放视频解码器，墙面滚动最流畅",
            "回到频道墙继续播放声音，会持续占用解码器与带宽"
        )

        // 多线路自动切换
        val AUTO_SWITCH_LABELS = listOf("开启（失败自动切备用源）", "关闭（仅手动切）")
        val AUTO_SWITCH_HINTS = listOf(
            "当前线路起播失败或中途断流时，自动尝试下一条线路并记住可用线路",
            "播放失败只提示错误，用遥控器 ← → 手动切换线路"
        )

        // XMLTV 刷新间隔
        val EPG_INTERVALS = listOf(3, 6, 12, 24)
        val EPG_LABELS = EPG_INTERVALS.map { "每 $it 小时自动刷新" }
        val EPG_HINTS = EPG_INTERVALS.map { "节目单缓存 $it 小时后重新拉取 XMLTV" }

        // Token 定时刷新
        val TOKEN_INTERVALS = listOf(0, 5, 15, 30, 60)
        val TOKEN_LABELS = listOf("关闭定时刷新", "每 5 分钟", "每 15 分钟", "每 30 分钟", "每 60 分钟")
        val TOKEN_HINTS = listOf(
            "只在加载接口时按需取 Token",
            "Token 有效期短时选 5 分钟",
            "较常见的短期 Token",
            "推荐：兼顾稳定性与请求量",
            "长有效期 Token，减少请求"
        )

        private const val KEY_VERSION = "version"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_RECENT = "recent"
        private const val KEY_RESERVATIONS = "reservations"
        private const val KEY_LINE_PREFIX = "line_"
        private const val KEY_HIDDEN = "hidden_channels"
        /** 1.8 一次性迁移标记：修正老版本被封面联动写死的 preview=false */
        private const val KEY_PREVIEW_MIGRATED = "preview_migrated_18"
        private const val MAX_RECENT = 30

        /** 手机扫码配置服务端口 */
        const val WEB_CONFIG_PORT = 8899

        /** 进程内缓存：所有 Prefs 实例共享，避免列表绑定时反复解析 JSONArray */
        private val CACHE_LOCK = Any()

        @Volatile
        private var cachedFavorites: Set<String>? = null

        @Volatile
        private var cachedRecent: List<String>? = null

        @Volatile
        private var cachedReservations: Set<String>? = null

        @Volatile
        private var cachedHidden: Set<String>? = null
    }

    private val sp = context.getSharedPreferences("tv_settings", Context.MODE_PRIVATE)

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        val editor = sp.edit()
        editor.block()
        editor.putLong(KEY_VERSION, sp.getLong(KEY_VERSION, 0L) + 1L)
        editor.apply()
    }

    /** 不触发 MainActivity 重新拉源的高频写入 */
    private fun silentEdit(block: SharedPreferences.Editor.() -> Unit) {
        val editor = sp.edit()
        editor.block()
        editor.apply()
    }

    private fun Int.clamp(size: Int) = if (this in 0 until size) this else 0

    val version: Long get() = sp.getLong(KEY_VERSION, 0L)

    /** 播放源列表变动时调用，让 MainActivity 回到前台后重新加载 */
    fun notifySourceChanged() = edit { }

    var quality: Int
        get() = sp.getInt("quality", QUALITY_AUTO).clamp(QUALITY_LABELS.size)
        set(value) = silentEdit { putInt("quality", value.clamp(QUALITY_LABELS.size)) }

    /** 1.2 起默认加速引擎：缓冲更短，盒子起播明显更快 */
    var engine: Int
        get() = sp.getInt("engine", ENGINE_FAST).clamp(ENGINE_LABELS.size)
        set(value) = silentEdit { putInt("engine", value.clamp(ENGINE_LABELS.size)) }

    var logoMode: Int
        get() = sp.getInt("logo_mode", LOGO_AUTO).clamp(LOGO_LABELS.size)
        set(value) = silentEdit { putInt("logo_mode", value.clamp(LOGO_LABELS.size)) }

    /** 频道封面风格：实时画面 / 网络台标 / 首字母；选「实时画面」时由设置页自动开启卡片预览 */
    var coverMode: Int
        get() = sp.getInt("cover_mode", COVER_LIVE).clamp(COVER_LABELS.size)
        set(value) = silentEdit { putInt("cover_mode", value.clamp(COVER_LABELS.size)) }

    var logoTemplate: String
        get() = sp.getString("logo_template", "").orEmpty()
        set(value) = silentEdit { putString("logo_template", value.trim()) }

    /**
     * 卡片低码率预览，与「频道封面显示」完全解耦：
     * 封面模式只决定静态封面（台标 / 首字母），悬停 / 聚焦是否出实时画面只看这个开关。
     * 老版本里选「台标/首字母」会把 preview 写死成 false，且之后再切回「实时画面」
     * 也不一定能恢复，导致悬停永远看不到直播画面；这里做一次性迁移把旧值纠正为开启。
     * 用户之后在设置里显式关掉就始终尊重其选择（setter 会打上迁移标记）。
     * 全局只有一个预览播放器、只在单张卡片上出画面，不会一屏多路解码。
     */
    var previewEnabled: Boolean
        get() {
            if (!sp.getBoolean(KEY_PREVIEW_MIGRATED, false)) {
                silentEdit {
                    putBoolean(KEY_PREVIEW_MIGRATED, true)
                    putBoolean("preview", true)
                }
            }
            return sp.getBoolean("preview", true)
        }
        set(value) = silentEdit {
            putBoolean(KEY_PREVIEW_MIGRATED, true)
            putBoolean("preview", value)
        }

    /** 返回频道墙时保留后台播放，默认关闭以释放视频解码器 */
    var backgroundPlay: Boolean
        get() = sp.getBoolean("background_play", false)
        set(value) = silentEdit { putBoolean("background_play", value) }

    /** XMLTV 节目单地址 */
    var epgUrl: String
        get() = sp.getString("epg_url", "").orEmpty()
        set(value) = edit { putString("epg_url", value.trim()) }

    var epgIntervalIndex: Int
        get() = sp.getInt("epg_interval", 1).clamp(EPG_INTERVALS.size)
        set(value) = silentEdit { putInt("epg_interval", value.clamp(EPG_INTERVALS.size)) }

    val epgRefreshMillis: Long
        get() = EPG_INTERVALS[epgIntervalIndex] * 3600_000L

    var tokenIntervalIndex: Int
        get() = sp.getInt("token_interval", 3).clamp(TOKEN_INTERVALS.size)
        set(value) = silentEdit { putInt("token_interval", value.clamp(TOKEN_INTERVALS.size)) }

    val tokenRefreshMinutes: Int get() = TOKEN_INTERVALS[tokenIntervalIndex]

    /** 手机扫码配置页的 PIN（每次打开设置页时由 QrConfigActivity 重新生成） */
    var webPin: String
        get() = sp.getString("web_pin", "").orEmpty()
        set(value) = silentEdit { putString("web_pin", value) }

    /** 线路失败时是否自动切换备用线路，默认开启 */
    var autoSwitchLine: Boolean
        get() = sp.getBoolean("auto_switch_line", true)
        set(value) = silentEdit { putBoolean("auto_switch_line", value) }

    /** 开启后：频道连续播放失败达到阈值，自动从墙面 / 筛选里隐藏（死链 / 测试源过滤） */
    var hideFailedChannels: Boolean
        get() = sp.getBoolean("hide_failed", false)
        set(value) = silentEdit { putBoolean("hide_failed", value) }

    /** 已被自动隐藏的频道稳定标识集合 */
    val hiddenChannels: Set<String>
        get() = synchronized(CACHE_LOCK) { cachedHidden } ?: loadHidden()

    private fun loadHidden(): Set<String> {
        val set = parseStringSet(sp.getString(KEY_HIDDEN, "").orEmpty())
        synchronized(CACHE_LOCK) { cachedHidden = set.toSet() }
        return set
    }

    /** 加入隐藏集合；已存在返回 false（避免重复触发 UI 刷新） */
    fun addHiddenChannel(key: String): Boolean {
        if (hiddenChannels.contains(key)) return false
        val set = LinkedHashSet(hiddenChannels)
        set.add(key)
        saveHidden(set)
        return true
    }

    fun clearHiddenChannels() {
        saveHidden(emptySet())
        // 让 MainActivity 回到前台后重新加载并刷新隐藏快照
        edit { }
    }

    private fun saveHidden(values: Set<String>) {
        val array = JSONArray()
        for (value in values) array.put(value)
        synchronized(CACHE_LOCK) { cachedHidden = values.toSet() }
        silentEdit { putString(KEY_HIDDEN, array.toString()) }
    }

    /** 实测官方 H5 页播不出来后置 true，以后自动跳过 WebView，直接走其它方案 */
    var h5Disabled: Boolean
        get() = sp.getBoolean("h5_disabled", false)
        set(value) = silentEdit { putBoolean("h5_disabled", value) }

    /** 老盒子内置解码连续失败时，自动尝试盒子上已安装的外部播放器 */
    var externalFallback: Boolean
        get() = sp.getBoolean("external_fallback", true)
        set(value) = silentEdit { putBoolean("external_fallback", value) }

    /** 记住某个频道上次成功的线路，下次起播优先用它 */
    fun lineIndexFor(key: String): Int = sp.getInt(KEY_LINE_PREFIX + key, 0).coerceAtLeast(0)

    fun setLineIndex(key: String, index: Int) =
        silentEdit { putInt(KEY_LINE_PREFIX + key, index.coerceAtLeast(0)) }

    // ------------------------------------------------------------ 节目预约
    // key 格式：频道稳定标识|节目开始时间(epoch ms)

    val reservations: Set<String>
        get() = synchronized(CACHE_LOCK) { cachedReservations } ?: loadReservations()

    private fun loadReservations(): Set<String> {
        val set = parseStringSet(sp.getString(KEY_RESERVATIONS, "").orEmpty())
        synchronized(CACHE_LOCK) { cachedReservations = set.toSet() }
        return set
    }

    private fun saveReservations(values: Collection<String>) {
        val array = JSONArray()
        for (value in values) array.put(value)
        synchronized(CACHE_LOCK) { cachedReservations = values.toSet() }
        silentEdit { putString(KEY_RESERVATIONS, array.toString()) }
    }

    fun isReserved(key: String): Boolean = reservations.contains(key)

    fun toggleReservation(key: String): Boolean {
        val set = LinkedHashSet(reservations)
        val added = if (set.remove(key)) false else { set.add(key); true }
        saveReservations(set)
        return added
    }

    fun clearReservations() = saveReservations(emptyList())

    val isFastEngine: Boolean get() = engine == ENGINE_FAST
    val isExternalEngine: Boolean get() = engine >= ENGINE_VLC

    /** 外部播放器包名；[ENGINE_SYSTEM] 返回 null，表示交给系统选择器 */
    val externalPackage: String?
        get() = when (engine) {
            ENGINE_VLC -> PACKAGE_VLC
            ENGINE_MX -> PACKAGE_MX_FREE
            ENGINE_KODI -> PACKAGE_KODI
            else -> null
        }

    // ------------------------------------------------------------ 收藏 / 最近观看

    private fun parseStringSet(raw: String): LinkedHashSet<String> {
        val result = LinkedHashSet<String>()
        if (raw.isBlank()) return result
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                array.optString(i, "").takeIf { it.isNotBlank() }?.let { result.add(it) }
            }
        }
        return result
    }

    private fun readStringSet(key: String): LinkedHashSet<String> {
        val cached = synchronized(CACHE_LOCK) {
            if (key == KEY_FAVORITES) cachedFavorites else cachedRecent
        }
        if (cached != null) return LinkedHashSet(cached)

        val result = parseStringSet(sp.getString(key, "").orEmpty())
        synchronized(CACHE_LOCK) {
            if (key == KEY_FAVORITES) cachedFavorites = result.toSet()
            else cachedRecent = result.toList()
        }
        return result
    }

    private fun writeStringSet(key: String, values: Collection<String>) {
        val array = JSONArray()
        for (value in values) array.put(value)
        synchronized(CACHE_LOCK) {
            if (key == KEY_FAVORITES) cachedFavorites = values.toSet()
            else cachedRecent = values.toList()
        }
        silentEdit { putString(key, array.toString()) }
    }

    val favoriteKeys: Set<String>
        get() = synchronized(CACHE_LOCK) { cachedFavorites }
            ?: readStringSet(KEY_FAVORITES)

    fun isFavorite(key: String): Boolean =
        (synchronized(CACHE_LOCK) { cachedFavorites } ?: readStringSet(KEY_FAVORITES)).contains(key)

    fun toggleFavorite(key: String): Boolean {
        val set = readStringSet(KEY_FAVORITES)
        val added = if (set.remove(key)) false else { set.add(key); true }
        writeStringSet(KEY_FAVORITES, set)
        return added
    }

    fun removeFavorite(key: String) {
        val set = readStringSet(KEY_FAVORITES)
        if (set.remove(key)) writeStringSet(KEY_FAVORITES, set)
    }

    fun clearFavorites() = writeStringSet(KEY_FAVORITES, emptyList())

    val recentKeys: List<String>
        get() = synchronized(CACHE_LOCK) { cachedRecent }
            ?: readStringSet(KEY_RECENT).toList()

    fun recordRecent(key: String) {
        val set = readStringSet(KEY_RECENT)
        // LinkedHashSet 不能调整顺序，重建一个把最新项放最前
        val ordered = ArrayList<String>(MAX_RECENT)
        ordered.add(key)
        for (item in set) {
            if (item != key && ordered.size < MAX_RECENT) ordered.add(item)
        }
        writeStringSet(KEY_RECENT, ordered)
    }

    fun clearRecent() = writeStringSet(KEY_RECENT, emptyList())

    /** 删除一条或多条最近观看记录（墙面「管理记录」的单选 / 多选 / 全选删除） */
    fun removeRecent(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val set = readStringSet(KEY_RECENT)
        var changed = false
        for (key in keys) {
            if (set.remove(key)) changed = true
        }
        if (changed) writeStringSet(KEY_RECENT, set)
    }

    fun removeRecent(key: String) = removeRecent(setOf(key))
}
