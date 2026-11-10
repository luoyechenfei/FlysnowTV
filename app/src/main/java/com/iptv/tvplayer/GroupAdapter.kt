package com.iptv.tvplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 海报墙上方的分组筛选条：第一个永远是“全部”（用空串占位）。
 */
class GroupAdapter(private val onSelect: (String?) -> Unit) :
    RecyclerView.Adapter<GroupAdapter.ChipHolder>() {

    private val items = ArrayList<String>()
    private var selected: String? = null

    fun submit(groups: List<String>) {
        items.clear()
        items.add("")
        items.addAll(groups)
        notifyDataSetChanged()
    }

    fun setSelected(group: String?) {
        if (group == selected) return
        selected = group
        notifyDataSetChanged()
    }

    /** 选中项在分组条中的位置；默认落在「最近观看」时把它滚进可见区域 */
    fun selectedPosition(): Int {
        val key = selected ?: ""
        return items.indexOfFirst { it == key }.coerceAtLeast(0)
    }

    /** 换源后旧分组可能已不存在，用来判断是否需要回退 */
    fun hasGroup(group: String?): Boolean = group == null || items.contains(group)

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_group_chip, parent, false) as TextView
        return ChipHolder(view)
    }

    override fun onBindViewHolder(holder: ChipHolder, position: Int) {
        val group = items[position]
        holder.bind(if (group.isBlank()) "全部" else group, group == selected || (group.isBlank() && selected == null))
    }

    inner class ChipHolder(textView: TextView) : RecyclerView.ViewHolder(textView) {
        private val chip: TextView = textView

        init {
            chip.setOnClickListener {
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                val group = items[position]
                onSelect(group.ifBlank { null })
            }
        }

        fun bind(label: String, isSelected: Boolean) {
            chip.text = label
            chip.isSelected = isSelected
        }
    }
}
