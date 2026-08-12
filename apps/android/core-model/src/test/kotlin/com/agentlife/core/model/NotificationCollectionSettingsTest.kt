package com.agentlife.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NotificationCollectionSettingsTest {
    @Test
    fun package_ids_sort_by_unicode_code_point_not_utf16_units() {
        val actual = sortNotificationPackageIds(listOf("com.\uD83D\uDE00", "com.a", "com.\uE000"))
        assertEquals(listOf("com.a", "com.\uE000", "com.\uD83D\uDE00"), actual)
    }

    @Test
    fun duplicate_package_ids_are_rejected_after_normalization() {
        assertThrows(IllegalArgumentException::class.java) {
            sortNotificationPackageIds(listOf("com.mail", "com.mail"))
        }
    }

    @Test
    fun sorting_empty_selection_returns_empty() {
        assertEquals(emptyList<String>(), sortNotificationPackageIds(emptyList()))
    }
}
