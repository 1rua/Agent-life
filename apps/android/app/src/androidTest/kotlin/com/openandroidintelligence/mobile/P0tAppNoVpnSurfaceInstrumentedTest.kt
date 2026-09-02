package com.openandroidintelligence.mobile

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-app on-device audit: com.openandroidintelligence.mobile (the shipped app APK) must
 * expose no VpnService implementation, request no BIND_VPN_SERVICE, and keep
 * an audit surface limited to plain INTERNET networking (no VPN/network-hijack
 * permissions at all). This is the strongest device-level form of "本 app 不
 * 创建 VpnService、不抢占系统 VPN 槽位".
 */
@RunWith(AndroidJUnit4::class)
class P0tAppNoVpnSurfaceInstrumentedTest {

    private val appPackage = "com.openandroidintelligence.mobile"

    @Test
    fun appHasNoVpnServiceComponentAndNoBindVpnPermission() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = ctx.packageManager
        val info = pm.getPackageInfo(
            appPackage,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES,
        )

        val permissions = info.requestedPermissions ?: emptyArray()
        assertFalse(
            "com.openandroidintelligence.mobile must not request BIND_VPN_SERVICE",
            permissions.any { it == "android.permission.BIND_VPN_SERVICE" },
        )

        val services = info.services ?: arrayOf()
        val vpnServices = services.filter { service ->
            runCatching {
                val clazz = Class.forName(service.name, false, ctx.classLoader)
                android.net.VpnService::class.java.isAssignableFrom(clazz)
            }.getOrDefault(false)
        }
        assertEquals(
            "com.openandroidintelligence.mobile must register no VpnService implementation",
            0,
            vpnServices.size,
        )
    }

    @Test
    fun appPermissionSurfaceIsPlainInternetOnly() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = ctx.packageManager
        val info = pm.getPackageInfo(appPackage, PackageManager.GET_PERMISSIONS)
        assertNotNull(info)

        val permissions = (info.requestedPermissions ?: emptyArray()).toList()
        // The audited shipping app must never carry VPN/network-takeover
        // permissions; INTERNET is the only required networking permission.
        val forbidden = listOf(
            "android.permission.BIND_VPN_SERVICE",
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.CHANGE_NETWORK_STATE",
        )
        permissions.forEach { perm ->
            assertFalse("forbidden permission found: $perm", perm in forbidden)
        }
        assertTrue(
            "app must keep its minimal INTERNET surface, got $permissions",
            "android.permission.INTERNET" in permissions,
        )
    }
}
