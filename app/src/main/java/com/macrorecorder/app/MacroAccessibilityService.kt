package com.macrorecorder.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.*
import android.view.accessibility.AccessibilityEvent
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.abs

class MacroAccessibilityService : AccessibilityService() {

    companion object {
        // UI → Service（這些走 explicit broadcast，保留給 OverlayService 發命令用）
        const val ACTION_START_RECORDING = "com.macrorecorder.START_RECORDING"
        const val ACTION_STOP_RECORDING  = "com.macrorecorder.STOP_RECORDING"
        const val ACTION_PLAY_RECORDED   = "com.macrorecorder.PLAY_RECORDED"
        const val ACTION_STOP            = "com.macrorecorder.STOP"

        var instance: MacroAccessibilityService? = null
        var isPlaying   = false
        var isRecording = false

        // OverlayService 直接讀這個，不需要廣播
        val recordedActions = mutableListOf<MacroAction>()
    }

    private val handler       = Handler(Looper.getMainLooper())
    private var repeatCount   = 1
    private var currentRepeat = 0
    private var playActions: List<MacroAction> = emptyList()
    private val gson = Gson()

    // 錄製計時
    private var lastEventTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    // 錄製用 trusted overlay
    private var touchOverlayView: View? = null
    private var windowManager: WindowManager? = null

    // ── BroadcastReceiver（接收 OverlayService 的命令）────────────────────────

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {

                ACTION_START_RECORDING -> {
                    recordedActions.clear()
                    lastEventTime = 0L
                    isRecording = true
                    addTouchOverlay()
                }

                ACTION_STOP_RECORDING -> {
                    isRecording = false
                    removeTouchOverlay()
                    // 通知 OverlayService 更新 UI（explicit broadcast）
                    notifyOverlay("record_stopped", recordedActions.size)
                }

                ACTION_PLAY_RECORDED -> {
                    if (recordedActions.isEmpty()) {
                        notifyOverlay("play_status", 0, "empty")
                        return
                    }
                    val repeat = intent.getIntExtra("repeat", 1)
                    playActions   = recordedActions.toList()
                    repeatCount   = if (repeat <= 0) 1 else repeat
                    currentRepeat = 0
                    isPlaying     = true
                    playNext()
                }

                ACTION_STOP -> {
                    isPlaying = false
                    handler.removeCallbacksAndMessages(null)
                    notifyOverlay("play_status", 0, "stopped")
                }
            }
        }
    }

    // explicit broadcast 給 OverlayService
    private fun notifyOverlay(type: String, count: Int = 0, status: String = "", current: Int = 0, total: Int = 0) {
        val intent = Intent("com.macrorecorder.OVERLAY_EVENT").apply {
            setPackage(packageName)   // explicit → 確保送達，不被 RECEIVER_NOT_EXPORTED 擋住
            putExtra("type", type)
            putExtra("count", count)
            putExtra("status", status)
            putExtra("current", current)
            putExtra("total", total)
        }
        sendBroadcast(intent)
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val filter = IntentFilter().apply {
            addAction(ACTION_START_RECORDING)
            addAction(ACTION_STOP_RECORDING)
            addAction(ACTION_PLAY_RECORDED)
            addAction(ACTION_STOP)
        }
        // OverlayService 發給我們的命令用 explicit，所以這裡用 RECEIVER_NOT_EXPORTED 沒問題
        registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        recordedActions.clear()
        removeTouchOverlay()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    // ── TYPE_ACCESSIBILITY_OVERLAY 觸控錄製視窗 ───────────────────────────────

    private fun addTouchOverlay() {
        if (touchOverlayView != null) return
        val wm = windowManager ?: return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val view = View(this)
        view.alpha = 0f
        view.setOnTouchListener { _, event ->
            if (isRecording) handleRecordTouch(event)
            false  // 穿透，不消費事件
        }

        wm.addView(view, params)
        touchOverlayView = view
    }

    private fun removeTouchOverlay() {
        touchOverlayView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        touchOverlayView = null
    }

    private fun handleRecordTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX    = event.rawX
                downY    = event.rawY
                downTime = SystemClock.uptimeMillis()
            }
            MotionEvent.ACTION_UP -> {
                val now      = SystemClock.uptimeMillis()
                val dx       = abs(event.rawX - downX)
                val dy       = abs(event.rawY - downY)
                val duration = now - downTime
                val delay    = if (recordedActions.isEmpty()) 0L else now - lastEventTime

                val action = when {
                    dx > 30 || dy > 30 -> MacroAction(
                        ActionType.SWIPE, downX, downY, event.rawX, event.rawY, duration, delay
                    )
                    duration > 400 -> MacroAction(
                        ActionType.LONG_PRESS, downX, downY, duration = duration, delay = delay
                    )
                    else -> MacroAction(ActionType.TAP, downX, downY, delay = delay)
                }
                recordedActions.add(action)
                lastEventTime = now

                // 通知 OverlayService 更新計數
                notifyOverlay("record_count", recordedActions.size)
            }
        }
    }

    // ── 播放 ─────────────────────────────────────────────────────────────────

    private fun playNext() {
        if (!isPlaying) return
        if (playActions.isEmpty()) return

        var totalDelay = 0L
        for (action in playActions) {
            val delay = totalDelay + action.delay
            totalDelay = delay + action.duration + 50L
            handler.postDelayed({
                if (!isPlaying) return@postDelayed
                performMacroAction(action)
            }, delay)
        }

        handler.postDelayed({
            if (!isPlaying) return@postDelayed
            currentRepeat++
            notifyOverlay("play_status", current = currentRepeat, total = repeatCount, status = "progress")
            if (currentRepeat < repeatCount) {
                playNext()
            } else {
                isPlaying = false
                notifyOverlay("play_status", status = "done")
            }
        }, totalDelay + 300L)
    }

    private fun performMacroAction(action: MacroAction) {
        val path = Path()
        when (action.type) {
            ActionType.TAP -> {
                path.moveTo(action.x, action.y)
                dispatchGesture(
                    GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                        .build(), null, null
                )
            }
            ActionType.LONG_PRESS -> {
                path.moveTo(action.x, action.y)
                dispatchGesture(
                    GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, action.duration.coerceAtLeast(500)))
                        .build(), null, null
                )
            }
            ActionType.SWIPE -> {
                path.moveTo(action.x, action.y)
                path.lineTo(action.x2, action.y2)
                dispatchGesture(
                    GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, action.duration.coerceAtLeast(100)))
                        .build(), null, null
                )
            }
        }
    }
}
