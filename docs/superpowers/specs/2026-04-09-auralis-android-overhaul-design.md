# Phase 2 Design: Auralis Android Premium Overhaul

## Status

- Approved in conversation for the Android-first delivery phase
- Delivery sequence selected: Android first, with later follow-up phases for the rest of the repo
- Primary target selected: premium UI with heavy performance work
- Product posture selected: premium consumer surface with pro signal available
- Screen scope selected: full Android brand pass
- Brand direction selected: global premium
- Visual direction selected: Quiet Premium
- Telemetry posture selected: subtle inline
- Primary user selected: mixed audience

## Context

The repository currently contains Android, iOS, and Python model tooling, but Android is the only product surface with meaningful implementation depth. The Android app already has a functioning interpretation flow, setup flow, voice profile flow, and settings view, but the current product still reads more like a polished prototype than a world-class application.

From the current inspection:

- the interpretation screen is the flagship experience, but it is implemented in a very large UI file
- the UI layer directly constructs and owns heavy runtime objects such as the pipeline, recorder, and player
- setup, interpretation, voice profile, and settings already exist, but they do not yet feel like one coherent premium product
- telemetry and runtime state are visible, but the presentation is inconsistent and tied too closely to implementation details
- there are no Android unit tests or Compose UI tests yet

This phase turns Android into the flagship product surface for the repository while creating a base that later iOS and tooling work can align with.

## Goals

- Rebrand the Android app around a premium global-facing identity named `Auralis`
- Turn live interpretation into a stronger flagship experience with clear product hierarchy
- Improve runtime ownership, state management, and perceived responsiveness in the interpretation flow
- Make the setup, voice profile, and settings surfaces feel like part of one product instead of adjacent utilities
- Add an Android verification baseline that covers behavior, UI states, and measurable performance

## Non-Goals

- Do not redesign iOS in this phase
- Do not restructure the whole repository in this phase
- Do not replace the existing ASR and TTS model stack in this phase
- Do not pursue deep model-level research work beyond app-level lifecycle and performance improvements
- Do not introduce a multi-tab application shell that competes with the interpretation workspace

## Design Summary

Phase 2 introduces a premium Android product identity and a cleaner runtime architecture at the same time.

- the shipping Android brand becomes `Auralis`
- the app keeps one dominant workspace instead of turning into a tabbed utility shell
- setup becomes a premium readiness gate rather than a maintenance screen
- interpretation becomes the hero surface with quiet-premium art direction and subtle inline telemetry
- voice profile and settings become secondary support surfaces with stronger product coherence
- live-session runtime ownership moves out of composables and into a dedicated stateful controller owned by the screen model layer
- automated tests and Android performance verification become part of the phase definition, not an afterthought

## Product Identity

### Brand

The Android app adopts `Auralis` as its customer-facing name for this phase.

- `Cross Dialect Communication` remains a repository or internal project reference, not the primary app-facing mark
- `Auralis` is used in launcher naming, major screen branding, and first-run presentation
- the identity should feel international, precise, and premium rather than academic or experimental

### Voice

The product voice should be calm, minimal, and confidence-oriented.

- status copy should be short and operational
- marketing language should stay sparse
- the app should sound like a premium tool, not a research demo

### Copy Direction

This phase uses English-first product branding and major navigation labels so the Android surface reads as a global product. Dialect names and language content still preserve their native forms where that improves recognition, and later localization work remains a separate concern.

## Visual Thesis

### Quiet Premium

The Android app should feel like a quiet premium voice interface.

- top-of-screen surfaces lean dark and cinematic
- working surfaces lean light, clear, and highly legible
- typography carries hierarchy more than containers or borders do
- one cool accent family does most of the visual signaling
- most sections should avoid card-heavy treatment

### Composition Principles

- the interpretation screen is the poster surface of the app
- the first viewport should clearly communicate brand, mode, and primary action in one glance
- the mic action should read as the center of gravity without becoming toy-like
- support screens should inherit the same material language without competing visually with the main workspace

### Motion Principles

- motion should be fast, controlled, and obviously intentional
- the mic interaction should have a subtle live pulse while listening
- transcript arrival should feel smooth and quiet rather than playful
- transitions between primary and support surfaces should feel continuous and premium, not flashy

