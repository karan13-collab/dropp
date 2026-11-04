using System;
using System.Buffers.Binary;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;
using dropp.Windows.Core.Diagnostics;

namespace dropp.Windows.Services
{
    [StructLayout(LayoutKind.Sequential, Pack = 1)]
    public struct DroppCameraFrameHeader
    {
        public uint Magic;          // 'DROP' = 0x44524F50
        public uint Version;        // 1
        public uint Width;          // 1920
        public uint Height;         // 1080
        public uint Stride;         // Width * 4
        public uint PixelFormat;    // 0 = BGRA32, 1 = RGBA32, 2 = NV12
        public ulong FrameIndex;    // Incremented per frame
        public ulong Timestamp;     // Unix ms
        public uint IsStreaming;    // 1 if active, 0 if paused/idle
        public uint Reserved0;
        public uint Reserved1;
        public uint Reserved2;
        public uint Reserved3;
        public uint Reserved4;
        public uint Reserved5;
        public uint Reserved6;
        public uint Reserved7;
    }

    public class CameraLensModel
    {
        public string Id { get; set; } = "0";
        public string Name { get; set; } = "";
        public string Facing { get; set; } = "BACK";
        public string LensType { get; set; } = "WIDE";
        public float FocalLength { get; set; }
        public float FNumber { get; set; }
        public int SensorOrientation { get; set; } = 90;
        public float MaxZoom { get; set; } = 10f;
        public bool HasFlash { get; set; }
        public List<string> Resolutions { get; set; } = new();

        public string DisplayName => LensType switch
        {
            "ULTRA_WIDE" => $"🌐 Ultra-Wide 0.5x ({FocalLength:F1}mm f/{FNumber:F1})",
            "TELEPHOTO" => $"🔭 Telephoto ({FocalLength:F1}mm f/{FNumber:F1})",
            "FRONT" => $"🤳 Front Selfie ({FocalLength:F1}mm f/{FNumber:F1})",
            _ => $"📷 Main Wide 1x ({FocalLength:F1}mm f/{FNumber:F1})"
        };

        public override string ToString() => DisplayName;
    }

    public class WebcamService : IDisposable
    {
        private const int CameraStreamPort = 8768;
        private const uint CameraMagicCAM1 = 0x43414D31; // "CAM1"
        private const string SharedMemoryName = "Global\\DroppCameraFrameMemory";
        private const uint MaxSharedMemorySize = 3840 * 2160 * 4 + 1024; // ~33 MB for 4K BGRA

        private static WebcamService? _instance;
        public static WebcamService Instance => _instance ??= new WebcamService();

        // Native Win32 Memory Mapping Imports
        [StructLayout(LayoutKind.Sequential)]
        private struct SECURITY_DESCRIPTOR
        {
            public byte revision;
            public byte size;
            public short control;
            public IntPtr owner;
            public IntPtr group;
            public IntPtr sacl;
            public IntPtr dacl;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct SECURITY_ATTRIBUTES
        {
            public int nLength;
            public IntPtr lpSecurityDescriptor;
            public int bInheritHandle;
        }

