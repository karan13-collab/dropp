using dropp.Windows.Core.Networking;
using dropp.Windows.Services;
using Microsoft.UI.Xaml;
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Text.Json.Nodes;
using System.Threading.Tasks;
using Microsoft.Windows.AppLifecycle;
using Microsoft.Windows.AppNotifications;
using dropp.Windows.Core.Diagnostics;

namespace dropp.Windows
{
    public partial class App : Application
    {
        public static ClipboardSyncService? ClipboardService { get; private set; }
        public static FileTransferService? FileTransfer { get; private set; }
        public static CommandReceiverService? CommandReceiver { get; private set; }

        private static string _masterKey = "";
        public static string MasterKey
        {
            get
            {
                if (string.IsNullOrEmpty(_masterKey))
                {
                    try
                    {
                        string fallbackDir = System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "dropp");
                        System.IO.Directory.CreateDirectory(fallbackDir);
                        string fallbackKeyPath = System.IO.Path.Combine(fallbackDir, "masterkey.txt");
                        string keyFilePath = System.IO.Path.Combine(global::Windows.Storage.ApplicationData.Current.LocalFolder.Path, "masterkey.txt");

                        if (System.IO.File.Exists(fallbackKeyPath))
                        {
                            _masterKey = System.IO.File.ReadAllText(fallbackKeyPath).Trim();
                            try { System.IO.File.WriteAllText(keyFilePath, _masterKey); } catch { }
                        }
                        else if (System.IO.File.Exists(keyFilePath))
                        {
                            _masterKey = System.IO.File.ReadAllText(keyFilePath).Trim();
                            try { System.IO.File.WriteAllText(fallbackKeyPath, _masterKey); } catch { }
                        }
                        else
                        {
                            _masterKey = Core.Security.EncryptionHelper.GeneratePairingKey();
                            try { System.IO.File.WriteAllText(keyFilePath, _masterKey); } catch { }
                            try { System.IO.File.WriteAllText(fallbackKeyPath, _masterKey); } catch { }
                        }
                    }
                    catch { }
                }
                return _masterKey;
            }
            private set => _masterKey = value;
        }

        private static readonly System.Collections.Concurrent.ConcurrentDictionary<string, DateTime> _dispatchedActivations = new();

        private Window? m_window;
        private MdnsManager? _mdnsManager;

        public App()
        {
            this.InitializeComponent();
            this.UnhandledException += (s, e) =>
            {
                AppLogger.Log("App", $"UnhandledException: {e.Message} - {e.Exception}");
                e.Handled = true;
            };
        }

