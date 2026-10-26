using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Windows.Devices.Enumeration;
using Windows.Media.Audio;
using dropp.Windows.Core.Diagnostics;

namespace dropp.Windows.Services
{
    public class BluetoothAudioSinkService
    {
        private static BluetoothAudioSinkService? _instance;
        public static BluetoothAudioSinkService Instance => _instance ??= new BluetoothAudioSinkService();

        private readonly ConcurrentDictionary<string, DeviceInformation> _knownDevices = new();
        private readonly ConcurrentDictionary<string, AudioPlaybackConnection> _connections = new();
        private DeviceWatcher? _deviceWatcher;
        private bool _isStarted;

        public bool IsRoutingEnabled { get; private set; } = false; // Default OFF: Audio plays on phone
        public bool IsSinkActive => _connections.Values.Any(c => c.State == AudioPlaybackConnectionState.Opened);
        public event Action<bool, string>? SinkStatusChanged;

        private BluetoothAudioSinkService() { }

        public async Task StartAsync()
        {
            if (_isStarted) return;
            _isStarted = true;

            AppLogger.Log("BluetoothAudioSink", "Starting Bluetooth Audio Sink Watcher (Routing default: OFF)...");

            try
            {
                string selector = AudioPlaybackConnection.GetDeviceSelector();
                var devices = await DeviceInformation.FindAllAsync(selector);

                AppLogger.Log("BluetoothAudioSink", $"Found {devices.Count} paired Bluetooth audio source devices.");
                foreach (var device in devices)
                {
                    if (!string.IsNullOrEmpty(device.Id))
                    {
                        _knownDevices[device.Id] = device;
                        AppLogger.Log("BluetoothAudioSink", $"Registered known audio device: {device.Name} ({device.Id})");
                    }
                }

                // Start watching for devices paired or connected later
                _deviceWatcher = DeviceInformation.CreateWatcher(selector);
                _deviceWatcher.Added += (watcher, deviceInfo) =>
                {
                    AppLogger.Log("BluetoothAudioSink", $"DeviceWatcher: Audio device added: {deviceInfo.Name} ({deviceInfo.Id})");
                    _knownDevices[deviceInfo.Id] = deviceInfo;
                    if (IsRoutingEnabled)
                    {
                        _ = ConnectDeviceAsync(deviceInfo);
                    }
                };

                _deviceWatcher.Removed += (watcher, deviceInfoUpdate) =>
                {
                    AppLogger.Log("BluetoothAudioSink", $"DeviceWatcher: Audio device removed: {deviceInfoUpdate.Id}");
                    _knownDevices.TryRemove(deviceInfoUpdate.Id, out _);
                    RemoveDevice(deviceInfoUpdate.Id);
                };

                _deviceWatcher.Updated += async (watcher, deviceInfoUpdate) =>
                {
                    if (!_knownDevices.ContainsKey(deviceInfoUpdate.Id))
                    {
                        var info = await DeviceInformation.CreateFromIdAsync(deviceInfoUpdate.Id);
                        if (info != null)
                        {
                            _knownDevices[info.Id] = info;
                            if (IsRoutingEnabled)
                            {
                                await ConnectDeviceAsync(info);
                            }
                        }
                    }
                };

                _deviceWatcher.Start();
                SinkStatusChanged?.Invoke(false, "Audio playing on phone (Routing is OFF)");
            }
            catch (Exception ex)
            {
                AppLogger.Log("BluetoothAudioSink", $"Failed to initialize Bluetooth Audio Sink watcher: {ex.Message}");
                SinkStatusChanged?.Invoke(false, $"Bluetooth Audio Sink error: {ex.Message}");
            }
        }

        public void EnableAudioRouting()
        {
            if (IsRoutingEnabled && IsSinkActive) return;
            IsRoutingEnabled = true;
            AppLogger.Log("BluetoothAudioSink", "User enabled audio routing -> Opening Bluetooth audio sink...");

            // Find target paired phone (specifically vivo X200 FE or active Android phone)
            DeviceInformation? targetDevice = null;
            foreach (var dev in _knownDevices.Values)
            {
                if (dev.Name.Contains("vivo", StringComparison.OrdinalIgnoreCase) ||
                    dev.Id.Contains("64447BDBE31C", StringComparison.OrdinalIgnoreCase))
                {
                    targetDevice = dev;
                    break;
                }
            }

            // Fallback: search by "phone" or any device that isn't offline/unrelated
            if (targetDevice == null)
            {
                targetDevice = _knownDevices.Values
                    .FirstOrDefault(d => d.Name.Contains("phone", StringComparison.OrdinalIgnoreCase));
            }

            if (targetDevice == null && _knownDevices.Count > 0)
            {
                // Take the first device that isn't Karan if possible
                targetDevice = _knownDevices.Values.FirstOrDefault(d => !d.Name.Contains("Karan", StringComparison.OrdinalIgnoreCase))
                               ?? _knownDevices.Values.First();
            }

            if (targetDevice == null)
            {
                AppLogger.Log("BluetoothAudioSink", "No paired phone found for audio routing.");
                SinkStatusChanged?.Invoke(false, "Waiting for phone Bluetooth connection...");
                return;
            }

            AppLogger.Log("BluetoothAudioSink", $"Selected target audio device: {targetDevice.Name} ({targetDevice.Id})");
            _ = ConnectDeviceAsync(targetDevice);
        }

        public void EnsureAllActive()
        {
            EnableAudioRouting();
        }

        public void DisableAudioRouting()
        {
            IsRoutingEnabled = false;
            AppLogger.Log("BluetoothAudioSink", "User disabled audio routing -> Closing Bluetooth audio sink...");

            foreach (var kvp in _connections)
            {
                try
                {
                    kvp.Value.Dispose();
                }
                catch { }
            }
            _connections.Clear();

            SinkStatusChanged?.Invoke(false, "Audio playing on phone (Routing is OFF)");
        }

