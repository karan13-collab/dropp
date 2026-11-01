using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json.Nodes;
using System.Threading.Tasks;
using dropp.Windows.Core.Diagnostics;

namespace dropp.Windows.Services
{
    public class SearchResultItem
    {
        public string Id { get; set; } = "";
        public string Name { get; set; } = "";
        public long Size { get; set; }
        public string Type { get; set; } = "FILE";

        public string FormattedSize
        {
            get
            {
                if (Size < 1024) return $"{Size} B";
                if (Size < 1024 * 1024) return $"{Size / 1024.0:F1} KB";
                if (Size < 1024 * 1024 * 1024) return $"{Size / (1024.0 * 1024.0):F1} MB";
                return $"{Size / (1024.0 * 1024.0 * 1024.0):F2} GB";
            }
        }

        public string TypeGlyph => Type switch
        {
            "PDF" => "\uEA90",      // PDF / Document glyph
            "IMAGE" => "\uEB9F",    // Photo / Image glyph
            "DOC" => "\uE8A5",      // Document / Text glyph
            "VIDEO" => "\uE8B7",    // Video glyph
            "AUDIO" => "\uE8D6",    // Audio / Music glyph
            _ => "\uE8A5"          // Generic file glyph
        };
    }

    public class SearchResponseEventArgs : EventArgs
    {
        public string RequestId { get; set; } = "";
        public List<SearchResultItem> Results { get; set; } = new();
        public string? ErrorMessage { get; set; }
    }

    public class SearchService
    {
        private static SearchService? _instance;
        public static SearchService Instance => _instance ??= new SearchService();

        public event EventHandler<SearchResponseEventArgs>? RemoteResultsReceived;
        private string? _pendingRequestId;

        private static readonly HashSet<string> SkipDirs = new(StringComparer.OrdinalIgnoreCase)
        {
            "node_modules", ".git", ".vs", "bin", "obj", "AppData", "Windows",
            "Program Files", "Program Files (x86)", ".gradle", "target", "vendor", ".idea"
        };

        private SearchService() { }

        public async Task<string> SearchRemoteAsync(string query, string fileType = "ALL")
        {
            string requestId = Guid.NewGuid().ToString();
            _pendingRequestId = requestId;

            var req = new JsonObject
            {
                ["type"] = "SEARCH_REQ",
                ["Type"] = "SEARCH_REQ",
                ["query"] = query,
                ["Query"] = query,
                ["fileType"] = fileType,
                ["FileType"] = fileType,
                ["requestId"] = requestId,
                ["RequestId"] = requestId
            };

            AppLogger.Log("SearchService", $"Dispatching SEARCH_REQ to Android: query='{query}', fileType='{fileType}', id='{requestId}'");
            bool sent = await CommandReceiverService.SendCommandAsync(req.ToJsonString());
            if (!sent)
            {
                AppLogger.Log("SearchService", "Failed to send SEARCH_REQ to Android (device unreachable).");
                RemoteResultsReceived?.Invoke(this, new SearchResponseEventArgs
                {
                    RequestId = requestId,
                    Results = new List<SearchResultItem>(),
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
                    AppLogger.Log("SearchService", $"Search request {requestId} timed out without response from Android.");
                    RemoteResultsReceived?.Invoke(this, new SearchResponseEventArgs
                    {
                        RequestId = requestId,
                        Results = new List<SearchResultItem>(),
                        ErrorMessage = "Search timed out. No response received from phone."
                    });
                }
            });

            return requestId;
        }

        public async Task<bool> RequestRemoteStreamAsync(string fileId, bool isPreview)
        {
            var streamReq = new JsonObject
            {
                ["type"] = "STREAM_REQ",
                ["Type"] = "STREAM_REQ",
                ["id"] = fileId,
                ["Id"] = fileId,
                ["isPreview"] = isPreview,
                ["IsPreview"] = isPreview
            };

            AppLogger.Log("SearchService", $"Dispatching STREAM_REQ to Android: id='{fileId}', isPreview={isPreview}");
            return await CommandReceiverService.SendCommandAsync(streamReq.ToJsonString());
        }

        public async Task HandleIncomingSearchRequestAsync(JsonNode json)
        {
            try
            {
                string query = (json["query"] ?? json["Query"])?.ToString() ?? "";
                string fileType = (json["fileType"] ?? json["FileType"])?.ToString()?.ToUpperInvariant() ?? "ALL";
                string requestId = (json["requestId"] ?? json["RequestId"])?.ToString() ?? "";

                AppLogger.Log("SearchService", $"Processing incoming SEARCH_REQ: query='{query}', fileType='{fileType}', requestId='{requestId}'");

                if (string.IsNullOrWhiteSpace(query)) return;

                var results = await Task.Run(() =>
                {
                    try
                    {
                        return SearchLocalFiles(query, fileType);
                    }
                    catch (Exception ex)
                    {
                        AppLogger.Log("SearchService", $"Error during SearchLocalFiles: {ex.Message}");
                        return new List<SearchResultItem>();
                    }
                });

                var resArray = new JsonArray();
                foreach (var r in results)
                {
                    resArray.Add(new JsonObject
                    {
                        ["id"] = r.Id,
                        ["name"] = r.Name,
                        ["size"] = r.Size,
                        ["type"] = r.Type
                    });
                }

                var res = new JsonObject
                {
                    ["type"] = "SEARCH_RES",
                    ["requestId"] = requestId,
                    ["results"] = resArray
                };

                AppLogger.Log("SearchService", $"Dispatching SEARCH_RES to Android ({results.Count} matches) for requestId='{requestId}'");
                await CommandReceiverService.SendCommandAsync(res.ToJsonString());
            }
            catch (Exception ex)
            {
                AppLogger.Log("SearchService", $"Fatal error handling SEARCH_REQ: {ex.Message}");
            }
        }

