package com.iptv.tvplayer

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale

/**
 * 遥控器可直接进入的设置中心：手机扫码配置、第三方接口（GET/POST/Token）、
 * XMLTV 节目单、卡片预览与收藏、画质、播放器、台标、关于。
 * 设置即时写入 Prefs，MainActivity 回到前台时按 version 变化重新应用并刷新。
 */
class SettingsActivity : AppCompatActivity() {

    private class Row(
        val key: String,
        val view: View,
        var index: Int,
        val labels: List<String>,
        val hints: List<String>,
        val setter: (Int) -> Unit
    )

    private lateinit var prefs: Prefs
    private lateinit var repository: PlaylistRepository
    private lateinit var tokens: TokenManager
    private lateinit var epg: EpgRepository
    private lateinit var container: LinearLayout
    private lateinit var sourceContainer: LinearLayout
    private lateinit var settingsScroll: ScrollView
    private val rows = ArrayList<Row>()
    /** 长按 MENU 打开设置页时，残留的重复按键不能把设置页立刻关掉 */
    private val menuGuardUntil = System.currentTimeMillis() + 2500L
    /** 分类锚点：遥控器按 1-9 直接跳到对应设置分区，不用滚十几屏 */
    private val sectionViews = ArrayList<View>()
    private var templateRow: View? = null
    private var epgStatusRow: View? = null
    private var tokenStatusRow: View? = null
    private var libraryRow: View? = null
    private var hiddenRow: View? = null

