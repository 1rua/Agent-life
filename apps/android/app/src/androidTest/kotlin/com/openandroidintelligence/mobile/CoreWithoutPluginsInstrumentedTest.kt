package com.openandroidintelligence.mobile

import android.content.ComponentName
import java.io.File
import java.time.Instant
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.AuditOutcome
import com.openandroidintelligence.kernel.AuditEvent
import com.openandroidintelligence.kernel.InMemoryAuditSink
import com.openandroidintelligence.kernel.PairingGrantBinding
import com.openandroidintelligence.kernel.PairingGrantCapabilities
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import com.openandroidintelligence.kernel.PersistentAuditSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreWithoutPluginsInstrumentedTest {

    @Test
    fun freshInstallStartsWithAnHonestDisconnectedRuntime() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val application = appContext.applicationContext as OpenAndroidIntelligenceApplication
        val runtime = application.gatewayRuntime
        runtime.resetFailure()

        assertNotNull(appContext)
        assertEquals(ConnectionPhase.Disconnected, runtime.phase.value)
        assertNull(runtime.controller.value)
        assertFalse(application.kernel.isEmergencyStopped())
        assertNull(application.kernel.registrationFor("org.openandroidintelligence.sms"))
    }

    @Test
    fun plainHttpIsRejectedBeforeAnyNetworkAttempt() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val application = appContext.applicationContext as OpenAndroidIntelligenceApplication
        val runtime = application.gatewayRuntime

        runtime.login("http://gateway.example.com", "alice", "secret".toCharArray())

        assertEquals(
            ConnectionPhase.Failed("AUTH_INVALID:url-scheme-required"),
            runtime.phase.value,
        )
        runtime.resetFailure()
    }

    @Test
    fun mainActivityUsesANoActionBarTheme() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            0,
        )
        val attributes = context.obtainStyledAttributes(
            info.theme,
            intArrayOf(android.R.attr.windowActionBar),
        )
        try {
            assertFalse("主 Activity 不得包含系统 ActionBar", attributes.getBoolean(0, true))
        } finally {
            attributes.recycle()
        }
    }

    @Test
    fun pairingGrantPreferencesSurviveCreatingANewStateHolder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(
            "open_android_intelligence_pairing_grants_test",
            android.content.Context.MODE_PRIVATE,
        )
        preferences.edit().clear().commit()
        val binding = PairingGrantBinding(
            gatewayId = "https://gateway.example.com",
            accountId = "account-test",
            installationId = "install-test",
        )

        try {
            val first = PairingGrantStateHolder(
                SharedPreferencesPairingGrantStore(preferences),
                AndroidAuditStore(InMemoryAuditSink()),
            )
            first.bind(binding)
            first.updatePrimitive(PairingGrantCapabilities.SMS, enabled = true)
            first.updateScreenSelection(enabled = true)

            val restored = PairingGrantStateHolder(
                SharedPreferencesPairingGrantStore(preferences),
                AndroidAuditStore(InMemoryAuditSink()),
            )
            restored.bind(binding)

            assertEquals(first.state.value, restored.state.value)
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test
    fun persistentAuditSinkCanReloadADeviceWrittenRecord() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "audit-test-${System.nanoTime()}.log")
        val event = AuditEvent(
            pluginId = "platform",
            accountId = "account-test",
            pairingId = "pairing-test",
            action = "emergency.stop",
            outcome = AuditOutcome.ALLOWED,
            correlationId = "correlation-test",
            timestampUtc = "2026-09-03T00:00:00.000Z",
        )

        try {
            PersistentAuditSink(
                file = file,
                clock = { Instant.parse("2026-09-04T00:00:00Z") },
            ).write(event)

            val reloaded = PersistentAuditSink(
                file = file,
                clock = { Instant.parse("2026-09-04T00:00:00Z") },
            )

            assertEquals(listOf(event), reloaded.events())
        } finally {
            file.delete()
        }
    }
}
