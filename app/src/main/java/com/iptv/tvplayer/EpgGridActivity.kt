package com.iptv.tvplayer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * EPG 时间轴网格视图。
 *
 * 左侧固定频道列，右侧按时间横向铺开 6 小时节目块；遥控器 ↑↓ 换频道、
 * ←→ 换节目、OK 预约/取消、播放键立即跳转播放。金色表示已预约。
 * 预约数据保存在 Prefs，由 MainActivity 的前台定时器在开播前 1 分钟提醒。
 */
class EpgGridActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLAY_INDEX = "play_index"

        private const val SPAN_HOURS = 6
        private const val ROWS = 8
        private const val ROW_DP = 62
        private const val RULER_DP = 30
        private const val DP_PER_MINUTE = 3f

        // 频道列表可能很大，用进程内 holder 传递，避免 Parcelable 与 Binder 上限
        @Volatile
        var pendingChannels: List<Channel> = emptyList()

        @Volatile
        var pendingIndex: Int = 0

        @Volatile
        var pendingEpg: EpgRepository? = null
    }

    private lateinit var channelColumn: LinearLayout
    private lateinit var gridContent: LinearLayout
    private lateinit var gridScroller: HorizontalScrollView
    private lateinit var detailView: TextView
    private lateinit var titleView: TextView
    private lateinit var prefs: Prefs

    private val epg: EpgRepository? get() = pendingEpg
    private val channels: List<Channel> get() = pendingChannels

    private val minutePx: Int by lazy {
        (DP_PER_MINUTE * resources.displayMetrics.density).toInt().coerceAtLeast(2)
    }
    private val rowPx: Int by lazy { (ROW_DP * resources.displayMetrics.density).toInt() }
    private val rulerPx: Int by lazy { (RULER_DP * resources.displayMetrics.density).toInt() }
    private val totalPx: Int by lazy { 60 * SPAN_HOURS * minutePx }

    private val windowStart: Long by lazy {
        val cal = Calendar.getInstance()
        // 盒子时钟常不准：用节目数据锚定时间对齐，避免时间轴落空
        cal.timeInMillis = epg?.anchorNow() ?: System.currentTimeMillis()
        cal.set(Calendar.MINUTE, if (cal.get(Calendar.MINUTE) < 30) 0 else 30)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.timeInMillis - 30 * 60_000L
    }
    private val windowEnd: Long get() = windowStart + SPAN_HOURS * 60L * 60_000L

    private var pageStart = 0
    private var selectedRow = 0
    private var selectedCol = 0
    private var rows: List<Channel> = emptyList()
    private var rowProgrammes: List<List<Programme>> = emptyList()
    private val cellViews = ArrayList<ArrayList<View>>()

    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_epg_grid)
        prefs = Prefs(this)

        channelColumn = findViewById(R.id.channelColumn)
        gridContent = findViewById(R.id.gridContent)
        gridScroller = findViewById(R.id.gridScroller)
        detailView = findViewById(R.id.gridDetail)
        titleView = findViewById(R.id.gridTitle)

        if (channels.isEmpty()) {
            detailView.text = "还没有频道，请先在设置里添加播放源"
            return
        }

        val index = pendingIndex.coerceIn(0, channels.size - 1)
        pageStart = (index - ROWS / 2).coerceIn(0, (channels.size - ROWS).coerceAtLeast(0))
        selectedRow = (index - pageStart).coerceIn(0, ROWS - 1)

        refreshProgrammes()
        render()

        // XMLTV 可能还在读缓存，异步补一次再重绘
        Thread {
            runCatching { epg?.load(false) }
            runOnUiThread {
                if (!isFinishing) {
                    refreshProgrammes()
                    render()
                }
            }
        }.start()
    }

    private fun refreshProgrammes() {
        rows = channels.subList(pageStart, minOf(pageStart + ROWS, channels.size))
        rowProgrammes = rows.map { channel ->
            epg?.window(channel, windowStart, windowEnd).orEmpty()
        }
        val span = "${clockFormat.format(Date(windowStart))} - ${clockFormat.format(Date(windowEnd))}"
        titleView.text = "节目时间轴　·　$span"
    }

    // ------------------------------------------------------------ 渲染

    private fun render() {
        channelColumn.removeAllViews()
        gridContent.removeAllViews()
        cellViews.clear()

        // HorizontalScrollView 是 FrameLayout 子类，子 View 必须用 FrameLayout.LayoutParams
        gridContent.layoutParams = FrameLayout.LayoutParams(
            totalPx, FrameLayout.LayoutParams.WRAP_CONTENT
        )

        // 左侧留白对齐时间刻度
        channelColumn.addView(
            View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, rulerPx
                )
            }
        )
        gridContent.addView(buildRuler())

        val anchor = epg?.anchorNow() ?: System.currentTimeMillis()
        val nowOffset = ((anchor - windowStart) / 60_000L * minutePx)
            .coerceIn(0L, totalPx.toLong()).toInt()
        val showNow = anchor in windowStart..windowEnd

        for (rowIndex in rows.indices) {
            channelColumn.addView(buildChannelLabel(rowIndex))
            gridContent.addView(buildTrack(rowIndex, nowOffset, showNow))
        }

        renderSelection()
        ensureVisible()
    }

    private fun buildRuler(): LinearLayout {
        val ruler = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(totalPx, rulerPx)
        }
        for (halfHour in 0 until SPAN_HOURS * 2) {
            val time = windowStart + halfHour * 30L * 60_000L
            val label = TextView(this).apply {
                text = clockFormat.format(Date(time))
                textSize = 10f
                setTextColor(Color.parseColor("#93A2BC"))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(30 * minutePx, rulerPx)
            }
            ruler.addView(label)
        }
        return ruler
    }

    private fun buildChannelLabel(rowIndex: Int): TextView {
        val channel = rows[rowIndex]
        val lines = if (epg?.isLoaded == true) rowProgrammes[rowIndex].size else 0
        return TextView(this).apply {
            text = "#${channel.number}  ${channel.name}"
            textSize = 12f
            setTextColor(Color.parseColor("#EAF1FF"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(10), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, rowPx
            )
            tag = "label_$rowIndex"
        }.also { label ->
            // 多选台信息放到 contentDescription，方便无障碍读取
            label.contentDescription = "${channel.name}，本时间段 $lines 个节目"
        }
    }

    private fun buildTrack(rowIndex: Int, nowOffset: Int, showNow: Boolean): FrameLayout {
        val track = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(totalPx, rowPx)
        }
        val channel = rows[rowIndex]
        val list = rowProgrammes.getOrElse(rowIndex) { emptyList() }
        val cells = ArrayList<View>()

        if (list.isEmpty()) {
            val empty = TextView(this).apply {
                text = if (epg?.isLoaded == true) "该频道本时段暂无节目数据" else "未加载 XMLTV 节目单（可在设置里填地址）"
                textSize = 11f
                setTextColor(Color.parseColor("#7C8AA3"))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, 0, 0)
                background = cellBackground(false, false)
                layoutParams = FrameLayout.LayoutParams(totalPx - dp(4), rowPx - dp(4)).apply {
                    leftMargin = 0
                    topMargin = dp(2)
                }
            }
            track.addView(empty)
            cells.add(empty)
        } else {
            for (programme in list) {
                val from = maxOf(programme.start, windowStart)
                val to = minOf(programme.stop, windowEnd)
                if (to <= from) continue
                val offset = ((from - windowStart) / 60_000L * minutePx).toInt()
                val width = ((to - from) / 60_000L * minutePx).toInt().coerceAtLeast(dp(28))
                val reserved = prefs.isReserved(reservationKey(channel, programme))
                val cell = TextView(this).apply {
                    text = (if (reserved) "⏰ " else "") + programme.title
                    textSize = 11f
                    setTextColor(Color.parseColor("#EAF1FF"))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), 0, dp(8), 0)
                    layoutParams = FrameLayout.LayoutParams(width - dp(3), rowPx - dp(4)).apply {
                        leftMargin = offset
                        topMargin = dp(2)
                    }
                    tag = programme.start
                }
                track.addView(cell)
                cells.add(cell)
            }
        }
        cellViews.add(cells)

        if (showNow) {
            track.addView(
                View(this).apply {
                    setBackgroundColor(Color.parseColor("#FF6B8B"))
                    layoutParams = FrameLayout.LayoutParams(dp(2), rowPx).apply {
                        leftMargin = nowOffset
                    }
                }
            )
        }
        return track
    }

    private fun cellBackground(selected: Boolean, reserved: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(
                when {
                    selected -> Color.parseColor("#33507F")
                    reserved -> Color.parseColor("#4A3C1E")
                    else -> Color.parseColor("#172033")
                }
            )
            setStroke(
                if (selected) dp(2) else dp(1),
                if (selected) Color.parseColor("#7FB0FF") else Color.parseColor("#2A364B")
            )
        }

    private fun renderSelection() {
        for (rowIndex in cellViews.indices) {
            val channel = rows.getOrNull(rowIndex)
            val programmes = rowProgrammes.getOrElse(rowIndex) { emptyList() }
            for (colIndex in cellViews[rowIndex].indices) {
                val programme = programmes.getOrNull(colIndex)
                val reserved = channel != null && programme != null &&
                    prefs.isReserved(reservationKey(channel, programme))
                cellViews[rowIndex][colIndex].background =
                    cellBackground(rowIndex == selectedRow && colIndex == selectedCol, reserved)
                (cellViews[rowIndex][colIndex] as? TextView)?.let { tv ->
                    val title = programme?.title.orEmpty()
                    tv.text = (if (reserved) "⏰ " else "") +
                        title.ifBlank { "该频道本时段暂无节目数据" }
                }
            }
            (channelColumn.getChildAt(rowIndex + 1) as? TextView)?.apply {
                setBackgroundColor(
                    if (rowIndex == selectedRow) Color.parseColor("#1E3358")
                    else Color.TRANSPARENT
                )
            }
        }

        val channel = rows.getOrNull(selectedRow)
        val programme = rowProgrammes.getOrNull(selectedRow)?.getOrNull(selectedCol)
        detailView.text = when {
            channel == null -> "暂无频道"
            programme == null -> "【${channel.name}】本时段没有节目数据\n在设置里填 XMLTV 地址后自动填充时间轴"
            else -> buildString {
                append("【").append(channel.name).append("】  ")
                append(programme.title).append("\n")
                append(programme.timeRange())
                if (prefs.isReserved(reservationKey(channel, programme))) append("　·　⏰ 已预约提醒")
                if (programme.desc.isNotBlank()) append("\n").append(programme.desc.take(60))
                append("\n\n按 OK 预约/取消　·　按播放键立即播放该频道")
            }
        }
    }

    // ------------------------------------------------------------ 遥控操作

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TV -> {
                finish()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                moveRow(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                moveRow(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                moveCol(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                moveCol(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                toggleReservation()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                playSelected()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun moveRow(delta: Int) {
        if (channels.isEmpty()) return
        val absolute = (pageStart + selectedRow + delta).coerceIn(0, channels.size - 1)
        if (absolute !in pageStart until pageStart + rows.size) {
            pageStart = (absolute - ROWS / 2)
                .coerceIn(0, (channels.size - ROWS).coerceAtLeast(0))
            selectedRow = absolute - pageStart
            selectedCol = 0
            refreshProgrammes()
            render()
        } else {
            selectedRow = absolute - pageStart
            selectedCol = selectedCol.coerceIn(
                0, (rowProgrammes.getOrNull(selectedRow)?.size ?: 1) - 1
            ).coerceAtLeast(0)
            renderSelection()
            ensureVisible()
        }
    }

    private fun moveCol(delta: Int) {
        val size = rowProgrammes.getOrNull(selectedRow)?.size ?: 1
        if (size <= 0) return
        selectedCol = (selectedCol + delta).coerceIn(0, size - 1)
        renderSelection()
        ensureVisible()
    }

    private fun ensureVisible() {
        val cell = cellViews.getOrNull(selectedRow)?.getOrNull(selectedCol) ?: return
        val lp = cell.layoutParams as? FrameLayout.LayoutParams ?: return
        gridScroller.smoothScrollTo(
            (lp.leftMargin - gridScroller.width / 3).coerceAtLeast(0), 0
        )
    }

    private fun toggleReservation() {
        val channel = rows.getOrNull(selectedRow) ?: return
        val programme = rowProgrammes.getOrNull(selectedRow)?.getOrNull(selectedCol) ?: return
        val added = prefs.toggleReservation(reservationKey(channel, programme))
        toast(if (added) "⏰ 已预约：${programme.title}" else "已取消预约：${programme.title}")
        renderSelection()
    }

    private fun playSelected() {
        val index = pageStart + selectedRow
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PLAY_INDEX, index))
        finish()
    }

    private fun reservationKey(channel: Channel, programme: Programme): String =
        channel.stableKey() + "|" + programme.start

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