        [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern bool ConvertStringSecurityDescriptorToSecurityDescriptorW(
            string StringSecurityDescriptor,
            uint StringSDRevision,
            out IntPtr SecurityDescriptor,
            IntPtr SecurityDescriptorSize);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr LocalFree(IntPtr hMem);

        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern IntPtr CreateFileMappingW(
            IntPtr hFile,
            IntPtr lpFileMappingAttributes,
            uint flProtect,
            uint dwMaximumSizeHigh,
            uint dwMaximumSizeLow,
            string lpName);

        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern IntPtr OpenFileMappingW(uint dwDesiredAccess, bool bInheritHandle, string lpName);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr MapViewOfFile(
            IntPtr hFileMappingObject,
            uint dwDesiredAccess,
            uint dwFileOffsetHigh,
            uint dwFileOffsetLow,
            UIntPtr dwNumberOfBytesToMap);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool UnmapViewOfFile(IntPtr lpBaseAddress);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool CloseHandle(IntPtr hObject);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool FlushViewOfFile(IntPtr lpBaseAddress, UIntPtr dwNumberOfBytesToFlush);

        private const uint PAGE_READWRITE = 0x04;
        private const uint FILE_MAP_ALL_ACCESS = 0xF001F;
        private const uint SECURITY_DESCRIPTOR_REVISION = 1;

        // Shared Memory state
        private IntPtr _hMapGlobal = IntPtr.Zero;
        private IntPtr _hMapLocal = IntPtr.Zero;
        private IntPtr _hMapFile = IntPtr.Zero;
        private IntPtr _pSharedMemory = IntPtr.Zero;
        private IntPtr _pSharedPixels = IntPtr.Zero;
        private IntPtr _pSa = IntPtr.Zero;
        private IntPtr _pSd = IntPtr.Zero;
        private readonly object _sharedMemLock = new();
        private ulong _frameIndex = 0;

        private const int TargetWidth = 1920;
        private const int TargetHeight = 1080;
        private const int TargetStride = TargetWidth * 4;
        private const int TargetTotalBytes = TargetStride * TargetHeight;

        // Streaming state
        private TcpClient? _streamClient;
        private CancellationTokenSource? _streamCts;
        private Task? _streamingTask;

        private byte[]? _jpegBuffer;
        private byte[]? _canvasBuffer;
        private byte[]? _srcPixelBuffer;

        public bool IsStreaming { get; private set; }
        public CameraLensModel? CurrentLens { get; private set; }
        public string CurrentResolution { get; private set; } = "1920x1080";
        public float CurrentZoom { get; private set; } = 1.0f;
        public bool IsTorchOn { get; private set; }
        public bool IsMirrored { get; set; } = false;
        public double CurrentFps { get; private set; }

        public List<CameraLensModel> AvailableLenses { get; } = new();

        public event Action<List<CameraLensModel>>? LensesReceived;
        public event Action<SoftwareBitmap>? FrameReceived;
        public event Action<bool>? StreamingStateChanged;
        public event Action<string>? StatusChanged;
        public event Action<double>? FpsUpdated;

        private WebcamService()
        {
            EnsureSharedMemoryCreated();
            EnsureVirtualCameraStarted();
        }

        private const string LocalSharedMemoryName = "DroppCameraFrameMemory";
        private const string GlobalSharedMemoryName = "Global\\DroppCameraFrameMemory";
        private const string SharedMemoryFilePath = @"C:\ProgramData\Dropp\dropp_cam_frame.dat";
        private FileStream? _sharedFileStream;

        public int ManualRotationAngle { get; set; } = 0; // 0, 90, 180, 270

        private void CreateNullDaclSecurityAttributes()
        {
            try
            {
                // SDDL allowing Everyone (WD), All Application Packages (AC), and Local Service (LS) with Low Mandatory Level (LW)
                string sddl = "D:(A;;GA;;;WD)(A;;GA;;;AC)(A;;GA;;;LS)S:(ML;;NW;;;LW)";
                if (ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl, 1, out _pSd, IntPtr.Zero))
                {
                    var sa = new SECURITY_ATTRIBUTES
                    {
                        nLength = Marshal.SizeOf<SECURITY_ATTRIBUTES>(),
                        lpSecurityDescriptor = _pSd,
                        bInheritHandle = 0
                    };

                    _pSa = Marshal.AllocHGlobal(Marshal.SizeOf(sa));
                    Marshal.StructureToPtr(sa, _pSa, false);
                    AppLogger.Log("WebcamService", "SDDL security attributes initialized successfully.");
                }
                else
                {
                    AppLogger.Log("WebcamService", $"ConvertStringSecurityDescriptorToSecurityDescriptor failed: {Marshal.GetLastWin32Error()}");
                }
            }
            catch (Exception ex)
            {
                AppLogger.Log("WebcamService", $"Failed to create SDDL security attributes: {ex.Message}");
            }
        }

