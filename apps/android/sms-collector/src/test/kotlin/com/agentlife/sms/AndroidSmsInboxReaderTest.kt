package com.agentlife.sms

import android.provider.Telephony
import com.agentlife.capability.SmsHistoryPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidSmsInboxReaderTest {
    @Test
    fun query_uses_only_the_inbox_uri_projection_bound_history_start_and_limit() {
        var actual: RecordedQuery? = null
        val reader = AndroidSmsInboxReader { uri, projection, selection, selectionArgs, sortOrder ->
            actual = RecordedQuery(uri.toString(), projection, selection, selectionArgs, sortOrder)
            null
        }

        reader.query(SmsInboxQuery(SmsHistoryPolicy(fromEpochMs = 1_700L, maxRecords = 3)))

        val query = requireNotNull(actual)
        assertEquals(Telephony.Sms.Inbox.CONTENT_URI.toString(), query.uri)
        assertArrayEquals(arrayOf("_id", "thread_id", "address", "body", "date", "read", "sub_id"), query.projection)
        assertEquals("date >= ?", query.selection)
        assertArrayEquals(arrayOf("1700"), query.selectionArgs)
        assertEquals("date DESC, _id DESC LIMIT 3", query.sortOrder)
    }

    @Test
    fun query_adds_a_strict_cursor_boundary_with_bound_values() {
        var actual: RecordedQuery? = null
        val reader = AndroidSmsInboxReader { uri, projection, selection, selectionArgs, sortOrder ->
            actual = RecordedQuery(uri.toString(), projection, selection, selectionArgs, sortOrder)
            null
        }

        reader.query(
            SmsInboxQuery(
                history = SmsHistoryPolicy(fromEpochMs = 1_000L, maxRecords = 2),
                cursor = SmsCursor(providerId = 42L, messageAtEpochMs = 1_500L),
            ),
        )

        val query = requireNotNull(actual)
        assertEquals("date >= ? AND (date > ? OR (date = ? AND _id > ?))", query.selection)
        assertArrayEquals(arrayOf("1000", "1500", "1500", "42"), query.selectionArgs)
        assertEquals("date DESC, _id DESC LIMIT 2", query.sortOrder)
    }

    private data class RecordedQuery(
        val uri: String,
        val projection: Array<String>,
        val selection: String?,
        val selectionArgs: Array<String>?,
        val sortOrder: String,
    )
}
