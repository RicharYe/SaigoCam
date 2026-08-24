package com.example

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.IBinder
import android.view.SurfaceHolder
import com.pedro.library.view.OpenGlView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    private var streamingService: StreamingService? = null
    private var isBound = mutableStateOf(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as StreamingService.LocalBinder
            streamingService = binder.getService()
            isBound.value = true
        }
        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound.value = false
            streamingService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Keep the screen on while the app is running/visible
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        val requiredPermissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val intent = Intent(this, StreamingService::class.java)
        startService(intent) // Start as sticky
        bindService(intent, connection, Context.BIND_AUTO_CREATE)

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var hasPermissions by remember {
                        mutableStateOf(requiredPermissions.all {
                            ContextCompat.checkSelfPermission(this@MainActivity, it) == PackageManager.PERMISSION_GRANTED
                        })
                    }

                    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { permissions ->
                        hasPermissions = permissions.values.all { it }
                    }

                    LaunchedEffect(Unit) {
                        if (!hasPermissions) {
                            launcher.launch(requiredPermissions.toTypedArray())
                        }
                    }

                    val bound by isBound
                    if (hasPermissions && bound && streamingService != null) {
                        SaigoCamScreen(streamingService!!)
                    } else {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (!hasPermissions) {
                                    Text("Camera and Audio permissions are required to stream.", modifier = Modifier.padding(16.dp))
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(onClick = { launcher.launch(requiredPermissions.toTypedArray()) }) {
                                        Text("Grant Permissions")
                                    }
                                } else {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound.value) {
            unbindService(connection)
            isBound.value = false
        }
    }
}

@Composable
fun SaigoCamScreen(service: StreamingService) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Stream", "Setup Guide")

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title, fontWeight = FontWeight.SemiBold) }
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (selectedTab == 0) {
                StreamContent(service)
            } else {
                SetupGuideContent(service)
            }
        }
    }
}

