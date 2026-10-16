using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json.Nodes;
using System.Threading.Tasks;
using Microsoft.Windows.AppNotifications;
using Microsoft.Windows.AppNotifications.Builder;
using dropp.Windows.Core.Diagnostics;

namespace dropp.Windows.Services
{
    public class CommandReceiverService
    {
        public const int CommandPort = 8767;
        private TcpListener? _listener;

        private static string? _targetAndroidIp;
        public static string? TargetAndroidIp
        {
            get
            {
                if (string.IsNullOrEmpty(_targetAndroidIp))
                {
                    try
                    {
                        string ipFile = Path.Combine(global::Windows.Storage.ApplicationData.Current.LocalFolder.Path, "last_android_ip.txt");
                        if (File.Exists(ipFile))
                        {
                            _targetAndroidIp = File.ReadAllText(ipFile).Trim();
                            AppLogger.Log("CommandReceiver", $"Loaded TargetAndroidIp from disk: {_targetAndroidIp}");
                        }
                    }
                    catch { }
                }
                return _targetAndroidIp;
            }
            set
            {
                SetTargetAndroidIp(value ?? "");
            }
        }

        public static void SetTargetAndroidIp(string ip)
        {
            if (string.IsNullOrWhiteSpace(ip)) return;
            bool changed = _targetAndroidIp != ip;
            _targetAndroidIp = ip;
            if (changed)
            {
                AppLogger.Log("CommandReceiver", $"Target Android IP set to {_targetAndroidIp}");
            }
            try
            {
                string ipFile = Path.Combine(global::Windows.Storage.ApplicationData.Current.LocalFolder.Path, "last_android_ip.txt");
                if (!File.Exists(ipFile) || changed)
                {
                    File.WriteAllText(ipFile, _targetAndroidIp);
                }
            }
            catch { }
        }

        private static string? _lastNotificationKey;
        public static string? LastNotificationKey
        {
            get
            {
                if (string.IsNullOrEmpty(_lastNotificationKey))
                {
                    try
                    {
                        string keyFile = Path.Combine(global::Windows.Storage.ApplicationData.Current.LocalFolder.Path, "last_notification_key.txt");
                        if (File.Exists(keyFile))
                        {
                            _lastNotificationKey = File.ReadAllText(keyFile).Trim();
                        }
                    }
                    catch { }
                }
                return _lastNotificationKey;
            }
            set
            {
                _lastNotificationKey = value;
                try
                {
                    if (!string.IsNullOrEmpty(_lastNotificationKey))
                    {
                        string keyFile = Path.Combine(global::Windows.Storage.ApplicationData.Current.LocalFolder.Path, "last_notification_key.txt");
                        File.WriteAllText(keyFile, _lastNotificationKey);
                    }
                }
                catch { }
            }
        }

        public void Start()
        {
            _ = Task.Run(ListenForCommandsAsync);
        }

        private async Task ListenForCommandsAsync()
        {
            try
            {
                _listener = new TcpListener(IPAddress.Any, CommandPort);
                _listener.Server.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                _listener.Start();
                AppLogger.Log("CommandReceiver", $"Listening for encrypted commands on port {CommandPort}...");

                while (true)
                {
                    TcpClient client;
                    try
                    {
                        client = await _listener.AcceptTcpClientAsync();
                    }
                    catch (Exception ex)
                    {
                        AppLogger.Log("CommandReceiver", $"Accept error: {ex.Message}");
                        await Task.Delay(500);
                        continue;
                    }

                    if (client.Client.RemoteEndPoint is IPEndPoint remoteEndPoint)
                    {
                        var addr = remoteEndPoint.Address;
                        if (addr.IsIPv4MappedToIPv6)
                        {
                            addr = addr.MapToIPv4();
                        }
                        string peerIp = addr.ToString();
                        SetTargetAndroidIp(peerIp);
                        App.ClipboardService?.SetTargetIp(peerIp);
                        App.FileTransfer?.SetTargetIp(peerIp);
                    }

                    _ = Task.Run(() => HandleClientAsync(client));
                }
            }
            catch (Exception ex)
            {
                AppLogger.Log("CommandReceiver", $"Fatal Listener Error: {ex.Message}");
            }
        }

