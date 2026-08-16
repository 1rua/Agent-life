package com.agentlife.calls

import com.agentlife.capability.callEndEpochMs
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Closed byte parser for the frozen call-record v1 grammar; it accepts no reordered or surplus fields. */
class StrictCallLogWireDecoder(private val wire: ByteArray) {
    private var index = 0

    fun decode(): DecodedCallLogRecord {
        expectByte('{'.code)
        expectAsciiName("kind"); expectByte(':'.code); expectString("upsert"); expectComma()
        expectAsciiName("record_id"); expectByte(':'.code); val recordId = readJsonString(); expectComma()
        expectAsciiName("source_epoch"); expectByte(':'.code); val sourceEpoch = readCanonicalULong(); requireFormat(sourceEpoch > 0u); expectComma()
        expectAsciiName("record_revision"); expectByte(':'.code); expectString("1"); expectComma()
        expectAsciiName("cursor_started_at_epoch_ms"); expectByte(':'.code); val cursorStarted = readCanonicalNonNegativeLong(); expectComma()
        expectAsciiName("cursor_provider_id"); expectByte(':'.code); val cursorProvider = readCanonicalPositiveLong(); expectComma()
        expectAsciiName("captured_at_epoch_ms"); expectByte(':'.code); val capturedAt = readCanonicalNonNegativeLong(); expectComma()
        expectAsciiName("capture_revision"); expectByte(':'.code); val captureRevision = readCanonicalULong(); expectComma()
        expectAsciiName("policy_revision"); expectByte(':'.code); val policyRevision = readCanonicalULong(); expectComma()
        expectAsciiName("metadata"); expectByte(':'.code); expectByte('{'.code)
        expectAsciiName("direction"); expectByte(':'.code); val direction = readJsonString(); requireFormat(direction in DIRECTIONS); expectComma()
        expectAsciiName("started_at_epoch_ms"); expectByte(':'.code); val started = readCanonicalNonNegativeLong(); expectComma()
        expectAsciiName("ended_at_epoch_ms"); expectByte(':'.code); val ended = readCanonicalNonNegativeLong(); expectComma()
        expectAsciiName("duration_seconds"); expectByte(':'.code); val duration = readCanonicalNonNegativeLong(); expectComma()
        expectAsciiName("observed_at_epoch_ms"); expectByte(':'.code); val observed = readCanonicalNonNegativeLong(); expectComma()
        expectAsciiName("number_presentation"); expectByte(':'.code); val presentation = readJsonString(); requireFormat(presentation in PRESENTATIONS)
        expectObjectEnd(); expectComma()
        expectAsciiName("counterparty_number"); expectByte(':'.code); readCounterparty(presentation)
        expectObjectEnd(); expectDocumentEnd()

        requireFormat(recordId == "call:$cursorProvider")
        requireFormat(cursorStarted == started && capturedAt == observed && captureRevision == policyRevision)
        try { requireFormat(ended == callEndEpochMs(started, duration)) } catch (_: ArithmeticException) { throw CallLogWireFormatException() }
        return DecodedCallLogRecord(recordId, sourceEpoch, CallLogCursor(cursorStarted, cursorProvider), captureRevision, policyRevision)
    }

    private fun readCounterparty(presentation: String) {
        expectByte('{'.code); expectAsciiName("state"); expectByte(':'.code)
        when (readJsonString()) {
            "withheld" -> expectObjectEnd()
            "released" -> {
                requireFormat(presentation == "allowed"); expectComma(); expectAsciiName("value"); expectByte(':'.code)
                val value = readJsonString(); requireFormat(value.toByteArray(Charsets.UTF_8).size in 1..256); expectObjectEnd()
            }
            else -> throw CallLogWireFormatException()
        }
    }

    fun expectByte(expected: Int) { if (index >= wire.size || wire[index++].toInt() and 0xff != expected) throw CallLogWireFormatException() }
    fun expectAsciiName(name: String) { expectByte('"'.code); name.forEach { expectByte(it.code) }; expectByte('"'.code) }
    fun expectComma() = expectByte(','.code)
    fun expectObjectEnd() = expectByte('}'.code)
    fun expectDocumentEnd() { if (index != wire.size) throw CallLogWireFormatException() }

