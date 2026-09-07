#!/usr/bin/env python3
"""Safety tests for tools/fix_stq_type_id.py. No 461MB copy required.

Synthetic GGUF v3 files cover unknown hash, already-remapped, truncated,
bad magic, and the 42→43 field-only rewrite. If the real Hy-MT pair is
present, a read-only byte-diff audit runs (no rewrite).
"""
from __future__ import annotations

import hashlib
import importlib.util
import io
import os
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "tools" / "fix_stq_type_id.py"
SPEC = importlib.util.spec_from_file_location("fix_stq_type_id", TOOL)
fix = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fix)

REAL_ORIG = Path.home() / "Library/Caches/Auralis/mt/models/Hy-MT1.5-1.8B-1.25bit.gguf"
REAL_FIX = Path.home() / "Library/Caches/Auralis/mt/models/Hy-MT1.5-1.8B-1.25bit-stq43.gguf"
REAL_ORIG_SHA = "93e025c93cc082e73a3f142b757623a8b9cf541c020a8013ca4ee669556860ab"
REAL_FIX_SHA = "e42935e2c143be4c579109ef3a096b0b00796af2527eaa09be76331832d4c971"


def write_gguf(path: Path, tensors: list[tuple[str, tuple[int, ...], int]], payload: bytes) -> None:
    """Minimal GGUF v3: 0 KV, then tensor infos, 32-byte aligned data."""
    buf = io.BytesIO()
    buf.write(b"GGUF")
    buf.write(struct.pack("<I", 3))
    buf.write(struct.pack("<Q", len(tensors)))
    buf.write(struct.pack("<Q", 0))
    for name, dims, ttype in tensors:
        encoded = name.encode("utf-8")
        buf.write(struct.pack("<Q", len(encoded)))
        buf.write(encoded)
        buf.write(struct.pack("<I", len(dims)))
        for dim in dims:
            buf.write(struct.pack("<Q", dim))
        buf.write(struct.pack("<I", ttype))
        # offset filled after we know packing; write placeholder then patch
        buf.write(struct.pack("<Q", 0))
    info = bytearray(buf.getvalue())
    # rebuild with real offsets
    buf = io.BytesIO()
    buf.write(b"GGUF")
    buf.write(struct.pack("<I", 3))
    buf.write(struct.pack("<Q", len(tensors)))
    buf.write(struct.pack("<Q", 0))
    cursor = 0
    for name, dims, ttype in tensors:
        encoded = name.encode("utf-8")
        buf.write(struct.pack("<Q", len(encoded)))
        buf.write(encoded)
        buf.write(struct.pack("<I", len(dims)))
        for dim in dims:
            buf.write(struct.pack("<Q", dim))
        buf.write(struct.pack("<I", ttype))
        buf.write(struct.pack("<Q", cursor))
        ts, bs = fix.TYPE_SIZES[ttype]
        cursor += fix._nbytes(dims, ts, bs)
    raw = buf.getvalue()
    align = 32
    data_start = (len(raw) + align - 1) // align * align
    out = bytearray(data_start + len(payload))
    out[: len(raw)] = raw
    out[data_start : data_start + len(payload)] = payload
    path.write_bytes(bytes(out))


