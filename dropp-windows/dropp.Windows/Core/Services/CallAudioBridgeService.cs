using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;
using NAudio.Wave;
using dropp.Windows.Core.Diagnostics;

namespace dropp.Windows.Services
{
    public class CallAudioBridgeService
    {
        public const int AudioPort = 5556;
        private const int SampleRate = 16000;
        private const int Channels = 1;
        private const int FrameSizeBytes = 640; // 20ms of 16kHz 16-bit mono PCM = 320 samples * 2 bytes
        private const byte MagicByte1 = 0x44; // 'D'
        private const byte MagicByte2 = 0x50; // 'P'
        private const byte MagicByte3 = 0x41; // 'A'
        private const byte MagicByte4 = 0x55; // 'U'

        private static CallAudioBridgeService? _instance;
        public static CallAudioBridgeService Instance => _instance ??= new CallAudioBridgeService();

        private TcpListener? _listener;
        private TcpClient? _currentClient;
        private NetworkStream? _currentStream;
        private CancellationTokenSource? _sessionCts;

        private WaveInEvent? _waveIn;
        private WaveOutEvent? _waveOut;
        private BufferedWaveProvider? _waveProvider;

        public bool IsRunning { get; private set; }
        public bool IsConnected { get; private set; }
        public bool IsMuted { get; set; }

        public event Action<bool, string>? StateChanged;

        private CallAudioBridgeService() { }

        public void StartListener()
        {
            if (IsRunning) return;

            IsRunning = true;
            _sessionCts = new CancellationTokenSource();
            AppLogger.Log("CallAudioBridge", $"Starting Audio Bridge Listener on port {AudioPort}...");

            Task.Run(async () =>
            {
                try
                {
                    _listener = new TcpListener(IPAddress.Any, AudioPort);
                    _listener.Server.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                    _listener.Start();

                    StateChanged?.Invoke(false, "Waiting for phone audio connection...");

                    while (IsRunning && !_sessionCts.Token.IsCancellationRequested)
                    {
                        var client = await _listener.AcceptTcpClientAsync(_sessionCts.Token).ConfigureAwait(false);
                        AppLogger.Log("CallAudioBridge", $"Accepted phone audio connection from {client.Client.RemoteEndPoint}");

                        // Handle connection in background
                        _ = HandleAudioSessionAsync(client, _sessionCts.Token);
                    }
                }
                catch (OperationCanceledException) { }
                catch (Exception ex)
                {
                    AppLogger.Log("CallAudioBridge", $"Audio listener error: {ex.Message}");
                }
                finally
                {
                    Stop();
                }
            });
        }

        private async Task HandleAudioSessionAsync(TcpClient client, CancellationToken ct)
        {
            _currentClient?.Close();
            _currentClient = client;
            client.NoDelay = true;

            try
            {
                _currentStream = client.GetStream();
                IsConnected = true;

                // 1. Read Handshake or Detect Legacy Stream
                int sampleRate = SampleRate;
                int channels = Channels;
                int bitsPerSample = 16;
                bool isMediaStream = false;
                int initialFrameLen = -1;

                byte[] headerBuf = new byte[13];
                int readCount = 0;
                while (readCount < 4)
                {
                    int r = await _currentStream.ReadAsync(headerBuf, readCount, 4 - readCount, ct).ConfigureAwait(false);
                    if (r <= 0) break;
                    readCount += r;
                }

                if (readCount == 4 && headerBuf[0] == MagicByte1 && headerBuf[1] == MagicByte2)
                {
                    if (headerBuf[2] == MagicByte3 && headerBuf[3] == MagicByte4)
                    {
                        // Modern Dropp Audio Handshake ('D', 'P', 'A', 'U')
                        while (readCount < 13)
                        {
                            int r = await _currentStream.ReadAsync(headerBuf, readCount, 13 - readCount, ct).ConfigureAwait(false);
                            if (r <= 0) break;
                            readCount += r;
                        }

                        if (readCount == 13)
                        {
                            sampleRate = (headerBuf[4] << 24) | (headerBuf[5] << 16) | (headerBuf[6] << 8) | headerBuf[7];
                            channels = (headerBuf[8] << 8) | headerBuf[9];
                            bitsPerSample = (headerBuf[10] << 8) | headerBuf[11];
                            byte streamType = headerBuf[12];
                            isMediaStream = (streamType == 0);
                        }
                    }
                    else
                    {
                        // Legacy framing: headerBuf[2] and [3] are len_hi and len_lo
                        initialFrameLen = (headerBuf[2] << 8) | headerBuf[3];
                    }
                }

                AppLogger.Log("CallAudioBridge", $"Audio Session format: {sampleRate}Hz, {channels}ch, {bitsPerSample}-bit. Mode: {(isMediaStream ? "Media Streaming" : "Call Voice")}");

                // 2. Initialize Speaker Playback
                var waveFormat = new WaveFormat(sampleRate, bitsPerSample, channels);
                _waveProvider = new BufferedWaveProvider(waveFormat)
                {
                    DiscardOnBufferOverflow = true,
                    BufferLength = sampleRate * channels * (bitsPerSample / 8) * 2 // 2 seconds buffer max
                };

                _waveOut = new WaveOutEvent
                {
                    DesiredLatency = 100
                };
                _waveOut.Init(_waveProvider);
                _waveOut.Play();

                if (isMediaStream)
                {
                    StateChanged?.Invoke(true, $"Phone Media Audio ({sampleRate / 1000}kHz Stereo) -> PC Speakers");
                }
                else
                {
                    StateChanged?.Invoke(true, "Audio bridge active (Microphone & Speakers connected)");
                    StartMicrophoneCapture(waveFormat);
                }

                // 3. Receive Stream Loop
                byte[] readBuf = new byte[16384];

                if (initialFrameLen > 0 && initialFrameLen <= readBuf.Length)
                {
                    int totalRead = 0;
                    while (totalRead < initialFrameLen)
                    {
                        int read = await _currentStream.ReadAsync(readBuf, totalRead, initialFrameLen - totalRead, ct).ConfigureAwait(false);
                        if (read <= 0) break;
                        totalRead += read;
                    }
                    if (totalRead == initialFrameLen && _waveProvider != null)
                    {
                        _waveProvider.AddSamples(readBuf, 0, initialFrameLen);
                    }
                }

                while (!ct.IsCancellationRequested && client.Connected)
                {
                    // Find magic header 'D', 'P'
                    int b1 = _currentStream.ReadByte();
                    while (b1 != MagicByte1 && b1 != -1 && !ct.IsCancellationRequested)
                    {
                        b1 = _currentStream.ReadByte();
                    }
                    if (b1 == -1) break;

                    int b2 = _currentStream.ReadByte();
                    if (b2 != MagicByte2) continue;

                    int hi = _currentStream.ReadByte();
                    int lo = _currentStream.ReadByte();
                    if (hi == -1 || lo == -1) break;

                    int len = (hi << 8) | lo;
                    if (len > 0)
                    {
                        if (len > readBuf.Length)
                        {
                            readBuf = new byte[Math.Max(readBuf.Length * 2, len)];
                        }

                        int totalRead = 0;
                        while (totalRead < len)
                        {
                            int read = await _currentStream.ReadAsync(readBuf, totalRead, len - totalRead, ct).ConfigureAwait(false);
                            if (read <= 0) break;
                            totalRead += read;
                        }

                        if (totalRead == len && _waveProvider != null)
                        {
                            if (_waveProvider.BufferedDuration.TotalMilliseconds > 250)
                            {
                                _waveProvider.ClearBuffer();
                            }
                            _waveProvider.AddSamples(readBuf, 0, len);
                        }
                    }
                }
            }
            catch (OperationCanceledException) { }
            catch (Exception ex)
            {
                AppLogger.Log("CallAudioBridge", $"Audio session ended: {ex.Message}");
            }
            finally
            {
                TeardownSession();
            }
        }

