using System;
using System.Collections.Concurrent;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using NAudio.CoreAudioApi;
using Windows.ApplicationModel.Calls;
using Windows.Devices.Enumeration;
using dropp.Windows.Core.Diagnostics;

namespace dropp.Windows.Services
{
    /// <summary>
    /// Manages the Windows Bluetooth Hands-Free Profile (HFP HF role) via PhoneLineTransportDevice,
    /// mirroring Microsoft Phone Link's call audio architecture so bidirectional voice audio
    /// (PC Speakers &lt;- Caller, PC Microphone -&gt; Caller) works natively for both Cellular and VoIP calls.
    /// </summary>
    public class BluetoothHandsFreeService
    {
        private static BluetoothHandsFreeService? _instance;
        public static BluetoothHandsFreeService Instance => _instance ??= new BluetoothHandsFreeService();

        private readonly ConcurrentDictionary<string, DeviceInformation> _knownHfpDevices = new();
        private readonly SemaphoreSlim _connectLock = new(1, 1);
        private DeviceWatcher? _watcher;
        private PhoneLineTransportDevice? _activeTransport;
        private DeviceInformation? _activeDeviceInfo;
        private PhoneLineWatcher? _lineWatcher;
        private bool _isStarted;

        public bool IsHfpConnected { get; private set; }
        public string ActiveDeviceName => _activeDeviceInfo?.Name ?? "";
        public event Action<bool, string>? HfpStatusChanged;

        private BluetoothHandsFreeService() { }

        public async Task StartAsync()
        {
            if (_isStarted) return;
            _isStarted = true;

            AppLogger.Log("BluetoothHFP", "Starting Bluetooth Hands-Free (PhoneLineTransportDevice) service...");

            try
            {
                string selector = PhoneLineTransportDevice.GetDeviceSelector(PhoneLineTransport.Bluetooth);
                var devices = await DeviceInformation.FindAllAsync(selector);

                AppLogger.Log("BluetoothHFP", $"Found {devices.Count} paired Bluetooth PhoneLineTransportDevice(s).");
                foreach (var dev in devices)
                {
                    if (!string.IsNullOrEmpty(dev.Id))
                    {
                        _knownHfpDevices[dev.Id] = dev;
                        AppLogger.Log("BluetoothHFP", $"Discovered HFP transport device: Name='{dev.Name}', Id='{dev.Id}', IsEnabled={dev.IsEnabled}");
                    }
                }

                // Watch for newly paired or updated Bluetooth HFP phones
                _watcher = DeviceInformation.CreateWatcher(selector);
                _watcher.Added += (s, info) =>
                {
                    if (!string.IsNullOrEmpty(info.Id))
                    {
                        _knownHfpDevices[info.Id] = info;
                        AppLogger.Log("BluetoothHFP", $"DeviceWatcher Added HFP device: {info.Name} ({info.Id})");
                        if (!IsHfpConnected)
                        {
                            _ = EnsureHfpConnectedAsync();
                        }
                    }
                };
                _watcher.Removed += (s, update) =>
                {
                    _knownHfpDevices.TryRemove(update.Id, out _);
                    AppLogger.Log("BluetoothHFP", $"DeviceWatcher Removed HFP device: {update.Id}");
                    if (_activeDeviceInfo?.Id == update.Id)
                    {
                        IsHfpConnected = false;
                        HfpStatusChanged?.Invoke(false, "Phone HFP disconnected");
                    }
                };
                _watcher.Start();

                // Initialize PhoneCallStore LineWatcher for diagnostics & line state tracking
                _ = InitPhoneLineWatcherAsync();

                // Proactively register & connect HFP transport so Windows accepts phone call audio immediately
                await EnsureHfpConnectedAsync();
            }
            catch (Exception ex)
            {
                AppLogger.Log("BluetoothHFP", $"Error starting BluetoothHandsFreeService: {ex.Message}");
                HfpStatusChanged?.Invoke(false, $"HFP init error: {ex.Message}");
            }
        }

        private async Task InitPhoneLineWatcherAsync()
        {
            try
            {
                var store = await PhoneCallManager.RequestStoreAsync();
                if (store == null)
                {
                    AppLogger.Log("BluetoothHFP", "PhoneCallManager.RequestStoreAsync returned null.");
                    return;
                }

                _lineWatcher = store.RequestLineWatcher();
                if (_lineWatcher == null) return;

                _lineWatcher.LineAdded += async (s, args) =>
                {
                    try
                    {
                        var line = await PhoneLine.FromIdAsync(args.LineId);
                        AppLogger.Log("BluetoothHFP", $"PhoneLine Added: LineId={args.LineId}, DisplayName='{line?.DisplayName}', Transport={line?.Transport}, CanDial={line?.CanDial}, TransportDeviceId='{line?.TransportDeviceId}'");
                    }
                    catch (Exception ex)
                    {
                        AppLogger.Log("BluetoothHFP", $"PhoneLine Added read error: {ex.GetType().Name} (0x{ex.HResult:X8}) {ex.Message}");
                    }
                };

                _lineWatcher.LineUpdated += async (s, args) =>
                {
                    try
                    {
                        var line = await PhoneLine.FromIdAsync(args.LineId);
                        AppLogger.Log("BluetoothHFP", $"PhoneLine Updated: LineId={args.LineId}, DisplayName='{line?.DisplayName}', NetworkState={line?.NetworkState}");
                    }
                    catch { }
                };

                _lineWatcher.Start();
                AppLogger.Log("BluetoothHFP", "PhoneLineWatcher started.");
            }
            catch (Exception ex)
            {
                AppLogger.Log("BluetoothHFP", $"InitPhoneLineWatcherAsync notice: {ex.Message}");
            }
        }

