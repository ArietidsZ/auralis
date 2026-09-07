package com.dialect.interpreter.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.URI
import java.text.Normalizer

/**
 * Model package manifest parsing and validation — strict v2 (spec 01 C02,
 * interface-C §1). This parser mirrors `convert/manifest_contract.py`
 * rule-for-rule (same stable error codes); there is deliberately no looser
 * "superset" acceptance. v1 JSON must go through C's migrate tool, not us.
 *
 * Stable error codes (same strings as the Python validator):
 * unknown-schema, field-conflict, empty-hash, empty-file-set, duplicate-path,
 * path-escape, role-missing, external-data-missing, placeholder-value,
 * missing-required, bad-value, bad-json.
 */
object ModelManifests {

    const val SCHEMA_VERSION_V2 = "2"
    val PACKAGE_IDS = listOf("asr", "mt", "tts")
    private val STATUSES = listOf("draft", "verified")
    private val CLASSIFICATIONS = listOf("model", "supportAsset")
    private val PLATFORMS = listOf("android", "ios")
    private val BACKENDS = listOf("onnx", "gguf-llama-cpp")
    private val MODES = listOf("transcribe", "translate", "synthesize", "clone")
    private val VERIFICATION_STATES = listOf("unverified", "bench-verified", "device-verified")
    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val REVISION_SHA = Regex("^[0-9a-f]{40}$")
    private val PLACEHOLDER_VERSION = Regex("(placeholder|todo|tbd)", RegexOption.IGNORE_CASE)
    private val ALLOWED_TOP_KEYS = setOf(
        "schemaVersion", "packageId", "version", "status", "source", "runtime",
        "capabilities", "files", "roles",
    )
    private val ALLOWED_FILE_KEYS = setOf("path", "sizeBytes", "sha256", "classification", "externalData")

    enum class Status { DRAFT, VERIFIED }

    class ContractError(val code: String, message: String) : Exception(message)

    data class ManifestFile(
        val path: String,
        val sizeBytes: Long?,
        val sha256: String?,
        val classification: String?,
        val externalData: List<String>,
    )

    data class PackageManifest(
        val schemaVersion: String,
        val packageId: String,
        val version: String,
        val status: Status,
        val sourceRevision: String?,
        val runtimeRevision: String?,
        val modes: List<String>,
        val clone: Boolean?,
        val files: List<ManifestFile>,
        val roles: Map<String, String> = emptyMap(),
        val runtimeBackend: String = "onnx",
        val apiContractVersion: String = "1",
        val buildRecipeId: String? = null,
        val buildProvenancePath: String? = null,
    ) {
        val isVerified: Boolean get() = status == Status.VERIFIED
    }

    private val json = Json { ignoreUnknownKeys = false }
    private val jsonNumber = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    /** Passive compiler record; called after staged/on-disk file hashing. */
    fun verifyBuildRecord(manifest: PackageManifest, packageDir: File) {
        val relative = manifest.buildProvenancePath?.removePrefix("${manifest.packageId}/") ?: return
        val file = ModelPathGuard.safeResolve(packageDir, relative)
            ?: throw ContractError("path-escape", "unsafe build record path")
        if (!file.isFile || file.length() > 1024 * 1024) {
            throw ContractError("bad-value", "build record missing or exceeds 1 MiB")
        }
        validateBuildProvenance(manifest, file.readText())
    }

