package com.omniai.dev.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.omniai.dev.MainActivity
import com.omniai.dev.R
import com.omniai.dev.data.OmniDevDatabase
import com.omniai.dev.data.WorkspaceManager
import com.omniai.dev.engine.GitAutoPushWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

data class LanServerStatus(
    val running: Boolean = false,
    val url: String = "",
    val pin: String = "",
    val projectSlug: String = "",
    val projectId: Long = -1L
)

class LocalLanWebServer : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private var serverSocket: ServerSocket? = null
    @Volatile private var activePin: String = ""
    @Volatile private var activeSlug: String = ""
    @Volatile private var activeProjectId: Long = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            stopSelf()
            return START_NOT_STICKY
        }

        activeProjectId = intent?.getLongExtra(EXTRA_PROJECT_ID, -1L) ?: -1L
        activeSlug = intent?.getStringExtra(EXTRA_PROJECT_SLUG).orEmpty()
        if (activeSlug.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        activePin = generateSessionPin()
        val ip = resolveLocalWifiIp()
        val url = "http://$ip:$PORT"

        ensureNotificationChannel()
        val notification = buildNotification(url, activePin)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }

        _status.value = LanServerStatus(
            running = true,
            url = url,
            pin = activePin,
            projectSlug = activeSlug,
            projectId = activeProjectId
        )

        startAcceptLoop()
        return START_STICKY
    }

    private fun startAcceptLoop() {
        scope.launch {
            runCatching { serverSocket?.close() }
            try {
                val socket = ServerSocket(PORT)
                serverSocket = socket
                while (!socket.isClosed) {
                    val client = socket.accept()
                    scope.launch { handleClient(client) }
                }
            } catch (_: Exception) {
                // Socket closed on service stop
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = 10_000
            val input = BufferedInputStream(sock.getInputStream())
            val output = sock.getOutputStream()

            val headerBytes = ByteArrayOutputStream()
            var prev3 = 0; var prev2 = 0; var prev1 = 0
            while (headerBytes.size() < 32_768) {
                val b = input.read()
                if (b == -1) break
                headerBytes.write(b)
                if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) break
                prev3 = prev2; prev2 = prev1; prev1 = b
            }

            val headerText = headerBytes.toString("UTF-8")
            val headerLines = headerText.split("\r\n")
            val requestLine = headerLines.firstOrNull()?.split(" ") ?: return
            if (requestLine.size < 2) return

            val method = requestLine[0].uppercase()
            val rawTarget = requestLine[1]
            val pathOnly = rawTarget.substringBefore("?")
            val queryStr = rawTarget.substringAfter("?", "")

            val headers = mutableMapOf<String, String>()
            headerLines.drop(1).forEach { line ->
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }

            val contentLength = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 2_000_000) ?: 0
            val bodyBytes = ByteArray(contentLength)
            var readTotal = 0
            while (readTotal < contentLength) {
                val n = input.read(bodyBytes, readTotal, contentLength - readTotal)
                if (n <= 0) break
                readTotal += n
            }
            val bodyStr = String(bodyBytes, 0, readTotal, Charsets.UTF_8)

            // Serve SPA Dashboard on GET /
            if (method == "GET" && (pathOnly == "/" || pathOnly == "/index.html")) {
                writeHttp(output, 200, "text/html; charset=utf-8", buildDashboardHtml(activeSlug))
                return
            }

            // Require 6-digit Session PIN header for all /api/* routes
            val providedPin = headers["x-omni-pin"].orEmpty().trim()
            if (providedPin != activePin) {
                writeHttp(output, 401, "application/json", """{"error":"Invalid or missing 6-digit Session PIN"}""")
                return
            }

            val root = WorkspaceManager.projectRoot(applicationContext, activeSlug)
            val canonicalRoot = root.canonicalPath

            when {
                method == "GET" && pathOnly == "/api/files" -> {
                    val files = root.walkTopDown()
                        .filter { it.isFile }
                        .map { it.relativeTo(root).path.replace('\\', '/') }
                        .filterNot { it.startsWith(".git/") }
                        .sorted()
                        .toList()
                    val payload = buildJsonObject {
                        put("project", activeSlug)
                        put("files", buildJsonArray { files.forEach { add(it) } })
                    }
                    writeHttp(output, 200, "application/json", payload.toString())
                }

                method == "GET" && pathOnly == "/api/file" -> {
                    val rel = parseQueryParam(queryStr, "path")
                    val target = File(root, rel)
                    if (rel.isBlank() || !target.canonicalPath.startsWith(canonicalRoot + File.separator) || !target.isFile) {
                        writeHttp(output, 404, "application/json", """{"error":"File not found"}""")
                    } else {
                        val payload = buildJsonObject {
                            put("path", rel)
                            put("content", target.readText(Charsets.UTF_8))
                        }
                        writeHttp(output, 200, "application/json", payload.toString())
                    }
                }

                method == "POST" && pathOnly == "/api/file" -> {
                    val obj = runCatching { json.parseToJsonElement(bodyStr).jsonObject }.getOrNull()
                    val rel = obj?.get("path")?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                    val content = obj?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
                    val target = File(root, rel)
                    if (rel.isBlank() || rel.contains("..") || !target.canonicalPath.startsWith(canonicalRoot + File.separator)) {
                        writeHttp(output, 400, "application/json", """{"error":"Invalid file path"}""")
                    } else {
                        target.parentFile?.mkdirs()
                        target.writeText(content, Charsets.UTF_8)
                        val savedJson = buildJsonObject {
                            put("saved", true)
                            put("path", rel)
                        }
                        writeHttp(output, 200, "application/json", savedJson.toString())
                    }
                }

                method == "POST" && pathOnly == "/api/push" -> {
                    val obj = runCatching { json.parseToJsonElement(bodyStr).jsonObject }.getOrNull()
                    val msg = obj?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty()
                        .ifBlank { "LAN Studio remote push" }
                    if (activeProjectId > 0L) {
                        val project = OmniDevDatabase.getInstance(applicationContext).dao().getProjectById(activeProjectId)
                        if (project != null) {
                            GitAutoPushWorker.enqueue(applicationContext, project.id, msg)
                            writeHttp(output, 200, "application/json", """{"queued":true,"message":"Git Auto-Push queued on device"}""")
                        } else {
                            writeHttp(output, 404, "application/json", """{"error":"Project not found"}""")
                        }
                    } else {
                        writeHttp(output, 400, "application/json", """{"error":"No active project ID"}""")
                    }
                }

                else -> writeHttp(output, 404, "application/json", """{"error":"Not found"}""")
            }
        }
    }

    private fun parseQueryParam(query: String, key: String): String {
        return query.split("&")
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx > 0) pair.substring(0, idx) to pair.substring(idx + 1) else null
            }
            .firstOrNull { it.first == key }
            ?.second
            ?.let { URLDecoder.decode(it, "UTF-8") }
            .orEmpty()
    }

    private fun writeHttp(
        out: java.io.OutputStream,
        status: Int,
        contentType: String,
        body: String
    ) {
        val statusText = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            else -> "Not Found"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $status $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun stopServer() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        _status.value = LanServerStatus(running = false)
    }

    override fun onDestroy() {
        stopServer()
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OmniDev LAN Web Studio",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(url: String, pin: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LocalLanWebServer::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_omni)
            .setContentTitle("OmniDev LAN Studio Active")
            .setContentText("$url  •  PIN: $pin")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_stat_omni, "Stop Server", stopIntent)
            .build()
    }

    private fun generateSessionPin(): String {
        val n = 100_000 + SecureRandom().nextInt(900_000)
        return n.toString()
    }

    private fun resolveLocalWifiIp(): String {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return@runCatching addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
            "127.0.0.1"
        }.getOrElse { "127.0.0.1" }
    }

    private fun buildDashboardHtml(slug: String): String = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>OmniDev LAN Studio — $slug</title>
