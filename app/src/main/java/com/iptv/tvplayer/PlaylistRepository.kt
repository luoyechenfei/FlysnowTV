package com.iptv.tvplayer

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * 多播放源仓库：m3u 列表、第三方 JSON 接口、单个流地址都可以作为「源」并存。
 * 支持 GET / POST、自定义请求头、{token} 占位与自动 Token 刷新。
 * 所有方法都是阻塞的，必须在子线程调用。
 */
class PlaylistRepository(
    private val context: Context,
    val tokens: TokenManager = TokenManager(context)
) {

    companion object {
        private const val PREFS_NAME = "tv_iptv"
        const val KEY_LAST_INDEX = "last_index"
        private const val KEY_SOURCES = "sources"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** 台号后面可以忽略的尾部词（`CCTV1 综合` ≈ `CCTV-1`） */
        private val FAMILY_SUFFIX = setOf("综合", "频道", "台", "直播")
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, "playlist_cache.m3u")

    /** 已添加的所有播放源 / 接口 */
    var sources: MutableList<SourceConfig>
        get() {
            val raw = prefs.getString(KEY_SOURCES, "").orEmpty()
            if (raw.isBlank()) return ArrayList()
            val result = ArrayList<SourceConfig>()
            runCatching {
                val array = JSONArray(raw)
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index)
                    if (item != null) {
                        val config = SourceConfig.fromJson(item)
                        if (config.url.isNotBlank()) result.add(config)
                    }
                }
            }
            return result
        }
        set(value) {
            val array = JSONArray()
            for (config in value) array.put(config.toJson())
            prefs.edit().putString(KEY_SOURCES, array.toString()).apply()
        }

    val hasSources: Boolean get() = sources.isNotEmpty()

    var lastIndex: Int
        get() = prefs.getInt(KEY_LAST_INDEX, 0)
        set(value) {
            prefs.edit().putInt(KEY_LAST_INDEX, value).apply()
        }

    fun addSource(config: SourceConfig) {
        val list = sources
        list.removeAll { it.url == config.url }
        list.add(config)
        sources = list
    }

    /** 按 id 更新一个已有源（保留顺序；若新地址与别的源重复则去掉重复项） */
    fun updateSource(config: SourceConfig) {
        val list = sources
        val withoutOld = list.filter { it.id != config.id }
        val deduped = withoutOld.filter { it.url != config.url }
        val result = ArrayList(deduped)
        result.add(config)
        sources = result
    }

    /** 在列表里上移 / 下移，用于控制多源合并的优先级 */
    fun moveSource(id: String, up: Boolean): Boolean {
        val list = ArrayList(sources)
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return false
        val target = if (up) index - 1 else index + 1
        if (target < 0 || target >= list.size) return false
        val a = list[index]
        list[index] = list[target]
        list[target] = a
        sources = list
        return true
    }

    fun removeSource(id: String) {
        val list = sources
        list.removeAll { it.id == id }
        sources = list
    }

    /** 汇总所有启用源的请求头，播放视频时也要带上（Token / Referer 常常是必需的） */
    fun allHeaders(): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        for (config in sources) if (config.enabled) merged.putAll(config.headers())
        return tokens.applyHeaders(merged)
    }

    fun label(): String {
        val list = sources
        return when {
            list.isEmpty() -> "未添加订阅"
            list.size == 1 -> list[0].label()
            else -> "${list.size} 个源"
        }
    }

    /** 拉取并合并所有启用的源；全部失败时回退到本地缓存 */
    fun load(): List<Channel> {
        val all = sources
        if (all.isEmpty()) throw IllegalStateException("尚未添加播放源或接口")
        val configs = all.filter { it.enabled }
        if (configs.isEmpty()) {
            val cached = readCache()
            if (cached.isNotEmpty()) {
                Diagnostics.log(context, "SOURCE", "所有接口都已停用，改用本地缓存 ${cached.size} 个频道")
                return cached
            }
            throw IllegalStateException("所有接口都处于停用状态，请到设置里启用或新增订阅")
        }

        // 按每个源自己的周期（或全局周期）补齐 Token，再发请求
        tokens.refreshAll(configs, force = false, globalMinutes = Prefs(context).tokenRefreshMinutes)

        val merged = ArrayList<Channel>()
        val errors = ArrayList<String>()
        // 跨订阅合并：同名 / 同 tvg-id 的频道只留一个卡片，其余订阅的同名线路挂成备用
        val byId = HashMap<String, Int>()
        val byName = HashMap<String, Int>()
        val byFamily = HashMap<String, Int>()
        var rawCount = 0

        for (config in configs) {
            val label = config.label()
            try {
                val parsed = parseSource(config, fetch(config))
                if (parsed.isEmpty()) {
                    errors.add("$label 未解析到频道")
                    continue
                }
                rawCount += parsed.size
                for (channel in parsed) {
                    val hit = findMerged(byId, byName, byFamily, channel)
                    if (hit == null) {
                        merged.add(channel.copy(lineLabels = listOf(label)))
                        registerKeys(byId, byName, byFamily, channel, merged.lastIndex)
                    } else {
                        merged[hit] = mergeLines(merged[hit], channel, label)
                    }
                }
            } catch (error: Exception) {
                errors.add("$label：${error.message ?: "请求失败"}")
                Diagnostics.log(context, "SOURCE", "拉取失败 ${config.url} → ${error.message}")
            }
        }

        if (merged.isEmpty()) {
            val cached = readCache()
            if (cached.isNotEmpty()) {
                Diagnostics.log(context, "SOURCE", "全部接口失败，改用本地缓存 ${cached.size} 个频道")
                return cached
            }
            throw IOException(errors.joinToString("；").ifBlank { "所有接口都未返回有效频道" })
        }

        Diagnostics.log(
            context, "SOURCE",
            "加载成功，共 ${merged.size} 个频道 / ${configs.size} 个启用源" +
                if (rawCount > merged.size) "（原始 $rawCount 条，同名合并 ${rawCount - merged.size} 条）" else ""
        )
        // 分组名去重：`卫视` 与 `卫视频道`、`央视` 与 `央视 ` 归成同一个筛选入口
        val groupNames = LinkedHashMap<String, String>()
        val normalized = merged.map { channel ->
            val raw = channel.group?.trim()
            if (raw.isNullOrBlank()) {
                channel.copy(group = "其他")
            } else {
                val key = normalizeKey(raw).ifBlank { raw }
                val display = groupNames.getOrPut(key) { raw }
                channel.copy(group = display)
            }
        }
        val renumbered = normalized.mapIndexed { index, channel -> channel.copy(number = index + 1) }
        writeCache(renumbered)
        return renumbered
    }

    /** 在已合并的列表里找同名频道：tvg-id → 归一化名称 → CCTV 台号族 */
    private fun findMerged(
        byId: Map<String, Int>,
        byName: Map<String, Int>,
        byFamily: Map<String, Int>,
        channel: Channel
    ): Int? {
        channel.tvgId?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()?.let { byId[it] }?.let { return it }
        val nameKey = normalizeKey(channel.name)
        if (nameKey.isNotEmpty()) {
            byName[nameKey]?.let { return it }
            familyKey(nameKey)?.let { byFamily[it] }?.let { return it }
        }
        return null
    }

    /** 登记新卡片的所有匹配键，供后续源命中 */
    private fun registerKeys(
        byId: MutableMap<String, Int>,
        byName: MutableMap<String, Int>,
        byFamily: MutableMap<String, Int>,
        channel: Channel,
        index: Int
    ) {
        // 不用 putIfAbsent：老盒子 API < 24 上没有这个方法
        fun keep(map: MutableMap<String, Int>, key: String) {
            if (key.isNotEmpty() && !map.containsKey(key)) map[key] = index
        }
        channel.tvgId?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()?.let { keep(byId, it) }
        val nameKey = normalizeKey(channel.name)
        if (nameKey.isNotEmpty()) {
            keep(byName, nameKey)
            familyKey(nameKey)?.let { keep(byFamily, it) }
        }
    }

    /**
     * CCTV 类台号族键：`CCTV-1`、`CCTV1 综合`、`cctv01` 都收敛到 `cctv1`。
     * 台号后面必须没有实质区分词（欧洲 / 美洲 / 4K 等），避免误合并两个不同频道。
     */
    private fun familyKey(normalized: String): String? {
        val match = Regex("^(cctv|央视)(\\d{1,2})(\\+?)(.*)$").find(normalized) ?: return null
        val suffix = match.groupValues[4]
        if (suffix.isNotEmpty() && suffix !in FAMILY_SUFFIX) return null
        return "cctv" + match.groupValues[2] + match.groupValues[3]
    }

    /**
     * 把另一个源里的同名频道并进来：主线路保持不变（优先级高的源），
     * 其余地址按顺序追加成备用线路，并记下来源标签供「换源」菜单显示。
     */
    private fun mergeLines(base: Channel, extra: Channel, label: String): Channel {
        val current = ArrayList(base.lines())
        var added = false
        for (url in extra.allUrls()) {
            if (url.isBlank() || current.any { it.first == url }) continue
            current.add(url to label)
            added = true
        }
        // 完全重复的列表：只补全缺失的台标 / 节目单信息，不产生多余线路
        if (!added) {
            return base.copy(
                logo = base.logo?.takeIf { it.isNotBlank() } ?: extra.logo,
                tvgId = base.tvgId?.takeIf { it.isNotBlank() } ?: extra.tvgId,
                catchupSource = base.catchupSource?.takeIf { it.isNotBlank() } ?: extra.catchupSource,
                catchupDays = if (base.catchupDays > 0) base.catchupDays else extra.catchupDays
            )
        }
        return base.copy(
            url = current.first().first,
            alternatives = current.drop(1).map { it.first },
            lineLabels = current.map { it.second },
            logo = base.logo?.takeIf { it.isNotBlank() } ?: extra.logo,
            group = base.group?.takeIf { it.isNotBlank() } ?: extra.group,
            tvgId = base.tvgId?.takeIf { it.isNotBlank() } ?: extra.tvgId,
            catchupSource = base.catchupSource?.takeIf { it.isNotBlank() } ?: extra.catchupSource,
            catchupDays = if (base.catchupDays > 0) base.catchupDays else extra.catchupDays
        )
    }

    /**
     * 一键导入前的真实校验：拉一次并按内容解析，返回（频道数, 说明）。
     * 公开给 SubscriptionImporter 与手机配置页使用；阻塞方法，须在子线程调用。
     */
    fun probe(config: SourceConfig): Pair<Int, String> = try {
        val text = fetch(config)
        val list = parseSource(config, text)
        if (list.isEmpty()) {
            0 to "返回 ${text.length} 字节，但未识别到频道（内容：${text.take(48).replace("\n", " ")}）"
        } else {
            list.size to "按${if (ApiPlaylistParser.isJson(text.trim())) "JSON" else "列表"}解析成功"
        }
    } catch (error: Exception) {
        0 to (error.message ?: "请求失败（${error.javaClass.simpleName}）")
    }

    /** 按内容自动判断是 m3u、JSON 接口、纯文本还是单个流地址 */
    private fun parseSource(config: SourceConfig, rawText: String): List<Channel> {
        val text = rawText.trim().removePrefix("\uFEFF")
        if (text.isEmpty()) return emptyList()

        // 聚合 m3u 头部常带 x-tvg-url，顺手把 XMLTV 节目单地址也配好
        if (text.startsWith("#EXTM3U", true)) {
            Regex("x-tvg-url=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.getOrNull(1)
                ?.takeIf { it.startsWith("http", true) }
                ?.let { url ->
                    val p = Prefs(context)
                    if (p.epgUrl.isBlank()) {
                        p.epgUrl = url
                        Diagnostics.log(context, "EPG", "订阅自带 x-tvg-url，已自动填入：$url")
                    }
                }
        }

        return when {
            ApiPlaylistParser.isJson(text) ->
                ApiPlaylistParser.parse(text, config.url, config.fields())

            text.startsWith("#EXTM3U", true) || text.contains("#EXTINF", true) ->
                PlaylistParser.parse(text)

            // 纯文本 / .txt 聚合（diyp 格式）：分组行「名称,#genre#」+「频道名,地址」
            else -> {
                val list = ArrayList<Channel>()
                val urlRegex = Regex("https?://[^\\s\"'<>]+")
                var group = ""
                for (line in text.lines()) {
                    val s = line.trim()
                    if (s.isEmpty() || s.startsWith("//")) continue
                    if (s.contains("#genre#", true)) {
                        group = s.substringBefore(",").trim()
                        continue
                    }
                    if (s.startsWith("#")) continue
                    val match = urlRegex.find(s) ?: continue
                    val url = match.value.trimEnd(',', ';', '|', ')')
                    val name = s.substring(0, match.range.first)
                        .trim().trimEnd(',', '|', ':', '-', '，', '、')
                        .ifBlank { "频道 ${list.size + 1}" }
                    list.add(Channel(list.size + 1, name, url, group = group.ifBlank { null }))
                }
                list
            }
        }
    }

    private fun fetch(config: SourceConfig): String {
        val source = tokens.apply(config.url, config.tokenUrl)
        // 本地文件（需已授予存储权限），主要用途是调试
        if (source.startsWith("file://") || source.startsWith("/")) {
            val path = source.removePrefix("file://")
            val file = File(path)
            if (!file.exists() || !file.isFile) throw IOException("文件不存在：$path")
            return decode(file.readBytes())
        }

        val headers = tokens.applyHeaders(config.headers(), config.tokenUrl)
        val connection = (URL(source).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Encoding", "gzip")
            for ((key, value) in headers) setRequestProperty(key, value)
            if (config.isPost) {
                requestMethod = "POST"
                doOutput = true
                val body = tokens.apply(config.body, config.tokenUrl)
                if (!headers.keys.any { it.equals("Content-Type", true) }) {
                    setRequestProperty(
                        "Content-Type",
                        if (body.trim().startsWith("{")) "application/json; charset=UTF-8"
                        else "application/x-www-form-urlencoded; charset=UTF-8"
                    )
                }
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            } else {
                requestMethod = "GET"
            }
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val stream = if (connection.contentEncoding?.contains("gzip", true) == true) {
                GZIPInputStream(connection.inputStream)
            } else {
                connection.inputStream
            }
            return decode(stream.use { it.readBytes() })
        } finally {
            connection.disconnect()
        }
    }

    private fun writeCache(channels: List<Channel>) {
        val builder = StringBuilder("#EXTM3U\n")
        for (channel in channels) {
            val group = channel.group.orEmpty().replace("\"", "")
            val id = channel.tvgId.orEmpty().replace("\"", "")
            val logo = channel.logo.orEmpty().replace("\"", "")
            val catchup = channel.catchupSource.orEmpty().replace("\"", "")
            builder.append("#EXTINF:-1 tvg-id=\"")
                .append(id)
                .append("\" tvg-logo=\"")
                .append(logo)
                .append("\" group-title=\"")
                .append(group)
                .append("\"")
            if (catchup.isNotBlank()) {
                builder.append(" catchup-source=\"").append(catchup).append("\"")
            }
            if (channel.catchupDays > 0) {
                builder.append(" catchup-days=\"").append(channel.catchupDays).append("\"")
            }
            builder.append(",")
                .append(channel.name.replace("\n", " "))
                .append("\n")
                .append(channel.url)
                .append("\n")
            for (alt in channel.alternatives) {
                if (alt.isNotBlank() && alt != channel.url) {
                    builder.append("#FX-ALT:").append(alt).append("\n")
                }
            }
        }
        runCatching { cacheFile.writeText(builder.toString()) }
    }

    private fun readCache(): List<Channel> =
        if (cacheFile.exists() && cacheFile.length() > 0L) {
            runCatching { PlaylistParser.parse(cacheFile.readText()) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

    /** 国内不少 m3u 是 GBK 编码，UTF-8 解出乱码时再试 GBK */
    private fun decode(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) return utf8
        return runCatching { String(bytes, charset("GBK")) }.getOrDefault(utf8)
    }
}