    fun validateBuildProvenance(manifest: PackageManifest, content: String) {
        if (manifest.buildRecipeId == null) return
        rejectDuplicateKeys(content)
        val record = try { json.parseToJsonElement(content) as? JsonObject }
            catch (e: Exception) { throw ContractError("bad-json", "invalid build record") }
        fun requireRecord(ok: Boolean, message: String) {
            if (!ok) throw ContractError("bad-value", message)
        }
        requireRecord(record != null, "build record must be an object")
        record!!
        rejectInvalidPrimitives(record)
        requireRecord(record["schemaVersion"] == JsonPrimitive(1) && record.string("recipeId") == manifest.buildRecipeId,
            "build schema/recipe differs from manifest")
        val upstream = record.obj("upstream")
        requireRecord(upstream?.string("repoId") == "Qwen/Qwen3-TTS-12Hz-0.6B-Base" &&
            upstream?.string("revision") == manifest.sourceRevision, "build upstream differs from source")
        requireRecord(record.obj("sourceCode")?.string("revision") == "022e286b98fbec7e1e916cb940cdf532cd9f488e",
            "build source differs from audited recipe")
        val recipe = record.obj("recipe")
        requireRecord(recipe?.keys == setOf("path", "sha256"), "missing recipe path/hash")
        val recipePath = recipe!!.string("path") ?: ""
        requireRecord(ModelPathGuard.isSafeRelativePath(recipePath) && recipePath.endsWith(".py") &&
            validBuildHash(recipe.string("sha256")), "invalid recipe identity")
        requireRecord(record.obj("toolchain")?.string("onnxruntime") == "1.24.2", "build ORT must be 1.24.2")
        fun entries(key: String): Map<String, JsonObject> {
            val rows = record.array(key)
            requireRecord(!rows.isNullOrEmpty(), "build $key is empty")
            val result = linkedMapOf<String, JsonObject>()
            for (item in rows!!) {
                val row = item as? JsonObject ?: throw ContractError("bad-value", "invalid $key entry")
                val path = row.string("path") ?: ""
                if (!ModelPathGuard.isSafeRelativePath(path)) throw ContractError("path-escape", "unsafe build path")
                if (path in result) throw ContractError("duplicate-path", "duplicate build path: $path")
                val size = row["sizeBytes"] as? JsonPrimitive
                requireRecord(size != null && !size.isString && (size.longOrNull ?: 0) > 0 &&
                    validBuildHash(row.string("sha256")), "build input/output requires size/hash")
                result[path] = row
            }
            return result
        }
        entries("inputs")
        val outputs = entries("outputs")
        val expected = manifest.files.filter { it.path != manifest.buildProvenancePath }
            .associateBy { it.path.removePrefix("${manifest.packageId}/") }
        requireRecord(outputs.keys == expected.keys, "build outputs differ from manifest files")
        val external = manifest.files.flatMap { it.externalData }.toSet()
        for ((path, row) in outputs) {
            val target = expected.getValue(path)
            requireRecord(row.long("sizeBytes") == target.sizeBytes && row.string("sha256") == target.sha256,
                "build bytes differ from manifest: $path")
            if (row.containsKey("role")) {
                val graph = row.string("role")?.let { manifest.roles[it] }
                val graphEntry = manifest.files.firstOrNull { it.path == graph }
                requireRecord(target.path == graph || graphEntry?.externalData?.contains(target.path) == true,
                    "build role differs from manifest: $path")
            }
            val ext = row["externalData"]
            requireRecord(ext == null || (ext is JsonPrimitive && !ext.isString && ext.booleanOrNull != null),
                "externalData must be boolean")
            requireRecord(((ext as? JsonPrimitive)?.booleanOrNull ?: false) == (target.path in external),
                "build external data differs from manifest: $path")
        }
    }

    private fun validBuildHash(value: String?): Boolean = value != null && HEX64.matches(value) && value != "0".repeat(64)

