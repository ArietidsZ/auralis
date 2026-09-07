#!/usr/bin/env python3
"""Install allow-listed, hash-pinned model files through a staged package swap.

Network acquisition requires an immutable source revision. --local-source
imports an already acquired bundle with the same mandatory file hashes; it
does not promote a draft manifest. Each installed package includes its exact
manifest. A package lease excludes readers/writers during recovery and swap.

Exit codes: 0 installed | 1 integrity failure | 2 environment/network/lease |
3 contract | 4 arguments. --update-manifest only backfills measured sizes.
"""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import uuid
from contextlib import contextmanager
from pathlib import Path
from urllib.request import urlopen

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT / "convert"))
from manifest_contract import ArgParser, ContractError, load_manifest_v2, validate_rel_path, STQ_TRANSFORM, verify_build_record

EXIT_OK, EXIT_FAIL, EXIT_ENV, EXIT_CONTRACT, EXIT_ARGS = 0, 1, 2, 3, 4
DEFAULT_DEST = REPO_ROOT / "models"
CHUNK = 1024 * 1024


class IntegrityError(ValueError):
    pass


def apply_source_transform(manifest: dict, staged: Path, allow_derived: bool) -> None:
    transform = manifest["source"].get("transform")
    if transform is None:
        return
    if transform["id"] != STQ_TRANSFORM:
        raise IntegrityError("unsupported source transform")
    expected_output = manifest["files"][0]["sha256"]
    actual = sha256_of(staged)
    if allow_derived and actual == expected_output:
        return  # An already derived local bundle still has to match exactly.
    if actual != transform["inputSha256"]:
        raise IntegrityError("transform input SHA-256 differs from audited upstream artifact")
    script = REPO_ROOT / "android/app/src/main/cpp/hymt_jni/tools/fix_stq_type_id.py"
    with tempfile.TemporaryDirectory(prefix="stq-transform-", dir=staged.parent) as directory:
        output = Path(directory) / "derived.gguf"
        result = subprocess.run([sys.executable, str(script), str(staged), str(output),
            "--expect-src-sha256", transform["inputSha256"], "--expect-dst-sha256", expected_output,
            "--expect-n-stq", "224"], capture_output=True, text=True, timeout=120)
        if result.returncode or not output.is_file() or sha256_of(output) != expected_output:
            raise IntegrityError(f"audited STQ transform failed: {result.stderr[-1500:]}")
        os.replace(output, staged)
        print(f"derived {staged.name}: {actual} -> {expected_output}")


def _download_archive(url: str, dest: Path, expected_size: int) -> None:
    with urlopen(url, timeout=60) as response, dest.open("xb") as target:
        if response.url.split(":", 1)[0] != "https":
            raise IntegrityError("archive redirect left HTTPS")
        total = 0
        while chunk := response.read(CHUNK):
            total += len(chunk)
            if total > expected_size:
                raise IntegrityError("archive exceeds pinned size")
            target.write(chunk)


def extract_archive(manifest: dict, archive_path: Path, staging: Path) -> None:
    """Verify the whole archive, then copy only manifest-listed regular files.

    No extractall, links, absolute paths, traversal, or duplicate destinations.
    The archive's tests/docs never become part of the installed model package.
    """
    archive = manifest["source"]["archive"]
    if archive_path.is_symlink() or not archive_path.is_file():
        raise IntegrityError("archive must be a regular file")
    if archive_path.stat().st_size != archive["sizeBytes"] or sha256_of(archive_path) != archive["sha256"]:
        raise IntegrityError("archive size or SHA-256 does not match its pinned source")
    prefix = archive["stripPrefix"] + "/"
    pkg_prefix = manifest["packageId"] + "/"
    wanted = {entry["path"].removeprefix(pkg_prefix): entry for entry in manifest["files"]}
    found = set()
    with tarfile.open(archive_path, "r|*") as source:
        for member in source:
            name = member.name.rstrip("/") if member.isdir() else member.name
            try:
                validate_rel_path(name)
            except ContractError as exc:
                raise IntegrityError(f"unsafe archive path: {member.name}") from exc
            if not member.isfile() and not member.isdir():
                raise IntegrityError(f"archive links/special entries are not allowed: {name}")
            if not name.startswith(prefix) or member.isdir():
                continue
            rel = name.removeprefix(prefix)
            if rel not in wanted:
                continue
            if rel in found:
                raise IntegrityError(f"duplicate archive member: {name}")
            found.add(rel)
            expected = wanted[rel].get("sizeBytes")
            if member.size <= 0 or (expected is not None and member.size != expected):
                raise IntegrityError(f"archive member size mismatch: {name}")
            target = staging / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            with source.extractfile(member) as reader, target.open("xb") as writer:
                shutil.copyfileobj(reader, writer, CHUNK)
    if found != set(wanted):
        raise IntegrityError(f"archive missing allow-listed files: {sorted(set(wanted) - found)}")


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(CHUNK), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _download_to(repo_id: str, revision: str, repo_file: str, dest: Path) -> None:
    from huggingface_hub import hf_hub_download
    cached = Path(hf_hub_download(repo_id=repo_id, filename=repo_file, revision=revision))
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(cached, dest)


