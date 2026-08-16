package com.agentlife.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import com.agentlife.capability.CapabilityAvailability

class AndroidCallLogAvailability private constructor(
    private val seams: Seams,
) : CallLogAvailabilitySource {
    constructor(
        context: Context,
        reader: CallLogReader,
        localEnabled: () -> Boolean,
    ) : this(
        Seams(
            localEnabled = localEnabled,
            providerAvailable = {
                context.packageManager.resolveContentProvider(CallLog.AUTHORITY, 0) != null
            },
            permissionGranted = {
                context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
            },
            probe = reader::probe,
        ),
    )

    internal constructor(
        localEnabled: () -> Boolean,
        providerAvailable: () -> Boolean,
        permissionGranted: () -> Boolean,
        probe: () -> Unit,
    ) : this(Seams(localEnabled, providerAvailable, permissionGranted, probe))

    override fun current(): CapabilityAvailability = when {
        !seams.localEnabled() -> CapabilityAvailability.DISABLED
        !seams.providerAvailable() -> CapabilityAvailability.PLATFORM_UNSUPPORTED
        !seams.permissionGranted() -> CapabilityAvailability.PERMISSION_REQUIRED
        else -> try {
            seams.probe()
            CapabilityAvailability.READY
        } catch (_: CallLogPermissionRequiredException) {
            CapabilityAvailability.PERMISSION_REQUIRED
        } catch (_: SecurityException) {
            CapabilityAvailability.PERMISSION_REQUIRED
        } catch (_: Exception) {
            CapabilityAvailability.PLATFORM_UNSUPPORTED
        }
    }

    private data class Seams(
        val localEnabled: () -> Boolean,
        val providerAvailable: () -> Boolean,
        val permissionGranted: () -> Boolean,
        val probe: () -> Unit,
    )
}
