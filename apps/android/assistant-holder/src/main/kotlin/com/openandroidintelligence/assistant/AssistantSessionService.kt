package com.openandroidintelligence.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Minimal role session; all bridge/network work remains in the main APK. */
class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        AssistantSession(this)
}

private class AssistantSession(service: AssistantSessionService) : VoiceInteractionSession(service)
