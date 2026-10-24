using System;
using System.Buffers.Binary;
using System.Collections.Generic;
using System.IO;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace FileSync.App.Services;

public static class Crc32
{
    private static readonly uint[] Table;

    static Crc32()
    {
        Table = new uint[256];
        const uint poly = 0xEDB88320u;
        for (uint i = 0; i < 256; i++)
        {
            uint entry = i;
            for (int j = 0; j < 8; j++)
            {
                if ((entry & 1) != 0)
                    entry = (entry >> 1) ^ poly;
                else
                    entry >>= 1;
            }
            Table[i] = entry;
        }
    }

    public static uint Compute(byte[] buffer, int offset, int count)
    {
        uint crc = 0xFFFFFFFFu;
        for (int i = offset; i < offset + count; i++)
        {
            byte idx = (byte)((crc & 0xFF) ^ buffer[i]);
            crc = (crc >> 8) ^ Table[idx];
        }
        return ~crc;
    }

    public static uint Compute(ReadOnlySpan<byte> data)
    {
        uint crc = 0xFFFFFFFFu;
        foreach (byte b in data)
        {
            byte idx = (byte)((crc & 0xFF) ^ b);
            crc = (crc >> 8) ^ Table[idx];
        }
        return ~crc;
    }
}

public enum MessageType : byte
{
    BatchManifest = 0x10,
    ManifestAck = 0x11,
    ChunkData = 0x20,
    FileComplete = 0x22,
    BatchComplete = 0x23,
    ErrorMessage = 0xFF
}

public class Frame
{
    public static readonly byte[] Magic = Encoding.ASCII.GetBytes("FSYNC");
    public const byte ProtocolVersion = 1;
    public const int HeaderSize = 16;

    public MessageType Type { get; set; }
    public byte[] Payload { get; set; }

    public Frame(MessageType type, byte[] payload)
    {
        Type = type;
        Payload = payload ?? Array.Empty<byte>();
    }

    public async Task WriteToStreamAsync(Stream stream, CancellationToken ct = default)
    {
        uint payloadLen = (uint)Payload.Length;

        // 16-byte Header
        byte[] header = new byte[HeaderSize];
        Buffer.BlockCopy(Magic, 0, header, 0, 5);
        header[5] = ProtocolVersion;
        header[6] = (byte)Type;
        header[7] = 0; // Reserved
        BinaryPrimitives.WriteUInt32LittleEndian(header.AsSpan(8, 4), payloadLen);

        uint headerCrc = Crc32.Compute(header.AsSpan(0, 12));
        BinaryPrimitives.WriteUInt32LittleEndian(header.AsSpan(12, 4), headerCrc);

        await stream.WriteAsync(header, 0, HeaderSize, ct);

        if (payloadLen > 0)
        {
            await stream.WriteAsync(Payload, 0, (int)payloadLen, ct);

            uint payloadCrc = Crc32.Compute(Payload, 0, (int)payloadLen);
            byte[] crcBuf = new byte[4];
            BinaryPrimitives.WriteUInt32LittleEndian(crcBuf, payloadCrc);
            await stream.WriteAsync(crcBuf, 0, 4, ct);
        }
        else
        {
            byte[] crcBuf = new byte[4];
            BinaryPrimitives.WriteUInt32LittleEndian(crcBuf, 0);
            await stream.WriteAsync(crcBuf, 0, 4, ct);
        }

        await stream.FlushAsync(ct);
    }

    public static async Task<Frame?> ReadFromStreamAsync(Stream stream, CancellationToken ct = default)
    {
        byte[] header = new byte[HeaderSize];
        if (!await ReadExactAsync(stream, header, HeaderSize, ct))
        {
            return null; // Connection closed
        }

        // Validate Magic
        if (header[0] != Magic[0] || header[1] != Magic[1] || header[2] != Magic[2] ||
            header[3] != Magic[3] || header[4] != Magic[4])
        {
            throw new InvalidDataException("Invalid FileSync magic bytes.");
        }

        // Validate Header CRC
        uint expectedHeaderCrc = BinaryPrimitives.ReadUInt32LittleEndian(header.AsSpan(12, 4));
        uint calculatedHeaderCrc = Crc32.Compute(header.AsSpan(0, 12));
        if (expectedHeaderCrc != calculatedHeaderCrc)
        {
            throw new InvalidDataException($"Header CRC mismatch: expected {expectedHeaderCrc:X8}, got {calculatedHeaderCrc:X8}");
        }

        MessageType msgType = (MessageType)header[6];
        uint payloadLen = BinaryPrimitives.ReadUInt32LittleEndian(header.AsSpan(8, 4));

        byte[] payload = new byte[payloadLen];
        if (payloadLen > 0)
        {
            if (!await ReadExactAsync(stream, payload, (int)payloadLen, ct))
            {
                throw new EndOfStreamException("Stream ended while reading payload.");
            }
        }

        byte[] crcBuf = new byte[4];
        if (!await ReadExactAsync(stream, crcBuf, 4, ct))
        {
            throw new EndOfStreamException("Stream ended while reading payload CRC.");
        }

        uint expectedPayloadCrc = BinaryPrimitives.ReadUInt32LittleEndian(crcBuf);
        if (payloadLen > 0)
        {
            uint calculatedPayloadCrc = Crc32.Compute(payload, 0, (int)payloadLen);
            if (expectedPayloadCrc != calculatedPayloadCrc)
            {
                throw new InvalidDataException($"Payload CRC mismatch: expected {expectedPayloadCrc:X8}, got {calculatedPayloadCrc:X8}");
            }
        }

        return new Frame(msgType, payload);
    }

    private static async Task<bool> ReadExactAsync(Stream stream, byte[] buffer, int count, CancellationToken ct)
    {
        int totalRead = 0;
        while (totalRead < count)
        {
            int read = await stream.ReadAsync(buffer, totalRead, count - totalRead, ct);
            if (read == 0) return false;
            totalRead += read;
        }
        return true;
    }
}

public class ManifestDto
{
    public string SessionId { get; set; } = Guid.NewGuid().ToString();
    public string SenderDeviceName { get; set; } = Environment.MachineName;
    public int TotalFiles { get; set; }
    public long TotalBytes { get; set; }
    public List<FileEntryDto> Entries { get; set; } = new();

    public byte[] ToJsonBytes()
    {
        return JsonSerializer.SerializeToUtf8Bytes(this, new JsonSerializerOptions
        {
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase
        });
    }

    public static ManifestDto? FromJsonBytes(byte[] bytes)
    {
        return JsonSerializer.Deserialize<ManifestDto>(bytes, new JsonSerializerOptions
        {
            PropertyNameCaseInsensitive = true
        });
    }
}

public class FileEntryDto
{
    public int FileIndex { get; set; }
    public string RelativePath { get; set; } = "";
    public long Size { get; set; }
    public long ModifiedEpochNanos { get; set; }
    public long CreatedEpochNanos { get; set; }
}
