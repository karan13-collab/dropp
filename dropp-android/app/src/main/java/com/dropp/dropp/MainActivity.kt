package com.dropp.dropp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import com.dropp.dropp.services.DroppBackgroundService
import com.dropp.dropp.ui.theme.DroppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        startDroppService()

        // Catch files if opened from the Share menu
        handleIncomingSharedFiles()

        setContent {
            DroppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DroppMainScreen(modifier = Modifier.padding(innerPadding))
                }
            }
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

    // 1. We moved this function INSIDE the MainActivity class!
    // Now it can successfully read the Activity's 'intent' property.
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
    // 1. Prepare the File Picker Launcher in Compose
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            // 2. Send the file using our background service!
            DroppBackgroundService.fileTransferManager?.sendFile(it)
        }
    }

    // 3. Draw the UI Button in the center of the screen
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Button(onClick = {
            // "*/*" opens the Android file browser and allows picking any file type
            filePickerLauncher.launch("*/*")
        }) {
            Text("Test Send File to Windows")
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