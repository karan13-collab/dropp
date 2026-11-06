using System;
using System.Runtime.InteropServices;
using Microsoft.UI;
using Microsoft.UI.Text;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Media;
using Windows.Graphics;
using Windows.UI;
using dropp.Windows.Core.Diagnostics;
using dropp.Windows.Services;

namespace dropp.Windows.UI
{
    /// <summary>
    /// Always-on-top compact call handoff window (similar to Microsoft Phone Link's call widget)
    /// allowing users to Answer on PC, Decline/End Call, Mute Mic, and Transfer Audio between PC &amp; Phone
    /// for both Cellular calls and VoIP calls (WhatsApp, Telegram, Instagram, Meet, etc.).
    /// </summary>
    public sealed class CallOverlayWindow : Window
    {
        private static CallOverlayWindow? _current;

        private readonly AppWindow? _appWindow;
        private readonly IntPtr _hWnd;
        private readonly DispatcherTimer _durationTimer;
        private readonly DispatcherTimer _autoCloseTimer;
        private DateTime _callStartUtc;

        private readonly TextBlock _appSourceText;
        private readonly Border _statePill;
        private readonly TextBlock _stateText;
        private readonly TextBlock _callerNameText;
        private readonly TextBlock _callerSubText;
        private readonly TextBlock _timerText;

        private readonly Button _answerBtn;
        private readonly Button _declineOrEndBtn;
        private readonly TextBlock _declineOrEndText;
        private readonly ToggleButton _muteBtn;
        private readonly FontIcon _muteIcon;
        private readonly TextBlock _muteText;
        private readonly Button _audioRouteBtn;
        private readonly FontIcon _audioRouteIcon;
        private readonly TextBlock _audioRouteText;

        private bool _isAudioOnPc = true;
        private DateTime _localRouteActionUtc = DateTime.MinValue;
        private bool _isClosed;
        private string _currentState = "IDLE";

        [DllImport("user32.dll")]
        private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

        private const int SW_SHOWNOACTIVATE = 4;

        public static void ShowOrUpdate(Microsoft.UI.Dispatching.DispatcherQueue dispatcher, CallEventArgs args)
        {
            dispatcher.TryEnqueue(() =>
            {
                try
                {
                    if (args.State == "IDLE") return;

                    if (_current == null || _current._isClosed)
                    {
                        if (args.State == "ENDED") return;
                        _current = new CallOverlayWindow();
                    }

                    _current.ApplyCallState(args);
                }
                catch (Exception ex)
                {
                    AppLogger.Log("CallOverlay", $"ShowOrUpdate error: {ex.Message}");
                }
            });
        }

        private CallOverlayWindow()
        {
            Title = "Dropp Call";
            SystemBackdrop = new MicaBackdrop();

            _hWnd = WinRT.Interop.WindowNative.GetWindowHandle(this);
            var winId = Win32Interop.GetWindowIdFromWindow(_hWnd);
            _appWindow = AppWindow.GetFromWindowId(winId);

            if (_appWindow != null)
            {
                _appWindow.Resize(new SizeInt32(400, 235));
                if (_appWindow.Presenter is OverlappedPresenter presenter)
                {
                    presenter.IsAlwaysOnTop = true;
                    presenter.IsMaximizable = false;
                    presenter.IsMinimizable = false;
                    presenter.IsResizable = false;
                    presenter.SetBorderAndTitleBar(true, false);
                }

                PositionBottomRight();
                _appWindow.Closing += (s, e) =>
                {
                    _isClosed = true;
                    _durationTimer?.Stop();
                    _autoCloseTimer?.Stop();
                };
            }

            Closed += (s, e) =>
            {
                _isClosed = true;
                _durationTimer?.Stop();
                _autoCloseTimer?.Stop();
                if (_current == this) _current = null;
            };

            // Root container
            var rootBorder = new Border
            {
                Background = new SolidColorBrush(Color.FromArgb(245, 22, 24, 30)),
                Padding = new Thickness(18, 14, 18, 16)
            };

            var mainStack = new StackPanel { Spacing = 12 };

            // Top Row: App Badge + State Pill + Dismiss X
            var topGrid = new Grid();
            topGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            topGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            topGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var appRow = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, VerticalAlignment = VerticalAlignment.Center };
            appRow.Children.Add(new FontIcon
            {
                Glyph = "\uE717",
                FontSize = 14,
                Foreground = new SolidColorBrush(Color.FromArgb(255, 76, 194, 255))
            });
            _appSourceText = new TextBlock
            {
                Text = "Phone Call",
                FontSize = 12,
                FontWeight = FontWeights.SemiBold,
                Foreground = new SolidColorBrush(Color.FromArgb(220, 200, 210, 225)),
                VerticalAlignment = VerticalAlignment.Center
            };
            appRow.Children.Add(_appSourceText);
            topGrid.Children.Add(appRow);

