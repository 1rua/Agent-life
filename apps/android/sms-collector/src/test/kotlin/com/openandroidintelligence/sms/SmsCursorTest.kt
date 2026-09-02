package com.openandroidintelligence.sms

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsCursorTest {
    @Test
    fun `cursor accepts the maximum signed long provider ID`() {
        val store = InMemorySmsCursorStore()

        assertTrue(store.advance(SmsCursor(providerId = Long.MAX_VALUE, messageAtEpochMs = 100L)))
        assertEquals(SmsCursor(providerId = Long.MAX_VALUE, messageAtEpochMs = 100L), store.current())
    }

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
            fileStore(file).advance(SmsCursor(providerId = 42L, messageAtEpochMs = 1_700L))

            assertEquals(
                SmsCursor(providerId = 42L, messageAtEpochMs = 1_700L),
                fileStore(file).current(),
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
            fileStore(file).advance(SmsCursor(providerId = 2L, messageAtEpochMs = 100L))
            val restarted = fileStore(file)

            assertFalse(restarted.advance(SmsCursor(providerId = 1L, messageAtEpochMs = 100L)))
            assertFalse(restarted.advance(SmsCursor(providerId = 2L, messageAtEpochMs = 100L)))

            assertEquals(SmsCursor(providerId = 2L, messageAtEpochMs = 100L), fileStore(file).current())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `no backup directory factory uses the fixed app-private cursor child`() {
        val noBackupDirectory = Files.createTempDirectory("sms-no-backup-").toFile()
        try {
            val cursorFile = File(noBackupDirectory, "sms-cursor-v1.bin")
            val store = fileStore(cursorFile)

            assertTrue(store.advance(SmsCursor(providerId = 7L, messageAtEpochMs = 700L)))
            assertEquals(
                SmsCursor(providerId = 7L, messageAtEpochMs = 700L),
                fileStore(cursorFile).current(),
            )
            assertTrue(File(noBackupDirectory, "sms-cursor-v1.bin").isFile)
        } finally {
            noBackupDirectory.deleteRecursively()
        }
    }

    private fun fileStore(file: File): FileSmsCursorStore =
        FileSmsCursorStore::class.java.getDeclaredConstructor(File::class.java).let { constructor ->
            constructor.isAccessible = true
            constructor.newInstance(file)
        }
}
