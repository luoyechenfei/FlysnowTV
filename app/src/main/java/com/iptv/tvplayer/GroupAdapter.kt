package com.iptv.tvplayer

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * 海报墙上方的分类条：
 * - 第一个永远是「全部」（空串占位）；
 * - 只显示全屏放得下的分类，放不下的收进末尾固定的「更多」chip；
 * - 从「更多」里选中的分类会被临时固定到可见区，保证当前分类始终可见。
 */
class GroupAdapter(
    private val context: Context,
    private val onSelect: (String?) -> Unit,
    /** 点击「更多」：把溢出分类与当前选中项交给 Activity 弹菜单 */
    private val onMore: (List<String>, String?) -> Unit
) : RecyclerView.Adapter<GroupAdapter.ChipHolder>() {

    /** 全部分类（含首位空串「全部」） */
    private val all = ArrayList<String>()
    /** 当前真正显示在分类条上的分类 */
    private val visible = ArrayList<String>()
    /** 收进「更多」的分类 */
    private var overflow: List<String> = emptyList()
    private var selected: String? = null
    private var availableWidth = -1

    private val density: Float = context.resources.displayMetrics.density
    private fun dp(value: Float): Int = (value * density + 0.5f).toInt()

    fun submit(groups: List<String>) {
        all.clear()
        all.add("")
        all.addAll(groups)
        recompute()
    }

    /** 分类条完成布局后传入可用像素宽度；宽度变化（分辨率/横竖屏）时自动重排 */
    fun setAvailableWidth(widthPx: Int) {
        if (widthPx <= 0 || widthPx == availableWidth) return
        availableWidth = widthPx
        recompute()
    }

    fun setSelected(group: String?) {
        if (group == selected) return
        selected = group
        recompute()
    }

    /** 选中项在可见分类条中的位置；被收进「更多」时返回「更多」chip 的位置 */
    fun selectedPosition(): Int {
        val key = selected ?: ""
        val index = visible.indexOfFirst { it == key }
        if (index >= 0) return index
        return if (overflow.isNotEmpty()) visible.size else 0
    }

    /** 换源后旧分组可能已不存在，用来判断是否需要回退 */
    fun hasGroup(group: String?): Boolean = group == null || all.contains(group)

    fun overflowGroups(): List<String> = overflow.toList()

    private fun label(group: String): String = if (group.isBlank()) "全部" else group

    /** 用与 item_group_chip.xml 相同的字号/内边距测量每个 chip 的实际占宽 */
    private fun measureChip(text: String): Int {
        val probe = TextView(context)
        probe.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        probe.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        val pad = dp(20f)
        probe.setPadding(pad, 0, pad, 0)
        probe.text = text
        probe.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(dp(38f), View.MeasureSpec.EXACTLY)
        )
        return probe.measuredWidth + dp(10f) + dp(4f)
    }

    private fun recompute() {
        visible.clear()
        if (availableWidth <= 0) {
            // 还没完成布局：先按原行为全量显示，布局完成后会再重排一次
            visible.addAll(all)
            overflow = emptyList()
            notifyDataSetChanged()
            return
        }
        val totalWidth = all.sumOf { measureChip(label(it)) }
        if (totalWidth <= availableWidth) {
            visible.addAll(all)
            overflow = emptyList()
            notifyDataSetChanged()
            return
        }
        // 预留「更多」chip 的宽度，逐个放入直到放不下为止
        val budget = (availableWidth - measureChip("更多 · 99") - dp(6f)).coerceAtLeast(dp(72f))
        val base = ArrayList<String>()
        var used = 0
        for (group in all) {
            val width = measureChip(label(group))
            if (base.isNotEmpty() && used + width > budget) break
            base.add(group)
            used += width
            if (base.size == 1 && width > budget) break
        }
        if (base.size >= all.size) {
            visible.addAll(base)
            overflow = emptyList()
        } else {
            val rest = all.subList(base.size, all.size).toMutableList()
            val current = selected
            // 选中的溢出分类替换掉最后一个可见项，让高亮始终留在分类条上；
            // 至少保留「全部 + 两个虚拟分组」，避免把收藏 / 最近观看挤进更多。
            if (current != null && rest.contains(current) && base.size >= 4) {
                rest.remove(current)
                val displaced = base.removeAt(base.size - 1)
                base.add(current)
                rest.add(0, displaced)
            }
            visible.addAll(base)
            overflow = rest
        }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int =
        visible.size + if (overflow.isNotEmpty()) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_group_chip, parent, false) as TextView
        return ChipHolder(view)
    }

    override fun onBindViewHolder(holder: ChipHolder, position: Int) {
        if (position == visible.size && overflow.isNotEmpty()) {
            holder.bindMore("更多 · ${overflow.size}", selected != null && overflow.contains(selected))
            return
        }
        val group = visible.getOrNull(position) ?: ""
        holder.bind(
            label(group),
            group == selected || (group.isBlank() && selected == null)
        )
    }

    inner class ChipHolder(textView: TextView) : RecyclerView.ViewHolder(textView) {
        private val chip: TextView = textView

        init {
            chip.setOnClickListener {
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                if (position == visible.size && overflow.isNotEmpty()) {
                    onMore(overflow.toList(), selected)
                } else {
                    onSelect((visible.getOrNull(position) ?: "").ifBlank { null })
                }
            }
        }

        fun bind(label: String, isSelected: Boolean) {
            chip.setBackgroundResource(R.drawable.bg_chip)
            ContextCompat.getColorStateList(chip.context, R.color.chip_text)
                ?.let { chip.setTextColor(it) }
            chip.text = label
            chip.isSelected = isSelected
        }

        fun bindMore(label: String, isSelected: Boolean) {
            chip.setBackgroundResource(R.drawable.bg_chip_more)
            ContextCompat.getColorStateList(chip.context, R.color.more_chip_text)
                ?.let { chip.setTextColor(it) }
            chip.text = label
            chip.isSelected = isSelected
        }
    }
}