    /** 导入订阅备份：用系统文档选择器挑一个 .json 文件（支持 U 盘 / 下载目录 / 网络存储） */
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) handleBackupUri(uri) else toast("未选择文件")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)
        repository = PlaylistRepository(this)
        tokens = repository.tokens
        epg = EpgRepository(this)
        container = findViewById(R.id.settingsContainer)
        settingsScroll = findViewById(R.id.settingsScroll)
        buildUi()
        registerDownloadReceiver()
    }

    override fun onDestroy() {
        // 页面退出时停掉下载线程，避免后台继续写文件 / 回调已销毁的界面
        updateCancelled = true
        runCatching { activeDownload?.disconnect() }
        runCatching { updateDialog?.dismiss() }
        updateDialog = null
        if (downloadReceiverRegistered) {
            runCatching { unregisterReceiver(downloadReceiver) }
            downloadReceiverRegistered = false
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::sourceContainer.isInitialized) {
            refreshSources()
            refreshStatusRows()
            // 手机后台页 / 底部信息栏可能改过画质、引擎等设置，回来时对齐真实值
            syncOptionRows()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val key = currentFocus?.tag as? String
        if (key != null && rows.any { it.key == key }) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    step(key, -1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    step(key, 1)
                    return true
                }
            }
        }
        // 1-9 快速跳到对应分类（与播放页的数字选台互不冲突，是两个界面）
        val sectionIndex = keyCode - KeyEvent.KEYCODE_1
        if (sectionIndex in sectionViews.indices) {
            val target = sectionViews[sectionIndex]
            settingsScroll.post {
                settingsScroll.smoothScrollTo(0, (target.top - dp(8)).coerceAtLeast(0))
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            // 长按 MENU 进设置时按键仍按着，会继续送 repeat 事件；这些事件若在此处
            // 直接 finish()，设置页就「闪一下又回到播放页」。启动后 2.5 秒内与所有
            // repeat 事件一律吞掉，等用户真正重新按 MENU 才返回播放页。
            if ((event?.repeatCount ?: 0) > 0 || System.currentTimeMillis() < menuGuardUntil) {
                return true
            }
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------ 界面搭建

    private fun buildUi() {
        container.removeAllViews()
        rows.clear()
        sectionViews.clear()
        templateRow = null
        epgStatusRow = null
        tokenStatusRow = null
        libraryRow = null

        section("① 一键导入订阅链接　·　最常用")
        actionRow("粘贴链接，一键导入", "推荐 · 一条或多条订阅地址，自动识别格式") { showQuickImportDialog() }
        actionRow("从剪贴板一键导入", "手机上复制链接，在盒子上按此行即可（需输入法支持粘贴）") {
            quickImportFromClipboard()
        }

        section("手机扫码配置　·　推荐")
        actionRow("打开二维码 / 启动配置页", "手机扫码 → 网页表单 → 一键保存到电视") {
            startActivity(Intent(this, QrConfigActivity::class.java))
        }

        section("第三方接口 / 播放源导入")
        actionRow("添加接口（URL）", "地址、请求方式、POST 请求体、请求头、Token 与字段映射；已有订阅点进去可编辑") { showSourceEditor() }
        actionRow("导入 m3u 文本", "整段粘贴列表内容，保存到 App 内部，不需要服务器") { showImportTextDialog() }
        actionRow("从本地文件导入", "填写 U 盘 / 下载目录里的 m3u 绝对路径") { showLocalFileDialog() }
        actionRow("导出订阅备份", "把所有订阅存成一个 JSON，可换机 / 分享 / 留底") { exportBackup() }
        actionRow("导入订阅备份", "从 U 盘 / 下载目录选一个备份 JSON 恢复") { importBackup() }
        actionRow("刷新全部播放源", "返回频道墙时重新拉取所有接口") {
            prefs.notifySourceChanged()
            toast("返回频道墙后自动刷新")
        }
        sourceContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(sourceContainer)
        refreshSources()

        section("Token 定时刷新")
        optionRow(
            "tokenInterval", "全局刷新周期",
            Prefs.TOKEN_LABELS, Prefs.TOKEN_HINTS, prefs.tokenIntervalIndex
        ) { prefs.tokenIntervalIndex = it }
        actionRow("立即刷新 Token", "强制重新获取一次，并更新视频播放请求头") { refreshTokenNow() }
        tokenStatusRow = actionRow("Token 状态", tokens.status()) { refreshTokenNow() }

        section("XMLTV 节目单")
        actionRow("XMLTV 地址", "当前：${prefs.epgUrl.ifBlank { "（未填写）" }}") { showEpgUrlDialog() }
        optionRow(
            "epgInterval", "自动刷新间隔",
            Prefs.EPG_LABELS, Prefs.EPG_HINTS, prefs.epgIntervalIndex
        ) { prefs.epgIntervalIndex = it }
        actionRow("立即刷新节目单", "重新拉取并解析 XMLTV") { refreshEpgNow() }
        epgStatusRow = actionRow("节目单状态", epg.status()) { refreshEpgNow() }

        section("性能 / 盒子流畅度")
        optionRow(
            "preview", "卡片低码率预览",
            Prefs.PREVIEW_LABELS, Prefs.PREVIEW_HINTS,
            if (prefs.previewEnabled) 0 else 1
        ) { prefs.previewEnabled = it == 0 }
        optionRow(
            "background", "返回频道墙后台续播",
            Prefs.BACKGROUND_LABELS, Prefs.BACKGROUND_HINTS,
            if (prefs.backgroundPlay) 1 else 0
        ) { prefs.backgroundPlay = it == 1 }

        section("多线路 / 自动切换")
        optionRow(
            "autoSwitch", "线路失败自动切换",
            Prefs.AUTO_SWITCH_LABELS, Prefs.AUTO_SWITCH_HINTS,
            if (prefs.autoSwitchLine) 0 else 1
        ) { prefs.autoSwitchLine = it == 0 }
        actionRow("多线路状态", multiLineSummary()) { toast(multiLineSummary()) }

        section("EPG 时间轴 / 节目预约")
        actionRow("打开节目时间轴", "按 GUIDE 也可以直接打开") { openGrid() }
        actionRow("节目预约", reservationSummary()) { showReservationDialog() }

        section("卡片预览 / 收藏夹")
        libraryRow = actionRow("收藏夹 / 最近观看", librarySummary()) { showLibraryDialog() }

        section("画质 / 分辨率")
        optionRow(
            "quality", "画质分辨率",
            Prefs.QUALITY_LABELS, Prefs.QUALITY_HINTS, prefs.quality
        ) { prefs.quality = it }

        section("播放器 / 内置加速")
        optionRow(
            "engine", "播放引擎",
            Prefs.ENGINE_LABELS, Prefs.ENGINE_HINTS, prefs.engine
        ) { prefs.engine = it }

        section("台标 LOGO 获取")
        optionRow(
            "logo", "台标来源",
            Prefs.LOGO_LABELS, Prefs.LOGO_HINTS, prefs.logoMode
        ) { prefs.logoMode = it }
        templateRow = actionRow(
            "台标地址模板", "当前：${prefs.logoTemplate.ifBlank { "（未填写）" }}"
        ) { showTemplateDialog() }

        section("频道封面 / 不可用源过滤")
        optionRow(
            "cover", "频道封面显示",
            Prefs.COVER_LABELS, Prefs.COVER_HINTS, prefs.coverMode
        ) {
            // 封面模式只决定静态封面，不再联动改写预览开关：
            // 以前选「台标/首字母」会把 preview 写死成 false，导致悬停永远看不到实时画面
            prefs.coverMode = it
        }
        optionRow(
            "hideFailed", "自动隐藏播放失败的频道",
            listOf("开启（失败 3 次后隐藏）", "关闭（保留全部）"),
            listOf(
                "连续播放失败的频道（死链 / 测试源）自动从墙面与筛选中移除，避免一开满屏黑屏",
                "任何频道都不隐藏，失败时只提示错误"
            ),
            if (prefs.hideFailedChannels) 0 else 1
        ) { prefs.hideFailedChannels = it == 0 }
        hiddenRow = actionRow(
            "已隐藏频道", hiddenSummary()
        ) {
            prefs.clearHiddenChannels()
            refreshHiddenRow()
            toast("已恢复全部被隐藏的频道")
        }

        section("播放诊断 / 盒子排障")
        actionRow("查看诊断日志", "设备 / TLS / 接口 / 播放错误，随时可查") { showDiagnosticsDialog() }
        actionRow("生成日志文件", "导出 .txt 到 Download 目录，可 adb pull 取回排查") { exportDiagnostics() }
        actionRow(
            "设备自动分级",
            DeviceProfile.summary(this).lineSequence().firstOrNull().orEmpty()
        ) { showDiagnosticsDialog() }
        optionRow(
            "externalFallback", "解码失败自动换播放器",
            listOf("开启（老盒子救急）", "关闭"),
            listOf(
                "内置解码连续失败 2 次后，自动尝试盒子上已装的 VLC / MX / 系统播放器",
                "只显示错误提示，不自动跳转外部应用"
            ),
            if (prefs.externalFallback) 0 else 1
        ) { prefs.externalFallback = it == 0 }

        section("关于 / 反馈")
        actionRow("关于 飞雪TV", "版本号、版权与项目主页") { showAboutDialog() }
        actionRow("检测更新", "对比 GitHub Releases，支持一键在线更新") { checkForUpdate() }
        actionRow("版本发布页", "查看历史版本与更新日志") { openUrl(releasesUrl) }
        actionRow("日志上报反馈", "扫码 / 微信 / QQ 分享运行日志与崩溃记录") { showFeedbackDialog() }

        container.post {
            for (i in 0 until container.childCount) {
                val v = container.getChildAt(i)
                if (v.isFocusable && v.visibility == View.VISIBLE) {
                    v.requestFocus()
                    break
                }
            }
        }
    }

    private fun section(title: String) {
        val tv = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#8FB2FF"))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(20)
            lp.bottomMargin = dp(6)
            layoutParams = lp
        }
        container.addView(tv)
        sectionViews.add(tv)
    }

    private fun note(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#AFC6E8"))
            textSize = 11f
            setLineSpacing(dp(3).toFloat(), 1f)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }
        container.addView(tv)
    }

    private fun inflateRow(): View =
        layoutInflater.inflate(R.layout.item_setting_row, container, false)

    private fun actionRow(title: String, desc: String, onClick: () -> Unit): View {
        val row = inflateRow()
        row.findViewById<TextView>(R.id.settingTitle).text = title
        row.findViewById<TextView>(R.id.settingDesc).text = desc
        row.findViewById<TextView>(R.id.settingValue).text = "打开"
        row.setOnClickListener { onClick() }
        container.addView(row)
        return row
    }

    private fun optionRow(
        key: String,
        title: String,
        labels: List<String>,
        hints: List<String>,
        current: Int,
        setter: (Int) -> Unit
    ) {
        val row = inflateRow()
        row.tag = key
        row.findViewById<TextView>(R.id.settingTitle).text = title
        row.findViewById<TextView>(R.id.settingValue).text = labels[current]
        row.findViewById<TextView>(R.id.settingDesc).text = hints.getOrElse(current) { "" }
        rows.add(Row(key, row, current, labels, hints, setter))
        row.setOnClickListener { step(key, 1) }
        container.addView(row)
    }

    private fun step(key: String, delta: Int) {
        val r = rows.firstOrNull { it.key == key } ?: return
        val size = r.labels.size
        r.index = ((r.index + delta) % size + size) % size
        r.setter(r.index)
        r.view.findViewById<TextView>(R.id.settingValue).text = r.labels[r.index]
        r.view.findViewById<TextView>(R.id.settingDesc).text = r.hints.getOrElse(r.index) { "" }
        toast("已设为：${r.labels[r.index]}")
    }

    /** 每个 option 行的真实当前值：集中一处，避免 UI 与 Prefs 各写各的 */
    private fun currentOptionValue(key: String): Int = when (key) {
        "tokenInterval" -> prefs.tokenIntervalIndex
        "epgInterval" -> prefs.epgIntervalIndex
        "preview" -> if (prefs.previewEnabled) 0 else 1
        "background" -> if (prefs.backgroundPlay) 0 else 1
        "autoSwitch" -> if (prefs.autoSwitchLine) 0 else 1
        "quality" -> prefs.quality
        "engine" -> prefs.engine
        "logo" -> prefs.logoMode
        "cover" -> prefs.coverMode
        "hideFailed" -> if (prefs.hideFailedChannels) 0 else 1
        "externalFallback" -> if (prefs.externalFallback) 0 else 1
        else -> -1
    }

    /**
     * 手机扫码后台 / 底部信息栏都可能改设置。回到设置页时把所有 option 行的
     * 值与说明重新对齐 Prefs，否则会出现「界面显示 720p、实际已是 1080p」的错乱。
     */
    private fun syncOptionRows() {
        for (r in rows) {
            val value = currentOptionValue(r.key)
            if (value < 0) continue
            r.index = value.coerceIn(0, r.labels.lastIndex)
            r.view.findViewById<TextView>(R.id.settingValue).text = r.labels[r.index]
            r.view.findViewById<TextView>(R.id.settingDesc).text = r.hints.getOrElse(r.index) { "" }
        }
    }

    // ------------------------------------------------------------ 接口管理

    private fun refreshSources() {
        sourceContainer.removeAllViews()
        val list = repository.sources
        if (list.isEmpty()) {
            val tv = TextView(this).apply {
                text = "尚未添加接口　·　最上方「粘贴链接，一键导入」填一个聚合订阅地址即可开始"
                setTextColor(Color.parseColor("#AFC6E8"))
                textSize = 12f
                setPadding(dp(4), dp(6), dp(4), dp(14))
            }
            sourceContainer.addView(tv)
            return
        }
        for ((index, config) in list.withIndex()) {
            val tv = layoutInflater.inflate(
                R.layout.item_source_row, sourceContainer, false
            ) as TextView
            val mark = if (config.enabled) "✔" else "✕"
            tv.text = "${index + 1}. $mark  ${config.describe()}"
            tv.alpha = if (config.enabled) 1f else 0.45f
            tv.background = ContextCompat.getDrawable(this, R.drawable.bg_source_row)
            tv.isFocusable = true
            tv.isClickable = true
            tv.setOnClickListener { manageSourceDialog(config) }
            sourceContainer.addView(tv)
        }
        val enabledCount = list.count { it.enabled }
        val tip = TextView(this).apply {
            text = "点某个订阅可：编辑 / 启用·停用 / 上下移优先级 / 重新校验 / 删除　·　共 ${list.size} 个（已启用 $enabledCount）"
            setTextColor(Color.parseColor("#AFC6E8"))
            textSize = 11f
            setPadding(dp(4), dp(8), dp(4), dp(6))
        }
        sourceContainer.addView(tip)
    }

    private fun confirmDelete(config: SourceConfig) {
        val message = buildString {
            append(config.url).append("\n\n")
            append("请求方式：").append(if (config.isPost) "POST" else "GET").append("\n")
            if (config.body.isNotBlank()) append("请求体：").append(config.body).append("\n")
            if (config.headersText.isNotBlank()) append("请求头：").append(config.headersText).append("\n")
            if (config.fieldsText.isNotBlank()) append("字段映射：").append(config.fieldsText).append("\n")
            if (config.hasToken) {
                append("Token 接口：").append(config.tokenUrl).append("\n")
                append("Token 路径：").append(config.tokenPath.ifBlank { "（整段响应）" }).append("\n")
                append("刷新周期：").append(
                    if (config.refreshMinutes > 0) "${config.refreshMinutes} 分钟" else "跟随全局"
                )
            }
        }
        AlertDialog.Builder(this)
            .setTitle("接口配置")
            .setMessage(message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                repository.removeSource(config.id)
                prefs.notifySourceChanged()
                refreshSources()
                toast("已删除接口")
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** 点击某个订阅后弹出的管理菜单：编辑 / 启用停用 / 调优先级 / 校验 / 删除 */
    private fun manageSourceDialog(config: SourceConfig) {
        val options = arrayOf(
            "编辑（地址与参数）",
            if (config.enabled) "停用此订阅" else "启用此订阅",
            "↑ 上移（提高合并优先级）",
            "↓ 下移（降低合并优先级）",
            "重新校验此订阅",
            getString(R.string.action_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(config.url)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showSourceEditor(config)
                    1 -> {
                        repository.updateSource(config.copy(enabled = !config.enabled))
                        prefs.notifySourceChanged()
                        refreshSources()
                        toast(if (!config.enabled) "已启用：${config.label()}" else "已停用：${config.label()}")
                    }
                    2 -> moveSource(config, up = true)
                    3 -> moveSource(config, up = false)
                    4 -> recheckSource(config)
                    5 -> confirmDelete(config)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun moveSource(config: SourceConfig, up: Boolean) {
        if (repository.moveSource(config.id, up)) {
            prefs.notifySourceChanged()
            refreshSources()
            toast(if (up) "已上移" else "已下移")
        } else {
            toast(if (up) "已经是最前" else "已经是最后")
        }
    }

    /** 单独校验一个订阅是否还能解析出频道 */
    private fun recheckSource(config: SourceConfig) {
        toast("正在校验 ${config.label()}…")
        Thread {
            val result = runCatching { repository.probe(config) }.getOrDefault(0 to "校验异常")
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val (count, detail) = result
                val msg = if (count > 0) {
                    "解析到 $count 个频道\n\n$detail"
                } else {
                    "校验未通过：\n$detail"
                }
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("校验结果 · ${config.label()}")
                    .setMessage(msg)
                    .setPositiveButton(R.string.action_close, null)
                    .show()
            }
        }.start()
    }

    // ------------------------------------------------------------ 一键导入订阅链接

    private fun showQuickImportDialog() {
        val c = layoutInflater.inflate(R.layout.dialog_quick_import, null)
        val input = c.findViewById<EditText>(R.id.quickImportUrls)
        val headersInput = c.findViewById<EditText>(R.id.quickImportHeaders)
        val replaceBox = c.findViewById<CheckBox>(R.id.quickImportReplace)

        // 遥控器打字麻烦：剪贴板里若有链接，打开时自动填好
        val clip = clipboardText()
        if (clip != null && SubscriptionImporter.normalize(clip).isNotEmpty()) {
            input.setText(clip.trim())
            input.setSelection(clip.trim().length)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("一键导入订阅链接")
            .setView(c)
            .setPositiveButton("立即导入") { _, _ ->
                runQuickImport(
                    input.text.toString(),
                    replaceBox.isChecked,
                    headersInput.text.toString()
                )
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.setOnShowListener {
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()
    }

    private fun clipboardText(): String? = runCatching {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager?.primaryClip?.getItemAt(0)?.text?.toString()
    }.getOrNull()

    private fun quickImportFromClipboard() {
        val text = clipboardText()
        if (text.isNullOrBlank()) {
            toast("剪贴板是空的，请改用「粘贴链接，一键导入」")
            return
        }
        runQuickImport(text, false)
    }

    private fun runQuickImport(raw: String, replace: Boolean, headers: String = "") {
        if (raw.isBlank()) {
            toast("请先粘贴订阅链接")
            return
        }
        toast("正在拉取并校验链接…")
        Thread {
            val items = SubscriptionImporter.import(this, repository, raw, replace, headers)
            val summary = SubscriptionImporter.summary(items)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                refreshSources()
                AlertDialog.Builder(this)
                    .setTitle("导入结果")
                    .setMessage(summary)
                    .setPositiveButton("完成", null)
                    .show()
            }
        }.start()
    }

    private fun showDiagnosticsDialog() {
        val text = Diagnostics.device() + "\n\n" + DeviceProfile.summary(this) +
            "\n\n—— 最近记录 ——\n\n" + Diagnostics.read(this)
        AlertDialog.Builder(this)
            .setTitle("播放诊断")
            .setMessage(text)
            .setPositiveButton("关闭", null)
            .setNegativeButton("清空日志") { _, _ ->
                Diagnostics.clear(this)
                toast("诊断日志已清空")
            }
            .show()
    }

    /** 生成一份可直接取回的排障日志文件 */
    private fun exportDiagnostics() {
        val path = Diagnostics.export(this, repository)
        AlertDialog.Builder(this)
            .setTitle("日志文件已生成")
            .setMessage(
                "$path\n\n" +
                    "内容：设备 / TLS / 接口配置 / 每次拉取结果 / 播放错误 / 崩溃堆栈\n\n" +
                    "取回方式：\nadb pull \"$path\"\n" +
                    "或在盒子文件管理器打开 Android/data/com.iptv.tvplayer/files/Download/"
            )
            .setPositiveButton("完成", null)
            .show()
    }

    private fun showSourceEditor(existing: SourceConfig? = null) {
        val c = layoutInflater.inflate(R.layout.dialog_add_source, null)
        val urlInput = c.findViewById<EditText>(R.id.apiUrl)
        val methodInput = c.findViewById<EditText>(R.id.apiMethod)
        val headersInput = c.findViewById<EditText>(R.id.apiHeaders)
        val fieldsInput = c.findViewById<EditText>(R.id.apiFields)
        val bodyInput = c.findViewById<EditText>(R.id.apiBody)
        val tokenUrlInput = c.findViewById<EditText>(R.id.apiTokenUrl)
        val tokenPathInput = c.findViewById<EditText>(R.id.apiTokenPath)
        val refreshInput = c.findViewById<EditText>(R.id.apiRefresh)

        if (existing != null) {
            urlInput.setText(existing.url)
            methodInput.setText(existing.method)
            headersInput.setText(existing.headersText)
            fieldsInput.setText(existing.fieldsText)
            bodyInput.setText(existing.body)
            tokenUrlInput.setText(existing.tokenUrl)
            tokenPathInput.setText(existing.tokenPath)
            refreshInput.setText(existing.refreshMinutes.toString())
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing != null) "编辑接口" else getString(R.string.add_source_title))
            .setView(c)
            .setPositiveButton(if (existing != null) "保存修改" else getString(R.string.action_load)) { _, _ ->
                saveSource(
                    existing = existing,
                    url = urlInput.text.toString(),
                    headers = headersInput.text.toString(),
                    fields = fieldsInput.text.toString(),
                    method = methodInput.text.toString(),
                    body = bodyInput.text.toString(),
                    tokenUrl = tokenUrlInput.text.toString(),
                    tokenPath = tokenPathInput.text.toString(),
                    refresh = refreshInput.text.toString()
                )
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.setOnShowListener {
            urlInput.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()
    }

    private fun saveSource(
        existing: SourceConfig?,
        url: String,
        headers: String,
        fields: String,
        method: String,
        body: String,
        tokenUrl: String,
        tokenPath: String,
        refresh: String
    ) {
        val address = url.trim()
        if (address.isEmpty()) {
            toast("请输入接口地址")
            return
        }
        val valid = address.startsWith("http://") || address.startsWith("https://") ||
            address.startsWith("file://") || address.startsWith("/")
        if (!valid) {
            toast("地址需以 http:// 或 https:// 开头")
            return
        }
        val normalizedMethod = method.trim().uppercase().takeIf {
            it == "GET" || it == "POST"
        } ?: "GET"
        val cfg = if (existing != null) {
            existing.copy(
                url = address,
                headersText = headers.trim(),
                fieldsText = fields.trim(),
                method = normalizedMethod,
                body = body.trim(),
                tokenUrl = tokenUrl.trim(),
                tokenPath = tokenPath.trim(),
                refreshMinutes = refresh.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
            )
        } else {
            SourceConfig(
                url = address,
                headersText = headers.trim(),
                fieldsText = fields.trim(),
                method = normalizedMethod,
                body = body.trim(),
                tokenUrl = tokenUrl.trim(),
                tokenPath = tokenPath.trim(),
                refreshMinutes = refresh.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
            )
        }
        if (existing != null) repository.updateSource(cfg) else repository.addSource(cfg)
        afterSourceAdded(if (existing != null) "已保存修改" else "已添加接口，返回频道墙自动加载")
    }

    private fun showImportTextDialog() {
        val c = layoutInflater.inflate(R.layout.dialog_import_text, null)
        val input = c.findViewById<EditText>(R.id.importText)
        AlertDialog.Builder(this)
            .setTitle("导入 m3u 文本")
            .setView(c)
            .setPositiveButton("导入") { _, _ ->
                val text = input.text.toString()
                if (!text.contains("#EXTINF", true) && !text.trim().startsWith("http", true)) {
                    toast("内容不像 m3u 列表")
                } else {
                    val file = File(filesDir, "imported_playlist.m3u")
                    if (runCatching { file.writeText(text) }.isFailure) {
                        toast("保存列表失败")
                    } else {
                        repository.addSource(SourceConfig(url = file.absolutePath))
                        afterSourceAdded("已导入 ${text.lines().size} 行")
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showLocalFileDialog() {
        showTextInput(
            "从本地文件导入",
            "填绝对路径，例如 /storage/emulated/0/Download/list.m3u；盒子需允许 App 读存储。",
            ""
        ) { value ->
            val path = value.trim()
            if (path.isEmpty()) {
                toast("请输入文件路径")
            } else {
                repository.addSource(SourceConfig(url = path))
                afterSourceAdded("已添加本地文件源")
            }
        }
    }

    private fun afterSourceAdded(message: String) {
        prefs.notifySourceChanged()
        refreshSources()
        toast(message)
    }

    // ------------------------------------------------------------ 订阅备份（导出 / 导入）

    /** 导出：生成订阅备份 JSON 到 App 专属 Download 目录，并提示取回方式 */
    private fun exportBackup() {
        val json = BackupHelper.toBackupJson(repository)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = BackupHelper.writeToDownloads(this, "FlysnowTV_sources_$stamp.json", json)
        AlertDialog.Builder(this)
            .setTitle("订阅备份已生成")
            .setMessage(
                "${file.absolutePath}\n\n共 ${repository.sources.size} 个订阅。\n\n取回方式：\n" +
                    "adb pull \"${file.absolutePath}\"\n" +
                    "或把文件拷到 U 盘 / 网络存储，再到另一台盒子的设置里「导入订阅备份」。\n\n" +
                    "也可以直接用手机配置页（扫码）的「下载订阅备份」按钮获取。"
            )
            .setPositiveButton("完成", null)
            .show()
    }

    /** 导入：拉起系统文档选择器，挑一个 .json 备份文件 */
    private fun importBackup() {
        importLauncher.launch(arrayOf("application/json", "*/*"))
    }

    private fun handleBackupUri(uri: Uri) {
        val text = runCatching {
            contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            toast("无法读取该文件")
            return
        }
        val list = BackupHelper.fromBackupJson(text)
        if (list == null) {
            toast("不是有效的飞雪TV订阅备份")
            return
        }
        if (list.isEmpty()) {
            toast("备份里没有可用订阅")
            return
        }
        confirmImport(list)
    }

    /** 导入前让用户选「合并」还是「替换全部」 */
    private fun confirmImport(list: List<SourceConfig>) {
        val exists = repository.sources.map { it.url }.toSet()
        val toAdd = list.count { !exists.contains(it.url) }
        val duplicate = list.size - toAdd
        AlertDialog.Builder(this)
            .setTitle("导入订阅备份")
            .setMessage(
                "备份含 ${list.size} 个订阅。\n\n" +
                    "合并导入：新增 $toAdd 个、跳过 $duplicate 个重复（按地址判断），不影响现有其它订阅。\n" +
                    "替换全部：用备份里的订阅覆盖当前所有订阅。"
            )
            .setPositiveButton("合并导入") { _, _ ->
                var n = 0
                for (c in list) {
                    if (!exists.contains(c.url)) {
                        repository.addSource(c)
                        n++
                    }
                }
                afterSourceAdded("已合并导入 $n 个订阅")
            }
            .setNeutralButton("替换全部") { _, _ ->
                repository.sources = ArrayList(list)
                afterSourceAdded("已替换为 ${list.size} 个订阅")
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }


    // ------------------------------------------------------------ Token / EPG

    private fun refreshTokenNow() {
        toast("正在刷新 Token…")
        Thread {
            val ok = tokens.forceRefresh(repository.sources, prefs.tokenRefreshMinutes)
            runOnUiThread {
                refreshStatusRows()
                toast(if (ok) "Token 已刷新" else "刷新失败：${tokens.lastError ?: "未配置 Token 接口"}")
            }
        }.start()
    }

    private fun refreshEpgNow() {
        toast("正在加载 XMLTV 节目单…")
        Thread {
            val ok = epg.load(force = true)
            runOnUiThread {
                refreshStatusRows()
                toast(if (ok) "已加载 ${epg.programmeCount} 条节目" else "加载失败：${epg.lastError ?: "无数据"}")
            }
        }.start()
    }

    private fun refreshStatusRows() {
        epgStatusRow?.findViewById<TextView>(R.id.settingDesc)?.text = epg.status()
        tokenStatusRow?.findViewById<TextView>(R.id.settingDesc)?.text = tokens.status()
        libraryRow?.findViewById<TextView>(R.id.settingDesc)?.text = librarySummary()
    }

    private fun showEpgUrlDialog() {
        showTextInput(
            "XMLTV 节目单地址",
            "标准 XMLTV 的 .xml 或 .xml.gz 地址；支持 {token} 与播放源请求头。",
            prefs.epgUrl
        ) { value ->
            prefs.epgUrl = value
            refreshStatusRows()
            toast("XMLTV 地址已保存，将自动加载")
            Thread { epg.load(force = true); runOnUiThread { refreshStatusRows() } }.start()
        }
    }

    // ------------------------------------------------------------ 收藏夹

    private fun librarySummary(): String =
        "已收藏 ${prefs.favoriteKeys.size} 个　·　最近观看 ${prefs.recentKeys.size} 个　（点此清空）"

    private fun multiLineSummary(): String =
        "同名 / 同 tvg-id 的源自动合并成多线路，失败自动切换　·　点此查看"

    private fun reservationSummary(): String =
        "已预约 ${prefs.reservations.size} 个节目　·　点此查看 / 清空"

    private fun hiddenSummary(): String =
        "已隐藏 ${prefs.hiddenChannels.size} 个（连续播放失败）　·　点此恢复全部"

    private fun refreshHiddenRow() {
        hiddenRow?.findViewById<TextView>(R.id.settingDesc)?.text = hiddenSummary()
    }

    private fun openGrid() {
        EpgGridActivity.pendingChannels = emptyList()
        EpgGridActivity.pendingIndex = 0
        EpgGridActivity.pendingEpg = epg
        startActivity(Intent(this, EpgGridActivity::class.java))
    }

    private fun showReservationDialog() {
        val list = prefs.reservations.toList()
        val message = if (list.isEmpty()) {
            "还没有预约节目。播放时按 GUIDE 打开节目时间轴，选中节目按 OK 即可预约。"
        } else {
            list.take(12).joinToString("\n") { key ->
                val start = key.substringAfterLast('|').toLongOrNull() ?: 0L
                "${Catchup.clock(start)}　${key.substringBeforeLast('|')}"
            } + if (list.size > 12) "\n… 等 ${list.size} 个" else ""
        }
        AlertDialog.Builder(this)
            .setTitle("节目预约")
            .setMessage(message)
            .setPositiveButton("清空全部预约") { _, _ ->
                prefs.clearReservations()
                toast("已清空预约")
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun showLibraryDialog() {
        val favorites = prefs.favoriteKeys.toList()
        val message = buildString {
            append("已收藏 ").append(favorites.size).append(" 个频道\n")
            append("最近观看 ").append(prefs.recentKeys.size).append(" 个频道\n\n")
            if (favorites.isEmpty()) {
                append("在频道墙上长按 OK 即可收藏；收藏后分组条会出现「★ 收藏」。")
            } else {
                append("收藏标识（tvg-id / 频道名）：\n")
                append(favorites.take(12).joinToString("\n"))
                if (favorites.size > 12) append("\n… 等 ${favorites.size} 个")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("收藏夹 / 最近观看")
            .setMessage(message)
            .setPositiveButton("清空收藏") { _, _ ->
                prefs.clearFavorites()
                prefs.notifySourceChanged()
                refreshStatusRows()
                toast("收藏夹已清空")
            }
            .setNeutralButton("清空最近") { _, _ ->
                prefs.clearRecent()
                prefs.notifySourceChanged()
                refreshStatusRows()
                toast("最近观看已清空")
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    // ------------------------------------------------------------ 输入弹窗

    private fun showTemplateDialog() {
        showTextInput(
            "台标地址模板",
            "支持 {num} / {short} / {name}，例如 https://example.com/logo/{short}.png",
            prefs.logoTemplate
        ) { value ->
            prefs.logoTemplate = value
            templateRow?.findViewById<TextView>(R.id.settingDesc)?.text =
                "当前：${value.ifBlank { "（未填写）" }}"
            toast("台标模板已保存")
        }
    }

    private fun showTextInput(title: String, hint: String, initial: String, onOk: (String) -> Unit) {
        val c = layoutInflater.inflate(R.layout.dialog_text_input, null)
        c.findViewById<TextView>(R.id.inputHint).text = hint
        val input = c.findViewById<EditText>(R.id.inputValue)
        input.setText(initial)
        input.setSelection(input.text.length)
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(c)
            .setPositiveButton("保存") { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.setOnShowListener {
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()
    }

    private fun showAboutDialog() {
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "1.9.4" }
        val message = buildString {
            append("飞雪TV　·　Android TV 直播播放器\n")
            append("版本 ").append(version).append("\n\n")

            append("【版权与授权】\n")
            append("· 本应用不内置、不分发任何电视台直播流地址。\n")
            append("· 所有频道均来自使用者自行添加的订阅源，请确认你有权使用。\n")
            append("· 播放源、接口地址、Token 与台标图片均由使用者自行提供。\n")
            append("· 仅供个人自用测试，请勿分发或公开提供流地址；商业使用需另行取得授权。\n\n")

            append("【项目主页】\n")
            append(repoUrl)
        }
        AlertDialog.Builder(this)
            .setTitle("关于 飞雪TV")
            .setMessage(message)
            .setPositiveButton("关闭", null)
            .setNeutralButton("打开项目主页") { _, _ -> openUrl(repoUrl) }
            .show()
    }

    // ------------------------------------------------------------ 日志上报反馈

    private companion object Feedback {
        const val MODE_QR = 0
        const val MODE_SHARE = 1
    }

    /** 生成日志前让用户补一句问题描述，随报告一起发出 */
    private fun showFeedbackDialog() {
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "1.9.4" }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(20)
            setPadding(p, dp(12), p, 0)
        }
        box.addView(TextView(this).apply {
            text = buildString {
                append("反馈方式：扫码取日志 / 微信 / QQ 分享\n")
                append("版本 v").append(version).append("\n")
                append("设备 ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
                    .append(" / Android ").append(Build.VERSION.RELEASE).append("\n\n")
                append("日志包含：设备与网络栈、播放源（已脱敏）、起播 / 错误 / 换线路记录、崩溃堆栈、logcat 片段。\n")
                append("订阅地址、Token 与自定义 Header 会自动打码。\n\n")
                append("可补充问题描述，会随日志一起发出：")
            }
            setTextColor(Color.parseColor("#C3D7F5"))
            textSize = 12f
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        val input = EditText(this).apply {
            hint = "例如：CCTV-1 没声音 / 悬停不出预览 / 检测更新失败…"
            setTextColor(Color.parseColor("#EAF1FF"))
            setHintTextColor(Color.parseColor("#93AED8"))
            textSize = 13f
            isSingleLine = false
            maxLines = 4
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(10)
            layoutParams = lp
        }
        box.addView(input)

        val dialog = AlertDialog.Builder(this)
            .setTitle("日志上报反馈")
            .setView(box)
            .setPositiveButton("扫码分享") { _, _ ->
                prepareFeedback(input.text.toString(), MODE_QR)
            }
            .setNeutralButton("微信 / QQ 分享") { _, _ ->
                prepareFeedback(input.text.toString(), MODE_SHARE)
            }
            .setNegativeButton("关闭", null)
            .create()
        dialog.show()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    private fun prepareFeedback(note: String, mode: Int) {
        val file = runCatching { FeedbackReporter.build(this, repository, note) }
            .getOrElse {
                toast("日志生成失败：${it.message}")
                return
            }
        // 无论走哪条通道，先把全文放剪贴板，盒子没有任何分享 App 时也能粘贴出去
        FeedbackReporter.copy(this, runCatching { file.readText() }.getOrDefault(""))
        Diagnostics.log(this, "FEEDBACK", "反馈日志 ${file.name} · ${file.length()} bytes · mode=$mode")

        if (mode == MODE_QR) {
            val started = runCatching {
                startActivity(Intent(this, QrConfigActivity::class.java).apply {
                    putExtra(QrConfigActivity.EXTRA_MODE, QrConfigActivity.MODE_LOGS)
                })
                true
            }.getOrDefault(false)
            if (started) toast("日志已生成并复制，请用手机扫码取回")
            else showFeedbackFallback(file)
        } else {
            showShareChooser(file, note)
        }
    }

    private fun showShareChooser(file: File, note: String) {
        AlertDialog.Builder(this)
            .setTitle("选择分享方式")
            .setItems(arrayOf("分享到微信", "分享到 QQ", "更多分享方式", "仅保存 / 复制")) { _, which ->
                when (which) {
                    0 -> doShare(file, note, FeedbackReporter.WECHAT)
                    1 -> doShare(file, note, FeedbackReporter.QQ)
                    2 -> doShare(file, note, null)
                    else -> showFeedbackFallback(file)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doShare(file: File, note: String, target: String?) {
        if (FeedbackReporter.share(this, file, note, target)) {
            toast("已调起分享；日志同时已复制到剪贴板")
        } else {
            toast("本机没有可分享的应用，日志已保存并复制")
            showFeedbackFallback(file)
        }
    }

    private fun showFeedbackFallback(file: File) {
        AlertDialog.Builder(this)
            .setTitle("日志已保存")
            .setMessage(
                "日志全文已复制到剪贴板，并保存到：\n\n" +
                    "${file.absolutePath}\n\n" +
                    "其它取回方式：\n" +
                    "· 设置 → 手机扫码配置 → 「日志反馈」→ 打开日志页\n" +
                    "· adb pull \"${file.absolutePath}\"\n" +
                    "· 直接粘贴到微信 / QQ 发送"
            )
            .setPositiveButton("完成", null)
            .show()
    }

    // ------------------------------------------------------------ 在线更新

    private data class ReleaseInfo(
        val tag: String,
        val name: String,
        val body: String,
        val htmlUrl: String,
        val apkUrl: String,
        val apkSize: Long
    )

    private val repoUrl = "https://github.com/luoyechenfei/FlysnowTV"
    private val releasesUrl = "https://github.com/luoyechenfei/FlysnowTV/releases"
    private val latestApi = "https://api.github.com/repos/luoyechenfei/FlysnowTV/releases/latest"
    private val updateFileName = "FlysnowTV-update.apk"
    private var updateDownloadId = -1L
    private var downloadReceiverRegistered = false
    /** App 内下载进度对话框；后台线程只通过 runOnUiThread 更新这几个 View */
    private var updateDialog: AlertDialog? = null
    @Volatile private var updateCancelled = false
    @Volatile private var activeDownload: HttpURLConnection? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != updateDownloadId) return
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
            var success = false
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (c.moveToFirst()) {
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    success = status == DownloadManager.STATUS_SUCCESSFUL
                }
            }
            if (success) {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                val file = dir?.let { File(it, updateFileName) }
                if (file != null && file.exists()) installApk(file) else openUrl(releasesUrl)
            } else {
                toast("更新包下载失败，可到发布页手动下载")
            }
        }
    }

    private fun registerDownloadReceiver() {
        if (downloadReceiverRegistered) return
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(downloadReceiver, filter)
        }
        downloadReceiverRegistered = true
    }

    /** 检测更新：对比 GitHub Releases 最新 tag 与本机版本 */
    private fun checkForUpdate() {
        toast("正在检测更新…")
        Thread {
            val result = runCatching { fetchLatestRelease() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { showUpdateResult(it) }
                    .onFailure { showUpdateFailure(it) }
            }
        }.start()
    }

    private fun fetchLatestRelease(): ReleaseInfo {
        val conn = (URL(latestApi).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "FlysnowTV-Android")
        }
        try {
            if (conn.responseCode != 200) {
                throw IllegalStateException("GitHub 返回 HTTP ${conn.responseCode}")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            var apkUrl = ""
            var apkSize = 0L
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    if (a.optString("name").endsWith(".apk", true)) {
                        apkUrl = a.optString("browser_download_url")
                        apkSize = a.optLong("size")
                        break
                    }
                }
            }
            return ReleaseInfo(
                tag = json.optString("tag_name"),
                name = json.optString("name"),
                body = json.optString("body"),
                htmlUrl = json.optString("html_url").ifBlank { releasesUrl },
                apkUrl = apkUrl,
                apkSize = apkSize
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun localVersion(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()

    /** 按数字段比较版本号：v1.9.1 > 1.9 > 1.8，1.10 > 1.9（支持三段版本号） */
    private fun isNewer(remote: String, local: String): Boolean {
        fun numbers(s: String) = Regex("\\d+").findAll(s)
            .map { it.value.toIntOrNull() ?: 0 }.toList()
        val r = numbers(remote)
        val l = numbers(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv > lv) return true
            if (rv < lv) return false
        }
        return false
    }

    private fun showUpdateResult(rel: ReleaseInfo) {
        val current = localVersion()
        if (rel.tag.isBlank()) {
            showUpdateFailure(IllegalStateException("未读取到版本信息"))
            return
        }
        if (!isNewer(rel.tag, current)) {
            AlertDialog.Builder(this)
                .setTitle("已是最新版本")
                .setMessage("当前版本 $current，GitHub Releases 上最新的发布版本为 ${rel.tag}。")
                .setPositiveButton("知道了", null)
                // 即便是最新版，也允许重新下载安装，方便验证在线更新链路 / 修复损坏安装
                .setNeutralButton("重新下载安装") { _, _ ->
                    if (rel.apkUrl.isNotBlank()) downloadAndInstall(rel) else openUrl(releasesUrl)
                }
                .setNegativeButton("查看发布页") { _, _ -> openUrl(releasesUrl) }
                .show()
            return
        }
        val sizeText = if (rel.apkSize > 0) {
            "（%.1f MB）".format(rel.apkSize / 1024.0 / 1024.0)
        } else ""
        val summary = rel.body.lineSequence()
            .filter { it.isNotBlank() }
            .take(8)
            .joinToString("\n")
            .ifBlank { "详见发布页更新说明" }
        val title = rel.name.takeIf { it.isNotBlank() }?.let { "$it\n\n" } ?: ""
        AlertDialog.Builder(this)
            .setTitle("发现新版本 ${rel.tag}")
            .setMessage(
                "当前版本：$current\n最新版本：${rel.tag}$sizeText\n\n" +
                    title + summary + "\n\n下载完成后会自动弹出安装界面。"
            )
            .setPositiveButton("一键在线更新") { _, _ ->
                if (rel.apkUrl.isNotBlank()) downloadAndInstall(rel) else openUrl(releasesUrl)
            }
            .setNeutralButton("查看发布页") { _, _ -> openUrl(rel.htmlUrl) }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun showUpdateFailure(error: Throwable) {
        AlertDialog.Builder(this)
            .setTitle("检测更新失败")
            .setMessage("无法连接 GitHub Releases：${error.message.orEmpty()}\n\n可直接打开发布页查看最新版本。")
            .setPositiveButton("打开 Releases") { _, _ -> openUrl(releasesUrl) }
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 在线更新：App 内自下载并实时显示进度（百分比 / 进度条 / 已下载 / 速度 / 剩余时间）。
     * 旧实现只把任务丢给系统 DownloadManager，界面上毫无反馈，容易被当成「没开始下载」。
     */
    private fun downloadAndInstall(rel: ReleaseInfo) {
        if (rel.apkUrl.isBlank()) {
            openUrl(releasesUrl)
            return
        }
        runCatching { updateDialog?.dismiss() }
        updateDialog = null
        updateCancelled = false

        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(cacheDir, "updates")
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, updateFileName)
        val part = File(dir, "$updateFileName.part")
        runCatching { target.delete(); part.delete() }

        val view = layoutInflater.inflate(R.layout.dialog_update_progress, null)
        val statusText = view.findViewById<TextView>(R.id.updateStatus)
        val percentText = view.findViewById<TextView>(R.id.updatePercent)
        val detailText = view.findViewById<TextView>(R.id.updateDetail)
        val speedText = view.findViewById<TextView>(R.id.updateSpeed)
        val bar = view.findViewById<ProgressBar>(R.id.updateProgress)

        var dialog: AlertDialog? = null
        /** 后台线程安全地刷新进度；对话框关闭或页面退出后直接丢弃回调 */
        fun publish(
            status: String, percent: Int, downloaded: Long,
            total: Long, bytesPerSec: Long, indeterminate: Boolean
        ) {
            if (updateCancelled || isFinishing || isDestroyed) return
            runOnUiThread {
                if (updateCancelled || dialog?.isShowing != true) return@runOnUiThread
                statusText.text = status
                bar.isIndeterminate = indeterminate
                if (indeterminate) {
                    percentText.text = "--"
                    detailText.text = "已下载 ${formatSize(downloaded)}"
                } else {
                    val p = percent.coerceIn(0, 100)
                    bar.progress = p
                    percentText.text = "$p%"
                    detailText.text = if (total > 0) {
                        "${formatSize(downloaded)} / ${formatSize(total)}"
                    } else "已下载 ${formatSize(downloaded)}"
                }
                val remain = if (total > 0 && bytesPerSec > 0) {
                    "　·　预计剩余 ${formatEta((total - downloaded).coerceAtLeast(0) / bytesPerSec)}"
                } else ""
                speedText.text = if (bytesPerSec > 0) {
                    "${formatSize(bytesPerSec)}/s$remain"
                } else "正在建立连接…"
            }
        }

        // Release 说明里写了 SHA-256，下载完顺手校验，避免装到半截包
        val expectedSha = Regex("""SHA-256[：:]\s*`?([a-fA-F0-9]{64})`?""")
            .find(rel.body)?.groupValues?.get(1)?.lowercase(Locale.ROOT)

        dialog = AlertDialog.Builder(this)
            .setTitle("正在更新到 ${rel.tag.ifBlank { "最新版本" }}")
            .setView(view)
            .setCancelable(false)
            .setNegativeButton("取消下载") { _, _ ->
                updateCancelled = true
                runCatching { activeDownload?.disconnect() }
                toast("已取消更新包下载")
            }
            .show()
        updateDialog = dialog

        Thread {
            var conn: HttpURLConnection? = null
            try {
                publish("正在连接下载服务器…", 0, 0, rel.apkSize, 0, true)
                conn = openDownloadConnection(rel.apkUrl)
                val headerSize = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                val total = if (headerSize > 0) headerSize else rel.apkSize
                var downloaded = 0L
                var lastUi = 0L
                var lastSampleAt = System.currentTimeMillis()
                var lastSampleBytes = 0L
                var speed = 0L
                BufferedInputStream(conn.inputStream, 8192).use { input ->
                    FileOutputStream(part).use { out ->
                        val buf = ByteArray(8192)
                        while (!updateCancelled) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            downloaded += n
                            val now = System.currentTimeMillis()
                            if (now - lastUi >= 150) {
                                val elapsed = now - lastSampleAt
                                if (elapsed >= 500) {
                                    val instant = (downloaded - lastSampleBytes) * 1000 /
                                        elapsed.coerceAtLeast(1)
                                    speed = if (speed == 0L) instant else (speed * 7 + instant * 3) / 10
                                    lastSampleAt = now
                                    lastSampleBytes = downloaded
                                }
                                val pct = if (total > 0) {
                                    (downloaded * 100 / total).toInt().coerceIn(0, 99)
                                } else -1
                                publish("正在下载更新包…", pct, downloaded, total, speed, total <= 0)
                                lastUi = now
                            }
                        }
                        out.flush()
                    }
                }
                if (updateCancelled) {
                    runCatching { part.delete() }
                    return@Thread
                }
                conn.disconnect()
                conn = null

                // 完整性校验：长度 → APK(ZIP) 文件头 → Release 说明中的 SHA-256（若提供）
                publish("正在校验更新包…", 100, downloaded,
                    if (total > 0) total else downloaded, 0, false)
                if (total > 0 && part.length() != total) {
                    throw IllegalStateException(
                        "文件不完整（${formatSize(part.length())} / ${formatSize(total)}）"
                    )
                }
                val head = ByteArray(2)
                val read = part.inputStream().use { it.read(head) }
                if (read != 2 || head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) {
                    throw IllegalStateException("下载内容不是有效的 APK 安装包")
                }
                if (!expectedSha.isNullOrBlank() && !sha256(part).equals(expectedSha, true)) {
                    throw IllegalStateException("安装包校验失败（SHA-256 不匹配）")
                }
                runCatching { target.delete() }
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }

                publish("下载完成 · 校验通过", 100, target.length(), target.length(), 0, false)
                uiHandler.postDelayed(object : Runnable {
                    override fun run() {
                        if (isFinishing || isDestroyed) return
                        runCatching { dialog?.dismiss() }
                        updateDialog = null
                        installApk(target)
                    }
                }, 700L)
            } catch (e: Exception) {
                runCatching { conn?.disconnect() }
                runCatching { part.delete() }
                if (updateCancelled || isFinishing || isDestroyed) return@Thread
                val message = when (e) {
                    is java.io.InterruptedIOException -> "连接被中断，请检查网络后重试"
                    else -> e.message ?: "未知错误"
                }
                runOnUiThread {
                    runCatching { dialog?.dismiss() }
                    updateDialog = null
                    showDownloadFailure(rel, message)
                }
            } finally {
                runCatching { conn?.disconnect() }
                activeDownload = null
            }
        }.start()
    }

    /** 手动跟随 302：GitHub Release 附件会跳到带签名的 CDN 域名 */
    private fun openDownloadConnection(urlText: String): HttpURLConnection {
        var url = URL(urlText)
        for (hop in 0 until 6) {
            if (updateCancelled) throw InterruptedException()
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "FlysnowTV-Android")
                setRequestProperty("Accept", "*/*")
                // 禁止自动 gzip：否则 Content-Length 是压缩后长度，落盘字节数会对不上，
                // 导致「文件不完整」误判（APK 本身已是压缩包，无需再压）
                setRequestProperty("Accept-Encoding", "identity")
            }
            activeDownload = conn
            val code = conn.responseCode
            if (code == 200) return conn
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            if (code !in 300..399 || location.isNullOrBlank()) {
                throw IllegalStateException("下载服务器返回 HTTP $code")
            }
            url = URL(url, location)
        }
        throw IllegalStateException("下载重定向次数过多")
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> "%.2f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun formatEta(seconds: Long): String = when {
        seconds <= 0 -> "即将完成"
        seconds < 60 -> "约 $seconds 秒"
        else -> "约 ${seconds / 60} 分 ${seconds % 60} 秒"
    }

    private fun showDownloadFailure(rel: ReleaseInfo, message: String) {
        AlertDialog.Builder(this)
            .setTitle("更新包下载失败")
            .setMessage("$message\n\n可重试 App 内下载，或改用系统下载（通知栏显示进度），也可到发布页手动下载。")
            .setPositiveButton("重试") { _, _ -> downloadAndInstall(rel) }
            .setNeutralButton("系统下载") { _, _ -> downloadWithSystemManager(rel.apkUrl) }
            .setNegativeButton("打开发布页") { _, _ -> openUrl(releasesUrl) }
            .show()
    }

    /** 兜底：部分盒子的网络栈无法直连 CDN 时，退回系统下载管理器 */
    private fun downloadWithSystemManager(apkUrl: String) {
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (dm == null || dir == null) {
            openUrl(apkUrl)
            return
        }
        runCatching { File(dir, updateFileName).takeIf { it.exists() }?.delete() }
        try {
            val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
                setTitle("飞雪TV 更新包")
                setDescription("下载完成后自动弹出安装")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setMimeType("application/vnd.android.package-archive")
                setDestinationInExternalFilesDir(
                    this@SettingsActivity, Environment.DIRECTORY_DOWNLOADS, updateFileName
                )
            }
            updateDownloadId = dm.enqueue(request)
            toast("已改用系统下载，进度请在通知栏查看")
        } catch (e: Exception) {
            openUrl(apkUrl)
        }
    }

    private fun installApk(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(install)
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("请手动安装")
                .setMessage("系统未能自动打开安装界面，可到「版本发布页」下载 APK 后安装。")
                .setPositiveButton("打开发布页") { _, _ -> openUrl(releasesUrl) }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { toast("无法打开链接：$url") }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
