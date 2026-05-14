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
    private var recordedCount = 0  // 本地記錄錄製數量，供 UI 顯示

    private lateinit var btnRecord: Button
    private lateinit var btnPlay: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Handler(Looper.getMainLooper()).post {
                when (intent.action) {

                    MacroAccessibilityService.ACTION_RECORD_COUNT -> {
                        recordedCount = intent.getIntExtra(MacroAccessibilityService.EXTRA_COUNT, 0)
                        tvStatus.text = "錄製中...已記錄 $recordedCount 個"
                    }

                    MacroAccessibilityService.ACTION_RECORD_STOPPED -> {
                        // 錄製完成通知（只更新顯示，不觸發播放）
                        recordedCount = intent.getIntExtra(MacroAccessibilityService.EXTRA_COUNT, 0)
                        tvStatus.text = "已錄製 $recordedCount 個動作，可按播放"
                        updateUI()
                    }

                    MacroAccessibilityService.ACTION_PLAY_STATUS -> {
                        val status  = intent.getStringExtra(MacroAccessibilityService.EXTRA_STATUS)
                        val current = intent.getIntExtra(MacroAccessibilityService.EXTRA_CURRENT, 0)
                        val total   = intent.getIntExtra(MacroAccessibilityService.EXTRA_TOTAL, 0)
                        when (status) {
                            "progress" -> tvStatus.text = "播放中 $current/$total"
                            "done"     -> {
                                isPlaying = false
                                updateUI()
                                tvStatus.text = "播放完成！"
                            }
                            "stopped"  -> {
                                isPlaying = false
                                updateUI()
                                tvStatus.text = "已停止"
                            }
                            "empty"    -> {
                                isPlaying = false
                                updateUI()
                                tvStatus.text = "沒有錄製的動作"
                            }
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
            addAction(MacroAccessibilityService.ACTION_RECORD_COUNT)
            addAction(MacroAccessibilityService.ACTION_RECORD_STOPPED)
            addAction(MacroAccessibilityService.ACTION_PLAY_STATUS)
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

        // 拖曳移動面板
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
        tvStatus.text = "準備就緒"
    }

    // ── 錄製控制 ──────────────────────────────────────────────────────────────

    private fun startRecording() {
        isRecording = true
        recordedCount = 0
        sendBroadcast(Intent(MacroAccessibilityService.ACTION_START_RECORDING))
        updateUI()
        tvStatus.text = "錄製中...點擊螢幕操作"
    }

    private fun stopRecording() {
        isRecording = false
        // 通知 AccessibilityService 停止錄製；結果透過 ACTION_RECORD_STOPPED 回傳
        sendBroadcast(Intent(MacroAccessibilityService.ACTION_STOP_RECORDING))
        updateUI()
        tvStatus.text = "停止錄製中..."
    }

    // ── 播放控制 ──────────────────────────────────────────────────────────────

    private fun startPlayingFromRecorded() {
        if (isPlaying || isRecording) return
        isPlaying = true
        updateUI()
        tvStatus.text = "播放中..."
        // 直接通知 AccessibilityService 播放已錄製的動作，播一次
        sendBroadcast(Intent(MacroAccessibilityService.ACTION_PLAY_RECORDED).apply {
            putExtra(MacroAccessibilityService.EXTRA_REPEAT, 1)
        })
    }

    private fun stopPlaying() {
        isPlaying = false
        sendBroadcast(Intent(MacroAccessibilityService.ACTION_STOP))
        updateUI()
    }

    private fun updateUI() {
        btnRecord.text = if (isRecording) "⏹ 停止錄製" else "🔴 開始錄製"
        btnRecord.setBackgroundColor(if (isRecording) Color.RED else Color.DKGRAY)
        btnPlay.isEnabled = !isRecording && !isPlaying && recordedCount > 0
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