        private async Task HandleClientAsync(TcpClient client)
        {
            try
            {
                using (client)
                using (var reader = new StreamReader(client.GetStream(), Encoding.UTF8))
                {
                    string? encryptedPayload = await reader.ReadLineAsync();
                    if (string.IsNullOrWhiteSpace(encryptedPayload)) return;

                    string jsonPayload = Core.Security.EncryptionHelper.Decrypt(encryptedPayload, App.MasterKey);
                    Debug.WriteLine($"[CommandReceiver] Decrypted payload: {jsonPayload}");
                    AppLogger.Log("CommandReceiver", $"Received decrypted command: {jsonPayload}");

                    await ProcessCommandAsync(jsonPayload);
                }
            }
            catch (Exception ex)
            {
                AppLogger.Log("CommandReceiver", $"Client/Decryption Error: {ex.Message}");
            }
        }

        private async Task ProcessCommandAsync(string jsonPayload)
        {
            try
            {
                var json = JsonNode.Parse(jsonPayload);
                if (json == null) return;

                string type = (json["type"] ?? json["Type"])?.ToString()?.ToUpperInvariant() ?? "";

                if (type == "NOTIFICATION")
                {
                    string notificationKey = (json["notificationKey"] ?? json["NotificationKey"])?.ToString() ?? "";
                    string packageName = (json["packageName"] ?? json["Package"])?.ToString() ?? "";
                    string appName = (json["appName"] ?? json["AppName"])?.ToString() ?? "";
                    string title = (json["title"] ?? json["Title"])?.ToString() ?? (string.IsNullOrEmpty(appName) ? "New Message" : appName);
                    string text = (json["text"] ?? json["Text"])?.ToString() ?? "";

                    var builder = new AppNotificationBuilder();

                    // Display App Name, Title, and Body Text
                    if (!string.IsNullOrWhiteSpace(appName) && !string.Equals(appName, title, StringComparison.OrdinalIgnoreCase))
                    {
                        builder.AddText(appName);
                        builder.AddText(title);
                        if (!string.IsNullOrWhiteSpace(text))
                        {
                            builder.AddText(text);
                        }
                    }
                    else
                    {
                        builder.AddText(title);
                        if (!string.IsNullOrWhiteSpace(text))
                        {
                            builder.AddText(text);
                        }
                    }

                    var actions = (json["actions"] ?? json["Actions"])?.AsArray();
                    int controlCount = 0;
                    const int maxControls = 5;

                    JsonNode? replyActionNode = null;
                    var buttonNodes = new List<JsonNode>();

                    if (actions != null)
                    {
                        foreach (var act in actions)
                        {
                            if (act == null) continue;
                            string actType = (act["actionType"] ?? act["ActionType"] ?? act["type"] ?? act["Type"])?.ToString()?.ToUpperInvariant() ?? "";
                            bool isReply = act["isReply"]?.GetValue<bool>() ?? act["IsReply"]?.GetValue<bool>() ?? false;

                            if (actType == "REPLY" || isReply)
                            {
                                if (replyActionNode == null)
                                {
                                    replyActionNode = act;
                                }
                                else
                                {
                                    buttonNodes.Add(act);
                                }
                            }
                            else
                            {
                                buttonNodes.Add(act);
                            }
                        }
                    }

                    LastNotificationKey = notificationKey;
                    string encodedKey = Uri.EscapeDataString(notificationKey);

                    // 1. If there is a reply action, inject text input box and reply button
                    if (replyActionNode != null && controlCount + 2 <= maxControls)
                    {
                        string replyTitle = (replyActionNode["title"] ?? replyActionNode["Title"])?.ToString() ?? "Reply";
                        int actionIndex = (replyActionNode["actionIndex"] ?? replyActionNode["ActionIndex"])?.GetValue<int>() ?? 0;
                        string resultKey = (replyActionNode["resultKey"] ?? replyActionNode["ResultKey"])?.ToString() ?? "key_text_reply";

                        // Set default arguments on the toast itself so that hitting enter or send icon carries the arguments
                        builder.AddArgument("actionType", "REPLY")
                               .AddArgument("notificationKey", encodedKey)
                               .AddArgument("actionIndex", actionIndex.ToString())
                               .AddArgument("resultKey", Uri.EscapeDataString(resultKey));

                        builder.AddTextBox("replyText", "Type a reply...", replyTitle);
                        controlCount++;

                        var replyButton = new AppNotificationButton(replyTitle)
                            .AddArgument("actionType", "REPLY")
                            .AddArgument("notificationKey", encodedKey)
                            .AddArgument("actionIndex", actionIndex.ToString())
                            .AddArgument("resultKey", Uri.EscapeDataString(resultKey))
                            .SetInputId("replyText");

                        builder.AddButton(replyButton);
                        controlCount++;
                    }

                    // 2. Add standard action buttons up to toast limit
                    foreach (var btnNode in buttonNodes)
                    {
                        if (controlCount >= maxControls) break;

                        string btnTitle = (btnNode["title"] ?? btnNode["Title"])?.ToString() ?? "Action";
                        int actionIndex = (btnNode["actionIndex"] ?? btnNode["ActionIndex"])?.GetValue<int>() ?? 0;

                        var actionBtn = new AppNotificationButton(btnTitle)
                            .AddArgument("actionType", "BUTTON")
                            .AddArgument("notificationKey", encodedKey)
                            .AddArgument("actionIndex", actionIndex.ToString());

                        builder.AddButton(actionBtn);
                        controlCount++;
                    }

                    var notification = builder.BuildNotification();
                    if (!string.IsNullOrEmpty(notificationKey))
                    {
                        using var sha = System.Security.Cryptography.SHA256.Create();
                        string safeTag = Convert.ToHexString(sha.ComputeHash(Encoding.UTF8.GetBytes(notificationKey))).Substring(0, 32);
                        notification.Tag = safeTag;
                        notification.Group = "dropp_notifications";
                    }

                    AppNotificationManager.Default.Show(notification);
                    AppLogger.Log("CommandReceiver", $"Rendered native toast for: {appName} - {title}");
                }
                else if (type == "NOTIFICATION_DISMISS" || type == "DISMISS")
                {
                    string notificationKey = (json["notificationKey"] ?? json["NotificationKey"])?.ToString() ?? "";
                    if (!string.IsNullOrEmpty(notificationKey))
                    {
                        using var sha = System.Security.Cryptography.SHA256.Create();
                        string safeTag = Convert.ToHexString(sha.ComputeHash(Encoding.UTF8.GetBytes(notificationKey))).Substring(0, 32);
                        await AppNotificationManager.Default.RemoveByTagAsync(safeTag);
                        AppLogger.Log("CommandReceiver", $"Dismissed toast for key: {notificationKey} (tag: {safeTag})");
                    }
                }
            }
            catch (Exception ex)
            {
                AppLogger.Log("CommandReceiver", $"JSON Parse / Toast Error: {ex.Message}");
            }
        }

