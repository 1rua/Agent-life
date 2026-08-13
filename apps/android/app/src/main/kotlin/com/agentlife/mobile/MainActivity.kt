package com.agentlife.mobile

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.agentlife.capability.CapabilityAvailability
import com.agentlife.capability.SmsHistoryPolicy
import com.agentlife.capability.SmsSyncInterval
import com.agentlife.core.model.AssistantHandoffDecision
import com.agentlife.core.model.AssistantHandoffGate
import com.agentlife.core.model.AssistantHandoffRequest
import com.agentlife.core.model.DefaultAssistantHandoffGate
import com.agentlife.core.model.NotificationDeliveryMode
import com.agentlife.core.model.NotificationFieldAccess
import com.agentlife.core.model.NotificationRuleMode
import com.agentlife.core.model.PolicyRevisionRace
import com.agentlife.notifications.AgentLifeNotificationListenerService
import com.agentlife.policy.PolicyStateCorrupted
import com.agentlife.sms.SmsSettingsDefaults
import com.agentlife.sms.SmsSettingsSnapshot

/** Local-only notification settings UI and the reviewed assistant handoff seam. */
class MainActivity : Activity() {
    private val handoffGate: AssistantHandoffGate = DefaultAssistantHandoffGate()
    private var lastHandoffDecision: AssistantHandoffDecision =
        AssistantHandoffDecision.Denied(
            com.agentlife.core.model.AssistantHandoffDenialReason.DEFAULT_DENY,
        )

    private lateinit var grantSwitch: Switch
    private lateinit var fieldAccessGroup: RadioGroup
    private lateinit var deliveryModeGroup: RadioGroup
    private lateinit var ruleModeGroup: RadioGroup
    private lateinit var packageSelections: LinearLayout
    private lateinit var listenerStatusText: TextView
    private lateinit var statusText: TextView
    private lateinit var application: AgentLifeApplication
    private var showingSmsSettings = false

