using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading.Tasks;
using dropp.Windows.Core.Diagnostics;

namespace dropp.Windows.Services
{
    public class FileTransferService
    {
        private const int FilePort = 5555;
        public string _targetIp { get; set; } = "";

        public void SetTargetIp(string ip)
        {
            if (string.IsNullOrWhiteSpace(ip) || _targetIp == ip) return;
            _targetIp = ip;
            AppLogger.Log("FileTransfer", $"Target IP set to {_targetIp}");
        }

        public async Task SendFileAsync(string filePath)
        {
            if (string.IsNullOrEmpty(_targetIp))
            {
                AppLogger.Log("FileTransfer", "Target IP is not set. Cannot send file.");
                return;
            }

            if (!File.Exists(filePath))
            {
                AppLogger.Log("FileTransfer", $"File does not exist: {filePath}");
                return;
            }

            try
            {
                FileInfo fileInfo = new FileInfo(filePath);
                string fileName = fileInfo.Name;
                long fileSize = fileInfo.Length;

                AppLogger.Log("FileTransfer", $"Preparing to send: {fileName} ({fileSize} bytes) to {_targetIp}");

                using var client = new TcpClient();
                await client.ConnectAsync(_targetIp, FilePort);
                using var netStream = client.GetStream();

                // 1. Construct and send the metadata header
                string header = $"FILENAME:{fileName}|SIZE:{fileSize}\n";
                byte[] headerBytes = Encoding.UTF8.GetBytes(header);
                await netStream.WriteAsync(headerBytes, 0, headerBytes.Length);
                await netStream.FlushAsync();

                // 2. Stream the file in 64KB chunks
                using var fileStream = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.Read);
                byte[] buffer = new byte[64 * 1024]; // 64KB chunk size
                int bytesRead;
                long totalSent = 0;

                while ((bytesRead = await fileStream.ReadAsync(buffer, 0, buffer.Length)) > 0)
                {
                    await netStream.WriteAsync(buffer, 0, bytesRead);
                    totalSent += bytesRead;
                }

                await netStream.FlushAsync();
                AppLogger.Log("FileTransfer", $"Successfully sent 100% of {fileName}!");
            }
            catch (Exception ex)
            {
                AppLogger.Log("FileTransfer", $"Transfer failed: {ex.Message}");
            }
        }

        public void Start()
        {
            Task.Run(ListenForIncomingFilesAsync);
        }

        private async Task ListenForIncomingFilesAsync()
        {
            var listener = new TcpListener(IPAddress.Any, FilePort);
            listener.Start();
            AppLogger.Log("FileTransfer", $"Windows Receiver listening on port {FilePort}");

            while (true)
            {
                try
                {
                    // 1. Wait for a new connection
                    var client = await listener.AcceptTcpClientAsync();

                    // Learn the peer IP automatically from incoming connection
                    if (client.Client.RemoteEndPoint is IPEndPoint remoteEndPoint)
                    {
                        string peerIp = remoteEndPoint.Address.ToString();
                        SetTargetIp(peerIp);
                        App.ClipboardService?.SetTargetIp(peerIp);
                    }

                    // 2. Fire and Forget! Handle the downloading on a separate background thread
                    _ = Task.Run(async () =>
                    {
                        try
                        {
                            using (client)
                            using (var stream = client.GetStream())
                            {
                                // 1. Read Header Byte-by-Byte
                                List<byte> headerBuffer = new List<byte>();
                                int b;
                                while ((b = stream.ReadByte()) != -1)
                                {
                                    if (b == '\n') break;
                                    headerBuffer.Add((byte)b);
                                }

                                string header = Encoding.UTF8.GetString(headerBuffer.ToArray()).Trim();
                                if (string.IsNullOrEmpty(header)) return;

                                // 2. Parse the Header safely
                                string[] parts = header.Split('|');
                                string fileName = parts[0].Substring(parts[0].IndexOf(':') + 1).Trim();
                                long fileSize = long.Parse(parts[1].Substring(parts[1].IndexOf(':') + 1).Trim());

                                AppLogger.Log("FileTransfer", $"Incoming file detected: {fileName} ({fileSize} bytes)");

                                // 3. Create the "Downloads/dropp" directory safely
                                string downloadsPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "dropp");
                                Directory.CreateDirectory(downloadsPath);
                                string savePath = Path.Combine(downloadsPath, fileName);

                                // 4. Stream the file in 64KB chunks
                                using var fileStream = new FileStream(savePath, FileMode.Create, FileAccess.Write, FileShare.None);
                                byte[] buffer = new byte[64 * 1024];
                                long totalRead = 0;

                                while (totalRead < fileSize)
                                {
                                    int bytesToRead = (int)Math.Min(buffer.Length, fileSize - totalRead);
                                    int read = await stream.ReadAsync(buffer, 0, bytesToRead);

                                    if (read == 0) break;

                                    await fileStream.WriteAsync(buffer, 0, read);
                                    totalRead += read;
                                }

                                AppLogger.Log("FileTransfer", $"Successfully received 100% of {fileName}!");
                            }
                        }
                        catch (Exception ex)
                        {
                            AppLogger.Log("FileTransfer", $"Error during background transfer: {ex.Message}");
                        }
                    });
                }
                catch (Exception ex)
                {
                    AppLogger.Log("FileTransfer", $"Listener error: {ex.Message}");
                }
            }
        }
    }
}