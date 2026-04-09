# Phase 1 Design: Codebase Quality Foundation

## Status

- Approved in conversation for Phase 1 of a larger overhaul program
- Scope selected: codebase quality foundation first
- Workspace direction selected: full monorepo normalization now
- Quality gate selected: local-first

## Context

The project currently spans three technical domains in one workspace:

- `android/` for the Android client
- `ios/` for the iOS client
- `convert/` for Python-based model export, validation, and benchmarking

The current root communicates product intent, but not a shared operating model. Source code, generated outputs, caches, and temporary artifacts appear close together in the workspace. The platform directories also share concepts, but those concepts are not represented through explicit versioned contracts.

Phase 1 establishes a normalized workspace that behaves like one maintainable system without forcing fake runtime sharing between Android and iOS.

## Goals

- Reframe the workspace as one coordinated product repository instead of three adjacent folders
- Separate hand-written source from generated outputs, caches, reports, and temporary artifacts
- Define a root-level developer contract for setup, diagnosis, verification, and cleanup
- Introduce explicit shared contracts for cross-platform reference data such as dialect definitions and model manifests
- Make local verification deterministic, readable, and portable to future CI
- Prepare the repo for later Android, iOS, and shared product/design-system overhaul phases

## Non-Goals

- Do not create shared runtime code across Android and iOS in this phase
- Do not redesign app UX, screen flows, or platform-specific UI components in this phase
- Do not force identical internal architecture inside Android, iOS, and Python beyond what improves clarity and maintainability
- Do not replace native platform build systems with a custom root build abstraction

## Design Summary

Phase 1 adopts an aggressive workspace normalization strategy:

- move product applications under `apps/`
- move Python conversion tooling under `tooling/`
- introduce `shared/` for versioned cross-platform contracts and metadata
- add `docs/`, `scripts/`, and `config/` as first-class root ownership areas
- define explicit output boundaries so generated artifacts are disposable and never mistaken for source

The root becomes the governance layer. Android, iOS, and model tooling keep runtime and build independence, while shared metadata and developer workflows become standardized.

## Current-State Findings

From the current workspace inspection:

- the workspace root contains `android/`, `ios/`, `convert/`, and `README.md`
- Android already contains build outputs and Gradle state under the project tree
- Python tooling is documented, but its operating contract is local to `convert/`
- iOS and Android both express similar product concepts, but they do not consume shared versioned contracts from a common location
- there is no root-level `docs/` structure for architecture, runbooks, or specifications
- there is no root-level quality contract for setup, diagnosis, verification, or cleanup
- the workspace is not currently a git repository at the inspected root

## Proposed Workspace Shape

```text
.
|- apps/
|  |- android/
|  '- ios/
|- tooling/
|  '- model-convert/
|- shared/
|  |- dialect-catalog/
|  '- model-manifests/
|- docs/
|  |- architecture/
|  |- runbooks/
|  '- specs/
|- scripts/
|  |- bootstrap
|  |- doctor
|  |- verify
|  '- clean
|- config/
|  |- lint/
|  '- style/
'- README.md
```

### Directory Intent

- `apps/` contains product applications and only product-application code/assets/build metadata that belong to those applications
- `tooling/` contains developer-operated tooling, data conversion scripts, validation logic, and benchmark entrypoints
- `shared/` contains versioned, human-reviewable contracts that more than one domain consumes
- `docs/` contains durable human documentation, with architecture and operational guidance separated from ad hoc notes
- `scripts/` contains the canonical root entrypoints for local setup and verification workflows
- `config/` contains reusable workspace-wide conventions where centralization helps consistency

## Component Model

### Root Workspace

The root owns the developer contract. It defines how a contributor bootstraps the workspace, checks prerequisites, runs verification, and cleans disposable outputs. The root does not hide platform-native tools; it orchestrates them consistently.

### Android App

`apps/android/` owns Android runtime behavior, Android-specific architecture, Gradle configuration, and app packaging. It consumes shared contracts from `shared/`, but it does not depend on shared runtime code.

### iOS App

