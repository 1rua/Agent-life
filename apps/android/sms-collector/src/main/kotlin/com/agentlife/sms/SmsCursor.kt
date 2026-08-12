package com.agentlife.sms

import com.agentlife.capability.SmsHistoryPolicy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

data class SmsCursor(val providerId: Long, val messageAtEpochMs: Long) : Comparable<SmsCursor> {
    init {
        require(providerId >= 0) { "SMS provider ID must not be negative" }
        require(messageAtEpochMs >= 0) { "SMS message time must not be negative" }
    }

    override fun compareTo(other: SmsCursor): Int =
        compareValuesBy(this, other, SmsCursor::messageAtEpochMs, SmsCursor::providerId)
}

fun interface SmsHistoryPolicySource {
    fun current(): SmsHistoryPolicy
}

/** Read-only in this slice; Task 3 owns cursor persistence. */
fun interface SmsCursorSource {
    fun current(): SmsCursor?
}

interface SmsCursorStore : SmsCursorSource {
    /** Returns true only when a strictly newer cursor was persisted. */
    fun advance(cursor: SmsCursor): Boolean
}

class InMemorySmsCursorStore(initial: SmsCursor? = null) : SmsCursorStore {
    private val lock = Any()
    private var cursor: SmsCursor? = initial

    override fun current(): SmsCursor? = synchronized(lock) { cursor }

    override fun advance(cursor: SmsCursor): Boolean = synchronized(lock) {
        if (this.cursor != null && cursor <= this.cursor!!) return false
        this.cursor = cursor
        true
    }
}

/** File adapter intended only for a child of Context.noBackupFilesDir. */
class FileSmsCursorStore(private val file: File) : SmsCursorStore {
    private val lock = Any()
    private var cursor: SmsCursor? = readCursor()

    override fun current(): SmsCursor? = synchronized(lock) { cursor }

    override fun advance(cursor: SmsCursor): Boolean = synchronized(lock) {
        if (this.cursor != null && cursor <= this.cursor!!) return false
        writeAtomically(encode(cursor))
        this.cursor = cursor
        true
    }

    private fun readCursor(): SmsCursor? {
        if (!file.isFile) return null
        return decode(file.readBytes())
    }

    private fun writeAtomically(bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val parent = file.parentFile ?: file.absoluteFile.parentFile ?: error("SMS cursor file has no parent")
        val temporary = File(parent, "${file.name}.tmp")
        temporary.writeBytes(bytes)
        check(temporary.renameTo(file)) { "unable to atomically persist SMS cursor" }
    }

    private fun encode(cursor: SmsCursor): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC.size)
            output.write(MAGIC)
            output.writeLong(cursor.providerId)
            output.writeLong(cursor.messageAtEpochMs)
        }
        bytes.toByteArray()
    }

    private fun decode(bytes: ByteArray): SmsCursor = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val magicLength = input.readInt()
        require(magicLength == MAGIC.size) { "SMS cursor format mismatch" }
        val magic = ByteArray(magicLength)
        input.readFully(magic)
        check(magic.contentEquals(MAGIC)) { "SMS cursor format mismatch" }
        val providerId = input.readLong()
        val messageAtEpochMs = input.readLong()
        check(input.available() == 0) { "SMS cursor trailing bytes" }
        SmsCursor(providerId, messageAtEpochMs)
    }

    private companion object {
        val MAGIC = "AGENT_LIFE_SMS_CURSOR_V1".encodeToByteArray()
    }
}
