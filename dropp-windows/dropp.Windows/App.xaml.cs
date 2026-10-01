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
        // Adding the '?' (nullable) magically fixes all those CS8618 warnings!
        public static ClipboardSyncService? ClipboardService { get; private set; }
        public static FileTransferService? FileTransfer { get; private set; }

        private Window? m_window;
        private MdnsManager? _mdnsManager;

        public App()
        {
            this.InitializeComponent();
        }

        protected override void OnLaunched(Microsoft.UI.Xaml.LaunchActivatedEventArgs args)
        {
            m_window = new MainWindow();

            // 1. Initialize both of our ecosystem services
            ClipboardService = new ClipboardSyncService(m_window.DispatcherQueue);
            FileTransfer = new FileTransferService();

            // 2. Start the clipboard background watcher
            ClipboardService.Start();
            FileTransfer?.Start();
            m_window.Activate();

            // 3. Initialize your Networking manager
            _mdnsManager = new MdnsManager();

            // 4. Fire and forget the background network discovery
            _ = Task.Run(async () =>
            {
                Debug.WriteLine("Starting mDNS discovery and broadcast for dropp ecosystem...");
                _mdnsManager.StartBroadcasting();
                await _mdnsManager.StartListeningAsync();
            });

            // --- NATIVE WINDOWS SHARE TARGET HANDLING ---
            var activatedArgs = AppInstance.GetCurrent().GetActivatedEventArgs();
            if (activatedArgs.Kind == ExtendedActivationKind.ShareTarget)
            {
                // The 'global::' prefix fixes the namespace collision!
                var shareArgs = activatedArgs.Data as global::Windows.ApplicationModel.Activation.ShareTargetActivatedEventArgs;
                if (shareArgs != null)
                {
                    var shareOperation = shareArgs.ShareOperation;
                    shareOperation.ReportStarted(); // Tells Windows UI we are processing it

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

                                        // 1. BYPASS THE SANDBOX: Copy the securely shared file to our app's Temp folder so C# can read it
                                        var tempFolder = global::Windows.Storage.ApplicationData.Current.TemporaryFolder;
                                        var tempFile = await file.CopyAsync(tempFolder, file.Name, global::Windows.Storage.NameCollisionOption.ReplaceExisting);

                                        // 2. BYPASS THE RACE CONDITION: Give mDNS 3 seconds to find the Android phone before sending
                                        await Task.Delay(3000);

                                        if (FileTransfer != null)
                                        {
                                            // 3. Send the copied Temp file!
                                            await FileTransfer.SendFileAsync(tempFile.Path);
                                        }
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
                            // Tells Windows we are done so it can close the Share UI overlay
                            shareOperation.ReportCompleted();
                        }
                    });
                }
            }
        }
    }
}