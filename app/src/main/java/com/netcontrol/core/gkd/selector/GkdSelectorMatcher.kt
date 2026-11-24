package com.netcontrol.core.gkd.selector

/**
 * 完整 GKD 选择器树匹配引擎：
 * 1. 从最右侧段（segments.last()）作为锚点开始：若 fastQuery=true 且存在 id/vid/text 提示，优先走高速索引；
 *    否则按 BFS 遍历整棵树收集入口候选节点；
 * 2. 沿 connectors 从右向左递归回溯验证每一段关系（支持 > / < / << / -> / + / - 及全部步长范围）；
 * 3. 整条链路命中后，返回 targetIndex（由 `@` 指定，默认最右侧）对应的精准目标节点！
 */
object GkdSelectorMatcher {

    fun <T> matchFirst(
        selector: GkdSelector,
        root: T,
        adapter: GkdNodeAdapter<T>,
        packageName: String? = null,
        fastQuery: Boolean = false
    ): T? {
        val lastIdx = selector.segments.lastIndex
        val entrySegment = selector.segments[lastIdx]

        val candidates: Sequence<T> = if (fastQuery) {
            resolveFastCandidates(selector, root, adapter, packageName)
                ?: traverseAllBfs(root, adapter)
        } else {
            traverseAllBfs(root, adapter)
        }

        val matchedChain = arrayOfNulls<Any>(selector.segments.size)
        for (candidate in candidates) {
            if (!matchesSegment(entrySegment, candidate, adapter)) continue
            matchedChain[lastIdx] = candidate
            if (matchRemainingLeft(selector, lastIdx - 1, candidate, adapter, matchedChain)) {
                @Suppress("UNCHECKED_CAST")
                return matchedChain[selector.targetIndex] as T
            }
        }
        return null
    }

    private fun <T> resolveFastCandidates(
        selector: GkdSelector,
        root: T,
        adapter: GkdNodeAdapter<T>,
        packageName: String?
    ): Sequence<T>? {
        for (hint in selector.fastQueryHints) {
            val found = when (hint) {
                is FastQueryHint.ByFullId -> adapter.fastQueryById(root, hint.fullId)
                is FastQueryHint.ByVid -> {
                    val full = if (!packageName.isNullOrBlank()) "$packageName:id/${hint.vid}" else null
                    if (full != null) adapter.fastQueryById(root, full) else null
                }
                is FastQueryHint.ByText -> adapter.fastQueryByText(root, hint.text)
            }
            if (found != null) {
                return found.asSequence()
            }
        }
        return null
    }

    private fun <T> matchRemainingLeft(
        selector: GkdSelector,
        segIndex: Int,
        rightNode: T,
        adapter: GkdNodeAdapter<T>,
        matchedChain: Array<Any?>
    ): Boolean {
        if (segIndex < 0) return true

        val connector = selector.connectors[segIndex]
        val leftSegment = selector.segments[segIndex]

        val leftCandidates = findLeftNeighbors(rightNode, connector, adapter)
        for (leftNode in leftCandidates) {
            if (!matchesSegment(leftSegment, leftNode, adapter)) continue
            matchedChain[segIndex] = leftNode
            if (matchRemainingLeft(selector, segIndex - 1, leftNode, adapter, matchedChain)) {
                return true
            }
        }
        return false
    }

    /**
     * 给定关系 `left <connector> right` 以及已命中的 rightNode，反向查找满足步长范围的所有 leftNode 候选：
     * - `L > R` (CHILD): L 是 R 的祖先（距离 d >= 1）
     * - `L -> R` (DESCENDANT): L 是 R 的祖先（任意距离）
     * - `L < R` (PARENT): L 是 R 的子代（距离 d >= 1）
     * - `L << R` (ANCESTOR): L 是 R 的子孙（任意距离）
     * - `L + R` (NEXT_SIBLING): R 在 L 后面，因此 L 是 R 前面的第 d 个兄弟
     * - `L - R` (PREV_SIBLING): R 在 L 前面，因此 L 是 R 后面的第 d 个兄弟
     */
    private fun <T> findLeftNeighbors(
        rightNode: T,
        connector: ConnectSegment,
        adapter: GkdNodeAdapter<T>
    ): Sequence<T> = sequence {
        val range = connector.range
        val maxD = range.maxDistance().coerceAtMost(64)
        if (maxD <= 0) return@sequence

        when (connector.operator) {
            ConnectOpType.CHILD, ConnectOpType.DESCENDANT -> {
                var cur = adapter.getParent(rightNode)
                var dist = 1
                while (cur != null && dist <= maxD) {
                    if (range.matchesDistance(dist)) {
                        yield(cur)
                    }
                    cur = adapter.getParent(cur)
                    dist++
                }
            }

            ConnectOpType.PARENT, ConnectOpType.ANCESTOR -> {
                // BFS 向下查找距离 1..maxD 的子节点/子孙节点
                val queue = ArrayDeque<Pair<T, Int>>()
                for (child in adapter.getChildren(rightNode)) {
                    queue.addLast(child to 1)
                }
                while (queue.isNotEmpty()) {
                    val (node, dist) = queue.removeFirst()
                    if (range.matchesDistance(dist)) {
                        yield(node)
                    }
                    if (dist < maxD) {
                        for (child in adapter.getChildren(node)) {
                            queue.addLast(child to dist + 1)
                        }
                    }
                }
            }

            ConnectOpType.NEXT_SIBLING -> {
                val parent = adapter.getParent(rightNode) ?: return@sequence
                val siblings = adapter.getChildren(parent)
                val rightIdx = adapter.getIndex(rightNode)
                if (rightIdx <= 0) return@sequence
                var dist = 1
                while (dist <= maxD && rightIdx - dist >= 0) {
                    if (range.matchesDistance(dist)) {
                        yield(siblings[rightIdx - dist])
                    }
                    dist++
                }
            }

            ConnectOpType.PREV_SIBLING -> {
                val parent = adapter.getParent(rightNode) ?: return@sequence
                val siblings = adapter.getChildren(parent)
                val rightIdx = adapter.getIndex(rightNode)
                if (rightIdx < 0 || rightIdx >= siblings.lastIndex) return@sequence
                var dist = 1
                while (dist <= maxD && rightIdx + dist < siblings.size) {
                    if (range.matchesDistance(dist)) {
                        yield(siblings[rightIdx + dist])
                    }
                    dist++
                }
            }
        }
    }