    /** Parse + full v2 validation. Throws [ContractError] with a stable code. */
    fun parse(content: String): PackageManifest {
        val root = try {
            val parsed = json.parseToJsonElement(content)
            rejectDuplicateKeys(content)
            rejectInvalidPrimitives(parsed)
            parsed.let { it as? JsonObject }
                ?: throw ContractError("bad-json", "manifest must be a JSON object")
        } catch (e: ContractError) {
            throw e
        } catch (e: Exception) {
            throw ContractError("bad-json", "manifest is not valid JSON: ${e.message}")
        }

        val schemaVersion = root.string("schemaVersion")
        if (schemaVersion != SCHEMA_VERSION_V2) {
            throw ContractError("unknown-schema", "unsupported schemaVersion $schemaVersion; expected '2'")
        }

        val packageId = root.string("packageId")
        if (packageId == null || packageId !in PACKAGE_IDS) {
            throw ContractError("bad-value", "packageId must be one of $PACKAGE_IDS, got $packageId")
        }

        val statusRaw = root.string("status")
        if (statusRaw !in STATUSES) {
            throw ContractError("missing-required", "status must be one of $STATUSES, got $statusRaw")
        }
        val status = if (statusRaw == "verified") Status.VERIFIED else Status.DRAFT

        val version = root.string("version")
            ?: throw ContractError("missing-required", "version must be a non-empty string")
        if (version.isEmpty()) throw ContractError("missing-required", "version must be a non-empty string")
        if (status == Status.VERIFIED) {
            if (version == "0.0.0" || PLACEHOLDER_VERSION.containsMatchIn(version)) {
                throw ContractError("placeholder-value", "verified manifest has placeholder version $version")
            }
        }

        // source ------------------------------------------------------------
        val source = root.obj("source") ?: throw ContractError("missing-required", "source must be an object")
        if ((source.keys - setOf("repoId", "revision", "upstreamModelId", "licenseSource",
                "archive", "transform", "build")).isNotEmpty()) {
            throw ContractError("bad-value", "unknown source fields")
        }
        for (key in listOf("repoId", "upstreamModelId", "licenseSource")) {
            if (source.string(key).isNullOrBlank()) {
                throw ContractError("missing-required", "source.$key must be a non-empty string")
            }
        }
        val revision = source.string("revision")
        val archive = source["archive"]
        if (archive != null) {
            validateArchive(archive as? JsonObject
                ?: throw ContractError("bad-value", "source.archive must be an object"))
        }
        // Immutable-source rule (matches C): a non-null revision must be a full
        // 40-char lowercase git SHA — for draft AND verified (no moving refs).
        if (revision != null && !REVISION_SHA.matches(revision)) {
            throw ContractError(
                "revision-not-pinned",
                "source.revision $revision is not an immutable full 40-char commit SHA",
            )
        }
        if (status == Status.VERIFIED && revision.isNullOrBlank() && archive == null) {
            throw ContractError("revision-not-pinned", "verified manifest requires source.revision or pinned archive")
        }

        // runtime -------------------------------------------------------------
        val runtime = root.obj("runtime") ?: throw ContractError("missing-required", "runtime must be an object")
        if (runtime.string("backend") !in BACKENDS) {
            throw ContractError("bad-value", "runtime.backend must be one of $BACKENDS")
        }
        if (runtime.string("apiContractVersion").isNullOrBlank()) {
            throw ContractError("missing-required", "runtime.apiContractVersion must be a non-empty string")
        }
        val platforms = runtime.array("targetPlatforms")
        if (platforms.isNullOrEmpty()) {
            throw ContractError("empty-file-set", "runtime.targetPlatforms must be a non-empty array")
        }
        for (plat in platforms) {
            val obj = plat as? JsonObject ?: throw ContractError("bad-value", "targetPlatform entry invalid")
            if (obj.string("platform") !in PLATFORMS) {
                throw ContractError("bad-value", "targetPlatform platform must be one of $PLATFORMS")
            }
        }
        val streaming = (runtime["streaming"] as? JsonPrimitive)?.booleanOrNull
            ?: throw ContractError("bad-value", "runtime.streaming must be a boolean")
        val runtimeRevision = runtime.string("runtimeRevision")
        if (status == Status.VERIFIED && runtimeRevision.isNullOrBlank()) {
            throw ContractError("missing-required", "verified manifest requires runtime.runtimeRevision")
        }

        // capabilities ----------------------------------------------------------
        val caps = root.obj("capabilities") ?: throw ContractError("missing-required", "capabilities must be an object")
        val modes = caps.array("modes")?.map { m ->
            (m as? JsonPrimitive)?.contentOrNull ?: throw ContractError("bad-value", "capabilities.modes entries must be strings")
        } ?: throw ContractError("bad-value", "capabilities.modes must be an array")
        for (mode in modes) {
            if (mode !in MODES) throw ContractError("bad-value", "unknown mode $mode")
        }
        val languages = caps.array("languages")
        if (status == Status.VERIFIED) {
            if (modes.isEmpty()) throw ContractError("empty-file-set", "verified manifest requires non-empty capabilities.modes")
            if (languages.isNullOrEmpty()) {
                throw ContractError("empty-file-set", "verified manifest requires non-empty capabilities.languages")
            }
            if (caps.obj("verification") == null) {
                throw ContractError("missing-required", "verified manifest requires capabilities.verification states")
            }
        }
        caps.obj("verification")?.let { verification ->
            for ((key, stateNode) in verification) {
                val state = (stateNode as? JsonPrimitive)?.contentOrNull
                if (state !in VERIFICATION_STATES) {
                    throw ContractError("bad-value", "verification state must be one of $VERIFICATION_STATES, got $key=$state")
                }
            }
        }
        val clone = (caps["clone"] as? JsonPrimitive)?.booleanOrNull

        // files -------------------------------------------------------------------
        val filesNode = root.array("files") ?: throw ContractError("bad-value", "files must be an array")
        if (status == Status.VERIFIED && filesNode.isEmpty()) {
            throw ContractError("empty-file-set", "verified manifest requires a non-empty files list")
        }
        val seen = HashSet<String>()
        val files = filesNode.mapIndexed { index, item ->
            val entry = item as? JsonObject ?: throw ContractError("bad-value", "files[$index] must be an object")
            val unknown = entry.keys - ALLOWED_FILE_KEYS
            if (unknown.isNotEmpty()) {
                throw ContractError("bad-value", "files[$index] has unknown keys (v2 uses one field system): ${unknown.sorted()}")
            }
            val path = entry.string("path") ?: throw ContractError("missing-required", "files[$index].path missing")
            if (!ModelPathGuard.isSafeRelativePath(path)) {
                throw ContractError("path-escape", "unsafe file path: $path")
            }
            if (!seen.add(path)) throw ContractError("duplicate-path", "duplicate file path: $path")

            val sizeBytes = entry.long("sizeBytes")
            if (sizeBytes != null && sizeBytes <= 0) {
                throw ContractError("bad-value", "$path: sizeBytes must be a positive integer or null")
            }
            if (status == Status.VERIFIED && sizeBytes == null) {
                throw ContractError("missing-required", "$path: verified manifest requires sizeBytes")
            }

            val sha = entry.string("sha256")
            if (sha != null) {
                if (sha.isEmpty()) throw ContractError("empty-hash", "$path: sha256 must not be an empty string (use null)")
                if (!HEX64.matches(sha)) {
                    throw ContractError("bad-value", "$path: sha256 must be 64 lowercase hex chars")
                }
            }
            if (status == Status.VERIFIED) {
                if (sha == null) throw ContractError("missing-required", "$path: verified manifest requires sha256")
                if (sha == "0".repeat(64)) throw ContractError("placeholder-value", "$path: placeholder (all-zero) sha256")
            }

            val classification = entry.string("classification")
            if (classification !in CLASSIFICATIONS) {
                throw ContractError("bad-value", "$path: classification must be one of $CLASSIFICATIONS, got $classification")
            }

            val ext = entry.array("externalData")?.map { e ->
                (e as? JsonPrimitive)?.contentOrNull ?: throw ContractError("bad-value", "$path: externalData entries must be strings")
            }
            if (entry["externalData"] != null && ext == null) {
                throw ContractError("bad-value", "$path: externalData must be an array or null")
            }
            ManifestFile(path, sizeBytes, sha, classification, ext ?: emptyList())
        }

        for (file in files) {
            for (extPath in file.externalData) {
                if (!ModelPathGuard.isSafeRelativePath(extPath)) {
                    throw ContractError("path-escape", "unsafe externalData path: $extPath")
                }
                if (seen.none { it == extPath }) {
                    throw ContractError(
                        "external-data-missing",
                        "${file.path}: externalData $extPath is not present in files",
                    )
                }
            }
        }

        // roles -----------------------------------------------------------------------
        val roles = root.obj("roles") ?: throw ContractError("missing-required", "roles must be an object")
        for ((role, targetNode) in roles.entries) {
            val target = (targetNode as? JsonPrimitive)?.contentOrNull
                ?: throw ContractError("bad-value", "role $role must map to a path string")
            if (role.isBlank()) throw ContractError("bad-value", "role names must be non-empty strings")
            if (!ModelPathGuard.isSafeRelativePath(target)) throw ContractError("path-escape", "unsafe role path: $target")
            val targetFile = files.firstOrNull { it.path == target }
                ?: throw ContractError("role-missing", "role $role references $target which is not listed in files")
            if (targetFile.classification != "model") {
                throw ContractError("bad-value", "role $role must reference a classification=model file")
            }
        }
        if (status == Status.VERIFIED && roles.isEmpty()) {
            throw ContractError("empty-file-set", "verified manifest requires at least one role")
        }

        val unknownTop = root.keys - ALLOWED_TOP_KEYS
        if (unknownTop.isNotEmpty()) {
            throw ContractError("bad-value", "unknown top-level keys: ${unknownTop.sorted()}")
        }

        if (source.containsKey("build")) {
            val build = source.obj("build")
                ?: throw ContractError("bad-value", "source.build must be an object")
            if (build.keys != setOf("recipeId", "provenancePath") ||
                build.string("recipeId") != "auralis.qwen3-tts.api2.fp32.v1" ||
                packageId != "tts" || runtime.string("backend") != "onnx" ||
                runtime.string("apiContractVersion") != "2" || source.containsKey("transform")) {
                throw ContractError("bad-value", "unsupported build recipe or model API")
            }
            if (revision == null || !REVISION_SHA.matches(revision)) {
                throw ContractError("revision-not-pinned", "compiled package requires a pinned upstream revision")
            }
            val provenance = build.string("provenancePath") ?: ""
            if (!ModelPathGuard.isSafeRelativePath(provenance)) {
                throw ContractError("path-escape", "unsafe build provenance path")
            }
            if (!provenance.startsWith(packageId + "/") || !provenance.endsWith(".json") ||
                files.firstOrNull { it.path == provenance }?.classification != "supportAsset") {
                throw ContractError("bad-value", "build provenance must be a declared package JSON supportAsset")
            }
        }

        if (source.containsKey("transform")) {
            val transform = source.obj("transform")
                ?: throw ContractError("bad-value", "source.transform must be an object")
            if (transform.keys != setOf("id", "inputPath", "inputSha256") ||
                transform.string("id") != "hymt-stq42-to43-v1" ||
                transform.string("inputSha256") != "93e025c93cc082e73a3f142b757623a8b9cf541c020a8013ca4ee669556860ab" ||
                packageId != "mt" || source.containsKey("archive") || runtime.string("backend") != "gguf-llama-cpp") {
                throw ContractError("bad-value", "unknown or unaudited source transform")
            }
            if (!ModelPathGuard.isSafeRelativePath(transform.string("inputPath") ?: "")) {
                throw ContractError("path-escape", "unsafe transform.inputPath")
            }
            val output = files.singleOrNull()
            if (output == null || output.sha256 != "e42935e2c143be4c579109ef3a096b0b00796af2527eaa09be76331832d4c971" ||
                output.sizeBytes != 461860704L || root.obj("roles")?.string("translator") != output.path ||
                runtimeRevision != "1e411d8f5a1e23525fa3265dfb4bd76265465397") {
                throw ContractError("bad-value", "STQ transform requires its audited output size/hash/role/runtime")
            }
        }

        return PackageManifest(
            schemaVersion = schemaVersion,
            packageId = packageId,
            version = version,
            status = status,
            sourceRevision = revision,
            runtimeRevision = runtimeRevision,
            modes = modes,
            clone = clone,
            files = files,
            roles = roles.mapValues { (it.value as JsonPrimitive).content },
            runtimeBackend = runtime.string("backend")!!,
            apiContractVersion = runtime.string("apiContractVersion")!!,
            buildRecipeId = source.obj("build")?.string("recipeId"),
            buildProvenancePath = source.obj("build")?.string("provenancePath"),
        )
    }

