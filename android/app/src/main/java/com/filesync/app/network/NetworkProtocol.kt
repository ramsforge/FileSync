package com.filesync.app.network

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

object ProtocolCrc32 {
    fun compute(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Long {
        val crc = CRC32()
        crc.update(bytes, offset, length)
        return crc.value
    }
}

enum class MessageType(val value: Byte) {
    BatchManifest(0x10),
    ManifestAck(0x11),
    ChunkData(0x20),
    FileComplete(0x22),
    BatchComplete(0x23),
    ErrorMessage(0xFF.toByte());

    companion object {
        fun fromByte(b: Byte): MessageType {
            return entries.find { it.value == b } ?: ErrorMessage
        }
    }
}

class Frame(
    val type: MessageType,
    val payload: ByteArray
) {
    companion object {
        val MAGIC = "FSYNC".toByteArray(Charsets.US_ASCII)
        const val PROTOCOL_VERSION: Byte = 1
        const val HEADER_SIZE = 16

        fun readFromStream(input: InputStream): Frame? {
            val header = ByteArray(HEADER_SIZE)
            if (!readExact(input, header, HEADER_SIZE)) {
                return null // Connection closed
            }

            // Verify Magic "FSYNC"
            for (i in 0 until 5) {
                if (header[i] != MAGIC[i]) {
                    throw IllegalStateException("Invalid FileSync magic bytes")
                }
            }

            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val version = buf.get(5)
            val msgTypeByte = buf.get(6)
            val reserved = buf.get(7)
            val payloadLen = buf.getInt(8)
            val expectedHeaderCrc = buf.getInt(12).toLong() and 0xFFFFFFFFL

            val calculatedHeaderCrc = ProtocolCrc32.compute(header, 0, 12)
            if (expectedHeaderCrc != calculatedHeaderCrc) {
                throw IllegalStateException("Header CRC mismatch: expected $expectedHeaderCrc, calculated $calculatedHeaderCrc")
            }

            val payload = ByteArray(payloadLen)
            if (payloadLen > 0) {
                if (!readExact(input, payload, payloadLen)) {
                    throw EOFException("Stream ended while reading payload")
                }
            }

            val crcBuf = ByteArray(4)
            if (!readExact(input, crcBuf, 4)) {
                throw EOFException("Stream ended while reading payload CRC")
            }
            val expectedPayloadCrc = ByteBuffer.wrap(crcBuf).order(ByteOrder.LITTLE_ENDIAN).getInt().toLong() and 0xFFFFFFFFL

            if (payloadLen > 0) {
                val calculatedPayloadCrc = ProtocolCrc32.compute(payload, 0, payloadLen)
                if (expectedPayloadCrc != calculatedPayloadCrc) {
                    throw IllegalStateException("Payload CRC mismatch: expected $expectedPayloadCrc, calculated $calculatedPayloadCrc")
                }
            }

            return Frame(MessageType.fromByte(msgTypeByte), payload)
        }

        private fun readExact(input: InputStream, buffer: ByteArray, count: Int): Boolean {
            var totalRead = 0
            while (totalRead < count) {
                val read = input.read(buffer, totalRead, count - totalRead)
                if (read == -1) return false
                totalRead += read
            }
            return true
        }
    }

    fun writeToStream(output: OutputStream) {
        val payloadLen = payload.size
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.put(MAGIC)
        header.put(PROTOCOL_VERSION)
        header.put(type.value)
        header.put(0.toByte()) // Reserved
        header.putInt(payloadLen)

        val headerBytes = header.array()
        val headerCrc = ProtocolCrc32.compute(headerBytes, 0, 12)
        header.putInt(12, headerCrc.toInt())

        output.write(header.array())

        if (payloadLen > 0) {
            output.write(payload)
            val payloadCrc = ProtocolCrc32.compute(payload, 0, payloadLen)
            val crcBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payloadCrc.toInt()).array()
            output.write(crcBuf)
        } else {
            val crcBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array()
            output.write(crcBuf)
        }

        output.flush()
    }
}

data class FileEntryDto(
    val fileIndex: Int,
    val relativePath: String,
    val size: Long,
    val modifiedEpochNanos: Long,
    val createdEpochNanos: Long
)

data class ManifestDto(
    val sessionId: String,
    val senderDeviceName: String,
    val totalFiles: Int,
    val totalBytes: Long,
    val entries: List<FileEntryDto>
) {
    fun toJsonBytes(): ByteArray {
        val root = JSONObject()
        root.put("sessionId", sessionId)
        root.put("senderDeviceName", senderDeviceName)
        root.put("totalFiles", totalFiles)
        root.put("totalBytes", totalBytes)

        val array = JSONArray()
        for (e in entries) {
            val item = JSONObject()
            item.put("fileIndex", e.fileIndex)
            item.put("relativePath", e.relativePath)
            item.put("size", e.size)
            item.put("modifiedEpochNanos", e.modifiedEpochNanos)
            item.put("createdEpochNanos", e.createdEpochNanos)
            array.put(item)
        }
        root.put("entries", array)
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun fromJsonBytes(bytes: ByteArray): ManifestDto {
            val str = String(bytes, Charsets.UTF_8)
            val root = JSONObject(str)
            val sessionId = root.optString("sessionId", "session")
            val senderDeviceName = root.optString("senderDeviceName", "Peer")
            val totalFiles = root.optInt("totalFiles", 0)
            val totalBytes = root.optLong("totalBytes", 0L)

            val entriesList = mutableListOf<FileEntryDto>()
            val array = root.optJSONArray("entries")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    entriesList.add(
                        FileEntryDto(
                            fileIndex = item.optInt("fileIndex", i),
                            relativePath = item.optString("relativePath", "file_$i"),
                            size = item.optLong("size", 0L),
                            modifiedEpochNanos = item.optLong("modifiedEpochNanos", 0L),
                            createdEpochNanos = item.optLong("createdEpochNanos", 0L)
                        )
                    )
                }
            }

            return ManifestDto(sessionId, senderDeviceName, totalFiles, totalBytes, entriesList)
        }
    }
}
