using System;
using System.Buffers.Binary;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;

namespace FileSync.App.Services;

public class TransferServer
{
    private TcpListener? _listener;
    private CancellationTokenSource? _cts;
    private readonly string _saveDirectory;

    public event Action<string, string>? FileReceived; // (fileName, fullPath)
    public event Action<string>? StatusChanged;

    public TransferServer(string? saveDirectory = null)
    {
        _saveDirectory = saveDirectory ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            "Downloads",
            "FileSync");

        Directory.CreateDirectory(_saveDirectory);
    }

    public void Start(int port = 45781)
    {
        if (_listener != null) return;

        _cts = new CancellationTokenSource();
        _listener = new TcpListener(IPAddress.Any, port);
        _listener.Server.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        _listener.Start();

        StatusChanged?.Invoke($"Receiver listening on port {port} (Saving to {_saveDirectory})...");
        _ = AcceptLoopAsync(_cts.Token);
    }

    public void Stop()
    {
        _cts?.Cancel();
        _listener?.Stop();
        _listener = null;
    }

    private async Task AcceptLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                var client = await _listener!.AcceptTcpClientAsync(ct);
                client.NoDelay = true;
                client.ReceiveBufferSize = 4 * 1024 * 1024;
                client.SendBufferSize = 4 * 1024 * 1024;

                _ = Task.Run(() => HandleClientAsync(client, ct), ct);
            }
            catch when (ct.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                StatusChanged?.Invoke($"Accept error: {ex.Message}");
            }
        }
    }

    private async Task HandleClientAsync(TcpClient client, CancellationToken ct)
    {
        using (client)
        using (var stream = client.GetStream())
        {
            try
            {
                // 1. Read BatchManifest
                var manifestFrame = await Frame.ReadFromStreamAsync(stream, ct);
                if (manifestFrame == null || manifestFrame.Type != MessageType.BatchManifest)
                {
                    return;
                }

                var manifest = ManifestDto.FromJsonBytes(manifestFrame.Payload);
                if (manifest == null || manifest.Entries.Count == 0)
                {
                    return;
                }

                StatusChanged?.Invoke($"Receiving {manifest.TotalFiles} file(s) from {manifest.SenderDeviceName}...");

                // 2. Send ManifestAck
                var ackFrame = new Frame(MessageType.ManifestAck, new byte[] { 1 });
                await ackFrame.WriteToStreamAsync(stream, ct);

                // Open active files map
                var activeFiles = new Dictionary<int, (FileStream Stream, string DestPath, FileEntryDto Entry)>();

                for (int i = 0; i < manifest.Entries.Count; i++)
                {
                    var entry = manifest.Entries[i];
                    string sanitizedRel = entry.RelativePath.Replace('/', Path.DirectorySeparatorChar)
                                                          .Replace('\\', Path.DirectorySeparatorChar)
                                                          .TrimStart(Path.DirectorySeparatorChar);

                    string destPath = Path.Combine(_saveDirectory, sanitizedRel);
                    string? dir = Path.GetDirectoryName(destPath);
                    if (!string.IsNullOrEmpty(dir))
                    {
                        Directory.CreateDirectory(dir);
                    }

                    var fs = new FileStream(destPath, FileMode.Create, FileAccess.Write, FileShare.None, 1024 * 1024, FileOptions.Asynchronous);
                    if (entry.Size > 0)
                    {
                        fs.SetLength(entry.Size); // Pre-allocate contiguous disk space
                    }
                    activeFiles[entry.FileIndex] = (fs, destPath, entry);
                }

                // 3. Receive Chunks
                while (true)
                {
                    var frame = await Frame.ReadFromStreamAsync(stream, ct);
                    if (frame == null) break;

                    if (frame.Type == MessageType.ChunkData)
                    {
                        if (frame.Payload.Length < 16) continue;

                        int fileIdx = (int)BinaryPrimitives.ReadUInt32LittleEndian(frame.Payload.AsSpan(0, 4));
                        long offset = (long)BinaryPrimitives.ReadUInt64LittleEndian(frame.Payload.AsSpan(4, 8));
                        int length = (int)BinaryPrimitives.ReadUInt32LittleEndian(frame.Payload.AsSpan(12, 4));

                        if (activeFiles.TryGetValue(fileIdx, out var fileInfo))
                        {
                            fileInfo.Stream.Seek(offset, SeekOrigin.Begin);
                            await fileInfo.Stream.WriteAsync(frame.Payload, 16, length, ct);
                        }
                    }
                    else if (frame.Type == MessageType.FileComplete)
                    {
                        if (frame.Payload.Length >= 4)
                        {
                            int fileIdx = (int)BinaryPrimitives.ReadUInt32LittleEndian(frame.Payload.AsSpan(0, 4));
                            if (activeFiles.TryGetValue(fileIdx, out var fileInfo))
                            {
                                await fileInfo.Stream.FlushAsync(ct);
                                fileInfo.Stream.Dispose();

                                // Restore exact timestamps
                                try
                                {
                                    if (fileInfo.Entry.ModifiedEpochNanos > 0)
                                    {
                                        var mtime = DateTimeOffset.FromUnixTimeMilliseconds(fileInfo.Entry.ModifiedEpochNanos / 1_000_000L).UtcDateTime;
                                        File.SetLastWriteTimeUtc(fileInfo.DestPath, mtime);
                                    }
                                    if (fileInfo.Entry.CreatedEpochNanos > 0)
                                    {
                                        var ctime = DateTimeOffset.FromUnixTimeMilliseconds(fileInfo.Entry.CreatedEpochNanos / 1_000_000L).UtcDateTime;
                                        File.SetCreationTimeUtc(fileInfo.DestPath, ctime);
                                    }
                                }
                                catch { }

                                FileReceived?.Invoke(Path.GetFileName(fileInfo.DestPath), fileInfo.DestPath);
                            }
                        }
                    }
                    else if (frame.Type == MessageType.BatchComplete)
                    {
                        break;
                    }
                }

                // Clean up any remaining open streams
                foreach (var kv in activeFiles)
                {
                    kv.Value.Stream.Dispose();
                }

                // 4. Send BatchComplete ACK
                var batchAck = new Frame(MessageType.BatchComplete, new byte[] { 1 });
                await batchAck.WriteToStreamAsync(stream, ct);

                StatusChanged?.Invoke($"Batch received successfully! Saved {manifest.TotalFiles} file(s) to {_saveDirectory}.");
            }
            catch (Exception ex)
            {
                StatusChanged?.Invoke($"Transfer receive error: {ex.Message}");
            }
        }
    }
}