        private void EnsureSharedMemoryCreated()
        {
            lock (_sharedMemLock)
            {
                if (_pSharedMemory != IntPtr.Zero) return;

                try
                {
                    if (_pSa == IntPtr.Zero)
                    {
                        CreateNullDaclSecurityAttributes();
                    }

                    IntPtr hFile = (IntPtr)(-1);
                    try
                    {
                        string dir = Path.GetDirectoryName(SharedMemoryFilePath)!;
                        if (!Directory.Exists(dir))
                        {
                            Directory.CreateDirectory(dir);
                        }

                        _sharedFileStream = new FileStream(
                            SharedMemoryFilePath,
                            FileMode.OpenOrCreate,
                            FileAccess.ReadWrite,
                            FileShare.ReadWrite);

                        if (_sharedFileStream.Length < MaxSharedMemorySize)
                        {
                            try
                            {
                                _sharedFileStream.SetLength(MaxSharedMemorySize);
                                _sharedFileStream.Flush();
                            }
                            catch (Exception ex)
                            {
                                AppLogger.Log("WebcamService", $"Warning: SetLength on shared file: {ex.Message}");
                            }
                        }

                        hFile = _sharedFileStream.SafeFileHandle.DangerousGetHandle();
                        AppLogger.Log("WebcamService", $"Shared memory file '{SharedMemoryFilePath}' opened (handle=0x{hFile:X}).");
                    }
                    catch (Exception ex)
                    {
                        AppLogger.Log("WebcamService", $"Could not open shared memory file (falling back to pure RAM): {ex.Message}");
                        _sharedFileStream = null;
                        hFile = (IntPtr)(-1);
                    }

                    uint mapSizeLow = hFile == (IntPtr)(-1) ? MaxSharedMemorySize : 0;

                    // 1. Create Global\ named mapping with NULL/Low-Integrity DACL
                    _hMapGlobal = CreateFileMappingW(
                        hFile,
                        _pSa,
                        PAGE_READWRITE,
                        0,
                        mapSizeLow,
                        GlobalSharedMemoryName);

                    int errGlobal = Marshal.GetLastWin32Error();
                    AppLogger.Log("WebcamService", $"CreateFileMappingW ('{GlobalSharedMemoryName}') = 0x{_hMapGlobal:X}, err={errGlobal}");

                    // 2. Also create local named mapping as fallback
                    _hMapLocal = CreateFileMappingW(
                        hFile,
                        _pSa,
                        PAGE_READWRITE,
                        0,
                        mapSizeLow,
                        LocalSharedMemoryName);

                    int errLocal = Marshal.GetLastWin32Error();
                    AppLogger.Log("WebcamService", $"CreateFileMappingW ('{LocalSharedMemoryName}') = 0x{_hMapLocal:X}, err={errLocal}");

                    // 3. Select primary handle to map view
                    IntPtr hToMap = _hMapGlobal != IntPtr.Zero ? _hMapGlobal : _hMapLocal;
                    if (hToMap == IntPtr.Zero && hFile != (IntPtr)(-1))
                    {
                        _hMapFile = CreateFileMappingW(hFile, _pSa, PAGE_READWRITE, 0, 0, null);
                        hToMap = _hMapFile;
                    }

                    if (hToMap != IntPtr.Zero)
                    {
                        _pSharedMemory = MapViewOfFile(hToMap, FILE_MAP_ALL_ACCESS, 0, 0, UIntPtr.Zero);
                        if (_pSharedMemory != IntPtr.Zero)
                        {
                            _pSharedPixels = IntPtr.Add(_pSharedMemory, Marshal.SizeOf<DroppCameraFrameHeader>());
                            AppLogger.Log("WebcamService", $"Shared memory mapped successfully at 0x{_pSharedMemory:X}. Pixels at 0x{_pSharedPixels:X}");

                            // Write initial idle header
                            var header = new DroppCameraFrameHeader
                            {
                                Magic = 0x44524F50,
                                Version = 1,
                                Width = TargetWidth,
                                Height = TargetHeight,
                                Stride = TargetStride,
                                PixelFormat = 0,
                                FrameIndex = 0,
                                Timestamp = (ulong)DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                                IsStreaming = 0
                            };
                            Marshal.StructureToPtr(header, _pSharedMemory, false);
                            FlushViewOfFile(_pSharedMemory, (UIntPtr)Marshal.SizeOf<DroppCameraFrameHeader>());
                        }
                        else
                        {
                            AppLogger.Log("WebcamService", $"MapViewOfFile failed, error={Marshal.GetLastWin32Error()}");
                        }
                    }
                    else
                    {
                        AppLogger.Log("WebcamService", "All CreateFileMappingW attempts failed.");
                    }
                }
                catch (Exception ex)
                {
                    AppLogger.Log("WebcamService", $"Exception initializing shared memory: {ex.Message}");
                }
            }
        }

        [DllImport("mfplat.dll", ExactSpelling = true)]
        private static extern int MFStartup(uint version, uint flags = 0);

        [DllImport("mfsensorgroup.dll", ExactSpelling = true)]
        private static extern int MFCreateVirtualCamera(
            int type,
            int lifetime,
            int access,
            [MarshalAs(UnmanagedType.LPWStr)] string friendlyName,
            [MarshalAs(UnmanagedType.LPWStr)] string sourceId,
            IntPtr categories,
            int categoryCount,
            out IntPtr ppVirtualCamera
        );

        private IntPtr _pVirtualCamera = IntPtr.Zero;

