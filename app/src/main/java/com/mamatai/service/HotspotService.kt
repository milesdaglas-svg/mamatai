package com.mamatai.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.mamatai.util.DataStore
import kotlinx.coroutines.*

class HotspotService : Service() {

    companion object {
        const val TAG = "HotspotService"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DataStore.init(applicationContext)
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
                delay(15_000)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
