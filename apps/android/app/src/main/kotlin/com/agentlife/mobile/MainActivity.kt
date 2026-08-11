package com.agentlife.mobile

import android.app.Activity
import android.os.Bundle
import com.agentlife.core.model.AssistantHandoffDecision
import com.agentlife.core.model.AssistantHandoffGate
import com.agentlife.core.model.AssistantHandoffRequest
import com.agentlife.core.model.DefaultAssistantHandoffGate

/**
 * Main-app UI shell. Assistant input reaches the app only as a typed request;
 * the default local gate denies it until the user explicitly enables the
 * handoff setting. No implicit IPC, network, or provider access is performed.
 */
class MainActivity : Activity() {
    private val handoffGate: AssistantHandoffGate = DefaultAssistantHandoffGate()
    private var lastHandoffDecision: AssistantHandoffDecision =
        AssistantHandoffDecision.Denied(
            com.agentlife.core.model.AssistantHandoffDenialReason.DEFAULT_DENY,
        )

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
    }

    /** Source-only seam for the reviewed local handoff adapter. */
    fun evaluateAssistantHandoff(request: AssistantHandoffRequest): AssistantHandoffDecision =
        handoffGate.evaluate(request).also { lastHandoffDecision = it }

    fun currentAssistantHandoffDecision(): AssistantHandoffDecision = lastHandoffDecision
}
