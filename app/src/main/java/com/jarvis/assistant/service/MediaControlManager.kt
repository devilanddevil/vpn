package com.jarvis.assistant.service

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent

class MediaControlManager(private val context: Context) {

    companion object {
        private const val TAG = "JarvisMediaControl"
        private const val SPOTIFY_PACKAGE = "com.spotify.music"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun playPause(): Boolean {
        return sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    }

    fun play(): Boolean {
        return sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    fun pause(): Boolean {
        return sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PAUSE)
    }

    fun next(): Boolean {
        return sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun previous(): Boolean {
        return sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    private fun sendMediaKeyEvent(keyCode: Int): Boolean {
        return try {
            val eventTime = SystemClock.uptimeMillis()
            val downEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
            val upEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)

            audioManager?.dispatchMediaKeyEvent(downEvent)
            audioManager?.dispatchMediaKeyEvent(upEvent)
            Log.d(TAG, "Dispatched media key code: $keyCode")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching media key $keyCode", e)
            false
        }
    }

    fun playOnSpotify(query: String): Boolean {
        return try {
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                setPackage(SPOTIFY_PACKAGE)
                putExtra(SearchManager.QUERY, query)
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            }
            if (isPackageInstalled(SPOTIFY_PACKAGE)) {
                context.startActivity(intent)
                true
            } else {
                val webIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://open.spotify.com/search/${Uri.encode(query)}")
                ).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(webIntent)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing on Spotify", e)
            false
        }
    }

    fun playOnYouTube(query: String): Boolean {
        return try {
            val intent = if (isPackageInstalled(YOUTUBE_PACKAGE)) {
                Intent(Intent.ACTION_SEARCH).apply {
                    setPackage(YOUTUBE_PACKAGE)
                    putExtra("query", query)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } else {
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
                ).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error playing on YouTube", e)
            false
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }
}
