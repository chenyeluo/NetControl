package com.netcontrol.core.gkd.subscription

/**
 * 零外部依赖的纯 Kotlin 完整 JSON5 解析器：
 * 原生支持单行/多行注释、无引号键名、单/双引号字符串、尾随逗号、十六进制数字与多行续航，
 * 100% 兼容 GKD 订阅文件（.json / .json5）。
 */
object Json5Parser {

    fun parse(input: String): Any? {
        val state = State(input)
        state.skipWsAndComments()
        val value = state.parseValue()
        state.skipWsAndComments()
        return value
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(input: String): Map<String, Any?> {
        return parse(input) as? Map<String, Any?>
            ?: throw IllegalArgumentException("JSON5 根节点必须是对象 {...}")
    }

    private class State(private val s: String) {
        private var pos = 0

        fun parseValue(): Any? {
            skipWsAndComments()
            if (eof()) throw IllegalArgumentException("JSON5 意外结束")
            return when (val c = peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"', '\'' -> parseString(c)
                't' -> { expectWord("true"); true }
                'f' -> { expectWord("false"); false }
                'n' -> { expectWord("null"); null }
                'N' -> { expectWord("NaN"); Double.NaN }
                'I' -> { expectWord("Infinity"); Double.POSITIVE_INFINITY }
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            pos++ // '{'
            val map = LinkedHashMap<String, Any?>()
            while (true) {
                skipWsAndComments()
                if (eof()) throw IllegalArgumentException("JSON5 对象未闭合")
                if (peek() == '}') {
                    pos++
                    return map
                }
                val key = when (val c = peek()) {
                    '"', '\'' -> parseString(c)
                    else -> parseUnquotedKey()
                }
                skipWsAndComments()
                if (eof() || peek() != ':') {
                    throw IllegalArgumentException("位置 $pos 处期望 ':' (key=$key)")
                }
                pos++ // ':'
                val value = parseValue()
                map[key] = value
                skipWsAndComments()
                if (!eof() && peek() == ',') {
                    pos++
                } else if (!eof() && peek() == '}') {
                    pos++
                    return map
                } else {
                    throw IllegalArgumentException("位置 $pos 处期望 ',' 或 '}'")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            pos++ // '['
            val list = ArrayList<Any?>()
            while (true) {
                skipWsAndComments()
                if (eof()) throw IllegalArgumentException("JSON5 数组未闭合")
                if (peek() == ']') {
                    pos++
                    return list
                }
                list.add(parseValue())
                skipWsAndComments()
                if (!eof() && peek() == ',') {
                    pos++
                } else if (!eof() && peek() == ']') {
                    pos++
                    return list
                } else {
                    throw IllegalArgumentException("位置 $pos 处期望 ',' 或 ']'")
                }
            }
        }

        private fun parseString(quote: Char): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (!eof()) {
                val c = s[pos++]
                if (c == quote) return sb.toString()
                if (c == '\\') {
                    if (eof()) throw IllegalArgumentException("字符串转义未结束")
                    when (val esc = s[pos++]) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        '\\' -> sb.append('\\')
                        '"' -> sb.append('"')
                        '\'' -> sb.append('\'')
                        '/' -> sb.append('/')
                        '\n' -> Unit // JSON5 多行续航
                        '\r' -> if (!eof() && peek() == '\n') pos++
                        'u' -> {
                            if (pos + 4 <= s.length) {
                                val hex = s.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw IllegalArgumentException("无效 \\u 转义: $hex")
                                sb.append(code.toChar())
                                pos += 4
                            }
                        }
                        'x' -> {
                            if (pos + 2 <= s.length) {
                                val hex = s.substring(pos, pos + 2)
                                val code = hex.toIntOrNull(16)
                                    ?: throw IllegalArgumentException("无效 \\x 转义: $hex")
                                sb.append(code.toChar())
                                pos += 2
                            }
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(c)
                }
            }
            throw IllegalArgumentException("JSON5 字符串未闭合")
        }

        private fun parseUnquotedKey(): String {
            val start = pos
            while (!eof()) {
                val c = s[pos]
                if (c.isLetterOrDigit() || c == '_' || c == '$') {
                    pos++
                } else {
                    break
                }
            }
            if (pos == start) {
                throw IllegalArgumentException("位置 $pos 处无效键名: ${s.substring(pos, minOf(pos + 20, s.length))}")
            }
            return s.substring(start, pos)
        }

        private fun parseNumber(): Number {
            val start = pos
            if (peek() == '+' || peek() == '-') pos++
            if (s.startsWith("Infinity", pos)) {
                pos += 8
                return if (s[start] == '-') Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
            }
            if (s.startsWith("NaN", pos)) {
                pos += 3
                return Double.NaN
            }
            if (s.startsWith("0x", pos) || s.startsWith("0X", pos)) {
                pos += 2
                val hexStart = pos
                while (!eof() && s[pos].isHexDigit()) pos++
                val sign = if (s[start] == '-') -1L else 1L
                return sign * s.substring(hexStart, pos).toLong(16)
            }
            var isFloat = false
            while (!eof() && s[pos].isDigit()) pos++
            if (!eof() && s[pos] == '.') {
                isFloat = true
                pos++
                while (!eof() && s[pos].isDigit()) pos++
            }
            if (!eof() && (s[pos] == 'e' || s[pos] == 'E')) {
                isFloat = true
                pos++
                if (!eof() && (s[pos] == '+' || s[pos] == '-')) pos++
                while (!eof() && s[pos].isDigit()) pos++
            }
            val token = s.substring(start, pos)
            if (token.isEmpty() || token == "+" || token == "-" || token == ".") {
                throw IllegalArgumentException("位置 $start 处无效数字: $token")
            }
            return if (isFloat) {
                token.toDouble()
            } else {
                token.toLongOrNull() ?: token.toDouble()
            }
        }

        private fun expectWord(word: String) {
            if (!s.startsWith(word, pos)) {
                throw IllegalArgumentException("位置 $pos 处期望 '$word'")
            }
            pos += word.length
        }

        fun skipWsAndComments() {
            while (!eof()) {
                val c = s[pos]
                when {
                    c.isWhitespace() || c == '\uFEFF' -> pos++
                    s.startsWith("//", pos) -> {
                        pos += 2
                        while (!eof() && s[pos] != '\n' && s[pos] != '\r') pos++
                    }
                    s.startsWith("/*", pos) -> {
                        pos += 2
                        val end = s.indexOf("*/", pos)
                        if (end < 0) throw IllegalArgumentException("多行注释未闭合")
                        pos = end + 2
                    }
                    else -> return
                }
            }
        }

        private fun Char.isHexDigit(): Boolean =
            this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

        private fun eof(): Boolean = pos >= s.length
        private fun peek(): Char = s[pos]
    }
}
