package com.jarvis.assistant.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log

class DeviceControlManager(private val context: Context) {

    companion object {
        private const val TAG = "JarvisDeviceControl"
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var isTorchOn = false

    fun toggleTorch(enable: Boolean): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && cameraManager != null) {
                val cameraId = cameraManager.cameraIdList[0]
                cameraManager.setTorchMode(cameraId, enable)
                isTorchOn = enable
                Log.d(TAG, "Flashlight set to: $enable")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling torch", e)
            false
        }
    }

    fun setVolume(streamType: Int = AudioManager.STREAM_MUSIC, percent: Int): Boolean {
        return try {
            if (audioManager != null) {
                val maxVolume = audioManager.getStreamMaxVolume(streamType)
                val target = (maxVolume * (percent.coerceIn(0, 100) / 100f)).toInt()
                audioManager.setStreamVolume(streamType, target, AudioManager.FLAG_SHOW_UI)
                Log.d(TAG, "Volume set to $percent%")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting volume", e)
            false
        }
    }

    fun openAppByName(appName: String): Boolean {
        val raw = appName.trim()
        if (raw.isBlank()) return false

        val pm = context.packageManager

        // 1. Clean app name of voice command artifacts
        val cleanName = raw.lowercase()
            .replace(Regex("(?i)\\b(open|kholo|khol|chalu|chalao|chala|start|launch|dikhao|karo|kar do|kar|do|please|bhai|app|application|on|ko)\\b"), " ")
            .replace(Regex("[^a-zA-Z0-9\\u0900-\\u097F\\s]"), " ")
            .trim()

        val queryTarget = if (cleanName.isNotBlank()) cleanName else raw.lowercase()

        // 2. Direct Package Dictionary for top apps
        val directMap = mapOf(
            "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "व्हाट्सएप" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "व्हाट्सऐप" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "whatsap" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "what's app" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "youtube" to listOf("com.google.android.youtube"),
            "यूट्यूब" to listOf("com.google.android.youtube"),
            "spotify" to listOf("com.spotify.music"),
            "स्पॉटिफाई" to listOf("com.spotify.music"),
            "instagram" to listOf("com.instagram.android"),
            "insta" to listOf("com.instagram.android"),
            "इंस्टाग्राम" to listOf("com.instagram.android"),
            "facebook" to listOf("com.facebook.katana", "com.facebook.lite"),
            "fb" to listOf("com.facebook.katana", "com.facebook.lite"),
            "फेसबुक" to listOf("com.facebook.katana", "com.facebook.lite"),
            "chrome" to listOf("com.android.chrome"),
            "क्रोम" to listOf("com.android.chrome"),
            "telegram" to listOf("org.telegram.messenger", "org.thunderdog.challegram"),
            "टेलीग्राम" to listOf("org.telegram.messenger"),
            "snapchat" to listOf("com.snapchat.android"),
            "स्नैपचैट" to listOf("com.snapchat.android"),
            "maps" to listOf("com.google.android.apps.maps"),
            "google maps" to listOf("com.google.android.apps.maps"),
            "gmail" to listOf("com.google.android.gm"),
            "google pay" to listOf("com.google.android.apps.nbu.paisa.user"),
            "gpay" to listOf("com.google.android.apps.nbu.paisa.user"),
            "paytm" to listOf("net.one97.paytm"),
            "phonepe" to listOf("com.phonepe.app")
        )

        for ((key, pkgList) in directMap) {
            if (queryTarget.contains(key) || key.contains(queryTarget)) {
                for (pkg in pkgList) {
                    val launchIntent = pm.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        Log.d(TAG, "Launched direct mapped app: $pkg for query '$appName'")
                        return true
                    }
                }
            }
        }

        // 3. Fallback System Intents (Camera, Settings, Phone, Gallery)
        if (queryTarget.contains("camera") || queryTarget.contains("कैमरा")) {
            try {
                val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return true
            } catch (ignored: Exception) {}
        }

        if (queryTarget.contains("setting") || queryTarget.contains("सेटिंग")) {
            try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return true
            } catch (ignored: Exception) {}
        }

        if (queryTarget.contains("phone") || queryTarget.contains("dialer") || queryTarget.contains("dial") || queryTarget.contains("कॉल")) {
            try {
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return true
            } catch (ignored: Exception) {}
        }

        // 4. Query All Launcher Activities
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            for (info in resolveInfos) {
                val label = info.loadLabel(pm).toString().lowercase()
                val pkg = info.activityInfo.packageName.lowercase()

                if (label.contains(queryTarget) || queryTarget.contains(label) || pkg.contains(queryTarget)) {
                    val launchIntent = pm.getLaunchIntentForPackage(info.activityInfo.packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        Log.d(TAG, "Launched matching launcher app: ${info.activityInfo.packageName}")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying launcher activities", e)
        }

        // 5. Inspect installed applications metadata
        try {
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in installedApps) {
                val label = pm.getApplicationLabel(app).toString().lowercase()
                val pkg = app.packageName.lowercase()

                if (label.contains(queryTarget) || queryTarget.contains(label) || pkg.contains(queryTarget)) {
                    val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        Log.d(TAG, "Launched installed app: ${app.packageName}")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying installed applications", e)
        }

        Log.w(TAG, "App not found for query: '$appName' (cleaned: '$queryTarget')")
        return false
    }

    fun getBatteryPercentage(): Int {
        return try {
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
                context.registerReceiver(null, ifilter)
            }
            val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                ((level / scale.toFloat()) * 100).toInt()
            } else {
                50
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting battery level", e)
            50
        }
    }

    fun isPhoneCharging(): Boolean {
        return try {
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
                context.registerReceiver(null, ifilter)
            }
            val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        } catch (e: Exception) {
            false
        }
    }

    fun getBatteryReport(): String {
        val percent = getBatteryPercentage()
        val charging = isPhoneCharging()
        return if (charging) {
            "Battery $percent percent hai aur phone abhi charge ho raha hai, Sir."
        } else {
            "Battery level $percent percent hai, Sir."
        }
    }

    fun openWifiSettings(): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun openBluetoothSettings(): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
