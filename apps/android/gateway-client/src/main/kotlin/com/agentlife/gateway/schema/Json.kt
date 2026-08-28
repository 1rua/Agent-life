package com.agentlife.gateway.schema

import java.security.MessageDigest

/**
 * Minimal JSON model plus an RFC 8785 (JCS) canonical serializer.
 *
 * Self-contained on purpose: the platform `org.json` is replaced by a stubbed
 * throw-on-use jar in local JVM unit tests, and the canonical digests this
 * package produces must be computable in both environments.
 */
sealed class JsonValue {
    data class JObject(val fields: List<Pair<String, JsonValue>>) : JsonValue()
    data class JArray(val items: List<JsonValue>) : JsonValue()
    data class JString(val value: String) : JsonValue()
    data class JNumber(val raw: String) : JsonValue()
    data class JBool(val value: Boolean) : JsonValue()
    object JNull : JsonValue()
}

class JsonError(message: String) : IllegalArgumentException(message)

object Json {

    fun parse(text: String): JsonValue {
        val cursor = Cursor(text)
        cursor.skipWhitespace()
        val value = cursor.readValue()
        cursor.skipWhitespace()
        if (!cursor.atEnd()) throw JsonError("trailing content at offset ${cursor.position}")
        return value
    }

    /** RFC 8785 canonical form: sorted keys, minimal escaping, no whitespace. */
    fun canonical(value: JsonValue): String = buildString { writeCanonical(value, this) }

    fun sha256(value: JsonValue): String {
        val bytes = canonical(value).toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    fun of(value: Any?): JsonValue = when (value) {
        null -> JsonValue.JNull
        is JsonValue -> value
        is Boolean -> JsonValue.JBool(value)
        is String -> JsonValue.JString(value)
        is Int -> JsonValue.JNumber(value.toString())
        is Long -> JsonValue.JNumber(value.toString())
        is Double -> JsonValue.JNumber(ecmascriptNumber(value))
        is Float -> JsonValue.JNumber(ecmascriptNumber(value.toDouble()))
        is Map<*, *> -> JsonValue.JObject(value.entries.map { (k, v) -> k.toString() to of(v) })
        is Iterable<*> -> JsonValue.JArray(value.map { of(it) })
        is Array<*> -> JsonValue.JArray(value.map { of(it) })
        else -> throw JsonError("unsupported value type ${value::class.java.name}")
    }

    private fun writeCanonical(value: JsonValue, out: StringBuilder) {
        when (value) {
            is JsonValue.JNull -> out.append("null")
            is JsonValue.JBool -> out.append(if (value.value) "true" else "false")
            is JsonValue.JNumber -> out.append(ecmascriptNumber(value.raw))
            is JsonValue.JString -> writeString(value.value, out)
            is JsonValue.JArray -> {
                out.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    writeCanonical(item, out)
                }
                out.append(']')
            }
            is JsonValue.JObject -> {
                out.append('{')
                value.fields.sortedWith { left, right -> compareUtf16(left.first, right.first) }
                    .forEachIndexed { index, (key, item) ->
                        if (index > 0) out.append(',')
                        writeString(key, out)
                        out.append(':')
                        writeCanonical(item, out)
                    }
                out.append('}')
            }
        }
    }

    private fun writeString(value: String, out: StringBuilder) {
        out.append('"')
        for (char in value) {
            when {
                char == '"' -> out.append("\\\"")
                char == '\\' -> out.append("\\\\")
                char == '\b' -> out.append("\\b")
                char == '\u000C' -> out.append("\\f")
                char == '\n' -> out.append("\\n")
                char == '\r' -> out.append("\\r")
                char == '\t' -> out.append("\\t")
                char.code < 0x20 -> out.append("\\u%04x".format(char.code))
                else -> out.append(char)
            }
        }
        out.append('"')
    }

    /** Keys sort by UTF-16 code unit, which is what JCS requires. */
    private fun compareUtf16(left: String, right: String): Int {
        val limit = minOf(left.length, right.length)
        for (index in 0 until limit) {
            val delta = left[index].code - right[index].code
            if (delta != 0) return delta
        }
        return left.length - right.length
    }