<style>
  * { box-sizing: border-box; }
  body { margin: 0; font-family: system-ui, -apple-system, sans-serif; background: #09090C; color: #E8E8F0; height: 100vh; display: flex; flex-direction: column; }
  header { display: flex; align-items: center; justify-content: space-between; padding: 12px 18px; background: #13131A; border-bottom: 1px solid #232330; gap: 12px; }
  h1 { margin: 0; font-size: 16px; color: #00E5FF; font-weight: 600; }
  .bar { display: flex; gap: 8px; align-items: center; }
  input { background: #09090C; color: #E8E8F0; border: 1px solid #232330; border-radius: 6px; padding: 7px 10px; font-size: 13px; }
  button { background: #00E5FF; color: #09090C; border: none; border-radius: 6px; padding: 7px 13px; font-weight: 600; font-size: 13px; cursor: pointer; }
  button.sec { background: #232330; color: #E8E8F0; }
  button.purple { background: #BB86FC; color: #09090C; }
  main { flex: 1; display: grid; grid-template-columns: 260px 1fr; min-height: 0; }
  aside { border-right: 1px solid #232330; background: #0E0E14; overflow-y: auto; padding: 10px; }
  .file { padding: 7px 10px; border-radius: 6px; font-size: 13px; font-family: ui-monospace, monospace; cursor: pointer; color: #B8B8C8; word-break: break-all; }
  .file:hover, .file.active { background: #1C1C28; color: #00E5FF; }
  section { display: flex; flex-direction: column; padding: 12px; gap: 10px; min-height: 0; }
  textarea { flex: 1; width: 100%; background: #13131A; color: #E8E8F0; border: 1px solid #232330; border-radius: 8px; padding: 14px; font-family: ui-monospace, SFMono-Regular, monospace; font-size: 13px; line-height: 1.5; resize: none; }
  #status { font-size: 12px; color: #9E9EB3; }
</style>
</head>
<body>
<header>
  <h1>OmniDev LAN Studio · $slug</h1>
  <div class="bar">
    <input id="pin" placeholder="6-digit PIN" maxlength="6" style="width:110px"/>
    <button onclick="loadFiles()">Unlock</button>
    <button class="sec" onclick="saveFile()">Save File</button>
    <button class="purple" onclick="triggerPush()">Git Auto-Push</button>
  </div>
</header>
<main>
  <aside id="tree"><div style="color:#777;font-size:13px;padding:8px;">Enter 6-digit PIN and click Unlock.</div></aside>
  <section>
    <div style="display:flex;justify-content:space-between;align-items:center;">
      <input id="curPath" placeholder="path/to/file.kt" style="width:60%;font-family:monospace;"/>
      <span id="status">Ready</span>
    </div>
    <textarea id="editor" spellcheck="false" placeholder="Select a file from the left or enter a new file path..."></textarea>
  </section>
</main>
<script>
  const headers = () => ({ 'Content-Type': 'application/json', 'X-Omni-Pin': document.getElementById('pin').value.trim() });
  const setStatus = (t) => document.getElementById('status').textContent = t;

  async function loadFiles() {
    setStatus('Loading workspace...');
    const res = await fetch('/api/files', { headers: headers() });
    const data = await res.json();
    if (!res.ok) { setStatus(data.error || 'Auth failed'); return; }
    const tree = document.getElementById('tree');
    tree.innerHTML = '';
    data.files.forEach(f => {
      const div = document.createElement('div');
      div.className = 'file';
      div.textContent = f;
      div.onclick = () => openFile(f, div);
      tree.appendChild(div);
    });
    setStatus('Loaded ' + data.files.length + ' files');
  }

  async function openFile(path, el) {
    document.querySelectorAll('.file').forEach(x => x.classList.remove('active'));
    el.classList.add('active');
    const res = await fetch('/api/file?path=' + encodeURIComponent(path), { headers: headers() });
    const data = await res.json();
    if (!res.ok) { setStatus(data.error || 'Read failed'); return; }
    document.getElementById('curPath').value = data.path;
    document.getElementById('editor').value = data.content;
    setStatus('Opened ' + data.path);
  }

  async function saveFile() {
    const path = document.getElementById('curPath').value.trim();
    const content = document.getElementById('editor').value;
    if (!path) { setStatus('Enter a file path first'); return; }
    const res = await fetch('/api/file', { method: 'POST', headers: headers(), body: JSON.stringify({ path, content }) });
    const data = await res.json();
    setStatus(res.ok ? 'Saved ' + path : (data.error || 'Save failed'));
    if (res.ok) loadFiles();
  }

  async function triggerPush() {
    const msg = prompt('Commit message:', 'LAN Studio remote update') || 'LAN Studio remote update';
    const res = await fetch('/api/push', { method: 'POST', headers: headers(), body: JSON.stringify({ message: msg }) });
    const data = await res.json();
    setStatus(res.ok ? data.message : (data.error || 'Push failed'));
  }
</script>
</body>
</html>
""".trimIndent()

    companion object {
        const val PORT = 8787
        private const val NOTIF_ID = 8787
        private const val CHANNEL_ID = "omnidev_lan_studio"
        const val ACTION_STOP = "com.omniai.dev.action.STOP_LAN"
        const val EXTRA_PROJECT_ID = "extra_project_id"
        const val EXTRA_PROJECT_SLUG = "extra_project_slug"

        private val _status = MutableStateFlow(LanServerStatus())
        val status: StateFlow<LanServerStatus> = _status.asStateFlow()

        fun start(context: Context, projectId: Long, projectSlug: String) {
            val intent = Intent(context, LocalLanWebServer::class.java).apply {
                putExtra(EXTRA_PROJECT_ID, projectId)
                putExtra(EXTRA_PROJECT_SLUG, projectSlug)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LocalLanWebServer::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
