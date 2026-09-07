package com.jarvis.assistant.service

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.ImageView
import com.jarvis.assistant.R
import com.jarvis.assistant.ai.GeminiAgent
import com.jarvis.assistant.voice.AndroidTTSManager
import com.jarvis.assistant.voice.VoiceRecognitionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class JarvisOverlayService : Service() {

    companion object {
        private const val TAG = "JarvisOverlayService"
        var isRunning = false
            private set

        fun start(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                Log.w(TAG, "Cannot start overlay service: permission denied")
                return
            }
            val intent = Intent(context, JarvisOverlayService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, JarvisOverlayService::class.java)
            context.stopService(intent)
        }
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private lateinit var geminiAgent: GeminiAgent
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var glowAnimator: ObjectAnimator? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        geminiAgent = GeminiAgent(this)
        createFloatingWidget()
        Log.d(TAG, "Jarvis Overlay Service Created")
    }

    private fun createFloatingWidget() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        floatingView = inflater.inflate(R.layout.layout_floating_arc_reactor, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 200
        }

        setupTouchListener()
        windowManager?.addView(floatingView, params)
    }

    private fun setupTouchListener() {
        val view = floatingView ?: return
        val reactorIcon = view.findViewById<ImageView>(R.id.ivFloatingReactor)
        val glowRing = view.findViewById<View>(R.id.viewGlowRing)

        view.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isClick = false

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                val layoutParams = params ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isClick = false
                        }
                        layoutParams.x = initialX + dx
                        layoutParams.y = initialY + dy
                        windowManager?.updateViewLayout(floatingView, layoutParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            onArcReactorTapped(glowRing)
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun onArcReactorTapped(glowRing: View) {
        startPulse(glowRing)
        val engine = com.jarvis.assistant.voice.JarvisWakeWordEngine.getInstance(this)
        engine.onCommandExecuted = {
            mainHandler.post { stopPulse() }
        }
        engine.triggerManualListening()
    }

    private fun startPulse(view: View) {
        glowAnimator?.cancel()
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.4f, 1.0f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.4f, 1.0f)
        val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.4f, 1.0f, 0.4f)
        glowAnimator = ObjectAnimator.ofPropertyValuesHolder(view, scaleX, scaleY, alpha).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopPulse() {
        glowAnimator?.cancel()
        floatingView?.findViewById<View>(R.id.viewGlowRing)?.apply {
            scaleX = 1.0f
            scaleY = 1.0f
            alpha = 0.5f
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopPulse()
        if (floatingView != null && windowManager != null) {
            windowManager?.removeView(floatingView)
        }
        Log.d(TAG, "Jarvis Overlay Service Destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