    /**
     * ECMAScript Number::toString. Integers in the safe range print without a
     * fractional part, which is what every digest in the contract relies on.
     */
    internal fun ecmascriptNumber(raw: String): String {
        val value = raw.toDoubleOrNull() ?: throw JsonError("malformed number $raw")
        return ecmascriptNumber(value)
    }

    internal fun ecmascriptNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) throw JsonError("non-finite number")
        if (value == Math.floor(value) && !value.isInfinite() && Math.abs(value) < 1e21) {
            return value.toLong().toString()
        }
        var text = value.toString()
        if (text.contains('E')) {
            val (base, exponent) = text.split('E')
            val digits = base.replace(".", "").trimEnd('0').trimStart('-')
            val sign = if (base.startsWith("-")) "-" else ""
            val exp = exponent.toInt()
            text = sign + digits + "e" + (if (exp >= 0) "+" else "-") + Math.abs(exp)
        }
        return text
    }

    private class Cursor(private val text: String) {
        var position = 0
            private set

        fun atEnd(): Boolean = position >= text.length

        fun skipWhitespace() {
            while (position < text.length && text[position].isJsonWhitespace()) position += 1
        }

        private fun peek(): Char =
            text.getOrNull(position) ?: throw JsonError("unexpected end of input")

        private fun expect(char: Char) {
            if (peek() != char) throw JsonError("expected '$char' at offset $position")
            position += 1
        }

        fun readValue(): JsonValue {
            return when (peek()) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonValue.JString(readString())
                't' -> { readLiteral("true"); JsonValue.JBool(true) }
                'f' -> { readLiteral("false"); JsonValue.JBool(false) }
                'n' -> { readLiteral("null"); JsonValue.JNull }
                else -> readNumber()
            }
        }

        private fun readObject(): JsonValue.JObject {
            expect('{')
            val fields = mutableListOf<Pair<String, JsonValue>>()
            skipWhitespace()
            if (peek() == '}') { position += 1; return JsonValue.JObject(fields) }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                fields += key to readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> position += 1
                    '}' -> { position += 1; return JsonValue.JObject(fields) }
                    else -> throw JsonError("malformed object at offset $position")
                }
            }
        }

        private fun readArray(): JsonValue.JArray {
            expect('[')
            val items = mutableListOf<JsonValue>()
            skipWhitespace()
            if (peek() == ']') { position += 1; return JsonValue.JArray(items) }
            while (true) {
                skipWhitespace()
                items += readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> position += 1
                    ']' -> { position += 1; return JsonValue.JArray(items) }
                    else -> throw JsonError("malformed array at offset $position")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonError("unterminated string")
                val char = text[position]
                position += 1
                when {
                    char == '"' -> return out.toString()
                    char == '\\' -> out.append(readEscape())
                    char.code < 0x20 -> throw JsonError("control character in string")
                    else -> out.append(char)
                }
            }
        }

        private fun readEscape(): Char {
            if (atEnd()) throw JsonError("unterminated escape")
            val char = text[position]
            position += 1
            return when (char) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    val hex = text.substring(position, position + 4)
                    position += 4
                    hex.toIntOrNull(16)?.toChar() ?: throw JsonError("bad unicode escape")
                }
                else -> throw JsonError("bad escape \\$char")
            }
        }

        private fun readNumber(): JsonValue.JNumber {
            val start = position
            if (peek() == '-') position += 1
            while (position < text.length && text[position].isNumberChar()) position += 1
            val raw = text.substring(start, position)
            if (raw.isEmpty() || raw == "-") throw JsonError("malformed number at offset $start")
            return JsonValue.JNumber(raw)
        }

        private fun readLiteral(literal: String) {
            if (!text.startsWith(literal, position)) throw JsonError("malformed literal at offset $position")
            position += literal.length
        }

        private fun Char.isJsonWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r'

        private fun Char.isNumberChar(): Boolean =
            this in '0'..'9' || this == '.' || this == 'e' || this == 'E' || this == '+' || this == '-'
    }
}