`apps/ios/` owns iOS runtime behavior, SwiftUI architecture, Xcode project state, and app packaging. It consumes shared contracts from `shared/`, but it remains natively designed and built.

### Model Tooling

`tooling/model-convert/` owns model export, quantization, validation, and benchmarking workflows. It produces artifacts and metadata through declared outputs rather than informal side effects.

### Shared Contracts

`shared/` is intentionally narrow. It exists for stable cross-platform inputs such as:

- dialect names, identifiers, display labels, and language relationships
- model package manifests
- checksums and version metadata
- other small, explicit contracts that both apps and tooling must agree on

This layer is not a dumping ground for convenience code.

## Shared Contract Boundaries

### `shared/dialect-catalog/`

This directory stores the canonical dialect reference set for the product. Android, iOS, and tooling read from the same source of truth instead of duplicating hard-coded lists. The representation should be versioned, machine-readable, and easy to validate.

#### Contract Interface

- canonical artifact: one versioned machine-readable catalog file plus optional human-facing reference notes
- preferred format: JSON for immediate cross-platform consumption, with a documented schema version field
- minimum required fields per dialect entry: stable identifier, display label, normalized language/dialect family, platform-visible short label, and any mapping fields required by current ASR/TTS consumers
- ownership: product/platform maintainers update semantic content; tooling may validate but does not redefine meaning
- consumers: Android UI, iOS UI, and any tooling that needs stable dialect metadata
- validation rules: unique identifiers, schema version presence, no duplicate display identifiers where uniqueness is required, and completeness checks for required consumer fields

### `shared/model-manifests/`

This directory stores model package definitions, integrity metadata, version identifiers, and package discovery information. Tooling publishes or updates these manifests; applications consume them.

#### Contract Interface

- canonical artifact: one manifest per model package family plus an optional index file if multiple packages are distributed together
- preferred format: JSON with explicit schema versioning
- minimum required fields: model identifier, semantic version, source package name, expected files, sizes, checksums, supported execution targets, and any required compatibility metadata
- ownership: tooling maintainers publish manifest structure and updates; app maintainers consume the stable interface
- consumers: model conversion tooling, Android model extraction/integrity logic, and iOS model acquisition/integrity logic
- validation rules: schema version presence, checksum algorithm declaration, completeness of required file entries, and referential consistency between manifest index and per-package data

### Boundary Rule

Only information that is both cross-platform and contract-like belongs in `shared/`. If a file is implementation-specific, build-specific, or temporary, it stays with its owning domain.

### Ownership Rule

Each shared contract must name one primary owning domain, even if multiple domains consume it. Cross-platform does not mean ownerless.

## Data Flow

The normalized workspace uses a controlled flow:

1. authors update source in `apps/`, `tooling/`, `shared/`, and `docs/`
2. shared contracts are validated before platform verification begins
3. root commands delegate to domain-native workflows
4. platform and tooling outputs are written into declared output locations
5. root verification reports one workspace-level result while preserving domain ownership of failures

### Flows That Move Forward

- dialect catalog definitions
- model manifests and checksum metadata
- setup and runbook documentation
- validation reports that are intended to be durable or reviewable

### Flows That Stay Contained

- Gradle caches and APK outputs
- Xcode derived data and local build products
- Python virtual environments and temporary conversion outputs
- brainstorm screens, scratch files, and transient reports

## Developer Command Contract

Phase 1 defines a small root command surface.

### `scripts/bootstrap`

- prepares the local workspace for development
- documents or automates prerequisite setup where reasonable
- establishes expected local directories and dependencies
- does not silently mutate unrelated developer state

### `scripts/doctor`

- inspects the workspace and local machine for expected prerequisites
- verifies required tools, directories, shared contracts, and output boundaries
- explains failures in human-readable terms with explicit fixes

### `scripts/verify`

- acts as the canonical local quality gate
- delegates to shared-contract checks, tooling validation, Android verification, and iOS verification
- emits a readable summary and stable exit codes
- supports `fast`, `full`, and scoped domain execution so contributors can validate what they can run locally without losing one canonical command surface

### `scripts/clean`

- removes disposable outputs only from declared output locations
- never deletes source-owned directories
- is safe-by-default and predictable

