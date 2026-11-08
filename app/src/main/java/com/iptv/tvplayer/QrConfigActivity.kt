package com.iptv.tvplayer

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 手机扫码配置页：在盒子上启动局域网配置服务，把地址生成二维码。
 * 手机扫码后打开内嵌网页，填接口 / 设置并保存，电视端 SharedPreferences 立即更新。
 */
class QrConfigActivity : AppCompatActivity() {

    private lateinit var server: WebConfigServer
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr)

        val pinView = findViewById<TextView>(R.id.qrPin)
        val urlView = findViewById<TextView>(R.id.qrUrl)
        statusView = findViewById(R.id.qrStatus)
        val qrImage = findViewById<ImageView>(R.id.qrImage)

        val pin = (1000..9999).random().toString()
        Prefs(this).webPin = pin
        pinView.text = pin

        val ip = WebConfigServer.localIp()
        val url = "http://$ip:${Prefs.WEB_CONFIG_PORT}/?pin=$pin"
        urlView.text = url

        val bitmap = makeQrCode(url)
        if (bitmap != null) {
            qrImage.setImageBitmap(bitmap)
        } else {
            statusView.text = "二维码生成失败，可在手机浏览器直接输入上面的地址"
        }

        server = WebConfigServer(this, pin) { message ->
            statusView.text = message
        }
        server.start()
    }

    override fun onDestroy() {
        if (::server.isInitialized) server.stop()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun makeQrCode(content: String, size: Int = 620): Bitmap? = try {
        val hints = HashMap<EncodeHintType, Any>()
        hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
        hints[EncodeHintType.MARGIN] = 1
        val matrix: BitMatrix = MultiFormatWriter()
            .encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val row = y * size
            for (x in 0 until size) {
                pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        bitmap
    } catch (ignored: Exception) {
        null
    }
}
