package com.jarvis.assistant.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.ai.GeminiAgent
import com.jarvis.assistant.databinding.ActivityVisionBinding
import com.jarvis.assistant.voice.AndroidTTSManager
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class JarvisVisionActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "JarvisVisionActivity"
        const val EXTRA_QUESTION = "extra_question"
    }

    private lateinit var binding: ActivityVisionBinding
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var geminiAgent: GeminiAgent
    private var isAnalyzing = false

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVisionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        geminiAgent = GeminiAgent(this)

        binding.btnCloseVision.setOnClickListener {
            finish()
        }

        if (binding.cameraTextureView.isAvailable) {
            openCamera()
        } else {
            binding.cameraTextureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            binding.tvVisionResult.text = "Camera permission not granted."
            return
        }

        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList[0]

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreview()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    Log.e(TAG, "Camera error: $error")
                }
            }, mainHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
        }
    }

    private fun startPreview() {
        val texture = binding.cameraTextureView.surfaceTexture ?: return
        val surface = Surface(texture)

        try {
            cameraDevice?.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val requestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    }
                    if (requestBuilder != null) {
                        session.setRepeatingRequest(requestBuilder.build(), null, mainHandler)
                    }

                    // Schedule snapshot after 800ms to allow auto-focus & exposure
                    mainHandler.postDelayed({
                        if (!isAnalyzing && !isFinishing) {
                            captureAndAnalyze()
                        }
                    }, 800)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Capture session configuration failed")
                }
            }, mainHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting preview", e)
        }
    }

    private fun captureAndAnalyze() {
        isAnalyzing = true
        val bitmap = binding.cameraTextureView.bitmap ?: return
        val question = intent.getStringExtra(EXTRA_QUESTION) ?: "Is image ko dhyan se dekho aur batao samne kya dikh raha hai ya kya likha hai. Hindi aur Hinglish me jawab do."

        binding.progressVision.visibility = View.VISIBLE
        binding.tvVisionResult.text = "Optical stream captured. Consulting Gemini AI Vision..."

        lifecycleScope.launch {
            try {
                // Resize for performance
                val maxDim = 720
                val ratio = Math.min(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
                val resizedBitmap = if (ratio < 1.0f) {
                    Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
                } else bitmap

                val stream = ByteArrayOutputStream()
                resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                val byteArray = stream.toByteArray()
                val base64Image = Base64.encodeToString(byteArray, Base64.NO_WRAP)

                val answer = geminiAgent.processMultimodalVision(base64Image, question)

                binding.progressVision.visibility = View.GONE
                binding.tvVisionResult.text = answer
                AndroidTTSManager.getInstance(this@JarvisVisionActivity).speak(answer)
            } catch (e: Exception) {
                Log.e(TAG, "Vision analysis error", e)
                binding.progressVision.visibility = View.GONE
                binding.tvVisionResult.text = "Analysis error: ${e.localizedMessage}"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        captureSession?.close()
        cameraDevice?.close()
    }
}
