package com.example.frontend

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            val prefs = context.getSharedPreferences("neurovault_host_prefs", Context.MODE_PRIVATE)
            val hostEnabled = prefs.getBoolean("host_enabled", false)

            if (hostEnabled) {
                val reservedGb = prefs.getInt("reserved_gb", 10)
                val containerPath = prefs.getString("container_path", "") ?: ""

                val serviceIntent = Intent(context, HostForegroundService::class.java).apply {
                    action = HostForegroundService.ACTION_START
                    putExtra("reservedGb", reservedGb)
                    putExtra("containerPath", containerPath)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
