package com.iptv.tvplayer

import android.app.Application
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 启动初始化：
 * - 启用老盒子的 TLS1.2（否则所有 https 直播源都会握手失败，表现为「装得上、完全播不了」）
 * - 轻量崩溃日志：adb shell cat /data/data/com.iptv.tvplayer/files/crash.log
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        TlsCompat.install(this)
        Diagnostics.log(this, "BOOT", Diagnostics.device().replace("\n", " | "))
        Diagnostics.log(this, "PROFILE", DeviceProfile.summary(this).replace("\n", " | "))

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val stamp = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", Locale.getDefault()
                ).format(Date())
                val trace = Log.getStackTraceString(error)
                File(filesDir, "crash.log").appendText(
                    "[$stamp] thread=${thread.name}\n$trace\n\n"
                )
                Diagnostics.log(this, "CRASH", trace.lineSequence().firstOrNull().orEmpty())
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