        public void HandleIncomingSearchResponse(JsonNode json)
        {
            try
            {
                string requestId = (json["requestId"] ?? json["RequestId"])?.ToString() ?? "";
                var resultsNode = (json["results"] ?? json["Results"])?.AsArray();

                var list = new List<SearchResultItem>();
                if (resultsNode != null)
                {
                    foreach (var item in resultsNode)
                    {
                        if (item == null) continue;
                        string id = (item["id"] ?? item["Id"])?.ToString() ?? "";
                        string name = (item["name"] ?? item["Name"])?.ToString() ?? "";
                        long size = (item["size"] ?? item["Size"])?.GetValue<long>() ?? 0;
                        string type = (item["type"] ?? item["Type"])?.ToString()?.ToUpperInvariant() ?? "FILE";

                        list.Add(new SearchResultItem
                        {
                            Id = id,
                            Name = name,
                            Size = size,
                            Type = type
                        });
                    }
                }

                _pendingRequestId = null;
                AppLogger.Log("SearchService", $"Received SEARCH_RES from Android: {list.Count} items for requestId='{requestId}'");
                RemoteResultsReceived?.Invoke(this, new SearchResponseEventArgs
                {
                    RequestId = requestId,
                    Results = list,
                    ErrorMessage = null
                });
            }
            catch (Exception ex)
            {
                AppLogger.Log("SearchService", $"Error parsing SEARCH_RES: {ex.Message}");
            }
        }

        private List<SearchResultItem> SearchLocalFiles(string query, string fileType)
        {
            var matches = new List<SearchResultItem>();
            const int maxResults = 50;

            var targetDirectories = new List<string>();

            void TryAddDir(string path)
            {
                if (!string.IsNullOrWhiteSpace(path) && Directory.Exists(path) && !targetDirectories.Contains(path))
                {
                    targetDirectories.Add(path);
                }
            }

            TryAddDir(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads"));
            TryAddDir(Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments));
            TryAddDir(Environment.GetFolderPath(Environment.SpecialFolder.Desktop));
            TryAddDir(Environment.GetFolderPath(Environment.SpecialFolder.MyPictures));
            TryAddDir(Environment.GetFolderPath(Environment.SpecialFolder.MyVideos));
            TryAddDir(Environment.GetFolderPath(Environment.SpecialFolder.MyMusic));

            // Include root directories of all fixed drives (e.g. D:\)
            try
            {
                foreach (var drive in DriveInfo.GetDrives())
                {
                    if (drive.IsReady && drive.DriveType == DriveType.Fixed)
                    {
                        TryAddDir(drive.RootDirectory.FullName);
                    }
                }
            }
            catch { }

            var queue = new Queue<(string Path, int Depth)>();
            foreach (var root in targetDirectories)
            {
                queue.Enqueue((root, 0));
            }

            while (queue.Count > 0 && matches.Count < maxResults)
            {
                var (currentDir, depth) = queue.Dequeue();

                try
                {
                    // 1. Search matching files in currentDir directly
                    foreach (var filePath in Directory.EnumerateFiles(currentDir))
                    {
                        if (matches.Count >= maxResults) break;

                        string fileName = Path.GetFileName(filePath);
                        if (fileName.StartsWith(".")) continue;

                        if (fileName.IndexOf(query, StringComparison.OrdinalIgnoreCase) >= 0)
                        {
                            string ext = Path.GetExtension(filePath).ToLowerInvariant();
                            string cat = GetCategory(ext);

                            if (MatchesFileType(cat, fileType))
                            {
                                try
                                {
                                    var fi = new FileInfo(filePath);
                                    matches.Add(new SearchResultItem
                                    {
                                        Id = filePath,
                                        Name = fileName,
                                        Size = fi.Length,
                                        Type = cat
                                    });
                                }
                                catch { }
                            }
                        }
                    }

                    // 2. Queue subdirectories if depth < 2
                    if (depth < 2 && matches.Count < maxResults)
                    {
                        foreach (var subDir in Directory.EnumerateDirectories(currentDir))
                        {
                            string dirName = Path.GetFileName(subDir);
                            if (dirName.StartsWith(".") || SkipDirs.Contains(dirName)) continue;

                            // Skip reparse points (NTFS junctions/symlinks) to avoid cycles and access denied
                            try
                            {
                                var di = new DirectoryInfo(subDir);
                                if ((di.Attributes & FileAttributes.ReparsePoint) != 0) continue;
                            }
                            catch { continue; }

                            queue.Enqueue((subDir, depth + 1));
                        }
                    }
                }
                catch { }
            }

            return matches;
        }

        private bool MatchesFileType(string category, string filterType)
        {
            if (string.Equals(filterType, "ALL", StringComparison.OrdinalIgnoreCase)) return true;
            return string.Equals(category, filterType, StringComparison.OrdinalIgnoreCase);
        }

        public static string GetCategory(string extension)
        {
            return extension switch
            {
                ".pdf" => "PDF",
                ".jpg" or ".jpeg" or ".png" or ".gif" or ".webp" or ".bmp" or ".svg" or ".heic" => "IMAGE",
                ".doc" or ".docx" or ".txt" or ".rtf" or ".odt" or ".xls" or ".xlsx" or ".ppt" or ".pptx" or ".csv" or ".md" or ".json" => "DOC",
                ".mp4" or ".mkv" or ".mov" or ".avi" or ".webm" or ".wmv" => "VIDEO",
                ".mp3" or ".wav" or ".m4a" or ".flac" or ".aac" or ".ogg" or ".wma" => "AUDIO",
                _ => "FILE"
            };
        }
    }
}
