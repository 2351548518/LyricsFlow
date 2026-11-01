package com.example.lyricsflow

import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lyricsflow.core.*
import com.example.lyricsflow.overlay.LyricsOverlayService
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OverlayLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun hiddenOverlayStillStopsImmediatelyWhenPermissionIsRevoked() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = compose.activity
        val graph = (context.application as LyricsFlowApp).graph
        fun permission(mode: String) {
            instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW $mode")
                .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
        }
        fun await(condition: () -> Boolean) {
            val end = android.os.SystemClock.elapsedRealtime() + 5000
            while (!condition() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(25)
            assertTrue(condition())
        }
        try {
            permission("allow")
            compose.runOnUiThread {
                graph.preview()
                ContextCompat.startForegroundService(context, Intent(context, LyricsOverlayService::class.java))
            }
            await { graph.state.value.overlay }
            compose.runOnUiThread {
                graph.onPlayback(Playback())
                graph.live()
            }
            await { graph.state.value.playback.track == null }
            Thread.sleep(250)
            assertTrue(graph.state.value.overlay)
            permission("deny")
            await { !graph.state.value.overlay }
        } finally {
            compose.runOnUiThread { context.stopService(Intent(context, LyricsOverlayService::class.java)) }
            permission("allow")
        }
    }
}
