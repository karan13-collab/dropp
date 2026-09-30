using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading.Tasks;
using Windows.ApplicationModel.DataTransfer;
using Windows.Storage;
using Windows.Storage.Streams;
using Microsoft.UI.Dispatching;

namespace dropp.Windows.Services
{
    public class ClipboardSyncService
    {
        private const int Port = 8766;
        private string _targetIp;

        private string _lastSyncedText = string.Empty;
        private DateTime _lastSyncTime = DateTime.MinValue;
        private readonly object _syncLock = new object();
        private readonly DispatcherQueue _dispatcherQueue;

        public ClipboardSyncService(DispatcherQueue dispatcherQueue)
        {
            _dispatcherQueue = dispatcherQueue;
        }

        public void SetTargetIp(string ip)
        {
            _targetIp = ip;
            Debug.WriteLine($"[Clipboard] Target IP set to {_targetIp}");
        }

        public void Start()
        {
            Task.Run(ListenForIncomingClipboardAsync);

            // This boots up the newly upgraded media watcher
            StartWindowsMediaWatcher();

            _dispatcherQueue.TryEnqueue(() =>
            {
                Clipboard.ContentChanged += OnWindowsClipboardChanged;
            });
        }

        private string NormalizeForCache(string input)
        {
            if (string.IsNullOrWhiteSpace(input)) return string.Empty;
            return input.Replace("\r\n", "\n").Trim();
        }

