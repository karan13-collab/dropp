using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json.Nodes;
using System.Threading.Tasks;
using dropp.Windows.Core.Diagnostics;

namespace dropp.Windows.Services
{
    public class RemoteBrowserItem
    {
        public string Id { get; set; } = "";
        public string Name { get; set; } = "";
        public bool IsDirectory { get; set; }
        public long Size { get; set; }
        public string Type { get; set; } = "FILE";
        public long Modified { get; set; }
        public int ItemCount { get; set; }

        public string FormattedSize
        {
            get
            {
                if (IsDirectory) return ItemCount > 0 ? $"{ItemCount} item{(ItemCount > 1 ? "s" : "")}" : "Folder";
                if (Size < 1024) return $"{Size} B";
                if (Size < 1024 * 1024) return $"{Size / 1024.0:F1} KB";
                if (Size < 1024 * 1024 * 1024) return $"{Size / (1024.0 * 1024.0):F1} MB";
                return $"{Size / (1024.0 * 1024.0 * 1024.0):F2} GB";
            }
        }

        public string TypeGlyph => IsDirectory ? "\uED25" : Type switch
        {
            "PDF" => "\uEA90",      // PDF / Document glyph
            "IMAGE" => "\uEB9F",    // Photo / Image glyph
            "DOC" => "\uE8A5",      // Document / Text glyph
            "VIDEO" => "\uE8B7",    // Video glyph
            "AUDIO" => "\uE8D6",    // Audio / Music glyph
            _ => "\uE8A5"          // Generic file glyph
        };

        public string SubtitleText => IsDirectory
            ? (ItemCount > 0 ? $"{ItemCount} item{(ItemCount > 1 ? "s" : "")} • Tap to open" : "Folder • Tap to open")
            : $"{FormattedSize} • Tap to preview";

        public Microsoft.UI.Xaml.Visibility FolderButtonVisibility => IsDirectory ? Microsoft.UI.Xaml.Visibility.Visible : Microsoft.UI.Xaml.Visibility.Collapsed;
        public Microsoft.UI.Xaml.Visibility FileActionsVisibility => IsDirectory ? Microsoft.UI.Xaml.Visibility.Collapsed : Microsoft.UI.Xaml.Visibility.Visible;
    }

    public class BrowseResponseEventArgs : EventArgs
    {
        public string RequestId { get; set; } = "";
        public string CurrentPath { get; set; } = "ROOT";
        public string? ParentPath { get; set; }
        public List<RemoteBrowserItem> Items { get; set; } = new();
        public string? ErrorMessage { get; set; }
    }

    public class FileBrowserService
    {
        private static FileBrowserService? _instance;
        public static FileBrowserService Instance => _instance ??= new FileBrowserService();

        public event EventHandler<BrowseResponseEventArgs>? RemoteDirectoryChanged;
        private string? _pendingRequestId;

        private static readonly HashSet<string> SkipDirs = new(StringComparer.OrdinalIgnoreCase)
        {
            "node_modules", ".git", ".vs", "bin", "obj", "AppData", "Windows",
            "Program Files", "Program Files (x86)", "$Recycle.Bin", "System Volume Information"
        };

        private FileBrowserService() { }

        public async Task<string> BrowseRemoteAsync(string path = "ROOT")
        {
            string requestId = Guid.NewGuid().ToString();
            _pendingRequestId = requestId;

            var req = new JsonObject
            {
                ["type"] = "BROWSE_REQ",
                ["Type"] = "BROWSE_REQ",
                ["path"] = path,
                ["Path"] = path,
                ["requestId"] = requestId,
                ["RequestId"] = requestId
            };

            AppLogger.Log("FileBrowserService", $"Dispatching BROWSE_REQ to Android: path='{path}', id='{requestId}'");
            bool sent = await CommandReceiverService.SendCommandAsync(req.ToJsonString());
            if (!sent)
            {
                AppLogger.Log("FileBrowserService", "Failed to send BROWSE_REQ to Android (device unreachable).");
                RemoteDirectoryChanged?.Invoke(this, new BrowseResponseEventArgs
                {
                    RequestId = requestId,
                    CurrentPath = path,
                    Items = new List<RemoteBrowserItem>(),
                    ErrorMessage = "Cannot reach Android phone. Ensure phone is on the same Wi-Fi/Hotspot."
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
                    AppLogger.Log("FileBrowserService", $"Browse request {requestId} timed out without response from Android.");
                    RemoteDirectoryChanged?.Invoke(this, new BrowseResponseEventArgs
                    {
                        RequestId = requestId,
                        CurrentPath = path,
                        Items = new List<RemoteBrowserItem>(),
                        ErrorMessage = "Browse request timed out. No response received from phone."
                    });
                }
            });

            return requestId;
        }

