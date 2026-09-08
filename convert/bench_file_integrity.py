#!/usr/bin/env python3
"""Micro-benchmark for large-file acquisition shapes used by fetch_model.

Measures the three candidate shapes for materializing one acquired file into
staging while producing its pinned SHA-256:

  A  shutil.copyfile + sha256_of(target)      (pre-change baseline: 2 reads)
  B  fused read->hash->write loop             (1 read + 1 write, portable)
  C  APFS clonefile + read-back hash          (0-copy then 1 read; darwin)

B and C measure the shipped file_integrity primitives; A reproduces the
pre-change shape. Each measurement runs in a fresh worker subprocess so
ru_maxrss is per candidate (bytes on macOS, normalized below). Warm-cache
medians come from rotated-order repeats; evicted-cache runs stream a large
eviction file through the page cache first, then measure each candidate
against its own dedicated source file so no candidate warms another's input.
Nothing here touches real model files.

Usage:
  python3 convert/bench_file_integrity.py                 # warm medians
  python3 convert/bench_file_integrity.py --cold          # add evicted rounds
  python3 convert/bench_file_integrity.py --worker A src dst   # internal
"""
from __future__ import annotations

import argparse
import json
import os
import resource
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
from pathlib import Path

CANDIDATES = ("A", "B", "C")

CONVERT = Path(__file__).resolve().parent
sys.path.insert(0, str(CONVERT))
import file_integrity  # noqa: E402  (the shipped primitives under test)


def sha256_of(path: Path) -> str:
    return file_integrity.sha256_of(path)


def candidate_a(source: Path, target: Path) -> tuple[int, str]:
    shutil.copyfile(source, target)
    return target.stat().st_size, sha256_of(target)


def candidate_b(source: Path, target: Path) -> tuple[int, str]:
    return file_integrity._stream_copy_with_digest(source, target)


def candidate_c(source: Path, target: Path) -> tuple[int, str]:
    probe = target.with_name(target.name + ".clone-probe")
    probe.unlink(missing_ok=True)
    if not file_integrity._try_clonefile(source, probe):
        raise RuntimeError("clonefile unavailable; C row must measure the clone path, not a fallback")
    probe.unlink()
    return file_integrity.copy_with_digest(source, target)


CANDIDATE_FUNCS = {"A": candidate_a, "B": candidate_b, "C": candidate_c}


def peak_rss_bytes() -> int:
    rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return rss if sys.platform == "darwin" else rss * 1024


def run_worker(candidate: str, source: Path, target: Path) -> dict:
    start = time.perf_counter()
    size, digest = CANDIDATE_FUNCS[candidate](source, target)
    elapsed = time.perf_counter() - start
    return {"candidate": candidate, "elapsedSeconds": elapsed,
            "sizeBytes": size, "sha256": digest,
            "peakRssBytes": peak_rss_bytes()}


def worker_main(argv: list[str]) -> int:
    candidate, source, target = argv
    result = run_worker(candidate, Path(source), Path(target))
    print(json.dumps(result))
    return 0


def make_source(directory: Path, name: str, mebibytes: int) -> Path:
    path = directory / name
    pattern = bytes(range(256)) * 4096  # deterministic 1 MiB block
    with path.open("wb") as writer:
        block = memoryview(pattern)
        for _ in range(mebibytes):
            writer.write(block)
    return path


def spawn(candidate: str, source: Path, target: Path) -> dict:
    result = subprocess.run(
        [sys.executable, __file__, "--worker", candidate, str(source), str(target)],
        capture_output=True, text=True, check=True)
    return json.loads(result.stdout)


def rotate(order: list[str], round_index: int) -> list[str]:
    return order[round_index % len(order):] + order[:round_index % len(order)]


def sample(temp: Path, size: int, candidates: list[str], prefix: str,
           round_index: int, samples: dict[str, list[float]],
           rss: dict[str, list[int]]) -> None:
    expected = sha256_of(temp / f"{prefix}-A.bin")
    for candidate in candidates:
        target = temp / f"{prefix}-dst-{candidate}.bin"
        target.unlink(missing_ok=True)
        result = spawn(candidate, temp / f"{prefix}-{candidate}.bin", target)
        assert result["sha256"] == expected, f"{candidate} digest drift"
        samples[candidate].append(result["elapsedSeconds"])
        rss[candidate].append(result["peakRssBytes"])
        target.unlink()


