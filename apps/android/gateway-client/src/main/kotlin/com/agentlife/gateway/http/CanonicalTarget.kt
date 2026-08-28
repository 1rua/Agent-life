package com.agentlife.gateway.http

/**
 * Canonical origin-form request target, ported from
 * `gateway-contract/src/request-signature.ts`.
 *
 * The signature must cover the exact bytes put on the wire, so this returns the
 * canonical form only when the input already *is* canonical; anything else is
 * rejected rather than silently rewritten.
 *
 * Error taxonomy note: the TypeScript contract separates `SCHEMA_INVALID` from
 * `NON_CANONICAL_TARGET`. On Android every rejection here means "the target you
 * are about to sign is not the canonical one", so all of them surface as
 * `NON_CANONICAL_TARGET`.
 */
object CanonicalTarget {

    private const val NON_CANONICAL = "NON_CANONICAL_TARGET"
    private val TARGET_PREFIX = "/agent-life/v2"
    private val UNRESERVED = (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('.', '_', '~', '-')).toSet()

    fun canonicalize(input: String): String = try {
        val canonical = canonicalizeInternal(input)
        if (canonical != input) throw NonCanonical("rewriting would change the signed bytes")
        canonical
    } catch (cause: NonCanonical) {
        throw IllegalArgumentException("$NON_CANONICAL:$input (${cause.message})")
    }

    private fun canonicalizeInternal(rawTarget: String): String {
        if (rawTarget.isEmpty()) fail("empty target")
        assertTargetCharacters(rawTarget)

        val querySeparator = rawTarget.indexOf('?')
        val path = if (querySeparator == -1) rawTarget else rawTarget.substring(0, querySeparator)
        val query = if (querySeparator == -1) null else rawTarget.substring(querySeparator + 1)
        val canonicalPath = canonicalPath(path)
        return if (query == null) canonicalPath else "$canonicalPath?${canonicalQuery(query)}"
    }

    private fun assertTargetCharacters(target: String) {
        for (char in target) {
            if (char.code <= 0x20 || char.code > 0x7f || char.code == 0x7f) fail("illegal character")
            if (char == '#') fail("fragment not allowed")
        }
    }

    private fun canonicalPath(path: String): String {
        if (!path.startsWith("/") || path.contains("//")) fail("malformed path")
        val segments = path.split("/")
        val canonicalSegments = segments.mapIndexed { index, segment ->
            if (segment.isEmpty() && index != 0 && index != segments.size - 1) fail("empty segment")

            val decoded = decodeComponent(segment)
            if (decoded.isEmpty()) return@mapIndexed ""
            if (decoded.size == 1 && decoded[0] == '.'.code.toByte()) fail("dot segment")
            if (decoded.size == 2 && decoded[0] == '.'.code.toByte() && decoded[1] == '.'.code.toByte()) {
                fail("dot-dot segment")
            }
            if (decoded.any { it == '/'.code.toByte() || it == '\\'.code.toByte() }) fail("separator in segment")
            decoded.joinToString("") { byte ->
                val char = (byte.toInt() and 0xff).toChar()
                if (char in UNRESERVED) char.toString() else "%%%02X".format(byte.toInt() and 0xff)
            }
        }

        val result = canonicalSegments.joinToString("/")
        if (result != TARGET_PREFIX && result != "$TARGET_PREFIX/" && !result.startsWith("$TARGET_PREFIX/")) {
            fail("outside the v2 namespace")
        }
        if (result.endsWith("/") && result != "$TARGET_PREFIX/") fail("trailing separator")
        return result
    }

    private fun canonicalQuery(query: String): String {
        if (query.contains("?")) fail("nested query")
        val pairs = query.split("&").mapIndexed { index, pair ->
            if (pair.isEmpty()) fail("empty query pair")
            val separator = pair.indexOf('=')
            val rawName = if (separator == -1) pair else pair.substring(0, separator)
            val rawValue = if (separator == -1) "" else pair.substring(separator + 1)
            if (rawName.isEmpty()) fail("empty query name")
            QueryPair(encodeComponent(rawName), encodeComponent(rawValue), index)
        }
        val sorted = pairs.sortedWith { left, right ->
            val byName = compareAscii(left.name, right.name)
            if (byName != 0) byName
            else {
                val byValue = compareAscii(left.value, right.value)
                if (byValue != 0) byValue else left.index - right.index
            }
        }
        return sorted.joinToString("&") { "${it.name}=${it.value}" }
    }

    private fun compareAscii(left: String, right: String): Int {
        val limit = minOf(left.length, right.length)
        for (index in 0 until limit) {
            val delta = left[index].code - right[index].code
            if (delta != 0) return delta
        }
        return left.length - right.length
    }

    private fun decodeComponent(component: String): List<Byte> {
        val bytes = mutableListOf<Byte>()
        var index = 0
        while (index < component.length) {
            val char = component[index]
            if (char == '%') {
                val high = component.getOrNull(index + 1)
                val low = component.getOrNull(index + 2)
                if (high == null || low == null) fail("truncated percent escape")
                bytes += ((hexValue(high) shl 4) or hexValue(low)).toByte()
                index += 3
                continue
            }
            if (char.code > 0x7f) fail("non-ascii byte")
            bytes += char.code.toByte()
            index += 1
        }
        return bytes
    }

    private fun encodeComponent(component: String): String {
        val bytes = decodeComponent(component)
        return bytes.joinToString("") { byte ->
            val char = (byte.toInt() and 0xff).toChar()
            if (char in UNRESERVED) char.toString() else "%%%02X".format(byte.toInt() and 0xff)
        }
    }

    private fun hexValue(char: Char): Int {
        val value = Character.digit(char, 16)
        if (value == -1) fail("bad hex digit")
        val upper = char.uppercaseChar()
        if (char != upper && char !in '0'..'9') fail("lowercase hex is not canonical")
        return value
    }

    private fun fail(reason: String): Nothing = throw NonCanonical(reason)

    private data class QueryPair(val name: String, val value: String, val index: Int)

    private class NonCanonical(message: String) : Exception(message)
}
