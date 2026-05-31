package com.mamatai.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.util.Log
import com.mamatai.util.DataStore
import kotlinx.coroutines.*
import java.lang.reflect.Method

/**
 * HotspotService
 *
 * Manages the phone's WiFi hotspot and periodically scans
 * for connected devices by reading the ARP table (/proc/net/arp).
 * This is available without root on all Android versions.
 */
class HotspotService : Service() {

    companion object {
        const val TAG = "HotspotService"
        @Volatile var isHotspotOn = false
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var wifiManager: WifiManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        DataStore.init(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startDeviceScanner()
        return START_STICKY
    }

    /**
     * Periodically reads /proc/net/arp to find all devices connected
     * to this phone's hotspot. Updates the connected users list.
     */
    private fun startDeviceScanner() {
        scope.launch {
            while (true) {
                try {
                    val arpEntries = readArpTable()
                    Log.d(TAG, "Found ${arpEntries.size} devices in ARP table")

                    // Check existing users for expiry
                    val users = DataStore.getConnectedUsers()
                    users.forEach { user ->
                        if (user.isExpired && user.isForwarding) {
                            user.isForwarding = false
                            DataStore.addOrUpdateUser(user)
                            Log.d(TAG, "Auto-expired user: ${user.ipAddress}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Scanner error: ${e.message}")
                }
                delay(10_000) // scan every 10 seconds
            }
        }
    }

    /**
     * Read the ARP table to find connected hotspot devices.
     * /proc/net/arp format:
     * IP address       HW type  Flags  HW address         Mask  Device
     * 192.168.43.100   0x1      0x2    aa:bb:cc:dd:ee:ff  *     wlan0
     */
    private fun readArpTable(): List<ArpEntry> {
        val entries = mutableListOf<ArpEntry>()
        try {
            val lines = java.io.File("/proc/net/arp").readLines()
            for (line in lines.drop(1)) { // skip header
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 6) {
                    val ip  = parts[0]
                    val mac = parts[3]
                    val dev = parts[5]
                    if (mac != "00:00:00:00:00:00" && dev.startsWith("wlan")) {
                        entries.add(ArpEntry(ip, mac))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ARP read error: ${e.message}")
        }
        return entries
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    data class ArpEntry(val ip: String, val mac: String)
}
