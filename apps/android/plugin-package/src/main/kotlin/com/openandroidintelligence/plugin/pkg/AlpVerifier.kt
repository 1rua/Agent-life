package com.openandroidintelligence.plugin.pkg

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

private const val MANIFEST_ENTRY = "manifest.json"
private const val FILES_ENTRY = "files.json"
private const val SIGNATURE_ENTRY = "signature.ed25519"
private val RESERVED_ENTRIES = setOf(MANIFEST_ENTRY, FILES_ENTRY, SIGNATURE_ENTRY)
private val SIGNATURE_DOMAIN = "OPEN-ANDROID-INTELLIGENCE-PLUGIN-PACKAGE-V1\n".toByteArray(Charsets.UTF_8)
private val PLUGIN_ID_PATTERN = Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+$")

/**
 * Verifies an `.alp` package before anything is trusted or installed.
 *
 * Order is fixed by the contract: container bounds → paths → count/size →
 * digests → author key → signature → schema → host compatibility. Security
 * changes are not decided here; [PluginUpdatePolicy] does that.
 */
class AlpVerifier(
    private val limits: PackageLimits,
    private val stagingRoot: File? = null,
    private val hostVersion: String = "2.0.0",
) {
    /** Maximum compressed bytes accepted from the wire; a real package is far smaller. */
    private val maxInputBytes = limits.maxTotalUncompressedBytes

    fun verify(input: InputStream): VerifiedPluginPackage {
        val archive = spoolToTempFile(input)
        return try {
            verifyArchive(archive)
        } finally {
            archive.delete()
        }
    }

    fun verify(bytes: ByteArray): VerifiedPluginPackage {
        val archive = java.nio.file.Files.createTempFile("alp-", ".zip").toFile()
        archive.writeBytes(bytes)
        return try {
            verifyArchive(archive)
        } finally {
            archive.delete()
        }
    }

    /**
     * The platform ZIP reader rejects some malformed containers itself. Its
     * exception is translated so every rejection leaves this class as a
     * [PackageRejected] carrying the contract's own code.
     */
    private fun verifyArchive(archive: File): VerifiedPluginPackage = try {
        verifyArchiveInner(archive)
    } catch (cause: java.util.zip.ZipException) {
        val message = cause.message.orEmpty()
        if (message.contains("duplicate", ignoreCase = true)) {
            throw PackageRejected("DUPLICATE_ENTRY")
        }
        // Android's ZIP reader screens entry names when the archive is opened,
        // so on a device it rejects `..` segments before [validateEntryName]
        // ever runs. The JVM reader does not, which is why this branch has a
        // device test and no unit test. The contract still demands that a
        // traversal surfaces as TRAVERSAL, not as a generic container error.
        if (message.contains("invalid zip entry path", ignoreCase = true)) {
            throw PackageRejected("TRAVERSAL:${cause.message}")
        }
        throw PackageRejected("CONTAINER_INVALID:${cause.message}")
    }

    private fun spoolToTempFile(input: InputStream): File {
        val file = java.nio.file.Files.createTempFile("alp-", ".zip").toFile()
        file.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                // Bounds the compressed stream itself: a hostile archive cannot
                // make us buffer unboundedly before size checks even run.
                if (total > maxInputBytes) {
                    file.delete()
                    throw PackageRejected("SIZE_LIMIT:input")
                }
                out.write(buffer, 0, read)
            }
        }
        return file
    }

    /**
     * Verification reads the central directory, not a streaming scan, so every
     * declared size and name is known before any payload byte is inflated.
     */
    private fun verifyArchiveInner(archive: File): VerifiedPluginPackage {
        val entries = LinkedHashMap<String, ByteArray>()
        var previousName: String? = null
        ZipFile(archive).use { zip ->
            var totalUncompressed = 0L
            for (entry in zip.entries()) {
                val name = validateEntryName(entry)
                if (entries.containsKey(name)) throw PackageRejected("DUPLICATE_ENTRY:$name")
                if (entries.size >= limits.maxEntries) throw PackageRejected("TOO_MANY_ENTRIES")

                // Declared sizes are checked before a single payload byte is read.
                val declared = declaredSize(entry)
                if (declared > limits.maxSingleEntryBytes) {
                    throw PackageRejected("SIZE_LIMIT:entry:$name")
                }
                totalUncompressed += declared
                if (totalUncompressed > limits.maxTotalUncompressedBytes) {
                    throw PackageRejected("SIZE_LIMIT:total")
                }
                // Container determinism: entries must ascend by UTF-8 path bytes.
                if (previousName != null && compareUtf8(previousName, name) >= 0) {
                    throw PackageRejected("UNSORTED_ENTRY:$name")
                }
                previousName = name

                entries[name] = readBounded(zip.getInputStream(entry), declared, name)
            }
        }

        val manifestBytes = entries[MANIFEST_ENTRY] ?: throw PackageRejected("MISSING_MANIFEST")
        val filesBytes = entries[FILES_ENTRY] ?: throw PackageRejected("MISSING_FILES_INDEX")
        val signatureText = entries[SIGNATURE_ENTRY]?.toString(Charsets.UTF_8)
            ?: throw PackageRejected("MISSING_SIGNATURE")

        val declared = parseFilesIndex(filesBytes)
        val presented = entries.keys - RESERVED_ENTRIES
        val undeclared = presented - declared.keys
        if (undeclared.isNotEmpty()) {
            throw PackageRejected("UNDECLARED_ENTRY:${undeclared.first()}")
        }
        val missing = declared.keys - presented
        if (missing.isNotEmpty()) {
            throw PackageRejected("MISSING_ENTRY:${missing.first()}")
        }

        for ((path, expected) in declared) {
            val actual = sha256Hex(entries[path]!!)
            if (!actual.equals(expected.sha256, ignoreCase = true)) {
                throw PackageRejected("DIGEST_MISMATCH:$path")
            }
            if (entries[path]!!.size.toLong() != expected.size) {
                throw PackageRejected("SIZE_MISMATCH:$path")
            }
        }

        val manifest = parseManifest(manifestBytes)
        val authorKey = decodeAuthorKey(manifest)

        verifySignature(manifestBytes, filesBytes, signatureText, authorKey)

        val staged = stagingRoot ?: createTempStaging()
        staged.mkdirs()
        for ((path, content) in entries) {
            if (path in RESERVED_ENTRIES) continue
            val target = File(staged, path)
            target.parentFile?.mkdirs()
            target.writeBytes(content)
        }

        return VerifiedPluginPackage(
            identity = PluginIdentity(
                pluginId = manifest.pluginId,
                authorKeyFingerprint = sha256Hex(authorKey),
                version = manifest.version,
            ),
            version = SemVer.parse(manifest.version) ?: throw PackageRejected("SCHEMA_INVALID:version"),
            runtime = manifest.runtime,
            capabilities = CapabilityDeclaration(manifest.providedCapabilities, manifest.kernelPrimitives),
            security = SecurityDeclaration(manifest.surface),
            stagedDirectory = staged,
        )
    }

    private fun validateEntryName(entry: ZipEntry): String {
        val name = entry.name
        if (name.isEmpty()) throw PackageRejected("EMPTY_ENTRY_NAME")
        if (name.startsWith("/")) throw PackageRejected("ABSOLUTE_PATH:$name")
        if (name.contains("\\")) throw PackageRejected("BACKSLASH_PATH:$name")
        for (segment in name.split("/")) {
            when (segment) {
                "" -> throw PackageRejected("EMPTY_SEGMENT:$name")
                "." -> throw PackageRejected("DOT_SEGMENT:$name")
                ".." -> throw PackageRejected("TRAVERSAL:$name")
            }
        }
        // NFC is required so the same path cannot be presented in two forms.
        if (name != java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC)) {
            throw PackageRejected("NON_NFC_PATH:$name")
        }
        return name
    }

    private fun declaredSize(entry: ZipEntry): Long {
        val size = entry.size
        // An unknown size cannot be bounded, so it is rejected rather than trusted.
        if (size < 0) throw PackageRejected("UNKNOWN_ENTRY_SIZE:${entry.name}")
        return size
    }

    private fun readBounded(zip: InputStream, declared: Long, name: String): ByteArray {
        val cap = minOf(declared, limits.maxSingleEntryBytes).toInt()
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            total += read
            // The declared size can lie; the byte count is the authority.
            if (total > cap) throw PackageRejected("SIZE_LIMIT:actual:$name")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun parseFilesIndex(bytes: ByteArray): Map<String, FileIndexEntry> {
        val root = Json.parse(bytes.toString(Charsets.UTF_8)) as? JsonValue.JArray
            ?: throw PackageRejected("SCHEMA_INVALID:filesIndex")
        val result = LinkedHashMap<String, FileIndexEntry>()
        var previous: String? = null
        for (item in root.items) {
            val obj = item as? JsonValue.JObject ?: throw PackageRejected("SCHEMA_INVALID:filesEntry")
            val path = (obj.get("path") as? JsonValue.JString)?.value
                ?: throw PackageRejected("SCHEMA_INVALID:filesPath")
            val sha = (obj.get("sha256") as? JsonValue.JString)?.value
                ?: throw PackageRejected("SCHEMA_INVALID:filesSha256")
            val size = (obj.get("size") as? JsonValue.JNumber)?.asLong()
                ?: throw PackageRejected("SCHEMA_INVALID:filesSize")
            if (!sha.matches(Regex("^[0-9a-f]{64}$"))) {
                throw PackageRejected("SCHEMA_INVALID:filesSha256Format:$path")
            }
            // The index must be sorted and duplicate-free.
            if (previous != null && previous >= path) {
                throw PackageRejected("FILES_INDEX_UNSORTED:$path")
            }
            previous = path
            result[path] = FileIndexEntry(sha256 = sha, size = size)
        }
        return result
    }

    private data class FileIndexEntry(val sha256: String, val size: Long)

    private data class ParsedManifest(
        val pluginId: String,
        val version: String,
        val runtime: RuntimeDeclaration,
        val providedCapabilities: Set<String>,
        val kernelPrimitives: Set<String>,
        val surface: SecuritySurface,
        val authorPublicKey: String,
    )

    private fun parseManifest(bytes: ByteArray): ParsedManifest {
        val root = Json.parse(bytes.toString(Charsets.UTF_8)) as? JsonValue.JObject
            ?: throw PackageRejected("SCHEMA_INVALID:manifest")

        val plugin = root.get("plugin") as? JsonValue.JObject
            ?: throw PackageRejected("SCHEMA_INVALID:plugin")
        val pluginId = (plugin.get("id") as? JsonValue.JString)?.value
            ?: throw PackageRejected("SCHEMA_INVALID:pluginId")
        if (!PLUGIN_ID_PATTERN.matches(pluginId)) throw PackageRejected("SCHEMA_INVALID:pluginIdFormat")

        val version = (plugin.get("version") as? JsonValue.JString)?.value
            ?: throw PackageRejected("SCHEMA_INVALID:pluginVersion")
        if (SemVer.parse(version) == null) throw PackageRejected("SCHEMA_INVALID:pluginVersionFormat")

        val author = root.get("author") as? JsonValue.JObject
            ?: throw PackageRejected("SCHEMA_INVALID:author")
        val algorithm = (author.get("algorithm") as? JsonValue.JString)?.value
        if (algorithm != "Ed25519") throw PackageRejected("SCHEMA_INVALID:authorAlgorithm")
        val publicKey = (author.get("publicKey") as? JsonValue.JString)?.value
            ?: throw PackageRejected("SCHEMA_INVALID:authorPublicKey")

        val runtimeObj = root.get("runtime") as? JsonValue.JObject
            ?: throw PackageRejected("SCHEMA_INVALID:runtime")
        val runtimeType = (runtimeObj.get("type") as? JsonValue.JString)?.value
            ?: throw PackageRejected("SCHEMA_INVALID:runtimeType")
        if (runtimeType !in setOf("protected-wasm", "developer-native", "companion")) {
            throw PackageRejected("SCHEMA_INVALID:runtimeTypeUnknown:$runtimeType")
        }
        val runtime = RuntimeDeclaration(
            type = runtimeType,
            abiVersion = (runtimeObj.get("abiVersion") as? JsonValue.JString)?.value,
            entrypoint = (runtimeObj.get("entrypoint") as? JsonValue.JString)?.value,
            payload = (runtimeObj.get("payload") as? JsonValue.JString)?.value,
        )

        val capabilities = root.get("capabilities") as? JsonValue.JObject
            ?: throw PackageRejected("SCHEMA_INVALID:capabilities")
        val provided = stringSet(capabilities, "provides", "id")
        val primitives = stringSet(capabilities, "kernelPrimitives", "id")

        val security = root.get("security") as? JsonValue.JObject
            ?: throw PackageRejected("SCHEMA_INVALID:security")
        val surface = parseSurface(security, runtimeType)

        val compatibility = root.get("compatibility") as? JsonValue.JObject
            ?: throw PackageRejected("SCHEMA_INVALID:compatibility")
        val androidHost = (compatibility.get("androidHost") as? JsonValue.JString)?.value
            ?: throw PackageRejected("SCHEMA_INVALID:androidHost")
        if (!hostSatisfies(hostVersion, androidHost)) {
            throw PackageRejected("INCOMPATIBLE_HOST:$androidHost")
        }

        return ParsedManifest(
            pluginId = pluginId,
            version = version,
            runtime = runtime,
            providedCapabilities = provided,
            kernelPrimitives = primitives,
            surface = surface,
            authorPublicKey = publicKey,
        )
    }

    private fun parseSurface(security: JsonValue.JObject, runtimeType: String): SecuritySurface {
        val network = security.get("network") as? JsonValue.JArray
            ?: throw PackageRejected("SCHEMA_INVALID:network")
        val hosts = network.items.mapNotNullTo(LinkedHashSet()) { item ->
            val obj = item as? JsonValue.JObject ?: throw PackageRejected("SCHEMA_INVALID:networkRule")
            (obj.get("host") as? JsonValue.JString)?.value
        }

        val background = security.get("background") as? JsonValue.JObject
            ?: throw PackageRejected("SCHEMA_INVALID:background")
        val backgroundRequested =
            (background.get("requested") as? JsonValue.JBoolean)?.value
                ?: throw PackageRejected("SCHEMA_INVALID:backgroundRequested")

        val resources = security.get("resources") as? JsonValue.JObject
            ?: throw PackageRejected("SCHEMA_INVALID:resources")

        fun longField(name: String): Long =
            (resources.get(name) as? JsonValue.JNumber)?.asLong()
                ?: throw PackageRejected("SCHEMA_INVALID:$name")

        val companionPackageName = if (runtimeType == "companion") {
            val runtime = security // companion package name lives on the runtime block
            (runtime.get("packageName") as? JsonValue.JString)?.value
        } else {
            null
        }

        return SecuritySurface(
            kernelPrimitives = emptySet(),
            networkHosts = hosts,
            maxStorageBytes = longField("maxStorageBytes"),
            maxMemoryBytes = longField("maxMemoryBytes"),
            maxInvocationMillis = longField("maxInvocationMillis"),
            maxConcurrentInvocations = longField("maxConcurrentInvocations").toInt(),
            maxDailyNetworkBytes = longField("maxDailyNetworkBytes"),
            backgroundRequested = backgroundRequested,
            companionPackageName = companionPackageName,
            nativeAbis = emptySet(),
        )
    }

    private fun stringSet(parent: JsonValue.JObject, field: String, idField: String): Set<String> {
        val array = parent.get(field) as? JsonValue.JArray
            ?: throw PackageRejected("SCHEMA_INVALID:$field")
        return array.items.mapNotNullTo(LinkedHashSet()) { item ->
            val obj = item as? JsonValue.JObject ?: throw PackageRejected("SCHEMA_INVALID:$field:entry")
            (obj.get(idField) as? JsonValue.JString)?.value
        }
    }

    private fun decodeAuthorKey(manifest: ParsedManifest): ByteArray {
        val raw = runCatching {
            Base64.getUrlDecoder().decode(manifest.authorPublicKey)
        }.getOrNull() ?: throw PackageRejected("SCHEMA_INVALID:authorPublicKeyEncoding")
        if (raw.size != 32) throw PackageRejected("SCHEMA_INVALID:authorPublicKeyLength")
        return raw
    }

    private fun verifySignature(
        manifestBytes: ByteArray,
        filesBytes: ByteArray,
        signatureText: String,
        authorKey: ByteArray,
    ) {
        val signature = runCatching {
            Base64.getUrlDecoder().decode(signatureText.trim())
        }.getOrNull() ?: throw PackageRejected("SIGNATURE_INVALID:encoding")
        if (signature.size != 64) throw PackageRejected("SIGNATURE_INVALID:length")

        val preimage = SIGNATURE_DOMAIN + manifestBytes + byteArrayOf('\n'.code.toByte()) + filesBytes
        val publicKey = runCatching { ed25519PublicKey(authorKey) }.getOrNull()
            ?: throw PackageRejected("SIGNATURE_INVALID:unsupportedKey")
        val verifier = runCatching {
            java.security.Signature.getInstance("Ed25519")
        }.getOrNull() ?: throw PackageRejected("SIGNATURE_INVALID:unsupportedAlgorithm")

        verifier.initVerify(publicKey)
        verifier.update(preimage)
        if (!verifier.verify(signature)) throw PackageRejected("SIGNATURE_INVALID")
    }

    /**
     * A raw 32-byte Ed25519 key is not an X.509 SubjectPublicKeyInfo; the
     * standard 12-byte prefix is prepended so the platform key factory accepts it.
     */
    private fun ed25519PublicKey(raw: ByteArray): java.security.PublicKey {
        val prefix = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        )
        val encoded = prefix + raw
        return java.security.KeyFactory.getInstance("Ed25519")
            .generatePublic(java.security.spec.X509EncodedKeySpec(encoded))
    }

    internal fun hostSatisfies(host: String, range: String): Boolean {
        val hostSemver = SemVer.parse(host) ?: return false
        // Supports the ">=a.b.c <d.e.f" form used by the contract.
        val bounds = range.trim().split("\\s+".toRegex())
        for (bound in bounds) {
            if (bound.startsWith(">=")) {
                val other = SemVer.parse(bound.removePrefix(">=")) ?: return false
                if (compare(hostSemver, other) < 0) return false
            } else if (bound.startsWith("<")) {
                val other = SemVer.parse(bound.removePrefix("<")) ?: return false
                if (compare(hostSemver, other) >= 0) return false
            } else {
                return false
            }
        }
        return true
    }

    private fun compareUtf8(a: String, b: String): Int {
        val ab = a.toByteArray(Charsets.UTF_8)
        val bb = b.toByteArray(Charsets.UTF_8)
        val len = minOf(ab.size, bb.size)
        for (i in 0 until len) {
            if (ab[i] != bb[i]) return ab[i].compareTo(bb[i])
        }
        return ab.size.compareTo(bb.size)
    }

    private fun compare(a: SemVer, b: SemVer): Int {
        if (a.major != b.major) return a.major.compareTo(b.major)
        if (a.minor != b.minor) return a.minor.compareTo(b.minor)
        return a.patch.compareTo(b.patch)
    }

    private fun createTempStaging(): File =
        java.nio.file.Files.createTempDirectory("alp-staging").toFile()

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