        public void EnsureVirtualCameraStarted()
        {
            try
            {
                const uint MF_VERSION = 0x00020070;
                int hrStartup = MFStartup(MF_VERSION, 0);
                AppLogger.Log("WebcamService", $"MFStartup returned 0x{hrStartup:X8}");

                EnsureVirtualCameraActive();
            }
            catch (Exception ex)
            {
                AppLogger.Log("WebcamService", $"Error initializing virtual camera: {ex.Message}");
            }
        }

        public async Task RequestLensesAsync()
        {
            try
            {
                string reqId = Guid.NewGuid().ToString();
                var req = new JsonObject
                {
                    ["type"] = "CAMERA_INFO_REQ",
                    ["requestId"] = reqId
                };

                AppLogger.Log("WebcamService", $"Requesting available phone lenses: {reqId}...");
                bool sent = await CommandReceiverService.SendCommandAsync(req.ToJsonString());
                if (!sent)
                {
                    AppLogger.Log("WebcamService", "Could not send CAMERA_INFO_REQ (Target device not reachable).");
                    StatusChanged?.Invoke("Cannot reach Android phone. Ensure phone is connected.");
                }
            }
            catch (Exception ex)
            {
                AppLogger.Log("WebcamService", $"Error requesting lenses: {ex.Message}");
            }
        }

        public void HandleIncomingCameraInfoResponse(JsonNode json)
        {
            try
            {
                var lensesNode = (json["lenses"] ?? json["Lenses"])?.AsArray();
                var list = new List<CameraLensModel>();

                if (lensesNode != null)
                {
                    foreach (var item in lensesNode)
                    {
                        if (item == null) continue;
                        var model = new CameraLensModel
                        {
                            Id = (item["id"] ?? item["Id"])?.ToString() ?? "0",
                            Name = (item["name"] ?? item["Name"])?.ToString() ?? "",
                            Facing = (item["facing"] ?? item["Facing"])?.ToString() ?? "BACK",
                            LensType = (item["lensType"] ?? item["LensType"])?.ToString() ?? "WIDE",
                            FocalLength = (float)((item["focalLength"] ?? item["FocalLength"])?.GetValue<double>() ?? 0.0),
                            FNumber = (float)((item["fNumber"] ?? item["FNumber"])?.GetValue<double>() ?? 0.0),
                            SensorOrientation = (item["sensorOrientation"] ?? item["SensorOrientation"])?.GetValue<int>() ?? 90,
                            MaxZoom = (float)((item["maxZoom"] ?? item["MaxZoom"])?.GetValue<double>() ?? 10.0),
                            HasFlash = (item["hasFlash"] ?? item["HasFlash"])?.GetValue<bool>() ?? false
                        };

                        var resNode = (item["resolutions"] ?? item["Resolutions"])?.AsArray();
                        if (resNode != null)
                        {
                            foreach (var r in resNode)
                            {
                                if (r != null) model.Resolutions.Add(r.ToString());
                            }
                        }

                        list.Add(model);
                    }
                }

                AvailableLenses.Clear();
                AvailableLenses.AddRange(list);
                AppLogger.Log("WebcamService", $"Discovered {list.Count} available lenses from Android.");

                LensesReceived?.Invoke(list);
            }
            catch (Exception ex)
            {
                AppLogger.Log("WebcamService", $"Error handling CAMERA_INFO_RES: {ex.Message}");
            }
        }