@Composable
fun StreamContent(service: StreamingService) {
    val isStreaming by service.isStreaming.collectAsState(initial = false)
    val connectionStatus by service.connectionStatus.collectAsState(initial = "Disconnected")
    var surfaceView by remember { mutableStateOf<OpenGlView?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Camera Preview with Switch Overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black, RoundedCornerShape(12.dp))
                .padding(4.dp)
        ) {
            AndroidView(
                factory = { ctx ->
                    OpenGlView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                service.initServer(ctx)
                                try {
                                    service.rtspServerCamera2?.replaceView(this@apply)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                                if (service.rtspServerCamera2?.isOnPreview == false) {
                                    service.rtspServerCamera2?.startPreview()
                                }
                            }
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                try {
                                    service.rtspServerCamera2?.replaceView(ctx)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                                if (!isStreaming) {
                                    service.rtspServerCamera2?.stopPreview()
                                }
                            }
                        })
                        surfaceView = this
                    }
                },
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
            )

            // Switch Camera Overlay Button
            IconButton(
                onClick = { 
                    try {
                        service.rtspServerCamera2?.switchCamera()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.5f), shape = RoundedCornerShape(50))
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Switch Camera",
                    tint = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Status
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Connection", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    text = if (isStreaming) "● $connectionStatus" else "● Disconnected",
                    color = if (isStreaming) Color(0xFF4CAF50) else Color.Gray,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (isStreaming) {
                    val bitrate by service.currentBitrate.collectAsState(initial = 0L)
                    val bitrateText = if (bitrate > 0) "%.1f Mbps".format(bitrate / 1_000_000.0) else "—"
                    Text(
                        text = "Bitrate: $bitrateText",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Settings
        val resolutions by service.resolutions.collectAsState(initial = emptyList())
        val selectedResolution by service.selectedResolution.collectAsState(initial = null)
        var expanded by remember { mutableStateOf(false) }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Resolution Selection", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(16.dp))
                
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(selectedResolution?.let { "${it.width}x${it.height}" } ?: "Select Resolution")
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        resolutions.forEach { res ->
                            DropdownMenuItem(
                                text = { Text("${res.width}x${res.height}") },
                                onClick = {
                                    service.setResolution(res)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Start/Stop Button
        Button(
            onClick = {
                if (isStreaming) {
                    service.stopStreaming()
                } else {
                    service.startStreaming()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isStreaming) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                text = if (isStreaming) "STOP STREAMING" else "START STREAMING",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun SetupGuideContent(service: StreamingService) {
    val context = LocalContext.current
    val ipAddress = getLocalIpAddress(context)
    val scrollState = androidx.compose.foundation.rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        Text("OBS Setup Guide", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(16.dp))

        // Wi-Fi Setup (VLC Video Source)
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("1. Wi-Fi Setup (VLC Video Source)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text("VLC is recommended for instant auto-connecting.\n\n• Install VLC on your PC and restart OBS.\n• Open OBS Studio -> Sources -> '+' -> 'VLC Video Source'.\n• CHECK 'Loop Playlist' (Required for auto-connect).\n• Set 'Network Caching' to 100ms (lowest possible).\n• In the Playlist box, click '+' -> 'Add Path/URL'.\n• Enter the exact URL below.\n\n*Note: If the app was recently closed or OBS was just opened, double-click the VLC source and click 'OK' to wake it up.*\n\n*Pro-Tip: Sometimes just clicking the \"Eye\" icon (hide/unhide) next to the source in OBS is enough to wake it up, which is much faster than opening properties.*")
                Spacer(modifier = Modifier.height(12.dp))
                Text("VLC Source URL:", fontWeight = FontWeight.SemiBold)
                Text("rtsp://$ipAddress:1935", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Wi-Fi Setup (Media Source)
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("2. Wi-Fi Setup (Media Source)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text("• Ensure PC and Phone are on the same Wi-Fi.\n• In the Stream tab, tap 'START STREAMING'.\n• Open OBS Studio -> Sources -> '+' -> 'Media Source'.\n• Uncheck 'Local File'.\n• Uncheck 'Use hardware decoding when available'.\n• Set 'Network Buffering' to 0 MB.\n• Set Input to the URL below.\n\n*Note: If the feed doesn't show up immediately, double-click the Media Source in OBS to open Properties and click 'OK' to force it to connect.*\n\n*Pro-Tip: Sometimes just clicking the \"Eye\" icon (hide/unhide) next to the source in OBS is enough to wake it up, which is much faster than opening properties.*")
                Spacer(modifier = Modifier.height(12.dp))
                Text("Media Source URL:", fontWeight = FontWeight.SemiBold)
                Text("rtsp://$ipAddress:1935", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // USB Setup
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("3. USB Setup (Lowest Latency)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text("• Enable USB Debugging on your phone.\n• Connect via USB to your PC.\n• Open terminal/cmd on your PC and run:")
                Card(modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.Black)) {
                    Text("adb forward tcp:1935 tcp:1935", color = Color.Green, modifier = Modifier.padding(8.dp), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
                Text("• Start streaming in this app.\n• Use the USB URL below in OBS with either the Media Source or VLC Source method.")
                Spacer(modifier = Modifier.height(12.dp))
                Text("USB URL:", fontWeight = FontWeight.SemiBold)
                Text("rtsp://127.0.0.1:1935", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Google Meet Setup Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("4. Streaming to Google Meet", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "Video: OBS Virtual Camera\n" +
                           "• In OBS, with your SaigoCam feed already showing as a source:\n" +
                           "• Click 'Start Virtual Camera' (bottom-right of OBS, or Tools → Start Virtual Camera).\n" +
                           "• In Google Meet, click Settings (⚙️) → Video → select 'OBS Virtual Camera' as your camera.\n\n" +
                           "Audio: Virtual Microphone (needs VB-Cable)\n" +
                           "OBS Virtual Camera only sends video. To send phone microphone audio:\n" +
                           "• Download & install VB-Cable (https://vb-audio.com/Cable/) on your PC.\n" +
                           "• In OBS: Settings → Audio → Monitoring Device → set to 'CABLE Input (VB-Audio)'.\n" +
                           "• In OBS Audio Mixer: Click the ⋮ (three dots) next to SaigoCam audio source → Advanced Audio Properties → set Monitor to 'Monitor and Output'.\n" +
                           "• In Google Meet: Settings → Audio → Microphone → select 'CABLE Output (VB-Audio)'.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

fun getLocalIpAddress(context: Context): String {
    try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        var fallbackIp = "Unknown"
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                    val ip = address.hostAddress ?: continue
                    if (networkInterface.name.contains("wlan")) {
                        return ip // Prefer Wi-Fi
                    } else if (networkInterface.name.contains("eth")) {
                        return ip // Prefer Ethernet
                    }
                    fallbackIp = ip
                }
            }
        }
        return fallbackIp
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return "Unknown"
}