        public void ToggleAudioRouting()
        {
            if (IsRoutingEnabled)
            {
                DisableAudioRouting();
            }
            else
            {
                EnableAudioRouting();
            }
        }

        public async Task ConnectDeviceAsync(DeviceInformation device)
        {
            if (string.IsNullOrEmpty(device.Id) || !IsRoutingEnabled) return;

            try
            {
                if (_connections.TryGetValue(device.Id, out var existingConn))
                {
                    if (existingConn.State == AudioPlaybackConnectionState.Opened)
                    {
                        AppLogger.Log("BluetoothAudioSink", $"AudioPlaybackConnection already open for {device.Name}");
                        return;
                    }
                    else
                    {
                        try { existingConn.Dispose(); } catch { }
                        _connections.TryRemove(device.Id, out _);
                    }
                }

                AppLogger.Log("BluetoothAudioSink", $"Attempting AudioPlaybackConnection for {device.Name} ({device.Id})...");
                var connection = AudioPlaybackConnection.TryCreateFromId(device.Id);
                if (connection == null)
                {
                    AppLogger.Log("BluetoothAudioSink", $"AudioPlaybackConnection.TryCreateFromId returned null for {device.Name}");
                    return;
                }

                connection.StateChanged += (conn, args) =>
                {
                    AppLogger.Log("BluetoothAudioSink", $"Connection state changed for {device.Name}: {conn.State}");
                    if (conn.State == AudioPlaybackConnectionState.Opened)
                    {
                        SinkStatusChanged?.Invoke(true, $"Active (Audio routed from {device.Name})");
                        _ = CommandReceiverService.SendAudioRoutingStatusAsync(true);
                    }
                    else if (conn.State == AudioPlaybackConnectionState.Closed)
                    {
                        SinkStatusChanged?.Invoke(false, "Audio playing on phone (Routing is OFF)");
                        _ = CommandReceiverService.SendAudioRoutingStatusAsync(false);
                        if (IsRoutingEnabled)
                        {
                            // Reconnect only if user still wants routing enabled
                            Task.Run(async () =>
                            {
                                await Task.Delay(2000);
                                if (IsRoutingEnabled)
                                {
                                    await EnsureOpenAsync(conn, device.Name);
                                }
                            });
                        }
                    }
                };

                connection.Start();
                _connections[device.Id] = connection;

                // Open the connection asynchronously with retry so Windows holds the audio sink open
                await EnsureOpenAsync(connection, device.Name);
            }
            catch (Exception ex)
            {
                AppLogger.Log("BluetoothAudioSink", $"Error creating AudioPlaybackConnection for {device.Name}: {ex.Message}");
            }
        }

        private async Task EnsureOpenAsync(AudioPlaybackConnection connection, string deviceName)
        {
            if (!IsRoutingEnabled) return;

            for (int attempt = 1; attempt <= 3 && IsRoutingEnabled; attempt++)
            {
                try
                {
                    if (connection.State == AudioPlaybackConnectionState.Opened)
                    {
                        AppLogger.Log("BluetoothAudioSink", $"Connection already Opened for {deviceName}.");
                        SinkStatusChanged?.Invoke(true, $"Active (Audio routed from {deviceName})");
                        await CommandReceiverService.SendAudioRoutingStatusAsync(true);
                        return;
                    }

                    AppLogger.Log("BluetoothAudioSink", $"Opening AudioPlaybackConnection for {deviceName} (Attempt {attempt}/3)...");
                    var openResult = await connection.OpenAsync();
                    AppLogger.Log("BluetoothAudioSink", $"AudioPlaybackConnection.Open() for {deviceName} result: {openResult.Status}, ExtendedError: {openResult.ExtendedError?.Message} (0x{openResult.ExtendedError?.HResult:X8})");

                    if (openResult.Status == AudioPlaybackConnectionOpenResultStatus.Success)
                    {
                        SinkStatusChanged?.Invoke(true, $"Active (Audio routed from {deviceName})");
                        await CommandReceiverService.SendAudioRoutingStatusAsync(true);
                        return;
                    }

                    if (attempt < 3 && IsRoutingEnabled)
                    {
                        AppLogger.Log("BluetoothAudioSink", $"Waiting 1.5s for Bluetooth ACL link before retry...");
                        await Task.Delay(1500);
                    }
                }
                catch (Exception ex)
                {
                    AppLogger.Log("BluetoothAudioSink", $"Open error for {deviceName}: {ex.Message}");
                    if (attempt < 3 && IsRoutingEnabled)
                    {
                        await Task.Delay(1500);
                    }
                }
            }

            if (IsRoutingEnabled && connection.State != AudioPlaybackConnectionState.Opened)
            {
                SinkStatusChanged?.Invoke(false, "Could not open Bluetooth audio link to phone");
                await CommandReceiverService.SendAudioRoutingStatusAsync(false);
            }
        }

        private void RemoveDevice(string deviceId)
        {
            if (_connections.TryRemove(deviceId, out var conn))
            {
                try
                {
                    conn.Dispose();
                }
                catch { }
            }
            SinkStatusChanged?.Invoke(IsRoutingEnabled && IsSinkActive, 
                IsRoutingEnabled ? "Routing active" : "Audio playing on phone (Routing is OFF)");
        }

        public void Stop()
        {
            DisableAudioRouting();
            _isStarted = false;
            try
            {
                _deviceWatcher?.Stop();
            }
            catch { }
            _deviceWatcher = null;
            _knownDevices.Clear();
            SinkStatusChanged?.Invoke(false, "Stopped");
        }
    }
}
