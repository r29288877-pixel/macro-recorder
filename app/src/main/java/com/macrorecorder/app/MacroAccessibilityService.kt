package com.macrorecorder.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class MacroAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_START_RECORDING = "com.macrorecorder.START_RECORDING"
        const val ACTION_STOP_RECORDING  = "com.macrorecorder.STOP_RECORDING"
        const val ACTION_PLAY_RECORDED   = "com.macrorecorder.PLAY_RECORDED"
        const val ACTION_STOP            = "com.macrorecorder.STOP"

        var instance: MacroAccessibilityService? = null
        var isPlaying   = false
        var isRecording = false

        val recordedActions = mutableListOf<MacroAction>()
    }

    private val handler       = Handler(Looper.getMainLooper())
    private var repeatCount   = 1
    private var currentRepeat = 0
    private var playActions: List<MacroAction> = emptyList()

    // 錄製用
    private var lastEventTime = 0L
    private var lastTouchDownX = 0f
    private var lastTouchDownY = 0f
    private var lastTouchDownTime = 0L
    private var pendingScrollStart: MacroAction? = null

    // ── BroadcastReceiver ────────────────────────────────────────────────────

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_START_RECORDING -> {
                    recordedActions.clear()
                    lastEventTime = 0L
                    isRecording = true
                    // 動態啟用 accessibility event 監聽
                    val info = serviceInfo
                    info.eventTypes = info.eventTypes or
                            AccessibilityEvent.TYPE_VIEW_CLICKED or
                            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
                            AccessibilityEvent.TYPE_VIEW_SCROLLED or
                            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START or
                            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END
                    serviceInfo = info
                    notifyOverlay("record_count", 0)
                }
                ACTION_STOP_RECORDING -> {
                    isRecording = false
                    notifyOverlay("record_stopped", recordedActions.size)
                }
                ACTION_PLAY_RECORDED -> {
                    if (recordedActions.isEmpty()) {
                        notifyOverlay("play_status", status = "empty")
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
                    notifyOverlay("play_status", status = "stopped")
                }
            }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val filter = IntentFilter().apply {
            addAction(ACTION_START_RECORDING)
            addAction(ACTION_STOP_RECORDING)
            addAction(ACTION_PLAY_RECORDED)
            addAction(ACTION_STOP)
        }
        registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        recordedActions.clear()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    // ── 核心：AccessibilityEvent 監聽觸控（不需要任何 overlay）────────────────
    //
    // 原理：
    // TYPE_VIEW_CLICKED      → 取得被點擊 view 的中心座標 → 記錄 TAP
    // TYPE_VIEW_LONG_CLICKED → 取得被長按 view 的中心座標 → 記錄 LONG_PRESS
    // TYPE_VIEW_SCROLLED     → 取得被滾動 view 的座標 → 記錄 SWIPE
    //
    // 這個方法完全不攔截任何觸控事件，畫面正常運作。

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRecording || event == null) return

        val now = SystemClock.uptimeMillis()

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val bounds = getNodeBounds(event.source) ?: return
                val x = (bounds.left + bounds.right) / 2f
                val y = (bounds.top + bounds.bottom) / 2f
                val delay = if (recordedActions.isEmpty()) 0L else now - lastEventTime
                recordedActions.add(MacroAction(ActionType.TAP, x, y, delay = delay))
                lastEventTime = now
                notifyOverlay("record_count", recordedActions.size)
            }

            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                val bounds = getNodeBounds(event.source) ?: return
                val x = (bounds.left + bounds.right) / 2f
                val y = (bounds.top + bounds.bottom) / 2f
                val delay = if (recordedActions.isEmpty()) 0L else now - lastEventTime
                recordedActions.add(MacroAction(ActionType.LONG_PRESS, x, y, duration = 600L, delay = delay))
                lastEventTime = now
                notifyOverlay("record_count", recordedActions.size)
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val bounds = getNodeBounds(event.source) ?: return
                val cx = (bounds.left + bounds.right) / 2f
                val cy = (bounds.top + bounds.bottom) / 2f

                // 根據滾動方向決定 swipe 方向
                val scrollDx = event.scrollDeltaX
                val scrollDy = event.scrollDeltaY

                val (x1, y1, x2, y2) = when {
                    scrollDy > 0 -> arrayOf(cx, cy - 200f, cx, cy + 200f) // 向下滾 → 往上滑
                    scrollDy < 0 -> arrayOf(cx, cy + 200f, cx, cy - 200f) // 向上滾 → 往下滑
                    scrollDx > 0 -> arrayOf(cx - 200f, cy, cx + 200f, cy) // 向右滾 → 往左滑
                    scrollDx < 0 -> arrayOf(cx + 200f, cy, cx - 200f, cy) // 向左滾 → 往右滑
                    else -> return
                }

                val delay = if (recordedActions.isEmpty()) 0L else now - lastEventTime
                recordedActions.add(MacroAction(ActionType.SWIPE, x1, y1, x2, y2, duration = 300L, delay = delay))
                lastEventTime = now
                notifyOverlay("record_count", recordedActions.size)
            }
        }
    }

    private fun getNodeBounds(node: AccessibilityNodeInfo?): Rect? {
        node ?: return null
        val rect = Rect()
        node.getBoundsInScreen(rect)
        node.recycle()
        return if (rect.isEmpty) null else rect
    }

    // ── 通知 OverlayService ──────────────────────────────────────────────────

    private fun notifyOverlay(
        type: String, count: Int = 0, status: String = "",
        current: Int = 0, total: Int = 0
    ) {
        sendBroadcast(Intent("com.macrorecorder.OVERLAY_EVENT").apply {
            setPackage(packageName)
            putExtra("type", type)
            putExtra("count", count)
            putExtra("status", status)
            putExtra("current", current)
            putExtra("total", total)
        })
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
