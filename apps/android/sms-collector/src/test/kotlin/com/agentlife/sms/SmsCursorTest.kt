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
            FileSmsCursorStore.forTesting(file).advance(SmsCursor(providerId = 42L, messageAtEpochMs = 1_700L))

            assertEquals(
                SmsCursor(providerId = 42L, messageAtEpochMs = 1_700L),
                FileSmsCursorStore.forTesting(file).current(),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `file store ignores older and equal advances after restart`() {
        val directory = Files.createTempDirectory("sms-cursor-").toFile()
        try {
            val file = File(directory, "cursor.bin")
            FileSmsCursorStore.forTesting(file).advance(SmsCursor(providerId = 2L, messageAtEpochMs = 100L))
            val restarted = FileSmsCursorStore.forTesting(file)

            assertFalse(restarted.advance(SmsCursor(providerId = 1L, messageAtEpochMs = 100L)))
            assertFalse(restarted.advance(SmsCursor(providerId = 2L, messageAtEpochMs = 100L)))

            assertEquals(SmsCursor(providerId = 2L, messageAtEpochMs = 100L), FileSmsCursorStore.forTesting(file).current())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `no backup directory factory uses the fixed app-private cursor child`() {
        val noBackupDirectory = Files.createTempDirectory("sms-no-backup-").toFile()
        try {
            val store = FileSmsCursorStore.fromNoBackupDirectory(noBackupDirectory)

            assertTrue(store.advance(SmsCursor(providerId = 7L, messageAtEpochMs = 700L)))
            assertEquals(
                SmsCursor(providerId = 7L, messageAtEpochMs = 700L),
                FileSmsCursorStore.fromNoBackupDirectory(noBackupDirectory).current(),
            )
            assertTrue(File(noBackupDirectory, "sms-cursor-v1.bin").isFile)
        } finally {
            noBackupDirectory.deleteRecursively()
        }
    }
}
