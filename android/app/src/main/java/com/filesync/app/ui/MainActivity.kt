package com.filesync.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Parcelable
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.filesync.app.core.ReceivedItem
import com.filesync.app.core.TransferItem
import com.filesync.app.network.TransferSenderClient
import com.filesync.app.service.TransferForegroundService
import com.filesync.app.ui.screens.HomeScreen
import com.filesync.app.ui.theme.FileSyncTheme
import com.filesync.app.util.PreferencesHelper
import com.filesync.app.util.StorageHelper
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private val queue = mutableStateListOf<TransferItem>()
    private val receivedFiles = mutableStateListOf<ReceivedItem>()
    private var isTransferring by mutableStateOf(false)
    private var currentSpeed by mutableStateOf("0 MB/s")
    private var totalProgress by mutableStateOf(0f)
    private var transferredText by mutableStateOf("0 MB / 0 MB")
    private var etaText by mutableStateOf("--")
    private var outputDirectory by mutableStateOf("")

    private val fileReceivedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val fileName = intent?.getStringExtra("FILE_NAME") ?: "New file"
            refreshReceivedFiles()
            val folderName = File(outputDirectory).name
            Toast.makeText(this@MainActivity, "🎉 Received: $fileName in $folderName", Toast.LENGTH_LONG).show()
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris?.let { handleUris(it) }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}

            val resolvedPath = StorageHelper.getPathFromTreeUri(this, it)
            if (resolvedPath != null) {
                setOutputFolder(resolvedPath)
            } else {
                Toast.makeText(this, "Could not resolve folder path. Please select internal storage folder.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        outputDirectory = PreferencesHelper.getOutputDirectory(this)

        // Ensure receiver service is active in background
        val serviceIntent = Intent(this, TransferForegroundService::class.java).apply {
            action = TransferForegroundService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        refreshReceivedFiles()

        val filter = IntentFilter("com.filesync.ACTION_FILE_RECEIVED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(fileReceivedReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(fileReceivedReceiver, filter)
        }

        // Handle Share Sheet intent if launched from another app
        handleIncomingIntent(intent)

        setContent {
            FileSyncTheme {
                HomeScreen(
                    queue = queue,
                    receivedFiles = receivedFiles,
                    outputDirectory = outputDirectory,
                    isTransferring = isTransferring,
                    currentSpeed = currentSpeed,
                    totalProgress = totalProgress,
                    transferredText = transferredText,
                    etaText = etaText,
                    onAddFilesClick = {
                        filePickerLauncher.launch("*/*")
                    },
                    onChangeOutputDirectoryClick = {
                        showFolderSelectionDialog()
                    },
                    onClearQueueClick = {
                        queue.clear()
                        updateTotals()
                    },
                    onClearCompletedClick = {
                        clearCompleted()
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

    override fun onResume() {
        super.onResume()
        outputDirectory = PreferencesHelper.getOutputDirectory(this)
        refreshReceivedFiles()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(fileReceivedReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun refreshReceivedFiles() {
        receivedFiles.clear()
        val currentDir = PreferencesHelper.getOutputDirectory(this)
        outputDirectory = currentDir
        val baseDir = File(currentDir)
        if (baseDir.exists() && baseDir.isDirectory) {
            val files = baseDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
            if (files != null) {
                val pathsToScan = mutableListOf<String>()
                files.forEach { f ->
                    receivedFiles.add(
                        ReceivedItem(
                            name = f.name,
                            path = f.absolutePath,
                            size = f.length(),
                            modifiedTime = f.lastModified()
                        )
                    )
                    pathsToScan.add(f.absolutePath)
                }
                if (pathsToScan.isNotEmpty()) {
                    MediaScannerConnection.scanFile(this, pathsToScan.toTypedArray(), null, null)
                }
            }
        }
    }

    private fun setOutputFolder(path: String) {
        PreferencesHelper.setOutputDirectory(this, path)
        outputDirectory = PreferencesHelper.getOutputDirectory(this)
        refreshReceivedFiles()
        Toast.makeText(this, "Destination folder set to:\n$path", Toast.LENGTH_LONG).show()
    }

    private fun showFolderSelectionDialog() {
        val presets = arrayOf(
            "Downloads / FileSync (Default)",
            "Downloads (Root)",
            "DCIM (Photos & Camera)",
            "Documents",
            "Browse Custom Folder..."
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("Select Destination Folder")
            .setItems(presets) { _, which ->
                when (which) {
                    0 -> setOutputFolder(PreferencesHelper.getDefaultOutputDirectory())
                    1 -> setOutputFolder(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
                    2 -> setOutputFolder(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).absolutePath)
                    3 -> setOutputFolder(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath)
                    4 -> folderPickerLauncher.launch(null)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        val pendingItems = queue.filter { !it.isDirectory && it.status != "Completed" }
        val totalBytes = pendingItems.sumOf { it.size }
        transferredText = "0 MB / ${formatBytes(totalBytes)}"
    }

    private fun clearCompleted() {
        queue.removeAll { it.status == "Completed" }
        updateTotals()
    }

    private fun startTransfer() {
        val pendingItems = queue.filter { !it.isDirectory && it.status != "Completed" }
        if (pendingItems.isEmpty()) {
            if (queue.isNotEmpty()) {
                Toast.makeText(this, "All queued files have already been sent to PC.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        isTransferring = true
        val totalBytes = pendingItems.sumOf { it.size }
        transferredText = "0 MB / ${formatBytes(totalBytes)}"
        currentSpeed = "Connecting..."
        etaText = "--"

        val client = TransferSenderClient(this).apply {
            onFileStarted = { index, _ ->
                val item = pendingItems.getOrNull(index)
                if (item != null) {
                    val qIdx = queue.indexOfFirst { it.fullPath == item.fullPath }
                    if (qIdx != -1) {
                        queue[qIdx] = queue[qIdx].copy(status = "InProgress", progress = 0f)
                    }
                }
            }
            onFileProgress = { index, bytesSent, totalFileSize ->
                val item = pendingItems.getOrNull(index)
                if (item != null && totalFileSize > 0) {
                    val qIdx = queue.indexOfFirst { it.fullPath == item.fullPath }
                    if (qIdx != -1) {
                        val frac = (bytesSent.toFloat() / totalFileSize).coerceIn(0f, 1f)
                        queue[qIdx] = queue[qIdx].copy(progress = frac)
                    }
                }
            }
            onFileCompleted = { index, _ ->
                val item = pendingItems.getOrNull(index)
                if (item != null) {
                    val qIdx = queue.indexOfFirst { it.fullPath == item.fullPath }
                    if (qIdx != -1) {
                        queue[qIdx] = queue[qIdx].copy(status = "Completed", progress = 1f)
                    }
                }
            }
        }

        lifecycleScope.launch {
            try {
                // USB ADB tunnel connects to Windows port 45781 via adb reverse (port 45783)
                val host = "127.0.0.1"
                val port = 45783

                val success = client.sendFiles(host, port, pendingItems) { percent, speed, fileName, transferred, total ->
                    totalProgress = percent / 100f
                    currentSpeed = speed
                    transferredText = "${formatBytes(transferred)} / ${formatBytes(total)}"

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
                    etaText = "Done"
                    totalProgress = 1f
                    for (item in pendingItems) {
                        val qIdx = queue.indexOfFirst { it.fullPath == item.fullPath }
                        if (qIdx != -1) {
                            queue[qIdx] = queue[qIdx].copy(status = "Completed", progress = 1f)
                        }
                    }
                    Toast.makeText(this@MainActivity, "✅ Successfully sent ${pendingItems.size} file(s) to Windows!", Toast.LENGTH_LONG).show()
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
                updateTotals()
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
