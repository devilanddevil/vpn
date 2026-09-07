package com.jarvis.assistant

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64

class JarvisApplication : Application() {

    companion object {
        lateinit var instance: JarvisApplication
            private set

        const val PREFS_NAME = "jarvis_settings"
        const val KEY_GEMINI_API_KEY = "gemini_api_key"
        const val KEY_ASSISTANT_NAME = "assistant_name"
        private const val DEFAULT_KEY_B64 = "QVEuQWI4Uk42SWpVeXlTZk1wR1hWVlR0cjJoRV9pUkFGX2F5cDMtZVVBTk5Pc0xValYwR2c="

        fun getDefaultKey(): String {
            return try {
                String(Base64.decode(DEFAULT_KEY_B64, Base64.DEFAULT), Charsets.UTF_8).trim()
            } catch (e: Exception) {
                ""
            }
        }
    }

    lateinit var prefs: SharedPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_GEMINI_API_KEY, "").isNullOrBlank()) {
            val defKey = getDefaultKey()
            if (defKey.isNotBlank()) {
                setGeminiApiKey(defKey)
            }
        }
    }

    fun getAssistantName(): String {
        return prefs.getString(KEY_ASSISTANT_NAME, "Jarvis") ?: "Jarvis"
    }

    fun setAssistantName(name: String) {
        prefs.edit().putString(KEY_ASSISTANT_NAME, name).apply()
    }

    fun getGeminiApiKey(): String {
        val key = prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
        return if (key.isNotBlank()) key else getDefaultKey()
    }

    fun setGeminiApiKey(apiKey: String) {
        prefs.edit().putString(KEY_GEMINI_API_KEY, apiKey).apply()
    }
}