    private fun validateArchive(archive: JsonObject) {
        if (archive.keys != setOf("url", "sha256", "sizeBytes", "stripPrefix")) {
            throw ContractError("bad-value", "archive requires only url/sha256/sizeBytes/stripPrefix")
        }
        val rawUrl = archive.string("url") ?: ""
        val url = runCatching { URI(rawUrl) }.getOrNull()
        if (url == null || url.scheme != "https" || url.host.isNullOrEmpty() ||
            url.userInfo != null || url.rawQuery != null || url.rawFragment != null || rawUrl.any { it.isWhitespace() }) {
            throw ContractError("bad-value", "archive.url must be HTTPS without credentials/query/fragment")
        }
        val sha = archive.string("sha256") ?: ""
        if (!HEX64.matches(sha) || sha == "0".repeat(64)) {
            throw ContractError("bad-value", "archive.sha256 must be a non-placeholder SHA-256")
        }
        val size = archive["sizeBytes"] as? JsonPrimitive
        if (size == null || size.isString || (size.longOrNull ?: 0) <= 0) {
            throw ContractError("bad-value", "archive.sizeBytes must be positive")
        }
        if (!ModelPathGuard.isSafeRelativePath(archive.string("stripPrefix") ?: "")) {
            throw ContractError("path-escape", "archive.stripPrefix must be a safe relative path")
        }
    }