    private val metadataId = View.generateViewId()
    private val contentId = View.generateViewId()
    private val onDemandId = View.generateViewId()
    private val autoSendId = View.generateViewId()
    private val allowlistId = View.generateViewId()
    private val denylistId = View.generateViewId()
    private val packageCheckBoxes = linkedMapOf<String, CheckBox>()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        application = getApplication() as AgentLifeApplication
        showingSmsSettings = false
        setContentView(buildSettingsView())
        refreshFromAuthority()
    }

    override fun onResume() {
        super.onResume()
        if (::application.isInitialized && !showingSmsSettings) refreshFromAuthority()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == READ_SMS_REQUEST_CODE) {
            showingSmsSettings = true
            renderSmsSettings()
        }
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

        content.addView(Button(this).apply {
            text = "Open SMS settings"
            setOnClickListener {
                showingSmsSettings = true
                renderSmsSettings()
            }
        }, matchParentWrap())

        listenerStatusText = TextView(this).apply { setPadding(0, 0, 0, dp(8)) }
        content.addView(listenerStatusText, matchParentWrap())

        content.addView(Button(this).apply {
            text = "Open Android notification access settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }, matchParentWrap())

        grantSwitch = Switch(this).apply { text = "Allow local notification access" }
        content.addView(grantSwitch, matchParentWrap())

        content.addView(label("Delivery mode"), matchParentWrap())
        deliveryModeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        deliveryModeGroup.addView(radio(onDemandId, "ON_DEMAND — the paired Agent asks when needed"))
        deliveryModeGroup.addView(radio(autoSendId, "AUTO_SEND — automatically sends to a paired Agent"))
        content.addView(deliveryModeGroup, matchParentWrap())

        content.addView(label("Field permission"), matchParentWrap())
        fieldAccessGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        fieldAccessGroup.addView(radio(metadataId, "METADATA — app identity and notification metadata"))
        fieldAccessGroup.addView(radio(contentId, "CONTENT — metadata plus title and body"))
        content.addView(fieldAccessGroup, matchParentWrap())

        content.addView(label("Package rule"), matchParentWrap())
        ruleModeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        ruleModeGroup.addView(radio(allowlistId, "ALLOWLIST — only selected applications"))
        ruleModeGroup.addView(radio(denylistId, "DENYLIST — all installed applications except selected ones"))
        content.addView(ruleModeGroup, matchParentWrap())

        content.addView(label("Applications"), matchParentWrap())
        packageSelections = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addInstalledApplications(packageSelections)
        content.addView(packageSelections, matchParentWrap())

        content.addView(TextView(this).apply {
            text = "AUTO_SEND automatically sends authorized notifications to the paired Agent. This settings page never performs that network delivery itself."
            setPadding(0, dp(12), 0, dp(16))
        }, matchParentWrap())

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

    private fun addInstalledApplications(container: LinearLayout) {
        packageManager.getInstalledApplications(0)
            .sortedWith(Comparator { left, right ->
                com.agentlife.core.model.compareNotificationPackageIds(left.packageName, right.packageName)
            })
            .forEach { info ->
                val label = info.loadLabel(packageManager).toString()
                val checkBox = CheckBox(this).apply {
                    text = "${info.packageName} — $label"
                }
                packageCheckBoxes[info.packageName] = checkBox
                container.addView(checkBox, matchParentWrap())
            }
    }

    private fun refreshFromAuthority() {
        val snapshot = application.localNotificationAuthoritySnapshot()
        val selectedPackages = snapshot.policy.packageIds.toSet()
        grantSwitch.isChecked = snapshot.granted
        fieldAccessGroup.check(if (snapshot.policy.fieldAccess == NotificationFieldAccess.CONTENT) contentId else metadataId)
        deliveryModeGroup.check(if (snapshot.deliveryMode == NotificationDeliveryMode.AUTO_SEND) autoSendId else onDemandId)
        ruleModeGroup.check(if (snapshot.policy.mode == NotificationRuleMode.DENYLIST) denylistId else allowlistId)
        packageCheckBoxes.forEach { (packageName, checkBox) ->
            checkBox.isChecked = packageName in selectedPackages
        }
        listenerStatusText.text = systemListenerStatus()
        statusText.text = if (snapshot.corrupted) {
            "Local policy evidence is corrupted; access remains denied."
        } else {
            "Current authorization revision: ${snapshot.authorizationRevision}"
        }
    }

    private fun saveSettings() {
        try {
            val snapshot = application.localNotificationAuthoritySnapshot()
            val commit = currentDraft().commitAgainst(snapshot)
            application.localNotificationPolicyController().apply(
                commit.policy,
                commit.authorizationRevision,
                commit.granted,
                commit.deliveryMode,
            )
            statusText.text = "Saved at authorization revision ${commit.authorizationRevision}."
        } catch (failure: IllegalArgumentException) {
            showMutationError("Invalid local notification settings.")
        } catch (failure: PolicyRevisionRace) {
            showMutationError("Settings changed before they could be saved.")
        } catch (failure: PolicyStateCorrupted) {
            showMutationError("Local notification settings are corrupted and remain denied.")
        } catch (failure: IllegalStateException) {
            showMutationError("Local notification settings are unavailable.")
        }
    }

    private fun revokeSettings() {
        try {
            val snapshot = application.localNotificationAuthoritySnapshot()
            val commit = NotificationSettingsDraft(
                granted = false,
                deliveryMode = snapshot.deliveryMode,
                fieldAccess = snapshot.policy.fieldAccess,
                ruleMode = snapshot.policy.mode,
                packageIds = snapshot.policy.packageIds,
            ).commitAgainst(snapshot)
            application.localNotificationPolicyController().revoke(commit.authorizationRevision)
            statusText.text = "Local notification access revoked."
            grantSwitch.isChecked = false
        } catch (failure: IllegalArgumentException) {
            showMutationError("Invalid local notification settings.")
        } catch (failure: PolicyRevisionRace) {
            showMutationError("Settings changed before they could be revoked.")
        } catch (failure: PolicyStateCorrupted) {
            showMutationError("Local notification settings are corrupted and remain denied.")
        } catch (failure: IllegalStateException) {
            showMutationError("Local notification settings are unavailable.")
        }
    }

    private fun currentDraft(): NotificationSettingsDraft = NotificationSettingsDraft(
        granted = grantSwitch.isChecked,
        deliveryMode = if (deliveryModeGroup.checkedRadioButtonId == autoSendId) {
            NotificationDeliveryMode.AUTO_SEND
        } else {
            NotificationDeliveryMode.ON_DEMAND
        },
        fieldAccess = if (fieldAccessGroup.checkedRadioButtonId == contentId) {
            NotificationFieldAccess.CONTENT
        } else {
            NotificationFieldAccess.METADATA
        },
        ruleMode = if (ruleModeGroup.checkedRadioButtonId == denylistId) {
            NotificationRuleMode.DENYLIST
        } else {
            NotificationRuleMode.ALLOWLIST
        },
        packageIds = packageCheckBoxes.filterValues { it.isChecked }.keys.toList(),
    )

    private fun systemListenerStatus(): String {
        val listenerComponent = ComponentName(
            this,
            AgentLifeNotificationListenerService::class.java,
        ).flattenToString()
        val enabledListeners = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners",
        )
        return if (enabledListeners?.split(':')?.contains(listenerComponent) == true) {
            "System notification listener: enabled."
        } else {
            "System notification listener: disabled."
        }
    }

    private fun showMutationError(message: String) {
        statusText.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

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
    private fun renderSmsSettings() {
        val app = application as AgentLifeApplication
        val presenter = SmsSettingsPresenter(
            snapshotSource = app::smsSettingsSnapshot,
            permissionAvailability = { app.smsPermissionAvailability(hasReadSmsPermission()) },
        )
        val state = presenter.state()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val status = TextView(this).apply { text = statusMessage(state) }
        val grant = CheckBox(this).apply {
            text = "Enable SMS collection"
            isChecked = state.granted
        }
        val permissionButton = Button(this).apply {
            text = "Grant SMS permission"
            setOnClickListener {
                if (!hasReadSmsPermission()) {
                    requestPermissions(arrayOf(Manifest.permission.READ_SMS), READ_SMS_REQUEST_CODE)
                } else {
                    renderSmsSettings()
                }
            }
        }
        val startMode = spinner(SmsHistoryStartMode.entries.toList()).apply {
            setSelection(SmsHistoryStartMode.entries.indexOf(state.historyStartMode))
        }
        val historyStart = EditText(this).apply {
            hint = "History start time (milliseconds)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(state.historyStartEpochMs?.toString().orEmpty())
        }
        val maxRecords = EditText(this).apply {
            hint = "Maximum records"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(state.maxRecords.toString())
        }
        val interval = spinner(SmsSyncInterval.entries.toList()).apply {
            setSelection(SmsSyncInterval.entries.indexOf(state.syncInterval))
        }
        val onDemand = CheckBox(this).apply {
            text = "Allow on-demand SMS reads"
            isChecked = state.onDemandEnabled
        }
        val autoSend = CheckBox(this).apply {
            text = "Enable automatic SMS sync"
            isChecked = state.autoSendEnabled
        }
        val agentRequest = CheckBox(this).apply {
            text = "Allow Agent SMS requests"
            isChecked = state.agentMayRequest
        }
        val save = Button(this).apply {
            text = "Save SMS settings"
            setOnClickListener {
                val selectedStartMode = startMode.selectedItem as SmsHistoryStartMode
                val selectedInterval = interval.selectedItem as SmsSyncInterval
                val selectedMaxRecords = maxRecords.text.toString().toIntOrNull()
                val selectedHistoryStart = historyStart.text.toString().toLongOrNull()
                if (selectedMaxRecords == null ||
                    (selectedStartMode == SmsHistoryStartMode.FROM_EPOCH && selectedHistoryStart == null)
                ) {
                    status.text = "Enter a valid local SMS history setting."
                    return@setOnClickListener
                }
                try {
                    val payload = presenter.savePayload(
                        state.copy(
                            granted = grant.isChecked,
                            historyStartMode = selectedStartMode,
                            historyStartEpochMs = selectedHistoryStart,
                            maxRecords = selectedMaxRecords,
                            syncInterval = selectedInterval,
                            onDemandEnabled = onDemand.isChecked,
                            autoSendEnabled = autoSend.isChecked,
                            agentMayRequest = agentRequest.isChecked,
                        ),
                    )
                    app.localSmsSettingsController().update(
                        historyPolicy = payload.historyPolicy,
                        syncInterval = payload.syncInterval,
                        granted = payload.granted,
                        onDemandEnabled = payload.onDemandEnabled,
                        autoSendEnabled = payload.autoSendEnabled,
                        agentMayRequest = payload.agentMayRequest,
                    )
                    try {
                        app.smsJobScheduler().schedule(selectedInterval)
                    } catch (_: Throwable) {
                        status.text = "SMS settings were saved, but automatic scheduling failed."
                        return@setOnClickListener
                    }
                    renderSmsSettings()
                } catch (_: Throwable) {
                    status.text = "SMS settings could not be saved."
                }
            }
        }

        val notificationSettings = Button(this).apply {
            text = "Open notification settings"
            setOnClickListener {
                showingSmsSettings = false
                setContentView(buildSettingsView())
                refreshFromAuthority()
            }
        }

        root.addView(notificationSettings)
        root.addView(status)
        root.addView(grant)
        root.addView(permissionButton)
        root.addView(label("History start mode"))
        root.addView(startMode)
        root.addView(historyStart)
        root.addView(maxRecords)
        root.addView(label("Automatic sync interval"))
        root.addView(interval)
        root.addView(onDemand)
        root.addView(autoSend)
        root.addView(agentRequest)
        root.addView(save)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun <T> spinner(values: List<T>): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, values)
    }

    private fun hasReadSmsPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    private fun statusMessage(state: SmsSettingsViewState): String = when {
        state.corrupted -> "SMS settings are unavailable because local state is corrupted."
        state.permissionStatus == CapabilityAvailability.PERMISSION_REQUIRED -> "PERMISSION_REQUIRED"
        state.permissionStatus == CapabilityAvailability.DISABLED -> "SMS collection is disabled."
        else -> "SMS collection is ready."
    }

    private companion object {
        const val READ_SMS_REQUEST_CODE = 6001
    }
}

