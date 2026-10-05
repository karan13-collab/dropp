package com.dropp.dropp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dropp.dropp.services.DroppBackgroundService
import com.dropp.dropp.ui.theme.DroppTheme
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.dropp.dropp.core.security.SecurityManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        startDroppService()
        SecurityManager.init(this)

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

    // 1. Prepare the File Picker Launcher in Compose
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            DroppBackgroundService.fileTransferManager?.sendFile(it)
        }
    }

    // 2. Draw the UI with multiple buttons
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Button(onClick = {
            // "*/*" opens the Android file browser and allows picking any file type
            filePickerLauncher.launch("*/*")
        }) {
            Text("Test Send File to Windows")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = {
            // Check if permission is already granted
            val isGranted = NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)

            if (!isGranted) {
                // Open the Android settings screen for Notification Access
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                context.startActivity(intent)
            }
        }) {
            Text("Grant Notification Permission")
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