package com.macrorecorder.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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
        const val ACTION_PLAY            = "com.macrorecorder.PLAY"
        const val ACTION_STOP            = "com.macrorecorder.STOP"
        const val ACTION_GET_RECORDED    = "com.macrorecorder.GET_RECORDED"
        const val ACTION_RECORDED_RESULT = "com.macrorecorder.RECORDED_RESULT"
        const val ACTION_CLEAR_RECORDED  = "com.macrorecorder.CLEAR_RECORDED"
        const val EXTRA_ACTIONS          = "actions"
        const val EXTRA_REPEAT           = "repeat"

        var instance: MacroAccessibilityService? = null
        var isPlaying   = false
        var isRecording = false
    }

    private val handler       = Handler(Looper.getMainLooper())
    private var repeatCount   = 1
    private var currentRepeat = 0
    private var actions: List<MacroAction> = emptyList()
    private val gson = Gson()

    // 錄製用
    private val recordedActions = mutableListOf<MacroAction>()
    private var lastEventTime   = 0L
    private var downX = 0f;  private var downY = 0f
    private var downTime = 0L

    // ── BroadcastReceiver ────────────────────────────────────────────────────

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
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

        // 動態啟用 onMotionEvent：需要 FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        serviceInfo = serviceInfo.also { info ->
            info.flags = info.flags or
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        }

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

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    // ── 核心：onMotionEvent（Android 12+ / API 31+）──────────────────────────
    //
    // 這個 callback 在系統層監聽，不需要任何 overlay。
    // 事件同樣會繼續傳遞給底下的 app，完全不攔截。
    // 只有在 isRecording = true 時才記錄。

    override fun onMotionEvent(event: MotionEvent) {
        if (!isRecording) return

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
