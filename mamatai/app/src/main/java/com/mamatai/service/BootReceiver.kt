package com.mamatai.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startForegroundService(Intent(context, PortalServerService::class.java))
            context.startForegroundService(Intent(context, HotspotService::class.java))
        }
    }
}
