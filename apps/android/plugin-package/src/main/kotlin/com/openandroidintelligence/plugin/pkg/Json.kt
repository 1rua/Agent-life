package com.openandroidintelligence.plugin.pkg

/**
 * Minimal JSON reader.
 *
 * The platform `org.json` is replaced by a throwing stub in local JVM unit
 * tests, so package parsing cannot depend on it.
 */
internal sealed interface JsonValue {
    data class JObject(val fields: List<Pair<String, JsonValue>>) : JsonValue {
        fun get(name: String): JsonValue? = fields.firstOrNull { it.first == name }?.second
    }

    data class JArray(val items: List<JsonValue>) : JsonValue
    data class JString(val value: String) : JsonValue
    data class JNumber(val raw: String) : JsonValue {
        fun asLong(): Long? = raw.toLongOrNull()
    }

    data class JBoolean(val value: Boolean) : JsonValue
    object JNull : JsonValue
}

internal object Json {
    fun parse(text: String): JsonValue {
        val cursor = Cursor(text)
        val value = cursor.readValue()
        cursor.skipWhitespace()
        check(cursor.isAtEnd()) { "JSON_INVALID:trailingContent" }
        return value
    }

    private class Cursor(private val text: String) {
        var index = 0

        fun isAtEnd(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        private fun peek(): Char {
            check(index < text.length) { "JSON_INVALID:unexpectedEnd" }
            return text[index]
        }

        private fun expect(char: Char) {
            check(peek() == char) { "JSON_INVALID:expected:$char" }
            index++
        }

        fun readValue(): JsonValue {
            skipWhitespace()
            return when (peek()) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonValue.JString(readString())
                't' -> { expectLiteral("true"); JsonValue.JBoolean(true) }
                'f' -> { expectLiteral("false"); JsonValue.JBoolean(false) }
                'n' -> { expectLiteral("null"); JsonValue.JNull }
                else -> readNumber()
            }
        }

        private fun readObject(): JsonValue {
            expect('{')
            val fields = mutableListOf<Pair<String, JsonValue>>()
            skipWhitespace()
            if (peek() == '}') { index++; return JsonValue.JObject(fields) }
            while (true) {
                skipWhitespace()
                val name = readString()
                skipWhitespace()
                expect(':')
                val value = readValue()
                fields += name to value
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> { index++; return JsonValue.JObject(fields) }
                    else -> error("JSON_INVALID:objectSeparator")
                }
            }
        }

        private fun readArray(): JsonValue {
            expect('[')
            val items = mutableListOf<JsonValue>()
            skipWhitespace()
            if (peek() == ']') { index++; return JsonValue.JArray(items) }
            while (true) {
                items += readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> { index++; return JsonValue.JArray(items) }
                    else -> error("JSON_INVALID:arraySeparator")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (index >= text.length) error("JSON_INVALID:unterminatedString")
                when (val char = text[index++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (index >= text.length) error("JSON_INVALID:badEscape")
                        when (val escaped = text[index++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                check(index + 4 <= text.length) { "JSON_INVALID:badUnicode" }
                                out.append(text.substring(index, index + 4).toInt(16).toChar())
                                index += 4
                            }
                            else -> error("JSON_INVALID:badEscape")
                        }
                    }
                    else -> out.append(char)
                }
            }
        }

        private fun readNumber(): JsonValue {
            val start = index
            if (peek() == '-') index++
            while (index < text.length && text[index].isDigit()) index++
            if (index < text.length && text[index] == '.') {
                index++
                while (index < text.length && text[index].isDigit()) index++
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
                while (index < text.length && text[index].isDigit()) index++
            }
            check(index > start) { "JSON_INVALID:number" }
            return JsonValue.JNumber(text.substring(start, index))
        }

        private fun expectLiteral(literal: String) {
            check(text.startsWith(literal, index)) { "JSON_INVALID:literal" }
            index += literal.length
        }
    }
}
