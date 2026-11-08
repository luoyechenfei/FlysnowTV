package com.iptv.tvplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 订阅备份：把所有播放源 / 第三方接口导出成一个可分享、可恢复的 JSON 文件，
 * 方便换机、备份、分享。导入时按地址去重合并，或整体替换。
 *
 * 备份文件结构（均用 SourceConfig 自身的 toJson / fromJson，向后兼容）：
 * {
 *   "app": "FlysnowTV",
 *   "type": "sources",
 *   "version": 1,
 *   "exportedAt": "2026-11-08 12:00:00",
 *   "count": 3,
 *   "sources": [ {SourceConfig...}, ... ]
 * }
 */
object BackupHelper {
    private const val APP_TAG = "FlysnowTV"
    private const val TYPE = "sources"
    private const val VERSION = 1

    /** 把所有订阅序列化成单文件 JSON（2 空格缩进，方便人眼看 / 手改） */
    fun toBackupJson(repository: PlaylistRepository): String {
        val sources = repository.sources
        val arr = JSONArray()
        for (s in sources) arr.put(s.toJson())
        return JSONObject().apply {
            put("app", APP_TAG)
            put("type", TYPE)
            put("version", VERSION)
            put(
                "exportedAt",
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            )
            put("count", sources.size)
            put("sources", arr)
        }.toString(2)
    }

    /**
     * 解析备份内容，返回可用订阅列表；解析失败 / 格式不匹配返回 null。
     * 兼容三种写法：完整导出格式、仅含 sources 字段的对象、纯数组。
     */
    fun fromBackupJson(text: String): List<SourceConfig>? {
        if (text.isBlank()) return null
        val arr: JSONArray? = try {
            val obj = JSONObject(text)
            obj.optJSONArray("sources")
        } catch (_: Exception) {
            // 可能不是对象，试试纯数组
            try {
                JSONArray(text)
            } catch (_: Exception) {
                null
            }
        }
        if (arr == null) return null
        if (arr.length() == 0) return emptyList()
        val list = ArrayList<SourceConfig>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val cfg = SourceConfig.fromJson(item)
            if (cfg.url.isNotBlank()) list.add(cfg)
        }
        return list
    }

    /** 把备份 JSON 写到 App 专属 Download 目录（无需存储权限），返回文件 */
    fun writeToDownloads(context: Context, name: String, json: String): File {
        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val file = File(dir, name)
        file.writeText(json)
        return file
    }
}
