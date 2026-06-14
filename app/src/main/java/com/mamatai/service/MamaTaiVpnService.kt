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
import java.nio.ByteBuffer

class MamaTaiVpnService : VpnService() {

    companion object {
        const val TAG = "MamaTaiVPN"
        const val ACTION_START = "com.mamatai.START_VPN"
        const val ACTION_STOP  = "com.mamatai.STOP_VPN"
        const val NOTIF_CHANNEL = "mamatai_vpn"
        const val NOTIF_ID = 1
        @Volatile var isRunning = false
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> { stopVpn(); START_NOT_STICKY }
            else -> { if (!isRunning) startVpn(); START_STICKY }
        }
    }

    private fun startVpn() {
        try {
            createNotificationChannel()
            startForeground(NOTIF_ID, buildNotification())

            vpnInterface = Builder()
                .addAddress("10.0.0.1", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
                .setSession("MAMA.TAI")
                .setMtu(1500)
                .establish()

            isRunning = true
            Log.d(TAG, "VPN started successfully")

            // Lightweight drain loop — just reads and discards packets
            // Real control happens via DataStore flags
            scope.launch {
                val buffer = ByteBuffer.allocate(32767)
                val stream = FileInputStream(vpnInterface!!.fileDescriptor)
                while (isRunning) {
                    try {
                        buffer.clear()
                        val len = stream.read(buffer.array())
                        if (len > 0) {
                            val srcIp = extractIp(buffer.array(), len)
                            if (srcIp != null) {
                                val user = DataStore.findUserByIp(srcIp)
                                if (user != null && user.isExpired) {
                                    user.isForwarding = false
                                    DataStore.addOrUpdateUser(user)
                                }
                            }
                        }
                        delay(100) // relaxed pace — easy on memory
                    } catch (e: Exception) {
                        if (isRunning) delay(500)
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "VPN start failed: ${e.message}")
            stopSelf()
        }
    }

    private fun extractIp(packet: ByteArray, length: Int): String? {
        if (length < 20) return null
        if ((packet[0].toInt() and 0xF0) shr 4 != 4) return null
        return try {
            "${packet[12].toInt() and 0xFF}.${packet[13].toInt() and 0xFF}" +
            ".${packet[14].toInt() and 0xFF}.${packet[15].toInt() and 0xFF}"
        } catch (e: Exception) { null }
    }

    private fun stopVpn() {
        isRunning = false
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try { vpnInterface?.close() } catch (e: Exception) {}
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            NOTIF_CHANNEL, "MAMA.TAI Hotspot",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, AdminActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("MAMA.TAI Active")
            .setContentText("Hotspot running — tap to manage")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
