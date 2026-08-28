package com.agentlife.gateway.events

/**
 * One SSE frame, emitted only once its terminating blank line has arrived.
 */
data class GatewayEvent(
    val id: String?,
    val event: String?,
    val data: String,
) {
    /**
     * A cancel notice records an intent; it is not an outcome. Only a trusted
     * result decides `cancelled`, another real terminal state, or
     * `outcome_unknown`.
     */
    val isTerminalDeviceRequestOutcome: Boolean
        get() = event in TERMINAL_DEVICE_REQUEST_EVENTS

    private companion object {
        val TERMINAL_DEVICE_REQUEST_EVENTS = setOf(
            "device.request.succeeded",
            "device.request.failed",
            "device.request.denied",
            "device.request.cancelled",
            "device.request.outcome_unknown",
        )
    }
}

/**
 * Byte-oriented SSE parser.
 *
 * The stream is framed by a blank line, not by lines: a chunk boundary can fall
 * anywhere, including in the middle of a UTF-8 character, so bytes are buffered
 * until a complete frame terminator is seen. Nothing is emitted or committed
 * before then.
 */
class SseParser(private val onEvent: (GatewayEvent) -> Unit = {}) {

    private val buffer = java.io.ByteArrayOutputStream()
    private var cursor: String? = null
    private var eventName: String? = null
    private val dataLines = mutableListOf<String>()

    fun feedBytes(chunk: ByteArray): List<GatewayEvent> {
        buffer.write(chunk, 0, chunk.size)
        return drain()
    }

    fun feed(chunk: String): List<GatewayEvent> = feedBytes(chunk.toByteArray(Charsets.UTF_8))

    fun reset() {
        buffer.reset()
        cursor = null
        eventName = null
        dataLines.clear()
    }

    private fun drain(): List<GatewayEvent> {
        val emitted = mutableListOf<GatewayEvent>()
        while (true) {
            val buffered = buffer.toByteArray()
            val terminator = findTerminator(buffered) ?: break

            // Decode only up to the terminator; trailing bytes stay buffered.
            val frame = String(buffered, 0, terminator.start, Charsets.UTF_8)
            val consumed = terminator.endExclusive
            buffer.reset()
            buffer.write(buffered, consumed, buffered.size - consumed)

            parseFrame(frame)?.let { event ->
                onEvent(event)
                emitted += event
            }
        }
        return emitted
    }

    /**
     * A frame ends at the first blank line. Both LF and CRLF are accepted, and
     * a lone CR is not treated as a terminator.
     */
    private fun findTerminator(bytes: ByteArray): Terminator? {
        var index = 0
        while (index < bytes.size) {
            when (bytes[index]) {
                '\n'.code.toByte() -> {
                    val next = index + 1
                    val isBlankLine = next >= bytes.size || bytes[next] == '\n'.code.toByte() ||
                        (bytes[next] == '\r'.code.toByte() && next + 1 < bytes.size && bytes[next + 1] == '\n'.code.toByte())
                    if (isBlankLine) {
                        return Terminator(index, if (bytes[next] == '\r'.code.toByte()) next + 2 else next + 1)
                    }
                    index = next
                }
                else -> index += 1
            }
        }
        return null
    }

    private fun parseFrame(frame: String): GatewayEvent? {
        resetFrameState()
        for (rawLine in frame.split('\n')) {
            val line = rawLine.removeSuffix("\r")
            if (line.isEmpty()) continue
            if (line.startsWith(":")) continue

            val separator = line.indexOf(':')
            val field = if (separator == -1) line else line.substring(0, separator)
            var value = if (separator == -1) "" else line.substring(separator + 1)
            if (value.startsWith(" ")) value = value.substring(1)

            when (field) {
                "id" -> cursor = value
                "event" -> eventName = value
                "data" -> dataLines += value
            }
        }

        // A frame with no data line carries no event, but still resets state.
        if (dataLines.isEmpty()) return null

        val event = GatewayEvent(
            id = cursor,
            event = eventName,
            data = dataLines.joinToString("\n"),
        )
        resetFrameState()
        return event
    }

    /**
     * The cursor belongs to the frame that carried it. An event with no `id`
     * line advances nothing, so a later frame must not inherit an earlier id.
     */
    private fun resetFrameState() {
        cursor = null
        eventName = null
        dataLines.clear()
    }

    private class Terminator(val start: Int, val endExclusive: Int)
}
