using System;
using System.Diagnostics;
using System.IO;

namespace dropp.Windows.Core.Diagnostics
{
    public static class AppLogger
    {
        private static readonly object _lock = new object();
        private static string? _logFilePath;

        public static void Log(string tag, string message)
        {
            string line = $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff}] [{tag}] {message}";
            Debug.WriteLine(line);

            try
            {
                if (_logFilePath == null)
                {
                    try
                    {
                        string localFolder = global::Windows.Storage.ApplicationData.Current.LocalFolder.Path;
                        _logFilePath = Path.Combine(localFolder, "dropp.log");
                    }
                    catch
                    {
                        string fallbackDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "dropp");
                        Directory.CreateDirectory(fallbackDir);
                        _logFilePath = Path.Combine(fallbackDir, "dropp.log");
                    }
                }

                lock (_lock)
                {
                    File.AppendAllText(_logFilePath, line + Environment.NewLine);
                }
            }
            catch { }
        }
    }
}
