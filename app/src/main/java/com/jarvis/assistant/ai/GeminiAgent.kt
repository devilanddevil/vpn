package com.jarvis.assistant.ai

import android.content.Context
import android.content.Intent
import android.provider.Settings
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

        // 1. Capture live screen context from Accessibility Service
        val accessibility = JarvisAccessibilityService.instance
        val screenState = accessibility?.captureCurrentScreenState()

        val screenContextSummary = if (screenState != null && screenState.clickableOptions.isNotEmpty()) {
            """
            [CURRENT ON-SCREEN CONTEXT]
            Active Package: ${screenState.packageName}
            Visible Clickable Options: ${screenState.clickableOptions.take(15).joinToString(", ")}
            Visible Switches/Toggles: ${screenState.toggleOptions.entries.take(10).joinToString(", ") { "${it.key}: ${if (it.value) "ON" else "OFF"}" }}
            Input Fields: ${screenState.inputFields.take(5).joinToString(", ")}
            """.trimIndent()
        } else {
            "[CURRENT ON-SCREEN CONTEXT]: Home Screen or idle app."
        }

        // 2. Direct fast-path for instant Revert command
        val lower = userInput.lowercase()
        if (lower.contains("revert") || lower.contains("pehle jaisa") || lower.contains("undo") || lower.contains("wapas karo")) {
            val revertMsg = accessibility?.revertLastAction() ?: "Revert service available nahi hai, Sir."
            AndroidTTSManager.getInstance(context).speak(revertMsg)
            return@withContext revertMsg
        }

        // 3. Fallback if no API key
        if (apiKey.isBlank()) {
            return@withContext handleAutonomousFallback(userInput, assistantName, screenState)
        }

        try {
            val systemPrompt = """
                You are $assistantName, an autonomous, hyper-intelligent AI voice assistant with FULL CONTROL over the user's Android phone.
                You think like a human assistant, understanding context, nuances, and commands in Hindi, Hinglish, and English.
                
                $screenContextSummary

                Analyze the user's command and decide the best action.
                Return ONLY a valid JSON object without markdown formatting.

                Available Actions:
                1. {"action": "open_settings", "sub_setting": "display|sound|wifi|bluetooth|main", "reply": "Settings open kar raha hu, Sir."}
                2. {"action": "read_screen", "reply": "<describe what options/settings are currently visible on screen in Hindi/English>"}
                3. {"action": "toggle_setting", "target": "<name of toggle/switch>", "state": true/false, "reply": "<confirmation>"}
                4. {"action": "revert_setting", "reply": "Reverting last change."}
                5. {"action": "click_ui", "target": "<exact text of button/option on screen>", "reply": "<confirmation>"}
                6. {"action": "type_ui", "target": "<field name or null>", "text": "<text to enter>", "reply": "<confirmation>"}
                7. {"action": "open_app", "app_name": "<app name>", "reply": "Opening <app>..."}
                8. {"action": "scroll", "direction": "down|up", "reply": "Scrolling..."}
                9. {"action": "call", "name": "<contact_name>", "reply": "<confirmation>"}
                10. {"action": "answer_call", "reply": "Call utha liya gaya hai, Sir."}
                11. {"action": "end_call", "reply": "Call cut kar diya gaya hai, Sir."}
                12. {"action": "reply_notification", "text": "<reply message>", "reply": "Reply bhej diya hai, Sir."}
                13. {"action": "torch", "state": true/false, "reply": "Torch on/off kar di gayi hai, Sir."}
                14. {"action": "volume", "percent": 0-100, "reply": "Volume set kar diya hai, Sir."}
                15. {"action": "speak", "reply": "<thoughtful, witty, or factual response in Hindi/English>"}
            """.trimIndent()

            val requestBodyJson = JsonObject().apply {
                val contents = com.google.gson.JsonArray()
                val userPart = JsonObject().apply {
                    val parts = com.google.gson.JsonArray()
                    parts.add(JsonObject().apply {
                        addProperty("text", "$systemPrompt\n\nUser Voice Command: \"$userInput\"")
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
                return@withContext handleAutonomousFallback(userInput, assistantName, screenState)
            }

            val parsedJson = gson.fromJson(responseBody, JsonObject::class.java)
            val candidates = parsedJson.getAsJsonArray("candidates")
            if (candidates != null && candidates.size() > 0) {
                val textResponse = candidates[0].asJsonObject
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts")[0].asJsonObject
                    .get("text").asString.trim()

                val cleanJson = textResponse.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                return@withContext executeAutonomousAction(cleanJson, assistantName)
            }

            return@withContext handleAutonomousFallback(userInput, assistantName, screenState)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini call exception", e)
            return@withContext handleAutonomousFallback(userInput, assistantName, screenState)
        }
    }

    private fun executeAutonomousAction(jsonString: String, assistantName: String): String {
        val tts = AndroidTTSManager.getInstance(context)
        val accessibility = JarvisAccessibilityService.instance

        return try {
            val json = gson.fromJson(jsonString, JsonObject::class.java)
            val action = json.get("action")?.asString ?: "speak"
            val reply = json.get("reply")?.asString ?: "Ji Sir, samajh gaya."

            when (action) {
                "open_settings" -> {
                    val sub = json.get("sub_setting")?.asString ?: "main"
                    val intent = when (sub) {
                        "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
                        "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
                        "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
                        "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                        else -> Intent(Settings.ACTION_SETTINGS)
                    }.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                    context.startActivity(intent)
                    tts.speak(reply)
                }
                "read_screen" -> {
                    val state = accessibility?.captureCurrentScreenState()
                    if (state != null && state.clickableOptions.isNotEmpty()) {
                        val spoken = "Screen par ye options dikh rahe hain: " + state.clickableOptions.take(6).joinToString(", ")
                        tts.speak(spoken)
                    } else {
                        tts.speak(reply)
                    }
                }
                "toggle_setting" -> {
                    val target = json.get("target")?.asString ?: ""
                    val desiredState = if (json.has("state")) json.get("state").asBoolean else null
                    val success = accessibility?.toggleSettingSwitch(target, desiredState) == true
                    if (success) {
                        tts.speak(reply)
                    } else {
                        tts.speak("Screen par $target switch nahi mila, Sir. Kya option scroll karke dhoondhu?")
                    }
                }
                "revert_setting" -> {
                    val revertMsg = accessibility?.revertLastAction() ?: "Revert karne ke liye koi action nahi mila."
                    tts.speak(revertMsg)
                }
                "click_ui" -> {
                    val target = json.get("target")?.asString ?: ""
                    val success = accessibility?.clickElementByText(target) == true
                    if (success) {
                        tts.speak(reply)
                    } else {
                        tts.speak("Screen par $target button nahi mila, Sir.")
                    }
                }
                "type_ui" -> {
                    val targetField = if (json.has("target")) json.get("target")?.asString else null
                    val textToType = json.get("text")?.asString ?: ""
                    val success = accessibility?.typeIntoField(targetField, textToType) == true
                    if (success) {
                        tts.speak(reply)
                    } else {
                        tts.speak("Text enter nahi ho paya, Sir.")
                    }
                }
                "scroll" -> {
                    val dir = json.get("direction")?.asString ?: "down"
                    if (dir == "up") accessibility?.scrollUp() else accessibility?.scrollDown()
                    tts.speak(reply)
                }
                "call" -> {
                    val name = json.get("name")?.asString ?: ""
                    callManager.findContactAndCall(name)
                }
                "answer_call" -> {
                    callManager.answerCall()
                    tts.speak(reply)
                }
                "end_call" -> {
                    callManager.endCall()
                    tts.speak(reply)
                }
                "reply_notification" -> {
                    val text = json.get("text")?.asString ?: ""
                    val ok = JarvisNotificationListenerService.instance?.replyToLatestMessage(text) == true
                    if (ok) {
                        tts.speak(reply)
                    } else {
                        tts.speak("Sir, reply karne ke liye koi active message nahi mila.")
                    }
                }
                "open_app" -> {
                    val appName = json.get("app_name")?.asString ?: ""
                    val ok = deviceControl.openAppByName(appName)
                    if (ok) tts.speak(reply) else tts.speak("$appName app nahi mila, Sir.")
                }
                "torch" -> {
                    val state = json.get("state")?.asBoolean ?: true
                    deviceControl.toggleTorch(state)
                    tts.speak(reply)
                }
                "volume" -> {
                    val percent = json.get("percent")?.asInt ?: 50
                    deviceControl.setVolume(percent = percent)
                    tts.speak(reply)
                }
                else -> {
                    tts.speak(reply)
                }
            }
            reply
        } catch (e: Exception) {
            Log.e(TAG, "Action execution error", e)
            tts.speak("Command execute karne me error aaya, Sir.")
            "Error executing action"
        }
    }

    private fun handleAutonomousFallback(userInput: String, assistantName: String, screenState: ScreenState?): String {
        val lower = userInput.lowercase()
        val tts = AndroidTTSManager.getInstance(context)
        val accessibility = JarvisAccessibilityService.instance

        return when {
            lower.contains("setting") && (lower.contains("kholo") || lower.contains("open") || lower.contains("dekho")) -> {
                val intent = Intent(Settings.ACTION_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                context.startActivity(intent)
                val reply = "Settings open kar di gayi hai, Sir. Kahiye kya badalna hai?"
                tts.speak(reply)
                reply
            }
            lower.contains("revert") || lower.contains("pehle jaisa") || lower.contains("undo") -> {
                val revertMsg = accessibility?.revertLastAction() ?: "Revert ke liye koi action nahi hai."
                tts.speak(revertMsg)
                revertMsg
            }
            lower.contains("scroll down") || lower.contains("niche jao") -> {
                accessibility?.scrollDown()
                val reply = "Niche scroll kiya, Sir."
                tts.speak(reply)
                reply
            }
            lower.contains("scroll up") || lower.contains("upar jao") -> {
                accessibility?.scrollUp()
                val reply = "Upar scroll kiya, Sir."
                tts.speak(reply)
                reply
            }
            lower.contains("call") || lower.contains("phone") -> {
                val name = userInput.replace(Regex("(?i)(call|phone|lagao|karo|ko|to)"), "").trim()
                if (name.isNotBlank()) {
                    callManager.findContactAndCall(name)
                    "Calling $name..."
                } else {
                    tts.speak("Kise call lagana hai, Sir?")
                    "Kise call lagana hai?"
                }
            }
            lower.contains("uthao") || lower.contains("answer") -> {
                callManager.answerCall()
                tts.speak("Call connect kar diya hai, Sir.")
                "Call answered"
            }
            lower.contains("cut") || lower.contains("reject") -> {
                callManager.endCall()
                tts.speak("Call disconnect kar diya hai, Sir.")
                "Call ended"
            }
            lower.contains("torch on") -> {
                deviceControl.toggleTorch(true)
                tts.speak("Flashlight on ho gayi hai, Sir.")
                "Flashlight ON"
            }
            lower.contains("torch off") -> {
                deviceControl.toggleTorch(false)
                tts.speak("Flashlight off ho gayi hai, Sir.")
                "Flashlight OFF"
            }
            lower.contains("open") || lower.contains("kholo") || lower.contains("chalao") -> {
                val app = userInput.replace(Regex("(?i)(open|kholo|chalao|app)"), "").trim()
                deviceControl.openAppByName(app)
                tts.speak("$app khol diya hai, Sir.")
                "Opening $app"
            }
            else -> {
                // Check if the user is trying to click something visible on screen
                if (screenState != null) {
                    val match = screenState.clickableOptions.firstOrNull { lower.contains(it.lowercase()) }
                    if (match != null) {
                        accessibility?.clickElementByText(match)
                        val reply = "$match par click kar diya gaya hai, Sir."
                        tts.speak(reply)
                        return reply
                    }
                }
                val reply = "Ji Sir, main $assistantName hu. Boliye main kya control karu?"
                tts.speak(reply)
                reply
            }
        }
    }
}
