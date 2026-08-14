package com.agentlife.calls

import android.content.ContentResolver
import android.database.Cursor
import android.provider.CallLog
import com.agentlife.capability.CallDirection
import com.agentlife.capability.CallHistoryPolicy
import com.agentlife.capability.CallNumberPresentation

internal enum class CallLogProviderTarget { CALLS }

/** Read-only adapter for the bounded public call-log provider surface. */
class AndroidCallLogReader private constructor(
    private val queryExecutor: QueryExecutor,
) : CallLogReader {
    constructor(resolver: ContentResolver) : this(
        QueryExecutor { target, projection, selection, selectionArgs, sortOrder, limit ->
            val uri = when (target) {
                CallLogProviderTarget.CALLS -> CallLog.Calls.CONTENT_URI.buildUpon()
                    .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, limit.toString())
                    .build()
            }
            resolver.query(uri, projection, selection, selectionArgs, sortOrder)
        },
    )

    internal constructor(
        queryExecutor: (
            CallLogProviderTarget,
            Array<String>,
            String?,
            Array<String>?,
            String,
            Int,
        ) -> Cursor?,
    ) : this(QueryExecutor(queryExecutor))

    override fun query(request: CallLogQuery): List<CallLogRow> {
        val providerTypes = request.directions.canonicalProviderTypes()
        val clauses = mutableListOf<String>()
        val arguments = mutableListOf<String>()
        request.history.fromEpochMs?.let {
            clauses += "date >= ?"
            arguments += it.toString()
        }
        clauses += "type IN (${providerTypes.joinToString(",") { "?" }})"
        arguments += providerTypes.map(Int::toString)
        request.cursor?.let {
            clauses += "(date > ? OR (date = ? AND _id > ?))"
            arguments += it.startedAtEpochMs.toString()
            arguments += it.startedAtEpochMs.toString()
            arguments += it.providerId.toString()
        }

        val cursor = try {
            queryExecutor.query(
                CallLogProviderTarget.CALLS,
                PROJECTION,
                clauses.joinToString(" AND "),
                arguments.toTypedArray(),
                SORT_ORDER,
                request.history.maxRecords,
            )
        } catch (_: Exception) {
            throw CallLogQueryException()
        }
        return try {
            cursor?.use { providerCursor ->
                val indexes = try {
                    ColumnIndexes(providerCursor)
                } catch (_: Exception) {
                    throw CallLogQueryException()
                }
                buildList {
                    while (size < request.history.maxRecords && providerCursor.moveToNext()) {
                        add(indexes.read(providerCursor))
                    }
                }
            }.orEmpty()
        } catch (failure: CallLogInvalidRowException) {
            throw failure
        } catch (_: Exception) {
            throw CallLogQueryException()
        }
    }

    override fun probe() {
        query(
            CallLogQuery(
                history = CallHistoryPolicy(fromEpochMs = null, maxRecords = 1),
                directions = CallDirection.entries.toSet(),
            ),
        )
    }

    private fun Set<CallDirection>.canonicalProviderTypes(): List<Int> =
        CallDirection.entries.filter(this::contains).map { it.providerType() }

    private class ColumnIndexes(cursor: Cursor) {
        private val providerId = cursor.getColumnIndexOrThrow("_id")
        private val type = cursor.getColumnIndexOrThrow("type")
        private val date = cursor.getColumnIndexOrThrow("date")
        private val duration = cursor.getColumnIndexOrThrow("duration")
        private val number = cursor.getColumnIndexOrThrow("number")
        private val numberPresentation = cursor.getColumnIndexOrThrow("number_presentation")

        fun read(cursor: Cursor): CallLogRow = try {
            CallLogRow(
                providerId = cursor.getLong(providerId),
                direction = cursor.getInt(type).toCallDirection(),
                startedAtEpochMs = cursor.getLong(date),
                durationSeconds = cursor.getLong(duration),
                number = cursor.getString(number),
                numberPresentation = cursor.getInt(numberPresentation).toNumberPresentation(),
            )
        } catch (_: Exception) {
            throw CallLogInvalidRowException()
        }
    }

    private fun interface QueryExecutor {
        fun query(
            target: CallLogProviderTarget,
            projection: Array<String>,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String,
            limit: Int,
        ): Cursor?
    }

    private companion object {
        val PROJECTION = arrayOf("_id", "type", "date", "duration", "number", "number_presentation")
        const val SORT_ORDER = "date DESC, _id DESC"
    }
}

private fun CallDirection.providerType(): Int = when (this) {
    CallDirection.INCOMING -> CallLog.Calls.INCOMING_TYPE
    CallDirection.OUTGOING -> CallLog.Calls.OUTGOING_TYPE
    CallDirection.MISSED -> CallLog.Calls.MISSED_TYPE
    CallDirection.REJECTED -> CallLog.Calls.REJECTED_TYPE
}

private fun Int.toCallDirection(): CallDirection = when (this) {
    CallLog.Calls.INCOMING_TYPE -> CallDirection.INCOMING
    CallLog.Calls.OUTGOING_TYPE -> CallDirection.OUTGOING
    CallLog.Calls.MISSED_TYPE -> CallDirection.MISSED
    CallLog.Calls.REJECTED_TYPE -> CallDirection.REJECTED
    else -> throw CallLogInvalidRowException()
}

private fun Int.toNumberPresentation(): CallNumberPresentation = when (this) {
    CallLog.Calls.PRESENTATION_ALLOWED -> CallNumberPresentation.ALLOWED
    CallLog.Calls.PRESENTATION_RESTRICTED -> CallNumberPresentation.RESTRICTED
    CallLog.Calls.PRESENTATION_UNKNOWN -> CallNumberPresentation.UNKNOWN
    CallLog.Calls.PRESENTATION_PAYPHONE -> CallNumberPresentation.PAYPHONE
    CallLog.Calls.PRESENTATION_UNAVAILABLE -> CallNumberPresentation.UNAVAILABLE
    else -> throw CallLogInvalidRowException()
}
