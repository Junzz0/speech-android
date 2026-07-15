package audio.soniqo.speech.llm

/**
 * Kotlin port of the Python export parser's
 * `function_calls.parse_function_calls`. Pure parsing — no I/O, no native
 * dependencies — so it is safe to call from any thread.
 */
object FunctionGemmaParser {

    fun parseFunctionCalls(text: String): List<FunctionCall> {
        val result = mutableListOf<FunctionCall>()
        val start = FunctionGemmaPrompt.FUNCTION_CALL_START
        val end = FunctionGemmaPrompt.FUNCTION_CALL_END
        var cursor = 0
        while (true) {
            val s = text.indexOf(start, cursor)
            if (s < 0) break
            val e = text.indexOf(end, s + start.length)
            if (e < 0) break
            val body = text.substring(s + start.length, e)
            parseCallBody(body)?.let { result += it }
            cursor = e + end.length
        }
        return result
    }

    private fun parseCallBody(rawBody: String): FunctionCall? {
        // Models (and runtimes) may emit a newline between the marker and the
        // call body — tolerate surrounding whitespace before matching. The
        // `call:` prefix from the training format is optional: the released
        // FunctionGemma-270M weights emit `NAME {args}` without it (verified
        // against the LiteRT-LM runtime).
        val body = rawBody.trim()
        val afterCall = body.removePrefix("call:")
        val brace = afterCall.indexOf('{')
        if (brace < 0) return null
        val name = afterCall.substring(0, brace).trim()
        if (name.isEmpty() || name.any { it.isWhitespace() }) return null
        val parser = Parser(afterCall.substring(brace))
        val value = parser.parseValue()
        if (value !is ArgumentValue.Object) return null
        return FunctionCall(name, value.fields)
    }

    private class Parser(private val input: String) {
        private var pos = 0

        fun parseValue(): ArgumentValue {
            skipSpace()
            if (pos >= input.length) return ArgumentValue.Null
            return when (input[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '<' -> if (matchLiteral(FunctionGemmaPrompt.ESCAPE))
                    ArgumentValue.Str(parseEscapedString())
                else ArgumentValue.Null
                't', 'f' -> when {
                    matchLiteral("true")  -> ArgumentValue.Bool(true)
                    matchLiteral("false") -> ArgumentValue.Bool(false)
                    else -> ArgumentValue.Null
                }
                'n' -> if (matchLiteral("null")) ArgumentValue.Null else ArgumentValue.Null
                else -> parseNumber()
            }
        }

        private fun parseObject(): ArgumentValue {
            val out = linkedMapOf<String, ArgumentValue>()
            if (pos >= input.length || input[pos] != '{') return ArgumentValue.Object(out)
            pos++
            skipSpace()
            if (pos < input.length && input[pos] == '}') { pos++; return ArgumentValue.Object(out) }
            while (pos < input.length) {
                skipSpace()
                val key = parseKey()
                skipSpace()
                if (pos < input.length && input[pos] == ':') pos++
                val v = parseValue()
                out[key] = v
                skipSpace()
                if (pos < input.length && input[pos] == ',') { pos++; continue }
                if (pos < input.length && input[pos] == '}') { pos++; break }
                break
            }
            return ArgumentValue.Object(out)
        }

        private fun parseArray(): ArgumentValue {
            val out = mutableListOf<ArgumentValue>()
            if (pos >= input.length || input[pos] != '[') return ArgumentValue.Array(out)
            pos++
            skipSpace()
            if (pos < input.length && input[pos] == ']') { pos++; return ArgumentValue.Array(out) }
            while (pos < input.length) {
                out += parseValue()
                skipSpace()
                if (pos < input.length && input[pos] == ',') { pos++; continue }
                if (pos < input.length && input[pos] == ']') { pos++; break }
                break
            }
            return ArgumentValue.Array(out)
        }

        private fun parseKey(): String {
            val sb = StringBuilder()
            while (pos < input.length) {
                val c = input[pos]
                if (c == ':' || c == ',' || c == '}') break
                sb.append(c); pos++
            }
            return sb.toString().trim()
        }

        private fun parseEscapedString(): String {
            val esc = FunctionGemmaPrompt.ESCAPE
            val sb = StringBuilder()
            while (pos < input.length) {
                if (matchLiteral(esc)) return sb.toString()
                sb.append(input[pos]); pos++
            }
            return sb.toString()
        }

        private fun parseNumber(): ArgumentValue {
            val sb = StringBuilder()
            var sawDot = false
            while (pos < input.length) {
                val c = input[pos]
                when {
                    c.isDigit() || c == '-' || c == '+' -> { sb.append(c); pos++ }
                    c == '.'                              -> { sawDot = true; sb.append(c); pos++ }
                    c == 'e' || c == 'E'                  -> { sawDot = true; sb.append(c); pos++ }
                    else -> return finalizeNumber(sb.toString(), sawDot)
                }
            }
            return finalizeNumber(sb.toString(), sawDot)
        }

        private fun finalizeNumber(raw: String, sawDot: Boolean): ArgumentValue {
            if (raw.isEmpty()) return ArgumentValue.Null
            if (sawDot) raw.toDoubleOrNull()?.let { return ArgumentValue.Double(it) }
            raw.toLongOrNull()?.let { return ArgumentValue.Int(it) }
            raw.toDoubleOrNull()?.let { return ArgumentValue.Double(it) }
            return ArgumentValue.Str(raw)
        }

        private fun skipSpace() {
            while (pos < input.length && input[pos].isWhitespace()) pos++
        }

        private fun matchLiteral(literal: String): Boolean {
            if (pos + literal.length > input.length) return false
            for (i in literal.indices) {
                if (input[pos + i] != literal[i]) return false
            }
            pos += literal.length
            return true
        }
    }
}
