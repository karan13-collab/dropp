package com.dropp.dropp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dropp.dropp.core.networking.CommandTransferManager
import com.dropp.dropp.core.security.SecurityManager
import android.content.pm.PackageManager
import android.util.Log
import com.dropp.dropp.services.DroppBackgroundService
import com.dropp.dropp.services.AudioRoutingManager
import com.dropp.dropp.ui.theme.DroppTheme
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning


class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach { (perm, granted) ->
            Log.d("MainActivity", "Permission $perm granted: $granted")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        startDroppService()
        SecurityManager.init(this)
        CommandTransferManager.init(this)
        AudioRoutingManager.init(this)
        AudioRoutingManager.queryStatus(this)

        // Request runtime permissions needed for calls, Bluetooth routing, and notifications
        requestRequiredPermissions()

        // Catch files if opened from the Share menu
        handleIncomingSharedFiles()

        setContent {
            DroppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // We use a Column to stack your existing UI and the new Button
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Your existing screen takes up the available space
                        Box(modifier = Modifier.weight(1f)) {
                            DroppMainScreen(modifier = Modifier.fillMaxSize())
                        }

                        // The new QR Pair button anchored at the bottom
                        Button(
                            onClick = { startQrScanner() },
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text("Scan QR Code to Pair")
                        }
                    }
                }
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.ANSWER_PHONE_CALLS)
            }
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(android.Manifest.permission.READ_PHONE_STATE)
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(android.Manifest.permission.READ_CONTACTS)
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(android.Manifest.permission.RECORD_AUDIO)
        }

        if (permissionsToRequest.isNotEmpty()) {
            Log.i("MainActivity", "Requesting missing permissions: $permissionsToRequest")
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun startQrScanner() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom() // Highly recommended by Google for easy scanning
            .build()

        val scanner = GmsBarcodeScanning.getClient(this, options)

        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val scannedKey = barcode.rawValue
                if (scannedKey != null && scannedKey.length > 20) {
                    // Save it permanently!
                    SecurityManager.saveKey(this, scannedKey)
                    Toast.makeText(this, "Paired Successfully!", Toast.LENGTH_LONG).show()

                    // (Optional: Start your networking services here if they aren't already running)
                } else {
                    Toast.makeText(this, "Invalid QR Code", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnCanceledListener {
                Toast.makeText(this, "Scan canceled", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Scan failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            DroppBackgroundService.clipboardManager?.checkAndSyncCurrentClipboard()
        }
    }

    private fun startDroppService() {
        val serviceIntent = Intent(this, DroppBackgroundService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun handleIncomingSharedFiles() {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                // Single file shared
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                uri?.let {
                    DroppBackgroundService.fileTransferManager?.sendFile(it)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                // Multiple files shared at once
                @Suppress("DEPRECATION")
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                uris?.forEach { uri ->
                    DroppBackgroundService.fileTransferManager?.sendFile(uri)
                }
            }
        }
    }
}

@Composable
fun DroppMainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // 1. File Picker Launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            DroppBackgroundService.fileTransferManager?.sendFile(it)
        }
    }

    val isRoutingToPc by AudioRoutingManager.isRoutingToPc.collectAsState()
    val targetIp by CommandTransferManager.targetWindowsIpFlow.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status Pill
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (!targetIp.isNullOrBlank())
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else
                    MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (!targetIp.isNullOrBlank()) "PC Connected: $targetIp" else "Looking for Windows PC on Wi-Fi...",
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }

        // --- FEATURE CARD: ROUTE AUDIO TO WINDOWS (ZERO PERMISSIONS) ---
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isRoutingToPc)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Route Audio to Windows PC",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Stream all phone audio (YouTube, Spotify, music, videos, games) directly through your Windows PC speakers. Toggle on or off anytime with zero popups.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                if (isRoutingToPc) {
                    Text(
                        text = "● Playing on Windows PC Speakers",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Button(
                        onClick = {
                            AudioRoutingManager.disableAudioRouting(context)
                            Toast.makeText(context, "Audio restored to phone speakers", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Stop Routing (Play on Phone)")
                    }
                } else {
                    Text(
                        text = "○ Playing on Phone Speakers",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = {
                            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
                            if (btManager?.adapter?.isEnabled != true) {
                                Toast.makeText(context, "Please turn on Bluetooth first", Toast.LENGTH_SHORT).show()
                            } else {
                                AudioRoutingManager.enableAudioRouting(context)
                                Toast.makeText(context, "Routing audio to PC speakers...", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Route Audio to PC")
                    }
                }
            }
        }

        // --- QUICK ACTIONS ---
        Button(
            onClick = {
                filePickerLauncher.launch("*/*")
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Send File to Windows")
        }

        Button(
            onClick = {
                val isGranted = NotificationManagerCompat.getEnabledListenerPackages(context)
                    .contains(context.packageName)

                if (!isGranted) {
                    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    context.startActivity(intent)
                } else {
                    Toast.makeText(context, "Notification permission is already granted!", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Notification Mirroring Permission")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DroppMainScreenPreview() {
    DroppTheme {
        DroppMainScreen()
    }
}