enum class SmsHistoryStartMode { ALL_HISTORY, FROM_EPOCH }

data class SmsSettingsViewState(
    val firstEnable: Boolean,
    val granted: Boolean,
    val permissionStatus: CapabilityAvailability,
    val historyStartMode: SmsHistoryStartMode,
    val historyStartEpochMs: Long?,
    val maxRecords: Int,
    val syncInterval: SmsSyncInterval,
    val onDemandEnabled: Boolean,
    val autoSendEnabled: Boolean,
    val agentMayRequest: Boolean,
    val corrupted: Boolean,
)

data class SmsSettingsSavePayload(
    val historyPolicy: SmsHistoryPolicy,
    val syncInterval: SmsSyncInterval,
    val granted: Boolean,
    val onDemandEnabled: Boolean,
    val autoSendEnabled: Boolean,
    val agentMayRequest: Boolean,
)

/** Read-only presenter: remote callers never receive a local mutation controller. */
class SmsSettingsPresenter(
    private val snapshotSource: () -> SmsSettingsSnapshot,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val permissionAvailability: () -> CapabilityAvailability,
) {
    fun state(): SmsSettingsViewState {
        val snapshot = snapshotSource()
        val firstEnable = !snapshot.corrupted && snapshot.policyRevision == 0uL && snapshot.authorizationRevision == 0uL
        val defaults = if (firstEnable) SmsSettingsDefaults.firstEnable(nowEpochMs()) else null
        val historyPolicy = defaults?.historyPolicy ?: snapshot.historyPolicy
        return SmsSettingsViewState(
            firstEnable = firstEnable,
            granted = snapshot.granted,
            permissionStatus = permissionAvailability(),
            historyStartMode = if (historyPolicy.fromEpochMs == null) {
                SmsHistoryStartMode.ALL_HISTORY
            } else {
                SmsHistoryStartMode.FROM_EPOCH
            },
            historyStartEpochMs = historyPolicy.fromEpochMs,
            maxRecords = historyPolicy.maxRecords,
            syncInterval = defaults?.syncInterval ?: snapshot.syncInterval,
            onDemandEnabled = snapshot.onDemandEnabled,
            autoSendEnabled = snapshot.autoSendEnabled,
            agentMayRequest = snapshot.agentMayRequest,
            corrupted = snapshot.corrupted,
        )
    }

    fun savePayload(state: SmsSettingsViewState): SmsSettingsSavePayload = SmsSettingsSavePayload(
        historyPolicy = SmsHistoryPolicy(
            fromEpochMs = if (state.historyStartMode == SmsHistoryStartMode.FROM_EPOCH) {
                requireNotNull(state.historyStartEpochMs) { "history start time is required" }
            } else {
                null
            },
            maxRecords = state.maxRecords,
        ),
        syncInterval = state.syncInterval,
        granted = state.granted,
        onDemandEnabled = state.onDemandEnabled,
        autoSendEnabled = state.autoSendEnabled,
        agentMayRequest = state.agentMayRequest,
    )
}