def src_pin(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run_fix(src: Path, dst: Path, extra: list[str] | None = None) -> int:
    args = [str(src), str(dst), "--expect-src-sha256", src_pin(src)]
    if extra:
        args.extend(extra)
    return fix.main(args)


def stq_block(scale_f16: int = 0x2E66) -> bytes:
    # 32 qs + 8 sign + 2 d
    return bytes(32) + bytes(8) + struct.pack("<H", scale_f16)


class FixStqTests(unittest.TestCase):
    def test_remap_only_type_id_bytes(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "in.gguf"
            dst = Path(tmp) / "out.gguf"
            payload = stq_block() + stq_block(0x2C00)
            write_gguf(
                src,
                [("a.weight", (256,), 42), ("b.weight", (256,), 42)],
                payload,
            )
            rc = run_fix(src, dst, ["--expect-n-stq", "2"])
            self.assertEqual(rc, 0)
            left = src.read_bytes()
            right = dst.read_bytes()
            self.assertEqual(len(left), len(right))
            diffs = [i for i, (a, b) in enumerate(zip(left, right)) if a != b]
            self.assertEqual(len(diffs), 2)  # one low byte per uint32 type field
            parsed = fix.parse_gguf(str(dst))
            self.assertEqual([t["ttype"] for t in parsed["tensors"]], [43, 43])
            self.assertTrue((Path(str(dst) + ".stq-remap.json")).is_file())

    def test_unknown_hash_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "in.gguf"
            dst = Path(tmp) / "out.gguf"
            write_gguf(src, [("a.weight", (256,), 42)], stq_block())
            rc = fix.main(
                [str(src), str(dst), "--expect-src-sha256", "00" * 32]
            )
            self.assertEqual(rc, 1)
            self.assertFalse(dst.exists())

    def test_already_remapped_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "in.gguf"
            dst = Path(tmp) / "out.gguf"
            write_gguf(src, [("a.weight", (256,), 43)], stq_block())
            rc = run_fix(src, dst)
            self.assertEqual(rc, 1)
            self.assertFalse(dst.exists())

    def test_repeat_on_output_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "in.gguf"
            dst = Path(tmp) / "out.gguf"
            dst2 = Path(tmp) / "out2.gguf"
            write_gguf(src, [("a.weight", (256,), 42)], stq_block())
            self.assertEqual(run_fix(src, dst), 0)
            self.assertEqual(run_fix(dst, dst2), 1)
            self.assertFalse(dst2.exists())

    def test_bad_magic_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "in.gguf"
            dst = Path(tmp) / "out.gguf"
            src.write_bytes(b"XXXX" + b"\x00" * 32)
            rc = run_fix(src, dst)
            self.assertEqual(rc, 1)

    def test_truncated_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "in.gguf"
            dst = Path(tmp) / "out.gguf"
            src.write_bytes(b"GGUF" + struct.pack("<I", 3) + b"\x00")
            rc = run_fix(src, dst)
            self.assertEqual(rc, 1)

    def test_same_path_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "in.gguf"
            write_gguf(src, [("a.weight", (256,), 42)], stq_block())
            rc = run_fix(src, src)
            self.assertEqual(rc, 1)

    def test_unknown_type_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "in.gguf"
            dst = Path(tmp) / "out.gguf"
            # type 8 is Q8_0; not in the fail-closed table
            src.write_bytes(
                b"GGUF"
                + struct.pack("<I", 3)
                + struct.pack("<Q", 1)
                + struct.pack("<Q", 0)
                + struct.pack("<Q", 1)
                + b"x"
                + struct.pack("<I", 1)
                + struct.pack("<Q", 32)
                + struct.pack("<I", 8)
                + struct.pack("<Q", 0)
                + b"\x00" * 64
            )
            rc = run_fix(src, dst)
            self.assertEqual(rc, 1)

    @unittest.skipUnless(REAL_ORIG.is_file() and REAL_FIX.is_file(), "real GGUF pair absent")
    def test_real_pair_only_224_low_bytes(self):
        src_sha = subprocess.check_output(["shasum", "-a", "256", str(REAL_ORIG)], text=True).split()[0]
        dst_sha = subprocess.check_output(["shasum", "-a", "256", str(REAL_FIX)], text=True).split()[0]
        self.assertEqual(src_sha, REAL_ORIG_SHA)
        self.assertEqual(dst_sha, REAL_FIX_SHA)
        n_diff = 0
        with REAL_ORIG.open("rb") as left, REAL_FIX.open("rb") as right:
            while True:
                a = left.read(1024 * 1024)
                b = right.read(1024 * 1024)
                if not a and not b:
                    break
                self.assertEqual(len(a), len(b))
                for x, y in zip(a, b):
                    if x != y:
                        n_diff += 1
        self.assertEqual(n_diff, 224)


if __name__ == "__main__":
    unittest.main()
