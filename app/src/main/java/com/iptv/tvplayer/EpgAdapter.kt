package com.iptv.tvplayer

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * 左侧节目单（频道清单 + XMLTV 当前/后续节目）。
 * 选中项由遥控器上下键手动控制，不依赖 View 焦点，避免与播放态上下键换台冲突。
 */
class EpgAdapter(
    private val onPick: (Int) -> Unit,
    private val guide: (Channel) -> String,
    private val isFavorite: (Channel) -> Boolean
) : RecyclerView.Adapter<EpgAdapter.Holder>() {

    private val items = ArrayList<Channel>()
    private var selected = 0
    private var playingUrl: String? = null

    fun submit(list: List<Channel>, index: Int, currentUrl: String?) {
        items.clear()
        items.addAll(list)
        selected = index.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        playingUrl = currentUrl
        notifyDataSetChanged()
    }

    fun updateSelection(index: Int, currentUrl: String?) {
        selected = index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        playingUrl = currentUrl
        notifyDataSetChanged()
    }

    fun refreshGuide() = notifyDataSetChanged()

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_epg_row, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val channel = items[position]
        val fav = isFavorite(channel)
        holder.number.text = String.format("%02d", channel.number)
        holder.name.text = channel.name
        // 独立星标列：已收藏实心金色 ★，未收藏空心淡蓝 ☆，长按收藏 / 取消后即时刷新
        holder.fav.text = if (fav) "★" else "☆"
        holder.fav.setTextColor(Color.parseColor(if (fav) "#FFD36B" else "#6F86AE"))
        holder.program.text = guide(channel)
        holder.meta.text = buildString {
            append(channel.group?.takeIf { it.isNotBlank() } ?: "未分组")
            append(" · ")
            append(if (channel.url.isBlank()) "官方入口" else "直连播放")
        }
        holder.now.visibility = if (channel.url == playingUrl && channel.url.isNotBlank()) {
            View.VISIBLE
        } else {
            View.GONE
        }
        holder.itemView.background = if (position == selected) {
            ContextCompat.getDrawable(holder.itemView.context, R.drawable.bg_epg_row_selected)
        } else {
            null
        }
    }

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val number: TextView = itemView.findViewById(R.id.epgNumber)
        val name: TextView = itemView.findViewById(R.id.epgName)
        val program: TextView = itemView.findViewById(R.id.epgProgram)
        val meta: TextView = itemView.findViewById(R.id.epgMeta)
        val fav: TextView = itemView.findViewById(R.id.epgFav)
        val now: TextView = itemView.findViewById(R.id.epgNow)

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onPick(position)
            }
        }
    }
}
