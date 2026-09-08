"""Streaming SHA-256 and file acquisition. Callers verify pins and fsync staging."""
from __future__ import annotations

import hashlib
import os
import stat
import sys
from pathlib import Path

CHUNK = 1024 * 1024

# hashlib.file_digest needs Python >= 3.11; the loop fallback below measured
# bit-for-bit identical and speed-parity on SHA-256 hardware (see the
# model-tooling report), so both branches are interchangeable here.
_file_digest = getattr(hashlib, "file_digest", None)


def sha256_of(path: Path) -> str:
    with path.open("rb") as source:
        if _file_digest is not None:
            return _file_digest(source, "sha256").hexdigest()
        digest = hashlib.sha256()
        for chunk in iter(lambda: source.read(CHUNK), b""):
            digest.update(chunk)
        return digest.hexdigest()


def _try_clonefile(source: Path, target: Path) -> bool:
    """Try a copy-on-write clone, preserving deletable staging files.

    Immutable/append flags propagate and prevent cleanup or replacement.
    Unsupported filesystems, flags or symbols use the streaming fallback.
    """
    if sys.platform != "darwin":
        return False
    try:
        if os.stat(source).st_flags & (stat.UF_IMMUTABLE | stat.SF_IMMUTABLE
                                       | stat.UF_APPEND | stat.SF_APPEND):
            return False
    except OSError:
        return False
    import ctypes
    libc = ctypes.CDLL(None, use_errno=True)
    clone = getattr(libc, "clonefile", None)
    if clone is None:
        return False
    clone.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_int]
    clone.restype = ctypes.c_int
    return clone(os.fsencode(source), os.fsencode(target), 0) == 0


def _stream_copy_with_digest(source: Path, target: Path) -> tuple[int, str]:
    digest = hashlib.sha256()
    total = 0
    buffer = bytearray(CHUNK)
    view = memoryview(buffer)
    with source.open("rb", buffering=0) as reader, target.open("xb") as writer:
        while (count := reader.readinto(buffer)):
            chunk = view[:count]
            digest.update(chunk)
            writer.write(chunk)
            total += count
    return total, digest.hexdigest()


def copy_with_digest(source: Path, target: Path) -> tuple[int, str]:
    """Create target and return its byte count and SHA-256.

    A clone is read once for its digest; the fallback hashes while copying.
    The caller fsyncs staged files and directories before promotion.
    """
    if _try_clonefile(source, target):
        return os.stat(target).st_size, sha256_of(target)
    return _stream_copy_with_digest(source, target)
