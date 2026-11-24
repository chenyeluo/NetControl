package com.netcontrol.core.gkd.selector

/**
 * 完整 GKD 高级选择器递归下降解析器：
 * 支持：
 * - `@` 目标标记、类名/通配符（`TextView`、`android.widget.Button`、`*`）
 * - 多组属性括号 `[a=1][b*="跳过"]` 及内部 `&&` / `||` / `!` / `(...)` 任意嵌套
 * - 全部比较符：`=`, `!=`, `^=`, `$=`, `*=`, `~=`, `<`, `<=`, `>`, `>=`
 * - 全部属性路径：`vid`, `id`, `text`, `text.length`, `desc`, `desc.length`, `clickable`, `visibleToUser`, `parent` 等
 * - 全部关系连接符：`>`, `>n`, `<`, `<n`, `<<`, `<<n`, `->`, `<-`, `+`, `+n`, `-`, `-n`, 元组 `+(1,2)` 与范围 `>1-3`
 */
object GkdSelectorParser {

    fun parse(input: String): GkdSelector {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            throw GkdSelectorSyntaxException("选择器不能为空")
        }
        val parser = ParserState(trimmed)
        return parser.parseSelector()
    }

    fun tryParse(input: String): GkdSelector? = runCatching { parse(input) }.getOrNull()

    private class ParserState(private val s: String) {
        private var pos = 0

        fun parseSelector(): GkdSelector {
            val segments = mutableListOf<PropertySegment>()
            val connectors = mutableListOf<ConnectSegment>()

            skipWs()
            segments.add(parsePropertySegment())
            while (true) {
                skipWs()
                if (eof()) break
                val conn = parseConnectSegment()
                connectors.add(conn)
                skipWs()
                segments.add(parsePropertySegment())
            }

            val markedIdx = segments.indexOfLast { it.isTargetMarked }
            val targetIdx = if (markedIdx >= 0) markedIdx else segments.lastIndex
            val fastHints = extractFastQueryHints(segments.last())

            return GkdSelector(
                source = s,
                segments = segments,
                connectors = connectors,
                targetIndex = targetIdx,
                fastQueryHints = fastHints
            )
        }

        private fun parsePropertySegment(): PropertySegment {
            skipWs()
            var isTarget = false
            if (peek() == '@') {
                isTarget = true
                pos++
            }

            var className: String? = null
            if (!eof() && peek() == '*') {
                className = "*"
                pos++
            } else if (!eof() && isIdentStart(peek())) {
                className = readClassName()
            }

            val exprs = mutableListOf<AttrExpr>()
            while (!eof() && peek() == '[') {
                pos++ // consume '['
                skipWs()
                val expr = parseOrExpr()
                skipWs()
                expect(']')
                exprs.add(expr)
            }

            if (className == null && exprs.isEmpty()) {
                throw GkdSelectorSyntaxException("位置 $pos 处缺少节点类型或属性表达式: $s")
            }

            return PropertySegment(
                isTargetMarked = isTarget,
                className = className,
                expressions = exprs
            )
        }

        private fun parseConnectSegment(): ConnectSegment {
            skipWs()
            val op: ConnectOpType
            var defaultRange: ConnectRange = ConnectRange.Fixed(1)

            when {
                startsWith("<<") -> {
                    pos += 2
                    op = ConnectOpType.ANCESTOR
                    defaultRange = ConnectRange.AnyLimit(Int.MAX_VALUE)
                }
                startsWith("->") -> {
                    pos += 2
                    op = ConnectOpType.DESCENDANT
                    defaultRange = ConnectRange.AnyLimit(Int.MAX_VALUE)
                }
                startsWith("<-") -> {
                    pos += 2
                    op = ConnectOpType.ANCESTOR
                    defaultRange = ConnectRange.AnyLimit(Int.MAX_VALUE)
                }
                peek() == '>' -> {
                    pos++
                    op = ConnectOpType.CHILD
                }
                peek() == '<' -> {
                    pos++
                    op = ConnectOpType.PARENT
                }
                peek() == '+' -> {
                    pos++
                    op = ConnectOpType.NEXT_SIBLING
                }
                peek() == '-' -> {
                    pos++
                    op = ConnectOpType.PREV_SIBLING
                }
                else -> throw GkdSelectorSyntaxException("位置 $pos 处期望关系连接符 (> < << + - -> <-): ${s.substring(pos)}")
            }

            val range = parseOptionalConnectRange(defaultRange)
            return ConnectSegment(op, range)
        }

        private fun parseOptionalConnectRange(defaultRange: ConnectRange): ConnectRange {
            if (eof()) return defaultRange
            val c = peek()
            return when {
                c == 'n' || c == 'N' -> {
                    pos++
                    ConnectRange.AnyLimit(Int.MAX_VALUE)
                }
                c == '(' -> {
                    pos++ // '('
                    val nums = mutableSetOf<Int>()
                    while (!eof()) {
                        skipWs()
                        if (peek() == 'n' || peek() == 'N') {
                            pos++
                            skipWs()
                            if (peek() == ')') pos++
                            return ConnectRange.AnyLimit(Int.MAX_VALUE)
                        }
                        val n = readPositiveInt()
                        nums.add(n)
                        skipWs()
                        if (peek() == ',') {
                            pos++
                        } else {
                            break
                        }
                    }
                    expect(')')
                    ConnectRange.ExactSet(nums)
                }
                c.isDigit() -> {
                    val first = readPositiveInt()
                    if (!eof() && peek() == '-') {
                        val saved = pos
                        pos++
                        if (!eof() && peek().isDigit()) {
                            val second = readPositiveInt()
                            ConnectRange.Range(minOf(first, second), maxOf(first, second))
                        } else {
                            pos = saved
                            ConnectRange.Fixed(first)
                        }
                    } else if (!eof() && (peek() == 'n' || peek() == 'N')) {
                        pos++
                        ConnectRange.AnyLimit(first)
                    } else {
                        // 在 GKD 中 <<3 表示最多向上 3 层，而 >2 / +2 表示精确第 2 步
                        if (defaultRange is ConnectRange.AnyLimit) {
                            ConnectRange.AnyLimit(first)
                        } else {
                            ConnectRange.Fixed(first)
                        }
                    }
                }
                else -> defaultRange
            }
        }

        private fun parseOrExpr(): AttrExpr {
            var left = parseAndExpr()
            while (true) {
                skipWs()
                if (startsWith("||")) {
                    pos += 2
                    skipWs()
                    val right = parseAndExpr()
                    left = AttrExpr.Or(left, right)
                } else {
                    break
                }
            }
            return left
        }

        private fun parseAndExpr(): AttrExpr {
            var left = parseUnaryExpr()
            while (true) {
                skipWs()
                if (startsWith("&&")) {
                    pos += 2
                    skipWs()
                    val right = parseUnaryExpr()
                    left = AttrExpr.And(left, right)
                } else {
                    break
                }
            }
            return left
        }

        private fun parseUnaryExpr(): AttrExpr {
            skipWs()
            if (peek() == '!') {
                pos++
                skipWs()
                return AttrExpr.Not(parseUnaryExpr())
            }
            if (peek() == '(') {
                pos++
                skipWs()
                val inner = parseOrExpr()
                skipWs()
                expect(')')
                return inner
            }
            return parseComparison()
        }

        private fun parseComparison(): AttrExpr.Compare {
            skipWs()
            val attrPath = readAttrPath()
            skipWs()
            val op = when {
                startsWith("!=") -> { pos += 2; CompareOp.NEQ }
                startsWith("^=") -> { pos += 2; CompareOp.STARTS_WITH }
                startsWith("$=") -> { pos += 2; CompareOp.ENDS_WITH }
                startsWith("*=") -> { pos += 2; CompareOp.CONTAINS }
                startsWith("~=") -> { pos += 2; CompareOp.REGEX }
                startsWith("<=") -> { pos += 2; CompareOp.LTE }
                startsWith(">=") -> { pos += 2; CompareOp.GTE }
                peek() == '=' -> { pos++; CompareOp.EQ }
                peek() == '<' -> { pos++; CompareOp.LT }
                peek() == '>' -> { pos++; CompareOp.GT }
                else -> throw GkdSelectorSyntaxException("位置 $pos 处缺少比较运算符: ${s.substring(pos)}")
            }
            skipWs()
            val literal = readLiteral()
            val compiledRegex = if (op == CompareOp.REGEX && literal is ValueLiteral.Str) {
                try {
                    Regex(literal.value)
                } catch (e: Throwable) {
                    throw GkdSelectorSyntaxException("无效正则表达式 '${literal.value}': ${e.message}")
                }
            } else {
                null
            }
            return AttrExpr.Compare(attrPath, op, literal, compiledRegex)
        }

        private fun readLiteral(): ValueLiteral {
            val c = peek()
            return when {
                c == '"' || c == '\'' -> ValueLiteral.Str(readQuotedString(c))
                c == '-' || c.isDigit() -> ValueLiteral.Num(readSignedLong())
                startsWithWord("true") -> { pos += 4; ValueLiteral.Bool(true) }
                startsWithWord("false") -> { pos += 5; ValueLiteral.Bool(false) }
                startsWithWord("null") -> { pos += 4; ValueLiteral.NullLit }
                else -> throw GkdSelectorSyntaxException("位置 $pos 处无法解析字面量: ${s.substring(pos)}")
            }
        }

        private fun readQuotedString(quote: Char): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (!eof()) {
                val ch = s[pos++]
                if (ch == quote) return sb.toString()
                if (ch == '\\' && !eof()) {
                    val esc = s[pos++]
                    when (esc) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        '\\' -> sb.append('\\')
                        '"' -> sb.append('"')
                        '\'' -> sb.append('\'')
                        'u' -> {
                            if (pos + 4 <= s.length) {
                                val hex = s.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16)
                                if (code != null) {
                                    sb.append(code.toChar())
                                    pos += 4
                                } else {
                                    sb.append("\\u")
                                }
                            }
                        }
                        else -> {
                            // 保留正则转义（如 \d、\s、\.）
                            sb.append('\\').append(esc)
                        }
                    }
                } else {
                    sb.append(ch)
                }
            }
            throw GkdSelectorSyntaxException("字符串未闭合")
        }

        private fun readClassName(): String {
            val start = pos
            while (!eof()) {
                val c = s[pos]
                if (c.isLetterOrDigit() || c == '_' || c == '.' || c == '$') {
                    pos++
                } else {
                    break
                }
            }
            return s.substring(start, pos)
        }

        private fun readAttrPath(): String {
            val start = pos
            while (!eof()) {
                val c = s[pos]
                if (c.isLetterOrDigit() || c == '_' || c == '.') {
                    pos++
                } else {
                    break
                }
            }
            if (pos == start) {
                throw GkdSelectorSyntaxException("位置 $pos 处期望属性名")
            }
            return s.substring(start, pos)
        }

        private fun readPositiveInt(): Int {
            val start = pos
            while (!eof() && s[pos].isDigit()) pos++
            if (pos == start) throw GkdSelectorSyntaxException("位置 $pos 处期望正整数")
            return s.substring(start, pos).toInt()
        }

        private fun readSignedLong(): Long {
            val start = pos
            if (peek() == '-') pos++
            while (!eof() && s[pos].isDigit()) pos++
            val token = s.substring(start, pos)
            return token.toLongOrNull()
                ?: throw GkdSelectorSyntaxException("位置 $start 处无效整数: $token")
        }

        private fun startsWith(prefix: String): Boolean = s.startsWith(prefix, pos)

        private fun startsWithWord(word: String): Boolean {
            if (!s.startsWith(word, pos)) return false
            val endIdx = pos + word.length
            return endIdx >= s.length || (!s[endIdx].isLetterOrDigit() && s[endIdx] != '_')
        }

        private fun isIdentStart(c: Char): Boolean = c.isLetter() || c == '_'

        private fun skipWs() {
            while (!eof() && s[pos].isWhitespace()) pos++
        }

        private fun expect(ch: Char) {
            if (eof() || s[pos] != ch) {
                throw GkdSelectorSyntaxException("位置 $pos 处期望 '$ch'")
            }
            pos++
        }

        private fun eof(): Boolean = pos >= s.length
        private fun peek(): Char = if (eof()) '\u0000' else s[pos]
    }

    /**
     * 从最右侧入口段提取可用于 Android 无障碍 fastQuery 的精确索引键（id / vid / text）。
     */
    private fun extractFastQueryHints(entrySegment: PropertySegment): List<FastQueryHint> {
        val hints = mutableListOf<FastQueryHint>()
        fun collect(expr: AttrExpr) {
            when (expr) {
                is AttrExpr.And -> {
                    collect(expr.left)
                    collect(expr.right)
                }
                is AttrExpr.Compare -> {
                    val strVal = (expr.literal as? ValueLiteral.Str)?.value ?: return
                    if (strVal.isEmpty()) return
                    when (expr.attrPath) {
                        "id" -> if (expr.op == CompareOp.EQ) hints.add(FastQueryHint.ByFullId(strVal))
                        "vid" -> if (expr.op == CompareOp.EQ) hints.add(FastQueryHint.ByVid(strVal))
                        "text" -> if (expr.op == CompareOp.EQ || expr.op == CompareOp.STARTS_WITH || expr.op == CompareOp.CONTAINS || expr.op == CompareOp.ENDS_WITH) {
                            hints.add(FastQueryHint.ByText(strVal))
                        }
                    }
                }
                is AttrExpr.Or, is AttrExpr.Not -> Unit
            }
        }
        entrySegment.expressions.forEach { collect(it) }
        return hints
    }
}
