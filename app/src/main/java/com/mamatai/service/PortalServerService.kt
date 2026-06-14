package com.mamatai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.mamatai.model.VoucherStatus
import com.mamatai.util.DataStore
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket

class PortalServerService : Service() {

    companion object {
        const val TAG = "PortalServer"
        const val PORT = 8080
        const val NOTIF_CHANNEL = "mamatai_portal"
        const val NOTIF_ID = 2
        @Volatile var isRunning = false
    }

    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            createNotificationChannel()
            startForeground(NOTIF_ID, buildNotification())
            startServer()
        }
        return START_STICKY
    }

    private fun startServer() {
        isRunning = true
        scope.launch {
            try {
                serverSocket = ServerSocket(PORT)
                Log.d(TAG, "Portal server running on port $PORT")
                while (isRunning) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        launch { handleClient(client) }
                    } catch (e: Exception) {
                        if (isRunning) Log.e(TAG, "Accept error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}")
            }
        }
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(BufferedWriter(OutputStreamWriter(socket.getOutputStream())), true)
            val clientIp = socket.inetAddress.hostAddress ?: "unknown"

            // Read request line
            val requestLine = reader.readLine() ?: return@withContext
            Log.d(TAG, "Request: $requestLine from $clientIp")

            // Read headers
            val headers = mutableMapOf<String, String>()
            var line = reader.readLine()
            while (!line.isNullOrEmpty()) {
                val parts = line.split(": ", limit = 2)
                if (parts.size == 2) headers[parts[0].lowercase()] = parts[1]
                line = reader.readLine()
            }

            val method = requestLine.split(" ").getOrElse(0) { "GET" }
            val path   = requestLine.split(" ").getOrElse(1) { "/" }

            // ── Captive portal detection URLs ──────────────────
            // Android, iOS and Windows all check these URLs to detect captive portals
            val isCaptiveCheck = path.contains("generate_204") ||
                path.contains("connecttest.txt") ||
                path.contains("ncsi.txt") ||
                path.contains("hotspot-detect") ||
                path.contains("success.txt") ||
                path.contains("library/test") ||
                path.contains("captive") ||
                path.contains("redirect")

            if (isCaptiveCheck) {
                // Return redirect to our portal — this triggers the login popup!
                val portalUrl = "http://${socket.localAddress.hostAddress}:$PORT/"
                sendRedirect(writer, portalUrl)
                socket.close()
                return@withContext
            }

            // ── POST — voucher activation ──────────────────────
            if (method == "POST" && path.startsWith("/activate")) {
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                val bodyChars = CharArray(contentLength)
                reader.read(bodyChars)
                val body = String(bodyChars)
                val code = body.substringAfter("code=")
                    .substringBefore("&")
                    .trim()
                    .uppercase()
                    .replace("+", " ")
                handleActivation(writer, clientIp, code)
                socket.close()
                return@withContext
            }

            // ── GET /status — check balance ────────────────────
            if (method == "GET" && path.startsWith("/status")) {
                handleStatus(writer, clientIp)
                socket.close()
                return@withContext
            }

            // ── Default — show login page ──────────────────────
            val user = DataStore.findUserByIp(clientIp)
            if (user != null && user.isForwarding && !user.isExpired) {
                sendHtml(writer, buildStatusPage(user))
            } else {
                sendHtml(writer, buildLoginPage(DataStore.getSettings().businessName))
            }
            socket.close()

        } catch (e: Exception) {
            Log.e(TAG, "Client error: ${e.message}")
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handleActivation(writer: PrintWriter, clientIp: String, code: String) {
        val voucher = DataStore.findVoucherByCode(code)
        when {
            voucher == null -> {
                sendHtml(writer, buildLoginPage(
                    DataStore.getSettings().businessName,
                    error = "Code not found. Check with your provider."
                ))
            }
            voucher.status == VoucherStatus.EXPIRED -> {
                sendHtml(writer, buildLoginPage(
                    DataStore.getSettings().businessName,
                    error = "This voucher has expired."
                ))
            }
            voucher.status == VoucherStatus.ACTIVE -> {
                val existing = DataStore.findUserByIp(clientIp)
                if (existing?.voucher?.code == code) {
                    sendHtml(writer, buildStatusPage(existing))
                } else {
                    sendHtml(writer, buildLoginPage(
                        DataStore.getSettings().businessName,
                        error = "This code is already active on another device."
                    ))
                }
            }
            else -> {
                val user = com.mamatai.model.ConnectedUser(
                    id = java.util.UUID.randomUUID().toString(),
                    macAddress = clientIp,
                    deviceName = "Device",
                    ipAddress = clientIp,
                    voucher = voucher,
                    isForwarding = true
                )
                DataStore.addOrUpdateUser(user)
                DataStore.updateVoucherStatus(code, VoucherStatus.ACTIVE)
                Log.d(TAG, "Activated $code for $clientIp")
                sendHtml(writer, buildSuccessPage(user))
            }
        }
    }

    private fun handleStatus(writer: PrintWriter, clientIp: String) {
        val user = DataStore.findUserByIp(clientIp)
        if (user == null) {
            sendHtml(writer, buildLoginPage(DataStore.getSettings().businessName))
        } else {
            sendHtml(writer, buildStatusPage(user))
        }
    }

    // ── HTML Pages ─────────────────────────────────────────────

    private fun buildLoginPage(bizName: String, error: String? = null) = """
<!DOCTYPE html><html><head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<title>$bizName</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,sans-serif;background:#0a0a0a;color:#fff;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:20px}
.card{background:#1a1a1a;border-radius:20px;padding:32px 24px;max-width:360px;width:100%;border:1px solid #2a2a2a;text-align:center}
.icon{font-size:56px;margin-bottom:16px}
h1{font-size:24px;font-weight:700;color:#00c853;margin-bottom:6px}
p{font-size:14px;color:#888;margin-bottom:24px}
label{font-size:13px;color:#aaa;display:block;margin-bottom:8px;text-align:left}
input{width:100%;padding:14px;font-size:20px;font-family:monospace;letter-spacing:3px;text-transform:uppercase;text-align:center;background:#111;border:1px solid #333;border-radius:12px;color:#fff;outline:none;margin-bottom:16px}
input:focus{border-color:#00c853}
button{width:100%;padding:14px;background:#00c853;color:#000;border:none;border-radius:12px;font-size:16px;font-weight:700;cursor:pointer}
.error{background:rgba(229,57,53,.15);border:1px solid rgba(229,57,53,.3);border-radius:10px;padding:12px;font-size:13px;color:#ef5350;margin-bottom:16px}
.footer{font-size:11px;color:#444;margin-top:20px}
</style></head><body>
<div class="card">
<div class="icon">📶</div>
<h1>$bizName</h1>
<p>Enter your voucher code to connect to the internet</p>
${if (error != null) "<div class=\"error\">$error</div>" else ""}
<form method="POST" action="/activate">
<label>Voucher Code</label>
<input type="text" name="code" placeholder="WIFI-0000" maxlength="9" autocomplete="off" autofocus>
<button type="submit">Connect Now</button>
</form>
<div class="footer">Powered by MAMA.TAI</div>
</div></body></html>
    """.trimIndent()

    private fun buildSuccessPage(user: com.mamatai.model.ConnectedUser): String {
        val dataLeft = if (user.voucher.dataLimitMb == 0) "Unlimited"
                       else "${user.dataRemainingMb} MB"
        return """
<!DOCTYPE html><html><head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<title>Connected!</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,sans-serif;background:#0a0a0a;color:#fff;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:20px}
.card{background:#1a1a1a;border-radius:20px;padding:32px 24px;max-width:360px;width:100%;border:1px solid #2a2a2a;text-align:center}
.icon{font-size:56px;margin-bottom:16px}
h1{font-size:24px;font-weight:700;color:#00c853}
p{color:#888;margin-top:8px;font-size:14px;margin-bottom:24px}
.stats{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-bottom:20px}
.stat{background:#111;border-radius:12px;padding:16px;border:1px solid #222}
.val{font-size:20px;font-weight:700;color:#00c853}
.lbl{font-size:11px;color:#666;margin-top:4px}
a{display:block;padding:13px;background:#222;border:1px solid #333;border-radius:12px;color:#fff;font-size:14px;text-decoration:none}
</style></head><body>
<div class="card">
<div class="icon">✅</div>
<h1>You are Connected!</h1>
<p>Your internet is now active. Enjoy browsing!</p>
<div class="stats">
<div class="stat"><div class="val">$dataLeft</div><div class="lbl">Data left</div></div>
<div class="stat"><div class="val">${user.timeRemainingMinutes}</div><div class="lbl">Mins left</div></div>
</div>
<a href="/status">Check my balance</a>
</div></body></html>
        """.trimIndent()
    }

    private fun buildStatusPage(user: com.mamatai.model.ConnectedUser): String {
        val pct = if (user.voucher.dataLimitMb == 0) 5
                  else (user.dataUsedMb * 100 / user.voucher.dataLimitMb).coerceIn(0, 100)
        val statusText = when {
            !user.isForwarding -> "Paused"
            user.isExpired -> "Expired"
            else -> "Active"
        }
        val statusColor = if (statusText == "Active") "#00c853" else "#ef5350"
        return """
<!DOCTYPE html><html><head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<title>My Balance</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,sans-serif;background:#0a0a0a;color:#fff;min-height:100vh;padding:20px}
.card{background:#1a1a1a;border-radius:20px;padding:24px;max-width:360px;margin:20px auto;border:1px solid #2a2a2a}
h2{font-size:14px;color:#666;text-transform:uppercase;letter-spacing:1px;margin-bottom:20px}
.big{font-size:52px;font-weight:700;color:#00c853;text-align:center;padding:16px 0}
.unit{font-size:14px;color:#666;text-align:center;margin-top:-8px;margin-bottom:16px}
.bar{height:10px;background:#222;border-radius:5px;overflow:hidden;margin-bottom:20px}
.fill{height:100%;border-radius:5px;background:${if (pct > 85) "#ef5350" else if (pct > 60) "#ff9800" else "#00c853"};width:$pct%}
.row{display:flex;justify-content:space-between;padding:12px 0;border-bottom:1px solid #222;font-size:14px}
.row:last-child{border-bottom:none}
.lbl{color:#666}
a{display:block;margin-top:16px;padding:13px;background:#222;border:1px solid #333;border-radius:12px;color:#fff;font-size:14px;text-align:center;text-decoration:none}
</style></head><body>
<div class="card">
<h2>My Balance</h2>
<div class="big">${if (user.voucher.dataLimitMb == 0) "∞" else user.dataRemainingMb}</div>
<div class="unit">${if (user.voucher.dataLimitMb == 0) "Unlimited data" else "MB remaining"}</div>
<div class="bar"><div class="fill"></div></div>
<div class="row"><span class="lbl">Status</span><span style="color:$statusColor">$statusText</span></div>
<div class="row"><span class="lbl">Time left</span><span>${user.timeRemainingMinutes} minutes</span></div>
<div class="row"><span class="lbl">Code</span><span style="font-family:monospace">${user.voucher.code}</span></div>
<div class="row"><span class="lbl">Paid</span><span>UGX ${String.format("%,d", user.voucher.priceUgx)}</span></div>
<a href="/status">Refresh</a>
</div></body></html>
        """.trimIndent()
    }

    // ── HTTP helpers ───────────────────────────────────────────

    private fun sendHtml(writer: PrintWriter, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        writer.print("HTTP/1.1 200 OK\r\n")
        writer.print("Content-Type: text/html; charset=UTF-8\r\n")
        writer.print("Content-Length: ${bytes.size}\r\n")
        writer.print("Cache-Control: no-cache, no-store\r\n")
        writer.print("Connection: close\r\n\r\n")
        writer.print(html)
        writer.flush()
    }

    private fun sendRedirect(writer: PrintWriter, url: String) {
        writer.print("HTTP/1.1 302 Found\r\n")
        writer.print("Location: $url\r\n")
        writer.print("Cache-Control: no-cache\r\n")
        writer.print("Connection: close\r\n\r\n")
        writer.flush()
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        try { serverSocket?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            NOTIF_CHANNEL, "MAMA.TAI Portal",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification() = Notification.Builder(this, NOTIF_CHANNEL)
        .setContentTitle("MAMA.TAI Portal")
        .setContentText("Login page ready for customers")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setOngoing(true)
        .build()
}
