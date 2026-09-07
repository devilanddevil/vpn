package com.jarvis.assistant.ai

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.service.*
import com.jarvis.assistant.ui.JarvisVisionActivity
import com.jarvis.assistant.voice.AndroidTTSManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class ChatMemoryTurn(val role: String, val message: String)

class GeminiAgent(private val context: Context) {

    companion object {
        private const val TAG = "JarvisGeminiAgent"
        private const val GEMINI_PRIMARY_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"
        private const val GEMINI_FALLBACK_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash-preview:generateContent"

        // Conversational Memory for Human-like Multi-Turn dialogue
        private val conversationHistory = mutableListOf<ChatMemoryTurn>()
        @Volatile
        var pendingWhatsAppContact: String? = null

        fun addMemory(role: String, text: String) {
            conversationHistory.add(ChatMemoryTurn(role, text))
            if (conversationHistory.size > 16) {
                conversationHistory.removeAt(0)
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val callManager = CallManager(context)
    private val deviceControl = DeviceControlManager(context)
    private val mediaControl = MediaControlManager(context)
    private val alarmManager = AlarmReminderManager(context)
    private val whatsAppManager = WhatsAppManager(context)

    suspend fun processUserCommand(userInput: String): String = withContext(Dispatchers.IO) {
        val apiKey = JarvisApplication.instance.getGeminiApiKey()
        val assistantName = JarvisApplication.instance.getAssistantName()
        val trimmed = userInput.trim()
        val lower = trimmed.lowercase()

        // 0. Contextual Multi-Turn WhatsApp Interception (if user is replying to "Rahul ko kya message bhejna hai?")
        if (!pendingWhatsAppContact.isNullOrBlank()) {
            val contact = pendingWhatsAppContact!!
            pendingWhatsAppContact = null
            val cleanMessage = userInput
                .replace(Regex("(?i)\\b(bolo|ki|likho|ye message|bhejo|bhej do)\\b"), " ")
                .trim()
            val finalMsg = if (cleanMessage.isNotBlank()) cleanMessage else userInput
            val reply = "Ji Sir, $contact ko WhatsApp message bhej raha hu: '$finalMsg'"
            AndroidTTSManager.getInstance(context).speak(reply)
            whatsAppManager.sendWhatsAppMessage(contact, finalMsg)
            addMemory("user", userInput)
            addMemory("assistant", reply)
            return@withContext reply
        }

        // 1. Fast-path for Wake words and Greetings
        val greetingPatterns = listOf(
            "hello", "hey", "hi", "jarvis", "hey jarvis", "hello jarvis", "hi jarvis",
            "ok jarvis", "oye jarvis", "sun jarvis", "suno jarvis", "जार्विस", "हे जार्विस",
            "हेलो जार्विस", "नमस्ते", "नमस्ते जार्विस"
        )
        if (lower in greetingPatterns || lower == assistantName.lowercase() || lower == "hey $assistantName".lowercase() || lower == "hello $assistantName".lowercase()) {
            val reply = "Yes Sir! Main sun raha hu, bataiye kya madad kar sakta hu?"
            AndroidTTSManager.getInstance(context).speak(reply)
            addMemory("user", userInput)
            addMemory("assistant", reply)
            return@withContext reply
        }

        // 2. Direct fast-path for instant Revert command
        val accessibility = JarvisAccessibilityService.instance
        if (lower.contains("revert") || lower.contains("pehle jaisa") || lower.contains("undo") || lower.contains("wapas karo")) {
            val revertMsg = accessibility?.revertLastAction() ?: "Revert service available nahi hai, Sir."
            AndroidTTSManager.getInstance(context).speak(revertMsg)
            addMemory("user", userInput)
            addMemory("assistant", revertMsg)
            return@withContext revertMsg
        }

        // 3. Capture live screen context from Accessibility Service
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

        // Build conversation memory context
        val memoryContext = if (conversationHistory.isNotEmpty()) {
            val formatted = conversationHistory.takeLast(6).joinToString("\n") {
                "${if (it.role == "user") "User" else "Jarvis"}: ${it.message}"
            }
            """
            [RECENT CONVERSATION HISTORY - MAINTAIN THIS CONTEXT & REMEMBER MULTI-TURN THREADS]
            $formatted
            """.trimIndent()
        } else {
            "[RECENT CONVERSATION HISTORY]: None (First Turn)"
        }

        // 4. Fallback if no API key
        if (apiKey.isBlank()) {
            return@withContext handleAutonomousFallback(userInput, assistantName, screenState)
        }

        try {
            val systemPrompt = """
                You are J.A.R.V.I.S., the hyper-intelligent, sophisticated, loyal and witty AI companion inspired by Iron Man, with FULL AUTONOMOUS CONTROL over the user's Android smartphone.
                You are NOT a basic voice assistant or command line tool. You think and speak like a real, charismatic, warm human personal assistant.
                You speak fluent Hindi, Hinglish, and English naturally, using polite respectful terms like 'Sir' or 'Boss'.
                
                $memoryContext

                $screenContextSummary

                CRITICAL CONVERSATION & MEMORY RULES:
                1. REMEMBER MULTI-TURN CONTEXT: If you asked the user a question in the previous turn (like asking what message to send or who to call), connect the user's current response directly to that task!
                2. WHATSAPP MESSAGING:
                   - If user asks to message someone but didn't provide message text (e.g. 'Rahul ko WhatsApp karo' or 'Rahul ko message bhejo'):
                     {"action": "ask_user", "target": "Rahul", "context": "whatsapp_contact", "reply": "Rahul ko kya message bhejna hai, Sir?"}
                   - If user says both contact and message (e.g. 'Rahul ko WhatsApp par message bhejo ki mai 10 minute me aa raha hu'):
                     {"action": "send_whatsapp", "contact": "Rahul", "message": "Mai 10 minute me aa raha hu", "reply": "Rahul ko message bhej raha hu: 'Mai 10 minute me aa raha hu', Sir."}
                   - If user says 'WhatsApp par message bhejo' without contact or message:
                     {"action": "ask_user", "reply": "Kise aur kya message bhejna hai, Sir?"}
                3. ALWAYS RETURN ONLY A SINGLE JSON OBJECT (no markdown fences, no backticks).

                Available Actions:
                1. {"action": "send_whatsapp", "contact": "<name>", "message": "<exact text>", "reply": "<human confirmation in Hindi/English>"}
                2. {"action": "ask_user", "reply": "<conversational question>", "target": "<contact or null>", "context": "<task context>"}
                3. {"action": "open_app", "app_name": "<name>", "reply": "Opening <app>..."}
                4. {"action": "call", "name": "<contact>", "reply": "<confirmation>"}
                5. {"action": "torch", "state": true/false, "reply": "<confirmation>"}
                6. {"action": "volume", "percent": 0-100, "reply": "<confirmation>"}
                7. {"action": "play_media", "platform": "spotify|youtube|default", "query": "<query>", "reply": "<confirmation>"}
                8. {"action": "media_command", "command": "play|pause|next|previous", "reply": "<confirmation>"}
                9. {"action": "set_alarm", "hour": 0-23, "minute": 0-59, "label": "<label>", "reply": "<confirmation>"}
                10. {"action": "set_timer", "seconds": <int>, "label": "<label>", "reply": "<confirmation>"}
                11. {"action": "battery_status", "reply": "<confirmation>"}
                12. {"action": "vision_scan", "question": "<question>", "reply": "<confirmation>"}
                13. {"action": "open_settings", "sub_setting": "display|sound|wifi|bluetooth|main", "reply": "<confirmation>"}
                14. {"action": "read_screen", "reply": "<describe visible options in Hindi/English>"}
                15. {"action": "scroll", "direction": "down|up", "reply": "<confirmation>"}
                16. {"action": "speak", "reply": "<witty, intelligent, knowledgeable, human-like answer in Hindi/English>"}
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

                // Enforce JSON format output from Gemini
                val genConfig = JsonObject().apply {
                    addProperty("response_mime_type", "application/json")
                }
                add("generationConfig", genConfig)
            }

            var responseText: String? = null
            for (endpoint in listOf(GEMINI_PRIMARY_URL, GEMINI_FALLBACK_URL)) {
                try {
                    val request = Request.Builder()
                        .url("$endpoint?key=$apiKey")
                        .post(requestBodyJson.toString().toRequestBody("application/json".toMediaType()))
                        .build()

                    val response = client.newCall(request).execute()
                    val responseBody = response.body?.string() ?: ""

                    if (response.isSuccessful) {
                        val parsedJson = gson.fromJson(responseBody, JsonObject::class.java)
                        val candidates = parsedJson.getAsJsonArray("candidates")
                        if (candidates != null && candidates.size() > 0) {
                            responseText = candidates[0].asJsonObject
                                .getAsJsonObject("content")
                                .getAsJsonArray("parts")[0].asJsonObject
                                .get("text")?.asString?.trim()
                            if (!responseText.isNullOrBlank()) break
                        }
                    } else {
                        Log.w(TAG, "Gemini $endpoint returned ${response.code}: $responseBody")
                    }
                } catch (endpointEx: Exception) {
                    Log.w(TAG, "Failed call to $endpoint", endpointEx)
                }
            }

            if (!responseText.isNullOrBlank()) {
                val cleanJson = responseText.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                return@withContext executeAutonomousAction(cleanJson, assistantName, userInput)
            }

            return@withContext handleAutonomousFallback(userInput, assistantName, screenState)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini call exception", e)
            return@withContext handleAutonomousFallback(userInput, assistantName, screenState)
        }
    }

    private fun executeAutonomousAction(jsonString: String, assistantName: String, originalUserInput: String): String {
        val tts = AndroidTTSManager.getInstance(context)
        val accessibility = JarvisAccessibilityService.instance

        return try {
            val json = gson.fromJson(jsonString, JsonObject::class.java)
            val action = json.get("action")?.asString ?: "speak"
            val reply = json.get("reply")?.asString ?: "Ji Sir, samajh gaya."

            when (action) {
                "send_whatsapp" -> {
                    val contact = json.get("contact")?.asString ?: ""
                    val message = json.get("message")?.asString ?: ""
                    if (contact.isNotBlank() && message.isNotBlank()) {
                        pendingWhatsAppContact = null
                        whatsAppManager.sendWhatsAppMessage(contact, message)
                        tts.speak(reply)
                    } else if (contact.isNotBlank()) {
                        pendingWhatsAppContact = contact
                        val askMsg = "$contact ko kya message bhejna hai, Sir?"
                        tts.speak(askMsg)
                        addMemory("user", originalUserInput)
                        addMemory("assistant", askMsg)
                        return askMsg
                    } else {
                        val askMsg = "Kise aur kya message bhejna hai, Sir?"
                        tts.speak(askMsg)
                        addMemory("user", originalUserInput)
                        addMemory("assistant", askMsg)
                        return askMsg
                    }
                }
                "ask_user" -> {
                    if (json.has("target")) {
                        pendingWhatsAppContact = json.get("target")?.asString
                    }
                    tts.speak(reply)
                }
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
                        tts.speak("Screen par $target switch nahi mila, Sir.")
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
                "play_media" -> {
                    val platform = json.get("platform")?.asString ?: "default"
                    val query = json.get("query")?.asString ?: ""
                    when (platform.lowercase()) {
                        "spotify" -> mediaControl.playOnSpotify(query)
                        "youtube" -> mediaControl.playOnYouTube(query)
                        else -> {
                            if (!mediaControl.playOnSpotify(query)) {
                                mediaControl.playOnYouTube(query)
                            }
                        }
                    }
                    tts.speak(reply)
                }
                "media_command" -> {
                    when (json.get("command")?.asString?.lowercase()) {
                        "play" -> mediaControl.play()
                        "pause" -> mediaControl.pause()
                        "next" -> mediaControl.next()
                        "previous" -> mediaControl.previous()
                        else -> mediaControl.playPause()
                    }
                    tts.speak(reply)
                }
                "set_alarm" -> {
                    val hour = json.get("hour")?.asInt ?: 6
                    val minute = json.get("minute")?.asInt ?: 0
                    val label = json.get("label")?.asString ?: "Jarvis Alarm"
                    alarmManager.setAlarm(hour, minute, label)
                    tts.speak(reply)
                }
                "set_timer" -> {
                    val seconds = json.get("seconds")?.asInt ?: 300
                    val label = json.get("label")?.asString ?: "Jarvis Timer"
                    alarmManager.setTimer(seconds, label)
                    tts.speak(reply)
                }
                "battery_status" -> {
                    val report = deviceControl.getBatteryReport()
                    tts.speak(report)
                }
                "vision_scan" -> {
                    val question = json.get("question")?.asString ?: "Is image ko describe karo"
                    val intent = Intent(context, JarvisVisionActivity::class.java).apply {
                        putExtra(JarvisVisionActivity.EXTRA_QUESTION, question)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    tts.speak(reply)
                }
                else -> {
                    tts.speak(reply)
                }
            }

            addMemory("user", originalUserInput)
            addMemory("assistant", reply)
            reply
        } catch (e: Exception) {
            Log.e(TAG, "Action execution error", e)
            val errMsg = "Command execute karne me error aaya, Sir."
            tts.speak(errMsg)
            errMsg
        }
    }

    private fun handleAutonomousFallback(userInput: String, assistantName: String, screenState: ScreenState?): String {
        val lower = userInput.lowercase()
        val tts = AndroidTTSManager.getInstance(context)
        val accessibility = JarvisAccessibilityService.instance

        // 1. Check for multi-turn pending WhatsApp reply
        if (!pendingWhatsAppContact.isNullOrBlank()) {
            val contact = pendingWhatsAppContact!!
            pendingWhatsAppContact = null
            val cleanMessage = userInput
                .replace(Regex("(?i)\\b(bolo|ki|likho|ye message|bhejo|bhej do)\\b"), " ")
                .trim()
            val finalMsg = if (cleanMessage.isNotBlank()) cleanMessage else userInput
            val reply = "Ji Sir, $contact ko WhatsApp message bhej raha hu: '$finalMsg'"
            tts.speak(reply)
            whatsAppManager.sendWhatsAppMessage(contact, finalMsg)
            addMemory("user", userInput)
            addMemory("assistant", reply)
            return reply
        }

        // 2. WhatsApp Messaging Intent
        val isMessageIntent = lower.contains("message") || lower.contains("msg") ||
                lower.contains("bhejo") || lower.contains("bhej do") ||
                lower.contains("bolo") || lower.contains("likho")

        if (isMessageIntent && (lower.contains("whatsapp") || lower.contains("व्हाट्सएप") || lower.contains("wa"))) {
            // Pattern 1: Contact + message with 'ki' or ':' (e.g. "Rahul ko WhatsApp par message bhejo ki mai aa raha hu")
            val pattern1 = Regex("(?i)([a-zA-Z\\u0900-\\u097F]+)\\s+ko\\s+.*?(?:ki|:)\\s*(.+)")
            val match1 = pattern1.find(userInput)
            if (match1 != null && match1.groupValues.size > 2) {
                val contact = match1.groupValues[1].replace(Regex("(?i)\\b(whatsapp|par|ko|message)\\b"), "").trim()
                val msg = match1.groupValues[2].trim()
                whatsAppManager.sendWhatsAppMessage(contact, msg)
                val reply = "$contact ko WhatsApp message bhej raha hu: '$msg', Sir."
                tts.speak(reply)
                addMemory("user", userInput)
                addMemory("assistant", reply)
                return reply
            }

            // Pattern 2: Contact specified without message ("Rahul ko WhatsApp message bhejo")
            val pattern2 = Regex("(?i)([a-zA-Z\\u0900-\\u097F]+)\\s+ko")
            val match2 = pattern2.find(userInput)
            if (match2 != null) {
                val contact = match2.groupValues[1].replace(Regex("(?i)\\b(whatsapp|par|ko|message)\\b"), "").trim()
                if (contact.isNotBlank()) {
                    pendingWhatsAppContact = contact
                    val reply = "$contact ko kya message bhejna hai, Sir?"
                    tts.speak(reply)
                    addMemory("user", userInput)
                    addMemory("assistant", reply)
                    return reply
                }
            }

            val reply = "Kise aur kya message bhejna hai, Sir?"
            tts.speak(reply)
            addMemory("user", userInput)
            addMemory("assistant", reply)
            return reply
        }

        return when {
            // Battery Status
            lower.contains("battery") || lower.contains("charge") -> {
                val report = deviceControl.getBatteryReport()
                tts.speak(report)
                addMemory("user", userInput)
                addMemory("assistant", report)
                report
            }
            // Vision AI
            lower.contains("dekho") || lower.contains("photo") || lower.contains("camera") || lower.contains("kya likha") || lower.contains("samne kya") -> {
                val intent = Intent(context, JarvisVisionActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                val reply = "Optical camera activate kar raha hu, Sir."
                tts.speak(reply)
                addMemory("user", userInput)
                addMemory("assistant", reply)
                reply
            }
            // Media Control
            lower.contains("spotify") -> {
                val query = userInput.replace(Regex("(?i)(spotify|par|gaane|chalao|play|song|on)"), "").trim()
                mediaControl.playOnSpotify(query)
                val reply = "Spotify par $query chala raha hu, Sir."
                tts.speak(reply)
                addMemory("user", userInput)
                addMemory("assistant", reply)
                reply
            }
            lower.contains("youtube") -> {
                val query = userInput.replace(Regex("(?i)(youtube|par|chalao|play|video|on)"), "").trim()
                mediaControl.playOnYouTube(query)
                val reply = "YouTube par $query play kar raha hu, Sir."
                tts.speak(reply)
                addMemory("user", userInput)
                addMemory("assistant", reply)
                reply
            }
            lower.contains("pause") || lower.contains("rok do") || lower.contains("roko") -> {
                mediaControl.pause()
                val reply = "Music pause kar diya gaya hai, Sir."
                tts.speak(reply)
                addMemory("user", userInput)
                addMemory("assistant", reply)
                reply
            }
            lower.contains("next song") || lower.contains("agla gaana") || lower.contains("change karo") -> {
                mediaControl.next()
                val reply = "Agla gaana chala diya hai, Sir."
                tts.speak(reply)
                addMemory("user", userInput)
                addMemory("assistant", reply)
                reply
            }
            // Alarms & Timers
            lower.contains("alarm") -> {
                alarmManager.setAlarm(6, 0, "Jarvis Alarm")
                val reply = "Alarm set kar diya hai, Sir."
                tts.speak(reply)
                addMemory("user", userInput)
                addMemory("assistant", reply)
                reply
            }
            lower.contains("timer") -> {
                alarmManager.setTimer(300, "Jarvis Timer")
                val reply = "5 minute ka timer shuru kar diya hai, Sir."
                tts.speak(reply)
                addMemory("user", userInput)
                addMemory("assistant", reply)
                reply
            }
            // Settings
            lower.contains("setting") && (lower.contains("kholo") || lower.contains("open") || lower.contains("dekho")) -> {
                val intent = Intent(Settings.ACTION_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                context.startActivity(intent)
                val reply = "Settings open kar di gayi hai, Sir."
                tts.speak(reply)
                addMemory("user", userInput)
                addMemory("assistant", reply)
                reply
            }
            lower.contains("revert") || lower.contains("pehle jaisa") || lower.contains("undo") -> {
                val revertMsg = accessibility?.revertLastAction() ?: "Revert ke liye koi action nahi hai."
                tts.speak(revertMsg)
                addMemory("user", userInput)
                addMemory("assistant", revertMsg)
                revertMsg
            }
            lower.contains("scroll down") || lower.contains("niche jao") -> {
                accessibility?.scrollDown()
                val reply = "Niche scroll kiya, Sir."
                tts.speak(reply)
                addMemory("user", userInput)
                addMemory("assistant", reply)
                reply
            }
            lower.contains("scroll up") || lower.contains("upar jao") -> {
                accessibility?.scrollUp()
                val reply = "Upar scroll kiya, Sir."
                tts.speak(reply)
                addMemory("user", userInput)
                addMemory("assistant", reply)
                reply
            }
            lower.contains("call") || lower.contains("phone") -> {
                val name = userInput.replace(Regex("(?i)(call|phone|lagao|karo|ko|to)"), "").trim()
                if (name.isNotBlank()) {
                    callManager.findContactAndCall(name)
                    val reply = "Calling $name..."
                    addMemory("user", userInput)
                    addMemory("assistant", reply)
                    reply
                } else {
                    val reply = "Kise call lagana hai, Sir?"
                    tts.speak(reply)
                    addMemory("user", userInput)
                    addMemory("assistant", reply)
                    reply
                }
            }
            lower.contains("uthao") || lower.contains("answer") -> {
                callManager.answerCall()
                val reply = "Call connect kar diya hai, Sir."
                tts.speak(reply)
                addMemory("user", userInput)
                addMemory("assistant", reply)
                reply
            }
            lower.contains("cut") || lower.contains("reject") -> {
                callManager.endCall()
                val reply = "Call disconnect kar diya hai, Sir."
                tts.speak(reply)
                addMemory("user", userInput)
                addMemory("assistant", reply)
                reply
            }
            lower.contains("torch on") -> {
                deviceControl.toggleTorch(true)
                val reply = "Flashlight on ho gayi hai, Sir."
                tts.speak(reply)
                addMemory("user", userInput)
                addMemory("assistant", reply)
                reply
            }
            lower.contains("torch off") -> {
                deviceControl.toggleTorch(false)
                val reply = "Flashlight off ho gayi hai, Sir."
                tts.speak(reply)
                addMemory("user", userInput)
                addMemory("assistant", reply)
                reply
            }
            // App opening fallback: WhatsApp, YouTube, Instagram, etc.
            lower.contains("whatsapp") || lower.contains("व्हाट्सएप") ||
            lower.contains("open") || lower.contains("kholo") || lower.contains("khol") ||
            lower.contains("chalao") || lower.contains("chalu") || lower.contains("start") ||
            lower.contains("launch") || lower.contains("dikhao") -> {
                val candidate = if (lower.contains("whatsapp") || lower.contains("व्हाट्सएप")) {
                    "whatsapp"
                } else {
                    userInput.replace(Regex("(?i)\\b(open|kholo|khol|chalao|chalu|start|launch|dikhao|karo|kar do|kar|do|please|bhai|app|application|ko)\\b"), " ").trim()
                }
                val success = deviceControl.openAppByName(candidate)
                val reply = if (success) {
                    "$candidate open kar diya hai, Sir."
                } else {
                    "$candidate app open nahi ho paya, Sir."
                }
                tts.speak(reply)
                addMemory("user", userInput)
                addMemory("assistant", reply)
                reply
            }
            lower.contains("hello") || lower.contains("hey") || lower.contains("hi") || lower.contains("jarvis") || lower.contains("जार्विस") -> {
                val reply = "Yes Sir, main $assistantName hu. Boliye main kya madad kar sakta hu?"
                tts.speak(reply)
                addMemory("user", userInput)
                addMemory("assistant", reply)
                reply
            }
            else -> {
                if (screenState != null) {
                    val match = screenState.clickableOptions.firstOrNull { lower.contains(it.lowercase()) }
                    if (match != null) {
                        accessibility?.clickElementByText(match)
                        val reply = "$match par click kar diya gaya hai, Sir."
                        tts.speak(reply)
                        addMemory("user", userInput)
                        addMemory("assistant", reply)
                        return reply
                    }
                }
                val reply = "Ji Sir, main $assistantName hu. Boliye main kya madad karu?"
                tts.speak(reply)
                addMemory("user", userInput)
                addMemory("assistant", reply)
                reply
            }
        }
    }

    suspend fun processMultimodalVision(base64Image: String, prompt: String): String = withContext(Dispatchers.IO) {
        val apiKey = JarvisApplication.instance.getGeminiApiKey()
        if (apiKey.isBlank()) {
            return@withContext "Sir, Gemini API Key configure nahi hai. Please main app me API key daalein."
        }

        try {
            val requestBodyJson = JsonObject().apply {
                val contents = com.google.gson.JsonArray()
                val contentObj = JsonObject().apply {
                    val parts = com.google.gson.JsonArray()

                    // Text prompt part
                    parts.add(JsonObject().apply {
                        addProperty("text", "You are Jarvis, an AI vision assistant. Respond concisely in Hindi and Hinglish. $prompt")
                    })

                    // Image part (inline_data)
                    parts.add(JsonObject().apply {
                        val inlineData = JsonObject().apply {
                            addProperty("mime_type", "image/jpeg")
                            addProperty("data", base64Image)
                        }
                        add("inline_data", inlineData)
                    })

                    add("parts", parts)
                }
                contents.add(contentObj)
                add("contents", contents)
            }

            val request = Request.Builder()
                .url("$GEMINI_PRIMARY_URL?key=$apiKey")
                .post(requestBodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Gemini Vision Error: ${response.code} $responseBody")
                return@withContext "Vision analysis me samasya aayi, code: ${response.code}"
            }

            val parsedJson = gson.fromJson(responseBody, JsonObject::class.java)
            val candidates = parsedJson.getAsJsonArray("candidates")
            if (candidates != null && candidates.size() > 0) {
                return@withContext candidates[0].asJsonObject
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts")[0].asJsonObject
                    .get("text").asString.trim()
            }
            "Image me kuch spasht nahi dikh raha hai, Sir."
        } catch (e: Exception) {
            Log.e(TAG, "Gemini Vision exception", e)
            "Vision analysis error: ${e.localizedMessage}"
        }
    }
}