## Screen Model

### Setup

`Setup` appears only when models are unavailable, corrupted, or fail integrity checks.

Responsibilities:

- communicate that the device is being prepared for offline voice interpretation
- show model readiness, install progress, expected storage footprint, and success or failure states
- provide one dominant primary action for install or retry

Design rules:

- setup is an onboarding gate, not a maintenance dashboard
- the layout should feel branded and intentional even when progress is waiting
- errors should be explicit and recoverable without exposing raw implementation detail

### Interpret

`Interpret` is the default destination when models are ready and the core flagship screen for the app.

Responsibilities:

- let users configure source and target language quickly
- let users start and stop a live session from one dominant control area
- present transcript history and translated output clearly
- surface pipeline status and latency in a subtle, readable form

Design rules:

- the top region is a dark branded control zone
- the transcript timeline is the main light workspace beneath it
- the source and target controls remain directly accessible, but compressed into a calm compact row
- the persistent bottom action dock owns the mic and live-session interaction
- telemetry stays inline and secondary, not dashboard-like

### Voice Profile

`Voice Profile` remains a separate screen, because it has enough depth to justify focus.

Responsibilities:

- list existing profiles
- guide recording of a new profile with strong confidence cues
- support preview, naming, save, and delete

Design rules:

- the screen should feel studio-like and deliberate rather than like a generic sheet utility
- recording guidance should reduce uncertainty with better progress and status feedback
- the primary action remains obvious without overpowering the rest of the surface

### Settings

`Settings` becomes a compact device and model intelligence view rather than a generic preferences screen.

Responsibilities:

- report execution provider, device information, and model readiness
- summarize storage and model state
- expose diagnostics-adjacent information in a polished readable form

Design rules:

- this screen can be denser than the interpretation surface
- it should still keep the same material system and typography discipline
- sections should communicate operational clarity, not developer clutter

## Navigation Model

The app keeps a single-workspace structure instead of adopting bottom navigation.

Primary flow:

1. `Setup -> Interpret`
2. from `Interpret`, open `Voice Profile`
3. from `Interpret`, open `Settings`

Supporting rules:

- source and target language selection open as a focused modal sheet
- telemetry does not become a top-level destination in this phase
- setup is a gate, not a peer destination after the app is ready

This structure preserves simplicity for everyday use while still supporting demos and more operational usage.

## Runtime Architecture

### Current Problem

The current interpretation surface couples composition too tightly to runtime setup.

- heavy runtime objects are created inside UI composition boundaries
- the UI file owns too many responsibilities at once
- transient live-session state and persistent transcript state are not separated clearly enough

This makes it harder to guarantee a smooth flagship experience and harder to verify lifecycle behavior with tests.

### Proposed Model

The interpretation flow gains a dedicated session/controller layer owned by the screen model layer rather than the composable tree.

Core boundaries:

- composables render stable UI models and send user intents
- the screen model owns state reduction and screen-level lifecycle
- a session controller owns start, stop, warmup, release, and event bridging for the pipeline
- the pipeline stays an application runtime dependency rather than a UI-created object

### Session State Model

The flagship screen should derive from one coherent interpretation session state model that can represent:

- setup readiness
- idle
- preparing
- listening
- recognizing
- synthesizing
- playing
- recoverable error

The state model should also carry:

- selected source dialect
- selected target language
- execution provider label
- inline telemetry summary
- live amplitude
- transcript timeline data
- action affordance state such as start, stop, retry, or disabled

### Persistent Versus Transient Data

The phase should explicitly separate two categories of data:

- persistent screen-session data: transcript timeline, selections, last known readiness, last resolved output
- transient live data: amplitude, active stage, in-flight timing, active warmup, and temporary failure state

This separation keeps transcript rendering stable while allowing the live session to update rapidly.

### Event Flow

The intended interpretation flow is:

1. the user changes dialect or language settings, or taps the primary mic action
2. the screen model accepts the intent
3. the session controller evaluates readiness and session lifecycle changes
4. the pipeline emits runtime events
5. the screen model reduces those events into one UI state
6. composables render the updated state in isolated regions

This design keeps the composable tree thinner and makes runtime behavior easier to test.

