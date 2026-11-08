package com.iptv.tvplayer

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URL

/**
 * 通用第三方 JSON 接口解析器（零依赖，用系统自带 org.json）。
 *
 * 自动识别常见结构：
 *   - 顶层数组 [{name,url}, ...]
 *   - 对象里任意层级数组 {data:[...]} / {result:{list:[...]}} / {rows:[...]}
 *   - 纯字典 {"CCTV1":"http://.../1.m3u8", ...}
 *
 * 字段名也做了一批常见别名兜底，遇到特殊接口可用 fieldsText 覆盖：
 *   name=xxx,url=xxx,logo=xxx,group=xxx,root=xxx
 */
object ApiPlaylistParser {

    private val NAME_KEYS = listOf(
        "name", "title", "channel", "channelName", "channel_name",
        "tvg_name", "tvg-name", "label", "cnName", "text"
    )
    private val URL_KEYS = listOf(
        "url", "stream", "streamUrl", "stream_url", "playUrl", "play_url",
        "playurl", "link", "m3u8", "hls", "src", "uri", "address"
    )
    private val LOGO_KEYS = listOf("logo", "icon", "tvg_logo", "tvg-logo", "pic", "image", "cover")
    private val GROUP_KEYS = listOf("group", "groupTitle", "group_title", "category", "type", "tag", "class")
    private val ID_KEYS = listOf("tvgId", "tvg_id", "tvg-id", "channelId", "channel_id", "epgId", "id", "key")

    fun isJson(text: String): Boolean {
        val value = text.trim().removePrefix("\uFEFF")
        return value.startsWith("{") || value.startsWith("[")
    }

    fun parse(text: String, baseUrl: String, mapping: Map<String, String>): List<Channel> {
        val value = runCatching {
            JSONTokener(text.trim().removePrefix("\uFEFF")).nextValue()
        }.getOrNull() ?: return emptyList()

        val arrays = ArrayList<JSONArray>()
        collectArrays(value, arrays, 0)

        // root=xxx 可以显式指定频道数组在哪个字段
        val rootKey = mapping["root"]
        if (rootKey != null && value is JSONObject) {
            val picked = value.opt(rootKey)
            if (picked is JSONArray) {
                arrays.add(0, picked)
            } else if (picked is JSONObject) {
                collectArrays(picked, arrays, 0)
            }
        }

        val channels = ArrayList<Channel>()
        for (array in arrays) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = pick(item, mapping["name"], NAME_KEYS)
                val rawUrl = pickUrl(item, mapping["url"])
                if (name.isBlank() || rawUrl.isBlank()) continue
                val url = resolveUrl(baseUrl, rawUrl)
                if (url.isBlank()) continue
                val logo = pick(item, mapping["logo"], LOGO_KEYS).takeIf { it.isNotBlank() }
                val group = pick(item, mapping["group"], GROUP_KEYS).takeIf { it.isNotBlank() }
                val tvgId = pick(item, mapping["tvgId"], ID_KEYS).takeIf { it.isNotBlank() }
                channels.add(
                    Channel(
                        number = channels.size + 1,
                        name = name,
                        url = url,
                        logo = logo,
                        group = group,
                        tvgId = tvgId
                    )
                )
            }
            if (channels.isNotEmpty()) break
        }

        // 兼容 {"频道名": "http://.../x.m3u8"} 字典结构
        if (channels.isEmpty() && value is JSONObject) {
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val url = value.optString(key, "")
                if (url.startsWith("http", ignoreCase = true)) {
                    channels.add(Channel(channels.size + 1, key, url))
                }
            }
        }
        return channels
    }

    private fun collectArrays(value: Any?, out: MutableList<JSONArray>, depth: Int) {
        if (depth > 5 || value == null) return
        when (value) {
            is JSONArray -> {
                out.add(value)
                for (index in 0 until minOf(value.length(), 400)) {
                    val child = value.opt(index)
                    if (child is JSONObject) collectArrays(child, out, depth + 1)
                }
            }
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    collectArrays(value.opt(keys.next()), out, depth + 1)
                }
            }
        }
    }

    private fun pick(item: JSONObject, override: String?, keys: List<String>): String {
        val candidates = if (override.isNullOrBlank()) keys else listOf(override) + keys
        for (key in candidates) {
            val value = item.opt(key) ?: continue
            if (value is String) {
                val text = value.trim()
                if (text.isNotEmpty() && !text.equals("null", true)) return text
            }
        }
        return ""
    }

    /** 地址也可能嵌在 {"stream": {"hls": "..."}} 里 */
    private fun pickUrl(item: JSONObject, override: String?): String {
        val direct = pick(item, override, URL_KEYS)
        if (direct.isNotEmpty()) return direct
        for (key in URL_KEYS) {
            val nested = item.optJSONObject(key) ?: continue
            val inner = pick(nested, null, URL_KEYS)
            if (inner.isNotEmpty()) return inner
        }
        return ""
    }

    /** 接口返回相对地址时，按接口地址补全 */
    private fun resolveUrl(baseUrl: String, raw: String): String {
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true) ||
            raw.startsWith("rtmp", true) || raw.startsWith("//")
        ) {
            return if (raw.startsWith("//")) "https:$raw" else raw
        }
        return runCatching { URL(URL(baseUrl), raw).toString() }.getOrDefault("")
    }
}
