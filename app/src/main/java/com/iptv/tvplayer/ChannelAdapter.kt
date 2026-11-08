package com.iptv.tvplayer

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.imageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.transform.RoundedCornersTransformation

/**
 * 深色海报墙的频道卡片适配器。
 *
 * 1.2 性能版：卡片里只留一个空的 [previewHost]，全局唯一的预览 PlayerView
 * 在聚焦时由 MainActivity 动态挂进来，避免一屏几十个 TextureView。
 * 聚焦动画去掉 elevation 阴影，台标加载去掉 crossfade，减少盒子 GPU 负担。
 */
class ChannelAdapter(
    private val onSelect: (Channel) -> Unit,
    private val prefs: Prefs,
    /** fromHover：true = 鼠标/飞鼠悬停触发，false = 遥控器焦点触发 */
    private val onFocus: (Channel?, FrameLayout?, Boolean) -> Unit,
    private val onToggleFavorite: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.CardHolder>() {

    companion object {
        private const val LONG_PRESS_MS = 650L
    }

    private val items = ArrayList<Channel>()
    private var currentUrl: String? = null
    private val handler = Handler(Looper.getMainLooper())

    /**
     * 当前正在显示预览小窗的卡片宿主，由 MainActivity 在挂载/摘除时同步。
     * 用于避免 RecyclerView 复用重绑（滚动、notifyItemChanged、鼠标移动触发重排）时
     * 把正在播放的预览小窗清掉——这是「鼠标一动预览就消失」的直接原因。
     */
    var activePreviewHost: FrameLayout? = null

    fun submit(list: List<Channel>) {
        // 列表内容没变就不重绘：onResume / 分组切换时避免全量 rebind 和台标重复加载
        if (items == list) return
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** 标记“正在播放”的卡片 */
    fun setCurrent(url: String?) {
        if (url == currentUrl) return
        val previous = currentUrl
        currentUrl = url
        positionOf(previous).takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        positionOf(url).takeIf { it >= 0 }?.let { notifyItemChanged(it) }
    }

    fun positionOf(url: String?): Int {
        if (url.isNullOrBlank()) return -1
        return items.indexOfFirst { it.url == url }
    }

    fun channelAt(position: Int): Channel? = items.getOrNull(position)

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel_card, parent, false)
        return CardHolder(view)
    }

    override fun onBindViewHolder(holder: CardHolder, position: Int) {
        holder.bind(items[position], items[position].url == currentUrl)
    }

    override fun onViewRecycled(holder: CardHolder) {
        holder.releasePreviewSurface(force = true)
        super.onViewRecycled(holder)
    }

    inner class CardHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val letterView: TextView = itemView.findViewById(R.id.channelLetter)
        private val logoView: ImageView = itemView.findViewById(R.id.channelLogo)
        private val nameView: TextView = itemView.findViewById(R.id.channelName)
        private val officialTag: TextView = itemView.findViewById(R.id.officialTag)
        private val nowDot: View = itemView.findViewById(R.id.nowDot)
        private val favoriteTag: TextView = itemView.findViewById(R.id.favoriteTag)
        private val previewTag: TextView = itemView.findViewById(R.id.previewTag)
        val previewHost: FrameLayout = itemView.findViewById(R.id.previewHost)

        private var longPressFired = false
        private var longPressRunnable: Runnable? = null

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onSelect(items[position])
            }
            itemView.setOnFocusChangeListener { view, hasFocus ->
                view.animate().cancel()
                view.animate()
                    .scaleX(if (hasFocus) 1.06f else 1f)
                    .scaleY(if (hasFocus) 1.06f else 1f)
                    .setDuration(110)
                    .start()
                val channel = items.getOrNull(bindingAdapterPosition)
                if (hasFocus && channel != null) {
                    onFocus(channel, previewHost, false)
                } else if (!hasFocus) {
                    cancelLongPress()
                    onFocus(null, null, false)
                }
            }
            // 鼠标 / 空中飞鼠：悬停即视为停留，交给预览逻辑（遥控器没有 hover 事件，不受影响）。
            // 除了 ENTER 还要处理 MOVE：模拟器/部分桌面只发 HOVER_MOVE、不发 ENTER，
            // 或者列表复用后停在卡片上不会再补发 ENTER，都会导致预览永远不启动。
            itemView.setOnHoverListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_HOVER_ENTER,
                    MotionEvent.ACTION_HOVER_MOVE -> {
                        val channel = items.getOrNull(bindingAdapterPosition)
                        if (channel != null) onFocus(channel, previewHost, true)
                    }
                    MotionEvent.ACTION_HOVER_EXIT -> {
                        // 若这张卡仍持有遥控器焦点，鼠标移开不该掐掉正在播放的预览；
                        // 预览继续由焦点维持，只把「悬停标记」清掉。
                        if (!view.hasFocus()) onFocus(null, null, true)
                    }
                }
                false
            }
            // 自己处理长按 OK：不依赖 framework 的 long-press 跟踪，兼容各类遥控器
            itemView.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    when (event.action) {
                        KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                            longPressFired = false
                            cancelLongPress()
                            val channel = items.getOrNull(bindingAdapterPosition)
                            if (channel != null) {
                                val runnable = Runnable {
                                    longPressFired = true
                                    onToggleFavorite(channel)
                                }
                                longPressRunnable = runnable
                                handler.postDelayed(runnable, LONG_PRESS_MS)
                            }
                        }
                        KeyEvent.ACTION_UP -> {
                            cancelLongPress()
                            if (longPressFired) {
                                longPressFired = false
                                return@setOnKeyListener true // 吞掉这次抬起，避免顺带触发播放
                            }
                        }
                    }
                }
                false
            }
        }

        private fun cancelLongPress() {
            longPressRunnable?.let { handler.removeCallbacks(it) }
            longPressRunnable = null
        }

        fun releasePreviewSurface(force: Boolean = false) {
            // 正在播放的预览不能被普通重绑/复用清掉，只有主动摘除时才拆
            if (!force && previewHost === activePreviewHost) return
            (previewHost.getChildAt(0) as? androidx.media3.ui.PlayerView)?.let { it.player = null }
            previewHost.removeAllViews()
            previewHost.visibility = View.GONE
            previewTag.visibility = View.GONE
        }

        fun bind(channel: Channel, isCurrent: Boolean) {
            nameView.text = channel.name
            letterView.text = firstLetter(channel.name)
            officialTag.visibility = if (channel.url.isBlank() && channel.officialUrl != null) {
                View.VISIBLE
            } else {
                View.GONE
            }
            nowDot.visibility = if (isCurrent) View.VISIBLE else View.INVISIBLE
            favoriteTag.visibility = if (prefs.isFavorite(channel.stableKey())) {
                View.VISIBLE
            } else {
                View.GONE
            }
            itemView.isSelected = isCurrent

            val focused = itemView.hasFocus()
            itemView.scaleX = if (focused) 1.06f else 1f
            itemView.scaleY = itemView.scaleX

            // ViewHolder 复用时先断开旧的预览 surface
            releasePreviewSurface()

            // 封面风格：首字母模式只显示文字；台标 / 实时画面都尽量渲染台标（即使全局关闭台标），
            // 「实时画面」模式由 MainActivity 在聚焦时叠加预览小窗
            val logoData: Any? = if (prefs.coverMode == Prefs.COVER_LETTER) {
                null
            } else {
                LogoResolver.resolve(channel, prefs, forceLogo = true)
            }
            val tag = "${channel.name}|${channel.url}|$logoData"
            // 同一频道已渲染过相同封面：跳过重复加载，避免快速滚动时反复请求网络台标拖慢盒子
            if (logoView.tag == tag) return
            logoView.tag = tag

            when (logoData) {
                null -> {
                    logoView.setImageDrawable(null)
                    letterView.visibility = View.VISIBLE
                }
                is Int -> {
                    // 内置台标也统一走圆角 + 统一解码尺寸，与网络台标保持一致的观感
                    logoView.setImageDrawable(null)
                    letterView.visibility = View.GONE
                    val request = ImageRequest.Builder(itemView.context)
                        .data(logoData)
                        .size(320, 160)
                        .transformations(RoundedCornersTransformation(16f))
                        .target(logoView)
                        .build()
                    itemView.context.imageLoader.enqueue(request)
                }
                else -> {
                    val url = logoData.toString()
                    logoView.setImageDrawable(null)
                    letterView.visibility = View.VISIBLE
                    val request = ImageRequest.Builder(itemView.context)
                        .data(url)
                        // 统一解码尺寸：导入源里的台标常是上千像素的大图，
                        // 限制到卡片实际需要的分辨率，省内存也减少滚动卡顿
                        .size(320, 160)
                        // 统一圆角：把白底方图 / 直角图片裁成一致的圆角图标砖，
                        // 收进统一的玻璃底板后视觉整齐协调
                        .transformations(RoundedCornersTransformation(16f))
                        .target(logoView)
                        .listener(object : ImageRequest.Listener {
                            override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                                if (logoView.tag == tag) letterView.visibility = View.GONE
                            }

                            override fun onError(request: ImageRequest, result: ErrorResult) {
                                if (logoView.tag == tag) {
                                    // 封面模式要求显示台标：内置台标优先，否则回退首字母占位
                                    if (channel.logoRes != 0) {
                                        logoView.setImageResource(channel.logoRes)
                                        letterView.visibility = View.GONE
                                    } else {
                                        letterView.visibility = View.VISIBLE
                                    }
                                }
                            }
                        })
                        .build()
                    itemView.context.imageLoader.enqueue(request)
                }
            }
        }

        private fun firstLetter(name: String): String {
            val first = name.trim().firstOrNull()?.toString() ?: "#"
            return first.uppercase()
        }
    }
}