        private DeviceInformation? SelectTargetPhone()
        {
            // Prefer vivo X200 FE or 64447BDBE31C, otherwise any paired phone (avoiding stale secondary entries if present)
            var vivo = _knownHfpDevices.Values.FirstOrDefault(d =>
                d.Name.Contains("vivo", StringComparison.OrdinalIgnoreCase) ||
                d.Id.Contains("64447BDBE31C", StringComparison.OrdinalIgnoreCase));
            if (vivo != null) return vivo;

            var nonKaran = _knownHfpDevices.Values.FirstOrDefault(d =>
                !d.Name.Contains("Karan", StringComparison.OrdinalIgnoreCase));
            return nonKaran ?? _knownHfpDevices.Values.FirstOrDefault();
        }

        public async Task<bool> EnsureHfpConnectedAsync()
        {
            if (!await _connectLock.WaitAsync(0))
            {
                // Wait for in-flight connection attempt and return its result
                await _connectLock.WaitAsync();
                _connectLock.Release();
                return IsHfpConnected;
            }

            try
            {
                if (_knownHfpDevices.IsEmpty)
                {
                    string selector = PhoneLineTransportDevice.GetDeviceSelector(PhoneLineTransport.Bluetooth);
                    var devices = await DeviceInformation.FindAllAsync(selector);
                    foreach (var d in devices)
                    {
                        if (!string.IsNullOrEmpty(d.Id)) _knownHfpDevices[d.Id] = d;
                    }
                }

                var targetInfo = SelectTargetPhone();
                if (targetInfo == null)
                {
                    AppLogger.Log("BluetoothHFP", "EnsureHfpConnectedAsync: No paired HFP phone found on Windows.");
                    HfpStatusChanged?.Invoke(false, "No paired Bluetooth HFP phone found");
                    return false;
                }

                _activeDeviceInfo = targetInfo;
                AppLogger.Log("BluetoothHFP", $"Selected HFP target phone: '{targetInfo.Name}' ({targetInfo.Id})");

                var transport = PhoneLineTransportDevice.FromId(targetInfo.Id);
                if (transport == null)
                {
                    AppLogger.Log("BluetoothHFP", $"PhoneLineTransportDevice.FromId returned null for {targetInfo.Name}");
                    HfpStatusChanged?.Invoke(false, "Could not open HFP device handle");
                    return false;
                }

                _activeTransport = transport;

                // 1. Request OS/User permission for PhoneLineTransportDevice
                var accessStatus = await transport.RequestAccessAsync();
                AppLogger.Log("BluetoothHFP", $"RequestAccessAsync() for '{targetInfo.Name}' returned: {accessStatus}");

                if (accessStatus != DeviceAccessStatus.Allowed)
                {
                    HfpStatusChanged?.Invoke(false, $"HFP access denied ({accessStatus}) — check Windows Privacy > Phone calls");
                    return false;
                }

                // 2. Register dropp.Windows as the active HFP companion app for this phone
                bool alreadyRegistered = false;
                try
                {
                    alreadyRegistered = transport.IsRegistered();
                }
                catch (Exception ex)
                {
                    AppLogger.Log("BluetoothHFP", $"IsRegistered() check error: {ex.Message}");
                }

                AppLogger.Log("BluetoothHFP", $"Transport IsRegistered before RegisterApp(): {alreadyRegistered}");
                if (!alreadyRegistered)
                {
                    try
                    {
                        transport.RegisterApp();
                        alreadyRegistered = transport.IsRegistered();
                        AppLogger.Log("BluetoothHFP", $"Transport IsRegistered after RegisterApp(): {alreadyRegistered}");
                    }
                    catch (Exception ex)
                    {
                        AppLogger.Log("BluetoothHFP", $"RegisterApp() exception: {ex.Message} (0x{ex.HResult:X8})");
                    }
                }

                // 3. Connect Bluetooth HFP Service Level Connection (SLC)
                for (int attempt = 1; attempt <= 3; attempt++)
                {
                    try
                    {
                        AppLogger.Log("BluetoothHFP", $"Calling transport.ConnectAsync() for '{targetInfo.Name}' (Attempt {attempt}/3)...");
                        bool connected = await transport.ConnectAsync();
                        AppLogger.Log("BluetoothHFP", $"transport.ConnectAsync() result for '{targetInfo.Name}': {connected}");

                        if (connected)
                        {
                            IsHfpConnected = true;
                            HfpStatusChanged?.Invoke(true, $"Hands-Free Ready ({targetInfo.Name})");
                            LogWasapiEndpoints("Post-HFP-Connect");
                            return true;
                        }
                    }
                    catch (Exception ex)
                    {
                        AppLogger.Log("BluetoothHFP", $"ConnectAsync attempt {attempt} error: {ex.Message} (0x{ex.HResult:X8})");
                    }

                    if (attempt < 3)
                    {
                        await Task.Delay(1200);
                    }
                }

                IsHfpConnected = false;
                LogWasapiEndpoints("Post-HFP-Standby");
                HfpStatusChanged?.Invoke(false, $"Hands-Free standby ({targetInfo.Name} out of range or busy)");
                return false;
            }
            catch (Exception ex)
            {
                AppLogger.Log("BluetoothHFP", $"EnsureHfpConnectedAsync fatal error: {ex.Message}");
                return false;
            }
            finally
            {
                _connectLock.Release();
            }
        }

