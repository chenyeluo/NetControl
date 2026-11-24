package com.netcontrol.core.gkd.runtime

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.netcontrol.core.gkd.subscription.GkdActionType
import com.netcontrol.core.gkd.subscription.GkdPosition

/**
 * GKD 动作执行器：
 * 完整支持 click（优先无障碍 ACTION_CLICK，不可点自动降级为手势坐标点击）、
 * clickNode、clickCenter（含 position 自定义偏移）、longClick、back 与 none。
 */
object GkdActionExecutor {

    fun execute(
        service: AccessibilityService,
        node: AccessibilityNodeInfo,
        bounds: Rect,
        action: GkdActionType,
        position: GkdPosition?
    ): Boolean {
        return when (action) {
            GkdActionType.NONE -> true
            GkdActionType.BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            GkdActionType.CLICK_NODE -> performNodeClickOrParent(node)
            GkdActionType.CLICK_CENTER -> dispatchTapGesture(service, bounds, position, durationMs = 45L)
            GkdActionType.CLICK -> {
                if (position != null) {
                    dispatchTapGesture(service, bounds, position, durationMs = 45L)
                } else {
                    performNodeClickOrParent(node) || dispatchTapGesture(service, bounds, null, durationMs = 45L)
                }
            }
            GkdActionType.LONG_CLICK_NODE -> node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            GkdActionType.LONG_CLICK_CENTER -> dispatchTapGesture(service, bounds, position, durationMs = 600L)
            GkdActionType.LONG_CLICK -> {
                node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) ||
                    dispatchTapGesture(service, bounds, position, durationMs = 600L)
            }
        }
    }

    private fun performNodeClickOrParent(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops < 4) {
            if (cur.isClickable) {
                if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
            cur = try {
                cur.parent
            } catch (_: Throwable) {
                null
            }
            hops++
        }
        return false
    }

    private fun dispatchTapGesture(
        service: AccessibilityService,
        bounds: Rect,
        position: GkdPosition?,
        durationMs: Long
    ): Boolean {
        if (bounds.isEmpty) return false
        val (x, y) = GkdRuleRuntimeEngine.computeClickPoint(
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            position = position
        )
        if (x < 0f || y < 0f) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(gesture, null, null)
    }
}
