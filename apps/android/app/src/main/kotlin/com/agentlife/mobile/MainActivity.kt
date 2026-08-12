package com.agentlife.mobile

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.agentlife.capability.CapabilityAvailability
import com.agentlife.capability.SmsHistoryPolicy
import com.agentlife.capability.SmsSyncInterval
import com.agentlife.core.model.AssistantHandoffDecision
import com.agentlife.core.model.AssistantHandoffGate
import com.agentlife.core.model.AssistantHandoffRequest
import com.agentlife.core.model.DefaultAssistantHandoffGate
import com.agentlife.sms.SmsSettingsDefaults
import com.agentlife.sms.SmsSettingsSnapshot

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
        renderSmsSettings()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == READ_SMS_REQUEST_CODE) renderSmsSettings()
    }

    /** Source-only seam for the reviewed local handoff adapter. */
    fun evaluateAssistantHandoff(request: AssistantHandoffRequest): AssistantHandoffDecision =
        handoffGate.evaluate(request).also { lastHandoffDecision = it }

    fun currentAssistantHandoffDecision(): AssistantHandoffDecision = lastHandoffDecision

    private fun renderSmsSettings() {
        val app = application as AgentLifeApplication
        val presenter = SmsSettingsPresenter(
            snapshotSource = app::smsSettingsSnapshot,
            permissionAvailability = { app.smsPermissionAvailability(hasReadSmsPermission()) },
        )
        val state = presenter.state()
        val defaults = SmsSettingsDefaults.firstEnable(System.currentTimeMillis())
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
            setText((state.historyStartEpochMs ?: defaults.historyPolicy.fromEpochMs).toString())
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
                val selectedHistoryStart = if (selectedStartMode == SmsHistoryStartMode.FROM_EPOCH) {
                    historyStart.text.toString().toLongOrNull()
                } else {
                    null
                }
                if (selectedMaxRecords == null ||
                    (selectedStartMode == SmsHistoryStartMode.FROM_EPOCH && selectedHistoryStart == null)
                ) {
                    status.text = "Enter a valid local SMS history setting."
                    return@setOnClickListener
                }
                try {
                    app.localSmsSettingsController().update(
                        historyPolicy = SmsHistoryPolicy(selectedHistoryStart, selectedMaxRecords),
                        syncInterval = selectedInterval,
                        granted = grant.isChecked,
                        onDemandEnabled = onDemand.isChecked,
                        autoSendEnabled = autoSend.isChecked,
                        agentMayRequest = agentRequest.isChecked,
                    )
                    app.smsJobScheduler().schedule(selectedInterval)
                    renderSmsSettings()
                } catch (_: Throwable) {
                    status.text = "SMS settings could not be saved."
                }
            }
        }

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

    private fun label(value: String): View = TextView(this).apply { text = value }

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

/** Read-only presenter: remote callers never receive a local mutation controller. */
class SmsSettingsPresenter(
    private val snapshotSource: () -> SmsSettingsSnapshot,
    private val permissionAvailability: () -> CapabilityAvailability,
) {
    fun state(): SmsSettingsViewState {
        val snapshot = snapshotSource()
        return SmsSettingsViewState(
            granted = snapshot.granted,
            permissionStatus = permissionAvailability(),
            historyStartMode = if (snapshot.historyPolicy.fromEpochMs == null) {
                SmsHistoryStartMode.ALL_HISTORY
            } else {
                SmsHistoryStartMode.FROM_EPOCH
            },
            historyStartEpochMs = snapshot.historyPolicy.fromEpochMs,
            maxRecords = snapshot.historyPolicy.maxRecords,
            syncInterval = snapshot.syncInterval,
            onDemandEnabled = snapshot.onDemandEnabled,
            autoSendEnabled = snapshot.autoSendEnabled,
            agentMayRequest = snapshot.agentMayRequest,
            corrupted = snapshot.corrupted,
        )
    }
}
