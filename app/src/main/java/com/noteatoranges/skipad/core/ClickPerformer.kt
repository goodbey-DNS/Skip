package com.noteatoranges.skipad.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 点击执行器：语义点击优先，坐标手势兜底。
 *
 * 1. performAction(ACTION_CLICK)：语义事件，目标 App 的 onClick 逻辑最完整地执行；
 * 2. 逐级向上尝试祖先的 ACTION_CLICK（部分广告按钮把 listener 挂在容器上）；
 * 3. dispatchGesture 坐标点击（API 24+）：节点不吃 ACTION_CLICK，或 WebView 广告
 *    场景由调用方直接给坐标（design.md §4 L4 层）。
 */
class ClickPerformer(private val service: AccessibilityService) {

    fun click(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true

        var parent = node.parent
        var hops = 0
        while (parent != null && hops < 4) {
            if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            parent = parent.parent
            hops++
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        return clickPoint(rect.exactCenterX(), rect.exactCenterY())
    }

    fun clickPoint(x: Float, y: Float): Boolean {
        if (x <= 0f || y <= 0f) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, STROKE_DURATION_MS))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    private companion object {
        const val STROKE_DURATION_MS = 50L
    }
}
