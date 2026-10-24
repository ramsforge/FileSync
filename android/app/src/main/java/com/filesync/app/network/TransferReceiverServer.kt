package com.filesync.app.network

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class TransferReceiverServer(private val context: Context) {

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    companion object {
        private const val TAG = "TransferReceiverServer"
        const val DEFAULT_PORT = 45780
    }

    var onStatusChanged: ((String) -> Unit)? = null
    var onProgressChanged: ((Int, String, String) -> Unit)? = null // progressPercent, speedStr, fileName
    var onFileReceived: ((String, File) -> Unit)? = null

    fun start(port: Int = DEFAULT_PORT) {
        if (isRunning.get()) return
        isRunning.set(true)

        serverThread = Thread({
            try {
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                Log.i(TAG, "Transfer Receiver Server listening on port $port")
                onStatusChanged?.invoke("Receiver listening on port $port...")

                while (isRunning.get()) {
                    val client = serverSocket?.accept() ?: break
                    client.tcpNoDelay = true
                    client.receiveBufferSize = 4 * 1024 * 1024
                    client.sendBufferSize = 4 * 1024 * 1024

                    Thread({
                        handleClient(client)
                    }, "FileSync-ClientWorker").start()
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Server socket error", e)
                    onStatusChanged?.invoke("Server error: ${e.message}")
                }
            }
        }, "FileSync-ReceiverServer").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverThread?.interrupt()
        serverSocket = null
        serverThread = null
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            val input = s.getInputStream()
            val output = s.getOutputStream()

            val baseDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "FileSync"
            ).apply { mkdirs() }

            try {
                // 1. Read BatchManifest
                val manifestFrame = Frame.readFromStream(input) ?: return
                if (manifestFrame.type != MessageType.BatchManifest) return

                val manifest = ManifestDto.fromJsonBytes(manifestFrame.payload)
                Log.i(TAG, "Receiving batch: ${manifest.totalFiles} files (${manifest.totalBytes} bytes) from ${manifest.senderDeviceName}")
                onStatusChanged?.invoke("Receiving ${manifest.totalFiles} files from ${manifest.senderDeviceName}...")

                // 2. Send ManifestAck
                val ack = Frame(MessageType.ManifestAck, byteArrayOf(1))
                ack.writeToStream(output)

                // Pre-allocate files
                val activeFiles = mutableMapOf<Int, Pair<RandomAccessFile, File>>()
                for (entry in manifest.entries) {
                    val sanitized = entry.relativePath
                        .replace('\\', '/')
                        .trimStart('/')

                    val destFile = File(baseDir, sanitized)
                    destFile.parentFile?.mkdirs()

                    val raf = RandomAccessFile(destFile, "rw")
                    if (entry.size > 0) {
                        raf.setLength(entry.size) // Pre-allocate contiguous disk space
                    }
                    activeFiles[entry.fileIndex] = Pair(raf, destFile)
                }

                var totalBytesTransferred = 0L
                val totalBytes = manifest.totalBytes
                var lastTime = System.currentTimeMillis()
                var lastBytes = 0L

                // 3. Receive Chunks
                while (true) {
                    val frame = Frame.readFromStream(input) ?: break

                    if (frame.type == MessageType.ChunkData) {
                        if (frame.payload.size < 16) continue

                        val buf = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN)
                        val fileIdx = buf.getInt()
                        val offset = buf.getLong()
                        val length = buf.getInt()

                        activeFiles[fileIdx]?.first?.let { raf ->
                            raf.seek(offset)
                            raf.write(frame.payload, 16, length)
                        }

                        totalBytesTransferred += length

                        val now = System.currentTimeMillis()
                        if (now - lastTime >= 100 || totalBytesTransferred == totalBytes) {
                            val elapsedSec = maxOf(0.001, (now - lastTime) / 1000.0)
                            val deltaBytes = totalBytesTransferred - lastBytes
                            val speed = deltaBytes / elapsedSec
                            lastTime = now
                            lastBytes = totalBytesTransferred

                            val percent = if (totalBytes > 0) (totalBytesTransferred * 100 / totalBytes).toInt() else 100
                            val speedStr = formatSpeed(speed)
                            onProgressChanged?.invoke(percent, speedStr, "Receiving files")
                        }
                    } else if (frame.type == MessageType.FileComplete) {
                        if (frame.payload.size >= 4) {
                            val buf = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN)
                            val fileIdx = buf.getInt()
                            activeFiles[fileIdx]?.let { (raf, file) ->
                                raf.close()

                                // Restore exact timestamps
                                val entry = manifest.entries.find { it.fileIndex == fileIdx }
                                if (entry != null && entry.modifiedEpochNanos > 0) {
                                    val mtimeMillis = entry.modifiedEpochNanos / 1_000_000L
                                    file.setLastModified(mtimeMillis)
                                }

                                // Scan into MediaStore so Gallery & File Manager immediately see it
                                MediaScannerConnection.scanFile(
                                    context,
                                    arrayOf(file.absolutePath),
                                    null,
                                    null
                                )

                                onFileReceived?.invoke(file.name, file)
                                Log.i(TAG, "File received & verified: ${file.name} (${file.length()} bytes)")
                            }
                        }
                    } else if (frame.type == MessageType.BatchComplete) {
                        break
                    }
                }

                // Close any remaining handles
                for ((_, pair) in activeFiles) {
                    try {
                        pair.first.close()
                    } catch (_: Exception) {}
                }

                // 4. Send BatchComplete ACK
                val batchAck = Frame(MessageType.BatchComplete, byteArrayOf(1))
                batchAck.writeToStream(output)

                onStatusChanged?.invoke("Batch complete! Saved ${manifest.totalFiles} file(s) to Downloads/FileSync.")
                onProgressChanged?.invoke(100, "0 MB/s", "Transfer Complete")
                Log.i(TAG, "Batch transfer finished successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Error handling client transfer", e)
                onStatusChanged?.invoke("Transfer error: ${e.message}")
            }
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