        private void StartMicrophoneCapture(WaveFormat waveFormat)
        {
            try
            {
                _waveIn = new WaveInEvent
                {
                    WaveFormat = waveFormat,
                    BufferMilliseconds = 20
                };

                _waveIn.DataAvailable += (s, e) =>
                {
                    if (!IsConnected || _currentStream == null || e.BytesRecorded <= 0) return;

                    try
                    {
                        byte[] buffer = new byte[e.BytesRecorded];
                        if (!IsMuted)
                        {
                            Array.Copy(e.Buffer, buffer, e.BytesRecorded);
                        }

                        byte[] header = new byte[4];
                        header[0] = MagicByte1;
                        header[1] = MagicByte2;
                        header[2] = (byte)((e.BytesRecorded >> 8) & 0xFF);
                        header[3] = (byte)(e.BytesRecorded & 0xFF);

                        lock (_currentStream)
                        {
                            _currentStream.Write(header, 0, 4);
                            _currentStream.Write(buffer, 0, e.BytesRecorded);
                            _currentStream.Flush();
                        }
                    }
                    catch (Exception ex)
                    {
                        AppLogger.Log("CallAudioBridge", $"Error sending PC mic audio: {ex.Message}");
                    }
                };

                _waveIn.StartRecording();
                AppLogger.Log("CallAudioBridge", "PC Microphone streaming started.");
            }
            catch (Exception ex)
            {
                AppLogger.Log("CallAudioBridge", $"Failed to start PC microphone capture: {ex.Message}");
            }
        }

        public void Stop()
        {
            if (!IsRunning) return;
            AppLogger.Log("CallAudioBridge", "Stopping Audio Bridge Listener and session...");

            IsRunning = false;
            _sessionCts?.Cancel();
            _sessionCts = null;

            try
            {
                _listener?.Stop();
            }
            catch { }
            _listener = null;

            TeardownSession();
            StateChanged?.Invoke(false, "Audio bridge stopped");
        }

        private void TeardownSession()
        {
            IsConnected = false;

            try
            {
                _waveIn?.StopRecording();
                _waveIn?.Dispose();
            }
            catch { }
            _waveIn = null;

            try
            {
                _waveOut?.Stop();
                _waveOut?.Dispose();
            }
            catch { }
            _waveOut = null;
            _waveProvider = null;

            try
            {
                _currentStream?.Dispose();
            }
            catch { }
            _currentStream = null;

            try
            {
                _currentClient?.Close();
            }
            catch { }
            _currentClient = null;

            StateChanged?.Invoke(false, IsRunning ? "Waiting for phone audio connection..." : "Disconnected");
        }
    }
}
