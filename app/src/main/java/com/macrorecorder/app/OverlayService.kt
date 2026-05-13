package com.macrorecorder.app

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "macro_overlay_channel"
        var isRunning = false
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private var isRecording = false
    private var isPlaying   = false

    private lateinit var btnRecord: Button
    private lateinit var btnPlay: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView

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
                        if (count > 0 && isPlaying) {
                            sendBroadcast(Intent(MacroAccessibilityService.ACTION_PLAY).apply {
                                putExtra(MacroAccessibilityService.EXTRA_ACTIONS, json)
                                putExtra(MacroAccessibilityService.EXTRA_REPEAT, 0)
                            })
                            tvStatus.text = "播放中..."
                        } else if (!isPlaying) {
                            // stopRecording 後拿到數量，只更新顯示
                            tvStatus.text = "已錄製 $count 個動作"
                            updateUI()
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

        // 拖曳移動浮動面板
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

    // ── 錄製控制 ──────────────────────────────────────────────────────────────
    // 不再有任何透明 overlay！
    // 錄製完全由 MacroAccessibilityService.onMotionEvent() 處理。

    private fun startRecording() {
        isRecording = true
        MacroAccessibilityService.isRecording = true
        sendBroadcast(Intent(MacroAccessibilityService.ACTION_CLEAR_RECORDED))
        updateUI()
        tvStatus.text = "錄製中...點擊螢幕操作"
    }

    private fun stopRecording() {
        isRecording = false
        MacroAccessibilityService.isRecording = false
        updateUI()
        sendBroadcast(Intent(MacroAccessibilityService.ACTION_GET_RECORDED))
    }

    private fun startPlayingFromRecorded() {
        if (isPlaying) return
        isPlaying = true
        updateUI()
        tvStatus.text = "準備播放..."
        sendBroadcast(Intent(MacroAccessibilityService.ACTION_GET_RECORDED))
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
        try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?) = null
}
