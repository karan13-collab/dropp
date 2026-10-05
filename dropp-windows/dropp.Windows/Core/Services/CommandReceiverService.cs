using System;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text.Json.Nodes;
using System.Threading.Tasks;
using Microsoft.Windows.AppNotifications;
using Microsoft.Windows.AppNotifications.Builder;

namespace dropp.Windows.Services
{
    public class CommandReceiverService
    {
        private const int CommandPort = 8081;
        private TcpListener? _listener;

        public void Start()
        {
            _ = Task.Run(ListenForCommandsAsync);
        }

        private async Task ListenForCommandsAsync()
        {
            try
            {
                _listener = new TcpListener(IPAddress.Any, CommandPort);
                _listener.Start();
                Debug.WriteLine($"[CommandReceiver] Listening for encrypted commands on port {CommandPort}...");

                while (true)
                {
                    var client = await _listener.AcceptTcpClientAsync();
                    _ = Task.Run(() => HandleClientAsync(client));
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"[CommandReceiver] Listener Error: {ex.Message}");
            }
        }

        private async Task HandleClientAsync(TcpClient client)
        {
            try
            {
                using (client)
                using (var reader = new StreamReader(client.GetStream()))
                {
                    // 1. Read the raw, encrypted Base64 string from the network
                    string? encryptedPayload = await reader.ReadLineAsync();
                    if (string.IsNullOrEmpty(encryptedPayload)) return;

                    // 2. Decrypt it using our saved Master Key
                    string jsonPayload = Core.Security.EncryptionHelper.Decrypt(encryptedPayload, App.MasterKey);

                    Debug.WriteLine($"[CommandReceiver] Successfully Decrypted Payload: {jsonPayload}");

                    // 3. Process the standard JSON
                    ProcessCommand(jsonPayload);
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"[CommandReceiver] Client/Decryption Error: {ex.Message}");
            }
        }

        private void ProcessCommand(string jsonPayload)
        {
            try
            {
                // Parse the incoming JSON from Android
                var json = JsonNode.Parse(jsonPayload);
                if (json == null) return;

                string? type = json["Type"]?.ToString();

                if (type == "NOTIFICATION")
                {
                    string title = json["Title"]?.ToString() ?? "New Message";
                    string text = json["Text"]?.ToString() ?? "";

                    // Build the native Windows 11 UI
                    var builder = new AppNotificationBuilder()
                        .AddText(title)
                        .AddText(text);

                    var actions = json["Actions"]?.AsArray();
                    if (actions != null)
                    {
                        foreach (var action in actions)
                        {
                            string? actionId = action["Id"]?.ToString();
                            string? actionTitle = action["Title"]?.ToString();
                            bool isReply = action["IsReply"]?.GetValue<bool>() ?? false;

                            if (actionId == null || actionTitle == null) continue;

                            if (isReply)
                            {
                                // If it's a reply button, inject a Windows native text box!
                                builder.AddTextBox(actionId, "Type your reply...", "Reply");
                                builder.AddButton(new AppNotificationButton(actionTitle)
                                    .AddArgument("action", actionId)
                                    .SetInputId(actionId)); // Links the button to the text box
                            }
                            else
                            {
                                // Standard button (e.g., "Mark as Read")
                                builder.AddButton(new AppNotificationButton(actionTitle)
                                    .AddArgument("action", actionId));
                            }
                        }
                    }

                    // Show the notification on the Windows desktop!
                    AppNotificationManager.Default.Show(builder.BuildNotification());
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"[CommandReceiver] JSON Parse Error: {ex.Message}");
            }
        }
    }
}