using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using QRCoder;
using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
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

        private IntPtr _hWnd;
        private readonly ObservableCollection<RemoteBrowserItem> _displayedItems = new();
        private List<RemoteBrowserItem> _cachedDirectoryItems = new();
        private string _currentRemotePath = "ROOT";
        private string? _parentRemotePath = null;
        private bool _isGlobalSearchActive = false;
        private readonly DispatcherTimer _searchDebounceTimer;
        private string _selectedFilter = "ALL";

        private readonly ObservableCollection<GalleryItem> _galleryItems = new();
        private string _selectedGalleryBucket = "CAMERA";

        private SoftwareBitmapSource? _webcamSource;
        private bool _isUpdatingWebcamPreview = false;

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
            _hWnd = hWnd;
            Microsoft.UI.WindowId windowId = Microsoft.UI.Win32Interop.GetWindowIdFromWindow(hWnd);
            _appWindow = AppWindow.GetFromWindowId(windowId);
            if (_appWindow != null)
            {
                _appWindow.Closing += AppWindow_Closing;
                _appWindow.Show();
            }
            ShowWindow(_hWnd, SW_RESTORE);
            SetForegroundWindow(_hWnd);

            // Setup Cross-Device File Explorer & Remote Search
            SearchResultsList.ItemsSource = _displayedItems;
            _searchDebounceTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(400) };
            _searchDebounceTimer.Tick += SearchDebounceTimer_Tick;
            SearchService.Instance.RemoteResultsReceived += OnRemoteResultsReceived;
            FileBrowserService.Instance.RemoteDirectoryChanged += OnRemoteDirectoryChanged;

            if (FilterAll != null)
            {
                FilterAll.IsChecked = true;
            }

            // Setup Cross-Device Gallery Preview
            GalleryGridView.ItemsSource = _galleryItems;
            GalleryService.Instance.GalleryReceived += OnGalleryReceived;
            GalleryService.Instance.AlbumsReceived += OnGalleryAlbumsReceived;
            _ = GalleryService.Instance.RequestAlbumsAsync();
            _ = GalleryService.Instance.RequestGalleryAsync("ALL", 60);

            // Load initial Android library root
            _ = FileBrowserService.Instance.BrowseRemoteAsync("ROOT");

            // Register Global Hotkeys (Win+Shift+D and Alt+Space)
            RegisterGlobalHotkeys();

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
            BluetoothHandsFreeService.Instance.HfpStatusChanged += OnHfpStatusChanged;

            // Subscribe to Bluetooth Audio Sink state and start background watcher
            BluetoothAudioSinkService.Instance.SinkStatusChanged += OnSinkStatusChanged;
            _ = BluetoothAudioSinkService.Instance.StartAsync();

            // Setup High-Res Phone Webcam
            WebcamService.Instance.LensesReceived += OnWebcamLensesReceived;
            WebcamService.Instance.FrameReceived += OnWebcamFrameReceived;
            WebcamService.Instance.StreamingStateChanged += OnWebcamStreamingStateChanged;
            WebcamService.Instance.StatusChanged += OnWebcamStatusChanged;
            WebcamService.Instance.FpsUpdated += OnWebcamFpsUpdated;

            if (Content is FrameworkElement fe)
            {
                fe.Loaded += MainWindow_Loaded;
            }
        }

        private async void MainWindow_Loaded(object sender, RoutedEventArgs e)
        {
            await CheckAndPromptFirstRunSetupAsync();
            _ = WebcamService.Instance.RequestLensesAsync();
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
            // Show or update the Phone Link-style always-on-top Call Overlay Window
            UI.CallOverlayWindow.ShowOrUpdate(DispatcherQueue, e);

            DispatcherQueue.TryEnqueue(() =>
            {
                AppLogger.Log("UI", $"Updating Call UI: State={e.State}, App={e.AppName}, Type={e.CallType}, Caller={e.CallerName}");

                string appLabel = !string.IsNullOrWhiteSpace(e.AppName)
                    ? $"{e.AppName} Call"
                    : (e.CallType == "VOIP" ? "VoIP Call" : "Cellular Call");

                if (e.State == "RINGING")
                {
                    CallerNameText.Text = !string.IsNullOrWhiteSpace(e.CallerName) ? e.CallerName : "Incoming Caller";
                    CallerNumberText.Text = !string.IsNullOrWhiteSpace(e.PhoneNumber) && e.PhoneNumber != e.CallerName
                        ? $"{appLabel} • {e.PhoneNumber}"
                        : $"Incoming {appLabel}";

                    CallStateBadgeText.Text = $"Incoming {appLabel}...";
                    try
                    {
                        CallStateBadge.Background = (Brush)Application.Current.Resources["SystemFillColorAttentionBackgroundBrush"];
                    }
                    catch { }

                    AnswerCallButton.Visibility = Visibility.Visible;
                    EndCallButton.Visibility = Visibility.Visible;
                    EndCallButtonText.Text = "Decline";
                    CallDurationText.Visibility = Visibility.Collapsed;
                    _callTimer.Stop();
                }
                else if (e.State == "ACTIVE")
                {
                    CallerNameText.Text = !string.IsNullOrWhiteSpace(e.CallerName) ? e.CallerName : "Active Call";
                    CallerNumberText.Text = !string.IsNullOrWhiteSpace(e.PhoneNumber) && e.PhoneNumber != e.CallerName
                        ? $"{appLabel} • {e.PhoneNumber}"
                        : $"{appLabel} • Audio Routed to PC";

                    CallStateBadgeText.Text = $"{appLabel} Active - Audio on PC";
                    try
                    {
                        CallStateBadge.Background = (Brush)Application.Current.Resources["SystemFillColorSuccessBackgroundBrush"];
                    }
                    catch { }

                    AnswerCallButton.Visibility = Visibility.Collapsed;
                    EndCallButton.Visibility = Visibility.Visible;
                    EndCallButtonText.Text = "End Call";
                    ToggleBridgeText.Text = "Transfer to Phone";

                    if (!_callTimer.IsEnabled)
                    {
                        _callStartTime = DateTime.UtcNow;
                        CallDurationText.Text = "00:00";
                        _callTimer.Start();
                    }
                    CallDurationText.Visibility = Visibility.Visible;
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
                            CallerNumberText.Text = "Cellular & VoIP calls (WhatsApp, Telegram, etc.) stream to PC";
                            CallDurationText.Visibility = Visibility.Collapsed;
                        }
                    };
                    resetTimer.Start();
                }
            });
        }

        private void OnHfpStatusChanged(bool isConnected, string statusMessage)
        {
            DispatcherQueue.TryEnqueue(() =>
            {
                AudioBridgeStatusText.Text = $"Call Audio (HFP): {statusMessage}";
            });
        }

        private void OnAudioBridgeStateChanged(bool isConnected, string statusMessage)
        {
            DispatcherQueue.TryEnqueue(() =>
            {
                if (isConnected)
                {
                    AudioBridgeStatusText.Text = $"Audio Bridge: {statusMessage}";
                }
            });
        }

        private async void AnswerCallButton_Click(object sender, RoutedEventArgs e)
        {
            AppLogger.Log("UI", "User clicked Answer on PC");
            _ = BluetoothHandsFreeService.Instance.EnsureHfpConnectedAsync();
            try { await Microsoft.Windows.AppNotifications.AppNotificationManager.Default.RemoveByTagAsync("active_call"); } catch { }
            await CommandReceiverService.SendCallCommandAsync("CALL_ANSWER");
        }

        private async void EndCallButton_Click(object sender, RoutedEventArgs e)
        {
            AppLogger.Log("UI", "User clicked End/Decline Call");
            CommandReceiverService.NotifyLocalCallEnded();
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
            bool currentlyOnPc = ToggleBridgeText.Text == "Stop Bridge" || ToggleBridgeText.Text == "Transfer to Phone";
            if (!currentlyOnPc)
            {
                _ = BluetoothHandsFreeService.Instance.EnsureHfpConnectedAsync();
            }
            await CommandReceiverService.SendCallCommandAsync(currentlyOnPc ? "CALL_AUDIO_STOP" : "CALL_AUDIO_START");
            ToggleBridgeText.Text = currentlyOnPc ? "Transfer to PC" : "Transfer to Phone";
        }

        private async void TestBridgeButton_Click(object sender, RoutedEventArgs e)
        {
            AppLogger.Log("UI", "Connecting Windows HFP Hands-Free & requesting Android Bluetooth SCO...");
            AudioBridgeStatusText.Text = "Call Audio (HFP): Connecting Windows Hands-Free channel...";
            bool hfpOk = await BluetoothHandsFreeService.Instance.EnsureHfpConnectedAsync();
            bool sent = await CommandReceiverService.SendCallCommandAsync("CALL_AUDIO_START");
            if (hfpOk && sent)
            {
                AudioBridgeStatusText.Text = $"Call Audio (HFP): Connected ({BluetoothHandsFreeService.Instance.ActiveDeviceName}) & SCO requested.";
            }
            else if (sent)
            {
                AudioBridgeStatusText.Text = "Call Audio (HFP): SCO command sent to phone (check HFP status in logs).";
            }
            else
            {
                AudioBridgeStatusText.Text = "Call Audio (HFP): Could not reach Android over Wi-Fi.";
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

        #region Global Hotkeys (Win+Shift+D and Alt+Space) & Subclassing
        private delegate IntPtr SubclassProc(IntPtr hWnd, uint uMsg, IntPtr wParam, IntPtr lParam, UIntPtr uIdSubclass, UIntPtr dwRefData);

        [DllImport("comctl32.dll", SetLastError = true)]
        private static extern bool SetWindowSubclass(IntPtr hWnd, SubclassProc pfnSubclass, UIntPtr uIdSubclass, UIntPtr dwRefData);

        [DllImport("comctl32.dll", SetLastError = true)]
        private static extern bool RemoveWindowSubclass(IntPtr hWnd, SubclassProc pfnSubclass, UIntPtr uIdSubclass);

        [DllImport("comctl32.dll", SetLastError = true)]
        private static extern IntPtr DefSubclassProc(IntPtr hWnd, uint uMsg, IntPtr wParam, IntPtr lParam);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool UnregisterHotKey(IntPtr hWnd, int id);

        [DllImport("user32.dll")]
        private static extern bool SetForegroundWindow(IntPtr hWnd);

        [DllImport("user32.dll")]
        private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

        private const int HOTKEY_ID_WIN_SHIFT_D = 9001;
        private const int HOTKEY_ID_ALT_SPACE = 9002;
        private const uint MOD_ALT = 0x0001;
        private const uint MOD_SHIFT = 0x0004;
        private const uint MOD_WIN = 0x0008;
        private const uint MOD_NOREPEAT = 0x4000;
        private const uint VK_SPACE = 0x20;
        private const uint VK_D = 0x44;
        private const uint WM_HOTKEY = 0x0312;
        private const int SW_RESTORE = 9;

        private SubclassProc? _subclassProc;

        private void RegisterGlobalHotkeys()
        {
            try
            {
                _subclassProc = new SubclassProc(WndProcSubclass);
                SetWindowSubclass(_hWnd, _subclassProc, new UIntPtr(1), UIntPtr.Zero);

                // Win + Shift + D
                bool winD = RegisterHotKey(_hWnd, HOTKEY_ID_WIN_SHIFT_D, MOD_WIN | MOD_SHIFT | MOD_NOREPEAT, VK_D);
                // Alt + Space
                bool altSpace = RegisterHotKey(_hWnd, HOTKEY_ID_ALT_SPACE, MOD_ALT | MOD_NOREPEAT, VK_SPACE);

                AppLogger.Log("MainWindow", $"Registered Global Hotkeys: Win+Shift+D={winD}, Alt+Space={altSpace}");
            }
            catch (Exception ex)
            {
                AppLogger.Log("MainWindow", $"Error registering global hotkeys: {ex.Message}");
            }
        }

        private IntPtr WndProcSubclass(IntPtr hWnd, uint uMsg, IntPtr wParam, IntPtr lParam, UIntPtr uIdSubclass, UIntPtr dwRefData)
        {
            if (uMsg == WM_HOTKEY)
            {
                int id = wParam.ToInt32();
                if (id == HOTKEY_ID_WIN_SHIFT_D || id == HOTKEY_ID_ALT_SPACE)
                {
                    DispatcherQueue.TryEnqueue(ActivateAndFocusSearch);
                    return IntPtr.Zero;
                }
            }
            return DefSubclassProc(hWnd, uMsg, wParam, lParam);
        }

        public void BringToForeground()
        {
            DispatcherQueue.TryEnqueue(() =>
            {
                _appWindow?.Show();
                ShowWindow(_hWnd, SW_RESTORE);
                SetForegroundWindow(_hWnd);
                this.Activate();
            });
        }

        private void ActivateAndFocusSearch()
        {
            _appWindow?.Show();
            ShowWindow(_hWnd, SW_RESTORE);
            SetForegroundWindow(_hWnd);
            this.Activate();
            SearchBox.Focus(FocusState.Programmatic);
            SearchBox.SelectAll();
        }
        #endregion

        #region Remote File Explorer & Search UI Logic
        private void NavigateToDirectory(string path)
        {
            SearchProgressBar.Visibility = Visibility.Visible;
            SearchStatusText.Text = $"Browsing {((path == "ROOT") ? "Android Libraries" : path)}...";
            _ = FileBrowserService.Instance.BrowseRemoteAsync(path);
        }

        private void NavRootButton_Click(object sender, RoutedEventArgs e)
        {
            NavigateToDirectory("ROOT");
        }

        private void NavUpButton_Click(object sender, RoutedEventArgs e)
        {
            if (!string.IsNullOrEmpty(_parentRemotePath))
            {
                NavigateToDirectory(_parentRemotePath);
            }
            else if (_currentRemotePath != "ROOT")
            {
                NavigateToDirectory("ROOT");
            }
        }

        private void NavRefreshButton_Click(object sender, RoutedEventArgs e)
        {
            NavigateToDirectory(_currentRemotePath);
        }

        private void QuickLibrary_Click(object sender, RoutedEventArgs e)
        {
            if (sender is FrameworkElement fe && fe.Tag is string tag)
            {
                NavigateToDirectory(tag);
            }
        }

        private void FolderItem_Click(object sender, RoutedEventArgs e)
        {
            if (sender is FrameworkElement fe && fe.Tag is string folderId)
            {
                NavigateToDirectory(folderId);
            }
        }

        private void SearchBox_TextChanged(object sender, TextChangedEventArgs e)
        {
            if (_isGlobalSearchActive)
            {
                _searchDebounceTimer.Stop();
                string query = SearchBox.Text.Trim();
                if (string.IsNullOrEmpty(query))
                {
                    _isGlobalSearchActive = false;
                    ApplyCurrentDirectoryFilter();
                    return;
                }

                SearchProgressBar.Visibility = Visibility.Visible;
                SearchStatusText.Text = $"Searching Android for '{query}' [{_selectedFilter}]...";
                _searchDebounceTimer.Start();
            }
            else
            {
                // Instant in-folder filtering
                ApplyCurrentDirectoryFilter();
            }
        }

        private void SearchBox_KeyDown(object sender, Microsoft.UI.Xaml.Input.KeyRoutedEventArgs e)
        {
            if (e.Key == global::Windows.System.VirtualKey.Enter)
            {
                ExecuteGlobalSearch();
            }
        }

        private void GlobalSearchButton_Click(object sender, RoutedEventArgs e)
        {
            ExecuteGlobalSearch();
        }

        private void ExecuteGlobalSearch()
        {
            string query = SearchBox.Text.Trim();
            if (string.IsNullOrEmpty(query))
            {
                _isGlobalSearchActive = false;
                ApplyCurrentDirectoryFilter();
                return;
            }

            _isGlobalSearchActive = true;
            SearchProgressBar.Visibility = Visibility.Visible;
            SearchStatusText.Text = $"Searching entire Android phone for '{query}' [{_selectedFilter}]...";
            _ = SearchService.Instance.SearchRemoteAsync(query, _selectedFilter);
        }

        private void SearchDebounceTimer_Tick(object? sender, object e)
        {
            _searchDebounceTimer.Stop();
            string query = SearchBox.Text.Trim();
            if (!string.IsNullOrEmpty(query))
            {
                _ = SearchService.Instance.SearchRemoteAsync(query, _selectedFilter);
            }
        }

        private void Filter_Checked(object sender, RoutedEventArgs e)
        {
            if (SearchBox == null || SearchStatusText == null || SearchProgressBar == null) return;

            if (sender is RadioButton rb && rb.IsChecked == true)
            {
                _selectedFilter = rb.Name switch
                {
                    "FilterFolder" => "FOLDER",
                    "FilterPdf" => "PDF",
                    "FilterDoc" => "DOC",
                    "FilterImage" => "IMAGE",
                    "FilterVideo" => "VIDEO",
                    "FilterAudio" => "AUDIO",
                    _ => "ALL"
                };

                if (_isGlobalSearchActive)
                {
                    string query = SearchBox?.Text?.Trim() ?? "";
                    if (!string.IsNullOrEmpty(query))
                    {
                        SearchProgressBar.Visibility = Visibility.Visible;
                        SearchStatusText.Text = $"Searching Android for '{query}' [{_selectedFilter}]...";
                        _ = SearchService.Instance.SearchRemoteAsync(query, _selectedFilter);
                    }
                }
                else
                {
                    ApplyCurrentDirectoryFilter();
                }
            }
        }

        private void ApplyCurrentDirectoryFilter()
        {
            if (SearchStatusText == null || _displayedItems == null || _cachedDirectoryItems == null) return;

            string query = SearchBox?.Text?.Trim() ?? "";

            var filtered = _cachedDirectoryItems.Where(item =>
            {
                // Category filtering
                if (_selectedFilter == "FOLDER" && !item.IsDirectory) return false;
                if (_selectedFilter != "ALL" && _selectedFilter != "FOLDER")
                {
                    if (item.IsDirectory) return false;
                    if (!item.Type.Equals(_selectedFilter, StringComparison.OrdinalIgnoreCase)) return false;
                }

                // Query text matching
                if (!string.IsNullOrEmpty(query))
                {
                    if (!item.Name.Contains(query, StringComparison.OrdinalIgnoreCase)) return false;
                }

                return true;
            }).ToList();

            _displayedItems.Clear();
            foreach (var item in filtered)
            {
                _displayedItems.Add(item);
            }

            if (!string.IsNullOrEmpty(query))
            {
                SearchStatusText.Text = $"{_displayedItems.Count} item(s) matching '{query}' in current folder";
            }
            else
            {
                SearchStatusText.Text = $"{_displayedItems.Count} item(s) in folder";
            }
        }

        private void OnRemoteDirectoryChanged(object? sender, BrowseResponseEventArgs e)
        {
            DispatcherQueue.TryEnqueue(() =>
            {
                SearchProgressBar.Visibility = Visibility.Collapsed;

                if (!string.IsNullOrEmpty(e.ErrorMessage))
                {
                    _displayedItems.Clear();
                    SearchStatusText.Text = e.ErrorMessage;
                    return;
                }

                _isGlobalSearchActive = false;
                _currentRemotePath = e.CurrentPath;
                _parentRemotePath = e.ParentPath;

                CurrentPathText.Text = (e.CurrentPath == "ROOT") ? "Android Libraries" : e.CurrentPath;
                NavUpButton.IsEnabled = !string.IsNullOrEmpty(e.ParentPath) || (e.CurrentPath != "ROOT");

                _cachedDirectoryItems = e.Items;
                ApplyCurrentDirectoryFilter();
            });
        }

        private void OnRemoteResultsReceived(object? sender, SearchResponseEventArgs e)
        {
            DispatcherQueue.TryEnqueue(() =>
            {
                SearchProgressBar.Visibility = Visibility.Collapsed;

                if (!string.IsNullOrEmpty(e.ErrorMessage))
                {
                    _displayedItems.Clear();
                    SearchStatusText.Text = e.ErrorMessage;
                    return;
                }

                _isGlobalSearchActive = true;
                _displayedItems.Clear();
                foreach (var item in e.Results)
                {
                    _displayedItems.Add(new RemoteBrowserItem
                    {
                        Id = item.Id,
                        Name = item.Name,
                        IsDirectory = false,
                        Size = item.Size,
                        Type = item.Type
                    });
                }

                SearchStatusText.Text = e.Results.Count > 0
                    ? $"{e.Results.Count} file{(e.Results.Count > 1 ? "s" : "")} found across Android storage"
                    : "No matching files found on Android phone";
            });
        }

        private async void SearchResultsList_ItemClick(object sender, ItemClickEventArgs e)
        {
            if (e.ClickedItem is RemoteBrowserItem item)
            {
                if (item.IsDirectory)
                {
                    NavigateToDirectory(item.Id);
                }
                else
                {
                    SearchStatusText.Text = $"Streaming remote preview for '{item.Name}'...";
                    await SearchService.Instance.RequestRemoteStreamAsync(item.Id, isPreview: true);
                }
            }
        }

        private async void PreviewItem_Click(object sender, RoutedEventArgs e)
        {
            if (sender is FrameworkElement fe && fe.Tag is string fileId)
            {
                var item = _displayedItems.FirstOrDefault(r => r.Id == fileId)
                           ?? _cachedDirectoryItems.FirstOrDefault(r => r.Id == fileId);
                string name = item?.Name ?? "file";
                SearchStatusText.Text = $"Streaming remote preview for '{name}'...";
                await SearchService.Instance.RequestRemoteStreamAsync(fileId, isPreview: true);
            }
        }

        private async void SaveItem_Click(object sender, RoutedEventArgs e)
        {
            if (sender is FrameworkElement fe && fe.Tag is string fileId)
            {
                var item = _displayedItems.FirstOrDefault(r => r.Id == fileId)
                           ?? _cachedDirectoryItems.FirstOrDefault(r => r.Id == fileId);
                string name = item?.Name ?? "file";
                SearchStatusText.Text = $"Downloading '{name}' to PC Downloads...";
                await SearchService.Instance.RequestRemoteStreamAsync(fileId, isPreview: false);
            }
        }
        #endregion

        #region Android Photo & Video Gallery UI Logic
        private async Task LoadGalleryBucketAsync(string bucket)
        {
            GalleryProgressBar.Visibility = Visibility.Visible;
            GalleryStatusText.Text = $"Loading {bucket} photos from Android phone...";
            await GalleryService.Instance.RequestGalleryAsync(bucket, 60);
        }

        private void GalleryRefreshButton_Click(object sender, RoutedEventArgs e)
        {
            _ = GalleryService.Instance.RequestAlbumsAsync();
            _ = LoadGalleryBucketAsync(_selectedGalleryBucket);
        }

        private void OnGalleryAlbumsReceived(object? sender, List<GalleryAlbumItem> albums)
        {
            DispatcherQueue.TryEnqueue(() =>
            {
                if (GalleryAlbumsPanel == null) return;
                GalleryAlbumsPanel.Children.Clear();

                foreach (var album in albums)
                {
                    var btn = new Button
                    {
                        Content = album.DisplayTitle,
                        Tag = album.Id,
                        CornerRadius = new CornerRadius(6),
                        FontSize = 12
                    };

                    if (album.Id == _selectedGalleryBucket)
                    {
                        btn.Style = (Style)Application.Current.Resources["AccentButtonStyle"];
                    }

                    btn.Click += (s, e) =>
                    {
                        if (s is Button clickedBtn && clickedBtn.Tag is string bucketId)
                        {
                            _selectedGalleryBucket = bucketId;
                            foreach (var child in GalleryAlbumsPanel.Children.OfType<Button>())
                            {
                                if (child.Tag is string bId && bId == bucketId)
                                {
                                    child.Style = (Style)Application.Current.Resources["AccentButtonStyle"];
                                }
                                else
                                {
                                    child.Style = (Style)Application.Current.Resources["DefaultButtonStyle"];
                                }
                            }
                            _ = LoadGalleryBucketAsync(bucketId);
                        }
                    };

                    GalleryAlbumsPanel.Children.Add(btn);
                }
            });
        }

        private void OnGalleryReceived(object? sender, GalleryResponseEventArgs e)
        {
            DispatcherQueue.TryEnqueue(() =>
            {
                GalleryProgressBar.Visibility = Visibility.Collapsed;

                if (!string.IsNullOrEmpty(e.ErrorMessage))
                {
                    GalleryStatusText.Text = e.ErrorMessage;
                    return;
                }

                _galleryItems.Clear();
                GalleryCountBadge.Text = $"{e.Items.Count} item{(e.Items.Count != 1 ? "s" : "")}";
                GalleryStatusText.Text = $"Showing {e.Items.Count} photo{(e.Items.Count != 1 ? "s" : "")} from Android phone ({e.Bucket})";

                foreach (var item in e.Items)
                {
                    _galleryItems.Add(item);
                    item.LoadThumbnail(DispatcherQueue);
                }
            });
        }

        private async void GalleryGridView_ItemClick(object sender, ItemClickEventArgs e)
        {
            if (e.ClickedItem is GalleryItem item)
            {
                GalleryStatusText.Text = $"Streaming full resolution preview for '{item.Name}'...";
                await SearchService.Instance.RequestRemoteStreamAsync(item.Id, isPreview: true);
            }
        }

        private async void PreviewGalleryItem_Click(object sender, RoutedEventArgs e)
        {
            if (sender is FrameworkElement fe && fe.Tag is string fileId)
            {
                var item = _galleryItems.FirstOrDefault(i => i.Id == fileId);
                string name = item?.Name ?? "photo";
                GalleryStatusText.Text = $"Streaming full resolution preview for '{name}'...";
                await SearchService.Instance.RequestRemoteStreamAsync(fileId, isPreview: true);
            }
        }

        private async void SaveGalleryItem_Click(object sender, RoutedEventArgs e)
        {
            if (sender is FrameworkElement fe && fe.Tag is string fileId)
            {
                var item = _galleryItems.FirstOrDefault(i => i.Id == fileId);
                string name = item?.Name ?? "photo";
                GalleryStatusText.Text = $"Downloading '{name}' to PC Downloads...";
                await SearchService.Instance.RequestRemoteStreamAsync(fileId, isPreview: false);
            }
        }
        #endregion

        #region Phone Webcam & Virtual Camera Methods
        private void OnWebcamLensesReceived(List<CameraLensModel> lenses)
        {
            DispatcherQueue.TryEnqueue(() =>
            {
                var previousSelected = LensComboBox.SelectedItem as CameraLensModel;
                LensComboBox.ItemsSource = null;
                LensComboBox.ItemsSource = lenses;

                if (lenses.Count > 0)
                {
                    var match = lenses.FirstOrDefault(l => previousSelected != null && l.Id == previousSelected.Id)
                                ?? lenses.FirstOrDefault(l => l.Facing == "BACK" && l.LensType == "WIDE")
                                ?? lenses[0];
                    LensComboBox.SelectedItem = match;
                }

                WebcamStatusText.Text = $"Discovered {lenses.Count} phone lenses. Virtual Camera ready.";
            });
        }

        private void OnWebcamFrameReceived(global::Windows.Graphics.Imaging.SoftwareBitmap bitmap)
        {
            if (_isUpdatingWebcamPreview) return;
            _isUpdatingWebcamPreview = true;

            DispatcherQueue.TryEnqueue(async () =>
            {
                try
                {
                    if (_webcamSource == null)
                    {
                        _webcamSource = new SoftwareBitmapSource();
                        WebcamPreviewImage.Source = _webcamSource;
                    }
                    await _webcamSource.SetBitmapAsync(bitmap);
                }
                catch { }
                finally
                {
                    _isUpdatingWebcamPreview = false;
                }
            });
        }

        private void OnWebcamStreamingStateChanged(bool isStreaming)
        {
            DispatcherQueue.TryEnqueue(() =>
            {
                if (isStreaming)
                {
                    WebcamStandbyOverlay.Visibility = Visibility.Collapsed;
                    WebcamLiveBadge.Visibility = Visibility.Visible;
                    ToggleWebcamIcon.Glyph = "\uE71A"; // Stop icon
                    ToggleWebcamText.Text = "Stop Webcam";
                    ToggleWebcamButton.Style = (Style)Application.Current.Resources["DefaultButtonStyle"];
                }
                else
                {
                    WebcamStandbyOverlay.Visibility = Visibility.Visible;
                    WebcamLiveBadge.Visibility = Visibility.Collapsed;
                    ToggleWebcamIcon.Glyph = "\uE768"; // Play icon
                    ToggleWebcamText.Text = "Start Webcam";
                    ToggleWebcamButton.Style = (Style)Application.Current.Resources["AccentButtonStyle"];
                    WebcamPreviewImage.Source = null;
                    _webcamSource = null;
                }
            });
        }

        private void OnWebcamStatusChanged(string status)
        {
            DispatcherQueue.TryEnqueue(() =>
            {
                WebcamStatusText.Text = status;
            });
        }

        private void OnWebcamFpsUpdated(double fps)
        {
            DispatcherQueue.TryEnqueue(() =>
            {
                string res = WebcamService.Instance.CurrentResolution;
                WebcamLiveBadgeText.Text = $"LIVE • {res} @ {fps:F0} FPS";
            });
        }

        private async void ToggleWebcamButton_Click(object sender, RoutedEventArgs e)
        {
            if (WebcamService.Instance.IsStreaming)
            {
                await WebcamService.Instance.StopStreamingAsync();
            }
            else
            {
                var selectedLens = LensComboBox.SelectedItem as CameraLensModel;
                if (selectedLens == null && WebcamService.Instance.AvailableLenses.Count > 0)
                {
                    selectedLens = WebcamService.Instance.AvailableLenses[0];
                    LensComboBox.SelectedItem = selectedLens;
                }

                if (selectedLens == null)
                {
                    WebcamStatusText.Text = "Querying phone lenses first...";
                    await WebcamService.Instance.RequestLensesAsync();
                    await Task.Delay(500);
                    selectedLens = LensComboBox.SelectedItem as CameraLensModel 
                                   ?? WebcamService.Instance.AvailableLenses.FirstOrDefault();
                }

                if (selectedLens == null)
                {
                    WebcamStatusText.Text = "Cannot find phone lenses. Ensure dropp is open on phone.";
                    return;
                }

                string res = "1920x1080";
                if (ResolutionComboBox.SelectedItem is ComboBoxItem cbi && cbi.Content is string s)
                {
                    res = s.Split(' ')[0];
                }

                await WebcamService.Instance.StartStreamingAsync(selectedLens, res);
            }
        }

        private async void LensComboBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            if (LensComboBox.SelectedItem is CameraLensModel newLens && WebcamService.Instance.IsStreaming)
            {
                if (WebcamService.Instance.CurrentLens?.Id != newLens.Id)
                {
                    await WebcamService.Instance.SwitchLensAsync(newLens);
                }
            }
        }

        private async void ResolutionComboBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            if (WebcamService.Instance.IsStreaming)
            {
                var lens = WebcamService.Instance.CurrentLens;
                if (lens != null)
                {
                    string res = "1920x1080";
                    if (ResolutionComboBox.SelectedItem is ComboBoxItem cbi && cbi.Content is string s)
                    {
                        res = s.Split(' ')[0];
                    }
                    await WebcamService.Instance.StartStreamingAsync(lens, res);
                }
            }
        }

        private void WebcamMirrorToggle_Click(object sender, RoutedEventArgs e)
        {
            WebcamService.Instance.IsMirrored = WebcamMirrorToggle.IsChecked == true;
        }

        private void WebcamRotateButton_Click(object sender, RoutedEventArgs e)
        {
            WebcamService.Instance.ManualRotationAngle = (WebcamService.Instance.ManualRotationAngle + 90) % 360;
            WebcamStatusText.Text = $"Camera rotation: {WebcamService.Instance.ManualRotationAngle}°";
        }

        private async void WebcamTorchToggle_Click(object sender, RoutedEventArgs e)
        {
            await WebcamService.Instance.SetTorchAsync(WebcamTorchToggle.IsChecked == true);
        }

        private async void WebcamZoomSlider_ValueChanged(object sender, Microsoft.UI.Xaml.Controls.Primitives.RangeBaseValueChangedEventArgs e)
        {
            float zoom = (float)Math.Round(e.NewValue, 1);
            if (WebcamZoomText != null)
            {
                WebcamZoomText.Text = $"{zoom:F1}x";
            }
            await WebcamService.Instance.SetZoomAsync(zoom);
        }

        private async void RefreshLensesButton_Click(object sender, RoutedEventArgs e)
        {
            WebcamStatusText.Text = "Refreshing phone lenses...";
            await WebcamService.Instance.RequestLensesAsync();
        }

        private void OpenWindowsCameraAppButton_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                Process.Start(new ProcessStartInfo("microsoft.windows.camera:") { UseShellExecute = true });
            }
            catch (Exception ex)
            {
                AppLogger.Log("MainWindow", $"Failed to open Windows Camera App: {ex.Message}");
            }
        }
        #endregion
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