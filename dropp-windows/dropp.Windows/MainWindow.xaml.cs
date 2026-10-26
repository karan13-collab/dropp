using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using QRCoder;
using System;
using System.Diagnostics;
using System.IO;
using System.Threading.Tasks;
using System.Windows.Input;
using Windows.Storage.Pickers;
using dropp.Windows.Core.Diagnostics;
using dropp.Windows.Services;

namespace dropp.Windows
{
    public sealed partial class MainWindow : Window
    {
        private AppWindow? _appWindow;
        private readonly DispatcherTimer _callTimer;
        private DateTime _callStartTime;

        public ICommand TrayDoubleClickCommand { get; }
        public ICommand OpenAppCommand { get; }
        public ICommand QuitAppCommand { get; }

        public MainWindow()
        {
            TrayDoubleClickCommand = new StandardCommand(() =>
            {
                _appWindow?.Show();
                this.Activate();
            });

            OpenAppCommand = new StandardCommand(() =>
            {
                _appWindow?.Show();
                this.Activate();
            });

            QuitAppCommand = new StandardCommand(() =>
            {
                Environment.Exit(0);
            });

            this.InitializeComponent();
            ShowPairingQrCode();

            IntPtr hWnd = WinRT.Interop.WindowNative.GetWindowHandle(this);
            Microsoft.UI.WindowId windowId = Microsoft.UI.Win32Interop.GetWindowIdFromWindow(hWnd);
            _appWindow = AppWindow.GetFromWindowId(windowId);
            if (_appWindow != null)
            {
                _appWindow.Closing += AppWindow_Closing;
            }

            // Setup Call Timer
            _callTimer = new DispatcherTimer
            {
                Interval = TimeSpan.FromSeconds(1)
            };
            _callTimer.Tick += (s, e) =>
            {
                var elapsed = DateTime.UtcNow - _callStartTime;
                CallDurationText.Text = elapsed.ToString(@"mm\:ss");
            };

            // Subscribe to Call Events and Audio Bridge State
            CommandReceiverService.CallEventReceived += OnCallEventReceived;
            CallAudioBridgeService.Instance.StateChanged += OnAudioBridgeStateChanged;

            // Subscribe to Bluetooth Audio Sink state and start background watcher
            BluetoothAudioSinkService.Instance.SinkStatusChanged += OnSinkStatusChanged;
            _ = BluetoothAudioSinkService.Instance.StartAsync();

            if (Content is FrameworkElement fe)
            {
                fe.Loaded += MainWindow_Loaded;
            }
        }

        private async void MainWindow_Loaded(object sender, RoutedEventArgs e)
        {
            await CheckAndPromptFirstRunSetupAsync();
        }

        private async Task CheckAndPromptFirstRunSetupAsync()
        {
            try
            {
                bool setupNeeded = await FirstRunSetupService.Instance.CheckIfSetupRequiredAsync();
                if (!setupNeeded) return;

                if (Content?.XamlRoot == null) return;

                var dialog = new ContentDialog
                {
                    Title = "One-Time Audio & Network Setup",
                    Content = "To route phone calls, audio, and files seamlessly without manual configuration, Dropp needs one-time administrator permission to enable Windows Bluetooth Audio Gateway services and configure local firewall ports.",
                    PrimaryButtonText = "Grant Permission & Configure",
                    CloseButtonText = "Later",
                    DefaultButton = ContentDialogButton.Primary,
                    XamlRoot = Content.XamlRoot
                };

                var result = await dialog.ShowAsync();
                if (result == ContentDialogResult.Primary)
                {
                    BluetoothSinkStatusText.Text = "Bluetooth Audio Sink: Configuring elevated services...";
                    bool success = await FirstRunSetupService.Instance.RunElevatedSetupScriptAsync();
                    if (success)
                    {
                        AppLogger.Log("UI", "First-Run Setup completed successfully.");
                        BluetoothSinkStatusText.Text = "Bluetooth Audio Sink: Setup complete. Services & ports configured.";
                    }
                }
            }
            catch (Exception ex)
            {
                AppLogger.Log("UI", $"First-Run Setup dialog error: {ex.Message}");
            }
        }


        private void AppWindow_Closing(AppWindow sender, AppWindowClosingEventArgs args)
        {
            args.Cancel = true;
            _appWindow?.Hide();
        }