    fun readJsonString(): String {
        expectByte('"'.code)
        val result = StringBuilder(); val raw = ByteArrayOutputStream()
        fun flushRaw() {
            if (raw.size() == 0) return
            val decoded = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw.toByteArray())).toString()
            } catch (_: CharacterCodingException) { throw CallLogWireFormatException() }
            requireValidUnicodeScalars(decoded)
            result.append(decoded); raw.reset()
        }
        while (index < wire.size) {
            val current = wire[index++].toInt() and 0xff
            when (current) {
                '"'.code -> { flushRaw(); return result.toString() }
                '\\'.code -> {
                    flushRaw(); if (index >= wire.size) throw CallLogWireFormatException()
                    when (val escape = wire[index++].toInt() and 0xff) {
                        '"'.code, '\\'.code, '/'.code -> result.append(escape.toChar())
                        'b'.code -> result.append('\b'); 'f'.code -> result.append('\u000C'); 'n'.code -> result.append('\n')
                        'r'.code -> result.append('\r'); 't'.code -> result.append('\t')
                        'u'.code -> result.appendCodePoint(readEscapedCodePoint())
                        else -> throw CallLogWireFormatException()
                    }
                }
                in 0..0x1f -> throw CallLogWireFormatException()
                else -> raw.write(current)
            }
        }
        throw CallLogWireFormatException()
    }

    fun readCanonicalULong(): ULong {
        expectByte('"'.code); val start = index
        while (index < wire.size && wire[index].toInt() and 0xff != '"'.code) {
            val byte = wire[index++].toInt() and 0xff; if (byte !in '0'.code..'9'.code) throw CallLogWireFormatException()
        }
        if (index == start || index >= wire.size) throw CallLogWireFormatException()
        val decimal = wire.copyOfRange(start, index).toString(Charsets.US_ASCII); expectByte('"'.code)
        requireFormat(decimal == "0" || decimal.first() != '0')
        return decimal.toULongOrNull() ?: throw CallLogWireFormatException()
    }

    fun readCanonicalPositiveLong(): Long {
        val value = readCanonicalULong(); requireFormat(value in 1uL..Long.MAX_VALUE.toULong()); return value.toLong()
    }
    private fun readCanonicalNonNegativeLong(): Long {
        val value = readCanonicalULong(); requireFormat(value <= Long.MAX_VALUE.toULong()); return value.toLong()
    }
    private fun expectString(expected: String) { requireFormat(readJsonString() == expected) }
    private fun readEscapedCodePoint(): Int {
        val high = readFourHex(); if (high !in 0xD800..0xDBFF) { requireFormat(high !in 0xDC00..0xDFFF); return high }
        expectByte('\\'.code); expectByte('u'.code); val low = readFourHex(); requireFormat(low in 0xDC00..0xDFFF)
        return 0x10000 + (high - 0xD800) * 0x400 + low - 0xDC00
    }
    private fun readFourHex(): Int {
        var value = 0; repeat(4) {
            if (index >= wire.size) throw CallLogWireFormatException(); val byte = wire[index++].toInt() and 0xff
            value = value * 16 + when (byte) { in '0'.code..'9'.code -> byte - '0'.code; in 'a'.code..'f'.code -> byte - 'a'.code + 10; in 'A'.code..'F'.code -> byte - 'A'.code + 10; else -> throw CallLogWireFormatException() }
        }; return value
    }
    private fun requireFormat(condition: Boolean) { if (!condition) throw CallLogWireFormatException() }
    private fun requireValidUnicodeScalars(value: String) {
        var position = 0
        while (position < value.length) {
            val character = value[position]
            if (character.isHighSurrogate()) {
                requireFormat(position + 1 < value.length && value[position + 1].isLowSurrogate())
                position += 2
            } else {
                requireFormat(!character.isLowSurrogate())
                position += 1
            }
        }
    }

    private companion object {
        val DIRECTIONS = setOf("incoming", "outgoing", "missed", "rejected")
        val PRESENTATIONS = setOf("allowed", "restricted", "unknown", "payphone", "unavailable")
    }
}
