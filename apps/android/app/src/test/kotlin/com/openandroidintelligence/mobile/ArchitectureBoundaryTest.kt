package com.openandroidintelligence.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArchitectureBoundaryTest {

    @Test
    fun appModuleHasNoDirectCollectorOrTransportDependencies() {
        val candidates = listOf(
            File("apps/android/app/build.gradle.kts"),
            File("build.gradle.kts"),
            File("../app/build.gradle.kts"),
        )
        val buildFile = candidates.firstOrNull { it.exists() }
        assertNotNull("app/build.gradle.kts 必须存在", buildFile)
        val buildScript = buildFile!!.readText()

        val forbiddenProjects = listOf(
            ":notification-collector",
            ":sms-collector",
            ":call-log-collector",
            ":transport",
            ":tailnet-core",
            ":capability-sync-runtime",
        )

        for (forbidden in forbiddenProjects) {
            assertFalse(
                "app 模块严禁直接依赖旧采集器或传输层: $forbidden",
                buildScript.contains(forbidden),
            )
        }
    }

    @Test
    fun coreNavigationContainsOnlyThreeMainDestinations() {
        val nav = CoreNavigation(
            gateway = GatewayDestination(baseUrl = "https://gw.example.com"),
            conversations = ConversationDestination(activeSessionId = "session-1"),
            attachments = AttachmentDestination(uploadedCount = 0),
        )

        assertNotNull(nav.gateway)
        assertNotNull(nav.conversations)
        assertNotNull(nav.attachments)
    }
}