        private void OnCallEventReceived(object? sender, CallEventArgs e)
        {
            DispatcherQueue.TryEnqueue(() =>
            {
                AppLogger.Log("UI", $"Updating Call UI: State={e.State}, Caller={e.CallerName}");

                if (e.State == "RINGING")
                {
                    CallerNameText.Text = !string.IsNullOrWhiteSpace(e.CallerName) ? e.CallerName : "Incoming Caller";
                    CallerNumberText.Text = !string.IsNullOrWhiteSpace(e.PhoneNumber) ? e.PhoneNumber : "Cellular Call";

                    CallStateBadgeText.Text = "Incoming Call...";
                    try
                    {
                        CallStateBadge.Background = (Brush)Application.Current.Resources["SystemFillColorAttentionBackgroundBrush"];
                    }
                    catch { }

                    AnswerCallButton.Visibility = Visibility.Visible;
                    EndCallButton.Visibility = Visibility.Visible;
                    EndCallButtonText.Text = "Decline";
                    CallDurationText.Visibility = Visibility.Collapsed;

                    // Unhide app window to alert user
                    _appWindow?.Show();
                }
                else if (e.State == "ACTIVE")
                {
                    CallerNameText.Text = !string.IsNullOrWhiteSpace(e.CallerName) ? e.CallerName : "Active Call";
                    CallerNumberText.Text = !string.IsNullOrWhiteSpace(e.PhoneNumber) ? e.PhoneNumber : "Audio Routed to PC";

                    CallStateBadgeText.Text = "Call Active - Audio Routed to PC";
                    try
                    {
                        CallStateBadge.Background = (Brush)Application.Current.Resources["SystemFillColorSuccessBackgroundBrush"];
                    }
                    catch { }

                    AnswerCallButton.Visibility = Visibility.Collapsed;
                    EndCallButton.Visibility = Visibility.Visible;
                    EndCallButtonText.Text = "End Call";
                    ToggleBridgeText.Text = "Stop Bridge";

                    _callStartTime = DateTime.UtcNow;
                    CallDurationText.Text = "00:00";
                    CallDurationText.Visibility = Visibility.Visible;
                    _callTimer.Start();
                }
                else if (e.State == "ENDED")
                {
                    _callTimer.Stop();
                    CallStateBadgeText.Text = "Call Ended";
                    try
                    {
                        CallStateBadge.Background = (Brush)Application.Current.Resources["SystemFillColorNeutralBackgroundBrush"];
                    }
                    catch { }

                    AnswerCallButton.Visibility = Visibility.Collapsed;
                    EndCallButton.Visibility = Visibility.Collapsed;
                    ToggleBridgeText.Text = "Start Bridge";

                    // Reset UI after 4 seconds
                    var resetTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(4) };
                    resetTimer.Tick += (s2, e2) =>
                    {
                        resetTimer.Stop();
                        if (CallStateBadgeText.Text == "Call Ended")
                        {
                            CallStateBadgeText.Text = "No Active Call";
                            CallerNameText.Text = "Ready for Calls";
                            CallerNumberText.Text = "Cellular audio will stream to PC";
                            CallDurationText.Visibility = Visibility.Collapsed;
                        }
                    };
                    resetTimer.Start();
                }
            });
        }

        private void OnAudioBridgeStateChanged(bool isConnected, string statusMessage)
        {
            DispatcherQueue.TryEnqueue(() =>
            {
                AudioBridgeStatusText.Text = $"Audio Bridge: {statusMessage}";
            });
        }

        private async void AnswerCallButton_Click(object sender, RoutedEventArgs e)
        {
            AppLogger.Log("UI", "User clicked Answer on PC");
            // Call audio will route via Bluetooth SCO/HFP from Android
            await CommandReceiverService.SendCallCommandAsync("CALL_ANSWER");
        }

        private async void EndCallButton_Click(object sender, RoutedEventArgs e)
        {
            AppLogger.Log("UI", "User clicked End/Decline Call");
            await CommandReceiverService.SendCallCommandAsync("CALL_END");
        }

        private async void MuteMicToggle_Click(object sender, RoutedEventArgs e)
        {
            bool isMuted = MuteMicToggle.IsChecked == true;
            MuteMicText.Text = isMuted ? "Unmute Mic" : "Mute Mic";
            MuteMicIcon.Glyph = isMuted ? "\uE74F" : "\uE720";
            AppLogger.Log("UI", $"Mic Mute toggled: {isMuted}");
            // Send mute command to Android to toggle mic mute on Bluetooth SCO
            await CommandReceiverService.SendCallCommandAsync(isMuted ? "CALL_MIC_MUTE" : "CALL_MIC_UNMUTE");
        }

        private async void RouteAudioButton_Click(object sender, RoutedEventArgs e)
        {
            BluetoothAudioSinkService.Instance.ToggleAudioRouting();
            bool isRouting = BluetoothAudioSinkService.Instance.IsRoutingEnabled;
            UpdateRouteAudioButtonUI(isRouting);
            await CommandReceiverService.SendAudioRoutingStatusAsync(isRouting);
        }