## Performance Model

### Target Outcome

The Android app should not just benchmark better. It should feel faster, calmer, and more predictable in active use.

### High-Leverage Performance Work

This phase prioritizes the following application-level improvements:

- move pipeline ownership outside composable creation
- reduce avoidable recomposition in the flagship screen
- isolate waveform and mic animation from transcript rendering
- keep transcript items append-oriented and keyed
- make warmup, loading, and ready states explicit so the app never appears stalled
- surface provider and readiness early so the user understands the device state immediately

### Perceived Performance

Perceived responsiveness matters as much as raw latency.

- the user should see clear preparing and ready states instead of silent waiting
- first-run install should feel intentional and premium rather than blocked
- first-session startup should communicate warmup clearly
- active-session UI updates should avoid visual jitter and unnecessary list churn

### Later Work Deferred

Deep ASR and TTS model-level optimization work is explicitly out of scope for this phase unless needed to support app lifecycle correctness. This phase focuses on the performance wins available through ownership, rendering, and session architecture.

## Error Handling Model

### Setup Errors

Setup must explicitly handle:

- missing model assets
- asset integrity failures
- extraction failure
- insufficient readiness to enter the interpretation screen

Each of these should map to clear UI copy and a local recovery action.

### Live Session Errors

The interpretation screen must explicitly handle:

- microphone permission issues
- model load failure during warmup
- pipeline exceptions during recognition or synthesis
- interrupted or failed playback

The user should see a local failure state with either retry or recovery guidance, not a silent reset.

### Boundary Rule

Raw implementation detail may be logged for debugging, but the UI should present clear human-readable status with an obvious next action.

## Component Responsibilities

### App Shell

The app shell owns start destination selection and support-screen routing.

### Interpretation Screen Model

The interpretation screen model owns:

- user intent handling
- transcript timeline state
- session state reduction
- event-to-UI translation

### Session Controller

The session controller owns:

- pipeline lifecycle
- start and stop behavior
- warmup and release
- event bridging from runtime into screen model state

### Runtime Pipeline

The runtime pipeline continues to own:

- audio capture
- VAD boundary detection
- ASR
- TTS
- playback

This phase changes how the pipeline is consumed, not what the pipeline fundamentally is.

## Testing Strategy

### Behavioral Tests

Add unit tests for:

- interpretation state reduction
- transcript timeline updates
- lifecycle transitions between idle, preparing, listening, and error states
- recovery behavior after session failure or retry

### Compose UI Tests

Add Compose UI coverage for:

- setup-ready and setup-required entry states
- idle interpretation state
- active listening state
- transcript rendering
- critical modal flows such as language selection
- visible error and retry states

### Performance Verification

The Android phase should include measurable evidence, not only subjective judgment.

Recommended checks:

- startup timing for the app
- time-to-interactive for the interpretation screen
- stability of transcript rendering during active updates

Preferred Android mechanisms for this phase:

- macrobenchmark coverage for startup and core navigation
- baseline profile generation to improve real-device startup and interaction smoothness

### Quality Gate

The Android-first phase should finish with a repeatable local verification set that includes build, lint, tests, and performance evidence.

## Success Criteria

This phase is successful when:

- Android launches into a clearly branded `Auralis` experience
- the interpretation screen feels like the unmistakable product center of gravity
- setup, voice profile, and settings all feel like part of one premium system
- live-session lifecycle is explicit, predictable, and recoverable
- runtime ownership is moved out of the composable surface and into a testable controller boundary
- Android has automated coverage for core behavior and UI states
- Android verification includes measurable performance evidence for the flagship flow

## Risks And Trade-Offs

- a pure visual pass would be faster, but would not fix the lifecycle and state ownership problems currently limiting the flagship screen
- a deep runtime-only pass could produce stronger technical performance, but would undershoot the product and brand quality requested for this phase
- this design intentionally balances product polish and engineering leverage, accepting a moderate implementation scope so Android can ship as a coherent premium milestone

## Follow-On Phases

After this Android phase ships, later work can extend the same product direction to:

- iOS parity work
- repository-level normalization and shared contracts
- deeper model/tooling performance work if needed

Those later phases should build on this Android milestone rather than dilute its focus.
