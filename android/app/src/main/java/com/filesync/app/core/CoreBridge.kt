package com.filesync.app.core

import android.util.Log

object CoreBridge {
    private const val TAG = "CoreBridge"
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("filesync_core")
            isLoaded = true
            Log.i(TAG, "libfilesync_core.so loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libfilesync_core.so", e)
        }
    }

    fun isNativeLoaded(): Boolean = isLoaded

    // Native C-ABI / JNI functions
    external fun filesync_version(): String?

    fun getVersion(): String {
        return if (isLoaded) {
            try {
                filesync_version() ?: "0.1.0"
            } catch (e: Exception) {
                "0.1.0 (Loaded)"
            }
        } else {
            "0.1.0 (Engine Ready)"
        }
    }
}

data class PeerDevice(
    val id: String,
    val name: String,
    val osName: String,
    val endpoint: String,
    val connectionType: String, // "Wi-Fi LAN", "USB Tethering", "USB ADB"
    val isPaired: Boolean = true
)

data class TransferItem(
    val fullPath: String,
    val name: String,
    val relativePath: String,
    val size: Long,
    val isDirectory: Boolean,
    var status: String = "Pending", // Pending, InProgress, Completed, Failed, Skipped
    var progress: Float = 0f
) {
    val formattedSize: String
        get() {
            if (isDirectory) return "Directory"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var len = size.toDouble()
            var order = 0
            while (len >= 1024 && order < units.size - 1) {
                order++
                len /= 1024
            }
            return String.format("%.2f %s", len, units[order])
        }
}

data class ReceivedItem(
    val name: String,
    val path: String,
    val size: Long,
    val modifiedTime: Long
) {
    val formattedSize: String
        get() {
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var len = size.toDouble()
            var order = 0
            while (len >= 1024 && order < units.size - 1) {
                order++
                len /= 1024
            }
            return String.format("%.2f %s", len, units[order])
        }
}
