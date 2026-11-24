package com.netcontrol.core.gkd.selector

/**
 * 抽象 UI 节点适配器：
 * - 运行时包装 Android AccessibilityNodeInfo（带缓存与 fastQuery 索引）；
 * - 单元测试与快照回放时包装纯 Kotlin GkdSnapshotNode。
 */
interface GkdNodeAdapter<T> {
    fun getAttr(node: T, attrName: String): Any?
    fun getParent(node: T): T?
    fun getChildren(node: T): List<T>
    fun getChildCount(node: T): Int = getChildren(node).size
    fun getIndex(node: T): Int
    fun getDepth(node: T): Int

    /**
     * 高速索引查询（对应 GKD fastQuery）：仅查 vid / id / text 命中的候选节点，
     * 避免遍历整棵无障碍节点树；若适配器或表达式不支持 fastQuery 则返回 null 回退全树搜索。
     */
    fun fastQueryById(root: T, fullId: String): List<T>? = null
    fun fastQueryByText(root: T, text: String): List<T>? = null
}

/**
 * 关系连接符类型（完全对标 gkd-kit/selector 规范，支持 L <op> R 语义）。
 */
enum class ConnectOpType {
    /** L > R：R 是 L 的子代（默认 1 层，>n 为 n 层内子代） */
    CHILD,
    /** L < R：R 是 L 的父代（默认 1 层，<n 为 n 层内祖先） */
    PARENT,
    /** L << R / L <<n R：R 是 L 的任意祖先（或 n 层内祖先） */
    ANCESTOR,
    /** L -> R：R 是 L 的任意后代（或 n 层内后代） */
    DESCENDANT,
    /** L + R：R 是 L 之后的第 n 个兄弟（默认 +1 紧邻后兄弟） */
    NEXT_SIBLING,
    /** L - R：R 是 L 之前的第 n 个兄弟（默认 -1 紧邻前兄弟） */
    PREV_SIBLING
}

/**
 * 连接符步长/范围描述：
 * - Fixed(1) 表示默认紧邻（如 `>`、`+`、`-`、`<`）
 * - AnyLimit(max) 表示 1..max 任意步长（`max = Int.MAX_VALUE` 表示无限，如 `<<n`）
 * - ExactSet(setOf(1, 2)) 表示元组步长，如 `+(1,2)`、`>(1,3)`
 * - Range(min, max) 表示闭区间范围，如 `1-3`
 */
sealed interface ConnectRange {
    fun matchesDistance(d: Int): Boolean
    fun maxDistance(): Int

    data class Fixed(val step: Int) : ConnectRange {
        override fun matchesDistance(d: Int): Boolean = d == step
        override fun maxDistance(): Int = step
    }

    data class AnyLimit(val max: Int = Int.MAX_VALUE) : ConnectRange {
        override fun matchesDistance(d: Int): Boolean = d in 1..max
        override fun maxDistance(): Int = max
    }

    data class ExactSet(val steps: Set<Int>) : ConnectRange {
        override fun matchesDistance(d: Int): Boolean = d in steps
        override fun maxDistance(): Int = steps.maxOrNull() ?: 0
    }

    data class Range(val min: Int, val max: Int) : ConnectRange {
        override fun matchesDistance(d: Int): Boolean = d in min..max
        override fun maxDistance(): Int = max
    }
}

data class ConnectSegment(
    val operator: ConnectOpType,
    val range: ConnectRange
)

enum class CompareOp {
    EQ,         // =
    NEQ,        // !=
    STARTS_WITH,// ^=
    ENDS_WITH,  // $=
    CONTAINS,   // *=
    REGEX,      // ~=
    LT,         // <
    LTE,        // <=
    GT,         // >
    GTE         // >=
}

sealed interface ValueLiteral {
    data class Str(val value: String) : ValueLiteral
    data class Num(val value: Long) : ValueLiteral
    data class Bool(val value: Boolean) : ValueLiteral
    data object NullLit : ValueLiteral
}

sealed interface AttrExpr {
    data class Compare(
        val attrPath: String,
        val op: CompareOp,
        val literal: ValueLiteral,
        val compiledRegex: Regex? = null
    ) : AttrExpr

    data class And(val left: AttrExpr, val right: AttrExpr) : AttrExpr
    data class Or(val left: AttrExpr, val right: AttrExpr) : AttrExpr
    data class Not(val inner: AttrExpr) : AttrExpr
}

data class PropertySegment(
    val isTargetMarked: Boolean,
    val className: String?, // null 或 "*" 表示通配任意节点
    val expressions: List<AttrExpr>
)

data class GkdSelector(
    val source: String,
    /** 从左到右的属性段，长度 = N (N >= 1) */
    val segments: List<PropertySegment>,
    /** 连接 segments[i] 与 segments[i+1] 的关系符，长度 = N - 1 */
    val connectors: List<ConnectSegment>,
    /** 最终目标点击段下标（有 @ 取最后一个 @ 段；无 @ 默认最右侧 segments.lastIndex） */
    val targetIndex: Int,
    /** 提取出的 fastQuery 加速提示（基于最右侧入口段） */
    val fastQueryHints: List<FastQueryHint>
)

sealed interface FastQueryHint {
    data class ByFullId(val fullId: String) : FastQueryHint
    data class ByVid(val vid: String) : FastQueryHint
    data class ByText(val text: String) : FastQueryHint
}

class GkdSelectorSyntaxException(message: String) : IllegalArgumentException(message)
