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
        val pm = context.packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        for (app in installedApps) {
            val label = pm.getApplicationLabel(app).toString()
            if (label.contains(appName, ignoreCase = true) || appName.contains(label, ignoreCase = true)) {
                val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    Log.d(TAG, "Launched app: $label (${app.packageName})")
                    return true
                }
            }
        }
        Log.w(TAG, "App not found: $appName")
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
