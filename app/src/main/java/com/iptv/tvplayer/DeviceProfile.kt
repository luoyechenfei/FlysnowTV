package com.iptv.tvplayer

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.webkit.WebView

/**
 * 自动识别盒子能力，决定 HLS 起播与解码策略（全部频道均为自带订阅直连播放）。
 *
 * 新盒子：内存足够 → HLS 走快速免分片起播，缩短首帧。
 * 老盒子：内存小 / API 低 → HLS 走标准路径 + 解码器软解回退，保证「能播」而不是黑屏。
 *
 * 判定结果常驻内存，写进诊断日志。
 */
object DeviceProfile {

    enum class Tier { MODERN, LEGACY, NO_WEBVIEW }

    data class Info(
        val tier: Tier,
        val sdk: Int,
        val webViewMajor: Int,
        val ramMb: Long,
        val reason: String
    )

    @Volatile
    private var cached: Info? = null

    fun info(context: Context): Info = cached ?: detect(context).also { cached = it }

    /** 老盒子关闭 HLS chunkless preparation，避免起播直接失败 */
    fun useChunkless(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 24 && info(context).tier == Tier.MODERN

    /** 软解回退始终开启：硬解不支持时至少还能播 */
    fun allowDecoderFallback(): Boolean = true

    fun summary(context: Context): String {
        val i = info(context)
        return when (i.tier) {
            Tier.MODERN ->
                "新盒子方案　·　API ${i.sdk} / WebView Chrome ${i.webViewMajor} / ${i.ramMb}MB\n" +
                    "HLS 快速起播 · 解码软解兜底"
            Tier.LEGACY ->
                "老盒子方案　·　API ${i.sdk} / WebView Chrome ${i.webViewMajor} / ${i.ramMb}MB\n" +
                    "标准 HLS 起播 · 解码软解兜底\n原因：${i.reason}"
            Tier.NO_WEBVIEW ->
                "兼容方案　·　API ${i.sdk} / ${i.ramMb}MB\n" +
                    "标准 HLS 起播 · 解码软解兜底\n原因：${i.reason}"
        }
    }

    private fun detect(context: Context): Info {
        val sdk = Build.VERSION.SDK_INT
        val ram = totalRamMb(context)
        val major = webViewMajor(context, sdk)
        val webViewPresent = webViewPresent(context)

        val tier = when {
            !webViewPresent || major < 0 -> Tier.NO_WEBVIEW
            major >= 70 && sdk >= 24 && (ram == 0L || ram >= 1024L) -> Tier.MODERN
            else -> Tier.LEGACY
        }
        val reason = when (tier) {
            Tier.MODERN -> "系统 WebView 版本足够新"
            Tier.LEGACY -> buildString {
                if (major in 0..69) append("WebView Chrome $major 过旧；")
                if (sdk < 24) append("系统 API $sdk 偏低；")
                if (ram in 1..1023) append("内存仅 ${ram}MB；")
                append("使用标准 HLS 起播")
            }
            Tier.NO_WEBVIEW -> "系统未提供可用 WebView 组件"
        }
        return Info(tier, sdk, major, ram, reason)
    }

    private fun webViewPresent(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        // 常见 WebView 包名；命中任意一个即认为组件存在
        listOf(
            "com.google.android.webview", "com.android.webview",
            "com.android.chrome", "com.tencent.mtt", "com.UCMobile.intl"
        ).any { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
        } || Build.VERSION.SDK_INT >= 24
    }.getOrDefault(true)

    private fun webViewMajor(@Suppress("UNUSED_PARAMETER") context: Context, sdk: Int): Int {
        if (Build.VERSION.SDK_INT >= 26) {
            val pkg = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
            if (pkg != null) {
                val major = pkg.versionName?.substringBefore(".")?.toIntOrNull()
                if (major != null) return major
                return 0
            }
        }
        // API < 26 拿不到 WebView 版本，按系统版本保守估计
        return when {
            sdk >= 26 -> 60
            sdk >= 24 -> 58
            sdk >= 21 -> 40
            else -> 30
        }
    }

    private fun totalRamMb(context: Context): Long = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0L
        val memory = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        memory.totalMem / 1024L / 1024L
    }.getOrDefault(0L)
}
