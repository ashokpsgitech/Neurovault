package com.example.frontend

import android.content.Context
import android.content.Intent
import android.os.Build
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val CHANNEL = "neurovault/host_service"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "startHostService" -> {
                    val reservedGb = call.argument<Int>("reservedGb") ?: 10
                    val containerPath = call.argument<String>("containerPath") ?: ""

                    // Save host state for BootReceiver
                    val prefs = getSharedPreferences("neurovault_host_prefs", Context.MODE_PRIVATE)
                    prefs.edit().apply {
                        putBoolean("host_enabled", true)
                        putInt("reserved_gb", reservedGb)
                        putString("container_path", containerPath)
                        apply()
                    }

                    val serviceIntent = Intent(this, HostForegroundService::class.java).apply {
                        action = HostForegroundService.ACTION_START
                        putExtra("reservedGb", reservedGb)
                        putExtra("containerPath", containerPath)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    result.success(true)
                }

                "stopHostService" -> {
                    val prefs = getSharedPreferences("neurovault_host_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("host_enabled", false).apply()

                    val serviceIntent = Intent(this, HostForegroundService::class.java).apply {
                        action = HostForegroundService.ACTION_STOP
                    }
                    stopService(serviceIntent)
                    result.success(true)
                }

                "isServiceRunning" -> {
                    result.success(HostForegroundService.isRunning)
                }

                else -> result.notImplemented()
            }
        }
    }
}
