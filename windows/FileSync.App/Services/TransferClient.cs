using System;
using System.Buffers.Binary;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;
using FileSync.App.Models;

namespace FileSync.App.Services;

public class TransferProgressReport
{
    public int CurrentFileIndex { get; set; }
    public string CurrentFileName { get; set; } = "";
    public double CurrentFileProgress { get; set; }
    public long TotalBytesTransferred { get; set; }
    public long TotalBytes { get; set; }
    public double TotalProgress { get; set; }
    public double SpeedBytesPerSec { get; set; }
    public string FormattedSpeed { get; set; } = "0 MB/s";
    public string FormattedEta { get; set; } = "--";
    public string StatusMessage { get; set; } = "";
}

public class TransferClient
{
    public const int DefaultChunkSize = 1024 * 1024; // 1 MB high-speed streaming chunk

    public async Task<bool> SendFilesAsync(
        string host,
        int port,
        List<TransferItem> items,
        Action<TransferProgressReport> onProgress,
        CancellationToken ct = default)
    {
        var fileItems = items.Where(i => !i.IsDirectory && File.Exists(i.FullPath)).ToList();
        if (fileItems.Count == 0) return false;

        using var tcpClient = new TcpClient();
        tcpClient.NoDelay = true;
        tcpClient.SendBufferSize = 4 * 1024 * 1024;
        tcpClient.ReceiveBufferSize = 4 * 1024 * 1024;

        onProgress(new TransferProgressReport
        {
            StatusMessage = $"Connecting to {host}:{port}..."
        });

        await tcpClient.ConnectAsync(host, port, ct);
        using var stream = tcpClient.GetStream();

        // 1. Build Manifest
        var manifest = new ManifestDto
        {
            SenderDeviceName = Environment.MachineName,
            TotalFiles = fileItems.Count,
            TotalBytes = fileItems.Sum(f => f.Size)
        };

        for (int i = 0; i < fileItems.Count; i++)
        {
            var item = fileItems[i];
            var fi = new FileInfo(item.FullPath);
            long mtimeNanos = new DateTimeOffset(fi.LastWriteTimeUtc).ToUnixTimeMilliseconds() * 1_000_000L;
            long ctimeNanos = new DateTimeOffset(fi.CreationTimeUtc).ToUnixTimeMilliseconds() * 1_000_000L;

            manifest.Entries.Add(new FileEntryDto
            {
                FileIndex = i,
                RelativePath = string.IsNullOrWhiteSpace(item.RelativePath) ? item.Name : item.RelativePath,
                Size = item.Size,
                ModifiedEpochNanos = mtimeNanos,
                CreatedEpochNanos = ctimeNanos
            });
        }

        // 2. Send Manifest Frame
        onProgress(new TransferProgressReport
        {
            StatusMessage = "Sending transfer manifest..."
        });

        var manifestFrame = new Frame(MessageType.BatchManifest, manifest.ToJsonBytes());
        await manifestFrame.WriteToStreamAsync(stream, ct);

        // 3. Await ManifestAck
        var ackFrame = await Frame.ReadFromStreamAsync(stream, ct);
        if (ackFrame == null || ackFrame.Type != MessageType.ManifestAck)
        {
            throw new InvalidOperationException("Did not receive ManifestAck from remote peer.");
        }

        // 4. Stream Chunks
        long totalTransferred = 0;
        long totalBytes = manifest.TotalBytes;
        byte[] readBuffer = new byte[DefaultChunkSize];
        byte[] chunkPayload = new byte[16 + DefaultChunkSize];

        var stopwatch = Stopwatch.StartNew();
        long lastTime = stopwatch.ElapsedMilliseconds;
        long lastBytes = 0;
        double currentSpeed = 0;

        for (int fileIdx = 0; fileIdx < fileItems.Count; fileIdx++)
        {
            ct.ThrowIfCancellationRequested();

            var item = fileItems[fileIdx];
            item.Status = "InProgress";

            using var fileStream = new FileStream(
                item.FullPath,
                FileMode.Open,
                FileAccess.Read,
                FileShare.Read,
                bufferSize: DefaultChunkSize,
                options: FileOptions.SequentialScan | FileOptions.Asynchronous);

            long fileOffset = 0;
            long fileSize = item.Size;

            while (fileOffset < fileSize)
            {
                ct.ThrowIfCancellationRequested();

                int toRead = (int)Math.Min(DefaultChunkSize, fileSize - fileOffset);
                int bytesRead = await fileStream.ReadAsync(readBuffer, 0, toRead, ct);
                if (bytesRead == 0) break;

                // Pack chunk header: fileIndex(4), offset(8), length(4)
                BinaryPrimitives.WriteUInt32LittleEndian(chunkPayload.AsSpan(0, 4), (uint)fileIdx);
                BinaryPrimitives.WriteUInt64LittleEndian(chunkPayload.AsSpan(4, 8), (ulong)fileOffset);
                BinaryPrimitives.WriteUInt32LittleEndian(chunkPayload.AsSpan(12, 4), (uint)bytesRead);
                Buffer.BlockCopy(readBuffer, 0, chunkPayload, 16, bytesRead);

                byte[] actualPayload = new byte[16 + bytesRead];
                Buffer.BlockCopy(chunkPayload, 0, actualPayload, 0, 16 + bytesRead);

                var chunkFrame = new Frame(MessageType.ChunkData, actualPayload);
                await chunkFrame.WriteToStreamAsync(stream, ct);

                fileOffset += bytesRead;
                totalTransferred += bytesRead;

                // Update Progress & Speed
                long now = stopwatch.ElapsedMilliseconds;
                if (now - lastTime >= 100 || totalTransferred == totalBytes)
                {
                    double elapsedSec = Math.Max(0.001, (now - lastTime) / 1000.0);
                    long bytesDelta = totalTransferred - lastBytes;
                    double instSpeed = bytesDelta / elapsedSec;
                    currentSpeed = currentSpeed == 0 ? instSpeed : (currentSpeed * 0.7 + instSpeed * 0.3);

                    lastTime = now;
                    lastBytes = totalTransferred;

                    long remainingBytes = Math.Max(0, totalBytes - totalTransferred);
                    double etaSec = currentSpeed > 0 ? remainingBytes / currentSpeed : 0;

                    onProgress(new TransferProgressReport
                    {
                        CurrentFileIndex = fileIdx,
                        CurrentFileName = item.Name,
                        CurrentFileProgress = (double)fileOffset / Math.Max(1, fileSize) * 100.0,
                        TotalBytesTransferred = totalTransferred,
                        TotalBytes = totalBytes,
                        TotalProgress = (double)totalTransferred / Math.Max(1, totalBytes) * 100.0,
                        SpeedBytesPerSec = currentSpeed,
                        FormattedSpeed = FormatSpeed(currentSpeed),
                        FormattedEta = etaSec < 1 ? "< 1s" : $"{etaSec:F0}s",
                        StatusMessage = $"Streaming {item.Name} at {FormatSpeed(currentSpeed)}..."
                    });
                }
            }

            // 5. Send FileComplete
            byte[] completePayload = new byte[4];
            BinaryPrimitives.WriteUInt32LittleEndian(completePayload, (uint)fileIdx);
            var completeFrame = new Frame(MessageType.FileComplete, completePayload);
            await completeFrame.WriteToStreamAsync(stream, ct);

            item.Status = "Completed";
            item.Progress = 100.0;
        }

        // 6. Send BatchComplete
        var batchFrame = new Frame(MessageType.BatchComplete, new byte[] { 1 });
        await batchFrame.WriteToStreamAsync(stream, ct);

        // 7. Wait for BatchComplete ACK
        var batchAck = await Frame.ReadFromStreamAsync(stream, ct);
        if (batchAck != null && batchAck.Type == MessageType.BatchComplete)
        {
            stopwatch.Stop();
            double avgSpeed = totalBytes / Math.Max(0.001, stopwatch.Elapsed.TotalSeconds);
            onProgress(new TransferProgressReport
            {
                TotalBytesTransferred = totalBytes,
                TotalBytes = totalBytes,
                TotalProgress = 100.0,
                SpeedBytesPerSec = 0,
                FormattedSpeed = "0 MB/s",
                FormattedEta = "Done",
                StatusMessage = $"Transfer complete! Sent {FormatBytes(totalBytes)} in {stopwatch.Elapsed.TotalSeconds:F2}s ({FormatSpeed(avgSpeed)} avg)."
            });
            return true;
        }

        return false;
    }

    private static string FormatSpeed(double bytesPerSec)
    {
        if (bytesPerSec >= 1024 * 1024 * 1024)
            return $"{bytesPerSec / (1024 * 1024 * 1024):F1} GB/s";
        if (bytesPerSec >= 1024 * 1024)
            return $"{bytesPerSec / (1024 * 1024):F1} MB/s";
        if (bytesPerSec >= 1024)
            return $"{bytesPerSec / 1024:F1} KB/s";
        return $"{bytesPerSec:F0} B/s";
    }

    private static string FormatBytes(long bytes)
    {
        string[] units = { "B", "KB", "MB", "GB", "TB" };
        double len = bytes;
        int order = 0;
        while (len >= 1024 && order < units.Length - 1)
        {
            order++;
            len /= 1024;
        }
        return $"{len:F2} {units[order]}";
    }
}
