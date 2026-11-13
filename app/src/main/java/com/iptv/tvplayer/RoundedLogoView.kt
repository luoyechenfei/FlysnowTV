package com.iptv.tvplayer

import android.content.Context
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 台标专用 ImageView（1.9.8 重做）：
 *
 * 圆角与大小全部在【显示层】一次性处理，替换两套各有短板的旧方案：
 *
 *  - 旧方案 A（clipToOutline + 透明圆角背景 shape）：outline 取自 background，
 *    而系统对 translucent 背景拒绝按 outline 裁剪，多数 TV ROM 上无效；
 *  - 旧方案 B（Coil RoundedCornersTransformation）：半径作用在解码位图上（≤320px），
 *    位图随后被 fitCenter 缩到几十~一百多 px 的显示尺寸，半径跟着等比缩小，
 *    1080p 电视上只剩 2~5px，肉眼几乎看不见——这就是「电视上不是圆角」的直接原因；
 *    大小缩放依赖 applyCardSize 时序回填改 LayoutParams，任何时序没走到就回退成
 *    match_parent + fitCenter，大台标顶满整个槽——「溢出框」的直接原因。
 *
 * 现在的做法：
 *  1) scaleType=MATRIX：按 drawable 真实尺寸与自身可用区域计算缩放矩阵，显示尺寸
 *     永远 ≤ 本框 78%（放大封顶 2 倍），任何来源的大图都不可能溢出，也不依赖任何
 *     外部时序（onSizeChanged / setImageDrawable 两个入口自动重算）；
 *  2) 显式 ViewOutlineProvider + clipToOutline：outline 不取自背景，由代码直接
 *     setRoundRect（alpha=255 必然可裁），圆角矩形精确对齐内容显示区域，半径按
 *     【显示尺寸】动态计算（≤12dp），与位图解码尺寸无关——每个台标圆角粗细一致、
 *     必然可见；RenderThread outline 裁剪自带抗锯齿，CardView 同款机制，跨 TV ROM 可靠。
 */
class RoundedLogoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private var cornerRadiusPx = 0f
    private var clipL = 0
    private var clipT = 0
    private var clipR = 0
    private var clipB = 0

    private val logoOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            if (cornerRadiusPx > 0f && clipR > clipL && clipB > clipT) {
                outline.setRoundRect(clipL, clipT, clipR, clipB, cornerRadiusPx)
            } else {
                outline.setEmpty()
            }
        }
    }

    init {
        scaleType = ScaleType.MATRIX
        outlineProvider = logoOutline
        clipToOutline = true
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

    /** 按当前 drawable 与自身尺寸重算：等比缩放矩阵 + 内容区圆角 outline */
    fun applyLogo() {
        val d = drawable ?: run { resetClip(); return }
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
        val m = Matrix()
        m.setScale(s, s)
        m.postTranslate(
            paddingLeft + (aw - dispW) / 2f,
            paddingTop + (ah - dispH) / 2f
        )
        imageMatrix = m

        // 圆角半径按【显示尺寸】算：内容大 → 12dp 封顶；内容小 → 等比缩小，始终协调
        val density = resources.displayMetrics.density
        cornerRadiusPx = min(12f * density, min(dispW, dispH) * 0.22f).coerceAtLeast(2f)

        clipL = (paddingLeft + (aw - dispW) / 2f).roundToInt()
        clipT = (paddingTop + (ah - dispH) / 2f).roundToInt()
        clipR = clipL + dispW.roundToInt()
        clipB = clipT + dispH.roundToInt()
        invalidateOutline()
    }

    private fun resetClip() {
        cornerRadiusPx = 0f
        invalidateOutline()
    }

    companion object {
        /** 显示尺寸上限：台标槽的 78%，保证显示完整、与槽协调 */
        private const val LOGO_MAX_RATIO = 0.78f

        /** 小图最多放大 2 倍，避免低分辨率台标放大后发虚 */
        private const val LOGO_MAX_UPSCALE = 2f
    }
}