### Command Modes and Scope

- `bootstrap`: may support domain-specific setup targets, but root bootstrap remains the documented starting point
- `doctor`: reports whole-workspace health and may also report per-domain health sections
- `verify --mode fast`: shared contract checks plus only the domains explicitly available or selected locally
- `verify --mode full`: shared contract checks plus Android, iOS, and tooling verification; intended for machines with full prerequisites and for future CI mirroring
- `verify --scope <domain>`: runs the requested domain plus any prerequisite shared contract validation
- `clean --scope <domain|all>`: removes only declared generated outputs for the chosen scope

### Exit-Code Categories

- `0`: success
- `1`: verification or validation failure inside a domain
- `2`: missing prerequisites or invalid local environment
- `3`: invalid shared contract inputs or incompatible workspace state
- `4`: command usage error

## Error Handling Model

Error handling is designed for ownership clarity.

### Prerequisite Failures

`bootstrap` and `doctor` report failures by domain, for example:

- Android SDK or JDK missing
- Xcode or simulator tooling unavailable
- Python environment or dependency issue
- shared contract files missing or malformed
- required model assets unavailable

### Verification Failures

`verify` preserves source attribution. Failures should read like:

- Android lint failed
- iOS scheme build failed
- tooling validation failed
- shared model manifest checksum mismatch

The root command should not collapse domain-specific failures into one opaque message.

### Shared Contract Failures

If `shared/dialect-catalog/` or `shared/model-manifests/` are invalid, both applications are treated as blocked because their shared inputs cannot be trusted.

### Cleanup Safety

`clean` removes only declared caches, reports, exports, and build outputs. It must not infer deletion targets from vague patterns.

## Output Boundary Matrix

The workspace needs explicit ownership for generated output.

| Domain | Source-Owned Directories | Generated/Disposable Directories | Notes |
|---|---|---|---|
| Root | `README.md`, `docs/`, `scripts/`, `config/`, `shared/` | `.superpowers/` | `.superpowers/` is local scratch output and should be ignored by default |
| Android | `apps/android/` source, Gradle config, checked-in resources | `apps/android/.gradle/`, `apps/android/**/build/` | build outputs and Gradle state are disposable |
| iOS | `apps/ios/` source, Xcode project files, checked-in resources | Xcode DerivedData and local build products outside source-owned paths | local build products remain disposable and must not mix with source |
| Tooling | `tooling/model-convert/` scripts, schemas, checked-in templates | virtual environments, temporary exports, benchmark outputs unless intentionally promoted | durable published metadata moves into `shared/` only by explicit promotion |
| Reports | durable runbooks/specs under `docs/` | transient validation reports outside `docs/` unless intentionally archived | generated reports are not documentation by default |

`scripts/clean`, ignore rules, and verification logic must all use this matrix as the authoritative boundary reference.

## Testing and Verification Strategy

Local-first validation is the primary design choice.

### Canonical Verification Flow

`scripts/verify` runs a deterministic sequence such as:

1. shared contract validation
2. tooling checks
3. Android checks
4. iOS checks

This sequence may support fast and full modes, but the command surface stays stable.

### Verification Scope Rules

- shared contract validation always runs first because all consuming domains depend on it
- `fast` mode is for local iteration and may skip domains whose prerequisites are unavailable unless explicitly requested
- `full` mode requires Android, iOS, and tooling prerequisites on the same machine and fails with a prerequisite-class error if any required domain cannot run
- scoped verification is part of Phase 1 so contributors can validate one domain without pretending every workstation is identical
- future CI should mirror `verify --mode full` on machines provisioned for complete workspace validation

### Underlying Native Checks

- Android verification continues to use Gradle tasks
- iOS verification continues to use Xcode or `xcodebuild`-based checks
- model tooling verification continues to use Python-native scripts and validation steps

The root layer coordinates these checks; it does not replace them.

### Shared Contract Tests

Phase 1 adds contract tests for `shared/`, including schema, completeness, referential integrity, and checksum validation where relevant. Shared-contract validation runs before app-specific checks to fail early.

### Success Condition

