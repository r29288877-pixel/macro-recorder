package com.macrorecorder.app

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "macro_overlay_channel"
        var isRunning = false
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private var isRecording = false
    private var isPlaying   = false
    private val gson = Gson()

    private lateinit var btnRecord: Button
    private lateinit var btnPlay: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView

    // ── 透明錄製 overlay（用來攔截觸控，同時穿透給底下的 app）──────────────
    private var touchOverlay: View? = null

    // Touch tracking
    private var downX    = 0f
    private var downY    = 0f
    private var downTime = 0L

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Handler(Looper.getMainLooper()).post {
                when (intent.action) {
                    "com.macrorecorder.STATUS" -> {
                        val status  = intent.getStringExtra("status")
                        val current = intent.getIntExtra("current", 0)
                        val total   = intent.getIntExtra("total", 0)
                        when (status) {
                            "progress" -> tvStatus.text = "播放中 $current/$total"
                            "done"     -> { isPlaying = false; updateUI(); tvStatus.text = "完成！" }
                            "stopped"  -> { isPlaying = false; updateUI(); tvStatus.text = "已停止" }
                        }
                    }
                    "com.macrorecorder.RECORD_COUNT" -> {
                        val count = intent.getIntExtra("count", 0)
                        tvStatus.text = "錄製中...已記錄 $count 個"
                    }
                    MacroAccessibilityService.ACTION_RECORDED_RESULT -> {
                        val json  = intent.getStringExtra(MacroAccessibilityService.EXTRA_ACTIONS) ?: return@post
                        val count = intent.getIntExtra("count", 0)
                        if (count > 0) {
                            isPlaying = true
                            updateUI()
                            sendBroadcast(Intent(MacroAccessibilityService.ACTION_PLAY).apply {
                                putExtra(MacroAccessibilityService.EXTRA_ACTIONS, json)
                                putExtra(MacroAccessibilityService.EXTRA_REPEAT, 0)
                            })
                            tvStatus.text = "播放中..."
                        } else {
                            tvStatus.text = "已停止錄製（0 個動作）"
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(1, buildNotification())
        setupOverlay()

        val filter = IntentFilter().apply {
            addAction("com.macrorecorder.STATUS")
            addAction("com.macrorecorder.RECORD_COUNT")
            addAction(MacroAccessibilityService.ACTION_RECORDED_RESULT)
        }
        registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Macro Recorder", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Macro Recorder 執行中")
            .setContentText("浮動控制面板已啟動")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

    // ── 浮動控制面板 ──────────────────────────────────────────────────────────

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 200
        }

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)
        btnRecord = overlayView.findViewById(R.id.btnRecord)
        btnPlay   = overlayView.findViewById(R.id.btnPlay)
        btnStop   = overlayView.findViewById(R.id.btnStop)
        tvStatus  = overlayView.findViewById(R.id.tvStatus)

        // 拖曳移動
        var dX = 0f; var dY = 0f
        overlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { dX = params.x - event.rawX; dY = params.y - event.rawY; false }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (event.rawX + dX).toInt()
                    params.y = (event.rawY + dY).toInt()
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                else -> false
            }
        }

        btnRecord.setOnClickListener { if (!isRecording) startRecording() else stopRecording() }
        btnPlay.setOnClickListener   { startPlayingFromRecorded() }
        btnStop.setOnClickListener   { stopPlaying() }

        windowManager.addView(overlayView, params)
        updateUI()
    }

    // ── 透明 pass-through 錄製 overlay ────────────────────────────────────────
    //
    // 關鍵旗標組合：
    //   FLAG_NOT_FOCUSABLE         — 不搶輸入焦點
    //   FLAG_WATCH_OUTSIDE_TOUCH   — 能接收到自身視窗範圍外的觸控
    //
    // onTouchListener 回傳 false → 系統繼續把事件分發給底下的視窗（穿透效果）
    // 我們只在 ACTION_DOWN / ACTION_UP 做記錄，不消費事件。

    private fun addTouchOverlay() {
        val tp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSPARENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val view = View(this)
        view.setOnTouchListener { _, event ->
            handleRecordTouch(event)
            false   // ← 關鍵：回傳 false，讓事件穿透到底下的 app
        }
        windowManager.addView(view, tp)
        touchOverlay = view
    }

    private fun removeTouchOverlay() {
        touchOverlay?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        touchOverlay = null
    }

    private fun handleRecordTouch(event: MotionEvent) {
        val svc = MacroAccessibilityService.instance ?: return
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY
                downTime = SystemClock.uptimeMillis()
            }
            MotionEvent.ACTION_UP -> {
                val now      = SystemClock.uptimeMillis()
                val dx       = abs(event.rawX - downX)
                val dy       = abs(event.rawY - downY)
                val duration = now - downTime
                when {
                    dx > 30 || dy > 30 ->
                        svc.recordTouch(ActionType.SWIPE, downX, downY, event.rawX, event.rawY, duration)
                    duration > 400 ->
                        svc.recordTouch(ActionType.LONG_PRESS, downX, downY, duration = duration)
                    else ->
                        svc.recordTouch(ActionType.TAP, downX, downY)
                }
            }
        }
    }

    // ── 錄製控制 ──────────────────────────────────────────────────────────────

    private fun startRecording() {
        isRecording = true
        MacroAccessibilityService.isRecording = true
        // 先清空之前的錄製
        sendBroadcast(Intent(MacroAccessibilityService.ACTION_CLEAR_RECORDED))
        addTouchOverlay()
        updateUI()
        tvStatus.text = "錄製中...點擊螢幕操作"
    }

    private fun stopRecording() {
        isRecording = false
        MacroAccessibilityService.isRecording = false
        removeTouchOverlay()
        updateUI()
        // 向 AccessibilityService 拿已錄製的清單，只更新顯示計數
        sendBroadcast(Intent(MacroAccessibilityService.ACTION_GET_RECORDED))
        tvStatus.text = "停止錄製中..."
    }

    private fun startPlayingFromRecorded() {
        if (isPlaying) return
        sendBroadcast(Intent(MacroAccessibilityService.ACTION_GET_RECORDED))
        // 結果回來後在 statusReceiver 的 ACTION_RECORDED_RESULT 裡處理
    }

    private fun stopPlaying() {
        isPlaying = false
        sendBroadcast(Intent(MacroAccessibilityService.ACTION_STOP))
        updateUI()
    }

    private fun updateUI() {
        btnRecord.text = if (isRecording) "⏹ 停止錄製" else "🔴 開始錄製"
        btnRecord.setBackgroundColor(if (isRecording) Color.RED else Color.DKGRAY)
        btnPlay.isEnabled = !isRecording && !isPlaying
        btnStop.isEnabled = isPlaying
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        removeTouchOverlay()
        try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?) = null
}
