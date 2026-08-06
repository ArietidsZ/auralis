"""
Download AngelSlim/Hy-MT1.5-1.8B-1.25bit GGUF into the Android MT asset pack.

The Android app expects:

    android/asset_pack_mt/src/main/assets/mt/
      Hy-MT1.5-1.8B-1.25bit.gguf
      manifest.json
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path

from huggingface_hub import hf_hub_download


REPO_ID = "AngelSlim/Hy-MT1.5-1.8B-1.25bit-GGUF"
MODEL_FILE = "Hy-MT1.5-1.8B-1.25bit.gguf"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description="Download Hy-MT GGUF for Android packaging")
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path(__file__).resolve().parents[1]
        / "android"
        / "asset_pack_mt"
        / "src"
        / "main"
        / "assets"
        / "mt",
    )
    args = parser.parse_args()

    args.output_dir.mkdir(parents=True, exist_ok=True)
    cached = Path(
        hf_hub_download(
            repo_id=REPO_ID,
            filename=MODEL_FILE,
        )
    )

    target = args.output_dir / MODEL_FILE
    shutil.copy2(cached, target)

    manifest = {
        "model": "AngelSlim/Hy-MT1.5-1.8B-1.25bit",
        "runtime": "GGUF",
        "source_repo": REPO_ID,
        "files": [
            {
                "path": f"mt/{MODEL_FILE}",
                "size_bytes": target.stat().st_size,
                "sha256": sha256(target),
            }
        ],
    }
    (args.output_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"Wrote {target}")
    print(f"Wrote {args.output_dir / 'manifest.json'}")


if __name__ == "__main__":
    main()
