package com.mamatai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.mamatai.util.DataStore
import kotlinx.coroutines.*

class HotspotService : Service() {

    companion object {
        const val TAG = "HotspotService"
        const val NOTIF_CHANNEL = "mamatai_hotspot"
        const val NOTIF_ID = 3
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DataStore.init(applicationContext)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startScanner()
        return START_STICKY
    }

    private fun startScanner() {
        scope.launch {
            while (true) {
                try {
                    val users = DataStore.getConnectedUsers()
                    users.forEach { user ->
                        if (user.isExpired && user.isForwarding) {
                            user.isForwarding = false
                            DataStore.addOrUpdateUser(user)
                            Log.d(TAG, "Auto-expired: ${user.ipAddress}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Scanner error: ${e.message}")
                }
                delay(20_000) // check every 20 seconds — very light
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            NOTIF_CHANNEL, "MAMA.TAI Scanner",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("MAMA.TAI")
            .setContentText("Monitoring users...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }
}
