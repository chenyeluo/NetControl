package com.netcontrol.core.gkd.snapshot

import com.netcontrol.core.gkd.selector.GkdNodeAdapter

data class GkdSnapshotNode(
    val id: Int,
    val pid: Int,
    val index: Int,
    val depth: Int,
    val name: String,
    val viewId: String?,
    val text: String?,
    val desc: String?,
    val clickable: Boolean,
    val visibleToUser: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val vid: String? get() = viewId?.substringAfter(":id/", "")?.ifEmpty { null }
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

data class GkdSnapshotDocument(
    val snapshotId: Long,
    val appId: String,
    val activityId: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val capturedAtMs: Long,
    val nodes: List<GkdSnapshotNode>
)

/**
 * 纯 Kotlin 快照节点树适配器：
 * 既支撑 JVM 单元测试，也支撑 App 内置的「离线选择器实时试跑器」！
 */
class SnapshotNodeAdapter(
    nodes: List<GkdSnapshotNode>
) : GkdNodeAdapter<GkdSnapshotNode> {

    private val byId = nodes.associateBy { it.id }
    private val childrenByPid = nodes.groupBy { it.pid }

    val root: GkdSnapshotNode? = nodes.firstOrNull { it.pid < 0 } ?: nodes.firstOrNull()

    override fun getAttr(node: GkdSnapshotNode, attrName: String): Any? = when (attrName) {
        "id" -> node.viewId
        "vid" -> node.vid
        "name" -> node.name
        "text" -> node.text
        "desc" -> node.desc
        "clickable" -> node.clickable
        "visibleToUser" -> node.visibleToUser
        "left" -> node.left
        "top" -> node.top
        "right" -> node.right
        "bottom" -> node.bottom
        "width" -> node.width
        "height" -> node.height
        "childCount" -> getChildren(node).size
        else -> null
    }

    override fun getParent(node: GkdSnapshotNode): GkdSnapshotNode? =
        if (node.pid < 0) null else byId[node.pid]

    override fun getChildren(node: GkdSnapshotNode): List<GkdSnapshotNode> =
        childrenByPid[node.id].orEmpty()

    override fun getIndex(node: GkdSnapshotNode): Int = node.index

    override fun getDepth(node: GkdSnapshotNode): Int = node.depth

    override fun fastQueryById(root: GkdSnapshotNode, fullId: String): List<GkdSnapshotNode> =
        byId.values.filter { it.viewId == fullId }

    override fun fastQueryByText(root: GkdSnapshotNode, text: String): List<GkdSnapshotNode> =
        byId.values.filter { it.text?.contains(text) == true }
}
