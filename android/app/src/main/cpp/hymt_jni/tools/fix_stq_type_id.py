#!/usr/bin/env python3
"""Remap GGUF tensor type id 42 → 43 (STQ1_0 at llama.cpp 1e411d8).

This is a metadata-only rewrite. It does not requantize.

Evidence the rewrite is valid (checked before any write):

* At pinned revision 1e411d8, ggml.h has GGML_TYPE_Q2_0=42 and
  GGML_TYPE_STQ1_0=43. PR commits 5503c4b / 5165daa / 1e411d8 already
  number STQ as 43; a GGUF that stores 42 on STQ-sized tensors came from
  an earlier enumerator (STQ assigned 42 before Q1_0/Q2_0 were inserted).
* block_stq1_0 at that revision is qs[QK_K/8] + sign[QK_K/32] + f16 d
  = 42 bytes / 256 weights (ggml-common.h static_assert). Q2_0 is 18 bytes
  / 64 weights. Those layouts do not produce the same tensor offsets.
* The script recomputes packed offsets with STQ sizes and with Q2_0 sizes.
  It remaps only when STQ matches every recorded offset and Q2_0 does not.

Refuses to write on: bad magic/version, short/truncated IO, unknown type
ids, already-remapped files (no id 42), mixed 42+43, offset mismatch,
Q2_0 layout also matching (ambiguous), optional source-hash mismatch,
repeat execution on the output.

Source hash is required. The only production pair is
  93e025c9… → e42935e2… (224 type-id bytes). Metadata is parsed from the
header only; the 460MB blob is hashed/copied in 1MiB chunks.

Usage:
  python3 fix_stq_type_id.py <in.gguf> <out.gguf> --expect-src-sha256 HEX \\
      [--expect-dst-sha256 HEX] [--expect-n-stq N] [--write-metadata PATH]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import struct
import sys

ALIGN_DEFAULT = 32
PINNED_REVISION = "1e411d8f5a1e23525fa3265dfb4bd76265465397"
PINNED_SRC_SHA256 = "93e025c93cc082e73a3f142b757623a8b9cf541c020a8013ca4ee669556860ab"
PINNED_DST_SHA256 = "e42935e2c143be4c579109ef3a096b0b00796af2527eaa09be76331832d4c971"
EXPECTED_QUANT_ID = 42
TARGET_QUANT_ID = 43
# Pinned ggml type_size, blck_size. Fail-closed: anything else is refused.
TYPE_SIZES = {
    0: (4, 1),       # F32
    14: (210, 256),  # Q6_K  (ql 128 + qh 64 + scales 16 + d 2)
    42: (42, 256),   # STQ1_0 written with pre-merge id
    43: (42, 256),   # STQ1_0 at 1e411d8
}
Q2_0_SIZE = (18, 64)  # pinned GGML_TYPE_Q2_0 — discriminator only
STQ_SIZE = (42, 256)
FIXED_KV = {0: 1, 1: 1, 2: 2, 3: 2, 4: 4, 5: 4, 6: 4, 7: 1, 10: 8, 11: 8, 12: 8}


class GGUFError(RuntimeError):
    pass


def sha256_file(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _nbytes(dims, type_size: int, block_size: int) -> int:
    if not dims:
        return 0
    if dims[0] % block_size != 0:
        raise GGUFError(
            "tensor ne[0]=%d is not a multiple of block size %d" % (dims[0], block_size)
        )
    rows = 1
    for dim in dims[1:]:
        rows *= dim
    return (dims[0] // block_size) * type_size * rows


class Reader:
    def __init__(self, handle, size: int):
        self.handle = handle
        self.pos = 0
        self.n = size

    def need(self, count: int) -> bytes:
        if count < 0 or self.pos + count > self.n:
            raise GGUFError(
                "truncated GGUF: need %d bytes at %d, file size %d" % (count, self.pos, self.n)
            )
        out = self.handle.read(count)
        if len(out) != count:
            raise GGUFError("short read at %d" % self.pos)
        self.pos += count
        return out

    def u32(self) -> int:
        return struct.unpack("<I", self.need(4))[0]

    def u64(self) -> int:
        return struct.unpack("<Q", self.need(8))[0]

    def gstr(self) -> bytes:
        length = self.u64()
        if length > 64 * 1024 * 1024:
            raise GGUFError("string length %d exceeds 64MiB bound" % length)
        return self.need(length)


def _skip_value(reader: Reader, type_id: int) -> None:
    if type_id == 8:
        reader.gstr()
        return
    if type_id == 9:
        elem = reader.u32()
        count = reader.u64()
        if count > 50_000_000:
            raise GGUFError("array length %d exceeds bound" % count)
        if elem in FIXED_KV:
            reader.need(FIXED_KV[elem] * count)
        elif elem in (8, 9):
            for _ in range(count):
                _skip_value(reader, elem)
        else:
            raise GGUFError("unsupported array elem type %d" % elem)
        return
    if type_id in FIXED_KV:
        reader.need(FIXED_KV[type_id])
        return
    raise GGUFError("unsupported kv type %d" % type_id)


def parse_gguf(path: str) -> dict:
    size = os.path.getsize(path)
    handle = open(path, "rb")
    try:
        return _parse_gguf_open(path, handle, size)
    finally:
        handle.close()


def _parse_gguf_open(path: str, handle, size: int) -> dict:
    reader = Reader(handle, size)
    if reader.need(4) != b"GGUF":
        raise GGUFError("not a GGUF file")
    version = reader.u32()
    if version != 3:
        raise GGUFError("expected GGUF v3, got v%d" % version)
    n_tensors = reader.u64()
    n_kv = reader.u64()
    if n_tensors > 100_000 or n_kv > 100_000:
        raise GGUFError("implausible n_tensors=%d n_kv=%d" % (n_tensors, n_kv))
    alignment = ALIGN_DEFAULT
    for _ in range(n_kv):
        key = reader.gstr()
        type_id = reader.u32()
        start = reader.pos
        _skip_value(reader, type_id)
        if key == b"general.alignment" and type_id == 4:
            handle.seek(start)
            raw = handle.read(4)
            handle.seek(reader.pos)
            alignment = struct.unpack("<I", raw)[0]
            if alignment == 0 or alignment % 8 != 0:
                raise GGUFError("invalid general.alignment %d" % alignment)
    tensors = []
    for _ in range(n_tensors):
        name = reader.gstr()
        ndim = reader.u32()
        if ndim > 8:
            raise GGUFError("ndim %d exceeds 8" % ndim)
        dims = tuple(reader.u64() for _ in range(ndim))
        field_pos = reader.pos
        ttype = reader.u32()
        offset = reader.u64()
        tensors.append(
            {
                "name": name.decode("utf-8", "replace"),
                "dims": dims,
                "ttype": ttype,
                "offset": offset,
                "field_pos": field_pos,
            }
        )
    info_end = reader.pos
    data_start = (info_end + alignment - 1) // alignment * alignment
    if data_start > size:
        raise GGUFError("data start %d exceeds file size %d" % (data_start, size))
    return {
        "path": path,
        "size": size,
        "n_tensors": n_tensors,
        "n_kv": n_kv,
        "alignment": alignment,
        "info_end": info_end,
        "data_start": data_start,
        "tensors": tensors,
    }


def _size_for(ttype: int, q2_for_42: bool) -> tuple[int, int]:
    if q2_for_42 and ttype == EXPECTED_QUANT_ID:
        return Q2_0_SIZE
    if ttype not in TYPE_SIZES:
        raise GGUFError("tensor has unmapped type id %d" % ttype)
    return TYPE_SIZES[ttype]


def reconstruct(tensors, q2_for_42: bool) -> tuple[list, int]:
    cursor = 0
    mismatches = []
    for tensor in tensors:
        type_size, block_size = _size_for(tensor["ttype"], q2_for_42)
        nbytes = _nbytes(tensor["dims"], type_size, block_size)
        if cursor != tensor["offset"]:
            mismatches.append((tensor["name"], tensor["offset"], cursor, tensor["ttype"]))
        cursor = tensor["offset"] + nbytes
    return mismatches, cursor


def _finite_f16(u16: int) -> bool:
    return ((u16 >> 10) & 0x1F) != 0x1F


def stq_scale_ok(parsed: dict, sample_blocks: int = 8) -> None:
    """Seek only STQ block tails; does not load the weight blob."""
    data_start = parsed["data_start"]
    with open(parsed["path"], "rb") as handle:
        for tensor in parsed["tensors"]:
            if tensor["ttype"] not in (EXPECTED_QUANT_ID, TARGET_QUANT_ID):
                continue
            nbytes = _nbytes(tensor["dims"], STQ_SIZE[0], STQ_SIZE[1])
            start = data_start + tensor["offset"]
            if start + nbytes > parsed["size"]:
                raise GGUFError("tensor %s data overruns file" % tensor["name"])
            nblocks = min(sample_blocks, nbytes // 42)
            if nblocks <= 0:
                continue
            finite = 0
            for i in range(nblocks):
                off = start + i * 42 + 40
                handle.seek(off)
                raw = handle.read(2)
                if len(raw) != 2:
                    raise GGUFError("short STQ scale read for %s" % tensor["name"])
                u16 = struct.unpack("<H", raw)[0]
                if _finite_f16(u16):
                    finite += 1
            if finite == 0:
                raise GGUFError(
                    "tensor %s: no finite f16 scale at STQ block tail; layout is not STQ1_0"
                    % tensor["name"]
                )


def validate_for_remap(parsed: dict, expect_n_stq: int | None) -> int:
    types = {}
    for tensor in parsed["tensors"]:
        types[tensor["ttype"]] = types.get(tensor["ttype"], 0) + 1
    n42 = types.get(EXPECTED_QUANT_ID, 0)
    n43 = types.get(TARGET_QUANT_ID, 0)
    if n42 == 0 and n43 > 0:
        raise GGUFError(
            "already remapped or no pre-merge STQ id %d (%d tensors have id %d)"
            % (EXPECTED_QUANT_ID, n43, TARGET_QUANT_ID)
        )
    if n42 == 0:
        raise GGUFError("no tensor with pre-merge STQ1_0 id %d; nothing to remap" % EXPECTED_QUANT_ID)
    if n43 > 0:
        raise GGUFError("mixed type ids 42 and 43; refusing to touch malformed metadata")
    for tensor in parsed["tensors"]:
        if tensor["ttype"] not in TYPE_SIZES:
            raise GGUFError("tensor %s has unmapped type id %d" % (tensor["name"], tensor["ttype"]))
    with open(parsed["path"], "rb") as handle:
        for tensor in parsed["tensors"]:
            handle.seek(tensor["field_pos"])
            recorded = struct.unpack("<I", handle.read(4))[0]
            if recorded != tensor["ttype"]:
                raise GGUFError(
                    "type field at %d is %d, parser saw %d"
                    % (tensor["field_pos"], recorded, tensor["ttype"])
                )
    stq_mismatch, stq_end = reconstruct(parsed["tensors"], q2_for_42=False)
    q2_mismatch, q2_end = reconstruct(parsed["tensors"], q2_for_42=True)
    payload = parsed["size"] - parsed["data_start"]
    if stq_mismatch:
        raise GGUFError(
            "STQ1_0 packed offsets do not match file (first %s file=%d computed=%d); refusing"
            % (stq_mismatch[0][0], stq_mismatch[0][1], stq_mismatch[0][2])
        )
    if stq_end != payload:
        raise GGUFError(
            "STQ packed data size %d != file payload %d; refusing" % (stq_end, payload)
        )
    q2_fits = (not q2_mismatch) and q2_end == payload
    if q2_fits:
        raise GGUFError(
            "type id 42 also matches pinned Q2_0 packed size; ambiguous, refusing to remap"
        )
    stq_scale_ok(parsed)
    if expect_n_stq is not None and n42 != expect_n_stq:
        raise GGUFError("expected %d STQ tensors, found %d" % (expect_n_stq, n42))
    return n42


def remap_copy(src: str, dst: str, parsed: dict) -> int:
    if os.path.abspath(src) == os.path.abspath(dst):
        raise GGUFError("refusing in-place remap; pass distinct output path")
    shutil.copyfile(src, dst)
    n_fixed = 0
    with open(dst, "r+b") as out:
        for tensor in parsed["tensors"]:
            if tensor["ttype"] != EXPECTED_QUANT_ID:
                continue
            out.seek(tensor["field_pos"])
            current = struct.unpack("<I", out.read(4))[0]
            if current != EXPECTED_QUANT_ID:
                raise GGUFError(
                    "output type field at %d is %d, expected %d"
                    % (tensor["field_pos"], current, EXPECTED_QUANT_ID)
                )
            out.seek(tensor["field_pos"])
            out.write(struct.pack("<I", TARGET_QUANT_ID))
            n_fixed += 1
    return n_fixed


def diff_only_type_fields(src: str, dst: str, parsed: dict) -> int:
    n_diff = 0
    with open(src, "rb") as left, open(dst, "rb") as right:
        pos = 0
        while True:
            a = left.read(1024 * 1024)
            b = right.read(1024 * 1024)
            if not a and not b:
                break
            if len(a) != len(b):
                raise GGUFError("output size changed")
            if a != b:
                for i, (ca, cb) in enumerate(zip(a, b)):
                    if ca == cb:
                        continue
                    abs_pos = pos + i
                    owner = None
                    for tensor in parsed["tensors"]:
                        if tensor["field_pos"] <= abs_pos < tensor["field_pos"] + 4:
                            owner = tensor
                            break
                    if owner is None or owner["ttype"] != EXPECTED_QUANT_ID:
                        raise GGUFError("unexpected byte change at offset %d" % abs_pos)
                    n_diff += 1
            pos += len(a)
    return n_diff


def write_metadata(path: str, payload: dict) -> None:
    parent = os.path.dirname(os.path.abspath(path))
    if parent:
        os.makedirs(parent, exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=2, sort_keys=True)
        handle.write("\n")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("src")
    parser.add_argument("dst")
    parser.add_argument("--expect-src-sha256", required=True,
                        help="required source pin; unknown hash fails closed")
    parser.add_argument("--expect-dst-sha256", default=None,
                        help="required when source is the production HF file")
    parser.add_argument("--expect-n-stq", type=int, default=None,
                        help="refuse if the number of id-42 tensors is not this value")
    parser.add_argument("--write-metadata", default=None,
                        help="write transform JSON (default: <dst>.stq-remap.json)")
    args = parser.parse_args(argv)
    try:
        if not os.path.isfile(args.src):
            raise GGUFError("source not found: %s" % args.src)
        src_hash = sha256_file(args.src)
        expect = args.expect_src_sha256.lower()
        if src_hash != expect:
            raise GGUFError("source sha256 %s != expected %s" % (src_hash, expect))
        parsed = parse_gguf(args.src)
        n_stq = validate_for_remap(parsed, args.expect_n_stq)
        print(
            "layout validated: %d tensors, %d STQ1_0 (id %d), Q2_0 layout does not match"
            % (parsed["n_tensors"], n_stq, EXPECTED_QUANT_ID)
        )
        n_fixed = remap_copy(args.src, args.dst, parsed)
        if n_fixed != n_stq:
            raise GGUFError("remapped %d tensors, expected %d" % (n_fixed, n_stq))
        n_diff = diff_only_type_fields(args.src, args.dst, parsed)
        dst_hash = sha256_file(args.dst)
        expect_dst = args.expect_dst_sha256.lower() if args.expect_dst_sha256 else None
        if src_hash == PINNED_SRC_SHA256:
            expect_dst = expect_dst or PINNED_DST_SHA256
        if expect_dst and dst_hash != expect_dst:
            raise GGUFError("output sha256 %s != expected %s" % (dst_hash, expect_dst))
        meta = {
            "tool": "fix_stq_type_id.py",
            "pinnedRuntimeRevision": PINNED_REVISION,
            "source": {
                "path": os.path.abspath(args.src),
                "sha256": src_hash,
                "sizeBytes": parsed["size"],
            },
            "output": {
                "path": os.path.abspath(args.dst),
                "sha256": dst_hash,
                "sizeBytes": os.path.getsize(args.dst),
            },
            "transform": {
                "kind": "gguf-tensor-type-id-remap",
                "fromTypeId": EXPECTED_QUANT_ID,
                "toTypeId": TARGET_QUANT_ID,
                "fromName": "pre-Q1_0/Q2_0 STQ id used by the HF GGUF",
                "toName": "GGML_TYPE_STQ1_0",
                "nTensorsRemapped": n_fixed,
                "bytesChanged": n_diff,
                "dataSectionUnchanged": True,
                "block": "block_stq1_0 = qs[32]+sign[8]+f16 d, 42 bytes / 256 weights",
                "notes": (
                    "Keep the original sha256 as the upstream LFS credential. "
                    "Record this sidecar as a derived artifact; do not replace the "
                    "upstream hash in a verified manifest with the remapped hash "
                    "without a files[] entry for the derived GGUF."
                ),
            },
        }
        meta_path = args.write_metadata or (args.dst + ".stq-remap.json")
        write_metadata(meta_path, meta)
        print("remapped %d tensor type ids %d -> %d (%d bytes changed)"
              % (n_fixed, EXPECTED_QUANT_ID, TARGET_QUANT_ID, n_diff))
        print("wrote %s (%d bytes) sha256=%s" % (args.dst, os.path.getsize(args.dst), dst_hash))
        print("metadata %s" % meta_path)
        print("pinned runtime revision: %s" % PINNED_REVISION)
        return 0
    except GGUFError as exc:
        print("ERROR: %s" % exc, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