        public async Task<bool> StartStreamingAsync(CameraLensModel lens, string resolution = "1920x1080", int fps = 30)
        {
            if (IsStreaming)
            {
                await StopStreamingAsync();
            }

            EnsureSharedMemoryCreated();

            CurrentLens = lens;
            CurrentResolution = resolution;
            CurrentZoom = 1.0f;
            IsTorchOn = false;

            int width = 1920;
            int height = 1080;
            var parts = resolution.Split('x');
            if (parts.Length == 2 && int.TryParse(parts[0], out int w) && int.TryParse(parts[1], out int h))
            {
                width = w;
                height = h;
            }

            string targetIp = CommandReceiverService.TargetAndroidIp;
            if (string.IsNullOrEmpty(targetIp))
            {
                AppLogger.Log("WebcamService", "Target Android IP unknown. Cannot start streaming.");
                StatusChanged?.Invoke("Target phone IP unknown. Please discover phone first.");
                return false;
            }

            try
            {
                StatusChanged?.Invoke($"Requesting Android camera ({lens.DisplayName})...");
                var req = new JsonObject
                {
                    ["type"] = "CAMERA_START_REQ",
                    ["cameraId"] = lens.Id,
                    ["width"] = width,
                    ["height"] = height,
                    ["fps"] = fps
                };

                bool sent = await CommandReceiverService.SendCommandAsync(req.ToJsonString());
                if (!sent)
                {
                    StatusChanged?.Invoke("Failed to send CAMERA_START_REQ to phone.");
                    return false;
                }

                // Connect TCP Video Stream socket to port 8768
                AppLogger.Log("WebcamService", $"Connecting to video stream socket at {targetIp}:{CameraStreamPort}...");
                StatusChanged?.Invoke("Connecting to video socket...");

                var client = new TcpClient();
                client.NoDelay = true;
                client.ReceiveBufferSize = 1024 * 1024;

                // Retry connecting up to 5 times (Android opens server socket on start request)
                bool connected = false;
                for (int attempt = 1; attempt <= 6; attempt++)
                {
                    try
                    {
                        await client.ConnectAsync(targetIp, CameraStreamPort);
                        connected = true;
                        break;
                    }
                    catch
                    {
                        await Task.Delay(400);
                    }
                }

                if (!connected)
                {
                    AppLogger.Log("WebcamService", $"Failed to connect to video socket on {targetIp}:{CameraStreamPort}");
                    StatusChanged?.Invoke("Failed to connect to phone camera stream socket.");
                    client.Dispose();
                    return false;
                }

                _streamClient = client;
                _streamCts = new CancellationTokenSource();
                IsStreaming = true;
                StreamingStateChanged?.Invoke(true);
                StatusChanged?.Invoke($"Streaming Live: {lens.DisplayName} ({resolution})");

                // Start background receive and decode loop
                _streamingTask = Task.Run(() => StreamProcessingLoopAsync(client, _streamCts.Token));

                // Also trigger Virtual Camera install/run in background to ensure Windows Frame Server has it
                _ = Task.Run(() => EnsureVirtualCameraActive());

                return true;
            }
            catch (Exception ex)
            {
                AppLogger.Log("WebcamService", $"Exception starting camera stream: {ex.Message}");
                StatusChanged?.Invoke($"Camera error: {ex.Message}");
                return false;
            }
        }

        private static bool _virtualCameraManagerExecuted = false;

        private void EnsureVirtualCameraActive()
        {
            if (_virtualCameraManagerExecuted) return;
            _virtualCameraManagerExecuted = true;

            try
            {
                string droppDir = @"C:\ProgramData\Dropp";
                if (!Directory.Exists(droppDir))
                {
                    Directory.CreateDirectory(droppDir);
                }

                string managerPath = Path.Combine(droppDir, "DroppVirtualCameraManager.exe");
                string dllPath = Path.Combine(droppDir, "DroppVirtualCamera.dll");

                string baseDir = AppDomain.CurrentDomain.BaseDirectory;
                string bundledManager = Path.Combine(baseDir, "Native", "VCam", "DroppVirtualCameraManager.exe");
                string bundledDll = Path.Combine(baseDir, "Native", "VCam", "DroppVirtualCamera.dll");

                if (File.Exists(bundledManager) && (!File.Exists(managerPath) || new FileInfo(bundledManager).LastWriteTimeUtc > new FileInfo(managerPath).LastWriteTimeUtc))
                {
                    try { File.Copy(bundledManager, managerPath, true); } catch { }
                }

                if (File.Exists(bundledDll))
                {
                    bool needCopy = !File.Exists(dllPath) || new FileInfo(bundledDll).LastWriteTimeUtc > new FileInfo(dllPath).LastWriteTimeUtc;
                    if (needCopy)
                    {
                        try
                        {
                            File.Copy(bundledDll, dllPath, true);
                        }
                        catch
                        {
                            try
                            {
                                string oldPath = dllPath + ".old." + DateTime.UtcNow.Ticks;
                                File.Move(dllPath, oldPath);
                                File.Copy(bundledDll, dllPath, true);
                            }
                            catch { }
                        }
                    }
                }

                if (File.Exists(managerPath))
                {
                    var psi = new ProcessStartInfo
                    {
                        FileName = managerPath,
                        Arguments = "/install",
                        UseShellExecute = false,
                        CreateNoWindow = true
                    };
                    using var proc = Process.Start(psi);
                    proc?.WaitForExit(2000);
                    AppLogger.Log("WebcamService", "DroppVirtualCameraManager /install executed successfully.");
                }
                else
                {
                    AppLogger.Log("WebcamService", $"DroppVirtualCameraManager.exe not found at {managerPath}");
                }
            }
            catch (Exception ex)
            {
                AppLogger.Log("WebcamService", $"Error running DroppVirtualCameraManager: {ex.Message}");
            }
        }

