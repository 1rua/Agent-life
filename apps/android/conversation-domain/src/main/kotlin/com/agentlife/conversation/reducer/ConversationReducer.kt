package com.agentlife.conversation.reducer

import com.agentlife.conversation.model.*

object ConversationReducer {
    fun reduce(state: ConversationSessionState, event: ConversationEvent): ConversationSessionState {
        return when (event) {
            is ConversationEvent.OpenSurface -> {
                if (state.surface == SurfaceState.TERMINATED) {
                    throw InvalidConversationTransition("Cannot open surface from TERMINATED state")
                }
                state.copy(surface = SurfaceState.EXPANDED)
            }
            is ConversationEvent.CloseSurface -> {
                state.copy(surface = SurfaceState.HIDDEN)
            }
            is ConversationEvent.DockSurface -> {
                state.copy(surface = SurfaceState.DOCKED)
            }
            is ConversationEvent.ExpandSurface -> {
                state.copy(surface = SurfaceState.EXPANDED)
            }
            is ConversationEvent.StartScreenSelection -> {
                state.copy(surface = SurfaceState.SELECTING_SCREEN)
            }
            is ConversationEvent.PreviewCrop -> {
                state.copy(surface = SurfaceState.CROP_PREVIEW)
            }
            is ConversationEvent.TerminateSurface -> {
                state.copy(surface = SurfaceState.TERMINATED)
            }

            is ConversationEvent.EditInput -> {
                // Editing input invalidates any pending submission intent
                state.copy(
                    composer = ComposerState.EDITING,
                    pendingSubmissionIntent = null,
                )
            }
            is ConversationEvent.AttachmentsPending -> {
                if (state.composer == ComposerState.ACCEPTED || state.composer == ComposerState.SUBMITTING) {
                    throw InvalidConversationTransition("Cannot transition composer to WAITING_ATTACHMENTS from ${state.composer}")
                }
                state.copy(composer = ComposerState.WAITING_ATTACHMENTS)
            }
            is ConversationEvent.AllAttachmentsReady -> {
                if (state.composer == ComposerState.WAITING_ATTACHMENTS) {
                    state.copy(composer = ComposerState.SEALED)
                } else {
                    state
                }
            }
            is ConversationEvent.PrepareSubmit -> {
                state.copy(
                    composer = ComposerState.SEALED,
                    pendingSubmissionIntent = event.intentId,
                )
            }
            is ConversationEvent.SubmitStarted -> {
                if (state.composer != ComposerState.SEALED && state.composer != ComposerState.EDITING) {
                    throw InvalidConversationTransition("Cannot start submission from composer state ${state.composer}")
                }
                state.copy(composer = ComposerState.SUBMITTING)
            }
            is ConversationEvent.SubmitAccepted -> {
                state.copy(
                    composer = ComposerState.ACCEPTED,
                    pendingSubmissionIntent = null,
                )
            }
            is ConversationEvent.SubmitFailed -> {
                state.copy(
                    composer = ComposerState.FAILED,
                    lastError = event.reason,
                )
            }

            is ConversationEvent.GenerationQueued -> {
                state.copy(generation = GenerationState.QUEUED)
            }
            is ConversationEvent.GenerationStarted -> {
                state.copy(generation = GenerationState.RUNNING)
            }
            is ConversationEvent.RequestCancelGeneration -> {
                if (state.generation == GenerationState.RUNNING || state.generation == GenerationState.QUEUED) {
                    state.copy(generation = GenerationState.CANCEL_REQUESTED)
                } else {
                    state
                }
            }
            is ConversationEvent.GenerationCancelled -> {
                state.copy(generation = GenerationState.CANCELLED)
            }
            is ConversationEvent.GenerationCompleted -> {
                if (state.generation == GenerationState.OUTCOME_UNKNOWN) {
                    throw InvalidConversationTransition("Cannot complete generation directly from OUTCOME_UNKNOWN")
                }
                state.copy(generation = GenerationState.COMPLETED)
            }
            is ConversationEvent.GenerationFailed -> {
                state.copy(generation = GenerationState.FAILED, lastError = event.reason)
            }
            is ConversationEvent.GenerationUnsupported -> {
                state.copy(generation = GenerationState.UNSUPPORTED)
            }
            is ConversationEvent.GenerationOutcomeUnknown -> {
                state.copy(generation = GenerationState.OUTCOME_UNKNOWN)
            }

            is ConversationEvent.SyncStateChanged -> {
                state.copy(mirrorSync = event.syncState)
            }
        }
    }
}
