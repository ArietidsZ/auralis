package com.dialect.interpreter.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.boolean
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * C02/C03 contract cases, strict v2 (review item 3). The [fixtureSuite] test
 * consumes lane C's real fixtures from `shared/fixtures/` and asserts this
 * parser agrees with `convert/manifest_contract.py` on every case — B must not
 * be a looser superset of the shared contract.
 */
class ModelManifestsTest {

    @Test fun buildProvenanceFixtureSuite() {
        val shared = requireNotNull(fixturesRoot()).parentFile
        val cases = Json.parseToJsonElement(File(shared, "build-provenance-fixtures.json").readText()).jsonArray
        assertEquals(21, cases.size)
        for (item in cases) {
            val case = item.jsonObject
            val manifest = ModelManifests.parse(case.getValue("manifest").toString())
            val name = case.getValue("name").jsonPrimitive.content
            val result = runCatching { ModelManifests.validateBuildProvenance(manifest, case.getValue("record").toString()) }
            assertEquals("build provenance: $name / ${result.exceptionOrNull()}",
                case.getValue("valid").jsonPrimitive.boolean, result.isSuccess)
        }
    }

    // Locate shared/fixtures from the Gradle test working directory.
    private fun fixturesRoot(): File? {
        var dir = File(System.getProperty("user.dir")!!)
        repeat(4) {
            val candidate = File(dir, "shared/fixtures")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return null
        }
        return null
    }

    private fun parseExpectOk(json: String): ModelManifests.PackageManifest =
        ModelManifests.parse(json)

    private fun parseExpectError(json: String, code: String): Unit {
        try {
            ModelManifests.parse(json)
        } catch (e: ModelManifests.ContractError) {
            assertEquals("wrong code for $code (message: ${e.message})", code, e.code)
            return
        }
        fail("expected ContractError($code) but parse succeeded")
    }