        private async void OnWindowsClipboardChanged(object sender, object e)
        {
            if (string.IsNullOrEmpty(_targetIp)) return;

            lock (_syncLock)
            {
                if ((DateTime.Now - _lastSyncTime).TotalMilliseconds < 1500) return;
            }

            try
            {
                var dataPackageView = Clipboard.GetContent();

                // 1. HANDLE TEXT
                if (dataPackageView.Contains(StandardDataFormats.Text))
                {
                    string rawText = await dataPackageView.GetTextAsync();
                    string normText = NormalizeForCache(rawText);
                    if (string.IsNullOrEmpty(normText)) return;

                    lock (_syncLock)
                    {
                        if (normText == _lastSyncedText) return;
                        _lastSyncTime = DateTime.Now;
                        _lastSyncedText = normText;
                    }

                    byte[] payload = Encoding.UTF8.GetBytes(rawText);
                    await SendDataAsync("TEXT", payload);
                }
                // 2. HANDLE IMAGES (Snipping Tool, Web Copies)
                else if (dataPackageView.Contains(StandardDataFormats.Bitmap))
                {
                    lock (_syncLock) { _lastSyncTime = DateTime.Now; }

                    var bmpRef = await dataPackageView.GetBitmapAsync();
                    using var stream = await bmpRef.OpenReadAsync();
                    using var classicStream = stream.AsStreamForRead();
                    using var ms = new MemoryStream();
                    await classicStream.CopyToAsync(ms);

                    Debug.WriteLine($"[Clipboard] Sending Bitmap Image...");
                    await SendDataAsync("IMAGE", ms.ToArray());
                }
                // 3. HANDLE IMAGE FILES (Ctrl+C on a file in File Explorer)
                else if (dataPackageView.Contains(StandardDataFormats.StorageItems))
                {
                    var items = await dataPackageView.GetStorageItemsAsync();
                    if (items.Count > 0 && items[0] is StorageFile file)
                    {
                        string ext = file.FileType.ToLower();
                        if (ext == ".png" || ext == ".jpg" || ext == ".jpeg" || ext == ".bmp")
                        {
                            lock (_syncLock) { _lastSyncTime = DateTime.Now; }

                            using var stream = await file.OpenStreamForReadAsync();
                            using var ms = new MemoryStream();
                            await stream.CopyToAsync(ms);

                            Debug.WriteLine($"[Clipboard] Sending Image File from Explorer...");
                            await SendDataAsync("IMAGE", ms.ToArray());
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"[Clipboard] Read error: {ex.Message}");
            }
        }

        private async Task SendDataAsync(string type, byte[] payload)
        {
            try
            {
                using var client = new TcpClient();
                await client.ConnectAsync(_targetIp, Port);
                using var stream = client.GetStream();

                // Send Header
                string header = $"TYPE:{type}|SIZE:{payload.Length}\n";
                byte[] headerBytes = Encoding.UTF8.GetBytes(header);
                await stream.WriteAsync(headerBytes, 0, headerBytes.Length);

                // Send Payload
                await stream.WriteAsync(payload, 0, payload.Length);
                await stream.FlushAsync();
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"[Clipboard] TCP Send failed: {ex.Message}");
            }
        }

        private async Task ListenForIncomingClipboardAsync()
        {
            var listener = new TcpListener(IPAddress.Any, Port);
            listener.Start();

            while (true)
            {
                try
                {
                    using var client = await listener.AcceptTcpClientAsync();
                    using var stream = client.GetStream();

                    // Read Header byte-by-byte so we don't consume binary data
                    List<byte> headerBuffer = new List<byte>();
                    int b;
                    while ((b = stream.ReadByte()) != -1)
                    {
                        if (b == '\n') break;
                        headerBuffer.Add((byte)b);
                    }

                    string header = Encoding.UTF8.GetString(headerBuffer.ToArray());
                    if (string.IsNullOrEmpty(header)) continue;

                    string[] parts = header.Split('|');
                    string type = parts[0].Split(':')[1];
                    int size = int.Parse(parts[1].Split(':')[1]);

                    // Read exact payload bytes
                    byte[] payload = new byte[size];
                    int totalRead = 0;
                    while (totalRead < size)
                    {
                        int read = await stream.ReadAsync(payload, totalRead, size - totalRead);
                        if (read == 0) break;
                        totalRead += read;
                    }

                    lock (_syncLock) { _lastSyncTime = DateTime.Now; }

                    if (type == "TEXT")
                    {
                        string rawReceived = Encoding.UTF8.GetString(payload);
                        string normReceived = NormalizeForCache(rawReceived);

                        lock (_syncLock) { _lastSyncedText = normReceived; }

                        _dispatcherQueue.TryEnqueue(() =>
                        {
                            var dataPackage = new DataPackage();
                            dataPackage.SetText(rawReceived);
                            Clipboard.SetContent(dataPackage);
                        });
                    }
                    else if (type == "IMAGE")
                    {
                        // Save binary array to a temp file and inject it into Windows Clipboard
                        _dispatcherQueue.TryEnqueue(async () =>
                        {
                            try
                            {
                                // 1. Give it a unique name with a timestamp so pasting into folders doesn't overwrite older pastes
                                string fileName = $"dropp_img_{DateTime.Now:yyyyMMdd_HHmmss}.png";
                                string tempFilePath = Path.Combine(Path.GetTempPath(), fileName);

                                // 2. Write the bytes to the disk
                                await File.WriteAllBytesAsync(tempFilePath, payload);

                                // 3. Convert the standard file path into a WinRT StorageFile
                                StorageFile storageFile = await StorageFile.GetFileFromPathAsync(tempFilePath);

                                // 4. Inject MULTIPLE formats into the Windows Clipboard
                                var dataPackage = new DataPackage();

                                // Format A: For Paint, Photoshop, Discord (Raw pixels)
                                dataPackage.SetBitmap(RandomAccessStreamReference.CreateFromFile(storageFile));

                                // Format B: For File Explorer (Actual physical file)
                                dataPackage.SetStorageItems(new[] { storageFile });

                                Clipboard.SetContent(dataPackage);

                                Debug.WriteLine("[Clipboard] Image successfully applied to Windows clipboard (Bitmap + File)!");
                            }
                            catch (Exception ex)
                            {
                                Debug.WriteLine($"[Clipboard] Failed to set image to clipboard: {ex.Message}");
                            }
                        });
                    }
                }
                catch (Exception ex)
                {
                    Debug.WriteLine($"[Clipboard] TCP Receive error: {ex.Message}");
                }
            }
        }
        private void StartWindowsMediaWatcher()
        {
            // Gets your dynamic Pictures path (handles OneDrive and localized languages natively)
            string picturesPath = Environment.GetFolderPath(Environment.SpecialFolder.MyPictures);
            WatchFolder(picturesPath);
        }

        private void WatchFolder(string folderPath)
        {
            if (!Directory.Exists(folderPath)) return;

            var watcher = new FileSystemWatcher(folderPath)
            {
                NotifyFilter = NotifyFilters.FileName | NotifyFilters.CreationTime,
                Filter = "*.*",
                IncludeSubdirectories = true, // Magic Fix: Catches files no matter what the sub-folders are named!
                EnableRaisingEvents = true
            };

            watcher.Created += async (sender, e) =>
            {
                string ext = Path.GetExtension(e.FullPath).ToLower();
                if (ext == ".jpg" || ext == ".jpeg" || ext == ".png" || ext == ".bmp")
                {
                    // Smart Retry Loop: Wait until the Camera app finishes saving the file
                    bool fileIsReady = false;
                    int retries = 5;
                    while (!fileIsReady && retries > 0)
                    {
                        try
                        {
                            using (var fs = File.OpenRead(e.FullPath)) { } // Test if we can read it
                            fileIsReady = true;
                        }
                        catch (IOException)
                        {
                            retries--;
                            await Task.Delay(500);
                        }
                    }

                    if (!fileIsReady) return; // Give up if it stays locked forever

                    _dispatcherQueue.TryEnqueue(async () =>
                    {
                        try
                        {
                            Debug.WriteLine($"[Clipboard] New Windows media detected at {e.FullPath}. Auto-copying...");

                            StorageFile storageFile = await StorageFile.GetFileFromPathAsync(e.FullPath);
                            var dataPackage = new DataPackage();

                            dataPackage.SetBitmap(RandomAccessStreamReference.CreateFromFile(storageFile));
                            dataPackage.SetStorageItems(new[] { storageFile });

                            Clipboard.SetContent(dataPackage);
                        }
                        catch (Exception ex)
                        {
                            Debug.WriteLine($"[Clipboard] Auto-copy failed: {ex.Message}");
                        }
                    });
                }
            };
        }
    }
}