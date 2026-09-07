import Foundation

// Core data-layer checks entry point. Exits 1 on any failure; every failure
// names the violated rule. No network, no ORT, no fakes.
try runFixtureChecks()
try await runInstallChecks()
try await runVoiceProfileChecks()

if !log.containsFailures() {
    print("core-checks: ALL PASS")
} else {
    let failures = log.dump()
    print("core-checks: \(failures.count) FAILURE(S):")
    for failure in failures { print("  - \(failure)") }
    exit(1)
}
