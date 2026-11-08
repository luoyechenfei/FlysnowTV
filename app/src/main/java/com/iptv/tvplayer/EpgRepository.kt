package com.iptv.tvplayer

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream

/** XMLTV 里的一条节目 */
data class Programme(
    val title: String,
    val start: Long,
    val stop: Long,
    val desc: String = "",
    val category: String = ""
) {
    fun timeRange(): String = "${clock(start)}–${clock(stop)}"

    companion object {
        private fun clock(time: Long): String =
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time))
    }
}

/**
 * XMLTV 节目单仓库。
 *
 * - 支持标准 XMLTV（含 `<channel><display-name>`、`<programme start/stop/channel>`、时区偏移）
 * - 支持 .xml / .xml.gz，自动 gzip 解压；自定义请求头与 {token} 会复用播放源配置
 * - 解析结果缓存到 App 内部文件，断网时仍可看当天节目
 * - 频道匹配顺序：tvg-id 精确 → display-name 精确 → CCTV 频道号宽松 → 中文名包含
 */
class EpgRepository(private val context: Context) {

    private enum class TextTarget { NONE, DISPLAY, TITLE, DESC, CATEGORY }

    private class PendingProgramme(
        val channel: String,
        val start: Long,
        val stop: Long,
        var title: String = "",
        var desc: String = "",
        var category: String = ""
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val TIME_WITH_TZ = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        private val TIME_LOCAL = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
    }

    private val cacheFile = File(context.filesDir, "epg_cache.xml")

    @Volatile
    private var programmes: Map<String, List<Programme>> = emptyMap()

    /** 归一化 display-name → 归一化 channel id */
    @Volatile
    private var nameIndex: Map<String, String> = emptyMap()

    @Volatile
    private var loadedAt = 0L

    /** 本次 XMLTV 数据的整体时间范围，用于识别“盒子时钟不准” */
    @Volatile
    private var dataStart = Long.MAX_VALUE

    @Volatile
    private var dataEnd = 0L

    @Volatile
    var lastError: String? = null
        private set

    val isLoaded: Boolean get() = programmes.isNotEmpty()
    val programmeCount: Int get() = programmes.values.sumOf { it.size }
    val channelCount: Int get() = programmes.size

    fun status(): String = when {
        isLoaded -> "已加载 $programmeCount 条节目 · $channelCount 个频道"
        lastError != null -> "加载失败：$lastError"
        Prefs(context).epgUrl.isBlank() -> "未配置 XMLTV 地址（节目单仅显示频道清单）"
        else -> "节目单尚未加载"
    }

    /** 阻塞方法：按缓存策略加载 / 刷新，返回是否有可用数据 */
    fun load(force: Boolean = false): Boolean {
        val prefs = Prefs(context)
        val url = prefs.epgUrl.trim()
        val now = System.currentTimeMillis()

        if (!force && programmes.isNotEmpty() && now - loadedAt < prefs.epgRefreshMillis) return true

        if (!force && cacheFile.exists() &&
            now - cacheFile.lastModified() < prefs.epgRefreshMillis
        ) {
            if (parse(runCatching { cacheFile.readText() }.getOrDefault(""))) return true
        }

        if (url.isNotBlank()) {
            val raw = runCatching { fetch(url) }
                .onFailure { lastError = it.message ?: "请求失败" }
                .getOrNull()
            if (raw != null) {
                runCatching { cacheFile.writeText(raw) }
                lastError = null
                return parse(raw)
            }
        }

        // 网络失败或没配地址：退回缓存
        return if (cacheFile.exists()) {
            parse(runCatching { cacheFile.readText() }.getOrDefault(""))
        } else {
            false
        }
    }

