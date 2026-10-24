package com.filesync.app.util

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object StorageHelper {

    fun hasAllFilesAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true // Below Android 11, standard read/write storage permissions apply
        }
    }

    fun requestAllFilesAccess(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                activity.startActivity(intent)
            }
        }
    }

    /**
     * Resolves a Storage Access Framework Tree URI into a direct POSIX file path.
     */
    fun getPathFromTreeUri(context: Context, treeUri: Uri): String? {
        try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val split = docId.split(":").toTypedArray()
            val type = split[0]
            val subPath = if (split.size > 1) split[1] else ""
            if ("primary".equals(type, ignoreCase = true)) {
                val extDir = Environment.getExternalStorageDirectory().absolutePath
                return if (subPath.isNotEmpty()) "$extDir/$subPath" else extDir
            } else {
                // Secondary SD card handling: /storage/{uuid}/{subPath}
                val rawPath = "/storage/$type"
                return if (subPath.isNotEmpty()) "$rawPath/$subPath" else rawPath
            }
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Resolves a Content URI into a direct POSIX file path for bit-for-bit high-speed Rust I/O.
     */
    fun getFilePathFromUri(context: Context, uri: Uri): String? {
        // Direct file scheme
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            return uri.path
        }

        // DocumentsContract
        if (DocumentsContract.isDocumentUri(context, uri)) {
            if ("com.android.externalstorage.documents" == uri.authority) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":").toTypedArray()
                val type = split[0]
                if ("primary".equals(type, ignoreCase = true)) {
                    return "${Environment.getExternalStorageDirectory()}/${split[1]}"
                }
            } else if ("com.android.providers.downloads.documents" == uri.authority) {
                val id = DocumentsContract.getDocumentId(uri)
                if (id.startsWith("raw:")) {
                    return id.replaceFirst("raw:", "")
                }
            } else if ("com.android.providers.media.documents" == uri.authority) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":").toTypedArray()
                val contentUri = when (split[0]) {
                    "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    else -> MediaStore.Files.getContentUri("external")
                }
                val selection = "_id=?"
                val selectionArgs = arrayOf(split[1])
                return getDataColumn(context, contentUri, selection, selectionArgs)
            }
        }

        // MediaStore or other ContentProvider
        if ("content".equals(uri.scheme, ignoreCase = true)) {
            return getDataColumn(context, uri, null, null)
        }

        return null
    }

    private fun getDataColumn(
        context: Context,
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): String? {
        val column = "_data"
        val projection = arrayOf(column)
        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndexOrThrow(column)
                    return cursor.getString(columnIndex)
                }
            }
        } catch (e: Exception) {
            // Fallback: If _data column is unavailable or restricted
        }
        return null
    }

    /**
     * Opens a received file in its system viewer (PDF reader, Gallery, etc.) using secure FileProvider.
     */
    fun openFile(context: Context, file: File) {
        if (!file.exists()) {
            Toast.makeText(context, "File does not exist: ${file.name}", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val extension = file.extension.lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "com.filesync.app.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "Open with").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Launches the system file manager (Samsung My Files, Google Files, etc.) directed to the received folder.
     */
    fun openReceivedFolder(context: Context, folderPath: String? = null) {
        val targetPath = folderPath ?: PreferencesHelper.getOutputDirectory(context)
        val targetDir = File(targetPath).apply { mkdirs() }

        try {
            val contentUri = FileProvider.getUriForFile(context, "com.filesync.app.fileprovider", targetDir)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return
        } catch (_: Exception) {}

        try {
            val samsungIntent = context.packageManager.getLaunchIntentForPackage("com.sec.android.app.myfiles")
            if (samsungIntent != null) {
                samsungIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(samsungIntent)
                Toast.makeText(context, "Location: $targetPath", Toast.LENGTH_LONG).show()
                return
            }

            val downloadIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(downloadIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "Location: $targetPath", Toast.LENGTH_LONG).show()
        }
    }
}