        public async Task HandleIncomingBrowseRequestAsync(JsonNode json)
        {
            try
            {
                string path = (json["path"] ?? json["Path"])?.ToString() ?? "ROOT";
                string requestId = (json["requestId"] ?? json["RequestId"])?.ToString() ?? "";

                AppLogger.Log("FileBrowserService", $"Processing incoming BROWSE_REQ: path='{path}', requestId='{requestId}'");

                var (currentResolved, parentResolved, items) = await Task.Run(() => GetDirectoryListing(path));

                var itemsArray = new JsonArray();
                foreach (var item in items)
                {
                    itemsArray.Add(new JsonObject
                    {
                        ["id"] = item.Id,
                        ["name"] = item.Name,
                        ["isDirectory"] = item.IsDirectory,
                        ["size"] = item.Size,
                        ["type"] = item.Type,
                        ["modified"] = item.Modified,
                        ["itemCount"] = item.ItemCount
                    });
                }

                var res = new JsonObject
                {
                    ["type"] = "BROWSE_RES",
                    ["requestId"] = requestId,
                    ["currentPath"] = currentResolved,
                    ["parentPath"] = parentResolved ?? "",
                    ["items"] = itemsArray
                };

                AppLogger.Log("FileBrowserService", $"Dispatching BROWSE_RES to Android ({items.Count} items) for path='{currentResolved}'");
                await CommandReceiverService.SendCommandAsync(res.ToJsonString());
            }
            catch (Exception ex)
            {
                AppLogger.Log("FileBrowserService", $"Fatal error handling BROWSE_REQ: {ex.Message}");
            }
        }

        public void HandleIncomingBrowseResponse(JsonNode json)
        {
            try
            {
                string requestId = (json["requestId"] ?? json["RequestId"])?.ToString() ?? "";
                string currentPath = (json["currentPath"] ?? json["CurrentPath"])?.ToString() ?? "ROOT";
                string? parentPath = (json["parentPath"] ?? json["ParentPath"])?.ToString();
                if (string.IsNullOrWhiteSpace(parentPath) || parentPath.Equals("null", StringComparison.OrdinalIgnoreCase))
                {
                    parentPath = null;
                }

                var itemsNode = (json["items"] ?? json["Items"])?.AsArray();
                var list = new List<RemoteBrowserItem>();

                if (itemsNode != null)
                {
                    foreach (var item in itemsNode)
                    {
                        if (item == null) continue;
                        string id = (item["id"] ?? item["Id"])?.ToString() ?? "";
                        string name = (item["name"] ?? item["Name"])?.ToString() ?? "";
                        bool isDir = item["isDirectory"]?.GetValue<bool>() ?? item["IsDirectory"]?.GetValue<bool>() ?? false;
                        long size = (item["size"] ?? item["Size"])?.GetValue<long>() ?? 0;
                        string type = (item["type"] ?? item["Type"])?.ToString()?.ToUpperInvariant() ?? (isDir ? "FOLDER" : "FILE");
                        long modified = (item["modified"] ?? item["Modified"])?.GetValue<long>() ?? 0;
                        int count = (item["itemCount"] ?? item["ItemCount"])?.GetValue<int>() ?? 0;

                        list.Add(new RemoteBrowserItem
                        {
                            Id = id,
                            Name = name,
                            IsDirectory = isDir,
                            Size = size,
                            Type = type,
                            Modified = modified,
                            ItemCount = count
                        });
                    }
                }

                _pendingRequestId = null;
                AppLogger.Log("FileBrowserService", $"Received BROWSE_RES from Android: {list.Count} items for path='{currentPath}'");
                RemoteDirectoryChanged?.Invoke(this, new BrowseResponseEventArgs
                {
                    RequestId = requestId,
                    CurrentPath = currentPath,
                    ParentPath = parentPath,
                    Items = list,
                    ErrorMessage = null
                });
            }
            catch (Exception ex)
            {
                AppLogger.Log("FileBrowserService", $"Error parsing BROWSE_RES: {ex.Message}");
            }
        }

