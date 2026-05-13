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
import android.view.accessibility.AccessibilityEvent
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class MacroAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_PLAY = "com.macrorecorder.PLAY"
        const val ACTION_STOP = "com.macrorecorder.STOP"
        const val ACTION_RECORD_EVENT = "com.macrorecorder.RECORD_EVENT"
        const val EXTRA_ACTIONS = "actions"
        const val EXTRA_REPEAT = "repeat"
        const val EXTRA_ACTION = "action"

        var instance: MacroAccessibilityService? = null
        var isPlaying = false
        var isRecording = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private var repeatCount = 1
    private var currentRepeat = 0
    private var actions: List<MacroAction> = emptyList()
    private val gson = Gson()

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
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val filter = IntentFilter().apply {
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
        try { unregisterReceiver(receiver) } catch (e: Exception) {}
    }

    private fun playNext() {
        if (!isPlaying) return
        if (actions.isEmpty()) return

        var totalDelay = 0L
        for ((index, action) in actions.withIndex()) {
            val delay = totalDelay + action.delay
            totalDelay = delay + action.duration + 50L

            handler.postDelayed({
                if (!isPlaying) return@postDelayed
                performMacroAction(action)
            }, delay)
        }

        // after all actions, check repeat
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
