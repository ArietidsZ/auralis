#!/usr/bin/env python3
"""Shared plumbing for scripts/doctor and scripts/verify (standard library only).

Exit-code contract (specs/2026-09-05-auralis/04-platform-tooling.md):
    0 = all requested checks passed
    1 = code/test failure
    2 = missing required environment/artifacts (blocked)
    3 = contract invalid
    4 = argument error
Multiple failures aggregate with priority 3 > 1 > 2. Argument errors exit 4 immediately.
Skipped is never counted as passed and is always reported with its reason.
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Callable

REPO_ROOT = Path(__file__).resolve().parents[1]
CONTRACT_TOOL = REPO_ROOT / "convert" / "manifest_contract.py"

EXIT_OK, EXIT_FAIL, EXIT_BLOCKED, EXIT_CONTRACT, EXIT_ARGS = 0, 1, 2, 3, 4


class ArgParser(argparse.ArgumentParser):
    """ArgumentParser whose usage errors exit with the spec's code 4 (not argparse's 2)."""

    def error(self, message: str):  # type: ignore[override]
        self.print_usage(sys.stderr)
        print(f"{self.prog}: error: {message}", file=sys.stderr)
        raise SystemExit(EXIT_ARGS)

STATUS_ORDER = {"pass": 0, "skipped": 0, "blocked": 1, "fail": 2, "contract": 3}
EXIT_FOR_STATUS = {"fail": EXIT_FAIL, "blocked": EXIT_BLOCKED, "contract": EXIT_CONTRACT}


@dataclass
class CheckResult:
    id: str
    status: str  # pass | fail | blocked | skipped
    reason: str = ""
    command: str | None = None
    durationMs: int = 0
    details: dict = field(default_factory=dict)


class Reporter:
    def __init__(self) -> None:
        self.results: list[CheckResult] = []

    def add(self, result: CheckResult) -> CheckResult:
        self.results.append(result)
        return result

    def run_cmd(
        self,
        check_id: str,
        cmd: list[str],
        *,
        cwd: Path | None = None,
        timeout: int = 600,
        pass_statuses: tuple[str, ...] = ("pass",),
        blocked_hint: str = "",
        env: dict | None = None,
        contract_errors: bool = False,
    ) -> CheckResult:
        """Run a subprocess command; non-zero exit => fail (2 => blocked if hinted)."""
        command_str = " ".join(cmd)
        start = time.monotonic()
        try:
            proc = subprocess.run(
                cmd, cwd=cwd, timeout=timeout, env=env,
                capture_output=True, text=True,
            )
        except FileNotFoundError as exc:
            return self.add(CheckResult(
                check_id, "blocked", f"executable not found: {exc}; {blocked_hint}".strip(),
                command_str, int((time.monotonic() - start) * 1000)))
        except subprocess.TimeoutExpired:
            return self.add(CheckResult(
                check_id, "fail", f"timed out after {timeout}s", command_str,
                int((time.monotonic() - start) * 1000)))
        duration = int((time.monotonic() - start) * 1000)
        if proc.returncode == 0:
            status = "pass"
            reason = "exit 0"
        elif proc.returncode == 2 and "blocked" in pass_statuses:
            status = "blocked"
            reason = _tail(proc.stdout, proc.stderr)
        else:
            status = "fail"
            reason = f"exit {proc.returncode}: {_tail(proc.stdout, proc.stderr)}"
        # Only our declared tool contract interprets 3/4 specially. A compiler
        # can use those same numeric codes for ordinary build failures.
        details = ({"exitCode": proc.returncode}
                   if contract_errors and proc.returncode in (EXIT_CONTRACT, EXIT_ARGS) else {})
        return self.add(CheckResult(check_id, status, reason, command_str, duration, details))

    def aggregate_exit(self) -> int:
        exit_code = EXIT_OK
        for result in self.results:
            mapped = result.details.get("exitCode", EXIT_FOR_STATUS.get(result.status))
            if mapped is not None and _rank(mapped) > _rank(exit_code):
                exit_code = mapped
        return exit_code

    def summary(self) -> dict:
        counts: dict[str, int] = {"pass": 0, "fail": 0, "blocked": 0, "skipped": 0}
        for result in self.results:
            counts[result.status] = counts.get(result.status, 0) + 1
        return counts

    def print_table(self) -> None:
        counts = self.summary()
        for result in self.results:
            marker = {"pass": "PASS", "fail": "FAIL", "blocked": "BLOCKED", "skipped": "SKIP"}[result.status]
            line = f"[{marker:7}] {result.id}"
            if result.command:
                line += f"  ({result.command})"
            print(line)
            if result.reason and result.reason != "exit 0":
                print(f"          {result.reason}")
        print(
            f"summary: {counts['pass']} pass, {counts['fail']} fail, "
            f"{counts['blocked']} blocked, {counts['skipped']} skipped"
        )

    def write_json(self, output_dir: Path, mode: str) -> Path:
        output_dir.mkdir(parents=True, exist_ok=True)
        path = output_dir / f"verify-{mode}-{int(time.time())}.json"
        payload = {
            "mode": mode,
            "exitCode": self.aggregate_exit(),
            "counts": self.summary(),
            "results": [asdict(result) for result in self.results],
        }
        tmp = path.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        tmp.replace(path)
        return path


def _rank(exit_code: int) -> int:
    return {EXIT_ARGS: 4, EXIT_CONTRACT: 3, EXIT_FAIL: 2, EXIT_BLOCKED: 1, EXIT_OK: 0}[exit_code]


def _tail(stdout: str, stderr: str, limit: int = 1200) -> str:
    text = (stdout or "").strip()
    err = (stderr or "").strip()
    combined = (text + ("\n" + err if err else "")).strip()
    if len(combined) > limit:
        combined = "…" + combined[-limit:]
    return combined or f"exit non-zero"


# ---------------------------------------------------------------------------
# environment probes (used by doctor; verify reuses them for blocked reasons)
# ---------------------------------------------------------------------------

def probe_python3() -> tuple[bool, str, str]:
    path = shutil.which("python3")
    if not path:
        return False, "python3 not found on PATH", "Install Python 3.10+ (e.g. brew install python)"
    proc = subprocess.run([path, "--version"], capture_output=True, text=True)
    version = (proc.stdout or proc.stderr).strip()
    minor = version.split()[-1].split(".") if version else ["0", "0"]
    ok = proc.returncode == 0 and int(minor[0]) >= 3 and int(minor[1]) >= 10
    return ok, version, "" if ok else "Python 3.10+ required"


def probe_jdk() -> tuple[bool, str, str]:
    path = shutil.which("java")
    if not path:
        return False, "java not found on PATH", "Install JDK 17 (e.g. brew install --cask temurin@17)"
    proc = subprocess.run([path, "-version"], capture_output=True, text=True)
    text = (proc.stderr or proc.stdout).splitlines()[0] if (proc.stderr or proc.stdout) else ""
    import re as _re
    match = _re.search(r'version "(\d+)', text)
    if not match:
        return False, text, "Could not parse java version; install JDK 17"
    major = int(match.group(1))
    ok = major >= 17
    return ok, text, "" if ok else f"JDK 17+ required (found {major}); brew install --cask temurin@17"


def probe_android_sdk() -> tuple[bool, str, str]:
    candidates = []
    import os
    env_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if env_home:
        candidates.append(Path(env_home))
    local_props = REPO_ROOT / "android" / "local.properties"
    if local_props.is_file():
        for line in local_props.read_text(encoding="utf-8").splitlines():
            if line.strip().startswith("sdk.dir="):
                candidates.append(Path(line.split("=", 1)[1].strip().replace("\\:", ":")))
    for candidate in candidates:
        platforms = candidate / "platforms"
        build_tools = candidate / "build-tools"
        platform_ok = platforms.is_dir() and any(p.name.startswith("android-35") for p in platforms.iterdir())
        bt_ok = build_tools.is_dir() and any(build_tools.iterdir())
        if candidate.is_dir() and platform_ok and bt_ok:
            return True, str(candidate), ""
    hint = "Set ANDROID_HOME or android/local.properties sdk.dir; install Platform 35 + Build-Tools via Android Studio SDK Manager"
    found = str(candidates[0]) if candidates else "no SDK configured"
    return False, found, hint


def probe_xcode(full_only: bool = True) -> tuple[bool, str, str]:
    path = shutil.which("xcodebuild")
    if not path:
        return False, "xcodebuild not found", "Install full Xcode from the App Store, then: sudo xcode-select -s /Applications/Xcode.app"
    proc = subprocess.run([path, "-showsdks"], capture_output=True, text=True)
    if proc.returncode != 0:
        return False, "xcodebuild -showsdks failed", "Install full Xcode; the current toolchain appears to be CommandLineTools only"
    has_ios_sim = any("iphonesimulator" in line for line in proc.stdout.splitlines())
    if full_only and not has_ios_sim:
        return False, "no iphonesimulator SDK (CommandLineTools only)", "Install full Xcode from the App Store, then: sudo xcode-select -s /Applications/Xcode.app"
    return True, "Xcode with iOS SDK available", ""


def pick_ios_simulator(simctl_json: str) -> str | None:
    """Choose an available iPhone UDID from `xcrun simctl list devices available --json`.

    Output shape: {"devices": {"com.apple.CoreSimulator.SimRuntime.iOS-18-2":
    [{"name": "iPhone 16", "udid": "<UUID>", "state": "Shutdown", "isAvailable": true}, ...]}}.
    Prefers the newest iOS runtime; never guesses from text output.
    Returns a UDID string, or None when no available iPhone exists.
    """
    import re as _re
    try:
        data = json.loads(simctl_json)
    except json.JSONDecodeError:
        return None
    devices = data.get("devices")
    if not isinstance(devices, dict):
        return None
    best: tuple[tuple[int, int], str] | None = None
    for runtime_key, entries in devices.items():
        match = _re.search(r"iOS-(\d+)(?:-(\d+))?", str(runtime_key))
        version = (int(match.group(1)), int(match.group(2) or 0)) if match else (0, 0)
        if not isinstance(entries, list):
            continue
        for entry in entries:
            if not isinstance(entry, dict) or entry.get("isAvailable") is not True:
                continue
            udid = entry.get("udid")
            if not udid or "iPhone" not in str(entry.get("name", "")):
                continue
            if best is None or version > best[0]:
                best = (version, udid)
    return best[1] if best else None


def probe_model_assets() -> tuple[bool, str, str]:
    packs = {
        "asr": REPO_ROOT / "android" / "asset_pack_asr" / "src" / "main" / "assets" / "asr",
        "mt": REPO_ROOT / "android" / "asset_pack_mt" / "src" / "main" / "assets" / "mt",
        "tts": REPO_ROOT / "android" / "asset_pack_tts" / "src" / "main" / "assets" / "tts",
    }
    missing = []
    present = []
    for name, directory in packs.items():
        model_files = [p for p in directory.rglob("*") if p.is_file() and p.suffix in (".onnx", ".gguf", ".data", ".npy")] if directory.is_dir() else []
        (present if model_files else missing).append(name)
    detail = f"packs with model artifacts: {present or 'none'}; empty packs: {missing or 'none'}"
    ok = not missing
    hint = "Run convert/fetch_model.py --package <asr|mt|tts> to download pinned revisions (multi-GB; not done by default)"
    return ok, detail, "" if ok else hint
