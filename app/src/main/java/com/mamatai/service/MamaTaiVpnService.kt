package com.mamatai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.mamatai.model.VoucherStatus
import com.mamatai.ui.admin.AdminActivity
import com.mamatai.util.DataStore
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * MamaTaiVpnService
 *
 * This is the CORE of MAMA.TAI. It runs as a VPN on the phone so all
 * traffic from hotspot clients passes through it. For each packet:
 *   - If the source IP has a valid active voucher → forward it
 *   - If not → drop it (or redirect to captive portal at 192.168.49.1:8080)
 *
 * This gives us full per-device internet ON/OFF control without root.
 */
class MamaTaiVpnService : VpnService() {

    companion object {
        const val TAG = "MamaTaiVPN"
        const val ACTION_START = "com.mamatai.START_VPN"
        const val ACTION_STOP  = "com.mamatai.STOP_VPN"
        const val NOTIF_CHANNEL = "mamatai_vpn"
        const val NOTIF_ID = 1

        // Portal server address — our built-in web server for login page
        const val PORTAL_IP   = "192.168.49.1"
        const val PORTAL_PORT = 8080

        @Volatile var isRunning = false
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var job: Job? = null
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
                .addDnsServer("8.8.4.4")
                .setSession("MAMA.TAI")
                .setMtu(1500)
                .establish()

            isRunning = true
            Log.d(TAG, "VPN interface established")
            startPacketLoop()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN: ${e.message}")
        }
    }

    /**
     * Packet processing loop.
     * Reads IP packets from the VPN tun interface, checks if the source
     * device has an active voucher, then either forwards or drops the packet.
     */
    private fun startPacketLoop() {
        val pfd = vpnInterface ?: return
        val inputStream  = FileInputStream(pfd.fileDescriptor)
        val outputStream = FileOutputStream(pfd.fileDescriptor)
        val buffer = ByteBuffer.allocate(32767)

        job = scope.launch {
            while (isRunning) {
                try {
                    buffer.clear()
                    val length = inputStream.read(buffer.array())
                    if (length <= 0) { delay(10); continue }

                    buffer.limit(length)

                    // Extract source IP from IPv4 header (bytes 12-15)
                    val srcIp = extractSourceIp(buffer.array(), length)

                    if (srcIp != null) {
                        val user = DataStore.findUserByIp(srcIp)

                        when {
                            // Unknown device — redirect to captive portal
                            user == null -> {
                                redirectToPortal(outputStream, buffer.array(), length, srcIp)
                            }
                            // Known user, internet ON, voucher valid
                            user.isForwarding && !user.isExpired -> {
                                // Update data usage (rough estimate by packet size)
                                user.dataUsedMb += (length / (1024 * 1024)).coerceAtLeast(0)
                                if (user.dataUsedMb % 5 == 0) { // save every 5MB
                                    DataStore.addOrUpdateUser(user)
                                }
                                // Forward packet to real internet
                                protect(inputStream.fd.hashCode())
                                outputStream.write(buffer.array(), 0, length)
                            }
                            // Expired — cut off and update status
                            user.isExpired -> {
                                DataStore.updateVoucherStatus(user.voucher.code, VoucherStatus.EXPIRED)
                                user.isForwarding = false
                                DataStore.addOrUpdateUser(user)
                                // Drop packet — don't forward
                                Log.d(TAG, "Dropped packet from expired user: $srcIp")
                            }
                            // Paused by admin — drop packet
                            !user.isForwarding -> {
                                Log.d(TAG, "Dropped packet from paused user: $srcIp")
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning) Log.e(TAG, "Packet loop error: ${e.message}")
                    delay(100)
                }
            }
        }
    }

    /**
     * Extract source IP address from raw IPv4 packet bytes.
     * IPv4 header: version+IHL(1), DSCP(1), length(2), id(2),
     *              flags(2), TTL(1), protocol(1), checksum(2),
     *              src IP (4 bytes at offset 12), dst IP (4 bytes at offset 16)
     */
    private fun extractSourceIp(packet: ByteArray, length: Int): String? {
        if (length < 20) return null
        val version = (packet[0].toInt() and 0xF0) shr 4
        if (version != 4) return null // Only handle IPv4
        return try {
            "${packet[12].toInt() and 0xFF}.${packet[13].toInt() and 0xFF}" +
            ".${packet[14].toInt() and 0xFF}.${packet[15].toInt() and 0xFF}"
        } catch (e: Exception) { null }
    }

    /**
     * For unknown devices, we send back an HTTP redirect to the portal page.
     * In a real captive portal, this intercepts HTTP and redirects to login.
     */
    private fun redirectToPortal(out: FileOutputStream, packet: ByteArray, length: Int, srcIp: String) {
        // Drop the packet — the portal server handles new connections via HTTP
        // When user opens browser, DNS resolves → portal server responds with login page
        Log.d(TAG, "Unknown device $srcIp — blocked, portal server will handle")
    }

    private fun stopVpn() {
        isRunning = false
        job?.cancel()
        vpnInterface?.close()
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL, "MAMA.TAI Hotspot",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Hotspot running" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