        public async Task SwitchLensAsync(CameraLensModel newLens)
        {
            if (!IsStreaming)
            {
                CurrentLens = newLens;
                return;
            }

            try
            {
                AppLogger.Log("WebcamService", $"Switching camera lens to '{newLens.Id}' ({newLens.DisplayName})...");
                CurrentLens = newLens;

                var req = new JsonObject
                {
                    ["type"] = "CAMERA_CONTROL_REQ",
                    ["switchCameraId"] = newLens.Id
                };

                await CommandReceiverService.SendCommandAsync(req.ToJsonString());
                StatusChanged?.Invoke($"Switched lens to {newLens.DisplayName}");
            }
            catch (Exception ex)
            {
                AppLogger.Log("WebcamService", $"Error switching lens: {ex.Message}");
            }
        }

        public async Task SetZoomAsync(float zoom)
        {
            CurrentZoom = zoom;
            if (!IsStreaming) return;

            try
            {
                var req = new JsonObject
                {
                    ["type"] = "CAMERA_CONTROL_REQ",
                    ["zoom"] = zoom
                };
                await CommandReceiverService.SendCommandAsync(req.ToJsonString());
            }
            catch (Exception ex)
            {
                AppLogger.Log("WebcamService", $"Error setting zoom: {ex.Message}");
            }
        }

        public async Task SetTorchAsync(bool torch)
        {
            IsTorchOn = torch;
            if (!IsStreaming) return;

            try
            {
                var req = new JsonObject
                {
                    ["type"] = "CAMERA_CONTROL_REQ",
                    ["torch"] = torch
                };
                await CommandReceiverService.SendCommandAsync(req.ToJsonString());
            }
            catch (Exception ex)
            {
                AppLogger.Log("WebcamService", $"Error setting torch: {ex.Message}");
            }
        }

        public async Task StopStreamingAsync()
        {
            if (!IsStreaming) return;

            AppLogger.Log("WebcamService", "Stopping camera streaming...");
            IsStreaming = false;
            StreamingStateChanged?.Invoke(false);
            StatusChanged?.Invoke("Webcam stopped.");

            // Cancel token and close stream client
            _streamCts?.Cancel();
            try { _streamClient?.Close(); } catch { }
            try { _streamClient?.Dispose(); } catch { }
            _streamClient = null;

            // Send stop request to Android
            try
            {
                var req = new JsonObject
                {
                    ["type"] = "CAMERA_STOP_REQ"
                };
                await CommandReceiverService.SendCommandAsync(req.ToJsonString());
            }
            catch { }

            // Mark shared memory as idle
            lock (_sharedMemLock)
            {
                if (_pSharedMemory != IntPtr.Zero)
                {
                    try
                    {
                        var header = new DroppCameraFrameHeader
                        {
                            Magic = 0x44524F50,
                            Version = 1,
                            Width = 1920,
                            Height = 1080,
                            Stride = 1920 * 4,
                            PixelFormat = 0,
                            FrameIndex = _frameIndex,
                            Timestamp = (ulong)DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                            IsStreaming = 0
                        };
                        Marshal.StructureToPtr(header, _pSharedMemory, false);
                        FlushViewOfFile(_pSharedMemory, (UIntPtr)Marshal.SizeOf<DroppCameraFrameHeader>());
                    }
                    catch { }
                }
            }

            CurrentFps = 0;
            FpsUpdated?.Invoke(0);
        }

