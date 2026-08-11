package com.agentlife.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import com.agentlife.core.model.AssistantHandoffAttachment
import com.agentlife.core.model.AssistantHandoffMediaType
import com.agentlife.core.model.AssistantHandoffOrigin
import com.agentlife.core.model.AssistantHandoffRequest
import com.agentlife.core.model.AssistantHandoffSource

/**
 * Separate assistant entry-point APK. It only captures the user-selected
 * launch payload and hands it to the main app through an explicit, local seam;
 * it never reads notification data or opens a network endpoint.
 */
class AssistantActivity : Activity() {
    private var launchPayload: AssistantLaunchPayload? = null
    private var grantIssuer: AssistantAttachmentGrantIssuer? = null
    private var latestLaunchIntent: Intent? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        captureLaunch(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) captureLaunch(intent)
    }

    override fun onDestroy() {
        // Do not retain provider-grant handles beyond this short-lived holder.
        latestLaunchIntent = null
        grantIssuer = null
        launchPayload = null
        super.onDestroy()
    }

    fun currentLaunchPayload(): AssistantLaunchPayload? = launchPayload

    /** Typed, URI-free handoff record for the reviewed local adapter. */
    fun currentLaunchHandoff(): AssistantHandoffRequest? = launchPayload?.let { payload ->
        AssistantHandoffRequest(
            text = payload.text,
            attachments = payload.attachmentSelections.map { selection ->
                AssistantHandoffAttachment(
                    selectionId = selection.selectionId,
                    source = when (selection.source) {
                        AssistantAttachmentSource.PHOTO_PICKER -> AssistantHandoffSource.PHOTO_PICKER
                        AssistantAttachmentSource.SAF -> AssistantHandoffSource.SAF
                    },
                    readGrantId = selection.readGrant.grantId,
                    mediaTypeHint = selection.mediaTypeHint?.let { hint ->
                        when (hint) {
                            AssistantAttachmentMediaType.JPEG -> AssistantHandoffMediaType.JPEG
                            AssistantAttachmentMediaType.PNG -> AssistantHandoffMediaType.PNG
                            AssistantAttachmentMediaType.WEBP -> AssistantHandoffMediaType.WEBP
                            AssistantAttachmentMediaType.PDF -> AssistantHandoffMediaType.PDF
                            AssistantAttachmentMediaType.TEXT_PLAIN -> AssistantHandoffMediaType.TEXT_PLAIN
                        }
                    },
                )
            },
            origin = AssistantHandoffOrigin.USER_INITIATED,
        )
    }

    /** Installed only by the reviewed local hand-off; absent means deny. */
    internal fun bindAttachmentGrantIssuer(issuer: AssistantAttachmentGrantIssuer) {
        grantIssuer = issuer
        // onCreate can run before the reviewed adapter is installed. Re-run
        // the exact captured selection now; never mint a local ID as fallback.
        latestLaunchIntent?.let(::captureLaunch)
    }

    private fun captureLaunch(intent: Intent) {
        latestLaunchIntent = intent
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.take(50_000)
        // The holder never resolves a provider location. Attachments must be
        // explicit content-provider grants from a known system picker; the
        // main app performs MIME/size/digest checks before forwarding them.
        val hasReadGrant = intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        val source = AssistantAttachmentSource.fromIntentAction(intent.action)
        val stream = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            ?: intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(::listOf)
            ?: emptyList()
        val issuer = grantIssuer
        val attachmentSelections = if (hasReadGrant && source != null && issuer != null) {
            stream
                .filter { uri -> uri.isHierarchical && uri.scheme == "content" }
                .take(MAX_ASSISTANT_ATTACHMENTS)
                .mapNotNull { uri ->
                    val selection = issuer.issue(source, AssistantProviderSelection(uri))
                    selection?.takeIf { selection.source == source }
                }
        } else {
            emptyList()
        }
        validateAssistantAttachmentSelections(attachmentSelections)
        launchPayload = AssistantLaunchPayload(text, attachmentSelections)
    }

}

/** Private provider handle; it never appears in AssistantLaunchPayload. */
class AssistantProviderSelection internal constructor(
    internal val contentUri: Uri,
)

data class AssistantLaunchPayload(
    val text: String?,
    val attachmentSelections: List<AssistantAttachmentSelection>,
)
