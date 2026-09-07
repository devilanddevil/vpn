package com.jarvis.assistant.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.jarvis.assistant.voice.AndroidTTSManager
import java.net.URLEncoder

class WhatsAppManager(private val context: Context) {

    companion object {
        private const val TAG = "JarvisWhatsAppManager"
    }

    private val callManager = CallManager(context)

    fun sendWhatsAppMessage(contactName: String, messageText: String): Boolean {
        val tts = AndroidTTSManager.getInstance(context)
        val cleanContact = contactName.trim()
            .replace(Regex("(?i)\\b(ko|to|message|bhejo|bolo|par|whatsapp)\\b"), " ")
            .trim()

        if (cleanContact.isBlank() && messageText.isBlank()) {
            tts.speak("Kise aur kya message bhejna hai, Sir?")
            return false
        }

        if (messageText.isBlank()) {
            tts.speak("$cleanContact ko kya message bhejna hai, Sir?")
            return false
        }

        // 1. Search Phone Contacts for Contact's Phone Number
        val rawNumber = callManager.getPhoneNumberByName(cleanContact)
        var launched = false

        if (!rawNumber.isNullOrBlank()) {
            // Strip any non-numeric characters
            val digitsOnly = rawNumber.replace(Regex("[^0-9]"), "")
            // Ensure India country code 91 if standard 10 digit number
            val finalNumber = if (digitsOnly.length == 10) "91$digitsOnly" else digitsOnly

            try {
                val encodedText = URLEncoder.encode(messageText, "UTF-8")
                val whatsappUrl = "https://api.whatsapp.com/send?phone=$finalNumber&text=$encodedText"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(whatsappUrl)).apply {
                    setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                launched = true
                Log.d(TAG, "Opened WhatsApp direct chat with $cleanContact ($finalNumber)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch direct whatsapp URL, falling back", e)
            }
        }

        // 2. Fallback to ACTION_SEND if phone number wasn't found or URL launch failed
        if (!launched) {
            try {
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, messageText)
                    setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(sendIntent)
                launched = true
                Log.d(TAG, "Launched WhatsApp ACTION_SEND intent with message: '$messageText'")
            } catch (e: Exception) {
                Log.e(TAG, "Error launching WhatsApp intent", e)
                tts.speak("WhatsApp kholne me samasya aayi, Sir.")
                return false
            }
        }

        // 3. Trigger Accessibility Service for Lock Detection and Auto-Send
        val accessibility = JarvisAccessibilityService.instance
        if (accessibility != null) {
            accessibility.startWhatsAppAutoSend(cleanContact, messageText)
        } else {
            tts.speak("$cleanContact ke liye WhatsApp open kar diya hai, Sir.")
        }

        return true
    }
}