A new contributor can follow one documented setup path and one documented verification path from the workspace root without guessing which platform-specific steps are required.

They can also explicitly choose scoped verification when they are working in one domain or do not yet have full cross-platform prerequisites installed.

## Documentation Model

Phase 1 introduces durable documentation structure:

- `docs/architecture/` for how the workspace is organized and why
- `docs/runbooks/` for common operational tasks such as setup, verification, model refresh, and cleanup
- `docs/specs/` for approved design and implementation specs

The root `README.md` should become a concise entry document that points readers to the right deeper docs instead of carrying every operational detail itself.

## Generated Output Policy

The workspace should enforce a simple rule: generated output is not source.

### Policy

- build products, caches, reports, temporary exports, and scratch artifacts live only in declared output areas
- those output areas are either ignored or explicitly documented as generated
- root commands know where generated output lives
- generated artifacts that must be preserved are promoted intentionally, not incidentally

### Examples

- platform build outputs remain under platform-owned build output boundaries
- temporary model export products remain tooling-owned until intentionally published
- brainstorm artifacts under `.superpowers/` are treated as workspace-local scratch output and should not be versioned by default

## Migration Strategy

Phase 1 is aggressive, but still staged.

### Step 1: Introduce Root Ownership Areas

Create `apps/`, `tooling/`, `shared/`, `docs/`, `scripts/`, and `config/` with documented intent.

This step also establishes the workspace root as the monorepo control point for versioned governance. If the current root is not yet a git repository, creating or attaching root-level version control is an enabling part of Phase 1 rather than an external assumption.

### Step 2: Relocate Existing Domains

- move `android/` to `apps/android/`
- move `ios/` to `apps/ios/`
- move `convert/` to `tooling/model-convert/`

### Step 3: Extract Shared Contracts

Move duplicated or implicit cross-platform reference data into `shared/` and update consumers.

During migration, existing Android, iOS, and tooling consumers may temporarily use compatibility shims or redirected file paths, but Phase 1 should end with all supported consumers reading the canonical shared contract locations directly.

### Step 4: Define Root Commands

Add `bootstrap`, `doctor`, `verify`, and `clean` as the official local entrypoints.

### Step 5: Normalize Ignore and Output Boundaries

Document and enforce generated-output locations. Add ignore rules for caches, build products, and local scratch directories such as `.superpowers/`.

### Step 6: Rewrite Root Documentation

Recast the root `README.md` as a navigation layer into architecture docs, runbooks, and platform/tooling specifics.

## Risks and Trade-Offs

### Benefits

- stronger workspace clarity
- easier onboarding
- fewer accidental commits of generated artifacts
- better foundation for Android and iOS redesign phases
- future CI can mirror local workflows instead of inventing separate ones

### Costs

- high path churn during relocation
- temporary disruption to existing local habits and scripts
- need to update references inside platform projects and docs
- some shared-contract extraction work may uncover hidden assumptions in current code
- root-level version control migration or initialization may require careful handling if history currently lives elsewhere

### Why This Trade-Off Is Accepted

The user explicitly chose an aggressive approach and selected full monorepo normalization over thinner governance. Because later overhaul phases depend on shared structure and reliable workflows, the upfront disruption is justified.

## Acceptance Criteria

Phase 1 is complete when all of the following are true:

- the workspace is reorganized under the approved root structure
- root commands exist for bootstrap, doctor, verify, and clean
- Android, iOS, and model tooling are reachable through documented root workflows
- shared dialect and model metadata live under explicit versioned contracts
- generated outputs are separated from source and handled through declared boundaries
- root documentation explains workspace structure and contributor workflows clearly
- local verification produces domain-attributed failures and a single workspace-level result

## Out of Scope for Later Phases

The following remain for later specs and implementation phases:

- shared product/design system definition
- Android architecture and UI overhaul
- iOS architecture and UI overhaul
- any broader CI rollout beyond mirroring the local-first contract

## Recommendation

Proceed with full monorepo normalization in Phase 1, but keep the `shared/` layer narrow and contract-focused. Use the root to unify governance and local verification, while preserving platform-native runtime independence.
