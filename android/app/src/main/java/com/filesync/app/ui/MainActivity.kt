package com.filesync.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.filesync.app.core.TransferItem
import com.filesync.app.network.TransferSenderClient
import com.filesync.app.service.TransferForegroundService
import com.filesync.app.ui.screens.HomeScreen
import com.filesync.app.ui.theme.FileSyncTheme
import com.filesync.app.util.StorageHelper
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private val queue = mutableStateListOf<TransferItem>()
    private var isTransferring by mutableStateOf(false)
    private var currentSpeed by mutableStateOf("0 MB/s")
    private var totalProgress by mutableStateOf(0f)
    private var transferredText by mutableStateOf("0 MB / 0 MB")
    private var etaText by mutableStateOf("--")

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris?.let { handleUris(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure receiver service is active in background
        val serviceIntent = Intent(this, TransferForegroundService::class.java).apply {
            action = TransferForegroundService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Handle Share Sheet intent if launched from another app
        handleIncomingIntent(intent)

        setContent {
            FileSyncTheme {
                HomeScreen(
                    queue = queue,
                    isTransferring = isTransferring,
                    currentSpeed = currentSpeed,
                    totalProgress = totalProgress,
                    transferredText = transferredText,
                    etaText = etaText,
                    onAddFilesClick = {
                        filePickerLauncher.launch("*/*")
                    },
                    onClearQueueClick = {
                        queue.clear()
                        updateTotals()
                    },
                    onStartTransferClick = {
                        startTransfer()
                    },
                    onCancelTransferClick = {
                        cancelTransfer()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_SEND -> {
                (intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let {
                    handleUris(listOf(it))
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)?.let { list ->
                    val uris = list.filterIsInstance<Uri>()
                    handleUris(uris)
                }
            }
        }
    }

    private fun handleUris(uris: List<Uri>) {
        for (uri in uris) {
            val directPath = StorageHelper.getFilePathFromUri(this, uri)
            if (directPath != null) {
                val file = File(directPath)
                if (file.exists()) {
                    queue.add(
                        TransferItem(
                            fullPath = directPath,
                            name = file.name,
                            relativePath = file.name,
                            size = file.length(),
                            isDirectory = file.isDirectory
                        )
                    )
                    continue
                }
            }

            // Fallback for virtual or content provider file
            val name = uri.lastPathSegment ?: "shared_file"
            queue.add(
                TransferItem(
                    fullPath = uri.toString(),
                    name = name,
                    relativePath = name,
                    size = 1024 * 1024,
                    isDirectory = false
                )
            )
        }
        updateTotals()
    }

    private fun updateTotals() {
        val totalBytes = queue.filter { !it.isDirectory }.sumOf { it.size }
        transferredText = "0 MB / ${formatBytes(totalBytes)}"
    }

    private fun startTransfer() {
        if (queue.isEmpty()) return

        isTransferring = true
        val totalBytes = queue.filter { !it.isDirectory }.sumOf { it.size }
        transferredText = "0 MB / ${formatBytes(totalBytes)}"
        currentSpeed = "Connecting..."
        etaText = "--"

        val client = TransferSenderClient(this)

        lifecycleScope.launch {
            try {
                // USB ADB tunnel connects to Windows port 45781 via adb reverse
                val host = "127.0.0.1"
                val port = 45781

                val success = client.sendFiles(host, port, queue.toList()) { percent, speed, fileName, transferred, total ->
                    totalProgress = percent / 100f
                    currentSpeed = speed
                    transferredText = "${formatBytes(transferred)} / ${formatBytes(total)}"

                    val remainingBytes = (total - transferred).coerceAtLeast(0)
                    etaText = if (speed.contains("MB/s") || speed.contains("GB/s")) "< 5s" else "--"

                    val updateIntent = Intent(this@MainActivity, TransferForegroundService::class.java).apply {
                        action = TransferForegroundService.ACTION_UPDATE
                        putExtra(TransferForegroundService.EXTRA_PROGRESS, percent)
                        putExtra(TransferForegroundService.EXTRA_SPEED, speed)
                        putExtra(TransferForegroundService.EXTRA_FILE_NAME, fileName)
                    }
                    startService(updateIntent)
                }

                if (success) {
                    currentSpeed = "0 MB/s"
                    etaText = "Complete"
                    totalProgress = 1f
                    for (item in queue) {
                        item.status = "Completed"
                        item.progress = 1f
                    }
                    Toast.makeText(this@MainActivity, "Files sent to Windows successfully!", Toast.LENGTH_LONG).show()
                } else {
                    currentSpeed = "0 MB/s"
                    etaText = "Failed"
                    Toast.makeText(this@MainActivity, "Transfer failed or peer disconnected.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                currentSpeed = "0 MB/s"
                etaText = "Error"
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isTransferring = false
            }
        }
    }

    private fun cancelTransfer() {
        isTransferring = false
        currentSpeed = "0 MB/s"
        etaText = "Cancelled"
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var len = bytes.toDouble()
        var order = 0
        while (len >= 1024 && order < units.size - 1) {
            order++; len /= 1024
        }
        return String.format("%.2f %s", len, units[order])
    }
}
