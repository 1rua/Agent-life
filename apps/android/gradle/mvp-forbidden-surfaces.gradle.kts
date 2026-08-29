/**
 * Static no-VPN/no-listener guard for the MVP.
 *
 * This intentionally scans source and merged manifests before packaging. The
 * mobile product is a userspace client of one ticket-bound Bridge; it does not
 * own a system tunnel, route, DNS, proxy or generic socket listener.
 */
import java.io.File

/**
 * Banned in every module, owners included: the product owns no system tunnel,
 * route, DNS, proxy or generic listener/dial surface.
 */
val alwaysForbidden = listOf(
    Regex("VpnService", RegexOption.IGNORE_CASE),
    Regex("BIND_VPN_SERVICE", RegexOption.IGNORE_CASE),
    Regex("TunInterface", RegexOption.IGNORE_CASE),
    Regex("\\bTUN\\b", RegexOption.IGNORE_CASE),
    Regex("addRoute", RegexOption.IGNORE_CASE),
    Regex("setHttpProxy", RegexOption.IGNORE_CASE),
    Regex("ProxyInfo", RegexOption.IGNORE_CASE),
    Regex("LocalAPI", RegexOption.IGNORE_CASE),
    Regex("\\bListen\\s*\\(", RegexOption.IGNORE_CASE),
    Regex("\\bDial\\s*\\(", RegexOption.IGNORE_CASE),
)

/**
 * Outbound HTTP/transport surfaces. These are no longer a blanket ban: the
 * Gateway client and an audited Companion transport adapter must own them, and
 * every other module must go through those owners.
 */
val forbiddenOutsideOwners = listOf(
    Regex("\\b(?:URLConnection|WebSocket|HttpClient|OkHttpClient|ServerSocket|DatagramSocket|Socket)\\b", RegexOption.IGNORE_CASE),
    Regex("\\b(?:URL|openConnection|createSocket)\\s*\\(", RegexOption.IGNORE_CASE),
)

val networkOwnerModules = setOf("gateway-client", "tailscale-companion", "companion-bridge")

/**
 * Generated output is not source: scanning it makes the gate depend on build
 * leftovers (stale androidTest result XML previously produced violations that
 * disappeared on a clean build).
 */
val generatedDirectoryNames = setOf("build", ".gradle", ".cxx", "generated")

/**
 * These files name the forbidden surfaces only to assert their absence on a
 * real device; they are the audits that enforce this gate. Granting them an
 * explicit, path-narrow exemption keeps every other module under the ban.
 */
val absenceAuditFiles = setOf(
    "app/src/androidTest/kotlin/com/agentlife/mobile/P0tAppNoVpnSurfaceInstrumentedTest.kt",
    "tailnet-core/src/androidTest/kotlin/com/agentlife/tailnet/core/P0tVpnSurfaceInstrumentedTest.kt",
)

fun scanNoVpnSurfaces(files: Iterable<File>, banned: List<Regex>): List<String> = buildList {
    files.filter { it.isFile && it.extension in setOf("kt", "java", "xml") }.forEach { file ->
        file.useLines { lines ->
            lines.forEachIndexed { index, line ->
                if (banned.any { it.containsMatchIn(line) }) {
                    add("${file.path}:${index + 1}: forbidden surface")
                }
            }
        }
    }
}

fun sourceFilesUnder(root: File): List<File> =
    root.walkTopDown()
        .onEnter { it.name !in generatedDirectoryNames }
        .filter { it.isFile && it.extension in setOf("kt", "java", "xml") }
        .filter { it.relativeTo(rootDir).path !in absenceAuditFiles }
        .toList()

tasks.register("noVpnSurfaceCheck") {
    group = "verification"
    description = "Reject system VPN, route/DNS, proxy/listener and generic dial surfaces."
    doLast {
        // Derived from the registered modules so a newly included module is
        // covered by this gate the moment it is added, not by a manual list edit.
        val violations = rootProject.subprojects.flatMap { project ->
            val banned = if (project.name in networkOwnerModules) {
                alwaysForbidden
            } else {
                alwaysForbidden + forbiddenOutsideOwners
            }
            scanNoVpnSurfaces(sourceFilesUnder(project.projectDir), banned)
        }
        check(violations.isEmpty()) { violations.joinToString("\n") }
    }
}

tasks.named("check") { dependsOn("noVpnSurfaceCheck") }

// Module-scoped checks must not bypass the same root-wide source scan.
subprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("noVpnSurfaceCheck"))
    }
}
