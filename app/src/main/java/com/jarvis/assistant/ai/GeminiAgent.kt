package com.jarvis.assistant.ai

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.service.CallManager
import com.jarvis.assistant.service.DeviceControlManager
import com.jarvis.assistant.service.JarvisAccessibilityService
import com.jarvis.assistant.service.JarvisNotificationListenerService
import com.jarvis.assistant.voice.AndroidTTSManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class GeminiAgent(private val context: Context) {

    companion object {
        private const val TAG = "JarvisGeminiAgent"
        private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val callManager = CallManager(context)
    private val deviceControl = DeviceControlManager(context)

    suspend fun processUserCommand(userInput: String): String = withContext(Dispatchers.IO) {
        val apiKey = JarvisApplication.instance.getGeminiApiKey()
        val assistantName = JarvisApplication.instance.getAssistantName()

        // Fallback local pattern matching if API key not set yet
        if (apiKey.isBlank()) {
            return@withContext handleLocalCommandFallback(userInput, assistantName)
        }

        try {
            val systemPrompt = """
                You are $assistantName, an elite AI voice assistant inspired by Tony Stark's JARVIS.
                You have direct admin control over the user's Android phone.
                The user can speak in Hindi, Hinglish, or English.
                Your job is to analyze their command and return a JSON action object ONLY.
                No markdown, no backticks, only raw JSON.

                Available actions:
                1. {"action": "call", "name": "<contact_name>", "reply": "<short confirmation in Hindi/English>"}
                2. {"action": "answer_call", "reply": "Call utha liya gaya hai, Sir."}
                3. {"action": "end_call", "reply": "Call cut kar diya gaya hai, Sir."}
                4. {"action": "reply_notification", "text": "<text to reply>", "reply": "Reply bhej diya gaya hai, Sir."}
                5. {"action": "torch", "state": true/false, "reply": "Torch on/off kar di gayi hai, Sir."}
                6. {"action": "volume", "percent": 0-100, "reply": "Volume set to..."}
                7. {"action": "open_app", "app_name": "<app_name>", "reply": "Opening <app_name>, Sir."}
                8. {"action": "speak", "reply": "<intelligent witty answer to user's question>"}
            """.trimIndent()

            val requestBodyJson = JsonObject().apply {
                val contents = com.google.gson.JsonArray()
                val userPart = JsonObject().apply {
                    val parts = com.google.gson.JsonArray()
                    parts.add(JsonObject().apply {
                        addProperty("text", "$systemPrompt\n\nUser Command: \"$userInput\"")
                    })
                    add("parts", parts)
                }
                contents.add(userPart)
                add("contents", contents)
            }

            val request = Request.Builder()
                .url("$GEMINI_URL?key=$apiKey")
                .post(requestBodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Gemini API Error: ${response.code} $responseBody")
                return@withContext handleLocalCommandFallback(userInput, assistantName)
            }

            val parsedJson = gson.fromJson(responseBody, JsonObject::class.java)
            val candidates = parsedJson.getAsJsonArray("candidates")
            if (candidates != null && candidates.size() > 0) {
                val textResponse = candidates[0].asJsonObject
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts")[0].asJsonObject
                    .get("text").asString.trim()

                val cleanJson = textResponse.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                return@withContext executeParsedAction(cleanJson, assistantName)
            }

            return@withContext handleLocalCommandFallback(userInput, assistantName)
        } catch (e: Exception) {
            Log.e(TAG, "Exception contacting Gemini AI", e)
            return@withContext handleLocalCommandFallback(userInput, assistantName)
        }
    }

    private fun executeParsedAction(jsonString: String, assistantName: String): String {
        return try {
            val json = gson.fromJson(jsonString, JsonObject::class.java)
            val action = json.get("action")?.asString ?: "speak"
            val reply = json.get("reply")?.asString ?: "Ji Sir, samajh gaya."

            when (action) {
                "call" -> {
                    val name = json.get("name")?.asString ?: ""
                    callManager.findContactAndCall(name)
                }
                "answer_call" -> {
                    callManager.answerCall()
                    AndroidTTSManager.getInstance(context).speak(reply)
                }
                "end_call" -> {
                    callManager.endCall()
                    AndroidTTSManager.getInstance(context).speak(reply)
                }
                "reply_notification" -> {
                    val replyText = json.get("text")?.asString ?: ""
                    val success = JarvisNotificationListenerService.instance?.replyToLatestMessage(replyText) == true
                    if (success) {
                        AndroidTTSManager.getInstance(context).speak(reply)
                    } else {
                        AndroidTTSManager.getInstance(context).speak("Sir, reply bhejne ke liye koi active message nahi mila.")
                    }
                }
                "torch" -> {
                    val state = json.get("state")?.asBoolean ?: true
                    deviceControl.toggleTorch(state)
                    AndroidTTSManager.getInstance(context).speak(reply)
                }
                "volume" -> {
                    val percent = json.get("percent")?.asInt ?: 50
                    deviceControl.setVolume(percent = percent)
                    AndroidTTSManager.getInstance(context).speak(reply)
                }
                "open_app" -> {
                    val appName = json.get("app_name")?.asString ?: ""
                    deviceControl.openAppByName(appName)
                    AndroidTTSManager.getInstance(context).speak(reply)
                }
                else -> {
                    AndroidTTSManager.getInstance(context).speak(reply)
                }
            }
            reply
        } catch (e: Exception) {
            Log.e(TAG, "Error executing action: $jsonString", e)
            AndroidTTSManager.getInstance(context).speak("Command samajh me aayi par execute nahi ho payi, Sir.")
            "Error executing action"
        }
    }

    private fun handleLocalCommandFallback(input: String, assistantName: String): String {
        val lower = input.lowercase()
        val tts = AndroidTTSManager.getInstance(context)

        return when {
            lower.contains("call") || lower.contains("phone") -> {
                val name = input.replace(Regex("(?i)(call|phone|lagao|karo|ko|to)"), "").trim()
                if (name.isNotBlank()) {
                    callManager.findContactAndCall(name)
                    "Calling $name..."
                } else {
                    tts.speak("Kise call lagana hai, Sir?")
                    "Kise call lagana hai?"
                }
            }
            lower.contains("uthao") || lower.contains("answer") || lower.contains("receive") -> {
                callManager.answerCall()
                tts.speak("Call connect kar diya hai, Sir.")
                "Call answered"
            }
            lower.contains("cut") || lower.contains("reject") || lower.contains("disconnect") -> {
                callManager.endCall()
                tts.speak("Call disconnect kar diya gaya hai, Sir.")
                "Call rejected"
            }
            lower.contains("torch on") || lower.contains("flashlight on") -> {
                deviceControl.toggleTorch(true)
                tts.speak("Flashlight on kar di gayi hai, Sir.")
                "Flashlight ON"
            }
            lower.contains("torch off") || lower.contains("flashlight off") -> {
                deviceControl.toggleTorch(false)
                tts.speak("Flashlight off kar di gayi hai, Sir.")
                "Flashlight OFF"
            }
            lower.contains("open") || lower.contains("kholo") || lower.contains("chalao") -> {
                val appName = input.replace(Regex("(?i)(open|kholo|chalao|app)"), "").trim()
                val success = deviceControl.openAppByName(appName)
                if (success) {
                    tts.speak("$appName open ho gaya hai, Sir.")
                    "Opening $appName"
                } else {
                    tts.speak("Phone me $appName nahi mila, Sir.")
                    "App not found"
                }
            }
            lower.contains("reply") -> {
                val replyText = input.replace(Regex("(?i)(reply|karo|bhejo|bolo)"), "").trim()
                val success = JarvisNotificationListenerService.instance?.replyToLatestMessage(replyText) == true
                if (success) {
                    tts.speak("Reply bhej diya gaya hai, Sir.")
                    "Reply sent"
                } else {
                    tts.speak("Reply bhejne ke liye koi unread message nahi mila, Sir.")
                    "No message to reply"
                }
            }
            else -> {
                val defaultReply = "Ji Sir, main $assistantName hu. Boliye main kya kaam karu?"
                tts.speak(defaultReply)
                defaultReply
            }
        }
    }
}
