package com.agentlife.sms

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsCursorTest {
    @Test
    fun `advance persists a cursor with a newer message date`() {
        val store = InMemorySmsCursorStore()

        assertTrue(store.advance(SmsCursor(providerId = 1L, messageAtEpochMs = 100L)))

        assertEquals(SmsCursor(providerId = 1L, messageAtEpochMs = 100L), store.current())
    }

    @Test
    fun `advance persists a higher provider ID at the same message date`() {
        val store = InMemorySmsCursorStore(SmsCursor(providerId = 1L, messageAtEpochMs = 100L))

        assertTrue(store.advance(SmsCursor(providerId = 2L, messageAtEpochMs = 100L)))

        assertEquals(SmsCursor(providerId = 2L, messageAtEpochMs = 100L), store.current())
    }

    @Test
    fun `advance ignores an older or equal cursor without changing persisted state`() {
        val store = InMemorySmsCursorStore(SmsCursor(providerId = 2L, messageAtEpochMs = 100L))

        assertFalse(store.advance(SmsCursor(providerId = 1L, messageAtEpochMs = 100L)))
        assertFalse(store.advance(SmsCursor(providerId = 2L, messageAtEpochMs = 100L)))
        assertFalse(store.advance(SmsCursor(providerId = 9L, messageAtEpochMs = 99L)))

        assertEquals(SmsCursor(providerId = 2L, messageAtEpochMs = 100L), store.current())
    }

    @Test
    fun `file store restores the exact persisted cursor after restart`() {
        val directory = Files.createTempDirectory("sms-cursor-").toFile()
        try {
            val file = File(directory, "cursor.bin")
            FileSmsCursorStore(file).advance(SmsCursor(providerId = 42L, messageAtEpochMs = 1_700L))

            assertEquals(
                SmsCursor(providerId = 42L, messageAtEpochMs = 1_700L),
                FileSmsCursorStore(file).current(),
            )
        } finally {
            directory.deleteRecursively()
        }
    }
}
