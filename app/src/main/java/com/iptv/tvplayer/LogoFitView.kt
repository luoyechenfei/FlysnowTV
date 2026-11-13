package com.iptv.tvplayer

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/**
 * 台标专用 ImageView（1.9.9：圆角恢复原样，只负责「完整显示 + 尺寸封顶」）。
 *
 * 历史：1.9.7 之前台标直角满铺；1.9.7/1.9.8 尝试给台标加圆角（位图变换 / 显示层
 * outline 裁剪），但真机观感是「矩形台标的四角被裁掉、显示不全」——按用户要求
 * 圆角全部移除，台标按原图原样显示。
 *
 * 现在只保留大小控制（在显示层完成，不依赖任何外部时序）：
 *  - scaleType=MATRIX：按 drawable 真实尺寸与自身可用区域计算等比缩放，
 *    显示尺寸永远 ≤ 本框 78%（放大封顶 2 倍防发虚）；
 *  - 不做任何裁剪、不裁圆角——任何来源的台标都**完整显示**、不溢出台标槽；
 *  - onSizeChanged / setImageDrawable 双入口自动重算，网络图 / 内置资源 /
 *    加载失败回退共用同一入口。
 */
class LogoFitView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        applyLogo()
    }

    override fun setImageResource(resId: Int) {
        super.setImageResource(resId)
        applyLogo()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyLogo()
    }

    /** 按当前 drawable 与自身尺寸重算等比缩放矩阵：完整显示、≤ 框 78%、放大 ≤ 2 倍 */
    fun applyLogo() {
        val d = drawable ?: return
        val iw = d.intrinsicWidth
        val ih = d.intrinsicHeight
        val aw = width - paddingLeft - paddingRight
        val ah = height - paddingTop - paddingBottom
        if (iw <= 0 || ih <= 0 || aw <= 0 || ah <= 0) return

        // fitCenter 的「顶满」比例；显示尺寸一律再压到框的 78% 以内，放大最多 2 倍
        val fill = min(aw.toFloat() / iw, ah.toFloat() / ih)
        var s = fill * LOGO_MAX_RATIO
        if (s > LOGO_MAX_UPSCALE) s = LOGO_MAX_UPSCALE
        if (s <= 0f) return

        val dispW = iw * s
        val dispH = ih * s
        // 必须新建 Matrix：setImageMatrix 只在矩阵变化时才刷新，复用内部实例不会触发重绘
        // 平移量把 padding 一并算入（ImageView 对 MATRIX 不另加 padding 偏移）
        val m = Matrix()
        m.setScale(s, s)
        m.postTranslate(
            paddingLeft + (aw - dispW) / 2f,
            paddingTop + (ah - dispH) / 2f
        )
        imageMatrix = m
    }

    companion object {
        /** 显示尺寸上限：台标槽的 78%，保证完整显示、与槽协调 */
        private const val LOGO_MAX_RATIO = 0.78f

        /** 小图最多放大 2 倍，避免低分辨率台标放大后发虚 */
        private const val LOGO_MAX_UPSCALE = 2f
    }
}
