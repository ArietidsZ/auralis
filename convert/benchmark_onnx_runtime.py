#!/usr/bin/env python3
"""
Benchmark ONNX Runtime model load and inference latency.

This script is designed for quick, repeatable performance checks after model
export/quantization. It discovers ONNX files, constructs synthetic inputs from
model signatures, runs warmup + timed inference loops, and writes JSON results.
"""

from __future__ import annotations

import argparse
import json
import logging
import statistics
import time
from pathlib import Path
from typing import Any

import numpy as np
import onnxruntime as ort

logging.basicConfig(
    level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s"
)
logger = logging.getLogger(__name__)

DEFAULT_MODELS_DIR = Path(__file__).parent.parent / "models"
DEFAULT_OUTPUT = Path(__file__).parent / "benchmark_results.json"


def infer_shape(name: str, raw_shape: list[Any]) -> list[int]:
    out: list[int] = []
    lower_name = name.lower()

    for idx, dim in enumerate(raw_shape):
        if isinstance(dim, int) and dim > 0:
            out.append(min(dim, 128))
            continue

        if idx == 0:
            out.append(1)
        elif "audio_features" in lower_name and idx == 1:
            out.append(80)
        elif "audio_features" in lower_name:
            out.append(300)
        elif "input_ids" in lower_name:
            out.append(32)
        elif "hidden" in lower_name:
            out.append(64)
        elif "codes" in lower_name:
            out.append(64)
        else:
            out.append(8)

    if not out:
        out = [1]
    return out


def dtype_from_ort(type_str: str) -> np.dtype:
    mapping = {
        "tensor(float)": np.float32,
        "tensor(float16)": np.float16,
        "tensor(double)": np.float64,
        "tensor(int64)": np.int64,
        "tensor(int32)": np.int32,
        "tensor(int16)": np.int16,
        "tensor(int8)": np.int8,
        "tensor(uint8)": np.uint8,
        "tensor(bool)": np.bool_,
    }
    return mapping.get(type_str, np.float32)


def make_input_array(name: str, shape: list[int], dtype: np.dtype) -> np.ndarray:
    rng = np.random.default_rng(42)

    if dtype == np.bool_:
        return rng.integers(0, 2, size=shape, dtype=np.int8).astype(np.bool_)

    if np.issubdtype(dtype, np.integer):
        high = 1024 if "input_ids" in name.lower() else 8
        return rng.integers(0, high, size=shape, dtype=dtype)

    arr = rng.standard_normal(size=shape).astype(dtype)
    return arr


def run_one_model(
    model_path: Path,
    providers: list[str],
    warmup: int,
    runs: int,
) -> dict[str, Any]:
    t0 = time.perf_counter()
    session = ort.InferenceSession(str(model_path), providers=providers)
    load_ms = (time.perf_counter() - t0) * 1000.0

    feed_dict: dict[str, np.ndarray] = {}
    input_shapes: dict[str, list[int]] = {}
    input_dtypes: dict[str, str] = {}

    for inp in session.get_inputs():
        shape = infer_shape(inp.name, list(inp.shape))
        dtype = dtype_from_ort(inp.type)
        feed_dict[inp.name] = make_input_array(inp.name, shape, dtype)
        input_shapes[inp.name] = shape
        input_dtypes[inp.name] = str(dtype)

    for _ in range(warmup):
        session.run(None, feed_dict)

    timings_ms: list[float] = []
    for _ in range(runs):
        start = time.perf_counter()
        session.run(None, feed_dict)
        timings_ms.append((time.perf_counter() - start) * 1000.0)

    session = None

    p50 = statistics.median(timings_ms) if timings_ms else 0.0
    p95 = np.percentile(timings_ms, 95).item() if timings_ms else 0.0
    avg = statistics.fmean(timings_ms) if timings_ms else 0.0

    return {
        "model": str(model_path),
        "size_mb": round(model_path.stat().st_size / 1e6, 3),
        "providers": providers,
        "load_ms": round(load_ms, 3),
        "warmup_runs": warmup,
        "timed_runs": runs,
        "avg_ms": round(avg, 3),
        "p50_ms": round(p50, 3),
        "p95_ms": round(p95, 3),
        "input_shapes": input_shapes,
        "input_dtypes": input_dtypes,
    }


def discover_models(models_dir: Path, pattern: str) -> list[Path]:
    if pattern:
        return sorted(models_dir.rglob(pattern))
    return sorted(models_dir.rglob("*.onnx"))


def print_summary(results: list[dict[str, Any]]) -> None:
    if not results:
        logger.warning("No benchmark results to summarize")
        return

    logger.info(
        "\n%-48s %9s %10s %10s %10s",
        "MODEL",
        "SIZE(MB)",
        "LOAD(ms)",
        "P50(ms)",
        "P95(ms)",
    )
    logger.info("-" * 95)

    for row in results:
        logger.info(
            "%-48s %9.2f %10.2f %10.2f %10.2f",
            Path(row["model"]).name,
            row["size_mb"],
            row["load_ms"],
            row["p50_ms"],
            row["p95_ms"],
        )


def main() -> None:
    parser = argparse.ArgumentParser(description="Benchmark ONNX Runtime latency")
    parser.add_argument("--models-dir", type=Path, default=DEFAULT_MODELS_DIR)
    parser.add_argument(
        "--pattern", default="*.onnx", help="Glob pattern under models-dir"
    )
    parser.add_argument("--providers", nargs="+", default=["CPUExecutionProvider"])
    parser.add_argument("--warmup", type=int, default=3)
    parser.add_argument("--runs", type=int, default=20)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()

    models = discover_models(args.models_dir, args.pattern)
    if not models:
        raise SystemExit(
            f"No ONNX files found in {args.models_dir} with pattern {args.pattern}"
        )

    logger.info("Benchmarking %d models", len(models))
    logger.info("Providers: %s", args.providers)

    results: list[dict[str, Any]] = []
    for model_path in models:
        logger.info("Running %s", model_path.name)
        try:
            result = run_one_model(
                model_path=model_path,
                providers=args.providers,
                warmup=args.warmup,
                runs=args.runs,
            )
            results.append(result)
        except Exception as exc:  # noqa: BLE001
            logger.exception("Failed benchmark for %s", model_path)
            results.append(
                {
                    "model": str(model_path),
                    "error": str(exc),
                    "providers": args.providers,
                }
            )

    args.output.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "models_dir": str(args.models_dir),
        "providers": args.providers,
        "warmup": args.warmup,
        "runs": args.runs,
        "results": results,
    }
    args.output.write_text(
        json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    logger.info("Results written to %s", args.output)

    ok_rows = [r for r in results if "error" not in r]
    print_summary(ok_rows)


if __name__ == "__main__":
    main()
