package com.jarvis.assistant

import android.app.Application
import android.content.Context
import android.content.SharedPreferences

class JarvisApplication : Application() {

    companion object {
        lateinit var instance: JarvisApplication
            private set
        
        const val PREFS_NAME = "jarvis_settings"
        const val KEY_GEMINI_API_KEY = "gemini_api_key"
        const val KEY_ASSISTANT_NAME = "assistant_name"
    }

    lateinit var prefs: SharedPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getAssistantName(): String {
        return prefs.getString(KEY_ASSISTANT_NAME, "Jarvis") ?: "Jarvis"
    }

    fun setAssistantName(name: String) {
        prefs.edit().putString(KEY_ASSISTANT_NAME, name).apply()
    }

    fun getGeminiApiKey(): String {
        return prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
    }

    fun setGeminiApiKey(apiKey: String) {
        prefs.edit().putString(KEY_GEMINI_API_KEY, apiKey).apply()
    }
}
