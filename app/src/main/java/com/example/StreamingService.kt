package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pedro.rtspserver.RtspServerCamera2
import com.pedro.common.ConnectChecker
import com.pedro.library.view.OpenGlView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import android.util.Size

class StreamingService : Service(), ConnectChecker {

    private val binder = LocalBinder()
    var rtspServerCamera2: RtspServerCamera2? = null
        private set
        
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming
    
    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus: StateFlow<String> = _connectionStatus

    private val _resolutions = MutableStateFlow<List<Size>>(emptyList())
    val resolutions: StateFlow<List<Size>> = _resolutions

    private val _selectedResolution = MutableStateFlow<Size?>(null)
    val selectedResolution: StateFlow<Size?> = _selectedResolution

    fun setResolution(size: Size) {
        if (_selectedResolution.value == size) return
        _selectedResolution.value = size
        if (_isStreaming.value) {
            restartStream()
        }
    }

    fun switchCamera() {
        try {
            rtspServerCamera2?.switchCamera()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun restartStream() {
        stopStreaming()
        // Wait a tiny bit for the stop to process then start again
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            startStreaming()
        }, 500)
    }

    private val port = 1935

    inner class LocalBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    fun initServer(context: Context) {
        if (rtspServerCamera2 == null) {
            rtspServerCamera2 = RtspServerCamera2(context, this, port)
            try {
                val backResolutions = rtspServerCamera2?.resolutionsBack ?: emptyList()
                val standardResolutions = listOf(Size(1920, 1080), Size(1280, 720))
                val availableStandard = standardResolutions.filter { std -> 
                    backResolutions.any { it.width == std.width && it.height == std.height }
                }
                
                if (availableStandard.isNotEmpty()) {
                    _resolutions.value = availableStandard
                    _selectedResolution.value = availableStandard.last() // Default to 720p if available
                } else {
                    // Fallback just in case
                    _resolutions.value = listOf(Size(1280, 720))
                    _selectedResolution.value = _resolutions.value.first()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun startStreaming() {
        if (rtspServerCamera2?.isStreaming == false) {
            val res = _selectedResolution.value ?: Size(1280, 720)
            val bitrate = if (res.width >= 1920) 4000 * 1024 else 2000 * 1024
            
            // Auto orientation handling by the library
            if (rtspServerCamera2?.prepareAudio() == true && rtspServerCamera2?.prepareVideo(res.width, res.height, bitrate) == true) {
                rtspServerCamera2?.startStream()
                _isStreaming.value = true
                _connectionStatus.value = "Waiting for OBS to connect on port $port..."
                startForeground(1, createNotification("Streaming Active", "Ready for OBS connection"))
            } else {
                _connectionStatus.value = "Failed to prepare camera or audio"
            }
        }
    }

    fun stopStreaming() {
        if (rtspServerCamera2?.isStreaming == true) {
            rtspServerCamera2?.stopStream()
        }
        _isStreaming.value = false
        _connectionStatus.value = "Disconnected"
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
    }

    override fun onConnectionStarted(rtspUrl: String) {
        _connectionStatus.value = "Connecting..."
    }

    override fun onConnectionSuccess() {
        _connectionStatus.value = "Connected to OBS!"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, createNotification("Streaming to OBS", "Connected"))
    }

    override fun onConnectionFailed(reason: String) {
        _connectionStatus.value = "Connection Failed: $reason"
        stopStreaming()
    }

    override fun onNewBitrate(bitrate: Long) {
    }

    override fun onDisconnect() {
        _connectionStatus.value = "Disconnected from OBS"
        stopStreaming()
    }

    override fun onAuthError() {
        _connectionStatus.value = "Auth Error"
        stopStreaming()
    }
    
    override fun onAuthSuccess() {
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "streaming_channel",
            "Streaming Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(title: String, content: String): Notification {
        return NotificationCompat.Builder(this, "streaming_channel")
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }
}
