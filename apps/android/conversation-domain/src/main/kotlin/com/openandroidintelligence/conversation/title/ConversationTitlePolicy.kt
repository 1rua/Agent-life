package com.openandroidintelligence.conversation.title

import com.openandroidintelligence.conversation.ports.OutgoingMessage
import java.text.BreakIterator
import java.util.Locale

object ConversationTitlePolicy {
    private const val MAX_GRAPHEMES = 48

    fun generateTitle(firstMessage: OutgoingMessage, locale: Locale = Locale.getDefault()): String {
        val rawText = firstMessage.text.trim()
        if (rawText.startsWith("/new")) {
            return "新对话"
        }
        if (rawText.isBlank()) {
            return if (firstMessage.attachmentIds.isNotEmpty()) "附件内容" else "新对话"
        }

        val firstLine = rawText.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "新对话"
        val iterator = BreakIterator.getCharacterInstance(locale)
        iterator.setText(firstLine)

        var count = 0
        var boundary = 0
        while (iterator.next() != BreakIterator.DONE) {
            count++
            if (count <= MAX_GRAPHEMES) {
                boundary = iterator.current()
            } else {
                break
            }
        }

        return if (count > MAX_GRAPHEMES) {
            firstLine.substring(0, boundary) + "…"
        } else {
            firstLine
        }
    }
}