        public static async Task<bool> SendCommandAsync(string jsonPayload)
        {
            string targetIp = TargetAndroidIp
                              ?? App.FileTransfer?._targetIp
                              ?? "";

            if (string.IsNullOrEmpty(targetIp))
            {
                AppLogger.Log("CommandReceiver", "Cannot send command to Android: target IP is not set.");
                return false;
            }

            if (string.IsNullOrEmpty(App.MasterKey))
            {
                AppLogger.Log("CommandReceiver", "Cannot send command to Android: MasterKey is empty.");
                return false;
            }

            try
            {
                AppLogger.Log("CommandReceiver", $"Connecting to Android at {targetIp}:{CommandPort}...");
                using var client = new TcpClient();
                var connectTask = client.ConnectAsync(targetIp, CommandPort);
                var completed = await Task.WhenAny(connectTask, Task.Delay(5000)).ConfigureAwait(false);
                if (completed != connectTask)
                {
                    throw new TimeoutException($"Connection to Android at {targetIp}:{CommandPort} timed out (5000ms).");
                }
                await connectTask.ConfigureAwait(false);

                using var stream = client.GetStream();
                using var writer = new StreamWriter(stream, Encoding.UTF8) { AutoFlush = true };
                string encryptedPayload = Core.Security.EncryptionHelper.Encrypt(jsonPayload, App.MasterKey);
                await writer.WriteLineAsync(encryptedPayload).ConfigureAwait(false);
                await writer.FlushAsync().ConfigureAwait(false);
                AppLogger.Log("CommandReceiver", $"Encrypted command successfully sent to Android ({targetIp}:{CommandPort})!");
                return true;
            }
            catch (Exception ex)
            {
                AppLogger.Log("CommandReceiver", $"Failed to send command to Android: {ex.Message}");
                return false;
            }
        }
    }
}