package com.iptv.tvplayer

/**
 * 一个频道。
 * [number] 从 1 开始，用于数字键直选。
 * [url] 为空表示没有直连流地址，此时用 [officialUrl] 跳转官方入口。
 * [tvgId] XMLTV 节目单按它匹配，同时也是收藏 / 最近观看的稳定标识。
 *
 * 1.2 新增：
 *  - [alternatives] 同一频道的备用线路，主线路失败时自动或手动切换；
 *  - [catchupSource] / [catchupDays] m3u 里 catchup-source 模板或带 {utc} 占位的回看地址。
 */
data class Channel(
    val number: Int,
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String? = null,
    val officialUrl: String? = null,
    /** 内置台标 drawable 资源 id，0 表示没有 */
    val logoRes: Int = 0,
    /** m3u 的 tvg-id / JSON 的频道 id */
    val tvgId: String? = null,
    /** 备用线路（与主线路不重复） */
    val alternatives: List<String> = emptyList(),
    /**
     * 与 [allUrls] 一一对应的来源标签（第一个是主线路）。
     * 多个订阅里有同名频道时，其余订阅的同名线路会挂到这里，标签是订阅的来源域名，
     * 供「换源」菜单里区分。为空时界面显示「线路 N」。
     */
    val lineLabels: List<String> = emptyList(),
    /** 回看地址模板，支持 {utc} / {lutc} / {timestamp} 等占位 */
    val catchupSource: String? = null,
    /** 源声明的可回看天数，0 = 未知 */
    val catchupDays: Int = 0
) {
    /** 主线路 + 所有备用线路，去重后的完整线路表 */
    fun allUrls(): List<String> = lines().map { it.first }

    /**
     * 线路表：地址 + 来源标签，已去重且顺序稳定（第一个是主线路）。
     * 标签可能为空，界面用 [lineLabel] 兜底成「线路 N」。
     */
    fun lines(): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>(alternatives.size + 1)
        fun add(url: String, label: String?) {
            if (url.isBlank()) return
            if (result.any { it.first == url }) return
            result.add(url to label.orEmpty().trim())
        }
        add(url, lineLabels.getOrNull(0))
        alternatives.forEachIndexed { index, alt -> add(alt, lineLabels.getOrNull(index + 1)) }
        return result
    }

    /** 「换源」菜单里显示的线路名：优先来源域名，其次「线路 N」 */
    fun lineLabel(index: Int): String {
        val label = lines().getOrNull(index)?.second.orEmpty().trim()
        if (label.isBlank()) return "线路 ${index + 1}"
        val short = label.removePrefix("www.")
        return if (short.length > 24) short.take(23) + "…" else short
    }

    fun hasMultiLine(): Boolean = allUrls().size > 1

    fun canCatchup(): Boolean = url.startsWith("http", ignoreCase = true)

    /**
     * 订阅里常混进内置 ExoPlayer 播不了的线路：组播 RTP 转发的裸 TS 流、
     * 需登录的鉴权接口、无后缀的数字路径等（实测 6384 条线路里只有约 30% 是 .m3u8）。
     * 这里把最可能可播的 HLS 线路提到主线路，其余按可播性稳定排序；
     * **不丢弃任何线路**（换源菜单、外部播放器仍能看到全部备用源）。
     * 只调整顺序，不动频道名 / 分组 / 台标，避免影响收藏与隐藏的稳定标识。
     */
    fun withPreferredLine(): Channel {
        val original = lines()
        if (original.size < 2) return this
        val ranked = original.sortedBy { lineScore(it.first) }
        if (ranked == original) return this
        return copy(
            url = ranked.first().first,
            alternatives = ranked.drop(1).map { it.first },
            lineLabels = ranked.map { it.second }
        )
    }

    companion object {
        /** 分数越小越可能播得出来；sortedBy 是稳定排序，同分保持订阅原顺序 */
        private fun lineScore(raw: String): Int {
            val lower = raw.lowercase()
            val path = lower.substringBefore('?')
            return when {
                path.endsWith(".m3u8") || lower.contains("format=hls") ||
                    lower.contains("format=m3u8") -> 0
                path.endsWith(".mp4") || path.endsWith(".ts") ||
                    path.endsWith(".flv") || path.endsWith(".mkv") -> 1
                // /rtp/组播IP:端口 这类是 UDP 组播转发的裸 TS 流，内置播放器基本解析不了
                lower.contains("/rtp/") || lower.contains("/udp/") -> 3
                else -> 2
            }
        }
    }
}
