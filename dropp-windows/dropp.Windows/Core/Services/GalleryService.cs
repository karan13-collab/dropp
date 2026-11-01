using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.IO;
using System.Text.Json.Nodes;
using System.Threading.Tasks;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Storage.Streams;
using dropp.Windows.Core.Diagnostics;

namespace dropp.Windows.Services
{
    public class GalleryAlbumItem
    {
        public string Id { get; set; } = "";
        public string Name { get; set; } = "";
        public int Count { get; set; }

        public string DisplayTitle => $"{GetAlbumEmoji(Name)} {Name} ({Count})";

        private static string GetAlbumEmoji(string name)
        {
            string lower = name.ToLowerInvariant();
            if (lower == "all" || lower == "all media") return "🖼";
            if (lower.Contains("camera") || lower.Contains("dcim")) return "📸";
            if (lower.Contains("screenshot")) return "📱";
            if (lower.Contains("whatsapp")) return "💬";
            if (lower.Contains("download")) return "📥";
            if (lower.Contains("video")) return "🎬";
            if (lower.Contains("instagram")) return "📷";
            if (lower.Contains("picture")) return "📁";
            return "📁";
        }
    }

    public class GalleryItem : INotifyPropertyChanged
    {
        public event PropertyChangedEventHandler? PropertyChanged;

        public string Id { get; set; } = "";
        public string Name { get; set; } = "";
        public long Date { get; set; }
        public long Size { get; set; }
        public string Type { get; set; } = "IMAGE";
        public int Width { get; set; }
        public int Height { get; set; }
        public string ThumbnailBase64 { get; set; } = "";