def check_no_symlink_escape(dest_root: Path, package_root: Path) -> None:
    """Check components inside the selected root, including staging and locks.

    System ancestors such as macOS /var may be symlinks. Inside the root no
    symlink is followed, even if its target happens to be inside that root.
    """
    current = dest_root.resolve()
    for part in package_root.relative_to(dest_root).parts:
        current = current / part
        if current.is_symlink():
            raise FileExistsError(f"refusing to install through symlink: {current}")


@contextmanager
def package_lease(root: Path, package_id: str, exclusive: bool = True):
    # The host tools target Linux/macOS, matching the iOS ModelPackageLease
    # lock location. Do not unlink a lock file: old and new inodes could then
    # admit two writers at once.
    import fcntl
    locks = root / ".locks"
    check_no_symlink_escape(root, locks / f"{package_id}.lock")
    locks.mkdir(exist_ok=True)
    fd = os.open(locks / f"{package_id}.lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        fcntl.flock(fd, (fcntl.LOCK_EX if exclusive else fcntl.LOCK_SH) | fcntl.LOCK_NB)
        yield
    finally:
        os.close(fd)


def sync_directory(path: Path) -> None:
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def write_json(path: Path, data: dict) -> None:
    temporary = path.with_name(path.name + ".tmp-" + uuid.uuid4().hex)
    try:
        with temporary.open("w", encoding="utf-8") as target:
            json.dump(data, target, ensure_ascii=False, indent=2)
            target.write("\n")
            target.flush()
            os.fsync(target.fileno())
        os.replace(temporary, path)
        sync_directory(path.parent)
    finally:
        temporary.unlink(missing_ok=True)


def install(manifest: dict, root: Path, local_source: Path | None, local_archive: Path | None = None) -> int:
    if manifest["source"].get("build") and local_source is None and not manifest["source"].get("archive"):
        print("compiled model package requires --local-source or a pinned prebuilt archive; "
              "build it with the repository's audited package tool", file=sys.stderr)
        return EXIT_ENV
    package_id = manifest["packageId"]
    active = root / package_id
    staging = root / ".staging" / f"{package_id}-{uuid.uuid4().hex}"
    backup = root / ".trash" / f"{package_id}-previous"
    for path in (active, staging, backup):
        check_no_symlink_escape(root, path)
    # Stable backup names permit recovery after a process/power interruption
    # between the two renames. Recovery happens under the package lease.
    if backup.exists():
        if not active.exists():
            os.replace(backup, active)
            sync_directory(root)
            print(f"restored previous {package_id} package")
        else:
            shutil.rmtree(backup)
    for stale in staging.parent.glob(f"{package_id}-*"):
        if stale.is_symlink():
            raise FileExistsError(f"staging contains a symlink: {stale}")
        shutil.rmtree(stale)

    required = sum(entry.get("sizeBytes") or 0 for entry in manifest["files"])
    if required > shutil.disk_usage(root).free:
        print(f"insufficient space: need {required} bytes", file=sys.stderr)
        return EXIT_ENV
    try:
        staging.mkdir(parents=True)
        from_archive = local_source is None and manifest["source"].get("archive") is not None
        if from_archive:
            if local_archive is not None:
                extract_archive(manifest, local_archive, staging)
            else:
                with tempfile.TemporaryDirectory(prefix=f"{package_id}-archive-", dir=staging.parent) as directory:
                    archive_path = Path(directory) / "source.tar"
                    source = manifest["source"]["archive"]
                    _download_archive(source["url"], archive_path, source["sizeBytes"])
                    extract_archive(manifest, archive_path, staging)
        for entry in manifest["files"]:
            relative = entry["path"].removeprefix(package_id + "/")
            staged_path = staging / relative
            staged_path.parent.mkdir(parents=True, exist_ok=True)
            try:
                if local_source is not None:
                    source = local_source / relative
                    transform = manifest["source"].get("transform")
                    if transform and not source.exists():
                        source = local_source / transform["inputPath"]
                    check_no_symlink_escape(local_source, source)
                    if not source.is_file():
                        raise FileNotFoundError(source)
                    shutil.copyfile(source, staged_path)
                elif not from_archive:
                    source_name = manifest["source"].get("transform", {}).get("inputPath", relative)
                    _download_to(manifest["source"]["repoId"], manifest["source"]["revision"],
                                 source_name, staged_path)
            except FileExistsError:
                raise
            except Exception as exc:
                print(f"acquisition failed for {relative}: {exc}", file=sys.stderr)
                return EXIT_ENV
            if not staged_path.is_file() or staged_path.is_symlink():
                print(f"acquisition did not produce a regular file: {relative}", file=sys.stderr)
                return EXIT_FAIL
            apply_source_transform(manifest, staged_path, allow_derived=local_source is not None)
            actual_size = staged_path.stat().st_size
            if actual_size <= 0 or (entry.get("sizeBytes") is not None and entry["sizeBytes"] != actual_size):
                print(f"integrity failure: {relative} size {actual_size} != {entry.get('sizeBytes')}", file=sys.stderr)
                return EXIT_FAIL
            actual_hash = sha256_of(staged_path)
            if actual_hash != entry["sha256"]:
                print(f"integrity failure: {relative} sha256 {actual_hash} != {entry['sha256']}", file=sys.stderr)
                return EXIT_FAIL
            entry["sizeBytes"] = actual_size
            with staged_path.open("rb") as source:
                os.fsync(source.fileno())
        verify_build_record(manifest, staging)
        # Manifest and files move together. The shared source manifest is
        # never promoted to verified and need not be writable for an install.
        write_json(staging / "manifest.json", manifest)
        for directory, _, _ in os.walk(staging, topdown=False):
            sync_directory(Path(directory))
        backup.parent.mkdir(parents=True, exist_ok=True)
        had_previous = active.exists()
        if had_previous:
            os.replace(active, backup)
            sync_directory(backup.parent)
            sync_directory(root)
        try:
            os.replace(staging, active)
            sync_directory(root)
        except OSError:
            if had_previous and backup.exists() and not active.exists():
                os.replace(backup, active)
                sync_directory(root)
            raise
        if backup.exists():
            shutil.rmtree(backup)
        print(f"installed {len(manifest['files'])} verified files into {active}; status={manifest['status']}")
        return EXIT_OK
    finally:
        shutil.rmtree(staging, ignore_errors=True)
        for parent in (staging.parent, backup.parent):
            try:
                parent.rmdir()
            except OSError:
                pass


def main(argv: list[str] | None = None) -> int:
    parser = ArgParser(description=__doc__)
    parser.add_argument("--package", required=True, choices=["asr", "mt", "tts"])
    parser.add_argument("--dest", type=Path, default=DEFAULT_DEST)
    parser.add_argument("--local-source", type=Path,
                        help="already acquired package directory; every file still needs a pinned hash")
    parser.add_argument("--archive", type=Path,
                        help="reuse a local release archive; requires source.archive with matching size/hash")
    parser.add_argument("--asset-pack", action="store_true")
    parser.add_argument("--update-manifest", action="store_true",
                        help="atomically backfill measured sizes in the shared manifest")
    args = parser.parse_args(argv)
    manifest_path = REPO_ROOT / "shared" / "model-manifests" / f"{args.package}.json"
    try:
        manifest = load_manifest_v2(manifest_path)
        if manifest["packageId"] != args.package:
            raise ContractError("bad-value", "manifest packageId differs from the requested package")
        if not manifest["files"]:
            raise ContractError("empty-file-set", "manifest declares no files")
        for entry in manifest["files"]:
            if not entry["path"].startswith(args.package + "/") or entry["path"] == args.package + "/manifest.json":
                raise ContractError("path-escape", f"invalid package file path: {entry['path']}")
            if not entry.get("sha256") or entry["sha256"] == "0" * 64:
                print(f"integrity failure: no usable pinned sha256 for {entry['path']}", file=sys.stderr)
                return EXIT_FAIL
        if args.local_source is not None and args.archive is not None:
            print("--local-source and --archive are mutually exclusive", file=sys.stderr)
            return EXIT_ARGS
        if args.archive is not None and not manifest["source"].get("archive"):
            print("--archive requires source.archive in the manifest", file=sys.stderr)
            return EXIT_ARGS
        if args.local_source is None and not (manifest["source"].get("revision") or manifest["source"].get("archive")):
            print("network download requires pinned source.revision or source.archive", file=sys.stderr)
            return EXIT_ARGS
        if args.local_source is not None and not args.local_source.is_dir():
            print(f"local source directory not found: {args.local_source}", file=sys.stderr)
            return EXIT_ENV
        root = (REPO_ROOT / "android" / f"asset_pack_{args.package}" / "src/main/assets"
                if args.asset_pack else args.dest)
        root.mkdir(parents=True, exist_ok=True)
        with package_lease(root, args.package):
            code = install(manifest, root, args.local_source, args.archive)
            if code == EXIT_OK and args.update_manifest:
                write_json(manifest_path, manifest)
            return code
    except ContractError as exc:
        print(f"contract error: {exc}", file=sys.stderr)
        return EXIT_CONTRACT
    except FileExistsError as exc:
        print(f"destination/source check failed: {exc}", file=sys.stderr)
        return EXIT_FAIL
    except (IntegrityError, tarfile.TarError, subprocess.TimeoutExpired) as exc:
        print(f"acquisition integrity failure: {exc}", file=sys.stderr)
        return EXIT_FAIL
    except (OSError, ImportError) as exc:
        print(f"installation unavailable: {exc}", file=sys.stderr)
        return EXIT_ENV


if __name__ == "__main__":
    sys.exit(main())
