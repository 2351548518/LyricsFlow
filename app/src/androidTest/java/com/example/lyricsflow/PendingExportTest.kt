package com.example.lyricsflow

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lyricsflow.data.PendingExport
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PendingExportTest {
    @Test fun payloadSurvivesNewInstanceAndStreamsExactly() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val slot = "export-process-recreation-test.bin"
        val destination = File(context.cacheDir, "export-process-recreation-test.zip")
        val payload = ByteArray(6_000_000) { (it % 251).toByte() }
        try {
            PendingExport(context, slot).stage(payload)
            val recreated = PendingExport(context, slot)
            recreated.writeTo(Uri.fromFile(destination))
            assertArrayEquals(payload, destination.readBytes())
            recreated.clear()
            assertTrue(runCatching { recreated.writeTo(Uri.fromFile(destination)) }.isFailure)
        } finally { PendingExport(context, slot).clear(); destination.delete() }
    }

    @Test fun cancellationClearsTheStagedPayload() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val slot = "export-cancel-test.bin"
        val pending = PendingExport(context, slot)
        pending.stage("private export data".toByteArray())
        pending.clear()
        assertFalse(File(context.noBackupFilesDir, slot).exists())
    }
}
