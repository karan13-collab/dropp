package com.dropp.dropp.core.networking

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.app.RemoteInput
import com.dropp.dropp.services.NotificationSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket

object CommandReceiverService {
    private const val COMMAND_PORT = 8082
    private var isListening = false

    fun startListening(context: Context) {
        if (isListening) return
        isListening = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                ServerSocket(COMMAND_PORT).use { serverSocket ->
                    Log.d("dropp", "Android Command Receiver listening on port $COMMAND_PORT...")
                    while (true) {
                        val socket = serverSocket.accept()

                        // We caught a packet from Windows!
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        val jsonString = reader.readLine()

                        if (jsonString != null) {
                            Log.d("dropp", "Received from Windows: $jsonString")
                            processCommand(context, jsonString)
                        }
                        socket.close()
                    }
                }
            } catch (e: Exception) {
                Log.e("dropp", "Command Receiver Error: ${e.message}")
                isListening = false
            }
        }
    }

    private fun processCommand(context: Context, jsonString: String) {
        try {
            val json = JSONObject(jsonString)
            val type = json.optString("Type")

            if (type == "REPLY") {
                val actionId = json.optString("ActionId")
                val text = json.optString("Text")

                // 1. Find the cached button in memory
                val action = NotificationSyncService.activeActions[actionId]
                if (action == null) {
                    Log.e("dropp", "Could not find cached Action ID: $actionId")
                    return
                }

                // 2. Find the RemoteInput object that WhatsApp uses for text
                val remoteInput = action.remoteInputs?.firstOrNull()
                if (remoteInput != null) {
                    // 3. Package the text exactly how WhatsApp expects it
                    val intent = Intent()
                    val bundle = Bundle()
                    bundle.putCharSequence(remoteInput.resultKey, text)
                    RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)

                    // 4. FIRE THE INTENT!
                    action.actionIntent?.send(context, 0, intent)
                    Log.d("dropp", "✅ Successfully executed WhatsApp reply: $text")
                }
            }
        } catch (e: Exception) {
            Log.e("dropp", "Error processing command: ${e.message}")
        }
    }
}