        private bool _weMutedPcMic = false;

        public async Task<bool> DisconnectHfpAsync()
        {
            await _connectLock.WaitAsync();
            try
            {
                if (_activeTransport != null)
                {
                    AppLogger.Log("BluetoothHFP", "Unregistering HFP transport (transferring call audio to phone)...");
                    _activeTransport.UnregisterApp();
                    IsHfpConnected = false;
                    HfpStatusChanged?.Invoke(false, "Audio transferred to phone");
                    AppLogger.Log("BluetoothHFP", "UnregisterApp() completed — HFP released to phone.");
                    return true;
                }
                return false;
            }
            catch (Exception ex)
            {
                AppLogger.Log("BluetoothHFP", $"DisconnectHfpAsync error: {ex.Message}");
                return false;
            }
            finally
            {
                _connectLock.Release();
            }
        }

        public void SetPcMicMuted(bool muted)
        {
            try
            {
                using var enumerator = new MMDeviceEnumerator();
                MMDevice? commMic = null;
                try { commMic = enumerator.GetDefaultAudioEndpoint(DataFlow.Capture, Role.Communications); } catch { }
                if (commMic != null)
                {
                    if (muted && !commMic.AudioEndpointVolume.Mute)
                    {
                        commMic.AudioEndpointVolume.Mute = true;
                        _weMutedPcMic = true;
                        AppLogger.Log("BluetoothHFP", $"Muted Windows Communications Mic: '{commMic.FriendlyName}'");
                    }
                    else if (!muted && _weMutedPcMic)
                    {
                        commMic.AudioEndpointVolume.Mute = false;
                        _weMutedPcMic = false;
                        AppLogger.Log("BluetoothHFP", $"Unmuted Windows Communications Mic: '{commMic.FriendlyName}'");
                    }
                }
            }
            catch (Exception ex)
            {
                AppLogger.Log("BluetoothHFP", $"SetPcMicMuted error: {ex.Message}");
            }
        }

        public void LogWasapiEndpoints(string contextTag)
        {
            try
            {
                using var enumerator = new MMDeviceEnumerator();
                var renderDevices = enumerator.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active);
                var captureDevices = enumerator.EnumerateAudioEndPoints(DataFlow.Capture, DeviceState.Active);

                foreach (var dev in renderDevices)
                {
                    try
                    {
                        string name = dev.FriendlyName;
                        if (name.Contains("vivo", StringComparison.OrdinalIgnoreCase) ||
                            name.Contains("Hands-Free", StringComparison.OrdinalIgnoreCase) ||
                            name.Contains("Headset", StringComparison.OrdinalIgnoreCase))
                        {
                            AppLogger.Log("BluetoothHFP", $"[{contextTag}] WASAPI Render: '{name}' State={dev.State} ID={dev.ID}");
                        }
                    }
                    catch { }
                }

                foreach (var dev in captureDevices)
                {
                    try
                    {
                        string name = dev.FriendlyName;
                        if (name.Contains("vivo", StringComparison.OrdinalIgnoreCase) ||
                            name.Contains("Hands-Free", StringComparison.OrdinalIgnoreCase) ||
                            name.Contains("Headset", StringComparison.OrdinalIgnoreCase))
                        {
                            AppLogger.Log("BluetoothHFP", $"[{contextTag}] WASAPI Capture: '{name}' State={dev.State} ID={dev.ID}");
                        }
                    }
                    catch { }
                }
            }
            catch (Exception ex)
            {
                AppLogger.Log("BluetoothHFP", $"LogWasapiEndpoints error: {ex.Message}");
            }
        }

        public void Stop()
        {
            _isStarted = false;
            IsHfpConnected = false;
            try { _watcher?.Stop(); } catch { }
            try { _lineWatcher?.Stop(); } catch { }
            _watcher = null;
            _lineWatcher = null;
        }
    }
}
