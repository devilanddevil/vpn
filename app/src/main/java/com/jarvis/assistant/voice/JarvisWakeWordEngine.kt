package com.jarvis.assistant.voice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.ai.GeminiAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class JarvisWakeWordEngine private constructor(private val context: Context) : RecognitionListener {

    companion object {
        private const val TAG = "JarvisWakeWordEngine"

        @Volatile
        private var instance: JarvisWakeWordEngine? = null

        fun getInstance(context: Context): JarvisWakeWordEngine {
            return instance ?: synchronized(this) {
                instance ?: JarvisWakeWordEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    enum class State {
        IDLE,
        LISTENING_FOR_WAKE_WORD,
        LISTENING_FOR_COMMAND,
        PROCESSING
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val geminiAgent = GeminiAgent(context)

    var currentState: State = State.IDLE
        private set

    var onStatusChanged: ((String) -> Unit)? = null
    var onTranscriptReceived: ((String) -> Unit)? = null
    var onCommandExecuted: ((String) -> Unit)? = null

    private var restartRunnable = Runnable {
        if (currentState == State.LISTENING_FOR_WAKE_WORD) {
            safeStartListening()
        }
    }

    private val wakeWords = listOf(
        "jarvis", "jarwis", "charvis", "service", "zarvis", "jarvees",
        "जार्विस", "जारविस", "सर्विस", "जार्विश", "चार्विस",
        "hey jarvis", "hello jarvis", "hi jarvis", "ok jarvis", "oye jarvis", "sun jarvis", "suno jarvis",
        "हे जार्विस", "हेलो जार्विस", "नमस्ते जार्विस", "सुन जार्विस"
    )

    fun startContinuousListening() {
        if (!hasAudioPermission()) {
            Log.w(TAG, "Audio permission not granted for continuous wake word")
            onStatusChanged?.invoke("Audio permission required for wake word")
            return
        }

        mainHandler.post {
            currentState = State.LISTENING_FOR_WAKE_WORD
            onStatusChanged?.invoke("Listening for 'Hey Jarvis'...")
            safeStartListening()
        }
    }

    fun stopListening() {
        mainHandler.post {
            mainHandler.removeCallbacks(restartRunnable)
            currentState = State.IDLE
            destroyRecognizer()
            onStatusChanged?.invoke("Voice Engine Standby")
        }
    }

    fun triggerManualListening() {
        if (!hasAudioPermission()) {
            onStatusChanged?.invoke("Audio permission missing")
            return
        }

        mainHandler.post {
            mainHandler.removeCallbacks(restartRunnable)
            currentState = State.LISTENING_FOR_COMMAND
            onStatusChanged?.invoke("Listening for command...")
            safeStartListening()
        }
    }

    private fun safeStartListening() {
        if (!hasAudioPermission()) return
        if (currentState == State.IDLE) return

        // If TTS is currently speaking, wait until it finishes
        val tts = AndroidTTSManager.getInstance(context)
        if (tts.isSpeaking()) {
            mainHandler.removeCallbacks(restartRunnable)
            mainHandler.postDelayed(restartRunnable, 600)
            return
        }

        try {
            if (speechRecognizer == null) {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    Log.e(TAG, "SpeechRecognizer not available on device")
                    onStatusChanged?.invoke("Speech recognition not available")
                    return
                }
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(this@JarvisWakeWordEngine)
                }
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES, arrayListOf("hi-IN", "en-IN", "en-US"))
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }

            speechRecognizer?.startListening(intent)
            Log.d(TAG, "SpeechRecognizer started in state: $currentState")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SpeechRecognizer", e)
            scheduleRestart(800)
        }
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying recognizer", e)
        } finally {
            speechRecognizer = null
        }
    }

    private fun scheduleRestart(delayMillis: Long = 350) {
        mainHandler.removeCallbacks(restartRunnable)
        if (currentState == State.LISTENING_FOR_WAKE_WORD) {
            mainHandler.postDelayed(restartRunnable, delayMillis)
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {
        if (currentState == State.LISTENING_FOR_COMMAND) {
            onStatusChanged?.invoke("Say your command now...")
        } else if (currentState == State.LISTENING_FOR_WAKE_WORD) {
            onStatusChanged?.invoke("Standby. Say 'Hey Jarvis'...")
        }
    }

    override fun onBeginningOfSpeech() {
        onStatusChanged?.invoke("Voice detected...")
    }

    override fun onRmsChanged(rmsdB: Float) {}

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        if (currentState == State.LISTENING_FOR_COMMAND) {
            onStatusChanged?.invoke("Processing command...")
        }
    }

    override fun onError(error: Int) {
        val errorMsg = when (error) {
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Busy"
            SpeechRecognizer.ERROR_AUDIO -> "Audio error"
            SpeechRecognizer.ERROR_CLIENT -> "Client error"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "No permission"
            else -> "Error $error"
        }
        Log.d(TAG, "SpeechRecognizer onError: $errorMsg ($error) in state: $currentState")

        when (currentState) {
            State.LISTENING_FOR_WAKE_WORD -> {
                // Normal background silence cycle: recreate if client or busy error, otherwise just restart
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                    destroyRecognizer()
                    scheduleRestart(600)
                } else {
                    scheduleRestart(350)
                }
            }
            State.LISTENING_FOR_COMMAND -> {
                if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH) {
                    onStatusChanged?.invoke("No command heard. Standby.")
                    currentState = State.LISTENING_FOR_WAKE_WORD
                    scheduleRestart(500)
                } else {
                    destroyRecognizer()
                    currentState = State.LISTENING_FOR_WAKE_WORD
                    scheduleRestart(800)
                }
            }
            State.PROCESSING, State.IDLE -> {}
        }
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (matches.isNullOrEmpty()) {
            scheduleRestart(350)
            return
        }

        val spokenText = matches[0].trim()
        Log.d(TAG, "Heard onResults: '$spokenText' in state: $currentState")

        when (currentState) {
            State.LISTENING_FOR_WAKE_WORD -> {
                handleWakeWordInput(spokenText)
            }
            State.LISTENING_FOR_COMMAND -> {
                handleCommandInput(spokenText)
            }
            State.PROCESSING, State.IDLE -> {}
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (matches.isNullOrEmpty()) return

        val partial = matches[0].trim()
        if (currentState == State.LISTENING_FOR_WAKE_WORD) {
            // Fast trigger if wake word spotted in partial results
            if (isWakeWordDetected(partial)) {
                Log.d(TAG, "Wake word detected early in partial results: $partial")
                speechRecognizer?.stopListening()
                handleWakeWordInput(partial)
            }
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}

    private fun isWakeWordDetected(text: String): Boolean {
        val lower = text.lowercase()
        val customName = JarvisApplication.instance.getAssistantName().lowercase()

        if (lower.contains(customName)) return true
        for (w in wakeWords) {
            if (lower.contains(w)) return true
        }
        return false
    }

    private fun extractCommandAfterWakeWord(text: String): String {
        var clean = text
        val customName = JarvisApplication.instance.getAssistantName().lowercase()
        val patterns = (wakeWords + listOf(customName, "hey $customName", "hello $customName")).distinct()

        for (pattern in patterns) {
            clean = clean.replace(Regex("(?i)\\b$pattern\\b"), "").trim()
        }
        return clean.trim()
    }

    private fun handleWakeWordInput(spokenText: String) {
        if (!isWakeWordDetected(spokenText)) {
            // Did not match wake word, keep listening
            scheduleRestart(300)
            return
        }

        onTranscriptReceived?.invoke(spokenText)
        val extractedCommand = extractCommandAfterWakeWord(spokenText)

        // Case 1: User spoke wake word AND command together (e.g., "Hey Jarvis WhatsApp open karo")
        if (extractedCommand.length >= 3 && containsActionWord(extractedCommand)) {
            Log.d(TAG, "Direct command detected with wake word: '$extractedCommand'")
            handleCommandInput(extractedCommand)
            return
        }

        // Case 2: User spoke ONLY the wake word (e.g., "Hey Jarvis" or "Hello Jarvis")
        currentState = State.PROCESSING
        destroyRecognizer()

        val assistantName = JarvisApplication.instance.getAssistantName()
        val greeting = "Yes Sir? Boliye, main kya madad karu?"
        onStatusChanged?.invoke("Wake word activated!")

        AndroidTTSManager.getInstance(context).speak(greeting, flush = true) {
            mainHandler.post {
                currentState = State.LISTENING_FOR_COMMAND
                onStatusChanged?.invoke("Listening for your command...")
                safeStartListening()
            }
        }
    }

    private fun containsActionWord(text: String): Boolean {
        val lower = text.lowercase()
        val actionKeywords = listOf(
            "open", "kholo", "khol", "chalao", "chalu", "start", "launch", "dikhao",
            "whatsapp", "youtube", "spotify", "instagram", "call", "phone", "lagao",
            "torch", "flashlight", "battery", "alarm", "timer", "volume", "music", "photo", "camera"
        )
        return actionKeywords.any { lower.contains(it) }
    }

    private fun handleCommandInput(commandText: String) {
        currentState = State.PROCESSING
        destroyRecognizer()

        onTranscriptReceived?.invoke("You: $commandText")
        onStatusChanged?.invoke("Executing command...")

        scope.launch {
            try {
                val result = geminiAgent.processUserCommand(commandText)
                onCommandExecuted?.invoke(result)
                onStatusChanged?.invoke("Executed: $result")
            } catch (e: Exception) {
                Log.e(TAG, "Error executing command", e)
                onStatusChanged?.invoke("Execution error: ${e.localizedMessage}")
            } finally {
                // Return to continuous wake word listening
                mainHandler.postDelayed({
                    currentState = State.LISTENING_FOR_WAKE_WORD
                    onStatusChanged?.invoke("Standby. Say 'Hey Jarvis'...")
                    safeStartListening()
                }, 1000)
            }
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
}