        private BitmapImage? _thumbnailImage;
        public BitmapImage? ThumbnailImage
        {
            get => _thumbnailImage;
            set
            {
                if (_thumbnailImage != value)
                {
                    _thumbnailImage = value;
                    PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(ThumbnailImage)));
                }
            }
        }

        public bool IsVideo => Type.Equals("VIDEO", StringComparison.OrdinalIgnoreCase);
        public Visibility VideoBadgeVisibility => IsVideo ? Visibility.Visible : Visibility.Collapsed;

        public string FormattedSize
        {
            get
            {
                if (Size < 1024) return $"{Size} B";
                if (Size < 1024 * 1024) return $"{Size / 1024.0:F1} KB";
                return $"{Size / (1024.0 * 1024.0):F1} MB";
            }
        }

        public string FormattedDate
        {
            get
            {
                if (Date <= 0) return "";
                try
                {
                    var dt = DateTimeOffset.FromUnixTimeMilliseconds(Date).ToLocalTime();
                    return dt.ToString("MMM d, yyyy");
                }
                catch
                {
                    return "";
                }
            }
        }

        public void LoadThumbnail(DispatcherQueue dispatcher)
        {
            if (string.IsNullOrWhiteSpace(ThumbnailBase64)) return;

            Task.Run(() =>
            {
                try
                {
                    byte[] bytes = Convert.FromBase64String(ThumbnailBase64);
                    dispatcher.TryEnqueue(async () =>
                    {
                        try
                        {
                            var stream = new InMemoryRandomAccessStream();
                            using (var writer = new DataWriter(stream))
                            {
                                writer.WriteBytes(bytes);
                                await writer.StoreAsync();
                                await writer.FlushAsync();
                                writer.DetachStream();
                            }
                            stream.Seek(0);

                            var bmp = new BitmapImage();
                            await bmp.SetSourceAsync(stream);
                            ThumbnailImage = bmp;
                        }
                        catch (Exception ex)
                        {
                            AppLogger.Log("GalleryItem", $"Error setting BitmapImage for {Name}: {ex.Message}");
                        }
                    });
                }
                catch (Exception ex)
                {
                    AppLogger.Log("GalleryItem", $"Base64 decode error for {Name}: {ex.Message}");
                }
            });
        }
    }

    public class GalleryResponseEventArgs : EventArgs
    {
        public string RequestId { get; set; } = "";
        public string Bucket { get; set; } = "ALL";
        public List<GalleryItem> Items { get; set; } = new();
        public string? ErrorMessage { get; set; }
    }

    public class GalleryService
    {
        private static GalleryService? _instance;
        public static GalleryService Instance => _instance ??= new GalleryService();

        public event EventHandler<GalleryResponseEventArgs>? GalleryReceived;
        public event EventHandler<List<GalleryAlbumItem>>? AlbumsReceived;

        private string? _pendingRequestId;

        private GalleryService() { }

        public async Task RequestAlbumsAsync()
        {
            try
            {
                var req = new JsonObject
                {
                    ["type"] = "GALLERY_ALBUMS_REQ",
                    ["Type"] = "GALLERY_ALBUMS_REQ",
                    ["requestId"] = Guid.NewGuid().ToString(),
                    ["RequestId"] = Guid.NewGuid().ToString()
                };

                AppLogger.Log("GalleryService", "Dispatching GALLERY_ALBUMS_REQ to Android...");
                await CommandReceiverService.SendCommandAsync(req.ToJsonString());
            }
            catch (Exception ex)
            {
                AppLogger.Log("GalleryService", $"Error requesting albums: {ex.Message}");
            }
        }

        public async Task<string> RequestGalleryAsync(string bucket = "ALL", int limit = 60, int offset = 0)
        {
            string requestId = Guid.NewGuid().ToString();
            _pendingRequestId = requestId;

            var req = new JsonObject
            {
                ["type"] = "GALLERY_REQ",
                ["Type"] = "GALLERY_REQ",
                ["bucket"] = bucket,
                ["Bucket"] = bucket,
                ["limit"] = limit,
                ["Limit"] = limit,
                ["offset"] = offset,
                ["Offset"] = offset,
                ["requestId"] = requestId,
                ["RequestId"] = requestId
            };

            AppLogger.Log("GalleryService", $"Dispatching GALLERY_REQ to Android: bucket='{bucket}', limit={limit}, id='{requestId}'");
            bool sent = await CommandReceiverService.SendCommandAsync(req.ToJsonString());
            if (!sent)
            {
                AppLogger.Log("GalleryService", "Failed to send GALLERY_REQ to Android (device unreachable).");
                GalleryReceived?.Invoke(this, new GalleryResponseEventArgs
                {
                    RequestId = requestId,
                    Bucket = bucket,
                    Items = new List<GalleryItem>(),
                    ErrorMessage = "Cannot reach Android phone. Ensure phone is connected to the same network."
                });
                return requestId;
            }

            // Launch 8-second timeout guard
            _ = Task.Run(async () =>
            {
                await Task.Delay(8000);
                if (_pendingRequestId == requestId)
                {
                    _pendingRequestId = null;
                    AppLogger.Log("GalleryService", $"Gallery request {requestId} timed out without response from Android.");
                    GalleryReceived?.Invoke(this, new GalleryResponseEventArgs
                    {
                        RequestId = requestId,
                        Bucket = bucket,
                        Items = new List<GalleryItem>(),
                        ErrorMessage = "Gallery request timed out. No response received from phone."
                    });
                }
            });

            return requestId;
        }

        public async Task HandleIncomingAlbumsResponseAsync(JsonNode json)
        {
            try
            {
                var albumsNode = (json["albums"] ?? json["Albums"])?.AsArray();
                var list = new List<GalleryAlbumItem>();
                if (albumsNode != null)
                {
                    foreach (var node in albumsNode)
                    {
                        if (node == null) continue;
                        string id = (node["id"] ?? node["Id"])?.ToString() ?? "";
                        string name = (node["name"] ?? node["Name"])?.ToString() ?? "";
                        int count = (node["count"] ?? node["Count"])?.GetValue<int>() ?? 0;
                        list.Add(new GalleryAlbumItem
                        {
                            Id = id,
                            Name = name,
                            Count = count
                        });
                    }
                }

                AppLogger.Log("GalleryService", $"Received GALLERY_ALBUMS_RES from Android: {list.Count} real albums.");
                AlbumsReceived?.Invoke(this, list);
            }
            catch (Exception ex)
            {
                AppLogger.Log("GalleryService", $"Error parsing GALLERY_ALBUMS_RES: {ex.Message}");
            }
        }

        public async Task HandleIncomingGalleryResponseAsync(JsonNode json)
        {
            try
            {
                string requestId = (json["requestId"] ?? json["RequestId"])?.ToString() ?? "";
                string bucket = (json["bucket"] ?? json["Bucket"])?.ToString() ?? "ALL";
                var itemsNode = (json["items"] ?? json["Items"])?.AsArray();

                var list = new List<GalleryItem>();

                if (itemsNode != null)
                {
                    foreach (var item in itemsNode)
                    {
                        if (item == null) continue;
                        string id = (item["id"] ?? item["Id"])?.ToString() ?? "";
                        string name = (item["name"] ?? item["Name"])?.ToString() ?? "";
                        long date = (item["date"] ?? item["Date"])?.GetValue<long>() ?? 0;
                        long size = (item["size"] ?? item["Size"])?.GetValue<long>() ?? 0;
                        string type = (item["type"] ?? item["Type"])?.ToString()?.ToUpperInvariant() ?? "IMAGE";
                        int width = (item["width"] ?? item["Width"])?.GetValue<int>() ?? 0;
                        int height = (item["height"] ?? item["Height"])?.GetValue<int>() ?? 0;
                        string thumbB64 = (item["thumbnail"] ?? item["Thumbnail"])?.ToString() ?? "";

                        list.Add(new GalleryItem
                        {
                            Id = id,
                            Name = name,
                            Date = date,
                            Size = size,
                            Type = type,
                            Width = width,
                            Height = height,
                            ThumbnailBase64 = thumbB64
                        });
                    }
                }

                _pendingRequestId = null;
                AppLogger.Log("GalleryService", $"Received GALLERY_RES from Android: {list.Count} media items for bucket='{bucket}'");

                GalleryReceived?.Invoke(this, new GalleryResponseEventArgs
                {
                    RequestId = requestId,
                    Bucket = bucket,
                    Items = list,
                    ErrorMessage = null
                });
            }
            catch (Exception ex)
            {
                AppLogger.Log("GalleryService", $"Error parsing GALLERY_RES: {ex.Message}");
            }
        }
    }
}
