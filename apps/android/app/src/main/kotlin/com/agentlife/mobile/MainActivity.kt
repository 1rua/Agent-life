package com.agentlife.mobile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.agentlife.core.model.AssistantHandoffDecision
import com.agentlife.core.model.AssistantHandoffGate
import com.agentlife.core.model.AssistantHandoffRequest
import com.agentlife.core.model.DefaultAssistantHandoffGate
import com.agentlife.core.model.NotificationDeliveryMode
import com.agentlife.core.model.NotificationFieldAccess

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

    private lateinit var grantSwitch: Switch
    private lateinit var packageIdsInput: EditText
    private lateinit var fieldAccessGroup: RadioGroup
    private lateinit var deliveryModeGroup: RadioGroup
    private lateinit var statusText: TextView
    private lateinit var application: AgentLifeApplication

    private val metadataId = View.generateViewId()
    private val contentId = View.generateViewId()
    private val onDemandId = View.generateViewId()
    private val autoSendId = View.generateViewId()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        application = getApplication() as AgentLifeApplication
        setContentView(buildSettingsView())
        refreshFromAuthority()
    }

    override fun onResume() {
        super.onResume()
        if (::application.isInitialized) refreshFromAuthority()
    }

    /** Source-only seam for the reviewed local handoff adapter. */
    fun evaluateAssistantHandoff(request: AssistantHandoffRequest): AssistantHandoffDecision =
        handoffGate.evaluate(request).also { lastHandoffDecision = it }

    fun currentAssistantHandoffDecision(): AssistantHandoffDecision = lastHandoffDecision

    private fun buildSettingsView(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        content.addView(TextView(this).apply {
            text = "Local notification settings"
            textSize = 24f
        }, matchParentWrap())
        content.addView(TextView(this).apply {
            text = "This page controls local consent only. It does not send network requests or accept remote permission changes from an Agent."
            setPadding(0, dp(8), 0, dp(16))
        }, matchParentWrap())

        grantSwitch = Switch(this).apply { text = "Allow local notification access" }
        content.addView(grantSwitch, matchParentWrap())

        content.addView(label("Allowed package IDs (one per line)"), matchParentWrap())
        packageIdsInput = EditText(this).apply {
            hint = "com.example.mail"
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_URI or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        content.addView(packageIdsInput, matchParentWrap(height = dp(120)))

        content.addView(label("Field permission"), matchParentWrap())
        fieldAccessGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        fieldAccessGroup.addView(radio(metadataId, "METADATA — app identity and notification metadata"))
        fieldAccessGroup.addView(radio(contentId, "CONTENT — metadata plus title and body"))
        content.addView(fieldAccessGroup, matchParentWrap())

        content.addView(label("Delivery mode"), matchParentWrap())
        deliveryModeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        deliveryModeGroup.addView(radio(onDemandId, "ON_DEMAND — the paired Agent asks when needed"))
        deliveryModeGroup.addView(radio(autoSendId, "AUTO_SEND — automatically sends to a paired Agent"))
        content.addView(deliveryModeGroup, matchParentWrap())
        content.addView(TextView(this).apply {
            text = "AUTO_SEND automatically sends authorized notifications to the paired Agent. This settings page never performs that network delivery itself."
            setPadding(0, dp(4), 0, dp(16))
        }, matchParentWrap())

        val openSystemSettings = Button(this).apply {
            text = "Open Android notification access settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        content.addView(openSystemSettings, matchParentWrap())

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "Save"
            setOnClickListener { saveSettings() }
        }, weightWrap())
        actions.addView(Button(this).apply {
            text = "Revoke"
            setOnClickListener { revokeSettings() }
        }, weightWrap())
        content.addView(actions, matchParentWrap())

        statusText = TextView(this).apply { setPadding(0, dp(12), 0, 0) }
        content.addView(statusText, matchParentWrap())

        return ScrollView(this).apply { addView(content) }
    }

    private fun refreshFromAuthority() {
        val snapshot = application.localNotificationAuthoritySnapshot()
        grantSwitch.isChecked = snapshot.granted
        packageIdsInput.setText(snapshot.policy.packageIds.joinToString("\n"))
        fieldAccessGroup.check(if (snapshot.policy.fieldAccess == NotificationFieldAccess.CONTENT) contentId else metadataId)
        deliveryModeGroup.check(if (snapshot.deliveryMode == NotificationDeliveryMode.AUTO_SEND) autoSendId else onDemandId)
        statusText.text = if (snapshot.corrupted) {
            "Local policy evidence is corrupted; access remains denied."
        } else {
            "Current authorization revision: ${snapshot.authorizationRevision}"
        }
    }

    private fun saveSettings() {
        try {
            val snapshot = application.localNotificationAuthoritySnapshot()
            val commit = commitNotificationSettings(snapshot, currentDraft())
            application.localNotificationPolicyController().apply(
                commit.policy,
                commit.authorizationRevision,
                commit.granted,
                commit.deliveryMode,
            )
            statusText.text = "Saved at authorization revision ${commit.authorizationRevision}."
        } catch (failure: IllegalArgumentException) {
            Toast.makeText(this, failure.message ?: "Invalid notification settings", Toast.LENGTH_LONG).show()
        }
    }

    private fun revokeSettings() {
        try {
            val snapshot = application.localNotificationAuthoritySnapshot()
            val draft = NotificationSettingsDraft(
                packageIdsText = snapshot.policy.packageIds.joinToString("\n"),
                fieldAccess = snapshot.policy.fieldAccess,
                deliveryMode = snapshot.deliveryMode,
                granted = false,
            )
            val commit = commitNotificationSettings(snapshot, draft)
            application.localNotificationPolicyController().revoke(commit.authorizationRevision)
            statusText.text = "Local notification access revoked."
            grantSwitch.isChecked = false
        } catch (failure: IllegalArgumentException) {
            Toast.makeText(this, failure.message ?: "Unable to revoke notification settings", Toast.LENGTH_LONG).show()
        }
    }

    private fun currentDraft(): NotificationSettingsDraft = NotificationSettingsDraft(
        packageIdsText = packageIdsInput.text.toString(),
        fieldAccess = if (fieldAccessGroup.checkedRadioButtonId == contentId) {
            NotificationFieldAccess.CONTENT
        } else {
            NotificationFieldAccess.METADATA
        },
        deliveryMode = if (deliveryModeGroup.checkedRadioButtonId == autoSendId) {
            NotificationDeliveryMode.AUTO_SEND
        } else {
            NotificationDeliveryMode.ON_DEMAND
        },
        granted = grantSwitch.isChecked,
    )

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun radio(id: Int, text: String): RadioButton = RadioButton(this).apply {
        this.id = id
        this.text = text
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchParentWrap(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)

    private fun weightWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
}
