using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net.Sockets;
using System.Text;
using System.Threading.Tasks;

namespace dropp.Windows.Services
{
    public class FileTransferService
    {
        private const int FilePort = 8767;
        private string _targetIp;

        public void SetTargetIp(string ip)
        {
            _targetIp = ip;
            Debug.WriteLine($"[FileTransfer] Target IP set to {_targetIp}");
        }

        public async Task SendFileAsync(string filePath)
        {
            if (string.IsNullOrEmpty(_targetIp))
            {
                Debug.WriteLine("[FileTransfer] Target IP is not set. Cannot send file.");
                return;
            }

            if (!File.Exists(filePath))
            {
                Debug.WriteLine($"[FileTransfer] File does not exist: {filePath}");
                return;
            }

            try
            {
                FileInfo fileInfo = new FileInfo(filePath);
                string fileName = fileInfo.Name;
                long fileSize = fileInfo.Length;

                Debug.WriteLine($"[FileTransfer] Preparing to send: {fileName} ({fileSize} bytes)");

                using var client = new TcpClient();
                await client.ConnectAsync(_targetIp, FilePort);
                using var netStream = client.GetStream();

                // 1. Construct and send the metadata header
                string header = $"FILENAME:{fileName}|SIZE:{fileSize}\n";
                byte[] headerBytes = Encoding.UTF8.GetBytes(header);
                await netStream.WriteAsync(headerBytes, 0, headerBytes.Length);

                // 2. Stream the file in 64KB chunks
                using var fileStream = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.Read);
                byte[] buffer = new byte[64 * 1024]; // 64KB chunk size
                int bytesRead;
                long totalSent = 0;

                while ((bytesRead = await fileStream.ReadAsync(buffer, 0, buffer.Length)) > 0)
                {
                    await netStream.WriteAsync(buffer, 0, bytesRead);
                    totalSent += bytesRead;

                    // (Later we will add UI progress bar updates right here using totalSent / fileSize)
                }

                await netStream.FlushAsync();
                Debug.WriteLine($"[FileTransfer] Successfully sent 100% of {fileName}!");
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"[FileTransfer] Transfer failed: {ex.Message}");
            }
        }
        public void Start()
        {
            Task.Run(ListenForIncomingFilesAsync);
        }

        private async Task ListenForIncomingFilesAsync()
        {
            var listener = new TcpListener(System.Net.IPAddress.Any, FilePort);
            listener.Start();
            Debug.WriteLine($"[FileTransfer] Windows Receiver listening on port {FilePort}");

            while (true)
            {
                try
                {
                    // 1. Wait for a new connection (Blocks here until Android sends a file)
                    var client = await listener.AcceptTcpClientAsync();

                    // 2. Fire and Forget! Handle the downloading on a separate background thread
                    _ = Task.Run(async () =>
                    {
                        try
                        {
                            // We wrap these in using blocks here so they safely close when the thread finishes
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

                                string header = Encoding.UTF8.GetString(headerBuffer.ToArray());
                                if (string.IsNullOrEmpty(header)) return;

                                // 2. Parse the Header
                                string[] parts = header.Split('|');
                                string fileName = parts[0].Split(':')[1];
                                long fileSize = long.Parse(parts[1].Split(':')[1]);

                                Debug.WriteLine($"[FileTransfer] Incoming file detected: {fileName} ({fileSize} bytes)");

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

                                Debug.WriteLine($"[FileTransfer] Successfully received 100% of {fileName}!");
                            }
                        }
                        catch (Exception ex)
                        {
                            Debug.WriteLine($"[FileTransfer] Error during background transfer: {ex.Message}");
                        }
                    });
                }
                catch (Exception ex)
                {
                    Debug.WriteLine($"[FileTransfer] Listener error: {ex.Message}");
                }
            }
        }
    }
}