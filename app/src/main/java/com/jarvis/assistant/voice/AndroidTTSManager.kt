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
    @Volatile
    private var isCurrentlySpeaking = false
    private val speechListeners = mutableListOf<() -> Unit>()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            val localeResult = tts?.setLanguage(Locale("hi", "IN"))
            if (localeResult == TextToSpeech.LANG_MISSING_DATA || localeResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale("en", "IN"))
            }

            tts?.setPitch(0.95f)
            tts?.setSpeechRate(1.05f)

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isCurrentlySpeaking = true
                }

                override fun onDone(utteranceId: String?) {
                    isCurrentlySpeaking = false
                    val iterator = speechListeners.iterator()
                    while (iterator.hasNext()) {
                        try {
                            iterator.next().invoke()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in speech completion callback", e)
                        }
                    }
                    speechListeners.clear()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    isCurrentlySpeaking = false
                    speechListeners.clear()
                }
            })

            Log.d(TAG, "Jarvis TTS Initialized successfully!")
        } else {
            Log.e(TAG, "Jarvis TTS Initialization failed with status: $status")
        }
    }

    fun isSpeaking(): Boolean {
        return isCurrentlySpeaking || tts?.isSpeaking == true
    }

    fun speak(text: String, flush: Boolean = true, onDone: (() -> Unit)? = null) {
        if (!isInitialized || tts == null) {
            Log.w(TAG, "TTS not ready yet, queuing: $text")
            onDone?.invoke()
            return
        }

        if (onDone != null) {
            speechListeners.add(onDone)
        }

        val utteranceId = "JarvisUtterance_${System.currentTimeMillis()}"
        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        isCurrentlySpeaking = true
        tts?.speak(text, queueMode, null, utteranceId)
    }

    fun stop() {
        isCurrentlySpeaking = false
        speechListeners.clear()
        tts?.stop()
    }

    fun shutdown() {
        isCurrentlySpeaking = false
        speechListeners.clear()
        tts?.shutdown()
        instance = null
    }
}
