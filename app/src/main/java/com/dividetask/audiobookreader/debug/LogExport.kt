package com.dividetask.audiobookreader.debug

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Saves [text] as a file the user can attach elsewhere: in Downloads on Android 10+,
 * else in the app's folder under Android/data. Returns where it went.
 */
fun saveToDownloads(context: Context, fileName: String, text: String): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create $fileName in Downloads")
        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            ?: error("Could not write $fileName")
        return "Downloads/$fileName"
    }
    val file = File(context.getExternalFilesDir(null), fileName)
    file.writeText(text)
    return file.absolutePath
}
