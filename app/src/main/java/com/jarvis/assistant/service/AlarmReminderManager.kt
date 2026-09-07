package com.jarvis.assistant.service

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.util.Log

class AlarmReminderManager(private val context: Context) {

    companion object {
        private const val TAG = "JarvisAlarmManager"
    }

    fun setAlarm(hour: Int, minute: Int, label: String = "Jarvis Alarm"): Boolean {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.d(TAG, "Alarm set for $hour:$minute with label '$label'")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set alarm", e)
            false
        }
    }

    fun setTimer(seconds: Int, label: String = "Jarvis Timer"): Boolean {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.d(TAG, "Timer set for $seconds seconds with label '$label'")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set timer", e)
            false
        }
    }

    fun setReminder(title: String, beginTimeMillis: Long): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.EXTRA_CUSTOM_APP_URI, "jarvis://reminder")
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginTimeMillis)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.d(TAG, "Reminder set: $title at $beginTimeMillis")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set reminder", e)
            false
        }
    }
}
