package com.iptv.tvplayer

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** 「更多分类」弹窗的一行 5 个分类卡片；当前分类前缀 ● 并置为 selected。 */
class MoreGroupAdapter(
    private val groups: List<String>,
    private val current: String?,
    private val onPick: (String) -> Unit
) : RecyclerView.Adapter<MoreGroupAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_more_group, parent, false) as TextView
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val group = groups[position]
        holder.bind(group, group == current)
    }

    override fun getItemCount(): Int = groups.size

    inner class Holder(private val chip: TextView) : RecyclerView.ViewHolder(chip) {
        init {
            chip.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    groups.getOrNull(position)?.let(onPick)
                }
            }
        }

        fun bind(label: String, selected: Boolean) {
            chip.text = (if (selected) "●  " else "") + label
            chip.isSelected = selected
        }
    }
}