        private void OnSinkStatusChanged(bool isActive, string statusMessage)
        {
            DispatcherQueue.TryEnqueue(() =>
            {
                BluetoothSinkStatusText.Text = $"Bluetooth Audio: {statusMessage}";
                UpdateRouteAudioButtonUI(BluetoothAudioSinkService.Instance.IsRoutingEnabled);
            });
        }

        private void UpdateRouteAudioButtonUI(bool isRouting)
        {
            DispatcherQueue.TryEnqueue(() =>
            {
                RouteAudioText.Text = isRouting ? "Stop Routing Audio" : "Route Phone Audio to PC";
                BluetoothSinkStatusText.Text = isRouting
                    ? "Bluetooth Audio: Active (Routing to PC speakers)"
                    : "Bluetooth Audio: Standby (Audio playing on phone)";
            });
        }

        private async void ToggleBridgeButton_Click(object sender, RoutedEventArgs e)
        {
            // Toggle the Bluetooth SCO audio bridge on Android
            await CommandReceiverService.SendCallCommandAsync(
                ToggleBridgeText.Text == "Stop Bridge" ? "CALL_AUDIO_STOP" : "CALL_AUDIO_START");
            ToggleBridgeText.Text = ToggleBridgeText.Text == "Stop Bridge" ? "Start Bridge" : "Stop Bridge";
        }

        private async void TestBridgeButton_Click(object sender, RoutedEventArgs e)
        {
            AppLogger.Log("UI", "Requesting Android to start Bluetooth SCO audio bridge...");
            AudioBridgeStatusText.Text = "Audio Bridge: Requesting Bluetooth SCO from phone...";
            bool sent = await CommandReceiverService.SendCallCommandAsync("CALL_AUDIO_START");
            if (sent)
            {
                AudioBridgeStatusText.Text = "Audio Bridge: Bluetooth SCO start command sent to phone.";
            }
            else
            {
                AudioBridgeStatusText.Text = "Audio Bridge: Could not reach Android. Ensure phone is paired and on Wi-Fi.";
            }
        }

        private async void RerunSetupButton_Click(object sender, RoutedEventArgs e)
        {
            AppLogger.Log("UI", "User clicked Setup Audio & Ports button.");
            BluetoothSinkStatusText.Text = "Bluetooth Audio Sink: Configuring elevated services & firewall...";
            bool success = await FirstRunSetupService.Instance.RunElevatedSetupScriptAsync();
            if (success)
            {
                BluetoothSinkStatusText.Text = "Bluetooth Audio Sink: Setup complete. Services & ports configured.";
            }
            else
            {
                BluetoothSinkStatusText.Text = "Bluetooth Audio Sink: Setup canceled or failed.";
            }
        }

        private void ShowPairingQrCode()
        {
            QRCodeGenerator qrGenerator = new QRCodeGenerator();
            QRCodeData qrCodeData = qrGenerator.CreateQrCode(App.MasterKey, QRCodeGenerator.ECCLevel.L);
            PngByteQRCode qrCode = new PngByteQRCode(qrCodeData);
            byte[] qrCodeBytes = qrCode.GetGraphic(20);

            string tempFile = Path.Combine(global::Windows.Storage.ApplicationData.Current.TemporaryFolder.Path, "pairing_qr.png");
            File.WriteAllBytes(tempFile, qrCodeBytes);

            QrCodeImage.Source = new BitmapImage(new Uri(tempFile));
        }

        private async void TestSendFile_Click(object sender, RoutedEventArgs e)
        {
            var picker = new FileOpenPicker();
            picker.ViewMode = PickerViewMode.Thumbnail;
            picker.SuggestedStartLocation = PickerLocationId.Desktop;
            picker.FileTypeFilter.Add("*");

            var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(this);
            WinRT.Interop.InitializeWithWindow.Initialize(picker, hwnd);

            var file = await picker.PickSingleFileAsync();
            if (file != null)
            {
                Debug.WriteLine($"[UI] User selected: {file.Name}");
                await App.FileTransfer.SendFileAsync(file.Path);
            }
            else
            {
                Debug.WriteLine("[UI] User canceled file selection.");
            }
        }
    }

    public class StandardCommand : ICommand
    {
        private readonly Action _action;
        public StandardCommand(Action action) { _action = action; }

#pragma warning disable CS0067
        public event EventHandler? CanExecuteChanged;
#pragma warning restore CS0067

        public bool CanExecute(object? parameter) => true;
        public void Execute(object? parameter) => _action();
    }
}