            _stateText = new TextBlock
            {
                Text = "Incoming",
                FontSize = 11,
                FontWeight = FontWeights.SemiBold,
                Foreground = new SolidColorBrush(Colors.White)
            };
            _statePill = new Border
            {
                Background = new SolidColorBrush(Color.FromArgb(255, 0, 120, 212)),
                CornerRadius = new CornerRadius(10),
                Padding = new Thickness(10, 3, 10, 3),
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(0, 0, 6, 0),
                Child = _stateText
            };
            Grid.SetColumn(_statePill, 1);
            topGrid.Children.Add(_statePill);

            var hideBtn = new Button
            {
                Background = new SolidColorBrush(Colors.Transparent),
                BorderThickness = new Thickness(0),
                Padding = new Thickness(6, 2, 6, 2),
                Content = new FontIcon { Glyph = "\uE711", FontSize = 11, Foreground = new SolidColorBrush(Color.FromArgb(180, 200, 200, 200)) }
            };
            hideBtn.Click += (s, e) => SafeClose();
            Grid.SetColumn(hideBtn, 2);
            topGrid.Children.Add(hideBtn);

            mainStack.Children.Add(topGrid);

            // Middle Row: Avatar + Caller Name/Sub + Timer
            var callerCard = new Border
            {
                Background = new SolidColorBrush(Color.FromArgb(160, 36, 40, 50)),
                CornerRadius = new CornerRadius(10),
                Padding = new Thickness(12, 10, 12, 10)
            };

            var callerGrid = new Grid();
            callerGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            callerGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            callerGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var avatar = new Border
            {
                Width = 42,
                Height = 42,
                CornerRadius = new CornerRadius(21),
                Background = new SolidColorBrush(Color.FromArgb(255, 0, 120, 212)),
                Margin = new Thickness(0, 0, 12, 0),
                Child = new FontIcon
                {
                    Glyph = "\uE77B",
                    FontSize = 18,
                    Foreground = new SolidColorBrush(Colors.White),
                    HorizontalAlignment = HorizontalAlignment.Center,
                    VerticalAlignment = VerticalAlignment.Center
                }
            };
            callerGrid.Children.Add(avatar);

            var nameStack = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Spacing = 2 };
            _callerNameText = new TextBlock
            {
                Text = "Incoming Caller",
                FontSize = 16,
                FontWeight = FontWeights.Bold,
                Foreground = new SolidColorBrush(Colors.White),
                TextTrimming = TextTrimming.CharacterEllipsis
            };
            _callerSubText = new TextBlock
            {
                Text = "Ringing...",
                FontSize = 12,
                Foreground = new SolidColorBrush(Color.FromArgb(190, 190, 200, 215)),
                TextTrimming = TextTrimming.CharacterEllipsis
            };
            nameStack.Children.Add(_callerNameText);
            nameStack.Children.Add(_callerSubText);
            Grid.SetColumn(nameStack, 1);
            callerGrid.Children.Add(nameStack);

