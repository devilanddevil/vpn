package com.jarvis.assistant.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.ai.GeminiAgent
import com.jarvis.assistant.databinding.ActivityMainBinding
import com.jarvis.assistant.service.*
import com.jarvis.assistant.voice.AndroidTTSManager
import com.jarvis.assistant.voice.VoiceRecognitionManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var geminiAgent: GeminiAgent
    private lateinit var callManager: CallManager
    private lateinit var deviceControl: DeviceControlManager
    private var voiceRecognitionManager: VoiceRecognitionManager? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        updatePermissionButtonsUI()
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "All runtime permissions granted!", Toast.LENGTH_SHORT).show()
        }
        if (hasAudioPermission()) {
            checkAndStartForegroundService()
        }
        if (hasCallPermission()) {
            callManager.registerCallListener()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        geminiAgent = GeminiAgent(this)
        callManager = CallManager(this)
        deviceControl = DeviceControlManager(this)

        if (hasCallPermission()) {
            callManager.registerCallListener()
        }

        setupUI()
        setupVoiceRecognition()

        if (hasAudioPermission()) {
            checkAndStartForegroundService()
        } else {
            requestRuntimePermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionButtonsUI()
    }

    private fun setupUI() {
        val app = JarvisApplication.instance
        binding.etAssistantName.setText(app.getAssistantName())
        binding.etApiKey.setText(app.getGeminiApiKey())

        // Save AI Config
        binding.btnSaveConfig.setOnClickListener {
            val name = binding.etAssistantName.text.toString().trim()
            val key = binding.etApiKey.text.toString().trim()

            if (name.isNotBlank()) app.setAssistantName(name)
            if (key.isNotBlank()) app.setGeminiApiKey(key)

            Toast.makeText(this, "Settings Saved for $name!", Toast.LENGTH_SHORT).show()
            AndroidTTSManager.getInstance(this).speak("Configuration saved. System online, Sir.")
        }

        // Permission: Notification Interceptor
        binding.btnEnableNotification.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        // Permission: Accessibility Service
        binding.btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Permission: Audio / Calls / Contacts
        binding.btnEnableAudio.setOnClickListener {
            requestRuntimePermissions()
        }

        // Permission: Overlay (Display over other apps)
        binding.btnEnableOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        // Permission: Camera
        binding.btnEnableCamera.setOnClickListener {
            requestRuntimePermissions()
        }

        // Advanced Protocols: Floating Arc Reactor Switch
        binding.switchFloatingReactor.isChecked = JarvisOverlayService.isRunning
        binding.switchFloatingReactor.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    binding.switchFloatingReactor.isChecked = false
                    Toast.makeText(this, "Please grant Overlay Permission first", Toast.LENGTH_SHORT).show()
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    JarvisOverlayService.start(this)
                    Toast.makeText(this, "Floating Arc Reactor Activated!", Toast.LENGTH_SHORT).show()
                }
            } else {
                JarvisOverlayService.stop(this)
            }
        }

        // Advanced Protocols: Vision Scanner
        binding.btnLaunchVision.setOnClickListener {
            if (hasCameraPermission()) {
                startActivity(Intent(this, JarvisVisionActivity::class.java))
            } else {
                requestRuntimePermissions()
            }
        }

        // Advanced Protocols: Battery Check
        binding.btnCheckBattery.setOnClickListener {
            val report = deviceControl.getBatteryReport()
            binding.tvTranscript.text = report
            AndroidTTSManager.getInstance(this).speak(report)
        }

        // FAB Mic Button
        binding.fabMic.setOnClickListener {
            if (hasAudioPermission()) {
                voiceRecognitionManager?.startListening()
            } else {
                requestRuntimePermissions()
            }
        }
    }

    private fun setupVoiceRecognition() {
        voiceRecognitionManager = VoiceRecognitionManager(
            context = this,
            onResultCallback = { recognizedText ->
                binding.tvTranscript.text = "You said: \"$recognizedText\""
                binding.tvVoiceStatus.text = "Analyzing command with Gemini AI..."

                lifecycleScope.launch {
                    val result = geminiAgent.processUserCommand(recognizedText)
                    binding.tvVoiceStatus.text = "Executed: $result"
                }
            },
            onStatusCallback = { status ->
                binding.tvVoiceStatus.text = status
            }
        )
    }

    private fun updatePermissionButtonsUI() {
        // Notification Listener
        val isNotifEnabled = isNotificationServiceEnabled()
        binding.btnEnableNotification.text = if (isNotifEnabled) "Active ✓" else "Grant"
        binding.btnEnableNotification.isEnabled = !isNotifEnabled

        // Accessibility Service
        val isAccessEnabled = isAccessibilityServiceEnabled()
        binding.btnEnableAccessibility.text = if (isAccessEnabled) "Active ✓" else "Grant"
        binding.btnEnableAccessibility.isEnabled = !isAccessEnabled

        // Runtime Permissions
        val hasRuntime = hasAudioPermission() && hasCallPermission()
        binding.btnEnableAudio.text = if (hasRuntime) "Active ✓" else "Grant"
        binding.btnEnableAudio.isEnabled = !hasRuntime

        // Overlay Permission
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
        binding.btnEnableOverlay.text = if (hasOverlay) "Active ✓" else "Grant"
        binding.btnEnableOverlay.isEnabled = !hasOverlay

        // Camera Permission
        val hasCamera = hasCameraPermission()
        binding.btnEnableCamera.text = if (hasCamera) "Active ✓" else "Grant"
        binding.btnEnableCamera.isEnabled = !hasCamera

        binding.switchFloatingReactor.isChecked = JarvisOverlayService.isRunning
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasCallPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (!TextUtils.isEmpty(flat)) {
            val names = flat.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            for (name in names) {
                val cn = ComponentName.unflattenFromString(name)
                if (cn != null && TextUtils.equals(pkgName, cn.packageName)) {
                    return true
                }
            }
        }
        return false
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, JarvisAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)

        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponent != null && enabledComponent == expectedComponentName) {
                return true
            }
        }
        return false
    }

    private fun checkAndStartForegroundService() {
        if (!hasAudioPermission()) {
            return
        }
        try {
            JarvisForegroundService.start(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceRecognitionManager?.stopListening()
    }
}
