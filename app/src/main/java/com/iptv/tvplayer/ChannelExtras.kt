package com.iptv.tvplayer

/**
 * 频道辅助：收藏 / 最近观看的稳定标识、虚拟分组，以及频道名归一化
 * （用于 XMLTV 节目单按名称互相匹配）。
 *
 * 应用不内置任何频道，全部频道来自用户自行导入的订阅源。
 */
object Library {

    /** 虚拟分组：我的收藏（固定排在「最近观看」之前，不属于任何源的 group-title） */
    const val GROUP_FAVORITE = "★ 我的收藏"

    /** 虚拟分组：最近观看 */
    const val GROUP_RECENT = "⏱ 最近观看"

    fun isVirtual(group: String?): Boolean =
        group == GROUP_FAVORITE || group == GROUP_RECENT
}

/**
 * 收藏 / 最近观看 / 节目预约的稳定标识：
 * 优先用 m3u 的 tvg-id（换线路、换源都不会丢收藏），没有时退回频道名。
 */
fun Channel.stableKey(): String =
    tvgId?.takeIf { it.isNotBlank() } ?: name

/**
 * 归一化频道名 / XMLTV id，便于各种写法互相匹配：
 * CCTV-1、CCTV1、cctv01、CCTV5+、cctv5plus 都能收敛到同一个键。
 */
fun normalizeKey(raw: String): String {
    var s = raw.lowercase()
        .replace(Regex("[\\s\\-_·（）()【】\\[\\]:：]"), "")
        .replace("高清", "")
        .replace("超清", "")
        .replace("标清", "")
        .replace("hd", "")
        .replace("频道", "")
        .replace("中央", "央视")
        .replace("plus", "+")
    // cctv01 → cctv1，cctv05+ → cctv5+
    s = Regex("^cctv0+(\\d)").replace(s, "cctv$1")
    return s
}

/** 取字符串里的第一段数字，用于 CCTV 类频道的宽松匹配（cctv5+ / cctv13）。 */
fun numberToken(raw: String): Int? =
    Regex("(\\d{1,3})").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
