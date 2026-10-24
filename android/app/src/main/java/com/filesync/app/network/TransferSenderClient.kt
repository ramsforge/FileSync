package com.filesync.app.network

import android.content.Context
import android.net.Uri
import com.filesync.app.core.TransferItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TransferSenderClient(private val context: Context) {

    companion object {
        const val DEFAULT_CHUNK_SIZE = 1024 * 1024 // 1 MB high-speed streaming chunks
    }

    suspend fun sendFiles(
        host: String,
        port: Int,
        items: List<TransferItem>,
        onProgress: (progressPercent: Int, speed: String, fileName: String, transferred: Long, total: Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val validItems = items.filter { !it.isDirectory }
        if (validItems.isEmpty()) return@withContext false

        val socket = Socket(host, port).apply {
            tcpNoDelay = true
            sendBufferSize = 4 * 1024 * 1024
            receiveBufferSize = 4 * 1024 * 1024
        }

        socket.use { s ->
            val input = s.getInputStream()
            val output = s.getOutputStream()

            // 1. Build Manifest
            val totalBytes = validItems.sumOf { it.size }
            val entries = mutableListOf<FileEntryDto>()

            for ((idx, item) in validItems.withIndex()) {
                val f = File(item.fullPath)
                val mtimeNanos = if (f.exists()) f.lastModified() * 1_000_000L else System.currentTimeMillis() * 1_000_000L
                entries.add(
                    FileEntryDto(
                        fileIndex = idx,
                        relativePath = item.relativePath.ifEmpty { item.name },
                        size = item.size,
                        modifiedEpochNanos = mtimeNanos,
                        createdEpochNanos = mtimeNanos
                    )
                )
            }

            val manifest = ManifestDto(
                sessionId = java.util.UUID.randomUUID().toString(),
                senderDeviceName = android.os.Build.MODEL,
                totalFiles = validItems.size,
                totalBytes = totalBytes,
                entries = entries
            )

            // 2. Send Manifest Frame
            val manifestFrame = Frame(MessageType.BatchManifest, manifest.toJsonBytes())
            manifestFrame.writeToStream(output)

            // 3. Await ManifestAck
            val ack = Frame.readFromStream(input)
            if (ack == null || ack.type != MessageType.ManifestAck) {
                throw IllegalStateException("Did not receive ManifestAck from remote peer")
            }

            // 4. Stream Chunks
            var totalTransferred = 0L
            val readBuffer = ByteArray(DEFAULT_CHUNK_SIZE)
            val chunkPayload = ByteArray(16 + DEFAULT_CHUNK_SIZE)

            var lastTime = System.currentTimeMillis()
            var lastBytes = 0L

            for ((idx, item) in validItems.withIndex()) {
                val inputStream: InputStream = if (item.fullPath.startsWith("content://")) {
                    context.contentResolver.openInputStream(Uri.parse(item.fullPath))
                        ?: throw IllegalStateException("Cannot open content URI: ${item.fullPath}")
                } else {
                    FileInputStream(File(item.fullPath))
                }

                inputStream.use { stream ->
                    var fileOffset = 0L
                    val fileSize = item.size

                    while (fileOffset < fileSize) {
                        val toRead = minOf(DEFAULT_CHUNK_SIZE.toLong(), fileSize - fileOffset).toInt()
                        val bytesRead = stream.read(readBuffer, 0, toRead)
                        if (bytesRead == -1 || bytesRead == 0) break

                        // Header: fileIndex(4), offset(8), length(4)
                        val headerBuf = ByteBuffer.wrap(chunkPayload, 0, 16).order(ByteOrder.LITTLE_ENDIAN)
                        headerBuf.putInt(idx)
                        headerBuf.putLong(fileOffset)
                        headerBuf.putInt(bytesRead)

                        System.arraycopy(readBuffer, 0, chunkPayload, 16, bytesRead)

                        val payloadToSend = ByteArray(16 + bytesRead)
                        System.arraycopy(chunkPayload, 0, payloadToSend, 0, 16 + bytesRead)

                        val chunkFrame = Frame(MessageType.ChunkData, payloadToSend)
                        chunkFrame.writeToStream(output)

                        fileOffset += bytesRead
                        totalTransferred += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastTime >= 100 || totalTransferred == totalBytes) {
                            val elapsedSec = maxOf(0.001, (now - lastTime) / 1000.0)
                            val deltaBytes = totalTransferred - lastBytes
                            val speed = deltaBytes / elapsedSec
                            lastTime = now
                            lastBytes = totalTransferred

                            val percent = if (totalBytes > 0) (totalTransferred * 100 / totalBytes).toInt() else 100
                            val speedStr = formatSpeed(speed)
                            onProgress(percent, speedStr, item.name, totalTransferred, totalBytes)
                        }
                    }
                }

                // 5. Send FileComplete
                val completeBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(idx).array()
                val completeFrame = Frame(MessageType.FileComplete, completeBuf)
                completeFrame.writeToStream(output)
            }

            // 6. Wait for BatchComplete
            val batchAck = Frame.readFromStream(input)
            return@withContext batchAck?.type == MessageType.BatchComplete
        }
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        return when {
            bytesPerSec >= 1024 * 1024 * 1024 -> String.format("%.1f GB/s", bytesPerSec / (1024 * 1024 * 1024))
            bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / (1024 * 1024))
            bytesPerSec >= 1024 -> String.format("%.1f KB/s", bytesPerSec / 1024)
            else -> String.format("%.0f B/s", bytesPerSec)
        }
    }
}