        protected override void OnLaunched(Microsoft.UI.Xaml.LaunchActivatedEventArgs args)
        {
            AppLogger.Log("App", "OnLaunched starting...");
            try
            {
                // Ensure MasterKey is initialized
                _ = MasterKey;

                var mainInstance = AppInstance.FindOrRegisterForKey("main");
                var activatedArgs = AppInstance.GetCurrent().GetActivatedEventArgs();

                if (!mainInstance.IsCurrent)
                {
                    AppLogger.Log("App", $"Secondary instance detected. Kind={activatedArgs?.Kind}, Data={activatedArgs?.Data?.GetType().FullName}");

                    bool handled = false;
                    if (activatedArgs != null)
                    {
                        try
                        {
                            handled = Task.Run(async () =>
                            {
                                return await HandleAnyActivationAsync(activatedArgs);
                            }).GetAwaiter().GetResult();
                        }
                        catch (Exception ex)
                        {
                            AppLogger.Log("App", $"Direct execution in secondary instance error: {ex.Message}");
                        }

                        // DO NOT redirect inline notification actions to main instance (prevents duplicate sends)
                        if (!handled)
                        {
                            try
                            {
                                Task.Run(async () =>
                                {
                                    await mainInstance.RedirectActivationToAsync(activatedArgs);
                                }).GetAwaiter().GetResult();
                            }
                            catch { }
                        }
                    }

                    Environment.Exit(0);
                    return;
                }

                // --- REGISTER WINDOWS APP SDK TOAST NOTIFICATION HANDLERS ---
                try
                {
                    AppNotificationManager.Default.NotificationInvoked += OnNotificationInvoked;
                    AppNotificationManager.Default.Register();
                    AppLogger.Log("AppNotification", "AppNotificationManager registered successfully.");
                }
                catch (Exception ex)
                {
                    AppLogger.Log("AppNotification", $"AppNotificationManager registration notice: {ex.Message}");
                }

                // Cold start check
                try
                {
                    if (activatedArgs != null && activatedArgs.Kind != ExtendedActivationKind.Launch)
                    {
                        _ = HandleAnyActivationAsync(activatedArgs);
                    }
                }
                catch (Exception ex)
                {
                    AppLogger.Log("AppNotification", $"Error checking cold-start notification args: {ex.Message}");
                }

                // --- CATCH REDIRECTED ACTIVATIONS & SHARE INTENTS ---
                mainInstance.Activated += (sender, incomingArgs) =>
                {
                    AppLogger.Log("App", $"Main instance Activated: Kind={incomingArgs?.Kind}, Data={incomingArgs?.Data?.GetType().FullName}");

                    // 1. Handle Toast Notification Actions when redirected
                    if (incomingArgs != null)
                    {
                        _ = HandleAnyActivationAsync(incomingArgs);
                    }

                    // 2. Handle Windows Explorer Context Menu File Sharing
                    if (incomingArgs?.Data is global::Windows.ApplicationModel.Activation.ShareTargetActivatedEventArgs shareArgs)
                    {
                        _ = Task.Run(async () =>
                        {
                            try
                            {
                                var shareOperation = shareArgs.ShareOperation;
                                shareOperation.ReportStarted();

                                if (shareOperation.Data.Contains(global::Windows.ApplicationModel.DataTransfer.StandardDataFormats.StorageItems))
                                {
                                    var storageItems = await shareOperation.Data.GetStorageItemsAsync();
                                    foreach (var item in storageItems)
                                    {
                                        if (item is global::Windows.Storage.StorageFile file)
                                        {
                                            string filePath = file.Path;
                                            string targetIp = FileTransfer?._targetIp ?? "";
                                            if (!string.IsNullOrEmpty(targetIp) && FileTransfer != null)
                                            {
                                                await FileTransfer.SendFileAsync(filePath);
                                            }
                                        }
                                    }
                                }

                                shareOperation.ReportCompleted();
                            }
                            catch (Exception ex)
                            {
                                Debug.WriteLine($"[ShareTarget] Error: {ex.Message}");
                            }
                        });
                    }
                };

                m_window = new MainWindow();

                ClipboardService = new ClipboardSyncService(m_window.DispatcherQueue);
                FileTransfer = new FileTransferService();
                CommandReceiver = new CommandReceiverService();

                ClipboardService.Start();
                FileTransfer?.Start();
                CommandReceiver.Start();
                CallAudioBridgeService.Instance.StartListener();

                m_window.Activate();

                _ = Task.Run(async () =>
                {
                    await Task.Delay(2000);
                    Debug.WriteLine($"\n\n=======================================");
                    Debug.WriteLine($"[SECURITY] YOUR MASTER PAIRING KEY:");
                    Debug.WriteLine(MasterKey);
                    Debug.WriteLine($"=======================================\n\n");
                });

                _mdnsManager = new MdnsManager();
                _ = Task.Run(async () =>
                {
                    _mdnsManager.StartBroadcasting();
                    await _mdnsManager.StartListeningAsync();
                });
            }
            catch (Exception ex)
            {
                AppLogger.Log("App", $"OnLaunched fatal error: {ex.Message}");
            }
        }

        private static void OnNotificationInvoked(AppNotificationManager sender, AppNotificationActivatedEventArgs args)
        {
            _ = HandleNotificationActivatedAsync(args);
        }

