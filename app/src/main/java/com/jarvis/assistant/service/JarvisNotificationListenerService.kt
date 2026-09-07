package com.jarvis.assistant.service

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.jarvis.assistant.voice.AndroidTTSManager

data class CapturedMessage(
    val key: String,
    val packageName: String,
    val appName: String,
    val sender: String,
    val text: String,
    val timestamp: Long,
    val replyAction: Notification.Action?
)

class JarvisNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "JarvisNotification"
        var instance: JarvisNotificationListenerService? = null
            private set
        
        var latestMessage: CapturedMessage? = null
            private set

        val recentMessages = mutableListOf<CapturedMessage>()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d(TAG, "Jarvis Notification Listener Connected Successfully!")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName
        // Ignore system notifications or our own
        if (packageName == applicationContext.packageName) return
        if (sbn.isOngoing) return // ignore ongoing downloads/media playback

        val extras = sbn.notification.extras ?: return
        val title = extras.getString(Notification.EXTRA_TITLE) ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getString(Notification.EXTRA_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        if (title.isBlank() && text.isBlank()) return

        val appName = getAppNameFromPackage(packageName)
        val replyAction = findReplyAction(sbn.notification)

        val captured = CapturedMessage(
            key = sbn.key,
            packageName = packageName,
            appName = appName,
            sender = title,
            text = text,
            timestamp = sbn.postTime,
            replyAction = replyAction
        )

        latestMessage = captured
        synchronized(recentMessages) {
            recentMessages.add(0, captured)
            if (recentMessages.size > 50) {
                recentMessages.removeAt(recentMessages.size - 1)
            }
        }

        Log.d(TAG, "Captured Notification from $appName: $title -> $text (Replyable: ${replyAction != null})")

        // Announce important incoming chat/SMS notifications aloud if not muted
        if (isMessagingApp(packageName)) {
            val announcement = "$appName par $title ne message bheja hai: $text"
            AndroidTTSManager.getInstance(applicationContext).speak(announcement)
        }
    }

    private fun findReplyAction(notification: Notification): Notification.Action? {
        val actions = notification.actions ?: return null
        for (action in actions) {
            val remoteInputs = action.remoteInputs
            if (remoteInputs != null && remoteInputs.isNotEmpty()) {
                return action
            }
        }
        return null
    }

    fun replyToLatestMessage(replyText: String): Boolean {
        val msg = latestMessage ?: return false
        val action = msg.replyAction ?: return false
        return sendDirectReply(action, replyText)
    }

    fun replyToMessageByKey(notificationKey: String, replyText: String): Boolean {
        val msg = recentMessages.find { it.key == notificationKey } ?: return false
        val action = msg.replyAction ?: return false
        return sendDirectReply(action, replyText)
    }

    private fun sendDirectReply(action: Notification.Action, replyText: String): Boolean {
        return try {
            val remoteInputs = action.remoteInputs ?: return false
            val bundle = Bundle()
            for (input in remoteInputs) {
                bundle.putCharSequence(input.resultKey, replyText)
            }
            val intent = Intent()
            RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
            action.actionIntent.send(this, 0, intent)
            Log.d(TAG, "Successfully sent direct reply: '$replyText'")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send direct reply", e)
            false
        }
    }

    private fun isMessagingApp(pkg: String): Boolean {
        val list = listOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", 
                          "com.instagram.android", "com.google.android.apps.messaging", "com.facebook.orca")
        return list.any { pkg.contains(it, ignoreCase = true) }
    }

    private fun getAppNameFromPackage(pkg: String): String {
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            pkg.substringAfterLast(".")
        }
    }
}
