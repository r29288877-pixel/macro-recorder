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
import android.view.accessibility.AccessibilityEvent
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class MacroAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_PLAY          = "com.macrorecorder.PLAY"
        const val ACTION_STOP          = "com.macrorecorder.STOP"
        const val ACTION_GET_RECORDED  = "com.macrorecorder.GET_RECORDED"
        const val ACTION_RECORDED_RESULT = "com.macrorecorder.RECORDED_RESULT"
        const val ACTION_CLEAR_RECORDED = "com.macrorecorder.CLEAR_RECORDED"
        const val EXTRA_ACTIONS        = "actions"
        const val EXTRA_REPEAT         = "repeat"

        var instance: MacroAccessibilityService? = null
        var isPlaying   = false
        var isRecording = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private var repeatCount    = 1
    private var currentRepeat  = 0
    private var actions: List<MacroAction> = emptyList()
    private val gson = Gson()

    // 錄製用的資料
    private val recordedActions = mutableListOf<MacroAction>()
    private var lastEventTime   = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PLAY -> {
                    val json = intent.getStringExtra(EXTRA_ACTIONS) ?: return
                    val type = object : TypeToken<List<MacroAction>>() {}.type
                    actions      = gson.fromJson(json, type)
                    repeatCount  = intent.getIntExtra(EXTRA_REPEAT, 1)
                    currentRepeat = 0
                    isPlaying    = true
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
                    // OverlayService 要拿錄製結果
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

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try { unregisterReceiver(receiver) } catch (e: Exception) {}
    }

    /**
     * 提供給 OverlayService 呼叫，用來記錄一個觸控動作。
     * OverlayService 的透明 overlay 收到 touch 後呼叫這個方法，
     * 然後把事件回傳 false 讓底下的 app 也能收到。
     */
    fun recordTouch(
        type: ActionType,
        x: Float, y: Float,
        x2: Float = 0f, y2: Float = 0f,
        duration: Long = 0L
    ) {
        if (!isRecording) return
        val now = SystemClock.uptimeMillis()
        val delay = if (recordedActions.isEmpty()) 0L else now - lastEventTime
        recordedActions.add(MacroAction(type, x, y, x2, y2, duration, delay))
        lastEventTime = now

        // 通知 OverlayService 更新計數顯示
        val ctx = this as Context
        ctx.sendBroadcast(Intent("com.macrorecorder.RECORD_COUNT").apply {
            putExtra("count", recordedActions.size)
        })
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