        private (string currentPath, string? parentPath, List<RemoteBrowserItem> items) GetDirectoryListing(string requestedPath)
        {
            var items = new List<RemoteBrowserItem>();

            if (string.IsNullOrWhiteSpace(requestedPath) || requestedPath.Equals("ROOT", StringComparison.OrdinalIgnoreCase))
            {
                // Virtual Windows Library Roots
                void TryAddLibrary(string label, string path)
                {
                    if (!string.IsNullOrWhiteSpace(path) && Directory.Exists(path))
                    {
                        try
                        {
                            int count = Directory.EnumerateFileSystemEntries(path).Take(100).Count();
                            items.Add(new RemoteBrowserItem
                            {
                                Id = path,
                                Name = label,
                                IsDirectory = true,
                                Size = 0,
                                Type = "FOLDER",
                                ItemCount = count
                            });
                        }
                        catch { }
                    }
                }

                TryAddLibrary("Downloads", Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads"));
                TryAddLibrary("Documents", Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments));
                TryAddLibrary("Desktop", Environment.GetFolderPath(Environment.SpecialFolder.Desktop));
                TryAddLibrary("Pictures", Environment.GetFolderPath(Environment.SpecialFolder.MyPictures));
                TryAddLibrary("Videos", Environment.GetFolderPath(Environment.SpecialFolder.MyVideos));
                TryAddLibrary("Music", Environment.GetFolderPath(Environment.SpecialFolder.MyMusic));

                // Add Fixed Drives
                try
                {
                    foreach (var drive in DriveInfo.GetDrives())
                    {
                        if (drive.IsReady && drive.DriveType == DriveType.Fixed)
                        {
                            string driveName = string.IsNullOrEmpty(drive.VolumeLabel)
                                ? $"Local Disk ({drive.Name.TrimEnd('\\')})"
                                : $"{drive.VolumeLabel} ({drive.Name.TrimEnd('\\')})";

                            items.Add(new RemoteBrowserItem
                            {
                                Id = drive.RootDirectory.FullName,
                                Name = driveName,
                                IsDirectory = true,
                                Size = drive.TotalSize,
                                Type = "FOLDER",
                                ItemCount = 0
                            });
                        }
                    }
                }
                catch { }

                return ("ROOT", null, items);
            }
            else
            {
                // Resolve potential library aliases from mobile client
                string targetPath = requestedPath.Trim();
                switch (targetPath.ToUpperInvariant())
                {
                    case "DOWNLOADS":
                        targetPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
                        break;
                    case "DOCUMENTS":
                        targetPath = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);
                        break;
                    case "DESKTOP":
                        targetPath = Environment.GetFolderPath(Environment.SpecialFolder.Desktop);
                        break;
                    case "PICTURES":
                        targetPath = Environment.GetFolderPath(Environment.SpecialFolder.MyPictures);
                        break;
                    case "VIDEOS":
                    case "MOVIES":
                        targetPath = Environment.GetFolderPath(Environment.SpecialFolder.MyVideos);
                        break;
                    case "MUSIC":
                        targetPath = Environment.GetFolderPath(Environment.SpecialFolder.MyMusic);
                        break;
                    case "C:":
                    case "C:\\":
                        targetPath = @"C:\";
                        break;
                    case "D:":
                    case "D:\\":
                        targetPath = @"D:\";
                        break;
                }

                if (!Directory.Exists(targetPath))
                {
                    return ("ROOT", null, items);
                }

                string currentResolved = Path.GetFullPath(targetPath);
                var parentDir = Directory.GetParent(currentResolved);
                string? parentResolved = parentDir?.FullName;

                // 1. Subdirectories
                try
                {
                    foreach (var dir in Directory.EnumerateDirectories(currentResolved))
                    {
                        string dirName = Path.GetFileName(dir);
                        if (dirName.StartsWith(".") || SkipDirs.Contains(dirName)) continue;

                        try
                        {
                            var di = new DirectoryInfo(dir);
                            if ((di.Attributes & FileAttributes.ReparsePoint) != 0) continue;
                        }
                        catch { continue; }

                        int count = 0;
                        try { count = Directory.EnumerateFileSystemEntries(dir).Take(50).Count(); } catch { }

                        items.Add(new RemoteBrowserItem
                        {
                            Id = dir,
                            Name = dirName,
                            IsDirectory = true,
                            Size = 0,
                            Type = "FOLDER",
                            ItemCount = count
                        });
                    }
                }
                catch { }

                // 2. Files
                try
                {
                    foreach (var file in Directory.EnumerateFiles(currentResolved))
                    {
                        string fileName = Path.GetFileName(file);
                        if (fileName.StartsWith(".")) continue;

                        string ext = Path.GetExtension(file).ToLowerInvariant();
                        string cat = SearchService.GetCategory(ext);

                        try
                        {
                            var fi = new FileInfo(file);
                            items.Add(new RemoteBrowserItem
                            {
                                Id = file,
                                Name = fileName,
                                IsDirectory = false,
                                Size = fi.Length,
                                Type = cat,
                                Modified = new DateTimeOffset(fi.LastWriteTimeUtc).ToUnixTimeMilliseconds()
                            });
                        }
                        catch { }
                    }
                }
                catch { }

                // Sort: Folders first alphabetically, then files alphabetically
                var sorted = items.OrderByDescending(i => i.IsDirectory)
                                  .ThenBy(i => i.Name, StringComparer.OrdinalIgnoreCase)
                                  .Take(300)
                                  .ToList();

                return (currentResolved, parentResolved, sorted);
            }
        }
    }
}
