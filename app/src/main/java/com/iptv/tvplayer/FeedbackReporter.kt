package com.iptv.tvplayer

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

/**
 * 「日志上报反馈」的分享通道。
 *
 * 电视盒子通常没有邮件客户端，所以不走邮件，改为：
 *  - 系统分享面板（微信 / QQ / 蓝牙 / 文件管理器等），日志同时作为文本与 .txt 附件；
 *  - 手机扫码取日志页（WebConfigServer 的 /log），复制或下载后再发微信 / QQ；
 *  - 剪贴板兜底：任何通道都会先把全文复制到剪贴板。
 */
object FeedbackReporter {

    private const val AUTHORITY = "com.iptv.tvplayer.fileprovider"
    const val WECHAT = "com.tencent.mm"
    const val QQ = "com.tencent.mobileqq"
    private const val MAX_INLINE = 4000

    /** 生成脱敏报告并保存成可分享的 .txt */
    fun build(context: Context, repository: PlaylistRepository, note: String): File =
        Diagnostics.saveFeedback(context, Diagnostics.feedbackReport(context, repository, note))

    private fun version(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "1.9.6" }

    /** 分享出去的正文：摘要 + 日志开头，避免微信 / QQ 对超长文本截断后丢失关键信息 */
    fun shareText(context: Context, file: File, note: String): String = buildString {
        append("【飞雪TV 运行日志反馈】\n")
        append("版本 v").append(version(context)).append("\n")
        append("设备 ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
            .append(" / Android ").append(Build.VERSION.RELEASE).append("\n")
        append("问题描述：").append(note.trim().ifBlank { "（未填写）" }).append("\n\n")
        append("完整日志见附件 ").append(file.name)
            .append("，订阅地址 / Token / Header 已脱敏。\n\n")
        append("—— 日志开头 ——\n")
        append(runCatching { file.readText() }.getOrDefault("").take(MAX_INLINE))
    }

    private fun uri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, AUTHORITY, file)

    /** 分享面板里可能出现多个 App，逐个授予附件读权限，否则微信 / QQ 打不开文件 */
    private fun grant(context: Context, intent: Intent, uri: Uri) {
        val list = resolve(context, intent)
        for (r in list) {
            runCatching {
                context.grantUriPermission(
                    r.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    private fun resolve(context: Context, intent: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }

    /**
     * 调起分享。target 非空时先尝试定向到微信 / QQ，未安装或失败则退回系统分享面板。
     * 返回 false 表示本机没有任何能接收分享的应用。
     */
    fun share(activity: Activity, file: File, note: String, target: String? = null): Boolean {
        val uri = uri(activity, file)
        val base = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "飞雪TV 运行日志 v${version(activity)}")
            putExtra(Intent.EXTRA_TEXT, shareText(activity, file, note))
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(activity.contentResolver, "飞雪TV 日志", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        if (!target.isNullOrBlank()) {
            val direct = Intent(base).setPackage(target)
            if (direct.resolveActivity(activity.packageManager) != null) {
                grant(activity, direct, uri)
                if (runCatching {
                        activity.startActivity(direct)
                        true
                    }.getOrDefault(false)) return true
            }
        }

        if (resolve(activity, base).isEmpty()) return false
        grant(activity, base, uri)
        return runCatching {
            activity.startActivity(Intent.createChooser(base, "分享日志到微信 / QQ"))
            true
        }.getOrDefault(false)
    }

    /** 剪贴板兜底：盒子没有任何分享应用时，用户仍可粘贴到手机微信 / QQ */
    fun copy(context: Context, text: String): Boolean = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("飞雪TV 运行日志", text))
        true
    }.getOrDefault(false)
}
