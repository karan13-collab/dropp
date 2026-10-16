using Makaretu.Dns;
using System;
using System.Diagnostics;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Threading.Tasks;
using Zeroconf;
using dropp.Windows.Core.Diagnostics;
using dropp.Windows.Services;

namespace dropp.Windows.Core.Networking
{
    public class MdnsManager
    {
        private readonly ServiceDiscovery _serviceDiscovery;
        private ServiceProfile? _serviceProfile;
        private string _wifiIp;
        private readonly object _profileLock = new object();

        public MdnsManager()
        {
            _serviceDiscovery = new ServiceDiscovery();

            // Explicitly resolve the physical Wi-Fi adapter IP
            _wifiIp = GetPhysicalWifiIp();
            AppLogger.Log("mDNS", $"Binding advertisement to Wi-Fi IP: {_wifiIp}");

            CreateServiceProfile(_wifiIp);

            // Listen for network changes (e.g., connecting to hotspot, switching Wi-Fi)
            NetworkChange.NetworkAddressChanged += OnNetworkAddressChanged;
        }

        private void CreateServiceProfile(string ip)
        {
            lock (_profileLock)
            {
                _serviceProfile = new ServiceProfile(
                    "dropp_windows",
                    "_dropp._tcp",
                    8765,
                    new[] { IPAddress.Parse(ip) });
            }
        }

        private void OnNetworkAddressChanged(object? sender, EventArgs e)
        {
            UpdateWifiIpAndReAdvertise();
        }

        public void UpdateWifiIpAndReAdvertise()
        {
            try
            {
                string newIp = GetPhysicalWifiIp();
                if (newIp != _wifiIp && newIp != "127.0.0.1")
                {
                    AppLogger.Log("mDNS", $"Network change detected! Updating IP from {_wifiIp} to {newIp}");

                    lock (_profileLock)
                    {
                        if (_serviceProfile != null)
                        {
                            try { _serviceDiscovery.Unadvertise(_serviceProfile); } catch { }
                        }

                        _wifiIp = newIp;
                        CreateServiceProfile(_wifiIp);
                    }

                    StartBroadcasting();
                }
            }
            catch (Exception ex)
            {
                AppLogger.Log("mDNS", $"Error updating advertisement on network change: {ex.Message}");
            }
        }

        /// <summary>
        /// Returns the IPv4 address of the first operational physical Wi-Fi adapter.
        /// Falls back to the first non-loopback IPv4 address if no wireless adapter is found.
        /// </summary>
        public static string GetPhysicalWifiIp()
        {
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                // Only consider physical wireless adapters that are actually up
                if (nic.NetworkInterfaceType != NetworkInterfaceType.Wireless80211
                    || nic.OperationalStatus != OperationalStatus.Up)
                    continue;

                foreach (var addr in nic.GetIPProperties().UnicastAddresses)
                {
                    if (addr.Address.AddressFamily == AddressFamily.InterNetwork
                        && !IPAddress.IsLoopback(addr.Address))
                    {
                        return addr.Address.ToString();
                    }
                }
            }

            // Fallback: first non-loopback IPv4 (should not normally be reached)
            AppLogger.Log("mDNS", "WARNING: No active Wi-Fi adapter found, falling back to first non-loopback IPv4.");
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != OperationalStatus.Up) continue;
                foreach (var addr in nic.GetIPProperties().UnicastAddresses)
                {
                    if (addr.Address.AddressFamily == AddressFamily.InterNetwork
                        && !IPAddress.IsLoopback(addr.Address))
                    {
                        return addr.Address.ToString();
                    }
                }
            }

            return "127.0.0.1";
        }

        public void StartBroadcasting()
        {
            try
            {
                lock (_profileLock)
                {
                    if (_serviceProfile != null)
                    {
                        _serviceDiscovery.Advertise(_serviceProfile);
                        AppLogger.Log("mDNS", $"Broadcasting dropp_windows on port 8765 from {_wifiIp}");
                    }
                }
            }
            catch (Exception ex)
            {
                AppLogger.Log("mDNS", $"Failed to start broadcasting: {ex.Message}");
            }
        }

        public async Task StartListeningAsync()
        {
            try
            {
                // Continuous scan for the Android peer
                while (true)
                {
                    // Check if our own IP changed
                    UpdateWifiIpAndReAdvertise();

                    var results = await ZeroconfResolver.ResolveAsync("_dropp._tcp.local.");

                    foreach (var host in results)
                    {
                        var service = host.Services.Values.FirstOrDefault();
                        var port = service?.Port ?? 8765;

                        // Exclude our own PC, react to anything else
                        if (!host.DisplayName.Contains("dropp_windows"))
                        {
                            string peerIp = host.IPAddress.ToString();
                            AppLogger.Log("mDNS", $"Found External Peer: {host.DisplayName} at {peerIp}:{port}");
                            CommandReceiverService.SetTargetAndroidIp(peerIp);
                            App.ClipboardService?.SetTargetIp(peerIp);
                            App.FileTransfer?.SetTargetIp(peerIp);
                        }
                    }

                    await Task.Delay(5000); // Wait 5 seconds before scanning again
                }
            }
            catch (Exception ex)
            {
                AppLogger.Log("mDNS", $"Failed to start listening: {ex.Message}");
            }
        }
    }
}