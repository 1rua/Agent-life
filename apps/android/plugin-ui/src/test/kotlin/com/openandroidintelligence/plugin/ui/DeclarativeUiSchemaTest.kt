package com.openandroidintelligence.plugin.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The host renders plugin UI from data, so the schema is the security boundary:
 * anything it accepts becomes something a user can see and tap.
 */
class DeclarativeUiSchemaTest {

    private fun rejected(json: String): String {
        try {
            DeclarativeUiSchema.parse(json)
        } catch (cause: UiRejected) {
            return cause.message!!
        }
        throw AssertionError("expected the contribution to be rejected: $json")
    }

    @Test
    fun parsesEveryWhitelistedComponentKind() {
        val contribution = DeclarativeUiSchema.parse(
            """
            {
              "id": "settings",
              "title": "Example",
              "root": {
                "type": "section",
                "id": "root",
                "label": "Root",
                "children": [
                  { "type": "text", "id": "t", "label": "Text", "value": "hello" },
                  { "type": "status", "id": "s", "label": "Status", "value": "ok", "severity": "info" },
                  { "type": "toggle", "id": "g", "label": "Toggle", "value": true, "action": "set-setting" },
                  {
                    "type": "select",
                    "id": "sel",
                    "label": "Select",
                    "value": "b",
                    "action": "set-setting",
                    "options": [
                      { "id": "a", "label": "A" },
                      { "id": "b", "label": "B" }
                    ]
                  },
                  { "type": "button", "id": "b", "label": "Run", "action": "invoke-capability" },
                  { "type": "permission-request", "id": "p", "label": "Grant", "capability": "kernel.sms.read", "action": "request-grant" },
                  { "type": "capability-picker", "id": "c", "label": "Pick", "capability": "org.example.sms.query", "action": "select-provider" }
                ]
              }
            }
            """.trimIndent(),
        )

        val section = contribution.root as UiComponent.Section
        assertEquals(7, section.children.size)
        assertEquals(true, (section.children[2] as UiComponent.Toggle).value)
        assertEquals("b", (section.children[3] as UiComponent.Select).value)
        assertEquals(
            UiSeverity.INFO,
            (section.children[1] as UiComponent.Status).severity,
        )
        assertEquals(
            UiActionId.REQUEST_GRANT,
            (section.children[5] as UiComponent.PermissionRequest).action,
        )
    }

    @Test
    fun rejectsHtmlField() {
        assertTrue(
            rejected(
                """
                { "id": "x", "root": { "type": "text", "id": "t", "html": "<b>hi</b>", "value": "hi" } }
                """.trimIndent(),
            ).contains("UI_UNKNOWN_FIELD"),
        )
    }

    @Test
    fun rejectsJavaScriptInAValue() {
        assertTrue(
            rejected(
                """
                { "id": "x", "root": { "type": "text", "id": "t", "value": "<script>alert(1)</script>" } }
                """.trimIndent(),
            ).contains("UI_FORBIDDEN_CONTENT"),
        )
    }

    @Test
    fun rejectsJavaScriptAddressInAValue() {
        assertTrue(
            rejected(
                """
                { "id": "x", "root": { "type": "button", "id": "b", "label": "javascript:alert(1)" } }
                """.trimIndent(),
            ).contains("UI_FORBIDDEN_CONTENT"),
        )
    }

    @Test
    fun rejectsIntentField() {
        assertTrue(
            rejected(
                """
                { "id": "x", "root": { "type": "button", "id": "b", "intent": "com.example.EVIL" } }
                """.trimIndent(),
            ).contains("UI_UNKNOWN_FIELD"),
        )
    }

    @Test
    fun rejectsCustomViewClassField() {
        assertTrue(
            rejected(
                """
                { "id": "x", "root": { "type": "text", "id": "t", "value": "hi", "className": "com.example.EvilView" } }
                """.trimIndent(),
            ).contains("UI_UNKNOWN_FIELD"),
        )
    }

    @Test
    fun rejectsUnknownComponentKind() {
        assertTrue(
            rejected(
                """
                { "id": "x", "root": { "type": "webview", "id": "w" } }
                """.trimIndent(),
            ).contains("UI_UNKNOWN_COMPONENT:webview"),
        )
    }

    @Test
    fun rejectsUnknownAction() {
        assertTrue(
            rejected(
                """
                { "id": "x", "root": { "type": "button", "id": "b", "action": "open-url" } }
                """.trimIndent(),
            ).contains("UI_UNKNOWN_ACTION:open-url"),
        )
    }

    @Test
    fun rejectsActionTheComponentKindMayNotCarry() {
        assertTrue(
            rejected(
                """
                { "id": "x", "root": { "type": "toggle", "id": "g", "value": true, "action": "invoke-capability" } }
                """.trimIndent(),
            ).contains("UI_ACTION_NOT_ALLOWED"),
        )
    }

    @Test
    fun rejectsUnknownFieldOnTheContributionItself() {
        assertTrue(
            rejected(
                """
                { "id": "x", "onclick": "evil()", "root": { "type": "text", "id": "t", "value": "hi" } }
                """.trimIndent(),
            ).contains("UI_UNKNOWN_FIELD:contribution"),
        )
    }

    @Test
    fun rejectsNestingPastTheDepthLimit() {
        var deepest = """{ "type": "text", "id": "leaf", "value": "hi" }"""
        repeat(12) { index ->
            deepest = """{ "type": "section", "id": "s$index", "children": [$deepest] }"""
        }
        assertTrue(
            rejected("""{ "id": "x", "root": $deepest }""").contains("UI_TOO_DEEP"),
        )
    }

    @Test
    fun rejectsSelectValueThatIsNotOneOfItsOptions() {
        assertTrue(
            rejected(
                """
                {
                  "id": "x",
                  "root": {
                    "type": "select",
                    "id": "sel",
                    "value": "c",
                    "options": [ { "id": "a", "label": "A" }, { "id": "b", "label": "B" } ]
                  }
                }
                """.trimIndent(),
            ).contains("UI_VALUE_NOT_AN_OPTION"),
        )
    }

    @Test
    fun rejectsMalformedCapabilityIdentifier() {
        assertTrue(
            rejected(
                """
                { "id": "x", "root": { "type": "permission-request", "id": "p", "capability": "../../evil", "action": "request-grant" } }
                """.trimIndent(),
            ).contains("UI_BAD_CAPABILITY"),
        )
    }

    @Test
    fun acceptsVersionedCapabilityIdentifier() {
        val contribution = DeclarativeUiSchema.parse(
            """
            { "id": "x", "root": { "type": "capability-picker", "id": "c", "capability": "org.openandroidintelligence.sms.query@1.0.0", "action": "select-provider" } }
            """.trimIndent(),
        )
        assertEquals(
            "org.openandroidintelligence.sms.query@1.0.0",
            (contribution.root as UiComponent.CapabilityPicker).capability,
        )
    }
}