    private fun fetch(urlRaw: String): String {
        val tokens = TokenManager(context)
        val url = tokens.apply(urlRaw)
        val headers = tokens.applyHeaders(PlaylistRepository(context).allHeaders())
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/xml,text/xml,*/*")
            setRequestProperty("Accept-Encoding", "gzip")
            for ((key, value) in headers) setRequestProperty(key, value)
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val gzipped = connection.contentEncoding?.contains("gzip", true) == true ||
                url.contains(".gz", true)
            val stream = if (gzipped) GZIPInputStream(connection.inputStream) else connection.inputStream
            return stream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(raw: String): Boolean {
        if (raw.isBlank()) return false
        return try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(raw.trim().removePrefix("\uFEFF")))

            val map = HashMap<String, MutableList<Programme>>()
            val names = HashMap<String, String>()
            val text = StringBuilder()
            var target = TextTarget.NONE
            var channelId = ""
            var pending: PendingProgramme? = null
            var event = parser.eventType

            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        text.setLength(0)
                        when (parser.name.lowercase(Locale.ROOT)) {
                            "channel" -> channelId = attr(parser, "id")
                            "display-name" -> if (channelId.isNotBlank()) target = TextTarget.DISPLAY
                            "programme" -> pending = PendingProgramme(
                                channel = attr(parser, "channel"),
                                start = parseTime(attr(parser, "start")),
                                stop = parseTime(attr(parser, "stop"))
                            )
                            "title" -> if (pending != null && pending.title.isBlank()) target = TextTarget.TITLE
                            "desc" -> if (pending != null) target = TextTarget.DESC
                            "category" -> if (pending != null && pending.category.isBlank()) target = TextTarget.CATEGORY
                        }
                    }
                    XmlPullParser.TEXT -> if (target != TextTarget.NONE) text.append(parser.text)
                    XmlPullParser.END_TAG -> {
                        when (parser.name.lowercase(Locale.ROOT)) {
                            "display-name" -> {
                                val name = text.toString().trim()
                                if (name.isNotBlank() && channelId.isNotBlank()) {
                                    val key = normalizeKey(channelId)
                                    names.putIfAbsent(key, name)
                                    names[normalizeKey(name)] = key
                                }
                                target = TextTarget.NONE
                            }
                            "title" -> {
                                pending?.title = text.toString().trim()
                                target = TextTarget.NONE
                            }
                            "desc" -> {
                                pending?.desc = text.toString().trim()
                                target = TextTarget.NONE
                            }
                            "category" -> {
                                pending?.category = text.toString().trim()
                                target = TextTarget.NONE
                            }
                            "programme" -> {
                                val p = pending
                                if (p != null && p.title.isNotBlank() && p.start > 0L && p.stop > p.start) {
                                    val key = normalizeKey(p.channel)
                                    map.getOrPut(key) { ArrayList() }.add(
                                        Programme(p.title, p.start, p.stop, p.desc, p.category)
                                    )
                                }
                                pending = null
                                target = TextTarget.NONE
                            }
                            "channel" -> {
                                channelId = ""
                                target = TextTarget.NONE
                            }
                        }
                    }
                }
                event = parser.next()
            }

            for (list in map.values) list.sortBy { it.start }
            var lo = Long.MAX_VALUE
            var hi = 0L
            for (list in map.values) for (p in list) {
                if (p.start < lo) lo = p.start
                if (p.stop > hi) hi = p.stop
            }
            dataStart = lo
            dataEnd = hi
            programmes = map
            nameIndex = names
            loadedAt = System.currentTimeMillis()
            lastError = null
            map.isNotEmpty()
        } catch (error: Exception) {
            lastError = error.message ?: "XMLTV 解析失败"
            false
        }
    }

    private fun attr(parser: XmlPullParser, name: String): String =
        parser.getAttributeValue(null, name).orEmpty().trim()

    /** XMLTV 时间：20261108013000 +0800 / 20261108013000 / 20261108013000 +08:00 */
    private fun parseTime(raw: String): Long {
        if (raw.length < 14) return 0L
        val digits = raw.substring(0, 14)
        if (!digits.all { it.isDigit() }) return 0L
        val zone = raw.substring(14).trim()
        return try {
            if (zone.isBlank()) {
                TIME_LOCAL.parse(digits)?.time ?: 0L
            } else {
                val normalized = zone.replace(":", "")
                (TIME_WITH_TZ.parse("$digits $normalized") ?: TIME_LOCAL.parse(digits))?.time ?: 0L
            }
        } catch (ignored: Exception) {
            0L
        }
    }

    /** 频道 → XMLTV channel id 的匹配 */
    private fun resolveKey(channel: Channel): String? {
        val keys = programmes.keys
        if (keys.isEmpty()) return null

        val wanted = normalizeKey(channel.tvgId?.takeIf { it.isNotBlank() } ?: channel.name)
        if (wanted.isBlank()) return null
        if (keys.contains(wanted)) return wanted

        // 有些源写成 cctv1.cn / cctv1.hd，按前缀匹配（后续不得再出现数字，避免 cctv1 命中 cctv13）
        keys.firstOrNull { key ->
            key.startsWith(wanted) &&
                key.length - wanted.length <= 8 &&
                key.substring(wanted.length).none { it.isDigit() }
        }?.let { return it }

        nameIndex[normalizeKey(channel.name)]?.takeIf { keys.contains(it) }?.let { return it }

        val num = numberToken(wanted)
        val isCctv = wanted.contains("cctv") || wanted.contains("央视") || wanted.contains("中央")
        val wantsPlus = wanted.contains("+")
        if (num != null && isCctv) {
            keys.firstOrNull { key ->
                val sameFamily = key.contains("cctv") || key.contains("央视") || key.contains("中央")
                sameFamily && numberToken(key) == num && key.contains("+") == wantsPlus
            }?.let { return it }

            nameIndex.entries.firstOrNull { (display, id) ->
                keys.contains(id) && numberToken(display) == num &&
                    display.contains("+") == wantsPlus &&
                    (display.contains("cctv") || display.contains("央视"))
            }?.value?.let { return it }
        }

        // 中文台名兜底：名称互相包含
        return nameIndex.entries.firstOrNull { (display, id) ->
            keys.contains(id) && display.length >= 3 &&
                (display.contains(wanted) || wanted.contains(display))
        }?.value
    }

    /**
     * 设备时钟不可信（盒子常不校时）时，把“现在”夹进节目数据的时间范围内，
     * 避免所有节目都被判为“已结束 / 未开始”而整片空白。时钟正常时原样返回。
     */
    /** 供 UI 对齐时间轴：设备时钟不准时返回夹进节目范围内的锚定时间 */
    fun anchorNow(): Long = effectiveNow()

    private fun effectiveNow(): Long {
        val now = System.currentTimeMillis()
        if (programmes.isEmpty()) return now
        val lo = dataStart
        val hi = dataEnd
        if (hi <= lo) return now
        val slack = 2 * 3600_000L
        if (now in (lo - slack)..(hi + slack)) return now
        return now.coerceIn(lo, hi - 1)
    }

    private fun forChannel(channel: Channel): List<Programme> {
        val key = resolveKey(channel) ?: return emptyList()
        val list = programmes[key].orEmpty()
        if (list.isEmpty()) return emptyList()
        val now = effectiveNow()
        val windowed = list.filter { it.stop > now - 2 * 3600_000L && it.start < now + 24 * 3600_000L }
            .take(60)
        if (windowed.isNotEmpty()) return windowed
        // 机顶盒常不校时：若当前设备时间与节目整体时间偏差过大（节目全落在窗口外），
        // 退化为显示全部节目，保证「节目单有内容」而不是整片空白
        return list.sortedBy { it.start }.take(60)
    }

    /** 指定时间窗口内的节目：时间轴网格、回看菜单共用 */
    fun window(channel: Channel, from: Long, to: Long): List<Programme> {
        val key = resolveKey(channel) ?: return emptyList()
        return programmes[key].orEmpty()
            .filter { it.stop > from && it.start < to }
            .take(80)
    }

    fun current(channel: Channel): Programme? {
        val now = effectiveNow()
        return forChannel(channel).firstOrNull { it.start <= now && now < it.stop }
    }

    fun upcoming(channel: Channel, limit: Int = 4): List<Programme> {
        val now = effectiveNow()
        val list = forChannel(channel)
        val future = list.filter { it.start > now }.take(limit)
        return future.ifEmpty { list.filter { it.stop > now }.take(limit) }
    }

    /** 频道列表里的一行摘要 */
    fun guideLine(channel: Channel): String {
        val now = current(channel)
        val next = upcoming(channel, 1).firstOrNull()
        return when {
            now != null && next != null -> "${now.title}  ${now.timeRange()}　→　${next.title}"
            now != null -> "${now.title}  ${now.timeRange()}"
            next != null -> "即将  ${next.title}  ${next.timeRange()}"
            else -> forChannel(channel).firstOrNull()?.let { "稍后  ${it.timeRange()}  ${it.title}" }
                ?: channel.group?.takeIf { it.isNotBlank() } ?: "暂无节目信息"
        }
    }

    fun progressPercent(programme: Programme): Int {
        val span = programme.stop - programme.start
        if (span <= 0L) return 0
        val done = effectiveNow() - programme.start
        return (done * 100L / span).toInt().coerceIn(0, 100)
    }

    /** 选中频道的节目时间轴（多行） */
    fun detail(channel: Channel): String {
        if (!isLoaded) return "尚未加载 XMLTV 节目单\n在「设置 → XMLTV 节目单」里填地址"
        val lines = ArrayList<String>()
        current(channel)?.let { p ->
            lines.add("正在  ${p.title}  ${progressPercent(p)}%")
            lines.add("       ${p.timeRange()}" + (p.category.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: ""))
            if (p.desc.isNotBlank()) lines.add("       ${p.desc.take(46)}")
        }
        for (p in upcoming(channel, 4)) {
            lines.add("稍后  ${p.timeRange()}  ${p.title}")
        }
        return if (lines.isEmpty()) {
            "该频道暂无节目数据\n检查 XMLTV 的 channel id 是否与 tvg-id 一致"
        } else {
            lines.joinToString("\n")
        }
    }
}