def summarize(rows: list[dict], size: int, cache: str, rounds: int,
              samples: dict[str, list[float]], rss: dict[str, list[int]]) -> None:
    for candidate in CANDIDATES:
        rows.append({"sizeMiB": size, "candidate": candidate, "cache": cache,
                     "medianSeconds": statistics.median(samples[candidate]),
                     "minSeconds": min(samples[candidate]),
                     "stdevSeconds": statistics.stdev(samples[candidate]) if rounds > 1 else 0.0,
                     "rounds": rounds, "peakRssBytes": max(rss[candidate])})


def warm_suite(temp: Path, sizes_mib: list[int], rounds: int) -> list[dict]:
    rows = []
    for size in sizes_mib:
        for candidate in CANDIDATES:
            make_source(temp, f"warm-{size}-{candidate}.bin", size)
        samples: dict[str, list[float]] = {c: [] for c in CANDIDATES}
        rss: dict[str, list[int]] = {c: [] for c in CANDIDATES}
        for round_index in range(rounds):
            sample(temp, size, rotate(list(CANDIDATES), round_index),
                   f"warm-{size}", round_index, samples, rss)
        summarize(rows, size, "warm", rounds, samples, rss)
        for candidate in CANDIDATES:
            (temp / f"warm-{size}-{candidate}.bin").unlink()
    return rows


def evict_page_cache(evict_path: Path, gib: int) -> None:
    """Best-effort eviction: stream gib of zeros through the page cache.

    Not positively verified (purge(8)/fs_usage need root); treat evicted rows
    as directional. The clone candidate reads a freshly created vnode in both
    cache states, so its warm rows are device-read-bound too.
    """
    if not evict_path.exists() or evict_path.stat().st_size < gib << 30:
        with evict_path.open("wb") as writer:
            block = b"\0" * file_integrity.CHUNK
            for _ in range(gib << 10):
                writer.write(block)
            writer.flush()
            os.fsync(writer.fileno())
    with evict_path.open("rb") as reader:
        while reader.read(file_integrity.CHUNK << 4):
            pass


def cold_suite(temp: Path, sizes_mib: list[int], rounds: int, evict_path: Path, evict_gib: int) -> list[dict]:
    rows = []
    for size in sizes_mib:
        for candidate in CANDIDATES:
            make_source(temp, f"cold-{size}-{candidate}.bin", size)
        samples: dict[str, list[float]] = {c: [] for c in CANDIDATES}
        rss: dict[str, list[int]] = {c: [] for c in CANDIDATES}
        for round_index in range(rounds):
            evict_page_cache(evict_path, evict_gib)
            # Fixed order: dedicated per-candidate sources already prevent
            # cross-candidate warming, and rotation cannot help after eviction.
            sample(temp, size, list(CANDIDATES), f"cold-{size}", round_index, samples, rss)
        summarize(rows, size, "evicted", rounds, samples, rss)
        for candidate in CANDIDATES:
            (temp / f"cold-{size}-{candidate}.bin").unlink()
    return rows


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sizes", default="64,256", help="comma-separated MiB sizes")
    parser.add_argument("--rounds", type=int, default=7, help="warm repetitions per candidate")
    parser.add_argument("--cold-rounds", type=int, default=3)
    parser.add_argument("--cold", action="store_true", help="include evicted-cache rounds")
    parser.add_argument("--evict-gib", type=int, default=36,
                        help="eviction file size; must exceed physical RAM")
    parser.add_argument("--json", action="store_true", help="print raw result rows")
    parser.add_argument("--worker", nargs=3, metavar=("CAND", "SRC", "DST"))
    args = parser.parse_args(argv)
    if args.worker:
        return worker_main(args.worker)
    sizes = [int(part) for part in args.sizes.split(",") if part]
    with tempfile.TemporaryDirectory(prefix="bench-integrity-") as directory:
        temp = Path(directory)
        evict_path = Path(temp) / "evict.bin"
        rows = warm_suite(temp, sizes, args.rounds)
        if args.cold:
            rows += cold_suite(temp, sizes, args.cold_rounds, evict_path, args.evict_gib)
    if args.json:
        print(json.dumps(rows, indent=2))
        return 0
    print("| size (MiB) | cache | candidate | median s | min s | stdev s | n | peak RSS MB |")
    print("|---|---|---|---|---|---|---|---|")
    for row in rows:
        print(f"| {row['sizeMiB']} | {row['cache']} | {row['candidate']} | "
              f"{row['medianSeconds']:.3f} | {row['minSeconds']:.3f} | "
              f"{row['stdevSeconds']:.3f} | {row['rounds']} | "
              f"{row['peakRssBytes'] / 1048576:.1f} |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
