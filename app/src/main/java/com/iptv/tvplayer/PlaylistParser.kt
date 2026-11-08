package com.iptv.tvplayer

/**
 * 极简 M3U / M3U8 播放列表解析器。
 *
 * 支持：
 *  - #EXTM3U 头
 *  - #EXTINF:-1 tvg-name="" tvg-logo="" group-title="",频道名
 *  - #EXTGRP:分组
 *  - catchup / catchup-source / catchup-days 回看属性
 *  - #FX-ALT:备用线路（飞雪TV 缓存格式，其它播放器会当未知标签忽略）
 *  - 注释行与空行
 *
 * 单条 m3u8 视频（不是列表）会被识别成只有一个频道，同样可用。
 */
object PlaylistParser {

    private val ATTR_REGEX = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")

    fun parse(raw: String): List<Channel> {
        val channels = ArrayList<Channel>()
        if (raw.isBlank()) return channels

        var pendingName: String? = null
        var pendingLogo: String? = null
        var pendingId: String? = null
        var pendingCatchup: String? = null
        var pendingCatchupDays = 0
        var currentGroup = ""
        var number = 0

        for (rawLine in raw.lineSequence()) {
            val line = rawLine.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = HashMap<String, String>()
                    for (match in ATTR_REGEX.findAll(line)) {
                        attrs[match.groupValues[1].lowercase()] = match.groupValues[2]
                    }
                    attrs["group-title"]?.takeIf { it.isNotBlank() }?.let { currentGroup = it }
                    pendingLogo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
                    pendingId = attrs["tvg-id"]?.takeIf { it.isNotBlank() }
                    pendingCatchup = attrs["catchup-source"]?.takeIf { it.isNotBlank() }
                        ?: attrs["catchup"]?.takeIf {
                            it.isNotBlank() && (it.contains("{") || it.startsWith("http", true))
                        }
                    pendingCatchupDays = attrs["catchup-days"]?.toIntOrNull()?.coerceIn(0, 30) ?: 0

                    val comma = line.lastIndexOf(',')
                    pendingName = if (comma >= 0 && comma < line.length - 1) {
                        line.substring(comma + 1).trim().takeIf { it.isNotBlank() }
                    } else {
                        attrs["tvg-name"]?.takeIf { it.isNotBlank() }
                    }
                }

                line.startsWith("#EXTGRP", ignoreCase = true) -> {
                    line.substringAfter(':', "").trim().takeIf { it.isNotBlank() }?.let {
                        currentGroup = it
                    }
                }

                // 备用线路：附加到刚解析出的频道上
                line.startsWith("#FX-ALT:", ignoreCase = true) -> {
                    val alt = line.substringAfter(':').trim()
                    val lastIndex = channels.lastIndex
                    if (alt.isNotBlank() && lastIndex >= 0) {
                        val last = channels[lastIndex]
                        if (alt != last.url && !last.alternatives.contains(alt)) {
                            channels[lastIndex] = last.copy(
                                alternatives = last.alternatives + alt
                            )
                        }
                    }
                }

                // 其他 # 开头的是合法标签（#EXTVLCOPT、#KODIPROP 等），忽略
                line.startsWith("#") -> Unit

                // 其余视为地址
                else -> {
                    number++
                    channels.add(
                        Channel(
                            number = number,
                            name = pendingName ?: "频道 $number",
                            url = line,
                            logo = pendingLogo,
                            group = currentGroup.takeIf { it.isNotBlank() },
                            tvgId = pendingId,
                            catchupSource = pendingCatchup,
                            catchupDays = pendingCatchupDays
                        )
                    )
                    pendingName = null
                    pendingLogo = null
                    pendingId = null
                    pendingCatchup = null
                    pendingCatchupDays = 0
                }
            }
        }
        return channels
    }
}