            _timerText = new TextBlock
            {
                Text = "00:00",
                FontSize = 15,
                FontWeight = FontWeights.Bold,
                Foreground = new SolidColorBrush(Color.FromArgb(255, 76, 194, 255)),
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(8, 0, 0, 0),
                Visibility = Visibility.Collapsed
            };
            Grid.SetColumn(_timerText, 2);
            callerGrid.Children.Add(_timerText);

            callerCard.Child = callerGrid;
            mainStack.Children.Add(callerCard);

            // Bottom Row: Action Buttons
            var actionsPanel = new StackPanel
            {
                Orientation = Orientation.Horizontal,
                HorizontalAlignment = HorizontalAlignment.Center,
                Spacing = 8
            };

            // 1. Answer on PC Button
            var answerContent = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
            answerContent.Children.Add(new FontIcon { Glyph = "\uE717", FontSize = 13, Foreground = new SolidColorBrush(Colors.White) });
            answerContent.Children.Add(new TextBlock { Text = "Answer on PC", FontWeight = FontWeights.SemiBold, Foreground = new SolidColorBrush(Colors.White) });
            _answerBtn = new Button
            {
                Background = new SolidColorBrush(Color.FromArgb(255, 16, 137, 62)),
                CornerRadius = new CornerRadius(8),
                Padding = new Thickness(14, 8, 14, 8),
                Content = answerContent
            };
            _answerBtn.Click += async (s, e) =>
            {
                AppLogger.Log("CallOverlay", "User clicked Answer on PC in overlay window.");
                _isAudioOnPc = true;
                _localRouteActionUtc = DateTime.UtcNow;
                UpdateAudioRouteButton();
                _ = BluetoothHandsFreeService.Instance.EnsureHfpConnectedAsync();
                try { await Microsoft.Windows.AppNotifications.AppNotificationManager.Default.RemoveByTagAsync("active_call"); } catch { }
                await CommandReceiverService.SendCallCommandAsync("CALL_ANSWER");
            };
            actionsPanel.Children.Add(_answerBtn);