        private static bool ShouldDispatch(string dedupeKey)
        {
            var now = DateTime.UtcNow;

            // 1. In-memory check
            if (_dispatchedActivations.TryGetValue(dedupeKey, out var lastTime))
            {
                if ((now - lastTime).TotalSeconds < 5)
                {
                    return false;
                }
            }
            _dispatchedActivations[dedupeKey] = now;

            // 2. Inter-process disk check
            try
            {
                string dedupeFile = System.IO.Path.Combine(global::Windows.Storage.ApplicationData.Current.LocalFolder.Path, "last_action_dispatch.txt");
                if (System.IO.File.Exists(dedupeFile))
                {
                    string content = System.IO.File.ReadAllText(dedupeFile).Trim();
                    var parts = content.Split('|');
                    if (parts.Length == 2 && parts[0] == dedupeKey)
                    {
                        if (long.TryParse(parts[1], out long fileTimestamp))
                        {
                            var lastFileTime = new DateTime(fileTimestamp, DateTimeKind.Utc);
                            if ((now - lastFileTime).TotalSeconds < 5)
                            {
                                return false;
                            }
                        }
                    }
                }
                System.IO.File.WriteAllText(dedupeFile, $"{dedupeKey}|{now.Ticks}");
            }
            catch { }

            return true;
        }

        public static async Task<bool> HandleAnyActivationAsync(AppActivationArguments incomingArgs)
        {
            if (incomingArgs == null) return false;
            AppLogger.Log("Activation", $"HandleAnyActivationAsync: Kind={incomingArgs.Kind}, Data={incomingArgs.Data?.GetType().FullName}");

            if (incomingArgs.Data is AppNotificationActivatedEventArgs appNotifArgs)
            {
                return await HandleNotificationActivatedAsync(appNotifArgs);
            }

            if (incomingArgs.Data is global::Windows.ApplicationModel.Activation.ToastNotificationActivatedEventArgs toastArgs)
            {
                return await HandleToastNotificationActivatedAsync(toastArgs);
            }

            if (incomingArgs.Data is global::Windows.ApplicationModel.Activation.LaunchActivatedEventArgs launchArgs)
            {
                string argsStr = launchArgs.Arguments ?? "";
                if (argsStr.Contains("actionType=", StringComparison.OrdinalIgnoreCase))
                {
                    var dict = ParseQueryString(argsStr);
                    dict.TryGetValue("actionType", out string? actionType);
                    dict.TryGetValue("notificationKey", out string? notifKey);
                    dict.TryGetValue("actionIndex", out string? actionIdxStr);
                    dict.TryGetValue("resultKey", out string? resKey);
                    int.TryParse(actionIdxStr, out int actionIndex);

                    return await DispatchNotificationActionAsync(actionType ?? "", notifKey ?? "", actionIndex, resKey ?? "", "");
                }
            }

            return false;
        }

        private static async Task<bool> HandleNotificationActivatedAsync(AppNotificationActivatedEventArgs args)
        {
            var arguments = args.Arguments;
            AppLogger.Log("AppNotificationActivation", $"Arguments count={arguments.Count}: {string.Join("; ", arguments.Select(kv => $"{kv.Key}='{kv.Value}'"))}");

            string replyText = "";
            if (args.UserInput != null)
            {
                AppLogger.Log("AppNotificationActivation", $"UserInput count={args.UserInput.Count}: {string.Join("; ", args.UserInput.Select(kv => $"{kv.Key}='{kv.Value}'"))}");
                if (args.UserInput.TryGetValue("replyText", out string? val))
                {
                    replyText = val ?? "";
                }
                if (string.IsNullOrWhiteSpace(replyText))
                {
                    foreach (var kvp in args.UserInput)
                    {
                        if (!string.IsNullOrWhiteSpace(kvp.Value))
                        {
                            replyText = kvp.Value;
                            break;
                        }
                    }
                }
            }

            arguments.TryGetValue("actionType", out string? actionType);
            arguments.TryGetValue("notificationKey", out string? notificationKey);
            arguments.TryGetValue("actionIndex", out string? actionIndexStr);
            arguments.TryGetValue("resultKey", out string? resultKey);
            int.TryParse(actionIndexStr, out int actionIndex);

            if (string.IsNullOrEmpty(actionType) && !string.IsNullOrWhiteSpace(replyText))
            {
                actionType = "REPLY";
            }
            if (string.IsNullOrEmpty(notificationKey))
            {
                notificationKey = CommandReceiverService.LastNotificationKey;
            }
            if (string.IsNullOrEmpty(resultKey))
            {
                resultKey = "direct_reply_input";
            }

            return await DispatchNotificationActionAsync(actionType ?? "", notificationKey ?? "", actionIndex, resultKey ?? "", replyText);
        }

