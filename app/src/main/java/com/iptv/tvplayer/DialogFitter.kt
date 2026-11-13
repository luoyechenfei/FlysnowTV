package com.iptv.tvplayer

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView

/**
 * 电视上系统 AlertDialog 默认按屏宽固定比例铺开，短提示左右会留一大片空白。
 * 这里在弹窗显示后读取里面所有 TextView 的真实文字宽度（含标题、正文、列表项、按钮），
 * 取最长一行加上内边距与按钮栏宽度，动态设定窗口宽度；高度一律 WRAP_CONTENT，
 * 长文本自然换行、短文本不留白，最大值仍受屏宽限制以免超出安全区。
 *
 * 接收者是 Builder（链式调用 AlertDialog.Builder(...).setMessage(...).showFitting()），
 * 返回值仍是 AlertDialog，调用方可以照常持有 / dismiss。
 * 只用 decorView.post，不占用 setOnShowListener（设置页的输入框弹窗需要它拉起软键盘）。
 */
fun AlertDialog.Builder.showFitting(
    minWidthDp: Float = 210f,
    maxWidthRatio: Float = 0.82f
): AlertDialog {
    val dialog = show()
    val win = dialog.window ?: return dialog
    val dm = dialog.context.resources.displayMetrics
    val maxWidth = (dm.widthPixels * maxWidthRatio).toInt()
    val minWidth = (minWidthDp * dm.density).toInt()
    win.decorView.post {
        var needed = 0f
        collectTextViews(win.decorView) { tv ->
            val text = tv.text?.toString().orEmpty()
            if (text.isBlank()) return@collectTextViews
            val paint = tv.paint
            val widest = text.split('\n').maxOfOrNull { paint.measureText(it) } ?: 0f
            needed = maxOf(needed, widest + tv.paddingLeft + tv.paddingRight)
        }
        var buttons = 0f
        for (button in listOf(
            dialog.getButton(AlertDialog.BUTTON_POSITIVE),
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE),
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
        )) {
            if (button != null && button.visibility == View.VISIBLE) {
                buttons += button.width + 24f * dm.density
            }
        }
        val pad = 52f * dm.density
        val target = maxOf(needed + pad, buttons + pad, minWidth.toFloat())
            .toInt().coerceAtMost(maxWidth)
        win.setLayout(target, WindowManager.LayoutParams.WRAP_CONTENT)
    }
    return dialog
}

private fun collectTextViews(view: View, block: (TextView) -> Unit) {
    if (view is TextView) block(view)
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            collectTextViews(view.getChildAt(i), block)
        }
    }
}
