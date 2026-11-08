package com.iptv.tvplayer

import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 回看 / 时移 URL 构造。
 *
 * 是否可用完全取决于播放源：
 *  - 标准 m3u 的 catchup-source 模板，常见占位 {utc} / {lutc} / {timestamp} / {offset}
 *  - 直播地址本身带 {utc}、{start} 等占位
 *  - 都没有时，退化为给直播地址追加 playseek= 参数（部分国内源支持）
 *
 * 源不支持时会播放失败并给出提示，不会破坏直播；按「回到直播」即可恢复。
 */
object Catchup {

    private const val HOUR = 3600_000L

    /** 时移菜单里的一档 */
    data class Option(val label: String, val start: Long)

    private fun utcFormatter() = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun localFormatter() = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())

    fun clock(time: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))

    /** 内置快捷档位 + XMLTV 里最近播过的节目 */
    fun options(channel: Channel, epg: EpgRepository?): List<Option> {
        val now = System.currentTimeMillis()
        val result = ArrayList<Option>()
        result.add(Option("回到直播（关闭时移）", 0L))
        result.add(Option("30 分钟前", now - HOUR / 2))
        result.add(Option("1 小时前", now - HOUR))
        result.add(Option("2 小时前", now - 2 * HOUR))
        result.add(Option("4 小时前", now - 4 * HOUR))

        val days = channel.catchupDays.coerceIn(0, 30)
        val earliest = if (days > 0) now - days * 24L * HOUR else now - 12 * HOUR
        val programmes = epg?.window(channel, earliest, now).orEmpty()
            .filter { it.start < now - 60_000L }
            .sortedByDescending { it.start }
            .take(8)
        for (p in programmes) {
            result.add(Option("${clock(p.start)}　${p.title}", p.start))
        }
        return result
    }

    /**
     * 把「想从哪个时刻开始看」转换成实际可播放地址。
     * [liveUrl] 用于模板是相对地址时补全，以及 playseek 兜底。
     */
    fun build(channel: Channel, startMillis: Long, liveUrl: String): String? {
        if (startMillis <= 0L) return liveUrl
        val template = channel.catchupSource?.takeIf { it.isNotBlank() } ?: liveUrl
        if (template.isBlank() || !liveUrl.startsWith("http", true)) return null

        val utc = utcFormatter().format(Date(startMillis))
        val local = localFormatter().format(Date(startMillis))
        val epoch = (startMillis / 1000L).toString()
        val offsetSec = ((System.currentTimeMillis() - startMillis) / 1000L).coerceAtLeast(0).toString()

        var replaced = template
            .replace("{utc}", utc, ignoreCase = true)
            .replace("{utctime}", utc, ignoreCase = true)
            .replace("{lutc}", local, ignoreCase = true)
            .replace("{local}", local, ignoreCase = true)
            .replace("{starttime}", local, ignoreCase = true)
            .replace("{start}", utc, ignoreCase = true)
            .replace("{timestamp}", epoch, ignoreCase = true)
            .replace("{utcsec}", epoch, ignoreCase = true)
            .replace("{offset}", offsetSec, ignoreCase = true)
            .replace("{Y}", utc.substring(0, 4), ignoreCase = true)
            .replace("{m}", utc.substring(4, 6), ignoreCase = true)
            .replace("{d}", utc.substring(6, 8), ignoreCase = true)
            .replace("{H}", utc.substring(8, 10), ignoreCase = true)
            .replace("{M}", utc.substring(10, 12), ignoreCase = true)
            .replace("{S}", utc.substring(12, 14), ignoreCase = true)

        if (replaced != template) {
            return if (replaced.startsWith("http", true)) {
                replaced
            } else {
                runCatching { URL(URL(liveUrl), replaced).toString() }
                    .getOrNull() ?: return null
            }
        }

        // 没有占位符：尝试常见的时移查询参数（需源端支持）
        val separator = if (liveUrl.contains("?")) "&" else "?"
        return liveUrl + separator + "playseek=" + local
    }

    /** 菜单里给用户的提示文案 */
    fun hint(channel: Channel): String = when {
        !channel.canCatchup() -> "该频道没有可直连的 http 地址"
        !channel.catchupSource.isNullOrBlank() ||
            channel.url.contains("{utc}", true) || channel.url.contains("{start", true) ->
            "源声明了回看模板，可按时移/节目单回看" +
                if (channel.catchupDays > 0) "（近 ${channel.catchupDays} 天）" else ""
        else -> "源未声明回看模板，将尝试 playseek 参数；是否可用取决于源端，失败会自动提示"
    }
}