        private static async Task<bool> HandleToastNotificationActivatedAsync(global::Windows.ApplicationModel.Activation.ToastNotificationActivatedEventArgs args)
        {
            AppLogger.Log("ToastActivation", $"RAW args.Argument='{args.Argument}'");
            string replyText = "";
            if (args.UserInput != null)
            {
                AppLogger.Log("ToastActivation", $"UserInput count={args.UserInput.Count}: {string.Join("; ", args.UserInput.Select(kv => $"{kv.Key}='{kv.Value}'"))}");
                if (args.UserInput.ContainsKey("replyText"))
                {
                    replyText = args.UserInput["replyText"]?.ToString() ?? "";
                }
                if (string.IsNullOrWhiteSpace(replyText))
                {
                    foreach (var kvp in args.UserInput)
                    {
                        string val = kvp.Value?.ToString() ?? "";
                        if (!string.IsNullOrWhiteSpace(val))
                        {
                            replyText = val;
                            break;
                        }
                    }
                }
            }

            var dict = ParseQueryString(args.Argument);
            dict.TryGetValue("actionType", out string? actionType);
            dict.TryGetValue("notificationKey", out string? notificationKey);
            dict.TryGetValue("actionIndex", out string? actionIndexStr);
            dict.TryGetValue("resultKey", out string? resultKey);
            int.TryParse(actionIndexStr, out int actionIndex);

            if (string.IsNullOrEmpty(actionType) && !string.IsNullOrWhiteSpace(replyText))
            {
                actionType = "REPLY";
            }
            if (string.IsNullOrEmpty(notificationKey))
            {
                notificationKey = CommandReceiverService.LastNotificationKey;
                AppLogger.Log("ToastActivation", $"notificationKey empty in arguments, using CommandReceiverService.LastNotificationKey: '{notificationKey}'");
            }
            if (string.IsNullOrEmpty(resultKey))
            {
                resultKey = "direct_reply_input";
            }

            return await DispatchNotificationActionAsync(actionType ?? "", notificationKey ?? "", actionIndex, resultKey ?? "", replyText);
        }