            // 2. Mute Mic Toggle (Visible when ACTIVE)
            _muteIcon = new FontIcon { Glyph = "\uE720", FontSize = 13, Foreground = new SolidColorBrush(Colors.White) };
            _muteText = new TextBlock { Text = "Mute", FontSize = 12, Foreground = new SolidColorBrush(Colors.White) };
            var muteContent = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 5 };
            muteContent.Children.Add(_muteIcon);
            muteContent.Children.Add(_muteText);
            _muteBtn = new ToggleButton
            {
                CornerRadius = new CornerRadius(8),
                Padding = new Thickness(10, 8, 10, 8),
                Visibility = Visibility.Collapsed,
                Content = muteContent
            };
            _muteBtn.Click += async (s, e) =>
            {
                bool muted = _muteBtn.IsChecked == true;
                _muteIcon.Glyph = muted ? "\uE74F" : "\uE720";
                _muteText.Text = muted ? "Unmute" : "Mute";
                BluetoothHandsFreeService.Instance.SetPcMicMuted(muted);
                await CommandReceiverService.SendCallCommandAsync(muted ? "CALL_MIC_MUTE" : "CALL_MIC_UNMUTE");
            };
            actionsPanel.Children.Add(_muteBtn);

            // 3. Transfer PC <-> Phone Audio Button (Visible when ACTIVE)
            _audioRouteIcon = new FontIcon { Glyph = "\uE7F5", FontSize = 13, Foreground = new SolidColorBrush(Colors.White) };
            _audioRouteText = new TextBlock { Text = "Use Phone", FontSize = 12, Foreground = new SolidColorBrush(Colors.White) };
            var routeContent = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 5 };
            routeContent.Children.Add(_audioRouteIcon);
            routeContent.Children.Add(_audioRouteText);
            _audioRouteBtn = new Button
            {
                CornerRadius = new CornerRadius(8),
                Padding = new Thickness(10, 8, 10, 8),
                Visibility = Visibility.Collapsed,
                Content = routeContent
            };
            _audioRouteBtn.Click += async (s, e) =>
            {
                _isAudioOnPc = !_isAudioOnPc;
                _localRouteActionUtc = DateTime.UtcNow;
                UpdateAudioRouteButton();
                if (_isAudioOnPc)
                {
                    await BluetoothHandsFreeService.Instance.EnsureHfpConnectedAsync();
                    await CommandReceiverService.SendCallCommandAsync("CALL_AUDIO_START");
                }
                else
                {
                    await CommandReceiverService.SendCallCommandAsync("CALL_AUDIO_STOP");
                    await BluetoothHandsFreeService.Instance.DisconnectHfpAsync();
                }
            };
            actionsPanel.Children.Add(_audioRouteBtn);

            // 4. Decline / End Call Button
            var endContent = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
            endContent.Children.Add(new FontIcon { Glyph = "\uE778", FontSize = 13, Foreground = new SolidColorBrush(Colors.White) });
            _declineOrEndText = new TextBlock { Text = "Decline", FontWeight = FontWeights.SemiBold, Foreground = new SolidColorBrush(Colors.White) };
            endContent.Children.Add(_declineOrEndText);
            _declineOrEndBtn = new Button
            {
                Background = new SolidColorBrush(Color.FromArgb(255, 196, 43, 28)),
                CornerRadius = new CornerRadius(8),
                Padding = new Thickness(14, 8, 14, 8),
                Content = endContent
            };
            _declineOrEndBtn.Click += async (s, e) =>
            {
                string cmd = _currentState == "RINGING" ? "CALL_REJECT" : "CALL_END";
                AppLogger.Log("CallOverlay", $"User clicked {_declineOrEndText.Text} in overlay window -> sending {cmd} and closing overlay.");
                CommandReceiverService.NotifyLocalCallEnded();
                SafeClose();
                await CommandReceiverService.SendCallCommandAsync(cmd);
            };
            actionsPanel.Children.Add(_declineOrEndBtn);

            mainStack.Children.Add(actionsPanel);
            rootBorder.Child = mainStack;
            Content = rootBorder;

            _durationTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
            _durationTimer.Tick += (s, e) =>
            {
                var elapsed = DateTime.UtcNow - _callStartUtc;
                _timerText.Text = elapsed.ToString(@"mm\:ss");
            };

            _autoCloseTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(900) };
            _autoCloseTimer.Tick += (s, e) =>
            {
                _autoCloseTimer.Stop();
                SafeClose();
            };
        }

        private void PositionBottomRight()
        {
            try
            {
                if (_appWindow == null) return;
                var displayArea = DisplayArea.GetFromWindowId(_appWindow.Id, DisplayAreaFallback.Primary);
                if (displayArea != null)
                {
                    var workArea = displayArea.WorkArea;
                    int x = workArea.X + workArea.Width - 416;
                    int y = workArea.Y + workArea.Height - 251;
                    _appWindow.Move(new PointInt32(Math.Max(0, x), Math.Max(0, y)));
                }
            }
            catch { }
        }

        private void UpdateAudioRouteButton()
        {
            if (_isAudioOnPc)
            {
                _audioRouteIcon.Glyph = "\uE8EA"; // Phone icon -> switch to phone
                _audioRouteText.Text = "To Phone";
                _stateText.Text = "On PC Audio";
            }
            else
            {
                _audioRouteIcon.Glyph = "\uE7F5"; // Speakers/PC icon -> switch to PC
                _audioRouteText.Text = "To PC";
                _stateText.Text = "On Phone";
            }
        }

        private void ApplyCallState(CallEventArgs args)
        {
            _autoCloseTimer.Stop();
            _currentState = args.State;

            string appLabel = !string.IsNullOrWhiteSpace(args.AppName)
                ? $"{args.AppName} Call"
                : (args.CallType == "VOIP" ? "VoIP Call" : "Cellular Call");
            _appSourceText.Text = appLabel;

            if (!string.IsNullOrWhiteSpace(args.CallerName))
            {
                _callerNameText.Text = args.CallerName;
            }

            if (args.State == "RINGING")
            {
                _callerSubText.Text = !string.IsNullOrWhiteSpace(args.PhoneNumber) && args.PhoneNumber != args.CallerName
                    ? args.PhoneNumber
                    : $"Incoming {appLabel}...";
                _stateText.Text = "Incoming";
                _statePill.Background = new SolidColorBrush(Color.FromArgb(255, 0, 120, 212));

                _answerBtn.Visibility = Visibility.Visible;
                _declineOrEndBtn.Visibility = Visibility.Visible;
                _declineOrEndText.Text = "Decline";
                _muteBtn.Visibility = Visibility.Collapsed;
                _audioRouteBtn.Visibility = Visibility.Collapsed;
                _timerText.Visibility = Visibility.Collapsed;
                _durationTimer.Stop();

                ShowOverlayNonIntrusive();
            }
            else if (args.State == "ACTIVE")
            {
                if (args.AudioOnPc.HasValue && (DateTime.UtcNow - _localRouteActionUtc).TotalSeconds > 2.5)
                {
                    _isAudioOnPc = args.AudioOnPc.Value;
                }

                _callerSubText.Text = !string.IsNullOrWhiteSpace(args.PhoneNumber) && args.PhoneNumber != args.CallerName
                    ? args.PhoneNumber
                    : $"{appLabel} • Connected";
                _statePill.Background = new SolidColorBrush(Color.FromArgb(255, 16, 137, 62));

                _answerBtn.Visibility = Visibility.Collapsed;
                _declineOrEndBtn.Visibility = Visibility.Visible;
                _declineOrEndText.Text = "End Call";
                _muteBtn.Visibility = Visibility.Visible;
                _audioRouteBtn.Visibility = Visibility.Visible;
                UpdateAudioRouteButton();

                if (!_durationTimer.IsEnabled)
                {
                    _callStartUtc = DateTime.UtcNow;
                    _timerText.Text = "00:00";
                    _durationTimer.Start();
                }
                _timerText.Visibility = Visibility.Visible;

                ShowOverlayNonIntrusive();
            }
            else if (args.State == "ENDED")
            {
                _durationTimer.Stop();
                BluetoothHandsFreeService.Instance.SetPcMicMuted(false);
                if (!_isAudioOnPc)
                {
                    _ = System.Threading.Tasks.Task.Run(async () =>
                    {
                        await System.Threading.Tasks.Task.Delay(2500);
                        await BluetoothHandsFreeService.Instance.EnsureHfpConnectedAsync();
                    });
                }

                _stateText.Text = "Call Ended";
                _statePill.Background = new SolidColorBrush(Color.FromArgb(255, 90, 95, 105));
                _callerSubText.Text = "Disconnected";

                _answerBtn.Visibility = Visibility.Collapsed;
                _declineOrEndBtn.Visibility = Visibility.Collapsed;
                _muteBtn.Visibility = Visibility.Collapsed;
                _audioRouteBtn.Visibility = Visibility.Collapsed;

                _autoCloseTimer.Start();
            }
        }

        private void ShowOverlayNonIntrusive()
        {
            if (_isClosed) return;
            PositionBottomRight();
            _appWindow?.Show(false);
            ShowWindow(_hWnd, SW_SHOWNOACTIVATE);
        }

        private void SafeClose()
        {
            if (_isClosed) return;
            _isClosed = true;
            if (_current == this) _current = null;
            _durationTimer.Stop();
            _autoCloseTimer.Stop();
            try { _appWindow?.Hide(); } catch { }
            try { Close(); } catch { }
        }
    }
}
