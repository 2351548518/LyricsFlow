package com.example.lyricsflow.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics

/** Observe taps without claiming the down event or interfering with the lyric list's scroll. */
@Composable
internal fun Modifier.lyricViewportTap(fullscreen: Boolean, onTap: () -> Unit): Modifier {
    val tap by rememberUpdatedState(onTap)
    return pointerInput(fullscreen) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var moved = false
            var pressed = true
            while (pressed) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                    event.changes.count { it.pressed } > 1) moved = true
                // Let nested controls handle the release first (e.g. "回到当前歌词").
                val afterChildren = awaitPointerEvent(PointerEventPass.Main)
                val released = afterChildren.changes.firstOrNull { it.id == down.id } ?: break
                pressed = released.pressed
                if (!pressed && !moved && !released.isConsumed) tap()
            }
        }
    }.semantics {
        onClick(if (fullscreen) "退出全屏歌词" else "全屏显示歌词") {
            tap()
            true
        }
    }
}