    fun <T> matchesSegment(
        segment: PropertySegment,
        node: T,
        adapter: GkdNodeAdapter<T>
    ): Boolean {
        val targetCls = segment.className
        if (targetCls != null && targetCls != "*") {
            val actualName = adapter.getAttr(node, "name")?.toString() ?: return false
            val matches = if (targetCls.contains('.')) {
                actualName == targetCls
            } else {
                actualName == targetCls || actualName.endsWith(".$targetCls")
            }
            if (!matches) return false
        }

        for (expr in segment.expressions) {
            if (!evalExpr(expr, node, adapter)) return false
        }
        return true
    }

    private fun <T> evalExpr(expr: AttrExpr, node: T, adapter: GkdNodeAdapter<T>): Boolean {
        return when (expr) {
            is AttrExpr.And -> evalExpr(expr.left, node, adapter) && evalExpr(expr.right, node, adapter)
            is AttrExpr.Or -> evalExpr(expr.left, node, adapter) || evalExpr(expr.right, node, adapter)
            is AttrExpr.Not -> !evalExpr(expr.inner, node, adapter)
            is AttrExpr.Compare -> evalCompare(expr, node, adapter)
        }
    }

    private fun <T> evalCompare(cmp: AttrExpr.Compare, node: T, adapter: GkdNodeAdapter<T>): Boolean {
        val actual = resolveAttrPath(node, cmp.attrPath, adapter)
        val expected = cmp.literal

        if (expected is ValueLiteral.NullLit) {
            return when (cmp.op) {
                CompareOp.EQ -> actual == null
                CompareOp.NEQ -> actual != null
                else -> false
            }
        }

        if (actual == null) {
            return cmp.op == CompareOp.NEQ
        }

        return when (expected) {
            is ValueLiteral.Bool -> {
                val actualBool = when (actual) {
                    is Boolean -> actual
                    is String -> actual.toBooleanStrictOrNull()
                    else -> null
                } ?: return cmp.op == CompareOp.NEQ
                when (cmp.op) {
                    CompareOp.EQ -> actualBool == expected.value
                    CompareOp.NEQ -> actualBool != expected.value
                    else -> false
                }
            }

            is ValueLiteral.Num -> {
                val actualNum = when (actual) {
                    is Number -> actual.toLong()
                    is String -> actual.toLongOrNull()
                    else -> null
                } ?: return cmp.op == CompareOp.NEQ
                when (cmp.op) {
                    CompareOp.EQ -> actualNum == expected.value
                    CompareOp.NEQ -> actualNum != expected.value
                    CompareOp.LT -> actualNum < expected.value
                    CompareOp.LTE -> actualNum <= expected.value
                    CompareOp.GT -> actualNum > expected.value
                    CompareOp.GTE -> actualNum >= expected.value
                    else -> false
                }
            }

            is ValueLiteral.Str -> {
                val actualStr = actual.toString()
                when (cmp.op) {
                    CompareOp.EQ -> actualStr == expected.value
                    CompareOp.NEQ -> actualStr != expected.value
                    CompareOp.STARTS_WITH -> actualStr.startsWith(expected.value)
                    CompareOp.ENDS_WITH -> actualStr.endsWith(expected.value)
                    CompareOp.CONTAINS -> actualStr.contains(expected.value)
                    CompareOp.REGEX -> cmp.compiledRegex?.containsMatchIn(actualStr) == true
                    else -> false
                }
            }

            ValueLiteral.NullLit -> false
        }
    }

    private fun <T> resolveAttrPath(node: T, path: String, adapter: GkdNodeAdapter<T>): Any? {
        if (!path.contains('.')) {
            return when (path) {
                "parent" -> adapter.getParent(node)
                "index" -> adapter.getIndex(node)
                "depth" -> adapter.getDepth(node)
                "childCount" -> adapter.getChildCount(node)
                else -> adapter.getAttr(node, path)
            }
        }
        val parts = path.split('.')
        var current: Any? = node
        for (part in parts) {
            if (current == null) return null
            current = when {
                part == "length" && current is CharSequence -> current.length
                part == "parent" -> {
                    @Suppress("UNCHECKED_CAST")
                    adapter.getParent(current as T)
                }
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    adapter.getAttr(current as T, part)
                }
            }
        }
        return current
    }

    private fun <T> traverseAllBfs(root: T, adapter: GkdNodeAdapter<T>): Sequence<T> = sequence {
        val queue = ArrayDeque<T>()
        queue.addLast(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            yield(node)
            for (child in adapter.getChildren(node)) {
                queue.addLast(child)
            }
        }
    }
}
