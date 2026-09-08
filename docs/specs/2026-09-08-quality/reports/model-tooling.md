# Model acquisition and verification — 2026-09-08

Baseline: `v0.1.0-preview.1` (`7e2cb57927d6290c089dce1c33a72bbc9902b6cc`). Model pins, readiness, precision and package layout are unchanged.

## Implementation

`file_integrity.py` supplies one SHA-256 reader and one acquisition primitive. `fetch_model`, `validate_models`, `model_tasks`, `mt_runner` and `tts_runner` reuse it. Existing `file_hash` and `sha256_file` names remain import aliases. Python 3.10 uses the chunked fallback when `hashlib.file_digest` is unavailable. The MT runner's default paths now use the current user's cache or `AURALIS_CACHE`, with `HYMT_LIB` / `HYMT_MODEL` overrides retained.

For local files, APFS `clonefile` followed by one read-back hash avoids copying file contents. Immutable or append-only sources use streaming copy because cloning their flags makes the staged file impossible to replace or delete. The same fallback handles unsupported platforms, filesystems or symbols. Both paths return `(size, digest)` to the existing pinned-hash gate. Digests stay in process and are never persisted as readiness markers.

Downloads hash incoming bytes and reject overlong or truncated input. Archives are fully authenticated **before** tar parsing; each selected regular member is then hashed while being copied. Local archives retain their required pre-hash pass. Symlink, traversal, duplicate selected-file, missing-file and per-member integrity checks remain intact.

Staging, leases, rollback, provenance checks and package manifests retain their existing boundaries. `install()` fsyncs every staged file and the affected directories before promotion; no extra source fsync remains in the clone helper.

| Acquisition path | Baseline full-file passes | Final passes |
|---|---|---|
| Local copy | 2 reads + 1 write | Clone: 1 read; fallback: 1 read + 1 write |
| Downloaded archive | Write + archive hash read + tar read | Hash during write + tar read |
| Local archive | Archive hash read + tar read | Same, to authenticate before parsing |
| Extracted member integrity | Additional read of each staged member | Hash during extraction |
| Transform output gate | Two reads of final output | One verified digest reused by the gate |

The audited transform script retains its own mandatory input/output verification. These pass counts are structural; they are not multi-GB latency measurements.

## Failure behavior

- A build-provenance mismatch is an integrity failure (exit 1), matching `validate_models`.
- `--update-manifest` does no write when all sizes were already pinned. If an explicitly needed metadata update fails after installation, the command exits 2 with `package installed, but the requested manifest update failed`; the verified package and its manifest remain installed.
- Invalid arguments, missing dependencies, contract failures and draft readiness retain their established meanings.

## Measurements and ablation

[Recorded warm benchmark](model-tooling-benchmark.json): macOS 27 arm64, Python 3.14.7, APFS, deterministic temporary files; nine rounds, separate process per sample and rotated order. Peak RSS is approximately 26 MiB across candidates, near interpreter baseline.

| File size | Copy then hash | Fused copy/hash | Clone then hash |
|---|---:|---:|---:|
| 64 MiB | 0.0403 s | 0.0422 s | 0.0368 s |
| 256 MiB | 0.1581 s | 0.1430 s | 0.1283 s |

At 256 MiB, clone/hash is 19% faster in this host microbenchmark. Fused copying remains the portable fallback, with one fewer read pass. At 64 MiB its timing does not improve over baseline. No model-install or device-wide speedup is inferred.

An earlier best-effort eviction run measured 0.170/0.157/0.141 seconds at 256 MiB. Cache eviction was not positively verified, and those results precede removal of an extra fsync; they are retained privately as diagnostic data, not a cold-storage guarantee.

| Candidate | Decision |
|---|---|
| Fused portable copy/hash | Keep: removes a read pass without retaining file-sized buffers |
| APFS clone/hash | Keep: observed benefit; tiny platform-specific path with portable fallback |
| `_HashingReader` archive wrapper | Delete: rehashed already authenticated bytes without saving a read |
| Source fsync inside clone helper | Delete: staged files and directories are already synced before promotion |
| Metadata-repair framework | Omit: route immutable/append-only inputs to ordinary copying |
| New digest/readiness cache | Omit: each acquisition verifies its own bytes |
| `file_digest` as a speed claim | Reject: parity with the loop here; retain it only as the standard-library implementation |

## Verification

The implementation pass ran 142 convert tests with six existing optional-dependency skips, 14 scripts tests, compileall and the `file_hash` import probe. Final integration reruns these through `scripts/verify --mode fast`; skipped cases are not counted as passed.

New regressions cover clone/stream byte equality, immutable and append-only flags on real macOS temporary files, source preservation, replaceable/deletable staging, transform rejection before process launch, provenance classification, explicit-update failure/noop, short/overlong downloads, authenticated archives with bad members, and rejection of bad archives before `tarfile.open` is called. Flag tests restore their temporary source flags in `finally`.

One additional adversarial finding was confirmed: append-only flags break cleanup just like immutable flags. Both are guarded and tested. The temporary source is assumed stable during acquisition; no general concurrent-mutator framework was added.

Reproduce lightweight checks with `python3 scripts/verify --mode fast`. Reproduce the warm microbenchmark with `python3 convert/bench_file_integrity.py --rounds 9 --json`. Private raw evidence is under `$AURALIS_CACHE/quality-2026-09-08/model-tooling/`; large temporary benchmark files were removed.

Real-model integration remains `scripts/verify --scope models`. The separate TTS identity gate remains blocked until its quality criteria are satisfied; this refactor does not make a successful inference imply clone-identity qualification.
