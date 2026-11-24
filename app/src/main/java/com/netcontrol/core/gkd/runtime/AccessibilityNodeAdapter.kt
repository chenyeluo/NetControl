package com.netcontrol.core.gkd.runtime

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.netcontrol.core.gkd.selector.GkdNodeAdapter
import java.util.IdentityHashMap

/**
 * 将 Android 原生 AccessibilityNodeInfo 适配给 GkdSelectorMatcher，
 * 带单次事件生命周期内轻量缓存与高速 fastQuery 索引支持。
 */
class AccessibilityNodeAdapter(
    private val rootNode: AccessibilityNodeInfo
) : GkdNodeAdapter<AccessibilityNodeInfo> {

    private val childrenCache = IdentityHashMap<AccessibilityNodeInfo, List<AccessibilityNodeInfo>>()
    private val parentCache = IdentityHashMap<AccessibilityNodeInfo, AccessibilityNodeInfo?>()
    private val rectCache = IdentityHashMap<AccessibilityNodeInfo, Rect>()

    override fun getAttr(node: AccessibilityNodeInfo, attrName: String): Any? {
        return when (attrName) {
            "id" -> node.viewIdResourceName
            "vid" -> node.viewIdResourceName?.substringAfter(":id/", "")?.ifEmpty { null }
            "name" -> node.className?.toString()
            "text" -> node.text?.toString()
            "desc" -> node.contentDescription?.toString()
            "clickable" -> node.isClickable
            "focusable" -> node.isFocusable
            "checkable" -> node.isCheckable
            "checked" -> @Suppress("DEPRECATION") node.isChecked
            "editable" -> node.isEditable
            "enabled" -> node.isEnabled
            "visibleToUser" -> node.isVisibleToUser
            "scrollable" -> node.isScrollable
            "selected" -> node.isSelected
            "longClickable" -> node.isLongClickable
            "left" -> getRect(node).left
            "top" -> getRect(node).top
            "right" -> getRect(node).right
            "bottom" -> getRect(node).bottom
            "width" -> getRect(node).width()
            "height" -> getRect(node).height()
            "childCount" -> node.childCount
            else -> null
        }
    }

    override fun getParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node === rootNode) return null
        if (parentCache.containsKey(node)) return parentCache[node]
        val p = try {
            node.parent
        } catch (_: Throwable) {
            null
        }
        parentCache[node] = p
        return p
    }

    override fun getChildren(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        childrenCache[node]?.let { return it }
        val count = try {
            node.childCount
        } catch (_: Throwable) {
            0
        }
        if (count <= 0) {
            childrenCache[node] = emptyList()
            return emptyList()
        }
        val list = ArrayList<AccessibilityNodeInfo>(count)
        for (i in 0 until count) {
            val child = try {
                node.getChild(i)
            } catch (_: Throwable) {
                null
            }
            if (child != null) {
                parentCache[child] = node
                list.add(child)
            }
        }
        childrenCache[node] = list
        return list
    }

    override fun getIndex(node: AccessibilityNodeInfo): Int {
        val parent = getParent(node) ?: return 0
        val siblings = getChildren(parent)
        val idx = siblings.indexOfFirst { it === node || it == node }
        return if (idx >= 0) idx else 0
    }

    override fun getDepth(node: AccessibilityNodeInfo): Int {
        var d = 0
        var cur = getParent(node)
        while (cur != null && d < 64) {
            d++
            cur = getParent(cur)
        }
        return d
    }

    override fun fastQueryById(root: AccessibilityNodeInfo, fullId: String): List<AccessibilityNodeInfo>? {
        return try {
            root.findAccessibilityNodeInfosByViewId(fullId)
        } catch (_: Throwable) {
            null
        }
    }

    override fun fastQueryByText(root: AccessibilityNodeInfo, text: String): List<AccessibilityNodeInfo>? {
        return try {
            root.findAccessibilityNodeInfosByText(text)
        } catch (_: Throwable) {
            null
        }
    }

    fun getBoundsRect(node: AccessibilityNodeInfo): Rect = getRect(node)

    private fun getRect(node: AccessibilityNodeInfo): Rect {
        rectCache[node]?.let { return it }
        val rect = Rect()
        try {
            node.getBoundsInScreen(rect)
        } catch (_: Throwable) {
        }
        rectCache[node] = rect
        return rect
    }
}
