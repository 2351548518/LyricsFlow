package com.example.lyricsflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.lyricsflow.core.LyricsFlowApp
import com.example.lyricsflow.ui.FlowApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FlowApp((application as LyricsFlowApp).graph)
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(15000)
                    (application as LyricsFlowApp).graph.playback.refresh()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as LyricsFlowApp).graph.playback.connect()
    }
}
