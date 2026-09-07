import Testing
import Foundation
@testable import DialectInterpreter

/// Shared-contract tests. These verify the bundled shared/ resources decode
/// and that the honesty boundaries of the MT stage hold.
@MainActor
struct SharedContractsTests {

    @Test func catalogDecodesWithStableIds() throws {
        let catalog = try SharedContracts.loadCatalog()
        #expect(!catalog.dialects.isEmpty)
        #expect(!catalog.targetLanguages.isEmpty)
        let ids = catalog.dialects.map(\.id)
        #expect(ids.count == Set(ids).count, "dialect IDs must be unique")
        // IDs are permanently stable; spot-check canonical entries.
        #expect(ids.contains("mandarin"))
        #expect(ids.contains("cantonese"))
        #expect(ids.contains("minnan"))
        // Every dialect must model ASR prompt and TTS language separately.
        for dialect in catalog.dialects {
            #expect(!dialect.asrLanguage.isEmpty)
        }
    }

    @Test func manifestsDecodeAsV2() throws {
        for packageId in ["asr", "mt", "tts"] {
            let manifest = try SharedContracts.loadManifest(packageId: packageId)
            #expect(manifest.schemaVersion == "2")
            #expect(manifest.packageId == packageId)
            #expect(manifest.status == "draft" || manifest.status == "verified")
        }
    }

    @Test func mtManifestPinsUpstreamRevision() throws {
        let manifest = try SharedContracts.loadManifest(packageId: "mt")
        // Immutable-source rule: the MODEL repo revision must be a 40-char
        // commit SHA. It is NOT a runtime ref; runtime.runtimeRevision is a
        // separate pin and stays null until a runtime is actually verified.
        #expect(manifest.sourceRevision != nil,
                "MT manifest must pin an upstream revision (spec 01 C02)")
        #expect(manifest.roles["translator"] != nil)
    }

    @Test func draftManifestsAreNeverReady() async {
        let manager = OnnxModelManager()
        await manager.refreshStatuses()
        // The repository ships draft manifests with no model artifacts:
        // reporting anything but unavailable/not-installed would be a lie.
        switch manager.status.mt {
        case .manifestUnavailable, .notInstalled, .filesMissing:
            break
        case .ready, .error:
            Issue.record("draft manifest must never evaluate as ready")
        }
    }

    @Test func mtEngineNeverPassesThrough() async {
        let engine = HyMtTranslationEngine()
        let availability = await engine.isAvailable
        #expect(!availability, "without a linked native GGUF runtime, MT is unavailable")
        do {
            _ = try await engine.translate(text: "你好", sourceLanguage: "Chinese", targetLanguage: "English")
            Issue.record("translate must throw when the MT runtime is unavailable; passing text through is forbidden")
        } catch {
            // Expected: a typed failure, not a fake translation.
        }
    }

    @Test func mtEngineRejectsEmptyInput() async {
        let engine = HyMtTranslationEngine()
        do {
            _ = try await engine.translate(text: "  ", sourceLanguage: "Chinese", targetLanguage: "English")
            Issue.record("empty input must throw")
        } catch {
            // Expected.
        }
    }
}