    // JsonElement parsing accepts non-JSON bare constants such as NaN.
    // Validate their lexical form without converting valid large numbers.
    private fun rejectInvalidPrimitives(value: JsonElement) {
        when (value) {
            is JsonObject -> value.values.forEach(::rejectInvalidPrimitives)
            is JsonArray -> value.forEach(::rejectInvalidPrimitives)
            is JsonPrimitive -> if (!value.isString &&
                value.content !in setOf("true", "false", "null") &&
                !jsonNumber.matches(value.content)
            ) {
                throw ContractError("bad-json", "invalid JSON constant: ${value.content}")
            }
        }
    }

    /** Run after the library checks syntax; it otherwise silently accepts duplicate keys. */
    private fun rejectDuplicateKeys(content: String) {
        val objects = mutableListOf<MutableSet<String>>()
        var i = 0
        while (i < content.length) {
            when (content[i]) {
                '{' -> objects.add(mutableSetOf())
                '}' -> if (objects.isNotEmpty()) objects.removeAt(objects.lastIndex)
                '"' -> {
                    val start = i++
                    while (i < content.length) {
                        if (content[i] == '\\') { i += 2; continue }
                        if (content[i] == '"') break
                        i++
                    }
                    var next = i + 1
                    while (next < content.length && content[next].isWhitespace()) next++
                    if (next < content.length && content[next] == ':' && objects.isNotEmpty()) {
                        val raw = (json.parseToJsonElement(content.substring(start, i + 1)) as JsonPrimitive).content
                        val key = Normalizer.normalize(raw, Normalizer.Form.NFC)
                        if (!objects.last().add(key)) throw ContractError("bad-json", "duplicate JSON key: $key")
                    }
                }
            }
            i++
        }
    }

