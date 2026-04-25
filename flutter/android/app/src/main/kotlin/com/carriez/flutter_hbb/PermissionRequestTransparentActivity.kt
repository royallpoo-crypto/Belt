package com.carriez.flutter_hbb

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log

class PermissionRequestTransparentActivity: Activity() {
    private val logTag = "permissionRequest"
    private var mainService: MainService? = null
    private var mediaProjectionResultIntent: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(logTag, "onCreate PermissionRequestTransparentActivity: intent.action: ${intent.action}")

        when (intent.action) {
            ACT_REQUEST_MEDIA_PROJECTION -> {
     val mediaProjectionManager =
                    getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val intent = mediaProjectionManager.createScreenCaptureIntent()
                startActivityForResult(intent, REQ_REQUEST_MEDIA_PROJECTION)
            }
            else -> finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                mediaProjectionResultIntent = data
                launchService(data)
            } else {
                Log.d(logTag, "Media projection permission denied")
                // Notify the service that permission was denied so it can reset the flag
                val serviceIntent = Intent(this, MainService::class.java)
                serviceIntent.action = ACT_MEDIA_PROJECTION_DENIED
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                setResult(RES_FAILED)
                finish()
            }
        }
    }

    private fun launchService(mediaProjectionResult: Intent) {
        Log.d(logTag, "Launch/Update MainService with media projection")
        val serviceIntent = Intent(this, MainService::class.java)
        serviceIntent.action = ACT_INIT_MEDIA_PROJECTION_AND_SERVICE
        serviceIntent.putExtra(EXT_MEDIA_PROJECTION_RES_INTENT, mediaProjectionResult)

        // First, try to bind to existing service if it's running
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)

        // Also start/update the service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(logTag, "Connected to MainService, sending media projection")
            try {
                val binder = service as MainService.LocalBinder
                mainService = binder.getService()
                
                // Send the media projection to the running service
                mediaProjectionResultIntent?.let {
                    Log.d(logTag, "Sending media projection through service binding")
                    mainService?.setMediaProjection(it)
                }
            } catch (e: Exception) {
                Log.e(logTag, "Error sending media projection to service: ${e.message}")
            } finally {
                unbindService(this)
                finish()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(logTag, "Disconnected from MainService")
            mainService = null
        }
    }

    override fun onDestroy() {
        try {
            unbindService(serviceConnection)
        } catch (e: Exception) {
            Log.d(logTag, "Error unbinding service: ${e.message}")
        }
        super.onDestroy()
    }

}