        private async Task StreamProcessingLoopAsync(TcpClient client, CancellationToken ct)
        {
            AppLogger.Log("WebcamService", "Video frame processing loop started.");

            byte[] headerBuf = new byte[28];
            int framesThisSecond = 0;
            var lastFpsCalc = DateTime.UtcNow;

            try
            {
                using var stream = client.GetStream();

                while (!ct.IsCancellationRequested)
                {
                    // Read 28-byte frame header:
                    // [4B MAGIC][4B length][4B width][4B height][4B orientation][8B timestamp]
                    await ReadExactAsync(stream, headerBuf, 0, 28, ct);

                    uint magic = BinaryPrimitives.ReadUInt32BigEndian(headerBuf.AsSpan(0, 4));
                    if (magic != CameraMagicCAM1)
                    {
                        AppLogger.Log("WebcamService", $"Corrupted frame packet magic: 0x{magic:X8}. Resynchronizing...");
                        continue;
                    }

                    int payloadLength = BinaryPrimitives.ReadInt32BigEndian(headerBuf.AsSpan(4, 4));
                    int width = BinaryPrimitives.ReadInt32BigEndian(headerBuf.AsSpan(8, 4));
                    int height = BinaryPrimitives.ReadInt32BigEndian(headerBuf.AsSpan(12, 4));
                    int orientation = BinaryPrimitives.ReadInt32BigEndian(headerBuf.AsSpan(16, 4));
                    long timestamp = BinaryPrimitives.ReadInt64BigEndian(headerBuf.AsSpan(20, 8));

                    if (payloadLength <= 0 || payloadLength > 25 * 1024 * 1024)
                    {
                        AppLogger.Log("WebcamService", $"Invalid payload length: {payloadLength}");
                        continue;
                    }

                    if (_jpegBuffer == null || _jpegBuffer.Length < payloadLength)
                    {
                        _jpegBuffer = new byte[payloadLength + 64 * 1024];
                    }

                    await ReadExactAsync(stream, _jpegBuffer, 0, payloadLength, ct);

                    // Decode JPEG using Windows.Graphics.Imaging.BitmapDecoder
                    await ProcessDecodedFrameAsync(_jpegBuffer, payloadLength, width, height, orientation);

                    framesThisSecond++;
                    var now = DateTime.UtcNow;
                    var elapsed = (now - lastFpsCalc).TotalSeconds;
                    if (elapsed >= 1.0)
                    {
                        CurrentFps = Math.Round(framesThisSecond / elapsed, 1);
                        FpsUpdated?.Invoke(CurrentFps);
                        framesThisSecond = 0;
                        lastFpsCalc = now;
                    }
                }
            }
            catch (OperationCanceledException) { }
            catch (Exception ex)
            {
                if (IsStreaming)
                {
                    AppLogger.Log("WebcamService", $"Streaming loop terminated: {ex.Message}");
                    StatusChanged?.Invoke("Stream connection lost.");
                    _ = StopStreamingAsync();
                }
            }

            AppLogger.Log("WebcamService", "Video frame processing loop exited.");
        }

        private async Task ProcessDecodedFrameAsync(byte[] jpegBytes, int length, int width, int height, int sensorOrientation)
        {
            try
            {
                using var ras = new InMemoryRandomAccessStream();
                using (var writer = new DataWriter(ras.GetOutputStreamAt(0)))
                {
                    writer.WriteBytes(new ReadOnlySpan<byte>(jpegBytes, 0, length).ToArray());
                    await writer.StoreAsync();
                }
                ras.Seek(0);

                var decoder = await BitmapDecoder.CreateAsync(BitmapDecoder.JpegDecoderId, ras);
                var transform = new BitmapTransform();

                // Apply sensor orientation + manual rotation
                int effectiveRotation = (sensorOrientation + ManualRotationAngle) % 360;
                if (effectiveRotation == 90)
                {
                    transform.Rotation = BitmapRotation.Clockwise90Degrees;
                }
                else if (effectiveRotation == 180)
                {
                    transform.Rotation = BitmapRotation.Clockwise180Degrees;
                }
                else if (effectiveRotation == 270)
                {
                    transform.Rotation = BitmapRotation.Clockwise270Degrees;
                }
                else
                {
                    transform.Rotation = BitmapRotation.None;
                }

                if (IsMirrored)
                {
                    transform.Flip = BitmapFlip.Horizontal;
                }

                var softwareBitmap = await decoder.GetSoftwareBitmapAsync(
                    BitmapPixelFormat.Bgra8,
                    BitmapAlphaMode.Premultiplied,
                    transform,
                    ExifOrientationMode.IgnoreExifOrientation,
                    ColorManagementMode.DoNotColorManage);

                int srcW = softwareBitmap.PixelWidth;
                int srcH = softwareBitmap.PixelHeight;

                if (_canvasBuffer == null || _canvasBuffer.Length != TargetTotalBytes)
                {
                    _canvasBuffer = new byte[TargetTotalBytes];
                }

                if (srcW == TargetWidth && srcH == TargetHeight)
                {
                    softwareBitmap.CopyToBuffer(_canvasBuffer.AsBuffer());
                }
                else
                {
                    int srcTotalBytes = srcW * 4 * srcH;
                    if (_srcPixelBuffer == null || _srcPixelBuffer.Length != srcTotalBytes)
                    {
                        _srcPixelBuffer = new byte[srcTotalBytes];
                    }
                    softwareBitmap.CopyToBuffer(_srcPixelBuffer.AsBuffer());

                    // Aspect-fit srcW x srcH centered onto 1920x1080 canvas
                    Array.Clear(_canvasBuffer, 0, _canvasBuffer.Length);

                    double scaleW = (double)TargetWidth / srcW;
                    double scaleH = (double)TargetHeight / srcH;
                    double scale = Math.Min(scaleW, scaleH);

                    int dstW = Math.Max(2, (int)(srcW * scale) & ~1);
                    int dstH = Math.Max(2, (int)(srcH * scale) & ~1);
                    int offsetX = (TargetWidth - dstW) / 2;
                    int offsetY = (TargetHeight - dstH) / 2;

                    unsafe
                    {
                        fixed (byte* pSrcBase = _srcPixelBuffer)
                        fixed (byte* pDstBase = _canvasBuffer)
                        {
                            uint* pSrc32 = (uint*)pSrcBase;
                            uint* pDst32 = (uint*)pDstBase;

                            for (int y = 0; y < dstH; y++)
                            {
                                int srcY = (int)((long)y * srcH / dstH);
                                uint* pSrcRow = pSrc32 + (srcY * srcW);
                                uint* pDstRow = pDst32 + ((offsetY + y) * TargetWidth + offsetX);

                                for (int x = 0; x < dstW; x++)
                                {
                                    int srcX = (int)((long)x * srcW / dstW);
                                    pDstRow[x] = pSrcRow[srcX];
                                }
                            }
                        }
                    }
                }

                // Copy to shared memory for Windows Camera App / OBS / Zoom / Meet
                lock (_sharedMemLock)
                {
                    if (_pSharedMemory != IntPtr.Zero && _pSharedPixels != IntPtr.Zero)
                    {
                        Marshal.Copy(_canvasBuffer, 0, _pSharedPixels, TargetTotalBytes);

                        var header = new DroppCameraFrameHeader
                        {
                            Magic = 0x44524F50,
                            Version = 1,
                            Width = TargetWidth,
                            Height = TargetHeight,
                            Stride = TargetStride,
                            PixelFormat = 0, // BGRA32
                            FrameIndex = ++_frameIndex,
                            Timestamp = (ulong)DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                            IsStreaming = 1
                        };

                        Marshal.StructureToPtr(header, _pSharedMemory, false);
                        FlushViewOfFile(_pSharedMemory, (UIntPtr)(Marshal.SizeOf<DroppCameraFrameHeader>() + TargetTotalBytes));
                    }
                }

                // Notify UI preview
                FrameReceived?.Invoke(softwareBitmap);
            }
            catch (Exception ex)
            {
                AppLogger.Log("WebcamService", $"Frame decode error: {ex.Message}");
            }
        }

