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
}
