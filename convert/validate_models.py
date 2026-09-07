#!/usr/bin/env python3
"""Validate on-disk model bundles against the shared v2 manifests.

Principles (specs 01 C02/C04, 04 P03, 05 V11):
  - Integrity against the manifest is real: every pinned sizeBytes/sha256 is
    checked on the actual files. A mismatch is a failure, not a warning.
  - Missing required gates can NEVER count as success. Exit 0 requires, for
    every selected package: a `verified` manifest, a non-empty files list,
    every file with pinned size AND hash matching on disk, non-empty roles
    that resolve, and a task-level check that actually ran. Missing any of
    these (draft manifest, empty files/roles, unpinned size/hash, missing
    bundle, unimplemented runner) yields exit 2 (blocked/unsupported) or
    exit 1 (concrete failure). There is no path from an empty directory to
    "validation passed".
  - Session/metadata loading is a compatibility check only (`compat-check`);
    it never counts as task-level verification.
  - ASR/MT/TTS run fresh through their real CLI runners with explicit suites.
  - ONNX compatibility probing is opt-in; it never substitutes for inference.
  - Quality limits apply only to the named suite and host, not production/device certification.

Exit codes: 0 all requested checks passed | 1 failure | 2 blocked/unsupported |
3 contract invalid | 4 arguments. JSON report via --json-report PATH.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
import uuid
from contextlib import ExitStack
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT / "convert"))

from manifest_contract import ArgParser, ContractError, load_manifest_v2, verify_build_record  # noqa: E402
from fetch_model import package_lease, check_no_symlink_escape
from model_tasks import load_suite, run_task, runner_layout_error

EXIT_OK, EXIT_FAIL, EXIT_BLOCKED, EXIT_CONTRACT, EXIT_ARGS = 0, 1, 2, 3, 4


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


class Report:
    def __init__(self) -> None:
        self.entries: list[dict] = []

    def add(self, check_id: str, status: str, detail: str, **extra) -> None:
        assert status in ("pass", "fail", "blocked", "unsupported",
                          "unverified-integrity", "compat-check", "not-run", "argument-error", "contract-error")
        entry = {"id": check_id, "status": status, "detail": detail}
        entry.update(extra)
        self.entries.append(entry)
        print(f"[{status}] {check_id}: {detail}")


def validate_package(report: Report, package_id: str, manifest: dict, models_dir: Path,
                     with_compat: bool = False, **task_options) -> bool:
    """Run all checks for one package. Returns True only if the package fully
    passed every required gate (verified + complete integrity + roles + a
    task-level check that actually ran)."""
    fully_passed = True

    report.add(f"{package_id}/manifest", "pass" if manifest["status"] == "verified" else "blocked",
               f"manifest v{manifest['schemaVersion']} status={manifest['status']} "
               f"backend={manifest['runtime']['backend']} revision={manifest['source']['revision']}"
               + ("" if manifest["status"] == "verified"
                  else " (draft manifests can never satisfy verification gates)"))
    if manifest["status"] != "verified":
        fully_passed = False

    # --- files gate --------------------------------------------------------
    files = manifest["files"]
    if not files:
        report.add(f"{package_id}/integrity", "blocked",
                   "manifest declares no files; nothing to verify")
        fully_passed = False
        return False

    bundle_dir = models_dir
    complete_integrity = True
    for entry in files:
        rel = entry["path"]
        # Manifest paths already start with <packageId>/; the on-disk layout is
        # <modelsRoot>/<packageId>/... so the manifest path appends verbatim.
        target = bundle_dir / rel
        if not rel.startswith(package_id + "/"):
            report.add(f"{package_id}/integrity", "fail", f"cross-package file: {rel}")
            complete_integrity = False
            continue
        try:
            check_no_symlink_escape(bundle_dir, target)
        except FileExistsError as exc:
            report.add(f"{package_id}/integrity", "fail", str(exc))
            complete_integrity = False
            continue
        expected_size = entry.get("sizeBytes")
        expected_hash = entry.get("sha256")

        if not target.is_file():
            report.add(f"{package_id}/integrity", "fail", f"missing file: {rel}")
            complete_integrity = False
            continue
        if expected_size is None or expected_hash is None:
            report.add(f"{package_id}/integrity", "unverified-integrity",
                       f"{rel} present but size/hash not pinned in manifest"
                       + (" (size missing)" if expected_size is None else "")
                       + (" (sha256 missing)" if expected_hash is None else ""))
            complete_integrity = False
            continue
        actual_size = target.stat().st_size
        if actual_size != expected_size:
            report.add(f"{package_id}/integrity", "fail",
                       f"{rel}: size {actual_size} != manifest {expected_size}")
            complete_integrity = False
            continue
        actual_hash = sha256_of(target)
        if actual_hash != expected_hash:
            report.add(f"{package_id}/integrity", "fail",
                       f"{rel}: sha256 {actual_hash} != manifest {expected_hash}")
            complete_integrity = False

    if complete_integrity and manifest["source"].get("build"):
        try:
            verify_build_record(manifest, models_dir / package_id)
            report.add(f"{package_id}/build-provenance", "pass", "build source and every output match the manifest")
        except (ContractError, OSError) as exc:
            report.add(f"{package_id}/build-provenance", "fail", str(exc))
            complete_integrity = False
    layout_error = runner_layout_error(package_id, manifest) if complete_integrity else None
    if layout_error:
        report.add(f"{package_id}/integrity", "fail", layout_error)
        complete_integrity = False
    if complete_integrity:
        report.add(f"{package_id}/integrity", "pass",
                   f"all {len(files)} file(s) present with pinned sizes and matching hashes",
                   files=[{"path": entry["path"], "sizeBytes": entry["sizeBytes"],
                           "sha256": entry["sha256"]} for entry in files])
    else:
        fully_passed = False

    # --- roles gate ----------------------------------------------------------
    roles = manifest["roles"]
    roles_ok = bool(roles)
    if not roles:
        report.add(f"{package_id}/roles", "blocked", "manifest declares no roles; nothing can be loaded")
        fully_passed = False
    else:
        missing = []
        for role, target in roles.items():
            if not (models_dir / target).is_file():
                missing.append(f"{role} -> {target}")
        if missing:
            roles_ok = False
            report.add(f"{package_id}/roles", "fail", f"role targets missing: {missing}")
            fully_passed = False
        else:
            report.add(f"{package_id}/roles", "pass", f"{len(roles)} role(s) resolved")

    if not complete_integrity or not roles_ok:
        report.add(f"{package_id}/task", "blocked", "runtime not invoked: integrity/roles gate failed")
        return False
    if with_compat and manifest["runtime"]["backend"] == "onnx":
        onnx_compat_check(report, package_id, manifest, models_dir)
    task_passed = run_task(report, package_id, manifest, models_dir, **task_options)
    return fully_passed and task_passed


def onnx_compat_check(report: Report, package_id: str, manifest: dict, models_dir: Path) -> None:
    """Load every role's ONNX session and dump real input/output metadata.

    This is a compatibility check only. It is NOT a task-level verification and
    never contributes to a 'pass' of model quality.
    """
    try:
        import onnxruntime as ort
    except ImportError:
        report.add(f"{package_id}/compat", "blocked", "onnxruntime not installed")
        return
    providers = ["CPUExecutionProvider"]
    loaded = 0
    for role, target in manifest["roles"].items():
        model_path = models_dir / target
        if model_path.suffix != ".onnx":
            continue
        start = time.monotonic()
        try:
            session = ort.InferenceSession(str(model_path), providers=providers)
            inputs = [{"name": i.name, "shape": i.shape, "type": i.type} for i in session.get_inputs()]
            outputs = [{"name": o.name, "shape": o.shape, "type": o.type} for o in session.get_outputs()]
            loaded += 1
            report.add(f"{package_id}/compat/{role}", "compat-check",
                       f"session loaded in {int((time.monotonic() - start) * 1000)}ms; "
                       f"inputs={inputs} outputs={outputs}")
        except Exception as exc:
            report.add(f"{package_id}/compat/{role}", "fail", f"session load failed: {exc}")
    if loaded == 0:
        report.add(f"{package_id}/compat", "blocked", "no .onnx role files loaded (bundle may be GGUF or absent)")


def main(argv: list[str] | None = None) -> int:
    parser = ArgParser(description="Validate pinned bundles with fresh native task execution")
    parser.add_argument("--models-dir", type=Path, required=True)
    parser.add_argument("--package", choices=["asr", "mt", "tts", "all"], default="all")
    parser.add_argument("--suite", type=Path, help="versioned JSON task cases and predeclared quality/performance limits")
    parser.add_argument("--sample-audio", type=Path, help="single ASR smoke input (quality remains blocked without suite limits)")
    parser.add_argument("--reference", help="reference for --sample-audio")
    parser.add_argument("--compat", action="store_true", help="also load generic ONNX sessions; not a task gate")
    parser.add_argument("--mt-library", type=Path, help="compiled pinned Hy-MT C ABI library")
    parser.add_argument("--runner-python", action="append", help="per-runner Python, e.g. asr=/path/to/venv/bin/python")
    parser.add_argument("--task-timeout", type=float, default=1200)
    parser.add_argument("--manifest", action="append", help="override package manifest: asr=/path/to/manifest.json")
    parser.add_argument("--json-report", type=Path)
    args = parser.parse_args(argv)
    import math
    if not math.isfinite(args.task_timeout) or args.task_timeout <= 0:
        parser.error("--task-timeout must be positive and finite")
    if args.suite and args.sample_audio:
        parser.error("--suite and --sample-audio are mutually exclusive")
    if args.reference is not None and args.sample_audio is None:
        parser.error("--reference requires --sample-audio")
    overrides, pythons = {}, {p: sys.executable for p in ("asr", "mt", "tts")}
    for option, items, dest in (("--manifest", args.manifest, overrides), ("--runner-python", args.runner_python, pythons)):
        seen = set()
        for item in items or []:
            pkg, sep, value = item.partition("=")
            if not sep or not value or pkg not in pythons or pkg in seen:
                parser.error(f"{option} expects one unique <asr|mt|tts>=<path>: {item!r}")
            seen.add(pkg)
            dest[pkg] = value
    try:
        suite = load_suite(args.suite) if args.suite else None
    except (OSError, ValueError, TypeError, ContractError) as exc:
        parser.error(f"invalid --suite: {exc}")
    if args.sample_audio:
        case = {"id": "sample", "audio": str(args.sample_audio.resolve())}
        if args.reference is not None:
            case["reference"] = args.reference
        suite = {"schemaVersion": 1, "name": "single-sample", "purpose": "smoke", "asr": {"cases": [case]}}
    report = Report()
    packages = ["asr", "mt", "tts"] if args.package == "all" else [args.package]
    if suite:
        report.add("suite", "pass", f"{suite['name']} ({suite['purpose']}); results are scoped to this suite and host",
                   sha256=sha256_of(args.suite) if args.suite else None)
    all_fully_passed = True
    if not args.models_dir.is_dir():
        report.add("models", "blocked", f"models directory not found: {args.models_dir}")
        all_fully_passed = False
    else:
        artifacts = ((args.json_report.parent if args.json_report else args.models_dir) /
                     ".model-task-runs" / uuid.uuid4().hex)
        try:
            if suite:
                artifacts.mkdir(parents=True)
            with ExitStack() as leases:
                # Keep read leases across all stages, including TTS round-trip
                # ASR, so installs cannot swap models after their hash checks.
                for pkg in packages:
                    leases.enter_context(package_lease(args.models_dir, pkg, exclusive=False))
                for pkg in packages:
                    path = Path(overrides[pkg]) if pkg in overrides else REPO_ROOT / "shared/model-manifests" / f"{pkg}.json"
                    try:
                        manifest = load_manifest_v2(path)
                        if manifest["packageId"] != pkg:
                            raise ContractError("bad-value", f"requested {pkg}, manifest declares {manifest['packageId']}")
                        fully = validate_package(report, pkg, manifest, args.models_dir, with_compat=args.compat,
                            suite=suite, pythons=pythons, library=args.mt_library, artifacts=artifacts, timeout=args.task_timeout)
                        all_fully_passed = all_fully_passed and fully
                    except ContractError as exc:
                        report.add(f"{pkg}/manifest", "contract-error", str(exc))
                        all_fully_passed = False
                    except OSError as exc:
                        report.add(f"{pkg}/files", "blocked", str(exc))
                        all_fully_passed = False
        except (OSError, FileExistsError) as exc:
            report.add("models/lease", "blocked", f"model package lease unavailable: {exc}")
            all_fully_passed = False
    if args.json_report:
        args.json_report.parent.mkdir(parents=True, exist_ok=True)
        tmp = args.json_report.with_name(args.json_report.name + ".tmp-" + uuid.uuid4().hex)
        tmp.write_text(json.dumps(report.entries, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
        tmp.replace(args.json_report)
    statuses = {e["status"] for e in report.entries}
    code = (EXIT_CONTRACT if "contract-error" in statuses else EXIT_ARGS if "argument-error" in statuses
            else EXIT_FAIL if "fail" in statuses else EXIT_OK if all_fully_passed else EXIT_BLOCKED)
    print(f"validate_models exit={code} ({len(report.entries)} checks)")
    return code


if __name__ == "__main__":
    sys.exit(main())