        private static async Task ReadExactAsync(Stream stream, byte[] buffer, int offset, int count, CancellationToken ct)
        {
            int total = 0;
            while (total < count)
            {
                int read = await stream.ReadAsync(buffer.AsMemory(offset + total, count - total), ct);
                if (read == 0) throw new EndOfStreamException("End of camera video stream reached.");
                total += read;
            }
        }

        public void Dispose()
        {
            _ = StopStreamingAsync();

            if (_pVirtualCamera != IntPtr.Zero)
            {
                try
                {
                    unsafe
                    {
                        IntPtr* vtbl = *(IntPtr**)_pVirtualCamera;
                        var stopFn = (delegate* unmanaged[Stdcall]<IntPtr, int>)vtbl[37];
                        stopFn(_pVirtualCamera);
                    }
                    Marshal.Release(_pVirtualCamera);
                }
                catch { }
                _pVirtualCamera = IntPtr.Zero;
            }

            lock (_sharedMemLock)
            {
                if (_pSharedMemory != IntPtr.Zero)
                {
                    UnmapViewOfFile(_pSharedMemory);
                    _pSharedMemory = IntPtr.Zero;
                    _pSharedPixels = IntPtr.Zero;
                }

                if (_hMapGlobal != IntPtr.Zero)
                {
                    CloseHandle(_hMapGlobal);
                    _hMapGlobal = IntPtr.Zero;
                }

                if (_hMapLocal != IntPtr.Zero)
                {
                    CloseHandle(_hMapLocal);
                    _hMapLocal = IntPtr.Zero;
                }

                if (_hMapFile != IntPtr.Zero)
                {
                    CloseHandle(_hMapFile);
                    _hMapFile = IntPtr.Zero;
                }

                if (_sharedFileStream != null)
                {
                    try { _sharedFileStream.Dispose(); } catch { }
                    _sharedFileStream = null;
                }

                if (_pSa != IntPtr.Zero)
                {
                    Marshal.FreeHGlobal(_pSa);
                    _pSa = IntPtr.Zero;
                }

                if (_pSd != IntPtr.Zero)
                {
                    LocalFree(_pSd);
                    _pSd = IntPtr.Zero;
                }
            }
        }
    }
}
