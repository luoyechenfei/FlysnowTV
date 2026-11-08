package com.iptv.tvplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * 第三方接口的 Token 管理。
 *
 * 用法：在接口地址、请求头、POST body 里写 `{token}`，本类会在请求前替换成当前值。
 * - 配置了 tokenUrl 的接口会按周期自动重新获取（JSON 路径 / 纯文本都支持）
 * - Token 与时间戳存在 SharedPreferences，进程内多个实例天然共享
 * - GET / POST、gzip、自定义请求头都支持；取 Token 的请求会自动剔除自引用的 {token} 头
 */
class TokenManager(context: Context) {

    companion object {
        const val PLACEHOLDER = "{token}"
        private const val PREFS_NAME = "tv_tokens"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var lastRefreshAt: Long = 0L
        private set

    fun current(): String = sp.getString("current", "").orEmpty()

    fun tokenFor(tokenUrl: String?): String =
        if (tokenUrl.isNullOrBlank()) current()
        else sp.getString("v:$tokenUrl", "")?.takeIf { it.isNotBlank() } ?: current()

    fun hasToken(): Boolean = current().isNotBlank()

    fun apply(text: String, tokenUrl: String? = null): String =
        if (text.contains(PLACEHOLDER)) text.replace(PLACEHOLDER, tokenFor(tokenUrl)) else text

    fun applyHeaders(headers: Map<String, String>, tokenUrl: String? = null): Map<String, String> =
        headers.mapValues { (_, value) -> apply(value, tokenUrl) }

    fun status(): String {
        val value = current()
        if (value.isBlank()) {
            return lastError?.let { "Token 获取失败：$it" } ?: "尚未获取 Token（接口里可写 {token} 占位）"
        }
        val shown = if (value.length > 10) value.take(6) + "…" + value.takeLast(4) else value
        val time = if (lastRefreshAt > 0L) {
            " · 本次会话 " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(lastRefreshAt))
        } else ""
        return "当前 Token：$shown$time"
    }

    /**
     * 遍历所有带 Token 接口的源，按各自周期（或全局周期）刷新。
     * 阻塞方法，必须在子线程调用；返回是否拿到了新 Token。
     */
    @Synchronized
    fun refreshAll(sources: List<SourceConfig>, force: Boolean, globalMinutes: Int): Boolean {
        var changed = false
        val now = System.currentTimeMillis()
        val visited = HashSet<String>()
        for (config in sources) {
            val endpoint = config.tokenUrl.trim()
            if (endpoint.isBlank() || !visited.add(endpoint)) continue

            val minutes = if (config.refreshMinutes > 0) {
                config.refreshMinutes
            } else {
                globalMinutes.coerceAtLeast(5)
            }
            val last = sp.getLong("t:$endpoint", 0L)
            val existing = sp.getString("v:$endpoint", "").orEmpty()
            if (!force && existing.isNotBlank() && now - last < minutes * 60_000L) continue

            val value = runCatching { fetchToken(config, endpoint) }
                .onFailure { lastError = it.message ?: "请求失败" }
                .getOrNull()

            if (!value.isNullOrBlank()) {
                sp.edit()
                    .putString("v:$endpoint", value)
                    .putLong("t:$endpoint", now)
                    .putString("current", value)
                    .apply()
                changed = true
                lastError = null
            }
        }
        if (changed) lastRefreshAt = System.currentTimeMillis()
        return changed
    }

    /** 主动刷新一次（设置页「立即刷新 Token」），返回是否成功 */
    @Synchronized
    fun forceRefresh(sources: List<SourceConfig>, globalMinutes: Int): Boolean {
        lastError = null
        return refreshAll(sources, force = true, globalMinutes = globalMinutes)
    }

    private fun fetchToken(config: SourceConfig, endpoint: String): String? {
        val url = apply(endpoint)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Encoding", "gzip")
            // 取 Token 时不能带自引用的 {token} 请求头
            for ((key, value) in config.headers()) {
                if (!value.contains(PLACEHOLDER)) setRequestProperty(key, value)
            }
            if (config.isPost) {
                requestMethod = "POST"
                doOutput = true
                val body = config.body.replace(PLACEHOLDER, "").trim()
                setRequestProperty(
                    "Content-Type",
                    if (body.startsWith("{")) "application/json; charset=UTF-8"
                    else "application/x-www-form-urlencoded; charset=UTF-8"
                )
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            } else {
                requestMethod = "GET"
            }
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                lastError = "HTTP $code"
                return null
            }
            val stream = if (connection.contentEncoding?.contains("gzip", true) == true) {
                GZIPInputStream(connection.inputStream)
            } else {
                connection.inputStream
            }
            val raw = stream.use { String(it.readBytes(), Charsets.UTF_8) }
            return extractToken(raw, config.tokenPath)
        } finally {
            connection.disconnect()
        }
    }

    /** 按点分路径取 JSON 字段，例如 data.token / result.0.access_token；留空则整段响应当 Token */
    private fun extractToken(raw: String, path: String): String? {
        val text = raw.trim().removePrefix("\uFEFF")
        if (text.isBlank()) return null
        if (path.isBlank()) return text.trim('"', '\n', '\r').take(1024)

        val root = runCatching { JSONTokener(text).nextValue() }.getOrNull()
            ?: return text.trim('"').take(1024)

        var node: Any? = root
        for (segment in path.split('.')) {
            val current = node
            node = when (current) {
                is JSONObject -> current.opt(segment)
                is JSONArray -> segment.toIntOrNull()?.let { current.opt(it) }
                else -> null
            }
            if (node == null || node == JSONObject.NULL) {
                lastError = "Token 路径 $path 未取到值"
                return null
            }
        }
        val value = when (node) {
            is String -> node
            is Number, is Boolean -> node.toString()
            else -> {
                lastError = "Token 路径 $path 不是文本"
                return null
            }
        }
        return value.trim().takeIf { it.isNotBlank() }
    }
}
