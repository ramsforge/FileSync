package com.filesync.app.util

import android.content.Context
import android.os.Environment
import java.io.File

object PreferencesHelper {

    private const val PREFS_NAME = "filesync_prefs"
    private const val KEY_OUTPUT_DIR = "output_directory"

    fun getDefaultOutputDirectory(): String {
        return File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "FileSync"
        ).absolutePath
    }

    fun getOutputDirectory(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val path = prefs.getString(KEY_OUTPUT_DIR, null)
        return if (!path.isNullOrEmpty() && File(path).exists()) {
            path
        } else {
            val def = getDefaultOutputDirectory()
            File(def).mkdirs()
            def
        }
    }

    fun setOutputDirectory(context: Context, path: String) {
        val dir = File(path)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_OUTPUT_DIR, dir.absolutePath).apply()
    }
}
