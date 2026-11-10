package com.iptv.tvplayer

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 盒子排障日志。电视上看不到 logcat，这里把关键的网络 / 解码 / 播放错误写到
 * App 内部文件，设置中心「播放诊断」可直接查看，也可以
 * adb shell cat /data/data/com.iptv.tvplayer/files/diag.log 取回。
 *
 * 起播 / 换台是热路径：日志写入走单线程后台队列，避免每次起播都在主线程
 * 「读整个旧文件 + 全文重写」，这是老盒子换台卡顿的一个隐性来源。
 */
object Diagnostics {

    private const val MAX_LINES = 160

    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "diag-writer").apply { isDaemon = true }
    }
    private val pending = ArrayDeque<String>()

    fun log(context: Context, tag: String, message: String) {
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        synchronized(pending) {
            pending.addLast("[$stamp][$tag] $message")
            while (pending.size > MAX_LINES) pending.removeFirst()
        }
        val app = context.applicationContext
        writer.execute { drain(app) }
    }

    private fun drain(context: Context) {
        val lines = synchronized(pending) {
            val copy = pending.toList()
            pending.clear()
            copy
        }
        if (lines.isEmpty()) return
        runCatching {
            val file = File(context.filesDir, "diag.log")
            val keep = (MAX_LINES - lines.size).coerceAtLeast(0)
            val old = if (file.exists() && keep > 0) file.readLines().takeLast(keep) else emptyList()
            file.writeText((old + lines).joinToString("\n") + "\n")
        }
    }

    /** 设置页 / 反馈报告读取前，把后台队列同步落盘，保证拿到最新记录 */
    private fun awaitFlush(context: Context) {
        runCatching { writer.submit(Runnable { drain(context.applicationContext) }).get() }
    }

    fun read(context: Context): String {
        awaitFlush(context)
        return runCatching {
            val file = File(context.filesDir, "diag.log")
            if (!file.exists()) "暂无诊断记录"
            else file.readText().trim().ifBlank { "暂无诊断记录" }
        }.getOrDefault("日志读取失败")
    }

    fun clear(context: Context) {
        synchronized(pending) { pending.clear() }
        runCatching { File(context.applicationContext.filesDir, "diag.log").writeText("") }
    }

    /** 生成一份完整排障日志，返回文件绝对路径（App 专属 Download 目录，无需存储权限） */
    fun export(context: Context, repository: PlaylistRepository): String {
        val now = Date()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(now)
        val name = "FlysnowTV_log_$stamp.txt"
        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val file = File(dir, name)
        val content = buildString {
            append("飞雪TV 排障日志\n")
            append("生成时间：")
                .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(now))
                .append("\n\n")
            append("【设备 / 网络栈】\n").append(device()).append("\n\n")
            append("【播放源 / 接口】\n")
            val sources = repository.sources
            if (sources.isEmpty()) append("（尚未添加）\n")
            for (config in sources) {
                append("- ").append(config.url).append("\n")
                append("  ").append(config.describe()).append("\n")
                if (config.headersText.isNotBlank()) {
                    append("  headers: ").append(config.headersText.replace("\n", " | ")).append("\n")
                }
                if (config.fieldsText.isNotBlank()) {
                    append("  fields: ").append(config.fieldsText).append("\n")
                }
            }
            append("\n【运行日志（接口拉取 / 起播 / 播放错误）】\n")
                .append(read(context)).append("\n\n")
            append("【崩溃日志】\n")
            val crash = File(context.filesDir, "crash.log")
            append(if (crash.exists()) crash.readText().takeLast(8000) else "（无）")
        }
        return runCatching {
            file.writeText(content)
            log(context, "EXPORT", "已生成日志文件：${file.absolutePath}")
            file.absolutePath
        }.getOrElse {
            val fallback = File(context.filesDir, name)
            runCatching { fallback.writeText(content) }
            fallback.absolutePath
        }
    }

    /** 设备与网络栈信息：盒子「能装不能播」时最需要先看这一段 */
    fun device(): String = buildString {
        append("Android ").append(Build.VERSION.RELEASE)
        append("  API ").append(Build.VERSION.SDK_INT)
        append("\n设备：").append(Build.MANUFACTURER).append(" / ").append(Build.MODEL)
        append("\nABI：").append(Build.SUPPORTED_ABIS.joinToString(", "))
        append("\n默认 TLS：").append(
            runCatching {
                javax.net.ssl.SSLContext.getDefault().supportedSSLParameters.protocols
                    .joinToString(", ")
            }.getOrDefault("未知")
        )
        append("\n已启用全局 TLS1.2 兼容：").append(TlsCompat.installed)
    }

    // ------------------------------------------------------------ 日志上报反馈

    /** 最新一份反馈报告：手机扫码页面从这里取，避免重复生成 / 参数传递 */
    fun latestFeedback(context: Context): String {
        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val file = File(dir, "FlysnowTV_latest_log.txt")
        return if (file.exists() && file.length() > 0) {
            runCatching { file.readText() }.getOrDefault("")
        } else ""
    }

    /**
     * 生成一份可外发的完整反馈报告。
     * 与 [export] 的区别：补上版本、用户填写的问题描述和 logcat 片段；
     * 并对订阅地址里的 Token / query 值、自定义 header 做脱敏，避免外发时泄露凭证。
     */
    fun feedbackReport(context: Context, repository: PlaylistRepository, note: String): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val (versionName, versionCode) = versionInfo(context)
        return buildString {
            append("飞雪TV 日志反馈\n")
            append("生成时间：").append(stamp).append("\n")
            append("版本：").append(versionName).append(" (").append(versionCode).append(")\n")
            append("反馈方式：扫码取日志 / 微信 / QQ 分享\n\n")

            append("【问题描述】\n")
                .append(scrubSecrets(note.trim().ifBlank { "（未填写）" })).append("\n\n")

            append("【设备 / 网络栈】\n")
                .append(device()).append("\n")
                .append(runCatching { DeviceProfile.summary(context) }.getOrDefault("（设备分级未知）"))
                .append("\n\n")

            append("【播放源（已脱敏）】\n")
            val sources = runCatching { repository.sources }.getOrDefault(emptyList())
            if (sources.isEmpty()) append("（尚未添加）\n")
            for (config in sources) {
                append("- ").append(scrubUrl(config.url)).append("\n")
                append("  ").append(scrubSecrets(config.describe())).append("\n")
                if (config.headersText.isNotBlank()) {
                    append("  headers: ")
                        .append(scrubSecrets(config.headersText.replace("\n", " | "))).append("\n")
                }
                if (config.fieldsText.isNotBlank()) {
                    append("  fields: ")
                        .append(scrubSecrets(config.fieldsText.replace("\n", " | "))).append("\n")
                }
            }

            append("\n【运行日志（接口拉取 / 起播 / 播放错误 / 换线路）】\n")
                .append(scrubSecrets(read(context))).append("\n\n")

            append("【崩溃日志】\n")
            val crash = File(context.filesDir, "crash.log")
            append(if (crash.exists()) crash.readText().takeLast(8000) else "（无）").append("\n\n")

            append("【logcat（本进程最近 200 行，已脱敏）】\n")
                .append(scrubSecrets(logcatTail())).append("\n")
        }
    }

    /** 把反馈报告写成可通过 FileProvider 分享的 .txt；同时留一份固定文件名便于取回 */
    fun saveFeedback(context: Context, text: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val file = File(dir, "FlysnowTV_feedback_$stamp.txt")
        file.writeText(text)
        runCatching { File(dir, "FlysnowTV_latest_log.txt").writeText(text) }
        log(context, "FEEDBACK", "已生成反馈日志：${file.name}（${text.length} 字）")
        return file
    }

    private fun versionInfo(context: Context): Pair<String, Long> = runCatching {
        val pm = context.packageManager
        val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode
        } else {
            pi.versionCode.toLong()
        }
        (pi.versionName.orEmpty().ifBlank { "未知" }) to code
    }.getOrDefault("未知" to -1L)

    /** 保留 scheme/host/port/path 便于排障，query 值与疑似 Token 的路径段一律打码 */
    private fun scrubUrl(raw: String): String = runCatching {
        val uri = android.net.Uri.parse(raw)
        if (uri.scheme.isNullOrBlank()) return scrubSecrets(raw)
        val safePath = uri.path.orEmpty().split("/").joinToString("/") { seg ->
            if (seg.length > 24 && seg.all { it.isLetterOrDigit() || it == '_' || it == '-' }) "***"
            else seg
        }
        val sb = StringBuilder()
            .append(uri.scheme).append("://").append(uri.authority.orEmpty()).append(safePath)
        val query = uri.query
        if (!query.isNullOrBlank()) {
            sb.append("?").append(query.split("&").joinToString("&") { part ->
                val key = part.substringBefore("=")
                if (key.isBlank()) "***" else "$key=***"
            })
        }
        sb.toString()
    }.getOrDefault("（地址解析失败）")

    /** 打码 key=value / key: value 形式的敏感字段，以及文本里残留的 URL query */
    private fun scrubSecrets(raw: String): String {
        var text = raw.replace(
            Regex("(?i)((?:token|authorization|auth|password|passwd|secret|api[_-]?key|access[_-]?key|cookie|session|sign|signature)\\s*[=:]\\s*)([^\\s,;&|\\n]+)")
        ) { it.groupValues[1] + "***" }
        text = text.replace(Regex("([?&])([A-Za-z0-9_\\-]+)=([^&\\s]+)")) {
            it.groupValues[1] + it.groupValues[2] + "=***"
        }
        return text
    }

    /** 普通应用从 Android 4.1 起只能读到自己进程的 logcat，不会外泄其它应用日志 */
    private fun logcatTail(): String = runCatching {
        val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "200", "-v", "time"))
        val text = proc.inputStream.bufferedReader().use { it.readText() }
        runCatching { proc.destroy() }
        text.lineSequence().filter { it.isNotBlank() }.toList().takeLast(200)
            .joinToString("\n").ifBlank { "（无本进程日志）" }
    }.getOrDefault("（logcat 不可用）")
}
