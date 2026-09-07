// swift-tools-version:6.0
// Local core checks: compiles and tests the Foundation/CryptoKit-only data
// layer (shared contracts, strict manifest verifier, staged install) WITHOUT
// Xcode, ORT, UIKit or fake runtimes. This is NOT a replacement for the full
// iOS build; it covers the data layer only. Plain executable (no Testing
// framework): the CommandLineTools swift-plugin-server is unreliable for the
// @Test macro, so checks are plain assertions run by `swift run core-checks`.
import PackageDescription

let package = Package(
    name: "AuralisCore",
    // Explicit minimum: without it, Xcode 16.4's SwiftPM defaults the host
    // build to macosx10.13, where CryptoKit (10.15+) and back-deployed
    // Concurrency availability checks fail the whole `swift build` with exit 1
    // (CLT toolchains happen to default higher and hide the bug locally).
    platforms: [.macOS(.v10_15)],
    products: [.executable(name: "core-checks", targets: ["CoreChecks"])],
    targets: [
        .target(name: "AuralisCore", path: "ios/DialectInterpreter/Data"),
        .executableTarget(name: "CoreChecks", dependencies: ["AuralisCore"], path: "core/Checks",
                          swiftSettings: [.swiftLanguageMode(.v5)]),
    ],
    swiftLanguageModes: [.v5]
)