    /**
     * Mirror the Python fixture runner (convert/manifest_contract.py:663-670):
     * `$expectedError` is the fixture's declared-expectation METADATA, popped
     * before validation — a real manifest must still reject it as an unknown
     * top-level key. Returns the stripped manifest text and the declared code.
     */
    private fun stripExpectedErrorMeta(content: String): Pair<String, String?> {
        val root = Json.parseToJsonElement(content).jsonObject
        val meta = root["\$expectedError"] ?: return content to null
        val code = (meta as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull
        return JsonObject(root - "\$expectedError").toString() to code
    }

    @Test
    fun `nonfinite constants fail syntax validation before manifest fields`() {
        for (constant in listOf("NaN", "Infinity", "-Infinity")) {
            parseExpectError("""{"nested":[$constant]}""", "bad-json")
        }
        // Quoted strings and valid exponent syntax reach schema validation.
        for (literal in listOf("\"NaN\"", "1e400", "-0.25E+12")) {
            parseExpectError("""{"unused":$literal}""", "unknown-schema")
        }
    }

    @Test
    fun `fixture suite agrees with the shared contract validator`() {
        val root = fixturesRoot()
            ?: throw AssertionError("shared/fixtures not found — run from android module checkout")
        val invalid = File(root, "invalid")
        val valid = File(root, "valid")
        assertTrue(invalid.isDirectory && valid.isDirectory)

        var manifestCases = 0
        for (file in valid.listFiles()!!) {
            if (!file.name.startsWith("manifest-")) continue // catalog fixtures are C-side
            manifestCases++
            val (stripped, _) = stripExpectedErrorMeta(file.readText())
            parseExpectOk(stripped)
        }
        for (file in invalid.listFiles()!!) {
            if (file.name.startsWith("catalog-")) continue // catalog fixtures are C-side
            manifestCases++
            val content = file.readText()
            if (file.name.endsWith(".invalidjson.json")) {
                parseExpectError(content, "bad-json")
            } else {
                val (stripped, declared) = stripExpectedErrorMeta(content)
                assertNotNull("fixture $file must declare an expected code", declared)
                parseExpectError(stripped, declared!!)
            }
        }
        assertTrue("fixture suite must cover manifests", manifestCases >= 20)
    }

    // ------------------------------------------------------------- happy path

    @Test
    fun `parses valid v2 manifest with pinned revision`() {
        val json = """
            {
              "schemaVersion": "2",
              "packageId": "mt",
              "version": "1.2.3",
              "status": "verified",
              "source": {
                "repoId": "AngelSlim/Hy-MT",
                "revision": "0123456789abcdef0123456789abcdef01234567",
                "upstreamModelId": "AngelSlim/Hy-MT",
                "licenseSource": "upstream"
              },
              "runtime": {
                "backend": "onnx",
                "apiContractVersion": "1",
                "runtimeRevision": "onnxruntime-1.22.0",
                "targetPlatforms": [{"platform": "android"}],
                "streaming": false
              },
              "capabilities": {
                "modes": ["translate"],
                "languages": ["zh"],
                "clone": false,
                "verification": {"languages": "bench-verified"}
              },
              "files": [
                {"path": "mt/model.gguf", "sizeBytes": 100, "sha256": "${"a".repeat(64)}", "classification": "model"},
                {"path": "mt/tokenizer.json", "sizeBytes": 10, "sha256": "${"b".repeat(64)}", "classification": "supportAsset"}
              ],
              "roles": {"decoder": "mt/model.gguf"}
            }
        """.trimIndent()
        val manifest = parseExpectOk(json)
        assertEquals("mt", manifest.packageId)
        assertEquals(ModelManifests.Status.VERIFIED, manifest.status)
        assertEquals(2, manifest.files.size)
        assertEquals("0123456789abcdef0123456789abcdef01234567", manifest.sourceRevision)
        assertNull(ModelManifests.validateForReady(manifest))
    }

    @Test
    fun `draft manifest with unpinned revision is rejected`() {
        val json = minimalDraft(revision = "main")
        parseExpectError(json, "revision-not-pinned")
    }

    @Test
    fun `draft manifest without revision is allowed`() {
        val manifest = parseExpectOk(minimalDraft(revision = null))
        assertEquals(ModelManifests.Status.DRAFT, manifest.status)
        assertEquals("模型尚未验证", ModelManifests.validateForReady(manifest))
    }

    // ---------------------------------------------------------- v2 strictness

    @Test
    fun `unknown schema rejected`() {
        parseExpectError(minimalDraft(schemaVersion = "3"), "unknown-schema")
    }

    @Test
    fun `unknown file key (incl v1 size_bytes) rejected`() {
        parseExpectError(
            draftWithFiles("""{"path": "mt/m", "size_bytes": 5, "classification": "model"}"""),
            "bad-value",
        )
    }

    @Test
    fun `unknown packageId rejected`() {
        parseExpectError(minimalDraft(packageId = "ocr"), "bad-value")
    }

    @Test
    fun `packageId mismatch with install target must be checked by callers`() {
        // Documented contract: ModelRepository compares manifest.packageId to
        // the installed package and fails the probe/install on mismatch.
        val manifest = parseExpectOk(minimalDraft(packageId = "mt"))
        assertEquals("mt", manifest.packageId)
    }

    @Test
    fun `empty hash string rejected`() {
        parseExpectError(draftWithFiles("""{"path": "mt/m", "sizeBytes": 5, "sha256": "", "classification": "model"}"""), "empty-hash")
    }

    @Test
    fun `empty file set can never be ready`() {
        // Verified + empty files is a parse error (C fixture empty-files-verified);
        // a draft manifest parses but validateForReady blocks it as unverified.
        val manifest = parseExpectOk(minimalDraft())
        assertEquals("模型尚未验证", ModelManifests.validateForReady(manifest))
    }

    @Test
    fun `duplicate paths rejected`() {
        parseExpectError(
            draftWithFiles(
                """{"path": "mt/m", "sizeBytes": 5, "classification": "model"},""" +
                    """{"path": "mt/m", "sizeBytes": 6, "classification": "model"}"""
            ),
            "duplicate-path",
        )
    }

    @Test
    fun `non-hex sha256 rejected`() {
        parseExpectError(
            draftWithFiles("""{"path": "mt/m", "sizeBytes": 5, "sha256": "XYZ", "classification": "model"}"""),
            "bad-value",
        )
    }

    @Test
    fun `unknown file key rejected`() {
        parseExpectError(
            draftWithFiles("""{"path": "mt/m", "size_bytes": 5, "classification": "model"}"""),
            "bad-value",
        )
    }

    private fun draftWithFiles(filesJson: String): String = minimalDraft()
        .replace("\"files\": []", "\"files\": [$filesJson]")

    @Test
    fun `roles must reference model files that exist`() {
        val json = """
            {
              "schemaVersion": "2",
              "packageId": "mt",
              "version": "1.0.0",
              "status": "draft",
              "source": {"repoId": "x/y", "upstreamModelId": "x/y", "licenseSource": "upstream"},
              "runtime": {"backend": "onnx", "apiContractVersion": "1", "targetPlatforms": [{"platform": "android"}], "streaming": false},
              "capabilities": {"modes": ["translate"], "languages": []},
              "files": [{"path": "mt/model.gguf", "sizeBytes": 5, "classification": "model"}],
              "roles": {"decoder": "mt/missing.gguf"}
            }
        """.trimIndent()
        parseExpectError(json, "role-missing")
    }

    @Test
    fun `verified manifest requires roles`() {
        val json = verifiedManifest(
            files = """[{"path": "mt/m.gguf", "sizeBytes": 5, "sha256": "${"a".repeat(64)}", "classification": "model"}]""",
            roles = "{}",
        )
        parseExpectError(json, "empty-file-set")
    }

    @Test
    fun `bad json and missing required sections rejected`() {
        parseExpectError("not json", "bad-json")
        parseExpectError("""{"schemaVersion": "2", "packageId": "mt"}""", "missing-required")
    }

    // ------------------------------------------------------- helpers

    private fun minimalDraft(
        schemaVersion: String = "2",
        packageId: String = "mt",
        revision: String? = null,
    ): String = """
        {
          "schemaVersion": "$schemaVersion",
          "packageId": "$packageId",
          "version": "0.1.0",
          "status": "draft",
          "source": {
            "repoId": "x/y",
            "upstreamModelId": "x/y",
            "licenseSource": "upstream"
            ${if (revision != null) ", \"revision\": \"$revision\"" else ""}
          },
          "runtime": {
            "backend": "onnx",
            "apiContractVersion": "1",
            "targetPlatforms": [{"platform": "android"}],
            "streaming": false
          },
          "capabilities": {"modes": ["translate"], "languages": []},
          "files": [],
          "roles": {}
        }
    """.trimIndent()

    private fun verifiedManifest(files: String, roles: String = """{"decoder": "mt/m.gguf"}"""): String = """
        {
          "schemaVersion": "2",
          "packageId": "mt",
          "version": "1.2.3",
          "status": "verified",
          "source": {
            "repoId": "x/y",
            "revision": "0123456789abcdef0123456789abcdef01234567",
            "upstreamModelId": "x/y",
            "licenseSource": "upstream"
          },
          "runtime": {
            "backend": "onnx",
            "apiContractVersion": "1",
            "runtimeRevision": "onnxruntime-1.22.0",
            "targetPlatforms": [{"platform": "android"}],
            "streaming": false
          },
          "capabilities": {"modes": ["translate"], "languages": ["zh"], "verification": {"languages": "bench-verified"}},
          "files": $files,
          "roles": $roles
        }
    """.trimIndent()

    // ------------------------------------------------------------- path guard

    @Test
    fun `path guard rejects traversal and absolute paths`() {
        for (bad in listOf(
            "../etc/passwd", "asr/../../x", "/absolute/path", "C:\\x", "asr\\win-path",
            "asr//double", "asr/./cur", "", " ", "asr/\u0000hidden", " leading/space",
        )) {
            assertTrue("expected rejection: '$bad'", !ModelPathGuard.isSafeRelativePath(bad))
        }
        assertTrue(ModelPathGuard.isSafeRelativePath("asr/tokenizer/tokenizer.json"))
        assertTrue(ModelPathGuard.isSafeRelativePath("mt/Hy-MT1.5-1.8B-1.25bit.gguf"))
    }

    @Test
    fun `safeResolve rejects symlink escaping root`() {
        val root = kotlin.io.path.createTempDirectory("modelroot").toFile()
        val outside = kotlin.io.path.createTempDirectory("outside").toFile()
        File(outside, "secret.txt").writeText("x")
        val link: File = File(root, "asr")
        try {
            try {
                java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
            } catch (e: UnsupportedOperationException) {
                return // filesystem without symlink support
            }
            assertNull(ModelPathGuard.safeResolve(root, "asr/secret.txt"))
            assertNull(ModelPathGuard.safeResolve(root, "asr"))
        } finally {
            link.delete()
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }
}
