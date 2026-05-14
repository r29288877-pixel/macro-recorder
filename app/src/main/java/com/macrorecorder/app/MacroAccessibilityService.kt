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
        const val ACTION_PLAY             = "com.macrorecorder.PLAY"
        const val ACTION_STOP             = "com.macrorecorder.STOP"
        const val ACTION_GET_RECORDED     = "com.macrorecorder.GET_RECORDED"
        const val ACTION_RECORDED_RESULT  = "com.macrorecorder.RECORDED_RESULT"
        const val ACTION_CLEAR_RECORDED   = "com.macrorecorder.CLEAR_RECORDED"
        const val ACTION_START_RECORDING  = "com.macrorecorder.START_RECORDING"
        const val ACTION_STOP_RECORDING   = "com.macrorecorder.STOP_RECORDING"
        const val EXTRA_ACTIONS           = "actions"
        const val EXTRA_REPEAT            = "repeat"

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

    // 用來錄製觸控的 TYPE_ACCESSIBILITY_OVERLAY 視窗
    // 這種視窗是 trusted，不會阻擋觸控事件傳到底下的 app
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
                    // 回傳結果給 OverlayService
                    sendBroadcast(Intent(ACTION_RECORDED_RESULT).apply {
                        putExtra(EXTRA_ACTIONS, gson.toJson(recordedActions))
                        putExtra("count", recordedActions.size)
                    })
                }
                ACTION_PLAY -> {
                    val json = intent.getStringExtra(EXTRA_ACTIONS) ?: return
                    val type = object : TypeToken<List<MacroAction>>() {}.type
                    actions       = gson.fromJson(json, type)
                    repeatCount   = intent.getIntExtra(EXTRA_REPEAT, 1)
                    currentRepeat = 0
                    isPlaying     = true
                    playNext()
                }
                ACTION_STOP -> {
                    isPlaying = false
                    handler.removeCallbacksAndMessages(null)
                    sendBroadcast(Intent("com.macrorecorder.STATUS").apply {
                        putExtra("status", "stopped")
                    })
                }
                ACTION_GET_RECORDED -> {
                    sendBroadcast(Intent(ACTION_RECORDED_RESULT).apply {
                        putExtra(EXTRA_ACTIONS, gson.toJson(recordedActions))
                        putExtra("count", recordedActions.size)
                    })
                }
                ACTION_CLEAR_RECORDED -> {
                    recordedActions.clear()
                    lastEventTime = 0L
                }
            }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // 取得 WindowManager，後面建立 accessibility overlay 用
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val filter = IntentFilter().apply {
            addAction(ACTION_START_RECORDING)
            addAction(ACTION_STOP_RECORDING)
            addAction(ACTION_PLAY)
            addAction(ACTION_STOP)
            addAction(ACTION_GET_RECORDED)
            addAction(ACTION_CLEAR_RECORDED)
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
    //
    // 關鍵：TYPE_ACCESSIBILITY_OVERLAY 是 trusted window。
    // 根據 Android 官方文件，trusted window 不受 Android 12+ 的觸控阻擋規則限制，
    // 觸控事件會同時傳到底下的 app，不會被攔截。
    // 完全不需要 FLAG_REQUEST_TOUCH_EXPLORATION_MODE（那個會鎖死畫面）。

    private fun addTouchOverlay() {
        if (touchOverlayView != null) return
        val wm = windowManager ?: return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            // TYPE_ACCESSIBILITY_OVERLAY：只能從 AccessibilityService context 建立
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // FLAG_NOT_FOCUSABLE：不搶輸入焦點，不加 FLAG_NOT_TOUCHABLE 才能收到觸控事件
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val view = View(this)
        // 設定 alpha 為 0，完全透明，視覺上不影響任何畫面
        view.alpha = 0f

        // setOnTouchListener 回傳 false：事件不被消費，穿透到底下的 app
        view.setOnTouchListener { _, event ->
            if (isRecording) handleRecordTouch(event)
            false // 回傳 false，讓事件繼續傳到底下的 app
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

                sendBroadcast(Intent("com.macrorecorder.RECORD_COUNT").apply {
                    putExtra("count", recordedActions.size)
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
            sendBroadcast(Intent("com.macrorecorder.STATUS").apply {
                putExtra("status", "progress")
                putExtra("current", currentRepeat)
                putExtra("total", repeatCount)
            })
            if (repeatCount == 0 || currentRepeat < repeatCount) {
                playNext()
            } else {
                isPlaying = false
                sendBroadcast(Intent("com.macrorecorder.STATUS").apply {
                    putExtra("status", "done")
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
