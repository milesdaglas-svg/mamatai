package com.mamatai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.mamatai.ui.admin.AdminActivity
import com.mamatai.util.DataStore
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

class MamaTaiVpnService : VpnService() {

    companion object {
        const val TAG = "MamaTaiVPN"
        const val ACTION_START = "com.mamatai.START_VPN"
        const val ACTION_STOP  = "com.mamatai.STOP_VPN"
        const val NOTIF_CHANNEL = "mamatai_vpn"
        const val NOTIF_ID = 1
        const val PORTAL_IP   = "192.168.49.1"
        const val PORTAL_PORT = 8080
        @Volatile var isRunning = false
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> { stopVpn(); START_NOT_STICKY }
            else        -> { startVpn(); START_STICKY }
        }
    }

    private fun startVpn() {
        if (isRunning) return
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        try {
            vpnInterface = Builder()
                .addAddress("10.0.0.1", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .setSession("MAMA.TAI")
                .setMtu(1500)
                .establish()

            isRunning = true
            Log.d(TAG, "VPN started")
            startPacketLoop()
        } catch (e: Exception) {
            Log.e(TAG, "VPN failed: ${e.message}")
        }
    }

    private fun startPacketLoop() {
        val pfd = vpnInterface ?: return

        scope.launch {
            val buffer = ByteBuffer.allocate(32767)
            val inputStream  = FileInputStream(pfd.fileDescriptor)
            val outputStream = FileOutputStream(pfd.fileDescriptor)

            while (isRunning) {
                try {
                    buffer.clear()
                    val length = withContext(Dispatchers.IO) {
                        inputStream.read(buffer.array())
                    }

                    if (length <= 0) {
                        delay(50)
                        continue
                    }

                    val srcIp = extractSourceIp(buffer.array(), length)

                    if (srcIp != null) {
                        val user = DataStore.findUserByIp(srcIp)
                        when {
                            user == null -> {
                                // Unknown — block, portal handles them
                            }
                            user.isForwarding && !user.isExpired -> {
                                // Forward packet
                                withContext(Dispatchers.IO) {
                                    outputStream.write(buffer.array(), 0, length)
                                }
                            }
                            user.isExpired -> {
                                user.isForwarding = false
                                DataStore.addOrUpdateUser(user)
                            }
                            else -> {
                                // Paused — drop packet
                            }
                        }
                    }

                    // Small delay to prevent CPU overload
                    delay(10)

                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e(TAG, "Packet error: ${e.message}")
                        delay(200)
                    }
                }
            }
        }
    }

    private fun extractSourceIp(packet: ByteArray, length: Int): String? {
        if (length < 20) return null
        val version = (packet[0].toInt() and 0xF0) shr 4
        if (version != 4) return null
        return try {
            "${packet[12].toInt() and 0xFF}.${packet[13].toInt() and 0xFF}" +
            ".${packet[14].toInt() and 0xFF}.${packet[15].toInt() and 0xFF}"
        } catch (e: Exception) { null }
    }

    private fun stopVpn() {
        isRunning = false
        scope.cancel()
        vpnInterface?.close()
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL, "MAMA.TAI Hotspot",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Hotspot running" }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, AdminActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("MAMA.TAI is running")
            .setContentText("Hotspot active — tap to manage")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }
}
