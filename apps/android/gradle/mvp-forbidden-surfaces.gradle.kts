/**
 * Static no-VPN/no-listener guard for the MVP.
 *
 * This intentionally scans source and merged manifests before packaging. The
 * mobile product is a userspace client of one ticket-bound Bridge; it does not
 * own a system tunnel, route, DNS, proxy or generic socket listener.
 */
import java.io.File

val forbidden = listOf(
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
    Regex("\\b(?:ServerSocket|Socket|DatagramSocket)\\b", RegexOption.IGNORE_CASE),
    Regex("\\b(?:URLConnection|WebSocket|HttpClient|OkHttpClient)\\b", RegexOption.IGNORE_CASE),
    Regex("\\b(?:URL|openConnection|createSocket)\\s*\\(", RegexOption.IGNORE_CASE),
)

fun scanNoVpnSurfaces(files: Iterable<File>): List<String> = buildList {
    files.filter { it.isFile && it.extension in setOf("kt", "java", "xml") }.forEach { file ->
        file.useLines { lines ->
            lines.forEachIndexed { index, line ->
                if (forbidden.any { it.containsMatchIn(line) }) {
                    add("${file.path}:${index + 1}: forbidden surface")
                }
            }
        }
    }
}

tasks.register("noVpnSurfaceCheck") {
    group = "verification"
    description = "Reject system VPN, route/DNS, proxy/listener and generic dial surfaces."
    doLast {
        val roots = listOf("app", "assistant-holder", "artifact-ports", "core-model", "capability-ports", "control-ports", "policy-engine", "notification-collector", "tailnet-core", "transport", "encrypted-store")
            .map(::file)
        val violations = scanNoVpnSurfaces(roots.flatMap { root -> root.walkTopDown().toList() })
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
