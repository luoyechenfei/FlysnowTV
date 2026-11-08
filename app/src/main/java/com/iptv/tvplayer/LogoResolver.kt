package com.iptv.tvplayer

import java.net.URLEncoder

/**
 * 台标来源解析。返回：
 *  - [String] 网络地址 → 交给 Coil 加载（自带内存/磁盘缓存）
 *  - [Int] 内置 drawable 资源 id → 直接 setImageResource
 *  - null → 显示频道名首字占位
 */
object LogoResolver {

    private val CCTV_ID = Regex("CCTV-?(\\d+\\+?)", RegexOption.IGNORE_CASE)

    fun resolve(channel: Channel, prefs: Prefs, forceLogo: Boolean = false): Any? {
        // 封面模式要求显示台标时（forceLogo），即便全局关闭了台标也仍解析台标
        val mode = if (forceLogo && prefs.logoMode == Prefs.LOGO_OFF) Prefs.LOGO_AUTO else prefs.logoMode
        return when (mode) {
            Prefs.LOGO_OFF -> null
            Prefs.LOGO_BUILTIN -> channel.logoRes.takeIf { it != 0 }
            Prefs.LOGO_CUSTOM -> customUrl(channel, prefs.logoTemplate)
                ?: channel.logoRes.takeIf { it != 0 }
            else -> when {
                !channel.logo.isNullOrBlank() -> channel.logo
                channel.logoRes != 0 -> channel.logoRes
                else -> null
            }
        }
    }

    /**
     * 模板占位符：
     *   {num}   频道号，如 5+、13
     *   {short} 紧凑小写名，如 cctv5plus、cctv13
     *   {name}  URL 编码后的完整频道名
     */
    private fun customUrl(channel: Channel, template: String): String? {
        if (template.isBlank()) return null
        val id = CCTV_ID.find(channel.name)?.groupValues?.get(1) ?: channel.number.toString()
        val short = "cctv" + id.lowercase().replace("+", "plus").replace("-", "")
        val encoded = runCatching { URLEncoder.encode(channel.name, "UTF-8") }.getOrDefault("")
        return template
            .replace("{num}", id)
            .replace("{short}", short)
            .replace("{name}", encoded)
            .trim()
            .takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }
}
