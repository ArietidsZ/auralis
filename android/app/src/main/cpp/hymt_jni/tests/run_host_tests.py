#!/usr/bin/env python3
"""Compile actual token adapters against pinned llama headers; no model required."""
import argparse
import shutil
import subprocess
import tempfile
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--include-dir", type=Path, action="append", required=True,
                    help="pinned header directory; repeat for llama/include and ggml/include")
args = parser.parse_args()
compiler = shutil.which("clang++")
if not compiler or not any((directory / "llama.h").is_file() for directory in args.include_dir):
    parser.exit(2, "clang++ and the pinned llama headers are required\n")
source = Path(__file__).resolve().parent
with tempfile.TemporaryDirectory(prefix="auralis-native-contract-") as tmp:
    binary = str(Path(tmp) / "token-contract-test")
    subprocess.run([
        compiler, "-std=c++17", "-Wall", "-Wextra", "-Werror",
        "-fsanitize=address,undefined", "-fno-omit-frame-pointer",
        *("-I" + str(directory) for directory in args.include_dir), "-I" + str(source.parent),
        str(source / "token_contract_test.cpp"), "-o", binary,
    ], check=True)
    subprocess.run([binary], check=True)
