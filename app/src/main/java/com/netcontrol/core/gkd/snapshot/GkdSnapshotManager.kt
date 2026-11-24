package com.netcontrol.core.gkd.snapshot

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * GKD 界面节点快照管理器：
 * 抓取当前无障碍窗口树并序列化为标准 GKD 兼容 JSON（可导出至 i.gkd.li 审查器或在 App 内直接测试选择器）。
 */
class GkdSnapshotManager(private val context: Context) {

    private val snapshotDir = File(context.filesDir, "gkd_snapshots").apply { mkdirs() }

    private val _snapshots = MutableStateFlow<List<GkdSnapshotDocument>>(emptyList())
    val snapshots: StateFlow<List<GkdSnapshotDocument>> = _snapshots.asStateFlow()

    fun captureFromRootNode(
        root: AccessibilityNodeInfo,
        appId: String,
        activityId: String
    ): GkdSnapshotDocument {
        val dm = context.resources.displayMetrics
        val now = System.currentTimeMillis()
        val nodes = mutableListOf<GkdSnapshotNode>()
        var nextId = 0

        fun walk(node: AccessibilityNodeInfo, pid: Int, index: Int, depth: Int) {
            if (nodes.size >= 1500 || depth > 48) return
            val myId = nextId++
            val rect = Rect()
            try {
                node.getBoundsInScreen(rect)
            } catch (_: Throwable) {
            }
            nodes.add(
                GkdSnapshotNode(
                    id = myId,
                    pid = pid,
                    index = index,
                    depth = depth,
                    name = node.className?.toString().orEmpty(),
                    viewId = node.viewIdResourceName,
                    text = node.text?.toString(),
                    desc = node.contentDescription?.toString(),
                    clickable = node.isClickable,
                    visibleToUser = node.isVisibleToUser,
                    left = rect.left,
                    top = rect.top,
                    right = rect.right,
                    bottom = rect.bottom
                )
            )
            val childCount = try {
                node.childCount
            } catch (_: Throwable) {
                0
            }
            for (i in 0 until childCount) {
                val child = try {
                    node.getChild(i)
                } catch (_: Throwable) {
                    null
                } ?: continue
                walk(child, myId, i, depth + 1)
            }
        }

        walk(root, pid = -1, index = 0, depth = 0)
        val doc = GkdSnapshotDocument(
            snapshotId = now,
            appId = appId,
            activityId = activityId,
            screenWidth = dm.widthPixels,
            screenHeight = dm.heightPixels,
            capturedAtMs = now,
            nodes = nodes
        )

        val file = File(snapshotDir, "snapshot_$now.json")
        file.writeText(serializeToGkdJson(doc), Charsets.UTF_8)
        _snapshots.value = (listOf(doc) + _snapshots.value).take(20)
        return doc
    }

    suspend fun exportSnapshotToUri(doc: GkdSnapshotDocument, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = serializeToGkdJson(doc)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
                out.flush()
            } != null
        } catch (_: Throwable) {
            false
        }
    }

    fun deleteSnapshot(snapshotId: Long) {
        File(snapshotDir, "snapshot_$snapshotId.json").delete()
        _snapshots.value = _snapshots.value.filterNot { it.snapshotId == snapshotId }
    }

    companion object {
        fun serializeToGkdJson(doc: GkdSnapshotDocument): String {
            val sb = StringBuilder(4096)
            sb.append("{\n")
            sb.append("  \"id\": ${doc.snapshotId},\n")
            sb.append("  \"appId\": \"${esc(doc.appId)}\",\n")
            sb.append("  \"activityId\": \"${esc(doc.activityId)}\",\n")
            sb.append("  \"screenWidth\": ${doc.screenWidth},\n")
            sb.append("  \"screenHeight\": ${doc.screenHeight},\n")
            sb.append("  \"nodes\": [\n")
            doc.nodes.forEachIndexed { idx, n ->
                sb.append("    {\"id\":${n.id},\"pid\":${n.pid},\"index\":${n.index},\"depth\":${n.depth},\"attr\":{")
                sb.append("\"name\":\"${esc(n.name)}\"")
                n.viewId?.let { sb.append(",\"id\":\"${esc(it)}\"") }
                n.vid?.let { sb.append(",\"vid\":\"${esc(it)}\"") }
                n.text?.let { sb.append(",\"text\":\"${esc(it)}\"") }
                n.desc?.let { sb.append(",\"desc\":\"${esc(it)}\"") }
                sb.append(",\"clickable\":${n.clickable},\"visibleToUser\":${n.visibleToUser}")
                sb.append(",\"left\":${n.left},\"top\":${n.top},\"right\":${n.right},\"bottom\":${n.bottom}")
                sb.append("}}")
                if (idx < doc.nodes.lastIndex) sb.append(',')
                sb.append('\n')
            }
            sb.append("  ]\n}")
            return sb.toString()
        }

        private fun esc(s: String): String = buildString(s.length) {
            for (c in s) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
        }
    }
}
