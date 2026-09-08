# Auralis quality and efficiency pass

Baseline: `v0.1.0-preview.1` / `7e2cb57927d6290c089dce1c33a72bbc9902b6cc`.

Improve measured hot paths and resource ownership while preserving inference results, public contracts, cancellation and package-integrity guarantees. This is a source-quality branch; the published preview remains unchanged.

## Work and acceptance

| Area | Candidate | Required evidence |
|---|---|---|
| Android TTS | Full-vocabulary top-k sorting; prepared-reference ownership and failure cleanup | Fixed-seed equivalence including ties/masks; targeted lifecycle tests; measured CPU/allocation comparison |
| iOS TTS | Large KV output arrays reconstructed each step; tensor shape arithmetic | Real ORT value reuse and dtype/shape checks; lifetime/cancellation tests; output equality and copy-volume evidence |
| iOS capture | Unbounded active utterance and callback queues | Exact PCM preservation, 10 s bound, callback overflow and session-failure checks |
| Model tools | Duplicate file scans during acquisition/verification; failure classification | Real temporary-file I/O measurement; symlink/rollback/provenance tests; unchanged CLI exit-code contract |

Small focused units are permitted when they remove repetition or enable independent verification. Extra strategies, global caches, wrappers or dependency changes need measured justification. Compare the simpler candidate with the baseline and remove losing designs.

Sampling, model weights/precision, package status, pinned revisions, branding and Apache-2.0 licensing remain fixed. No blanket formatting or file-count target. Heavy model jobs and performance benchmarks run separately to avoid memory contention and misleading timing. Host/emulator results do not satisfy physical-device qualification.

## Validation

Preserve and run the affected behavioral tests first, then run the existing Android, Swift and Python integration checks once the lanes converge. Record failed experiments and limitations in the area reports; never replace failures with skips or relaxed timeouts.