        public static async Task<bool> DispatchNotificationActionAsync(
            string actionType,
            string notificationKey,
            int actionIndex,
            string resultKey,
            string replyText)
        {
            try
            {
                AppLogger.Log("NotificationActivation", $"DispatchNotificationActionAsync called: actionType='{actionType}', notifKey='{notificationKey}', actionIndex={actionIndex}, resultKey='{resultKey}', replyText='{replyText}'");

                while (!string.IsNullOrEmpty(notificationKey) && notificationKey.Contains("%"))
                {
                    try
                    {
                        string unescaped = Uri.UnescapeDataString(notificationKey);
                        if (unescaped == notificationKey) break;
                        notificationKey = unescaped;
                    }
                    catch { break; }
                }

                while (!string.IsNullOrEmpty(resultKey) && resultKey.Contains("%"))
                {
                    try
                    {
                        string unescaped = Uri.UnescapeDataString(resultKey);
                        if (unescaped == resultKey) break;
                        resultKey = unescaped;
                    }
                    catch { break; }
                }

                string dedupeKey = $"{actionType}_{notificationKey}_{actionIndex}_{replyText}";
                if (!ShouldDispatch(dedupeKey))
                {
                    AppLogger.Log("NotificationActivation", $"Suppressed duplicate activation: {dedupeKey}");
                    return true; // Already processed!
                }

                if (string.Equals(actionType, "REPLY", StringComparison.OrdinalIgnoreCase))
                {
                    if (string.IsNullOrWhiteSpace(replyText))
                    {
                        AppLogger.Log("NotificationActivation", "Empty reply text, ignoring reply.");
                        return false;
                    }

                    var payload = new JsonObject
                    {
                        ["type"] = "NOTIFICATION_REPLY",
                        ["Type"] = "NOTIFICATION_REPLY",
                        ["notificationKey"] = notificationKey ?? "",
                        ["NotificationKey"] = notificationKey ?? "",
                        ["actionIndex"] = actionIndex,
                        ["ActionIndex"] = actionIndex,
                        ["resultKey"] = resultKey ?? "",
                        ["ResultKey"] = resultKey ?? "",
                        ["replyText"] = replyText,
                        ["ReplyText"] = replyText
                    };

                    AppLogger.Log("NotificationActivation", $"Dispatching inline reply for {notificationKey}: {replyText}");
                    bool sent = await CommandReceiverService.SendCommandAsync(payload.ToJsonString());
                    AppLogger.Log("NotificationActivation", $"Inline reply send result: {sent}");
                    return sent;
                }
                else if (string.Equals(actionType, "BUTTON", StringComparison.OrdinalIgnoreCase))
                {
                    var payload = new JsonObject
                    {
                        ["type"] = "NOTIFICATION_ACTION",
                        ["Type"] = "NOTIFICATION_ACTION",
                        ["notificationKey"] = notificationKey ?? "",
                        ["NotificationKey"] = notificationKey ?? "",
                        ["actionIndex"] = actionIndex,
                        ["ActionIndex"] = actionIndex
                    };

                    AppLogger.Log("NotificationActivation", $"Dispatching action button {actionIndex} for {notificationKey}");
                    bool sent = await CommandReceiverService.SendCommandAsync(payload.ToJsonString());
                    AppLogger.Log("NotificationActivation", $"Action button send result: {sent}");
                    return sent;
                }
                else if (string.Equals(actionType, "CALL_ANSWER", StringComparison.OrdinalIgnoreCase))
                {
                    AppLogger.Log("CallActivation", "User clicked 'Answer on PC' on toast.");
                    // Call audio will be routed via Bluetooth SCO/HFP from Android
                    try
                    {
                        await Microsoft.Windows.AppNotifications.AppNotificationManager.Default.RemoveByTagAsync("active_call");
                    }
                    catch { }
                    return await CommandReceiverService.SendCallCommandAsync("CALL_ANSWER");
                }
                else if (string.Equals(actionType, "CALL_REJECT", StringComparison.OrdinalIgnoreCase))
                {
                    AppLogger.Log("CallActivation", "User clicked 'Decline' on toast.");
                    try
                    {
                        await Microsoft.Windows.AppNotifications.AppNotificationManager.Default.RemoveByTagAsync("active_call");
                    }
                    catch { }
                    return await CommandReceiverService.SendCallCommandAsync("CALL_REJECT");
                }
                else
                {
                    AppLogger.Log("NotificationActivation", $"Unrecognized actionType: '{actionType}'");
                    return false;
                }
            }
            catch (Exception ex)
            {
                AppLogger.Log("NotificationActivation", $"Error in DispatchNotificationActionAsync: {ex.Message}");
                return false;
            }
        }

        private static Dictionary<string, string> ParseQueryString(string query)
        {
            var dict = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            if (string.IsNullOrWhiteSpace(query)) return dict;

            if (query.StartsWith("?")) query = query.Substring(1);
            var pairs = query.Split(new char[] { '&', ';' }, StringSplitOptions.RemoveEmptyEntries);
            foreach (var pair in pairs)
            {
                int eqIdx = pair.IndexOf('=');
                if (eqIdx > 0)
                {
                    string k = pair.Substring(0, eqIdx).Trim();
                    string v = pair.Substring(eqIdx + 1).Trim();
                    try
                    {
                        while (k.Contains("%"))
                        {
                            string un = Uri.UnescapeDataString(k);
                            if (un == k) break;
                            k = un;
                        }
                    }
                    catch { }
                    try
                    {
                        while (v.Contains("%"))
                        {
                            string un = Uri.UnescapeDataString(v);
                            if (un == v) break;
                            v = un;
                        }
                    }
                    catch { }
                    dict[k] = v;
                }
                else
                {
                    dict[pair.Trim()] = "";
                }
            }
            return dict;
        }
    }
}