package com.jarvis.assistant.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import com.jarvis.assistant.voice.AndroidTTSManager

class CallManager(private val context: Context) {

    companion object {
        private const val TAG = "JarvisCallManager"
    }

    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    fun registerCallListener() {
        val hasPhonePermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_PHONE_STATE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasPhonePermission) {
            Log.w(TAG, "READ_PHONE_STATE permission not granted. Skipping call listener registration.")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyManager?.registerTelephonyCallback(
                    androidx.core.content.ContextCompat.getMainExecutor(context),
                    object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                        override fun onCallStateChanged(state: Int) {
                            handleCallState(state, null)
                        }
                    }
                )
            } else {
                @Suppress("DEPRECATION")
                telephonyManager?.listen(object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleCallState(state, phoneNumber)
                    }
                }, PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering call listener", e)
        }
    }

    private fun handleCallState(state: Int, number: String?) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                val callerName = if (!number.isNullOrBlank()) getContactNameFromNumber(number) ?: number else "Unknown"
                Log.d(TAG, "Incoming Call from: $callerName")
                val tts = AndroidTTSManager.getInstance(context)
                tts.speak("Incoming call from $callerName. Bolie, uthana hai ya cut karna hai?")
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                Log.d(TAG, "Call Idle")
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                Log.d(TAG, "Call Connected")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun answerCall(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && telecomManager != null) {
                telecomManager.acceptRingingCall()
                Log.d(TAG, "Call answered successfully")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error answering call", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun endCall(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && telecomManager != null) {
                telecomManager.endCall()
                Log.d(TAG, "Call ended successfully")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error ending call", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun makeCall(phoneNumber: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.d(TAG, "Initiated call to $phoneNumber")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error making call", e)
            false
        }
    }

    fun findContactAndCall(name: String): Boolean {
        val number = getPhoneNumberByName(name)
        if (number != null) {
            AndroidTTSManager.getInstance(context).speak("$name ko phone lagaya ja raha hai...")
            return makeCall(number)
        } else {
            AndroidTTSManager.getInstance(context).speak("Contacts me $name ka number nahi mila.")
            return false
        }
    }

    fun getPhoneNumberByName(targetName: String): String? {
        val clean = targetName.trim().lowercase()
            .replace(Regex("(?i)\\b(bhai|ji|sir|ko|to|call|phone)\\b"), " ")
            .trim()
        if (clean.isBlank()) return null

        val resolver = context.contentResolver

        // 1. Try exact or like query on cleaned name
        val candidates = listOf(clean) + clean.split(" ").filter { it.length >= 2 }
        for (candidate in candidates) {
            val cursor: Cursor? = resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$candidate%"),
                null
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (numberIndex != -1) {
                        val num = it.getString(numberIndex)
                        if (!num.isNullOrBlank()) {
                            return num
                        }
                    }
                }
            }
        }
        return null
    }

    private fun getContactNameFromNumber(phoneNumber: String): String? {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null,
            null,
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                if (nameIndex != -1) return it.getString(nameIndex)
            }
        }
        return null
    }
}
