package com.iptv.tvplayer

import android.content.Context
import android.os.Build
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 老盒子（Android 5.x / 6.x，API 21~22 及以下）默认【不启用 TLSv1.2】，
 * 而几乎所有 https 直播源与接口都要求 TLS1.2。表现就是：
 * 电脑模拟器（新 WebView / 新 TLS 栈）能播，盒子握手直接失败、一个频道都出不来。
 *
 * 这里在 Application 启动时把 TLS1.2 显式打开，并挂到全局 HttpsURLConnection，
 * ExoPlayer 的 DefaultHttpDataSource 也会走这个默认 SSLSocketFactory。
 */
object TlsCompat {

    @Volatile
    var installed = false
        private set

    @Volatile
    var detail = ""
        private set

    fun install(context: Context) {
        // 有 Google Play 服务的设备顺带升级安全提供器（模拟器/部分盒子有效）
        runCatching {
            val clazz = Class.forName("com.google.android.gms.security.ProviderInstaller")
            clazz.getMethod("installIfNeeded", Context::class.java).invoke(null, context)
            detail = "ProviderInstaller 已更新"
        }

        if (Build.VERSION.SDK_INT > 22) {
            if (detail.isEmpty()) detail = "系统原生支持 TLS1.2（API ${Build.VERSION.SDK_INT}）"
            installed = true
            return
        }

        runCatching {
            val sslContext = SSLContext.getInstance("TLSv1.2")
            sslContext.init(null, null, null)
            HttpsURLConnection.setDefaultSSLSocketFactory(Tls12Factory(sslContext.socketFactory))
            installed = true
            detail = "已为 API ${Build.VERSION.SDK_INT} 强制启用 TLS1.2" +
                if (this.detail.isEmpty()) "" else "；${this.detail}"
        }.onFailure {
            detail = "TLS1.2 启用失败：${it.message}"
        }
    }

    private class Tls12Factory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        private fun patch(socket: Socket): Socket {
            if (socket is SSLSocket) {
                runCatching {
                    socket.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.1", "TLSv1")
                }
            }
            return socket
        }

        override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
            patch(delegate.createSocket(s, host, port, autoClose))

        override fun createSocket(host: String?, port: Int): Socket =
            patch(delegate.createSocket(host, port))

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            patch(delegate.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            patch(delegate.createSocket(host, port))

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            patch(delegate.createSocket(address, port, localAddress, localPort))
    }
}
