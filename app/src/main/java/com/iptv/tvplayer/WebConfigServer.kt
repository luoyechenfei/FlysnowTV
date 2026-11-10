package com.iptv.tvplayer

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * 电视盒子上的轻量局域网配置服务（仅绑定本机 LAN 地址，端口 8899）。
 *
 * 手机扫码打开网页后，可以增删第三方接口（含 POST / Token 配置）、粘贴 m3u 文本、
 * 修改画质 / 播放器 / 台标 / 预览 / XMLTV / Token 刷新设置。
 * 所有写操作都要带上电视屏幕上显示的 4 位 PIN，防止同网段其他人误改。
 */
class WebConfigServer(
    private val context: Context,
    private val pin: String,
    private val onEvent: (String) -> Unit
) {

    companion object {
        fun localIp(): String {
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val nif = interfaces.nextElement()
                    if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
                    val addresses = nif.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (address is Inet4Address && address.isSiteLocalAddress &&
                            !address.isLoopbackAddress
                        ) {
                            return address.hostAddress ?: "127.0.0.1"
                        }
                    }
                }
                "127.0.0.1"
            } catch (ignored: Exception) {
                "127.0.0.1"
            }
        }
    }

    private val repository = PlaylistRepository(context)
    private val prefs = Prefs(context)
    private val tokens = TokenManager(context)
    private val epg = EpgRepository(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    fun start(port: Int = Prefs.WEB_CONFIG_PORT) {
        if (running) return
        running = true
        acceptThread = Thread {
            try {
                serverSocket = ServerSocket(port, 32).apply { reuseAddress = true }
                emit("配置服务已启动，等待手机连接…")
                while (running) {
                    val client = serverSocket?.accept() ?: break
                    Thread { handle(client) }.start()
                }
            } catch (error: Exception) {
                if (running) emit("服务启动失败：${error.message ?: "端口被占用"}")
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
    }

    private fun emit(message: String) {
        mainHandler.post { onEvent(message) }
    }

    // ------------------------------------------------------------ HTTP

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 10_000
            val input = client.getInputStream()
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            var headerEnd = -1
            var contentLength = 0
            var expectedTotal = Int.MAX_VALUE

            while (buffer.size() < 2_000_000) {
                val read = input.read(chunk)
                if (read < 0) break
                buffer.write(chunk, 0, read)
                val bytes = buffer.toByteArray()
                if (headerEnd < 0) {
                    headerEnd = indexOfHeaderEnd(bytes)
                    if (headerEnd >= 0) {
                        val header = String(bytes, 0, headerEnd, Charsets.ISO_8859_1)
                        contentLength = header.lines()
                            .firstOrNull { it.trim().startsWith("content-length:", true) }
                            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                        expectedTotal = headerEnd + 4 + contentLength
                    }
                }
                if (headerEnd >= 0 && bytes.size >= expectedTotal) break
            }

            val all = buffer.toByteArray()
            if (headerEnd < 0) return
            val header = String(all, 0, headerEnd, Charsets.ISO_8859_1)
            val requestLine = header.lines().firstOrNull().orEmpty()
            val parts = requestLine.split(' ')
            val method = parts.getOrElse(0) { "GET" }
            val fullPath = parts.getOrElse(1) { "/" }
            val path = fullPath.substringBefore('?')
            val body = if (contentLength > 0 && all.size >= headerEnd + 4 + contentLength) {
                String(all, headerEnd + 4, contentLength, Charsets.UTF_8)
            } else {
                ""
            }

            if (method.equals("OPTIONS", true)) {
                respond(client, 200, "application/json", "{}")
                return
            }

            when {
                method == "GET" && (path == "/" || path == "/index.html") ->
                    respond(client, 200, "text/html; charset=utf-8", html())

                method == "GET" && path == "/api/backup/export" -> {
                    if (!checkPin(queryOf(fullPath)["pin"])) {
                        respond(client, 403, "application/json; charset=utf-8", errorJson("PIN 不正确").toString())
                    } else {
                        respondDownload(client, "FlysnowTV_sources.json", BackupHelper.toBackupJson(repository))
                    }
                }

                method == "GET" && path == "/api/state" -> {
                    if (!checkPin(queryOf(fullPath)["pin"])) {
                        respond(client, 403, "application/json; charset=utf-8", errorJson("PIN 不正确").toString())
                    } else {
                        respond(client, 200, "application/json; charset=utf-8", stateJson().toString())
                    }
                }

                // 日志反馈：手机扫码后打开分享页，页面再通过这个接口取脱敏日志全文
                method == "GET" && path == "/log" -> {
                    val pin = queryOf(fullPath)["pin"].orEmpty()
                    if (!checkPin(pin)) {
                        respond(client, 403, "text/html; charset=utf-8", logPage("PIN 不正确，请看电视屏幕上的配对码", pin))
                    } else {
                        respond(client, 200, "text/html; charset=utf-8", logPage("", pin))
                    }
                }

                method == "GET" && path == "/api/logs" -> {
                    if (!checkPin(queryOf(fullPath)["pin"])) {
                        respond(client, 403, "text/plain; charset=utf-8", "PIN 不正确")
                    } else {
                        val text = Diagnostics.latestFeedback(context).ifBlank {
                            Diagnostics.feedbackReport(context, repository, "手机端扫码获取")
                        }
                        respond(client, 200, "text/plain; charset=utf-8", text)
                    }
                }

                method == "POST" -> {
                    val json = runCatching { JSONObject(body) }.getOrNull()
                    if (json == null) {
                        respond(client, 400, "application/json; charset=utf-8", errorJson("请求格式错误").toString())
                        return
                    }
                    if (!checkPin(json.optString("pin", ""))) {
                        respond(client, 403, "application/json; charset=utf-8", errorJson("PIN 不正确，请看电视屏幕").toString())
                        return
                    }
                    respond(client, 200, "application/json; charset=utf-8", handlePost(path, json).toString())
                }

                else -> respond(client, 404, "application/json; charset=utf-8", errorJson("Not found").toString())
            }
        } catch (ignored: Exception) {
            // 手机刷新/关闭页面导致的连接中断，忽略即可
        } finally {
            runCatching { client.close() }
        }
    }

    private fun handlePost(path: String, json: JSONObject): JSONObject {
        return try {
            when (path) {
                "/api/source/quick" -> {
                    val raw = json.optString("text", "")
                    val items = SubscriptionImporter.import(
                        context, repository, raw, json.optBoolean("replace", false),
                        json.optString("headers", "")
                    )
                    val ok = items.count { it.ok }
                    emit("手机一键导入：$ok/${items.size} 个链接成功")
                    JSONObject().apply {
                        put("ok", ok > 0)
                        put("message", "成功 $ok/${items.size} 个链接，共 ${items.sumOf { it.channels }} 个频道")
                        put("detail", SubscriptionImporter.summary(items))
                        put("state", stateJson())
                    }
                }

                "/api/source/add" -> {
                    val url = json.optString("url", "").trim()
                    if (url.isBlank()) return errorJson("接口地址不能为空")
                    val config = SourceConfig(
                        url = url,
                        headersText = json.optString("headers", ""),
                        fieldsText = json.optString("fields", ""),
                        method = json.optString("method", "GET").ifBlank { "GET" },
                        body = json.optString("body", ""),
                        tokenUrl = json.optString("tokenUrl", ""),
                        tokenPath = json.optString("tokenPath", ""),
                        refreshMinutes = json.optInt("refreshMinutes", 0)
                    )
                    repository.addSource(config)
                    prefs.notifySourceChanged()
                    emit("手机添加了接口：${config.label()}")
                    okJson("接口已保存，电视端即将刷新")
                }

                "/api/source/delete" -> {
                    repository.removeSource(json.optString("id", ""))
                    prefs.notifySourceChanged()
                    emit("手机删除了一个接口")
                    okJson("已删除")
                }

                "/api/source/update" -> {
                    val id = json.optString("id", "")
                    val existing = repository.sources.firstOrNull { it.id == id }
                        ?: return errorJson("找不到该接口")
                    val url = json.optString("url", "").trim()
                    if (url.isBlank()) return errorJson("接口地址不能为空")
                    val cfg = existing.copy(
                        url = url,
                        headersText = json.optString("headers", existing.headersText),
                        fieldsText = json.optString("fields", existing.fieldsText),
                        method = json.optString("method", existing.method).ifBlank { "GET" },
                        body = json.optString("body", existing.body),
                        tokenUrl = json.optString("tokenUrl", existing.tokenUrl),
                        tokenPath = json.optString("tokenPath", existing.tokenPath),
                        refreshMinutes = json.optInt("refreshMinutes", existing.refreshMinutes)
                    )
                    repository.updateSource(cfg)
                    prefs.notifySourceChanged()
                    emit("手机更新了接口：${cfg.label()}")
                    okJson("已保存修改")
                }

                "/api/source/toggle" -> {
                    val id = json.optString("id", "")
                    val existing = repository.sources.firstOrNull { it.id == id }
                        ?: return errorJson("找不到该接口")
                    repository.updateSource(existing.copy(enabled = !existing.enabled))
                    prefs.notifySourceChanged()
                    emit("手机${if (existing.enabled) "停用" else "启用"}了一个接口")
                    okJson(if (existing.enabled) "已停用" else "已启用")
                }

                "/api/source/move" -> {
                    val id = json.optString("id", "")
                    val up = json.optBoolean("up", true)
                    val moved = repository.moveSource(id, up)
                    prefs.notifySourceChanged()
                    if (moved) okJson(if (up) "已上移" else "已下移")
                    else errorJson(if (up) "已经是第一个" else "已经是最后一个")
                }

                "/api/source/text" -> {
                    val text = json.optString("text", "")
                    if (!text.contains("#EXTINF", true) && !text.trim().startsWith("http", true)) {
                        return errorJson("内容不像 m3u 列表")
                    }
                    val file = File(context.filesDir, "imported_playlist.m3u")
                    file.writeText(text)
                    repository.addSource(SourceConfig(url = file.absolutePath))
                    prefs.notifySourceChanged()
                    emit("手机导入了 ${text.lines().size} 行 m3u 文本")
                    okJson("m3u 文本已导入")
                }

                "/api/backup/import" -> {
                    val text = json.optString("text", "")
                    val list = BackupHelper.fromBackupJson(text)
                        ?: return errorJson("内容不是有效的飞雪TV订阅备份")
                    if (list.isEmpty()) return errorJson("备份里没有可用订阅")
                    val mode = json.optString("mode", "merge")
                    if (mode == "replace") {
                        repository.sources = ArrayList(list)
                        prefs.notifySourceChanged()
                        emit("手机用备份替换了 ${list.size} 个订阅")
                        okJson("已替换为 ${list.size} 个订阅")
                    } else {
                        val exists = repository.sources.map { it.url }.toSet()
                        var added = 0
                        for (c in list) if (!exists.contains(c.url)) { repository.addSource(c); added++ }
                        prefs.notifySourceChanged()
                        emit("手机合并导入了 $added 个订阅")
                        okJson("已合并导入 $added 个订阅（跳过 ${list.size - added} 个重复）")
                    }
                }

                "/api/settings" -> {
                    val s = json.optJSONObject("settings") ?: return errorJson("缺少 settings")
                    prefs.quality = s.optInt("quality", prefs.quality)
                    prefs.engine = s.optInt("engine", prefs.engine)
                    prefs.logoMode = s.optInt("logoMode", prefs.logoMode)
                    prefs.coverMode = s.optInt("coverMode", prefs.coverMode)
                    prefs.logoTemplate = s.optString("logoTemplate", prefs.logoTemplate)
                    prefs.previewEnabled = s.optBoolean("preview", prefs.previewEnabled)
                    prefs.backgroundPlay = s.optBoolean("background", prefs.backgroundPlay)
                    prefs.autoSwitchLine = s.optBoolean("autoSwitch", prefs.autoSwitchLine)
                    prefs.epgUrl = s.optString("epgUrl", prefs.epgUrl)
                    prefs.epgIntervalIndex = s.optInt("epgInterval", prefs.epgIntervalIndex)
                    prefs.tokenIntervalIndex = s.optInt("tokenInterval", prefs.tokenIntervalIndex)
                    // 与本地设置中心保持一致的另外两个开关
                    val oldHide = prefs.hideFailedChannels
                    prefs.hideFailedChannels = s.optBoolean("hideFailed", oldHide)
                    prefs.externalFallback = s.optBoolean("externalFallback", prefs.externalFallback)
                    if (oldHide != prefs.hideFailedChannels) prefs.notifySourceChanged()
                    emit("手机更新了播放设置")
                    okJson("设置已保存")
                }

                "/api/token/refresh" -> {
                    Thread {
                        val ok = tokens.forceRefresh(repository.sources, prefs.tokenRefreshMinutes)
                        emit(if (ok) "Token 已刷新" else "Token 刷新失败：${tokens.lastError ?: "未配置 Token 接口"}")
                    }.start()
                    okJson("正在刷新 Token…")
                }

                "/api/epg/refresh" -> {
                    Thread {
                        val ok = epg.load(force = true)
                        emit(if (ok) "XMLTV 节目单已刷新（${epg.programmeCount} 条）" else "EPG 刷新失败：${epg.lastError ?: "无数据"}")
                    }.start()
                    okJson("正在刷新节目单…")
                }

                else -> errorJson("未知操作")
            }
        } catch (error: Exception) {
            errorJson(error.message ?: "保存失败")
        }
    }

    private fun checkPin(value: String?): Boolean = value == pin

    private fun stateJson(): JSONObject {
        val sources = JSONArray()
        for (config in repository.sources) {
            val item = config.toJson()
            item.put("describe", config.describe())
            sources.put(item)
        }
        val settings = JSONObject().apply {
            put("quality", prefs.quality)
            put("engine", prefs.engine)
            put("logoMode", prefs.logoMode)
            put("coverMode", prefs.coverMode)
            put("logoTemplate", prefs.logoTemplate)
            put("preview", prefs.previewEnabled)
            put("background", prefs.backgroundPlay)
            put("autoSwitch", prefs.autoSwitchLine)
            put("epgUrl", prefs.epgUrl)
            put("epgInterval", prefs.epgIntervalIndex)
            put("tokenInterval", prefs.tokenIntervalIndex)
            put("hideFailed", prefs.hideFailedChannels)
            put("externalFallback", prefs.externalFallback)
        }
        return JSONObject().apply {
            put("ok", true)
            put("app", "飞雪TV")
            put("sources", sources)
            put("settings", settings)
            put("token", tokens.status())
            put("epg", epg.status())
        }
    }

    private fun okJson(message: String): JSONObject = JSONObject().apply {
        put("ok", true)
        put("message", message)
        put("state", stateJson())
    }

    private fun errorJson(message: String): JSONObject = JSONObject().apply {
        put("ok", false)
        put("message", message)
    }

    private fun respond(client: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = if (code == 200) "OK" else if (code == 403) "Forbidden" else "Bad Request"
        val header = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(type).append("\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: Content-Type\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = client.getOutputStream()
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    /** 以「附件下载」方式返回订阅备份 JSON（Content-Disposition 触发浏览器保存） */
    private fun respondDownload(client: Socket, filename: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Disposition: attachment; filename=\"").append(filename).append("\"\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = client.getOutputStream()
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    /**
     * 手机扫码后的日志分享页：把脱敏日志读进来，提供「复制 / 下载 / 系统分享」，
     * 之后由用户在微信、QQ 里自行发送。局域网是 http 非安全上下文，
     * navigator.share / clipboard 不一定可用，所以一律保留 textarea 手动复制兜底。
     */
    private fun logPage(error: String, pin: String): String {
        val initJs = if (error.isBlank()) {
            "load();"
        } else {
            "msg(" + JSONObject.quote(error) + ");document.getElementById(\"log\").value=\"\";"
        }
        return """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>飞雪TV 日志反馈</title>
<style>
*{box-sizing:border-box}
body{margin:0;background:#0A2049;color:#e8eefb;font-family:-apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif;padding:18px}
h1{font-size:20px;margin:0 0 8px}
p{color:#AFC6E8;font-size:13px;line-height:1.7;margin:0 0 16px}
.card{background:#123970;border:1px solid #2C5A9E;border-radius:14px;padding:16px;margin-bottom:14px}
button{background:linear-gradient(135deg,#2F6FD6,#5B9BFF);color:#fff;border:0;border-radius:9px;padding:11px 15px;font-size:14px;font-weight:600;margin:0 8px 8px 0;cursor:pointer}
button.alt{background:#1D4A8C;color:#cfe0ff}
textarea{width:100%;height:46vh;background:#0B2657;color:#c9d8f5;border:1px solid #2C5A9E;border-radius:10px;padding:12px;font-size:12px;line-height:1.55;resize:vertical}
#msg{color:#8fe0a8;font-size:13px;min-height:20px;margin-top:6px}
.tag{display:inline-block;background:#1C4788;color:#9fc0ff;border-radius:6px;padding:3px 8px;font-size:12px;margin-bottom:10px}
</style></head><body>
<h1>飞雪TV 运行日志</h1>
<p>日志中的订阅地址、Token、自定义 Header 已自动脱敏。复制或下载后，用微信 / QQ 发送即可。</p>
<div class="card">
<span class="tag">扫码取回 · 无需数据线</span>
<div>
<button onclick="shareLog()">分享到微信 / QQ</button>
<button class="alt" onclick="copyLog()">复制全部日志</button>
<button class="alt" onclick="downloadLog()">下载 .txt 文件</button>
</div>
<div id="msg"></div>
</div>
<textarea id="log" readonly>正在读取日志…</textarea>
<script>
var PIN = """ + JSONObject.quote(pin) + """;
var LOG = "";
function msg(t){document.getElementById("msg").textContent=t;}
function load(){
  fetch("/api/logs?pin="+encodeURIComponent(PIN)).then(function(r){
    if(!r.ok){throw new Error("PIN 不正确或日志尚未生成");}
    return r.text();
  }).then(function(t){
    LOG=t;document.getElementById("log").value=t;
    msg("已读取 "+t.length+" 字符，可直接复制或分享");
  }).catch(function(e){msg(e.message);});
}
function legacyCopy(){
  var ta=document.getElementById("log");ta.focus();ta.select();
  try{msg(document.execCommand("copy")?"已复制全文，粘贴到微信 / QQ 发送即可":"请长按文本框手动全选复制");}
  catch(e){msg("请长按文本框手动全选复制");}
}
function copyLog(){
  if(!LOG){msg("日志还没读取完");return;}
  if(navigator.clipboard&&navigator.clipboard.writeText){
    navigator.clipboard.writeText(LOG).then(function(){msg("已复制全文，粘贴到微信 / QQ 发送即可");},legacyCopy);
  }else{legacyCopy();}
}
function downloadLog(){
  if(!LOG){msg("日志还没读取完");return;}
  var blob=new Blob([LOG],{type:"text/plain;charset=utf-8"});
  var url=URL.createObjectURL(blob);
  var a=document.createElement("a");a.href=url;a.download="FlysnowTV_log.txt";
  document.body.appendChild(a);a.click();document.body.removeChild(a);
  setTimeout(function(){URL.revokeObjectURL(url);},2000);
  msg("已下载 .txt，可在微信 / QQ 里以「文件」形式发送");
}
function shareLog(){
  if(!LOG){msg("日志还没读取完");return;}
  if(navigator.share){
    navigator.share({title:"飞雪TV 运行日志",text:LOG}).then(function(){msg("已调起系统分享");})
      .catch(function(){copyLog();});
  }else{copyLog();msg("当前浏览器不支持直接分享，已复制全文，请粘贴到微信 / QQ");}
}
""" + initJs + """
</script></body></html>"""
    }

    private fun indexOfHeaderEnd(bytes: ByteArray): Int {
        val marker = byteArrayOf(13, 10, 13, 10)
        var i = 0
        while (i <= bytes.size - marker.size) {
            var matched = true
            for (j in marker.indices) {
                if (bytes[i + j] != marker[j]) { matched = false; break }
            }
            if (matched) return i
            i++
        }
        return -1
    }

    private fun queryOf(fullPath: String): Map<String, String> {
        val query = fullPath.substringAfter('?', "")
        if (query.isBlank()) return emptyMap()
        val result = HashMap<String, String>()
        for (pair in query.split('&')) {
            val index = pair.indexOf('=')
            if (index > 0) {
                result[pair.substring(0, index)] = runCatching {
                    java.net.URLDecoder.decode(pair.substring(index + 1), "UTF-8")
                }.getOrDefault(pair.substring(index + 1))
            }
        }
        return result
    }

    // ------------------------------------------------------------ 手机端页面

    private fun html(): String = """<!DOCTYPE html>
<html lang="zh-CN"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>飞雪TV · 手机配置</title>
<style>
:root{--bg:#0A2049;--card:#103470;--card2:#164587;--line:#2C5A9E;--txt:#F1F5FC;--sub:#AFC6E8;--a:#5B9BFF;--b:#4FC3F0;--ok:#3DDC97}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;background:radial-gradient(1100px 520px at 12% -10%,#1E52A8 0%,transparent 62%),radial-gradient(800px 420px at 100% 0%,#12508F 0%,transparent 58%),var(--bg);color:var(--txt);font:15px/1.55 -apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif;padding:20px 16px 44px}
.wrap{max-width:720px;margin:0 auto}
header{display:flex;align-items:center;gap:12px;margin:8px 0 22px}
.logo{width:44px;height:44px;border-radius:14px;background:linear-gradient(135deg,var(--a),var(--b));display:grid;place-items:center;font-weight:800;font-size:19px;box-shadow:0 10px 26px rgba(91,155,255,.34)}
h1{font-size:20px;margin:0;letter-spacing:.5px}h1 small{display:block;font-size:11px;color:var(--sub);font-weight:400;letter-spacing:0}
.card{background:linear-gradient(160deg,rgba(28,76,146,.92),rgba(14,44,96,.94));border:1px solid var(--line);border-radius:20px;padding:18px 16px;margin-bottom:16px;box-shadow:0 14px 34px rgba(2,18,52,.32)}
.card h2{font-size:14px;margin:0 0 14px;display:flex;align-items:center;gap:8px;color:var(--txt)}
.card h2:before{content:"";width:4px;height:15px;border-radius:3px;background:linear-gradient(var(--a),var(--b))}
label{display:block;font-size:11px;color:var(--sub);margin:12px 0 5px;letter-spacing:.3px}
input,textarea,select{width:100%;background:#0B2657;border:1px solid var(--line);color:var(--txt);border-radius:11px;padding:11px 12px;font:14px/1.5 inherit;outline:none}
input:focus,textarea:focus,select:focus{border-color:var(--a);box-shadow:0 0 0 3px rgba(91,155,255,.16)}
textarea{resize:vertical;min-height:64px;font-size:12px}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:0 10px}
button{border:0;border-radius:11px;padding:11px 16px;font:600 14px inherit;color:#fff;background:linear-gradient(135deg,var(--a),var(--b));cursor:pointer;box-shadow:0 8px 20px rgba(91,155,255,.24)}
button.ghost{background:#1A4488;border:1px solid var(--line);box-shadow:none;color:var(--sub)}
button.mini{padding:7px 11px;font-size:12px;border-radius:9px}
.row{display:flex;gap:9px;margin-top:14px;flex-wrap:wrap}
.src{display:flex;align-items:center;gap:10px;background:#0B2657;border:1px solid var(--line);border-radius:13px;padding:11px 12px;margin-top:9px}
.src div{flex:1;min-width:0;font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.src span{display:block;color:var(--sub);font-size:10.5px;margin-top:2px}
.hint{font-size:11px;color:var(--sub);margin-top:9px;line-height:1.6}
.pinbar{display:flex;align-items:center;gap:10px;background:#0B2657;border:1px solid var(--line);border-radius:13px;padding:10px 12px;margin-bottom:16px;font-size:12px;color:var(--sub)}
.pinbar input{flex:1;width:auto;padding:7px 10px;letter-spacing:3px;font-size:16px;text-align:center}
.toast{position:fixed;left:50%;bottom:26px;transform:translateX(-50%) translateY(20px);background:#17417F;border:1px solid var(--line);color:var(--txt);padding:11px 18px;border-radius:13px;font-size:13px;opacity:0;transition:.25s;pointer-events:none;z-index:9;box-shadow:0 12px 30px rgba(2,18,52,.55)}
.toast.show{opacity:1;transform:translateX(-50%) translateY(0)}
.status{font-size:11px;color:var(--ok);margin-top:10px;word-break:break-all}
.chk{display:flex;align-items:center;gap:9px;margin-top:12px;font-size:13px;color:var(--txt)}
.chk input{width:19px;height:19px;accent-color:var(--a)}
footer{text-align:center;color:var(--sub);font-size:11px;margin-top:22px;line-height:1.8}
</style></head><body><div class="wrap">
<header><div class="logo" style="font-size:24px">&#10052;</div><h1>飞雪TV · 手机配置<small>填完点保存，电视端立即生效</small></h1></header>
<div class="pinbar">配对 PIN<input id="pin" inputmode="numeric" maxlength="4" placeholder="看电视屏幕"><button class="mini" onclick="loadState()">连接</button></div>

<div class="card"><h2>① 一键导入订阅链接</h2>
<label>聚合订阅地址（.m3u / .m3u8 / .txt / JSON，可一行一个批量粘贴）</label>
<textarea id="quick" style="min-height:88px" placeholder="每行一个订阅地址，可批量粘贴（如 http://example.com/playlist.m3u）"></textarea>
<label>可选请求头（每行 Key: Value；接口要 Referer / UA 时才填）</label><textarea id="quickHeaders" style="min-height:52px" placeholder="Referer: https://example.com/"></textarea>
<label class="chk"><input type="checkbox" id="quickReplace"> 替换现有接口（换订阅时勾选）</label>
<div class="row"><button onclick="quickImport()">一键导入到电视</button></div>
<div class="hint" id="quickResult" style="white-space:pre-wrap">只填链接，不用复制列表内容。导入时会自动拉取并校验频道数，只有能解析出频道的才保存。</div>
</div>

<div class="card"><h2>添加第三方接口（高级参数）</h2>
<label>接口地址（m3u / JSON / 单个流地址，支持 {token}）</label><input id="url" placeholder="https://api.example.com/live/list">
<div class="grid"><div><label>请求方式</label><select id="method"><option value="GET">GET</option><option value="POST">POST</option></select></div>
<div><label>Token 刷新周期（分钟，0=跟随全局）</label><input id="refresh" inputmode="numeric" value="0"></div></div>
<label>POST 请求体（支持 {token}）</label><textarea id="body" placeholder="page=1&amp;token={token}"></textarea>
<label>自定义请求头（每行 Key: Value，支持 {token}）</label><textarea id="headers" placeholder="Authorization: Bearer {token}&#10;Referer: https://example.com/"></textarea>
<label>JSON 字段映射</label><input id="fields" placeholder="name=title,url=playUrl,group=category,root=data">
<div class="grid"><div><label>Token 刷新接口（可选）</label><input id="tokenUrl" placeholder="https://api.example.com/token"></div>
<div><label>Token JSON 路径（可选）</label><input id="tokenPath" placeholder="data.token"></div></div>
<div class="row"><button id="addBtn" onclick="addSource()">保存到电视</button><button class="ghost" onclick="clearForm()">清空</button></div>
<div class="hint">留空 Token 路径时把整段响应当 Token；GET/POST 与请求头会同时用于接口和视频播放。</div>
</div>

<div class="card"><h2>已添加的订阅（可管理）</h2><div id="sources"></div>
<div class="hint">每个订阅支持：编辑、启用/停用、上移/下移（调整合并优先级）、删除。点「编辑」会把参数载入上方表单，改完点「保存修改」。</div></div>

<div class="card"><h2>直接粘贴 m3u 文本</h2><textarea id="m3u" style="min-height:110px" placeholder="#EXTM3U&#10;#EXTINF:-1 tvg-id=&quot;example1&quot; group-title=&quot;示例分组&quot;,示例频道&#10;https://.../1.m3u8"></textarea>
<div class="row"><button onclick="importText()">导入到电视</button></div></div>

<div class="card"><h2>订阅备份（换机 / 分享）</h2>
<label>导出：把当前所有订阅存成一个 JSON 文件，可发到其它盒子或留作备份。</label>
<div class="row"><button onclick="exportBackup()">下载订阅备份</button></div>
<label>导入：把从其它盒子导出的备份 JSON 内容粘贴到这里。</label>
<textarea id="backupText" style="min-height:90px" placeholder='{"app":"FlysnowTV","type":"sources","sources":[...]}'></textarea>
<label class="chk"><input type="checkbox" id="backupReplace"> 替换全部（不勾则按地址合并，不动现有其它订阅）</label>
<div class="row"><button onclick="importBackup()">导入到电视</button></div>
<div class="hint" id="backupResult">合并：新增不重复订阅；替换：用备份覆盖当前全部订阅。</div></div>

<div class="card"><h2>日志反馈</h2>
<label>遇到播放问题？打开日志页，复制或下载运行日志后，用微信 / QQ 发送即可。</label>
<div class="row"><button onclick="openLogPage()">打开日志页（复制 / 下载 / 分享）</button></div>
<div class="hint">日志里的订阅地址、Token、自定义 Header 已自动脱敏。</div></div>

<div class="card"><h2>播放设置</h2>
<div class="grid"><div><label>画质</label><select id="quality"></select></div><div><label>播放引擎</label><select id="engine"></select></div></div>
<div class="grid"><div><label>台标来源</label><select id="logoMode"></select></div><div><label>XMLTV 刷新</label><select id="epgInterval"></select></div></div>
<label>频道封面显示（实时画面=聚焦预览直播，台标=网络台标，首字母=纯文字占位）</label><select id="coverMode"></select>
<label>台标地址模板</label><input id="logoTemplate" placeholder="https://example.com/logo/{short}.png">
<label>XMLTV 节目单地址（.xml / .xml.gz）</label><input id="epgUrl" placeholder="https://example.com/epg.xml.gz">
<label>Token 定时刷新</label><select id="tokenInterval"></select>
<label class="chk"><input type="checkbox" id="preview"> 卡片聚焦时低码率预览小窗（盒子建议关闭）</label>
<label class="chk"><input type="checkbox" id="background"> 返回频道墙继续后台播放（盒子建议关闭）</label>
<label class="chk"><input type="checkbox" id="autoSwitch"> 线路失败时自动切换备用源</label>
<label class="chk"><input type="checkbox" id="hideFailed"> 自动隐藏反复播放失败的频道</label>
<label class="chk"><input type="checkbox" id="externalFallback"> 内置解码失败时自动尝试外部播放器</label>
<div class="row"><button onclick="saveSettings()">保存设置</button><button class="ghost" onclick="refreshToken()">刷新 Token</button><button class="ghost" onclick="refreshEpg()">刷新节目单</button></div>
<div class="status" id="status"></div></div>

<footer>同一 WiFi 下使用 · 数据只保存在这台电视盒子里<br>飞雪TV · 自用测试，请勿分发直播流地址</footer>
</div><div class="toast" id="toast"></div>
<script>
var OPT={quality:["自动","最高画质","1080p","720p","480p","省流"],engine:["内置标准","内置加速","VLC","MX Player","Kodi","系统播放器"],logoMode:["自动","只用内置","自定义","关闭"],coverMode:["实时画面","台标","首字母"],epgInterval:["每 3 小时","每 6 小时","每 12 小时","每 24 小时"],tokenInterval:["关闭","每 5 分钟","每 15 分钟","每 30 分钟","每 60 分钟"]};
var editId=null;
function $(id){return document.getElementById(id)}
function v(id){return $(id).value.trim()}
function pin(){return $("pin").value.trim()}
function toast(msg){var t=$("toast");t.textContent=msg;t.className="toast show";setTimeout(function(){t.className="toast"},2200)}
function post(path,obj){obj.pin=pin();return fetch(path,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(obj)}).then(function(r){return r.json()})}
function mk(label,cls,fn){var b=document.createElement("button");b.className="mini "+(cls||"");b.textContent=label;b.onclick=fn;return b}
function fillSelects(){for(var k in OPT){var s=$(k);if(!s)continue;s.innerHTML="";for(var i=0;i<OPT[k].length;i++){var o=document.createElement("option");o.value=i;o.textContent=OPT[k][i];s.appendChild(o)}}}
function loadState(){fetch("/api/state?pin="+encodeURIComponent(pin())).then(function(r){return r.json()}).then(function(d){
 if(!d.ok){toast(d.message||"连接失败");return}
 var s=d.settings||{};$("quality").value=s.quality||0;$("engine").value=s.engine||0;$("logoMode").value=s.logoMode||0;$("coverMode").value=(s.coverMode==null?0:s.coverMode);$("epgInterval").value=s.epgInterval==null?1:s.epgInterval;$("tokenInterval").value=s.tokenInterval==null?3:s.tokenInterval;$("logoTemplate").value=s.logoTemplate||"";$("epgUrl").value=s.epgUrl||"";$("preview").checked=!!s.preview;$("background").checked=!!s.background;$("autoSwitch").checked=s.autoSwitch!==false;$("hideFailed").checked=!!s.hideFailed;$("externalFallback").checked=s.externalFallback!==false;
 var box=$("sources");box.innerHTML="";var arr=d.sources||[];
 if(!arr.length){box.innerHTML='<div class="hint">还没有接口，用上面的表单添加</div>'}
 for(var i=0;i<arr.length;i++){(function(c){
   var row=document.createElement("div");row.className="src";row.style.flexWrap="wrap";
   var info=document.createElement("div");
   var on=c.enabled!==false;
   info.innerHTML='<b style="color:'+(on?'#3DDC97':'#AFC6E8')+'">'+(on?'✔ 启用中':'⏸ 已停用')+'</b>　'+(c.describe||c.url);
   var sub=document.createElement("span");sub.textContent=c.url;info.appendChild(sub);
   row.appendChild(info);
   var acts=document.createElement("div");acts.style.display="flex";acts.style.gap="6px";acts.style.flexWrap="wrap";
   [mk("编辑","ghost",function(){editSource(c)}),
    mk(on?"停用":"启用","ghost",function(){toggleSource(c.id)}),
    mk("↑","ghost",function(){moveSource(c.id,true)}),
    mk("↓","ghost",function(){moveSource(c.id,false)}),
    mk("删除","ghost",function(){del(c.id)})].forEach(function(b){acts.appendChild(b)});
   row.appendChild(acts);box.appendChild(row);
 })(arr[i])}
 $("status").textContent=(d.token||"")+" · "+(d.epg||"");toast("已连接电视")
}).catch(function(){toast("连不上电视，检查 WiFi 与 PIN" )})}
function quickImport(){var t=$("quick").value;if(!t.trim()){toast("请先粘贴订阅链接");return}
 $("quickResult").textContent="正在拉取并校验，请稍候…";
 post("/api/source/quick",{text:t,headers:$("quickHeaders").value,replace:$("quickReplace").checked}).then(function(d){toast(d.message);$("quickResult").textContent=d.detail||d.message;if(d.ok){$("quick").value="";loadState()}}).catch(function(){$("quickResult").textContent="连不上电视，检查 WiFi 与 PIN"})}
function addSource(){var url=v("url");if(!url){toast("请填写接口地址");return}
 var payload={url:url,method:v("method"),headers:$("headers").value,fields:v("fields"),body:$("body").value,tokenUrl:v("tokenUrl"),tokenPath:v("tokenPath"),refreshMinutes:parseInt(v("refresh")||"0")||0};
 var path="/api/source/add";
 if(editId){payload.id=editId;path="/api/source/update"}
 post(path,payload).then(function(d){toast(d.message);if(d.ok){clearForm();loadState()}})}
function editSource(c){editId=c.id;
 $("url").value=c.url||"";$("method").value=(c.method||"GET");$("headers").value=c.headers||"";
 $("fields").value=c.fields||"";$("body").value=c.body||"";$("tokenUrl").value=c.tokenUrl||"";
 $("tokenPath").value=c.tokenPath||"";$("refresh").value=c.refreshMinutes||0;
 $("addBtn").textContent="保存修改";window.scrollTo({top:0,behavior:"smooth"});toast("已载入参数，修改后点「保存修改」")}
function toggleSource(id){post("/api/source/toggle",{id:id}).then(function(d){toast(d.message);loadState()})}
function moveSource(id,up){post("/api/source/move",{id:id,up:up}).then(function(d){toast(d.message);loadState()})}
function clearForm(){editId=null;["url","headers","fields","body","tokenUrl","tokenPath"].forEach(function(id){$(id).value=""});$("method").value="GET";$("refresh").value="0";if($("addBtn"))$("addBtn").textContent="保存到电视"}
function del(id){post("/api/source/delete",{id:id}).then(function(d){toast(d.message);loadState()})}
function importText(){var text=$("m3u").value;if(!text){toast("请先粘贴 m3u 内容");return}post("/api/source/text",{text:text}).then(function(d){toast(d.message);if(d.ok){$("m3u").value="";loadState()}})}
function exportBackup(){window.location="/api/backup/export?pin="+encodeURIComponent(pin())}
function openLogPage(){window.location="/log?pin="+encodeURIComponent(pin())}
function importBackup(){var t=$("backupText").value;if(!t.trim()){toast("请粘贴备份 JSON 内容");return}$("backupResult").textContent="正在导入…";var mode=$("backupReplace").checked?"replace":"merge";post("/api/backup/import",{text:t,mode:mode}).then(function(d){toast(d.message);$("backupResult").textContent=d.message;if(d.ok){$("backupText").value="";loadState()}}).catch(function(){$("backupResult").textContent="连不上电视，检查 WiFi 与 PIN"})}
function saveSettings(){post("/api/settings",{settings:{quality:parseInt($("quality").value),engine:parseInt($("engine").value),logoMode:parseInt($("logoMode").value),coverMode:parseInt($("coverMode").value),logoTemplate:v("logoTemplate"),preview:$("preview").checked,background:$("background").checked,autoSwitch:$("autoSwitch").checked,hideFailed:$("hideFailed").checked,externalFallback:$("externalFallback").checked,epgUrl:v("epgUrl"),epgInterval:parseInt($("epgInterval").value),tokenInterval:parseInt($("tokenInterval").value)}}).then(function(d){toast(d.message);loadState()})}
function refreshToken(){post("/api/token/refresh",{}).then(function(d){toast(d.message)})}
function refreshEpg(){post("/api/epg/refresh",{}).then(function(d){toast(d.message)})}
window.onload=function(){fillSelects();var q=new URLSearchParams(location.search).get("pin");if(q){$("pin").value=q}loadState()};
</script></body></html>"""
}
