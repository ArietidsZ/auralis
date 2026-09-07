import Foundation

/// Loads the shared cross-platform contracts that are copied into the app
/// bundle by the "Sync shared contracts" build phase:
///   - shared/dialect-catalog/catalog.json  (dialect IDs are permanently stable)
///   - shared/model-manifests/*.json        (v2 unified field system)
///
/// The single source of truth lives in the repository `shared/` directory;
/// Kotlin, Swift and Python all consume the same bytes.
enum SharedContracts {
    struct DialectCatalog: Decodable {
        struct Dialect: Decodable {
            let id: String
            let displayLabel: String
            let shortLabel: String?
            let family: String?
            let asrLanguage: String
            let mtLanguage: String?
            let ttsLanguageCode: String?
        }

        struct TargetLanguage: Decodable {
            let id: String
            let displayLabel: String
            let asrLanguage: String?
            let mtLanguage: String?
        }

        let dialects: [Dialect]
        let targetLanguages: [TargetLanguage]
    }

    enum ContractsError: Error, LocalizedError {
        case missingResource(String)
        case decodingFailed(String)

        var errorDescription: String? {
            switch self {
            case .missingResource(let name): return "shared contract resource missing from bundle: \(name)"
            case .decodingFailed(let name): return "shared contract resource failed to decode: \(name)"
            }
        }
    }

    static func loadCatalog(bundle: Bundle = .main) throws -> DialectCatalog {
        guard let url = bundle.url(forResource: "catalog", withExtension: "json", subdirectory: "shared/dialect-catalog")
            ?? bundle.url(forResource: "catalog", withExtension: "json") else {
            throw ContractsError.missingResource("shared/dialect-catalog/catalog.json")
        }
        do {
            return try JSONDecoder().decode(DialectCatalog.self, from: Data(contentsOf: url))
        } catch {
            throw ContractsError.decodingFailed("catalog.json: \(error)")
        }
    }

    /// Raw manifest bytes; the strict decode lives in PackageVerifier.decodeManifest.
    static func manifestData(packageId: String, bundle: Bundle = .main) throws -> Data {
        guard let url = bundle.url(forResource: packageId, withExtension: "json", subdirectory: "shared/model-manifests")
            ?? bundle.url(forResource: packageId, withExtension: "json") else {
            throw ContractsError.missingResource("shared/model-manifests/\(packageId).json")
        }
        return try Data(contentsOf: url)
    }

    /// Strictly decoded manifest (PackageVerifier.Manifest is the single
    /// manifest type; there is no lenient mirror anymore).
    static func loadManifest(packageId: String, bundle: Bundle = .main) throws -> PackageVerifier.Manifest {
        do {
            return try PackageVerifier.decodeManifest(manifestData(packageId: packageId, bundle: bundle),
                                                      packageId: packageId)
        } catch let error as PackageVerifier.ManifestError {
            throw ContractsError.decodingFailed("\(packageId).json: \(error)")
        }
    }
}
