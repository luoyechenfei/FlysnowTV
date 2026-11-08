package com.iptv.tvplayer

import android.net.Uri
import org.json.JSONObject
import java.util.UUID

/**
 * 一个播放源 / 第三方接口配置。
 *
 * [url]            m3u、m3u8 列表地址，或返回 JSON 的第三方接口地址，支持 {token} 占位
 * [headersText]    自定义请求头，每行 `Key: Value`，值里可以写 {token}
 * [fieldsText]     JSON 字段映射，例如 name=title,url=playUrl,group=category,root=data
 * [method]         GET / POST
 * [body]           POST 请求体，支持 {token}；以 `{` 开头自动按 JSON 发送
 * [tokenUrl]       Token 刷新接口；留空表示这个源不需要 Token
 * [tokenPath]      从 Token 响应里取值的 JSON 路径，例如 data.token；留空表示整个响应当 Token
 * [refreshMinutes] 这个源自己的 Token 刷新周期（分钟），0 表示跟随全局设置
 * [enabled]        是否启用；停用后不参与合并、不应用其请求头，方便临时屏蔽某个订阅
 */
data class SourceConfig(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val headersText: String = "",
    val fieldsText: String = "",
    val method: String = "GET",
    val body: String = "",
    val tokenUrl: String = "",
    val tokenPath: String = "",
    val refreshMinutes: Int = 0,
    val enabled: Boolean = true
) {

    fun headers(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (line in headersText.lines()) {
            val index = line.indexOf(':')
            if (index <= 0) continue
            val key = line.substring(0, index).trim()
            val value = line.substring(index + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) result[key] = value
        }
        return result
    }

    fun fields(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (part in fieldsText.split(',', '\n')) {
            val index = part.indexOf('=')
            if (index <= 0) continue
            val key = part.substring(0, index).trim()
            val value = part.substring(index + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) result[key] = value
        }
        return result
    }

    val isPost: Boolean get() = method.equals("POST", ignoreCase = true)

    val hasToken: Boolean get() = tokenUrl.isNotBlank()

    fun label(): String {
        val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
        if (host.isNotBlank()) return host
        // 本地文件 / 无域名地址：用文件名当标签，比整条路径好读
        return url.substringAfterLast('/').ifBlank { url }
    }

    /** 设置中心与手机配置页里的接口摘要 */
    fun describe(): String = buildString {
        append(if (isPost) "POST" else "GET")
        append("  ·  ")
        append(label())
        if (headersText.isNotBlank()) append("  ·  自定义请求头")
        if (hasToken) {
            append("  ·  Token ")
            append(if (refreshMinutes > 0) "$refreshMinutes 分钟" else "跟随全局")
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("url", url)
        put("headers", headersText)
        put("fields", fieldsText)
        put("method", method)
        put("body", body)
        put("tokenUrl", tokenUrl)
        put("tokenPath", tokenPath)
        put("refreshMinutes", refreshMinutes)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(json: JSONObject): SourceConfig = SourceConfig(
            id = json.optString("id", UUID.randomUUID().toString()),
            url = json.optString("url", ""),
            headersText = json.optString("headers", ""),
            fieldsText = json.optString("fields", ""),
            method = json.optString("method", "GET").ifBlank { "GET" },
            body = json.optString("body", ""),
            tokenUrl = json.optString("tokenUrl", ""),
            tokenPath = json.optString("tokenPath", ""),
            refreshMinutes = json.optInt("refreshMinutes", 0),
            enabled = json.optBoolean("enabled", true)
        )
    }
}
