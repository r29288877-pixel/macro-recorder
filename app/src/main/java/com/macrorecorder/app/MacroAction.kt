package com.macrorecorder.app

data class MacroAction(
    val type: ActionType,
    val x: Float,
    val y: Float,
    val x2: Float = 0f,
    val y2: Float = 0f,
    val duration: Long = 0L,
    val delay: Long = 0L  // delay before this action (ms)
)

enum class ActionType {
    TAP,
    SWIPE,
    LONG_PRESS
}
