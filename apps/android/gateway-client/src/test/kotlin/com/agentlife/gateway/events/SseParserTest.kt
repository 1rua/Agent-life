package com.agentlife.gateway.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSE is byte framing, not line framing: an event is only real once its
 * terminating blank line arrives. A chunk boundary may fall anywhere, including
 * inside a UTF-8 character, so nothing may be committed before the terminator.
 */
class SseParserTest {

    private fun parser(cursorStore: EventCursorStore = InMemoryEventCursorStore(), accountId: String = "acct-1"): SseParser =
        SseParser { event -> event.id?.let { cursorStore.save(accountId, it) } }

    @Test
    fun resumesAfterLastCompleteEventOnly() {
        val cursorStore = InMemoryEventCursorStore()
        val parser = parser(cursorStore)

        parser.feed("id: e1\nevent: gateway.notice\ndata: {}\n\nid: e2\ndata:")

        assertEquals("e1", cursorStore.load("acct-1"))
    }

    @Test
    fun incompleteEventIsNotEmitted() {
        val parser = parser()
        val events = parser.feed("id: e1\nevent: gateway.notice\ndata: hel")
        assertTrue(events.isEmpty())
    }

    @Test
    fun eventArrivesWhenTerminatorCompletesTheFrame() {
        val parser = parser()
        parser.feed("id: e1\nevent: gateway.notice\ndata: hel")
        val events = parser.feed("lo\n\n")

        assertEquals(1, events.size)
        assertEquals("gateway.notice", events[0].event)
        assertEquals("hello", events[0].data)
        assertEquals("e1", events[0].id)
    }

    @Test
    fun multiChunkSplitInsideUtf8CharacterStillReassembles() {
        val parser = parser()
        val text = "héllo"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val splitAt = bytes.indexOf(0xC3.toByte())

        parser.feed("id: e1\nevent: gateway.notice\ndata: ")
        parser.feedBytes(bytes.copyOfRange(0, splitAt))
        val events = parser.feedBytes(bytes.copyOfRange(splitAt, bytes.size) + "\n\n".toByteArray())

        assertEquals(1, events.size)
        assertEquals(text, events[0].data)
    }

    @Test
    fun carriageReturnLineFeedIsAccepted() {
        val parser = parser()
        val events = parser.feed("id: e1\r\nevent: gateway.notice\r\ndata: {}\r\n\r\n")

        assertEquals(1, events.size)
        assertEquals("{}", events[0].data)
    }

    @Test
    fun eventWithoutIdDoesNotAdvanceCursor() {
        val cursorStore = InMemoryEventCursorStore()
        val parser = parser(cursorStore)

        parser.feed("event: gateway.notice\ndata: {}\n\n")

        assertEquals(null, cursorStore.load("acct-1"))
    }

    @Test
    fun multipleDataLinesJoinWithNewline() {
        val parser = parser()
        val events = parser.feed("id: e1\nevent: gateway.notice\ndata: one\ndata: two\n\n")

        assertEquals("one\ntwo", events[0].data)
    }

    @Test
    fun cancelRequestedIsAnIntentNotATerminalState() {
        val parser = parser()
        val events = parser.feed(
            "id: e1\nevent: device.request.cancel.requested\ndata: {\"requestId\":\"r1\"}\n\n",
        )

        assertEquals(1, events.size)
        assertEquals("device.request.cancel.requested", events[0].event)
        assertEquals(false, events[0].isTerminalDeviceRequestOutcome)
    }

    @Test
    fun cursorAdvancesToNewestCompleteEvent() {
        val cursorStore = InMemoryEventCursorStore()
        val parser = parser(cursorStore)

        parser.feed("id: e1\nevent: a\ndata: {}\n\nid: e2\nevent: b\ndata: {}\n\n")

        assertEquals("e2", cursorStore.load("acct-1"))
    }

    @Test
    fun commentsAreIgnored() {
        val parser = parser()
        val events = parser.feed(": keepalive\n\nid: e1\nevent: gateway.notice\ndata: {}\n\n")

        assertEquals(1, events.size)
        assertEquals("e1", events[0].id)
    }
}
