package com.example.lyricsflow.data

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Retain exports on private disk while the system picker is open, including process recreation. */
internal class PendingExport(context: Context, name: String = "pending-export.bin") {
    private val app = context.applicationContext
    private val file = AtomicFile(File(app.noBackupFilesDir, name))

    suspend fun stage(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }

    suspend fun writeTo(uri: Uri) = withContext(Dispatchers.IO) {
        file.openRead().use { input ->
            checkNotNull(app.contentResolver.openOutputStream(uri, "wt")) { "无法打开导出文件" }
                .use { output -> input.copyTo(output) }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) { file.delete() }
}
