package com.jarvis.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class AndroidTTSManager private constructor(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "JarvisTTS"

        @Volatile
        private var instance: AndroidTTSManager? = null

        fun getInstance(context: Context): AndroidTTSManager {
            return instance ?: synchronized(this) {
                instance ?: AndroidTTSManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isInitialized = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            // Support Indian English or Hindi
            val localeResult = tts?.setLanguage(Locale("hi", "IN"))
            if (localeResult == TextToSpeech.LANG_MISSING_DATA || localeResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale("en", "IN"))
            }

            tts?.setPitch(0.95f) // Crisp Jarvis slightly deeper pitch
            tts?.setSpeechRate(1.05f) // Slightly faster pace
            Log.d(TAG, "Jarvis TTS Initialized successfully!")
        } else {
            Log.e(TAG, "Jarvis TTS Initialization failed with status: $status")
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!isInitialized || tts == null) {
            Log.w(TAG, "TTS not ready yet, queuing: $text")
            return
        }

        val utteranceId = "JarvisUtterance_${System.currentTimeMillis()}"

        if (onDone != null) {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId) onDone()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {}
            })
        }

        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        instance = null
    }
}
