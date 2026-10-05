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

        // This holds our AES key in memory for the other services to read
        public static string MasterKey { get; private set; } = "";

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
                try
                {
                    mainInstance.RedirectActivationToAsync(activatedArgs).AsTask().Wait();
                }
                catch { }
                Environment.Exit(0);
                return;
            }

            // --- SECURITY FIX: Save key to a physical file to guarantee it works ---
            string keyFilePath = System.IO.Path.Combine(global::Windows.Storage.ApplicationData.Current.LocalFolder.Path, "masterkey.txt");
            if (!System.IO.File.Exists(keyFilePath))
            {
                System.IO.File.WriteAllText(keyFilePath, Core.Security.EncryptionHelper.GeneratePairingKey());
            }
            MasterKey = System.IO.File.ReadAllText(keyFilePath).Trim();

            // --- CATCH THE REPLY ---
            mainInstance.Activated += (sender, incomingArgs) =>
            {
                if (incomingArgs.Data is global::Windows.ApplicationModel.Activation.ToastNotificationActivatedEventArgs toastArgs)
                {
                    string rawAction = toastArgs.Argument.Replace("action=", "");
                    string actionId = Uri.UnescapeDataString(rawAction);
                    string replyText = "";

                    foreach (var input in toastArgs.UserInput)
                    {
                        if (input.Key == actionId) replyText = input.Value?.ToString() ?? "";
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

                        _ = Task.Run(async () =>
                        {
                            try
                            {
                                string targetIp = FileTransfer?._targetIp ?? "";
                                if (string.IsNullOrEmpty(targetIp)) return;

                                using (var client = new System.Net.Sockets.TcpClient())
                                {
                                    await client.ConnectAsync(targetIp, 8082);
                                    using (var writer = new System.IO.StreamWriter(client.GetStream()))
                                    {
                                        string encryptedPayload = Core.Security.EncryptionHelper.Encrypt(jsonString, MasterKey);
                                        await writer.WriteLineAsync(encryptedPayload);
                                    }
                                }
                            }
                            catch { }
                        });
                    }
                }
            };

            m_window = new MainWindow();

            ClipboardService = new ClipboardSyncService(m_window.DispatcherQueue);
            FileTransfer = new FileTransferService();
            CommandReceiver = new CommandReceiverService();

            ClipboardService.Start();
            FileTransfer?.Start();
            CommandReceiver.Start();

            m_window.Activate();

            // --- PRINT KEY AFTER WINDOW IS ACTIVE ---
            _ = Task.Run(async () =>
            {
                await Task.Delay(2000); // Wait 2 seconds for VS to fully attach
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

            // ... (Native Windows Share Target Handling block stays exactly the same below this) ...
        }
        }
    }