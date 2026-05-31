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

/**
 * PortalServerService
 *
 * A lightweight HTTP server that runs on port 8080 on the phone.
 * When a new hotspot user opens any website, they get redirected here
 * and see the MAMA.TAI voucher login page.
 *
 * Routes:
 *   GET  /          → show login page
 *   POST /activate  → validate voucher code, activate internet
 *   GET  /status    → check current balance (JSON)
 *   GET  /hotdetect → captive portal detection (returns 204 to fool Android/iOS checks)
 */
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
        if (!isRunning) startServer()
        return START_STICKY
    }

    private fun startServer() {
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        isRunning = true

        scope.launch {
            try {
                serverSocket = ServerSocket(PORT)
                Log.d(TAG, "Portal server listening on port $PORT")
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "Server error: ${e.message}")
            }
        }
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)
            val clientIp = socket.inetAddress.hostAddress ?: "unknown"

            val requestLine = reader.readLine() ?: return@withContext
            val headers = mutableMapOf<String, String>()
            var line = reader.readLine()
            while (!line.isNullOrEmpty()) {
                val parts = line.split(": ", limit = 2)
                if (parts.size == 2) headers[parts[0]] = parts[1]
                line = reader.readLine()
            }

            val method = requestLine.split(" ").getOrElse(0) { "GET" }
            val path   = requestLine.split(" ").getOrElse(1) { "/" }

            Log.d(TAG, "$method $path from $clientIp")

            when {
                path.contains("hotdetect") || path.contains("generate_204") ||
                path.contains("connecttest") || path.contains("ncsi.txt") -> {
                    // Captive portal detection — return 302 redirect to our portal
                    sendRedirect(writer, "http://192.168.49.1:$PORT/")
                }

                method == "POST" && path.startsWith("/activate") -> {
                    // Read POST body
                    val contentLength = headers["Content-Length"]?.toIntOrNull() ?: 0
                    val bodyChars = CharArray(contentLength)
                    reader.read(bodyChars)
                    val body = String(bodyChars)
                    val code = body.substringAfter("code=").substringBefore("&").trim()
                        .replace("+", " ").uppercase()

                    handleActivation(writer, clientIp, code)
                }

                method == "GET" && path.startsWith("/status") -> {
                    handleStatus(writer, clientIp)
                }

                else -> {
                    // Serve login page
                    sendHtml(writer, buildLoginPage(DataStore.getSettings().businessName))
                }
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
                    error = "Code not found. Check with MAMA.TAI."
                ))
            }
            voucher.status == VoucherStatus.EXPIRED -> {
                sendHtml(writer, buildLoginPage(
                    DataStore.getSettings().businessName,
                    error = "This voucher has expired."
                ))
            }
            voucher.status == VoucherStatus.ACTIVE -> {
                // Already active — check if it's this user's device
                val existingUser = DataStore.findUserByIp(clientIp)
                if (existingUser?.voucher?.code == code) {
                    sendHtml(writer, buildSuccessPage(existingUser))
                } else {
                    sendHtml(writer, buildLoginPage(
                        DataStore.getSettings().businessName,
                        error = "This code is already in use on another device."
                    ))
                }
            }
            else -> {
                // Activate the voucher for this device
                val user = com.mamatai.model.ConnectedUser(
                    id = java.util.UUID.randomUUID().toString(),
                    macAddress = clientIp, // using IP as identifier (MAC not accessible without root)
                    deviceName = "Device",
                    ipAddress = clientIp,
                    voucher = voucher,
                    isForwarding = true
                )
                DataStore.addOrUpdateUser(user)
                DataStore.updateVoucherStatus(code, VoucherStatus.ACTIVE)
                Log.d(TAG, "Activated voucher $code for $clientIp")
                sendHtml(writer, buildSuccessPage(user))
            }
        }
    }

    private fun handleStatus(writer: PrintWriter, clientIp: String) {
        val user = DataStore.findUserByIp(clientIp)
        if (user == null) {
            sendHtml(writer, buildLoginPage(DataStore.getSettings().businessName))
            return
        }
        sendHtml(writer, buildStatusPage(user))
    }

    // ── HTML Pages ─────────────────────────────────────────────────────────────

    private fun buildLoginPage(bizName: String, error: String? = null) = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<title>$bizName — Login</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: -apple-system, sans-serif; background: #0a0a0a; color: #fff; min-height: 100vh; display: flex; align-items: center; justify-content: center; padding: 20px; }
  .card { background: #1a1a1a; border-radius: 20px; padding: 32px 24px; max-width: 360px; width: 100%; border: 1px solid #2a2a2a; }
  .logo { text-align: center; margin-bottom: 28px; }
  .logo-icon { width: 72px; height: 72px; border-radius: 50%; background: linear-gradient(135deg, #00c853, #00695c); display: flex; align-items: center; justify-content: center; margin: 0 auto 14px; font-size: 32px; }
  h1 { font-size: 22px; font-weight: 700; letter-spacing: -0.5px; }
  p { font-size: 13px; color: #888; margin-top: 6px; }
  label { font-size: 13px; color: #aaa; display: block; margin-bottom: 8px; margin-top: 20px; }
  input { width: 100%; padding: 14px; font-size: 20px; font-family: monospace; letter-spacing: 3px; text-transform: uppercase; text-align: center; background: #111; border: 1px solid #333; border-radius: 12px; color: #fff; outline: none; }
  input:focus { border-color: #00c853; }
  button { width: 100%; margin-top: 16px; padding: 14px; background: #00c853; color: #000; border: none; border-radius: 12px; font-size: 16px; font-weight: 700; cursor: pointer; }
  .error { background: rgba(229,57,53,0.15); border: 1px solid rgba(229,57,53,0.3); border-radius: 10px; padding: 12px; text-align: center; font-size: 13px; color: #ef5350; margin-top: 16px; }
  .footer { text-align: center; font-size: 11px; color: #444; margin-top: 24px; }
</style>
</head>
<body>
<div class="card">
  <div class="logo">
    <div class="logo-icon">📶</div>
    <h1>$bizName</h1>
    <p>Enter your voucher code to connect</p>
  </div>
  <form method="POST" action="/activate">
    <label>Voucher Code</label>
    <input type="text" name="code" placeholder="WIFI-0000" maxlength="9" autocomplete="off" autofocus>
    ${if (error != null) """<div class="error">$error</div>""" else ""}
    <button type="submit">Connect to Internet</button>
  </form>
  <div class="footer">Powered by MAMA.TAI</div>
</div>
</body>
</html>
    """.trimIndent()

    private fun buildSuccessPage(user: com.mamatai.model.ConnectedUser): String {
        val dataText = if (user.voucher.dataLimitMb == 0) "Unlimited"
                       else "${user.dataRemainingMb} MB remaining"
        val timeText = "${user.timeRemainingMinutes} minutes remaining"
        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<title>Connected!</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: -apple-system, sans-serif; background: #0a0a0a; color: #fff; min-height: 100vh; display: flex; align-items: center; justify-content: center; padding: 20px; }
  .card { background: #1a1a1a; border-radius: 20px; padding: 32px 24px; max-width: 360px; width: 100%; border: 1px solid #2a2a2a; text-align: center; }
  .check { font-size: 56px; margin-bottom: 16px; }
  h1 { font-size: 24px; font-weight: 700; color: #00c853; }
  p { color: #888; margin-top: 8px; font-size: 14px; }
  .stats { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-top: 24px; }
  .stat { background: #111; border-radius: 12px; padding: 16px; border: 1px solid #222; }
  .stat .val { font-size: 18px; font-weight: 700; color: #00c853; }
  .stat .lbl { font-size: 11px; color: #666; margin-top: 4px; }
  .btn { display: block; margin-top: 20px; padding: 13px; background: #222; border: 1px solid #333; border-radius: 12px; color: #fff; font-size: 14px; text-decoration: none; }
  .code { font-family: monospace; font-size: 13px; color: #555; margin-top: 20px; }
</style>
</head>
<body>
<div class="card">
  <div class="check">✅</div>
  <h1>You're Connected!</h1>
  <p>Your internet is now active</p>
  <div class="stats">
    <div class="stat"><div class="val">${dataText.split(" ")[0]}</div><div class="lbl">Data left</div></div>
    <div class="stat"><div class="val">${user.timeRemainingMinutes}</div><div class="lbl">Mins left</div></div>
  </div>
  <a href="/status" class="btn">Check my balance</a>
  <div class="code">Code: ${user.voucher.code}</div>
</div>
</body>
</html>
        """.trimIndent()
    }

    private fun buildStatusPage(user: com.mamatai.model.ConnectedUser): String {
        val pct = if (user.voucher.dataLimitMb == 0) 0
                  else (user.dataUsedMb * 100 / user.voucher.dataLimitMb).coerceIn(0, 100)
        val statusText = when {
            !user.isForwarding -> "Paused by admin"
            user.isExpired     -> "Expired"
            else               -> "Active"
        }
        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<title>My Balance</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: -apple-system, sans-serif; background: #0a0a0a; color: #fff; min-height: 100vh; padding: 20px; }
  .card { background: #1a1a1a; border-radius: 20px; padding: 24px; max-width: 360px; margin: 20px auto; border: 1px solid #2a2a2a; }
  h2 { font-size: 16px; color: #666; margin-bottom: 20px; text-transform: uppercase; letter-spacing: 1px; }
  .big { font-size: 48px; font-weight: 700; color: #00c853; text-align: center; padding: 20px 0; }
  .unit { font-size: 14px; color: #666; text-align: center; margin-top: -10px; }
  .bar-wrap { margin: 20px 0; }
  .bar-label { display: flex; justify-content: space-between; font-size: 12px; color: #666; margin-bottom: 8px; }
  .bar { height: 10px; background: #222; border-radius: 5px; overflow: hidden; }
  .bar-fill { height: 100%; border-radius: 5px; background: ${if (pct > 85) "#ef5350" else if (pct > 60) "#ff9800" else "#00c853"}; width: $pct%; }
  .row { display: flex; justify-content: space-between; padding: 12px 0; border-bottom: 1px solid #222; font-size: 14px; }
  .row:last-child { border-bottom: none; }
  .row .lbl { color: #666; }
  .status-badge { padding: 3px 10px; border-radius: 20px; font-size: 12px; font-weight: 600; background: ${if (statusText == "Active") "rgba(0,200,83,0.15)" else "rgba(239,83,80,0.15)"}; color: ${if (statusText == "Active") "#00c853" else "#ef5350"}; }
  a { display: block; margin-top: 20px; padding: 13px; background: #222; border: 1px solid #333; border-radius: 12px; color: #fff; font-size: 14px; text-align: center; text-decoration: none; }
</style>
</head>
<body>
<div class="card">
  <h2>My Balance</h2>
  <div class="big">${if (user.voucher.dataLimitMb == 0) "∞" else user.dataRemainingMb}</div>
  <div class="unit">${if (user.voucher.dataLimitMb == 0) "Unlimited data" else "MB remaining"}</div>
  <div class="bar-wrap">
    <div class="bar-label"><span>Used: ${user.dataUsedMb}MB</span><span>Total: ${if(user.voucher.dataLimitMb==0)"∞" else "${user.voucher.dataLimitMb}MB"}</span></div>
    <div class="bar"><div class="bar-fill"></div></div>
  </div>
  <div class="row"><span class="lbl">Status</span><span class="status-badge">$statusText</span></div>
  <div class="row"><span class="lbl">Time left</span><span>${user.timeRemainingMinutes} minutes</span></div>
  <div class="row"><span class="lbl">Code</span><span style="font-family:monospace">${user.voucher.code}</span></div>
  <div class="row"><span class="lbl">Paid</span><span>UGX ${user.voucher.priceUgx.toLocaleString()}</span></div>
  <a href="/status">Refresh balance</a>
</div>
</body>
</html>
        """.trimIndent()
    }

    // ── HTTP Helpers ───────────────────────────────────────────────────────────

    private fun sendHtml(writer: PrintWriter, html: String) {
        writer.print("HTTP/1.1 200 OK\r\n")
        writer.print("Content-Type: text/html; charset=UTF-8\r\n")
        writer.print("Content-Length: ${html.toByteArray().size}\r\n")
        writer.print("Connection: close\r\n\r\n")
        writer.print(html)
        writer.flush()
    }

    private fun sendRedirect(writer: PrintWriter, url: String) {
        writer.print("HTTP/1.1 302 Found\r\n")
        writer.print("Location: $url\r\n")
        writer.print("Connection: close\r\n\r\n")
        writer.flush()
    }

    private fun Int.toLocaleString() = String.format("%,d", this)

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        try { serverSocket?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(NOTIF_CHANNEL, "MAMA.TAI Portal", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification() = Notification.Builder(this, NOTIF_CHANNEL)
        .setContentTitle("MAMA.TAI Portal")
        .setContentText("Login page ready for customers")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setOngoing(true)
        .build()
}
