package com.macrorecorder.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.abs

class MacroAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_PLAY = "com.macrorecorder.PLAY"
        const val ACTION_STOP = "com.macrorecorder.STOP"
        const val EXTRA_ACTIONS = "actions"
        const val EXTRA_REPEAT = "repeat"

        // Called by OverlayService to start/stop recording mode
        var isRecording = false

        var instance: MacroAccessibilityService? = null
        var isPlaying = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private var repeatCount = 1
    private var currentRepeat = 0
    private var actions: List<MacroAction> = emptyList()
    private val gson = Gson()

    // Touch tracking for recording
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastEventTime = 0L
    private val recordedActions = mutableListOf<MacroAction>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PLAY -> {
                    val json = intent.getStringExtra(EXTRA_ACTIONS) ?: return
                    val type = object : TypeToken<List<MacroAction>>() {}.type
                    actions = gson.fromJson(json, type)
                    repeatCount = intent.getIntExtra(EXTRA_REPEAT, 1)
                    currentRepeat = 0
                    isPlaying = true
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
                    // OverlayService 向我們拿已錄製的動作
                    val json = gson.toJson(recordedActions)
                    sendBroadcast(Intent(ACTION_RECORDED_RESULT).apply {
                        putExtra(EXTRA_ACTIONS, json)
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

    // OverlayService 用這些 action 跟我們溝通錄製結果
    companion object {
        const val ACTION_GET_RECORDED = "com.macrorecorder.GET_RECORDED"
        const val ACTION_RECORDED_RESULT = "com.macrorecorder.RECORDED_RESULT"
        const val ACTION_CLEAR_RECORDED = "com.macrorecorder.CLEAR_RECORDED"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY)
            addAction(ACTION_STOP)
            addAction(ACTION_GET_RECORDED)
            addAction(ACTION_CLEAR_RECORDED)
        }
        registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /**
     * 這是關鍵：用 onTouchEvent 來錄製觸控。
     * 只有在 isRecording = true 時才記錄。
     * 回傳 false 讓事件繼續往底下的 app 傳遞 —— 這樣使用者的操作就能正常被手機接收。
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isRecording) return false

        val now = SystemClock.uptimeMillis()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                downTime = now
            }
            MotionEvent.ACTION_UP -> {
                val delay = if (recordedActions.isEmpty()) 0L else now - lastEventTime
                val dx = abs(event.rawX - downX)
                val dy = abs(event.rawY - downY)
                val duration = now - downTime

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
                sendBroadcast(Intent("com.macrorecorder.RECORD_COUNT").apply {
                    putExtra("count", recordedActions.size)
                })
            }
        }
        // 重要：回傳 false，讓事件穿透繼續傳到底下的 app
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try { unregisterReceiver(receiver) } catch (e: Exception) {}
    }

    private fun playNext() {
        if (!isPlaying) return
        if (actions.isEmpty()) return

        var totalDelay = 0L
        for ((_, action) in actions.withIndex()) {
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
            val status = Intent("com.macrorecorder.STATUS").apply {
                putExtra("status", "progress")
                putExtra("current", currentRepeat)
                putExtra("total", repeatCount)
            }
            sendBroadcast(status)

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
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                    .build()
                dispatchGesture(gesture, null, null)
            }
            ActionType.LONG_PRESS -> {
                path.moveTo(action.x, action.y)
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, action.duration.coerceAtLeast(500)))
                    .build()
                dispatchGesture(gesture, null, null)
            }
            ActionType.SWIPE -> {
                path.moveTo(action.x, action.y)
                path.lineTo(action.x2, action.y2)
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, action.duration.coerceAtLeast(100)))
                    .build()
                dispatchGesture(gesture, null, null)
            }
        }
    }
}