    /**
     * Readiness validation (C02/C03). Returns the blocking reason, or null when
     * the package may become Ready once files verify and the runtime probe passes.
     */
    fun validateForReady(manifest: PackageManifest): String? {
        if (!manifest.isVerified) return "模型尚未验证"
        if (manifest.files.isEmpty()) return "清单没有定义任何文件"
        for (f in manifest.files) {
            if (f.sizeBytes == null) return "文件 ${f.path} 缺少大小"
            if (f.sha256 == null) return "文件 ${f.path} 缺少 sha256"
        }
        return null
    }
}

/**
 * Path safety for package file resolution (spec C03.1): rejects absolute paths,
 * `..`, empty segments, backslash confusion and symlinks that escape [root].
 */
object ModelPathGuard {

    fun isSafeRelativePath(relative: String): Boolean {
        if (relative.isBlank()) return false
        if (relative != relative.trim()) return false
        if (relative.contains('\\')) return false
        if (relative.contains('\u0000')) return false
        if (relative.startsWith("/")) return false
        if (Regex("^[A-Za-z]:").containsMatchIn(relative)) return false
        val segments = relative.split('/')
        if (segments.isEmpty()) return false
        for (segment in segments) {
            if (segment.isEmpty() || segment == "." || segment == "..") return false
        }
        return true
    }

    /**
     * Resolve [relative] under [root], returning null when unsafe. Symlinks that
     * resolve outside [root] are rejected via canonical path comparison.
     */
    fun safeResolve(root: File, relative: String): File? {
        if (!isSafeRelativePath(relative)) return null
        val resolved = File(root, relative)
        val rootCanonical = root.canonicalFile
        val resolvedCanonical = try {
            resolved.canonicalFile
        } catch (e: Exception) {
            return null
        }
        if (resolvedCanonical != rootCanonical &&
            !resolvedCanonical.path.startsWith(rootCanonical.path + File.separator)
        ) {
            return null
        }
        return resolved
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull

private fun JsonObject.obj(key: String): JsonObject? =
    this[key] as? JsonObject

private fun JsonObject.array(key: String): List<kotlinx.serialization.json.JsonElement>? =
    (this[key] as? JsonArray)?.toList()
