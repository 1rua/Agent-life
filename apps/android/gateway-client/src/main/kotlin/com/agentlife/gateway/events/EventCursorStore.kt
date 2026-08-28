package com.agentlife.gateway.events

/**
 * Per-account resume point for the event stream.
 *
 * On reconnect the canonical query cursor is authoritative, and it is only ever
 * advanced to the id of a fully received event — never to a partially buffered
 * one, which would silently skip the rest of that event.
 */
interface EventCursorStore {
    fun load(accountId: String): String?

    fun save(accountId: String, cursor: String)

    fun clear(accountId: String)
}

class InMemoryEventCursorStore : EventCursorStore {

    private val cursors = LinkedHashMap<String, String>()

    @Synchronized
    override fun load(accountId: String): String? = cursors[accountId]

    @Synchronized
    override fun save(accountId: String, cursor: String) {
        require(cursor.isNotBlank()) { "cursor must not be blank" }
        cursors[accountId] = cursor
    }

    @Synchronized
    override fun clear(accountId: String) {
        cursors.remove(accountId)
    }
}
