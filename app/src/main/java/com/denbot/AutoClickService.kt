package com.denbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class AutoClickService : AccessibilityService() {

    companion object {
        var instance: AutoClickService? = null
        val isEnabled get() = instance != null

        fun tap(x: Float, y: Float, durationMs: Long = 60, onDone: (() -> Unit)? = null) {
            instance?.performTap(x, y, durationMs, onDone)
        }

        fun tapSequence(x1: Float, y1: Float, x2: Float, y2: Float, delayMs: Long, durationMs: Long = 60, onDone: (() -> Unit)? = null) {
            instance?.let { svc ->
                svc.performTap(x1, y1, durationMs) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        svc.performTap(x2, y2, durationMs, onDone)
                    }, delayMs)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun performTap(x: Float, y: Float, durationMs: Long, onDone: (() -> Unit)? = null) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { onDone?.invoke() }
            override fun onCancelled(gestureDescription: GestureDescription?) { onDone?.invoke() }
        }, null)
    }
}
