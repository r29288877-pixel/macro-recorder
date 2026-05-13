package com.macrorecorder.app

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.view.MotionEvent
import android.widget.*
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        const val ACTION_START_OVERLAY = "com.macrorecorder.START_OVERLAY"
        const val CHANNEL_ID = "macro_overlay_channel"
        var isRunning = false
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private var isRecording = false
    private var isPlaying = false
    private val recordedActions = mutableListOf<MacroAction>()
    private var lastEventTime = 0L
    private val gson = Gson()

    // Touch tracking for recording
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    private lateinit var btnRecord: Button
    private lateinit var btnPlay: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra("status")
            val current = intent.getIntExtra("current", 0)
            val total = intent.getIntExtra("total", 0)
            Handler(Looper.getMainLooper()).post {
                when (status) {
                    "progress" -> tvStatus.text = "播放中 $current/$total"
                    "done" -> {
                        isPlaying = false
                        updateUI()
                        tvStatus.text = "完成！共 ${recordedActions.size} 個動作"
                    }
                    "stopped" -> {
                        isPlaying = false
                        updateUI()
                        tvStatus.text = "已停止"
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
        registerReceiver(statusReceiver, IntentFilter("com.macrorecorder.STATUS"), RECEIVER_NOT_EXPORTED)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Macro Recorder", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Macro Recorder 執行中")
            .setContentText("浮動控制面板已啟動")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }

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
            x = 0
            y = 200
        }

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)
        btnRecord = overlayView.findViewById(R.id.btnRecord)
        btnPlay = overlayView.findViewById(R.id.btnPlay)
        btnStop = overlayView.findViewById(R.id.btnStop)
        tvStatus = overlayView.findViewById(R.id.tvStatus)

        // Drag to move
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

        btnRecord.setOnClickListener {
            if (!isRecording) startRecording() else stopRecording()
        }

        btnPlay.setOnClickListener {
            if (recordedActions.isNotEmpty()) startPlaying()
        }

        btnStop.setOnClickListener {
            stopPlaying()
        }

        windowManager.addView(overlayView, params)
        updateUI()
    }

    private fun startRecording() {
        recordedActions.clear()
        isRecording = true
        lastEventTime = SystemClock.uptimeMillis()
        MacroAccessibilityService.isRecording = true

        // Setup touch interceptor overlay for recording
        setupTouchRecorder()
        updateUI()
        tvStatus.text = "錄製中...點擊螢幕"
    }

    private fun stopRecording() {
        isRecording = false
        MacroAccessibilityService.isRecording = false
        removeTouchRecorder()
        updateUI()
        tvStatus.text = "已錄製 ${recordedActions.size} 個動作"
    }

    private var touchOverlay: View? = null
    private var touchParams: WindowManager.LayoutParams? = null

    private fun setupTouchRecorder() {
        touchParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSPARENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        touchOverlay = View(this)
        touchOverlay!!.setOnTouchListener { _, event ->
            handleTouchEvent(event)
            false
        }
        windowManager.addView(touchOverlay, touchParams)
    }

    private fun handleTouchEvent(event: MotionEvent) {
        val now = SystemClock.uptimeMillis()
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY; downTime = now
            }
            MotionEvent.ACTION_UP -> {
                val delay = if (recordedActions.isEmpty()) 0L else now - lastEventTime
                val dx = abs(event.rawX - downX); val dy = abs(event.rawY - downY)
                val duration = now - downTime
                val action = when {
                    dx > 30 || dy > 30 -> MacroAction(ActionType.SWIPE, downX, downY, event.rawX, event.rawY, duration, delay)
                    duration > 400 -> MacroAction(ActionType.LONG_PRESS, downX, downY, duration = duration, delay = delay)
                    else -> MacroAction(ActionType.TAP, downX, downY, delay = delay)
                }
                recordedActions.add(action)
                lastEventTime = now
                tvStatus.text = "錄製中...已記錄 ${recordedActions.size} 個"
            }
        }
    }

    private fun removeTouchRecorder() {
        touchOverlay?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        touchOverlay = null
    }

    private fun startPlaying() {
        if (recordedActions.isEmpty()) return
        isPlaying = true
        updateUI()

        // Ask for repeat count via main activity
        val repeatCount = 0 // 0 = infinite; change via settings
        val intent = Intent(MacroAccessibilityService.ACTION_PLAY).apply {
            putExtra(MacroAccessibilityService.EXTRA_ACTIONS, gson.toJson(recordedActions))
            putExtra(MacroAccessibilityService.EXTRA_REPEAT, repeatCount)
        }
        sendBroadcast(intent)
        tvStatus.text = "播放中..."
    }

    private fun stopPlaying() {
        isPlaying = false
        sendBroadcast(Intent(MacroAccessibilityService.ACTION_STOP))
    }

    private fun updateUI() {
        btnRecord.text = if (isRecording) "⏹ 停止錄製" else "🔴 開始錄製"
        btnRecord.setBackgroundColor(if (isRecording) Color.RED else Color.DKGRAY)
        btnPlay.isEnabled = recordedActions.isNotEmpty() && !isRecording && !isPlaying
        btnStop.isEnabled = isPlaying
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try { windowManager.removeView(overlayView) } catch (e: Exception) {}
        removeTouchRecorder()
        try { unregisterReceiver(statusReceiver) } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?) = null
}
