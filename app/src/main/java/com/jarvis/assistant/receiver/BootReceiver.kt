package com.jarvis.assistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.assistant.service.JarvisForegroundService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED && context != null) {
            val hasMicPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (hasMicPermission) {
                Log.d("JarvisBootReceiver", "Boot completed detected! Starting Jarvis background service...")
                JarvisForegroundService.start(context)
            } else {
                Log.w("JarvisBootReceiver", "Audio permission not granted, skipping foreground service startup.")
            }
        }
    }
}
