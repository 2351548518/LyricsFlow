package com.example.lyricsflow.overlay

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.example.lyricsflow.MainActivity
import com.example.lyricsflow.R
import com.example.lyricsflow.core.*
import kotlinx.coroutines.*

class LyricsOverlayService : Service() {
    companion object {
        const val STOP = "stop"
        const val LOCK = "lock"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wm: WindowManager
    private lateinit var graph: AppGraph
    private var panel: LinearLayout? = null
    private var text: TextView? = null
    private var translation: TextView? = null
    private var handle: TextView? = null
    private var locked = false
    private var pausedAt = 0L
    private var styleRaw = ""
    private var theme: ThemeSpec? = null
    private var rendered = ""
    private lateinit var params: WindowManager.LayoutParams

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        graph = (application as LyricsFlowApp).graph
        wm = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel("overlay", "悬浮歌词", NotificationManager.IMPORTANCE_LOW)
                )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            graph.message("请先授权显示在其他应用上层")
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == LOCK) {
            locked = !locked
            updateLock()
            graph.overlay(true, locked)
            notifyState()
            return START_NOT_STICKY
        }
        startForeground(41, notification())
        if (panel == null) {
            createWindow()
            graph.overlay(true, false)
            scope.launch {
                while (isActive) {
                    render()
                    delay(80)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun notification(): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        fun action(a: String, id: Int) =
            PendingIntent.getService(
                this,
                id,
                Intent(this, LyricsOverlayService::class.java).setAction(a),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, "overlay")
            .setSmallIcon(R.drawable.ic_flow)
            .setContentTitle("LyricsFlow 悬浮歌词")
            .setContentText(if (locked) "已锁定穿透 · 点击解锁后调整位置" else "拖动标题调整位置，拖动右下角调整大小")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, if (locked) "解锁" else "锁定穿透", action(LOCK, 1))
            .addAction(0, "关闭悬浮窗", action(STOP, 2))
            .build()
    }

    private fun notifyState() {
        getSystemService(NotificationManager::class.java).notify(41, notification())
    }

    private fun createWindow() {
        val prefs = getSharedPreferences("overlay-position", 0)
        val maxW = resources.displayMetrics.widthPixels
        val maxH = resources.displayMetrics.heightPixels
        params =
            WindowManager.LayoutParams(
                    prefs.getInt("w", dp(310)).coerceIn(dp(200), maxW),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    if (Build.VERSION.SDK_INT >= 26)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT,
                )
                .apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = prefs.getInt("x", dp(20)).coerceIn(0, (maxW - width).coerceAtLeast(0))
                    y = prefs.getInt("y", dp(180)).coerceIn(0, (maxH - dp(160)).coerceAtLeast(0))
                }
        panel =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background =
                    GradientDrawable().apply {
                        setColor(Color.rgb(41, 53, 46))
                        cornerRadius = dp(20).toFloat()
                    }
            }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        handle =
            TextView(this).apply {
                text = "⠿  LyricsFlow"
                textSize = 12f
                setTextColor(0xffc6d2c0.toInt())
                contentDescription = "拖动悬浮窗"
                minHeight = dp(32)
            }
        val lock =
            TextView(this).apply {
                text = "锁定"
                setTextColor(0xffe3e8dc.toInt())
                textSize = 12f
                setPadding(dp(8), dp(8), dp(8), dp(8))
                contentDescription = "锁定并穿透悬浮窗"
                setOnClickListener {
                    locked = true
                    updateLock()
                    graph.overlay(true, true)
                    notifyState()
                }
            }
        bar.addView(handle, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(lock)
        text =
            TextView(this).apply {
                setTextColor(Color.WHITE)
                setPadding(0, dp(4), 0, dp(4))
                maxLines = 4
            }
        translation =
            TextView(this).apply {
                setTextColor(0xffc6d2c0.toInt())
                maxLines = 3
            }
        val resize =
            TextView(this).apply {
                text = "◢"
                gravity = Gravity.END
                setTextColor(0xffc6d2c0.toInt())
                contentDescription = "调整悬浮窗大小"
                minHeight = dp(26)
            }
        panel!!.addView(bar)
        panel!!.addView(text)
        panel!!.addView(translation)
        panel!!.addView(resize)
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        handle!!.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX
                    sy = e.rawY
                    ox = params.x
                    oy = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x =
                        (ox + e.rawX - sx)
                            .toInt()
                            .coerceIn(0, (maxW - params.width).coerceAtLeast(0))
                    params.y =
                        (oy + e.rawY - sy)
                            .toInt()
                            .coerceIn(0, (maxH - (panel?.height ?: dp(120))).coerceAtLeast(0))
                    updateWindow()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    savePosition()
                    true
                }
                else -> false
            }
        }
        resize.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX
                    ox = params.width
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.width =
                        (ox + e.rawX - sx)
                            .toInt()
                            .coerceIn(dp(200), (maxW - params.x).coerceAtLeast(dp(200)))
                    updateWindow()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    savePosition()
                    true
                }
                else -> false
            }
        }
        try {
            wm.addView(panel, params)
        } catch (e: Exception) {
            graph.message("悬浮窗创建失败，请检查系统权限")
            stopSelf()
        }
    }

    private fun updateWindow() {
        panel?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    private fun savePosition() {
        getSharedPreferences("overlay-position", 0)
            .edit()
            .putInt("x", params.x)
            .putInt("y", params.y)
            .putInt("w", params.width)
            .apply()
    }

    private fun updateLock() {
        if (panel == null) return
        params.flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                if (locked) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0
        val maximum =
            if (Build.VERSION.SDK_INT >= 31)
                getSystemService(android.hardware.input.InputManager::class.java)
                    .maximumObscuringOpacityForTouch
            else .8f
        params.alpha = if (locked) minOf(.75f, maximum) else 1f
        updateWindow()
    }

    private fun render() {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        val s = graph.state.value
        val p = s.playback
        val now = SystemClock.elapsedRealtime()
        if (p.playing) pausedAt = 0 else if (pausedAt == 0L) pausedAt = now
        val hidden =
            !OverlayPolicy.visible(
                p.track != null,
                getSystemService(PowerManager::class.java).isInteractive,
                getSystemService(KeyguardManager::class.java).isKeyguardLocked,
                p.playing,
                pausedAt,
                now,
            )
        panel?.visibility = if (hidden) View.GONE else View.VISIBLE
        if (hidden) return
        val line =
            if (p.positionKnown)
                s.lyrics?.let { it.lines.getOrNull(it.active(p.at(now) - s.delay)) }
            else null
        if (styleRaw != s.overlayStyleRaw) {
            styleRaw = s.overlayStyleRaw
            theme =
                styleRaw
                    .takeIf { it.isNotBlank() }
                    ?.let { runCatching { ThemeSpec.from(it, "overlay") }.getOrNull() }
            rendered = ""
        }
        val position = p.at(now) - s.delay
        val content = line?.text ?: if (s.busy) "正在加载歌词…" else "暂无同步歌词"
        val signature =
            content +
                line?.translation +
                line?.words?.indexOfLast { position >= it.start } +
                s.prefs +
                styleRaw
        if (signature != rendered) {
            rendered = signature
            text?.apply {
                val color = theme?.original?.color?.toInt() ?: Color.WHITE
                setTextColor(color)
                textSize =
                    if (s.prefs.overlaySize == 22f) theme?.original?.size ?: 22f
                    else s.prefs.overlaySize
                typeface =
                    android.graphics.Typeface.create(
                        theme?.font ?: "sans-serif",
                        android.graphics.Typeface.BOLD,
                    )
                text =
                    if (
                        line != null &&
                            line.words.isNotEmpty() &&
                            line.words.joinToString("") { it.text } == content
                    ) {
                        android.text.SpannableString(content).apply {
                            var offset = 0
                            line.words.forEach { w ->
                                val end = offset + w.text.length
                                if (position < w.start)
                                    setSpan(
                                        android.text.style.ForegroundColorSpan(
                                            (color and 0x00ffffff) or 0x66000000
                                        ),
                                        offset,
                                        end,
                                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                                    )
                                offset = end
                            }
                        }
                    } else content
            }
            translation?.apply {
                text = line?.translation.orEmpty()
                setTextColor(theme?.translation?.color?.toInt() ?: 0xffc6d2c0.toInt())
                textSize =
                    theme?.translation?.size ?: (s.prefs.overlaySize * .65f).coerceAtLeast(12f)
                visibility =
                    if (s.prefs.overlayTranslation && !line?.translation.isNullOrBlank())
                        View.VISIBLE
                    else View.GONE
            }
            (panel?.background as? GradientDrawable)?.setColor(
                theme?.backgroundColor?.toInt() ?: Color.rgb(41, 53, 46)
            )
        }
        val motion =
            if (!s.prefs.reduced && line != null)
                theme?.motion(
                    (position - (line.start ?: position)).coerceAtLeast(0),
                    (line.end ?: position) - (line.start ?: 0),
                ) ?: Motion()
            else Motion()
        text?.apply {
            alpha = motion.opacity
            scaleX = motion.scale
            scaleY = motion.scale
            rotation = motion.rotation
            translationX = motion.x * width
            translationY = motion.y * height
        }
        handle?.visibility = if (locked) View.GONE else View.VISIBLE
        panel?.background?.alpha = (s.prefs.overlayAlpha * 255).toInt()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (panel != null) {
            val w = resources.displayMetrics.widthPixels
            val h = resources.displayMetrics.heightPixels
            params.width = params.width.coerceIn(dp(200), w)
            params.x = params.x.coerceIn(0, (w - params.width).coerceAtLeast(0))
            params.y = params.y.coerceIn(0, (h - (panel?.height ?: dp(120))).coerceAtLeast(0))
            updateWindow()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        graph.overlay(false, false)
        super.onDestroy()
    }
}
