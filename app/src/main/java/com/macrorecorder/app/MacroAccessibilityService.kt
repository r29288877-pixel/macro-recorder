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
        const val ACTION_START_RECORDING = "com.macrorecorder.START_RECORDING"
        const val ACTION_STOP_RECORDING  = "com.macrorecorder.STOP_RECORDING"
        const val ACTION_PLAY_RECORDED   = "com.macrorecorder.PLAY_RECORDED"   // 播放已錄製的動作
        const val ACTION_PLAY            = "com.macrorecorder.PLAY"             // 播放指定 json
        const val ACTION_STOP            = "com.macrorecorder.STOP"
        const val ACTION_RECORD_COUNT    = "com.macrorecorder.RECORD_COUNT"     // 通知計數更新
        const val ACTION_RECORD_STOPPED  = "com.macrorecorder.RECORD_STOPPED"   // 錄製結束通知
        const val ACTION_PLAY_STATUS     = "com.macrorecorder.PLAY_STATUS"      // 播放狀態通知
        const val EXTRA_ACTIONS          = "actions"
        const val EXTRA_REPEAT           = "repeat"
        const val EXTRA_COUNT            = "count"
        const val EXTRA_STATUS           = "status"
        const val EXTRA_CURRENT          = "current"
        const val EXTRA_TOTAL            = "total"

        var instance: MacroAccessibilityService? = null
        var isPlaying   = false
        var isRecording = false
    }

    private val handler       = Handler(Looper.getMainLooper())
    private var repeatCount   = 1
    private var currentRepeat = 0
    private var actions: List<MacroAction> = emptyList()
    private val gson = Gson()

    // 錄製資料
    private val recordedActions = mutableListOf<MacroAction>()
    private var lastEventTime   = 0L
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    // 錄製用 trusted overlay
    private var touchOverlayView: View? = null
    private var windowManager: WindowManager? = null

    // ── BroadcastReceiver ────────────────────────────────────────────────────

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
                    // 通知 OverlayService 錄製已完成，附上計數
                    sendBroadcast(Intent(ACTION_RECORD_STOPPED).apply {
                        putExtra(EXTRA_COUNT, recordedActions.size)
                    })
                }

                ACTION_PLAY_RECORDED -> {
                    // 直接播放已錄製的動作，不需要 GET/RESULT 的來回
                    if (recordedActions.isEmpty()) {
                        sendBroadcast(Intent(ACTION_PLAY_STATUS).apply {
                            putExtra(EXTRA_STATUS, "empty")
                        })
                        return
                    }
                    val repeat = intent.getIntExtra(EXTRA_REPEAT, 1)
                    actions       = recordedActions.toList()
                    repeatCount   = if (repeat <= 0) 1 else repeat
                    currentRepeat = 0
                    isPlaying     = true
                    playNext()
                }

                ACTION_PLAY -> {
                    // 播放外部傳入的 json（保留相容性）
                    val json = intent.getStringExtra(EXTRA_ACTIONS) ?: return
                    val type = object : TypeToken<List<MacroAction>>() {}.type
                    actions       = gson.fromJson(json, type)
                    repeatCount   = intent.getIntExtra(EXTRA_REPEAT, 1).let { if (it <= 0) 1 else it }
                    currentRepeat = 0
                    isPlaying     = true
                    playNext()
                }

                ACTION_STOP -> {
                    isPlaying = false
                    handler.removeCallbacksAndMessages(null)
                    sendBroadcast(Intent(ACTION_PLAY_STATUS).apply {
                        putExtra(EXTRA_STATUS, "stopped")
                    })
                }
            }
        }
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
            addAction(ACTION_PLAY)
            addAction(ACTION_STOP)
        }
        registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        removeTouchOverlay()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    // ── TYPE_ACCESSIBILITY_OVERLAY 觸控錄製視窗 ───────────────────────────────
    // trusted window，觸控事件穿透到底下的 app，不需要 TOUCH_EXPLORATION flag

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
            false  // 不消費事件，讓底下的 app 正常收到
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

                sendBroadcast(Intent(ACTION_RECORD_COUNT).apply {
                    putExtra(EXTRA_COUNT, recordedActions.size)
                })
            }
        }
    }

    // ── 播放 ─────────────────────────────────────────────────────────────────

    private fun playNext() {
        if (!isPlaying) return
        if (actions.isEmpty()) return

        var totalDelay = 0L
        for (action in actions) {
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
            sendBroadcast(Intent(ACTION_PLAY_STATUS).apply {
                putExtra(EXTRA_STATUS, "progress")
                putExtra(EXTRA_CURRENT, currentRepeat)
                putExtra(EXTRA_TOTAL, repeatCount)
            })
            if (currentRepeat < repeatCount) {
                playNext()
            } else {
                isPlaying = false
                sendBroadcast(Intent(ACTION_PLAY_STATUS).apply {
                    putExtra(EXTRA_STATUS, "done")
                })
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
