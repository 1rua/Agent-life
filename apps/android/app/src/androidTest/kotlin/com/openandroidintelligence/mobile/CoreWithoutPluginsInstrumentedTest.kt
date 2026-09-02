package com.openandroidintelligence.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreWithoutPluginsInstrumentedTest {

    @Test
    fun freshInstallCanLoginChatAndUploadWithoutPlugins() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull(appContext)

        val gatewayPresenter = GatewayPresenter()
        gatewayPresenter.connect("https://gw.example.com")
        assertTrue("Gateway 必须成功标记为已连接", gatewayPresenter.currentState().isOnline)

        val conversationPresenter = ConversationPresenter()
        val sent = conversationPresenter.sendMessage("hello")
        assertEquals("hello", sent.text)
        val reply = conversationPresenter.receiveReply("world")
        assertEquals("world", reply.text)
        assertEquals(2, conversationPresenter.currentState().messages.size)

        val attachmentPresenter = AttachmentPresenter()
        val attachment = attachmentPresenter.addAttachment(
            name = "note.txt",
            mimeType = "text/plain",
            sizeBytes = 12L,
            sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        )
        assertEquals("note.txt", attachment.name)
        assertEquals(1, attachmentPresenter.currentState().attachments.size)

        val settingsPresenter = PlatformSettingsPresenter(
            DistributionPolicy(allowRuntimePlugins = true, allowDeveloperTrustMode = true),
        )
        assertEquals(0, settingsPresenter.currentState().installedPluginsCount)
    }
}

