package com.iptv.tvplayer

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 盒子排障日志。电视上看不到 logcat，这里把关键的网络 / 解码 / 播放错误写到
 * App 内部文件，设置中心「播放诊断」可直接查看，也可以
 * adb shell cat /data/data/com.iptv.tvplayer/files/diag.log 取回。
 */
object Diagnostics {

    private const val MAX_LINES = 160

    fun log(context: Context, tag: String, message: String) {
        runCatching {
            val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val file = File(context.filesDir, "diag.log")
            val old = if (file.exists()) file.readLines().takeLast(MAX_LINES) else emptyList()
            file.writeText((old + "[$stamp][$tag] $message").joinToString("\n") + "\n")
        }
    }

    fun read(context: Context): String = runCatching {
        val file = File(context.filesDir, "diag.log")
        if (!file.exists()) "暂无诊断记录"
        else file.readText().trim().ifBlank { "暂无诊断记录" }
    }.getOrDefault("日志读取失败")

    fun clear(context: Context) {
        runCatching { File(context.filesDir, "diag.log").writeText("") }
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
}
