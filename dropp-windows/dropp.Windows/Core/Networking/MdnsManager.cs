using Makaretu.Dns;
using System;
using System.Diagnostics;
using System.Linq;
using System.Threading.Tasks;
using Zeroconf;

namespace dropp.Windows.Core.Networking
{
    public class MdnsManager
    {
        private readonly ServiceDiscovery _serviceDiscovery;
        private readonly ServiceProfile _serviceProfile;

        public MdnsManager()
        {
            _serviceDiscovery = new ServiceDiscovery();

            // Requires 3 parameters: instanceName, serviceName, port
            _serviceProfile = new ServiceProfile("dropp_windows", "_dropp._tcp", 8765);
        }

        public void StartBroadcasting()
        {
            try
            {
                // ServiceDiscovery handles the advertisement, not MulticastService
                _serviceDiscovery.Advertise(_serviceProfile);
                Debug.WriteLine("Broadcasting dropp_windows on port 8765");
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Failed to start broadcasting: {ex.Message}");
            }
        }

        public async Task StartListeningAsync()
        {
            try
            {
                // Continuous scan for the Android peer
                while (true)
                {
                    var results = await ZeroconfResolver.ResolveAsync("_dropp._tcp.local.");

                    foreach (var host in results)
                    {
                        var service = host.Services.Values.FirstOrDefault();
                        var port = service?.Port ?? 8765;

                        // 1. Log the exact raw name of EVERYTHING discovered to see what Zeroconf sees
                        Debug.WriteLine($"[DEBUG SCAN] Discovered raw name: '{host.DisplayName}' at {host.IPAddress}");

                        // 2. Exclude our own PC, react to anything else
                        if (!host.DisplayName.Contains("dropp_windows"))
                        {
                            Debug.WriteLine($"Found External Peer: {host.DisplayName} at {host.IPAddress}:{port}");
                            App.ClipboardService.SetTargetIp(host.IPAddress.ToString());
                        }
                    }

                    await Task.Delay(5000); // Wait 5 seconds before scanning again
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Failed to start listening: {ex.Message}");
            }
        }
    }
}