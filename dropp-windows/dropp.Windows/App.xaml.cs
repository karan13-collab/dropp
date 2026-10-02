using dropp.Windows.Core.Networking;
using dropp.Windows.Services;
using Microsoft.UI.Xaml;
using System;
using System.Diagnostics;
using System.Threading.Tasks;
using Microsoft.Windows.AppLifecycle;

namespace dropp.Windows
{
    public partial class App : Application
    {
        public static ClipboardSyncService? ClipboardService { get; private set; }
        public static FileTransferService? FileTransfer { get; private set; }
        public static CommandReceiverService? CommandReceiver { get; private set; }

        private Window? m_window;
        private MdnsManager? _mdnsManager;

        public App()
        {
            this.InitializeComponent();
        }

        protected override void OnLaunched(Microsoft.UI.Xaml.LaunchActivatedEventArgs args)
        {
            var mainInstance = AppInstance.FindOrRegisterForKey("main");
            var activatedArgs = AppInstance.GetCurrent().GetActivatedEventArgs();

            if (!mainInstance.IsCurrent)
            {
                // We are the ghost instance! Forward the payload and die safely.
                try
                {
                    mainInstance.RedirectActivationToAsync(activatedArgs).AsTask().Wait();
                }
                catch { /* Ignore ghost failures */ }

                Environment.Exit(0);
                return;
            }


            // --- 2. CATCH THE REPLY ---
            mainInstance.Activated += (sender, incomingArgs) =>
            {
                Debug.WriteLine($"\n=======================================");
                Debug.WriteLine($"[ACTIVATION RECEIVED] Kind: {incomingArgs.Kind}");

                if (incomingArgs.Data is global::Windows.ApplicationModel.Activation.ToastNotificationActivatedEventArgs toastArgs)
                {
                    // 1. Remove the "action=" prefix
                    string rawAction = toastArgs.Argument.Replace("action=", "");

                    // 2. Decode the URL encoding (Converts %3D back into =)
                    string actionId = Uri.UnescapeDataString(rawAction);
                    string replyText = "";

                    // 3. Extract the text
                    foreach (var input in toastArgs.UserInput)
                    {
                        if (input.Key == actionId)
                        {
                            replyText = input.Value?.ToString() ?? "";
                        }
                    }

                    if (!string.IsNullOrEmpty(actionId) && !string.IsNullOrEmpty(replyText))
                    {
                        var replyJson = new System.Text.Json.Nodes.JsonObject
                        {
                            ["Type"] = "REPLY",
                            ["ActionId"] = actionId,
                            ["Text"] = replyText
                        };
                        string jsonString = replyJson.ToJsonString();
                        Debug.WriteLine($"[JSON Payload] {jsonString}");

                        // --- NEW: Send it back to Android on Port 8082 ---
                        _ = Task.Run(async () =>
                        {
                            try
                            {
                                // We pull the Android IP that was discovered earlier by mDNS
                                string targetIp = FileTransfer?._targetIp ?? "";
                                if (string.IsNullOrEmpty(targetIp)) return;

                                using (var client = new System.Net.Sockets.TcpClient())
                                {
                                    await client.ConnectAsync(targetIp, 8082); // Connect to Android
                                    using (var writer = new System.IO.StreamWriter(client.GetStream()))
                                    {
                                        await writer.WriteLineAsync(jsonString); // Beam the text!
                                    }
                                }
                                Debug.WriteLine("[Reply Sent] Successfully beamed reply to Android!");
                            }
                            catch (Exception ex)
                            {
                                Debug.WriteLine($"[Reply Send Error] {ex.Message}");
                            }
                        });
                    }
                }
                Debug.WriteLine($"=======================================\n");
            };

            Debug.WriteLine("[App] Main instance armed and waiting for Reply clicks...");

            m_window = new MainWindow();

            ClipboardService = new ClipboardSyncService(m_window.DispatcherQueue);
            FileTransfer = new FileTransferService();
            CommandReceiver = new CommandReceiverService();

            ClipboardService.Start();
            FileTransfer?.Start();
            CommandReceiver.Start();

            m_window.Activate();

            _mdnsManager = new MdnsManager();

            _ = Task.Run(async () =>
            {
                Debug.WriteLine("Starting mDNS discovery and broadcast for dropp ecosystem...");
                _mdnsManager.StartBroadcasting();
                await _mdnsManager.StartListeningAsync();
            });

            // --- NATIVE WINDOWS SHARE TARGET HANDLING ---
            if (activatedArgs.Kind == ExtendedActivationKind.ShareTarget)
            {
                var shareArgs = activatedArgs.Data as global::Windows.ApplicationModel.Activation.ShareTargetActivatedEventArgs;
                if (shareArgs != null)
                {
                    var shareOperation = shareArgs.ShareOperation;
                    shareOperation.ReportStarted();

                    _ = Task.Run(async () =>
                    {
                        try
                        {
                            if (shareOperation.Data.Contains(global::Windows.ApplicationModel.DataTransfer.StandardDataFormats.StorageItems))
                            {
                                var items = await shareOperation.Data.GetStorageItemsAsync();
                                foreach (var item in items)
                                {
                                    if (item is global::Windows.Storage.StorageFile file)
                                    {
                                        Debug.WriteLine($"[ShareTarget] Received {file.Name} from Windows Share menu.");
                                        var tempFolder = global::Windows.Storage.ApplicationData.Current.TemporaryFolder;
                                        var tempFile = await file.CopyAsync(tempFolder, file.Name, global::Windows.Storage.NameCollisionOption.ReplaceExisting);
                                        await Task.Delay(3000);
                                        if (FileTransfer != null) await FileTransfer.SendFileAsync(tempFile.Path);
                                    }
                                }
                            }
                        }
                        catch (Exception ex)
                        {
                            Debug.WriteLine($"[ShareTarget] Error: {ex.Message}");
                        }
                        finally
                        {
                            shareOperation.ReportCompleted();
                        }
                    });
                }
            }
        }
    }
}