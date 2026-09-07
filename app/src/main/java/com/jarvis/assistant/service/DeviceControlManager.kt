package com.jarvis.assistant.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
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
}
