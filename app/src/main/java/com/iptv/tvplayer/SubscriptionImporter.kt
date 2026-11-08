package com.iptv.tvplayer

import android.content.Context
import android.net.Uri

/**
 * 「一键导入订阅链接」：给一个（或多个）聚合 .m3u / .m3u8 / .txt / JSON / 任意接口地址，
 * 不需要复制列表内容，也不需要填请求方式、字段映射等任何额外参数。
 *
 * - 自动从一段文本里识别所有 URL：支持多行批量、`名称,URL`、`名称|URL`、带注释、缺 scheme
 * - 按返回内容自动判断格式（仓现有的 parseSource 已支持 m3u / JSON / 纯文本 / 单流）
 * - 导入前逐个真实拉取并校验，只有能解析出频道的才写入，失败给出具体原因
 * - 最多 20 条，避免误把一整份 m3u 内容当成 20 个订阅源
 */
object SubscriptionImporter {

    const val MAX_URLS = 20

    data class Item(
        val url: String,
        val ok: Boolean,
        val channels: Int,
        val detail: String
    )

    private val URL_RX = Regex("https?://[^\\s\"'<>\\[\\]]+", RegexOption.IGNORE_CASE)

    /** 从任意输入里提取并清洗订阅链接 */
    fun normalize(raw: String): List<String> {
        val result = LinkedHashSet<String>()

        val found = URL_RX.findAll(raw).map { it.value }.toList()
        if (found.isNotEmpty()) {
            for (candidate in found) {
                if (result.size >= MAX_URLS) break
                val cleaned = clean(candidate)
                if (cleaned != null) result.add(cleaned)
            }
        } else {
            // 没写 http:// 的裸域名，例如 example.com/list.m3u
            for (line in raw.lines()) {
                if (result.size >= MAX_URLS) break
                var s = line.trim().trim('"', '\'', '`').trim()
                if (s.isEmpty() || s.startsWith("#") || s.startsWith("//")) continue
                if (!s.startsWith("http", true) && s.contains('.') && s.contains('/')) {
                    s = "https://$s"
                }
                val cleaned = clean(s)
                if (cleaned != null) result.add(cleaned)
            }
        }
        return result.toList()
    }

    private fun clean(value: String): String? {
        var s = value.trim()
            .trimEnd(',', ';', '|', ')', '>', ']', '}', '.')
            .trim('"', '\'', '`')
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) return null
        // 去掉常见的中文/英文说明尾巴：`https://x.m3u 名称` 由正则已按空白切断
        val uri = runCatching { Uri.parse(s) }.getOrNull() ?: return null
        val host = uri.host.orEmpty()
        if (host.isBlank()) return null
        if (!host.contains('.') && !host.equals("localhost", true)) return null
        if (s.length > 1024) s = s.substring(0, 1024)
        return s
    }

    /**
     * 阻塞方法，必须在子线程调用。
     * [replace] 为 true 时先清空已有源，适合换新的聚合订阅。
     */
    fun import(
        context: Context,
        repository: PlaylistRepository,
        raw: String,
        replace: Boolean = false,
        headersText: String = ""
    ): List<Item> {
        val urls = normalize(raw)
        if (urls.isEmpty()) {
            return listOf(Item("", false, 0, "没有识别到链接。请粘贴 http:// 或 https:// 开头的订阅地址"))
        }
        // 误把整份列表内容粘进来时给个明确提示（.m3u / .txt diyp 都算）
        if (raw.contains("#EXTM3U", true) || raw.contains("#EXTINF", true) ||
            raw.contains("#genre#", true)
        ) {
            return listOf(
                Item(
                    "", false, 0,
                    "这看起来是列表内容而不是订阅链接。请用「导入 m3u 文本」；" +
                        "这里只填订阅地址（每行一个，支持 .m3u / .m3u8 / .txt）"
                )
            )
        }

        val items = ArrayList<Item>()
        val valid = ArrayList<SourceConfig>()
        for (url in urls) {
            val config = SourceConfig(url = url, headersText = headersText)
            val probe = repository.probe(config)
            val ok = probe.first > 0
            if (ok) valid.add(config)
            items.add(Item(url, ok, probe.first, probe.second))
            Diagnostics.log(
                context, "IMPORT",
                "${if (ok) "OK" else "FAIL"} ${probe.first} 频道 $url ${probe.second}"
            )
        }

        // 只有至少一个链接真的可用才写库；「替换」时若全部失败则保留原配置，避免把源清空
        if (valid.isNotEmpty()) {
            if (replace) repository.sources = ArrayList()
            for (config in valid) repository.addSource(config)
            Prefs(context).notifySourceChanged()
        } else if (replace) {
            Diagnostics.log(context, "IMPORT", "全部链接失败，保留原有接口不清空")
        }
        return items
    }

    /** 把导入结果拼成给用户看的多行反馈 */
    fun summary(items: List<Item>): String = buildString {
        val ok = items.count { it.ok }
        val channels = items.sumOf { it.channels }
        append("导入完成：成功 $ok/${items.size} 个链接，共 $channels 个频道\n\n")
        for (item in items) {
            append(if (item.ok) "✓ " else "✗ ")
            append(item.url.take(70))
            append("\n   ")
            if (item.ok) append("解析到 ${item.channels} 个频道，已保存")
            else append(item.detail)
            append("\n")
        }
        if (ok > 0) append("\n返回频道墙即自动加载。")
        else append("\n常见原因：链接已失效、需要 Referer/Token（请用「添加接口（URL）」配置）、或盒子网络不通。")
    }
}
