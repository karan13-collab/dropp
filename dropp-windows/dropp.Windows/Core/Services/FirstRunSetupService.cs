using System;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Threading.Tasks;
using Microsoft.Win32;
using Windows.Storage;
using dropp.Windows.Core.Diagnostics;

namespace dropp.Windows.Services
{
    public class FirstRunSetupService
    {
        private const string SettingKey = "FirstRunSystemSetupCompleted";
        private static FirstRunSetupService? _instance;
        public static FirstRunSetupService Instance => _instance ??= new FirstRunSetupService();

        private FirstRunSetupService() { }

        public async Task<bool> CheckIfSetupRequiredAsync()
        {
            try
            {
                // 1. Check local settings / disk flag
                if (IsFlagCompleted())
                {
                    // If marked completed, verify services are still automatic
                    if (IsServiceAutomatic("BTAGService") && IsServiceAutomatic("BthServ"))
                    {
                        return false; // Already setup and running
                    }
                }
                return true;
            }
            catch (Exception ex)
            {
                AppLogger.Log("FirstRunSetup", $"Error checking setup status: {ex.Message}");
                return true;
            }
        }

        private bool IsFlagCompleted()
        {
            try
            {
                if (ApplicationData.Current.LocalSettings.Values.TryGetValue(SettingKey, out object? val))
                {
                    if (val is bool b && b) return true;
                }
            }
            catch { }

            try
            {
                string flagFile = Path.Combine(ApplicationData.Current.LocalFolder.Path, "setup_completed.txt");
                if (File.Exists(flagFile))
                {
                    string txt = File.ReadAllText(flagFile).Trim();
                    if (txt == "1" || txt.Equals("true", StringComparison.OrdinalIgnoreCase))
                    {
                        return true;
                    }
                }
            }
            catch { }

            return false;
        }

        private bool IsServiceAutomatic(string serviceName)
        {
            try
            {
                using var key = Registry.LocalMachine.OpenSubKey($@"SYSTEM\CurrentControlSet\Services\{serviceName}");
                if (key != null)
                {
                    object? startVal = key.GetValue("Start");
                    if (startVal is int startInt)
                    {
                        // 2 = Automatic
                        return startInt == 2;
                    }
                }
            }
            catch { }
            return false;
        }

        public async Task<bool> RunElevatedSetupScriptAsync()
        {
            AppLogger.Log("FirstRunSetup", "Initiating elevated First-Run Setup script...");

            string script = 
                "Set-Service -Name 'BTAGService' -StartupType Automatic -ErrorAction SilentlyContinue; " +
                "Start-Service -Name 'BTAGService' -ErrorAction SilentlyContinue; " +
                "Set-Service -Name 'BthServ' -StartupType Automatic -ErrorAction SilentlyContinue; " +
                "Start-Service -Name 'BthServ' -ErrorAction SilentlyContinue; " +
                "New-NetFirewallRule -DisplayName 'dropp mDNS' -Direction Inbound -Protocol UDP -LocalPort 8765 -Action Allow -Profile Any -ErrorAction SilentlyContinue; " +
                "New-NetFirewallRule -DisplayName 'dropp Services' -Direction Inbound -Protocol TCP -LocalPort 8767,5555,5556 -Action Allow -Profile Any -ErrorAction SilentlyContinue; " +
                "Get-PnpDevice -Class 'AudioEndpoint','Bluetooth' -Status OK -ErrorAction SilentlyContinue | Where-Object { $_.FriendlyName -match 'Hands-Free|Audio|Phone' } | Restart-PnpDevice -Confirm:$false -ErrorAction SilentlyContinue";

            var psi = new ProcessStartInfo
            {
                FileName = "powershell.exe",
                Arguments = $"-NoProfile -ExecutionPolicy Bypass -Command \"{script}\"",
                Verb = "runas",
                UseShellExecute = true,
                WindowStyle = ProcessWindowStyle.Hidden
            };

            try
            {
                using var proc = Process.Start(psi);
                if (proc == null)
                {
                    AppLogger.Log("FirstRunSetup", "Failed to start PowerShell setup process.");
                    return false;
                }

                await proc.WaitForExitAsync();
                AppLogger.Log("FirstRunSetup", $"Elevated setup script finished with ExitCode {proc.ExitCode}");

                // Save completed flag
                MarkSetupCompleted();

                // Start or ensure Bluetooth Audio Sink service is active
                _ = BluetoothAudioSinkService.Instance.StartAsync();

                return true;
            }
            catch (Win32Exception ex) when (ex.NativeErrorCode == 1223) // ERROR_CANCELLED (User declined UAC)
            {
                AppLogger.Log("FirstRunSetup", "User declined UAC administrator prompt.");
                return false;
            }
            catch (Exception ex)
            {
                AppLogger.Log("FirstRunSetup", $"Exception running elevated setup: {ex.Message}");
                return false;
            }
        }

        public void MarkSetupCompleted()
        {
            try
            {
                ApplicationData.Current.LocalSettings.Values[SettingKey] = true;
            }
            catch { }

            try
            {
                string flagFile = Path.Combine(ApplicationData.Current.LocalFolder.Path, "setup_completed.txt");
                File.WriteAllText(flagFile, "true");
            }
            catch { }
        }
    }
}
