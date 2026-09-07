#!/usr/bin/env python3
"""Auralis shared contract: model-manifest v2 validation, v1 migration, catalog checks.

Normative validator for `shared/schema/model-manifest.v2.schema.json` and
`shared/schema/dialect-catalog.v1.schema.json`. Pure standard library so that
schema/CLI checks never pull in torch/onnxruntime (see specs 04 P03).

Exit codes follow specs/2026-09-05-auralis/04-platform-tooling.md:
    0 all requested checks passed
    1 code/test failure (internal errors)
    2 missing environment/artifacts
    3 contract invalid
    4 argument error
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import unicodedata
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit
import argparse as _argparse


class ArgParser(_argparse.ArgumentParser):
    """Usage errors exit 4 per specs 04 P02, not argparse's default 2."""

    def error(self, message: str):
        self.print_usage(sys.stderr)
        print(f"{self.prog}: error: {message}", file=sys.stderr)
        raise SystemExit(4)

SCHEMA_VERSION_V2 = "2"
CATALOG_SCHEMA_VERSION = "1"
PACKAGE_IDS = ("asr", "mt", "tts")
STATUSES = ("draft", "verified")
BACKENDS = ("onnx", "gguf-llama-cpp")
CLASSIFICATIONS = ("model", "supportAsset")
VERIFICATION_STATES = ("unverified", "bench-verified", "device-verified")
PLATFORMS = ("android", "ios")
HEX64_RE = re.compile(r"^[0-9a-f]{64}$")
# Immutable source revisions: a full 40-char git commit SHA. Branch names,
# tags and moving refs (main/latest/HEAD) are mutable and never accepted.
REVISION_SHA_RE = re.compile(r"^[0-9a-f]{40}$")
PLACEHOLDER_HASHES = {"0" * 64}
PLACEHOLDER_VERSION_RE = re.compile(r"(placeholder|todo|tbd)", re.IGNORECASE)
STQ_TRANSFORM = "hymt-stq42-to43-v1"
STQ_INPUT_SHA256 = "93e025c93cc082e73a3f142b757623a8b9cf541c020a8013ca4ee669556860ab"
STQ_OUTPUT_SHA256 = "e42935e2c143be4c579109ef3a096b0b00796af2527eaa09be76331832d4c971"
STQ_RUNTIME_REVISION = "1e411d8f5a1e23525fa3265dfb4bd76265465397"

# Contract error codes (stable strings used by tests and JSON reports).
E_UNKNOWN_SCHEMA = "unknown-schema"
E_FIELD_CONFLICT = "field-conflict"
E_EMPTY_HASH = "empty-hash"
E_EMPTY_FILE_SET = "empty-file-set"
E_DUPLICATE_PATH = "duplicate-path"
E_PATH_ESCAPE = "path-escape"
E_ROLE_MISSING = "role-missing"
E_EXTERNAL_DATA_MISSING = "external-data-missing"
E_PLACEHOLDER = "placeholder-value"
E_MISSING_REQUIRED = "missing-required"
E_BAD_VALUE = "bad-value"
E_BAD_JSON = "bad-json"
E_REVISION_NOT_PINNED = "revision-not-pinned"


class ContractError(Exception):
    """A contract violation with a stable machine-readable code."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message

    def __str__(self) -> str:  # pragma: no cover - cosmetic
        return f"[{self.code}] {self.message}"


# ---------------------------------------------------------------------------
# shared path rules
# ---------------------------------------------------------------------------

def validate_build_provenance(manifest: dict, record: dict) -> None:
    """Bind a compiled package's passive build record to every installed byte.

    File integrity is checked separately before this function. The record is
    not an executable recipe or independent proof of model quality.
    """
    build = manifest["source"].get("build")
    if build is None:
        return
    _require(isinstance(record, dict) and type(record.get("schemaVersion")) is int
             and record["schemaVersion"] == 1 and record.get("recipeId") == build["recipeId"],
             E_BAD_VALUE, "build record schema/recipe differs from manifest")
    upstream = record.get("upstream")
    _require(isinstance(upstream, dict)
             and upstream.get("repoId") == "Qwen/Qwen3-TTS-12Hz-0.6B-Base"
             and upstream.get("revision") == manifest["source"]["revision"],
             E_BAD_VALUE, "build upstream differs from pinned source")
    source = record.get("sourceCode")
    _require(isinstance(source, dict)
             and source.get("revision") == "022e286b98fbec7e1e916cb940cdf532cd9f488e",
             E_BAD_VALUE, "build source code differs from the audited recipe")
    recipe = record.get("recipe")
    _require(isinstance(recipe, dict) and set(recipe) == {"path", "sha256"},
             E_BAD_VALUE, "build record requires its archived recipe path/hash")
    validate_rel_path(recipe["path"])
    _require(recipe["path"].endswith(".py") and isinstance(recipe["sha256"], str)
             and HEX64_RE.fullmatch(recipe["sha256"]) and recipe["sha256"] not in PLACEHOLDER_HASHES,
             E_BAD_VALUE, "invalid recipe identity")
    toolchain = record.get("toolchain")
    _require(isinstance(toolchain, dict) and toolchain.get("onnxruntime") == "1.24.2",
             E_BAD_VALUE, "build record must identify the validated ORT 1.24.2 toolchain")

    def entries(key: str) -> dict:
        rows = record.get(key)
        _require(isinstance(rows, list) and bool(rows), E_BAD_VALUE, f"build {key} must be nonempty")
        result = {}
        for row in rows:
            _require(isinstance(row, dict), E_BAD_VALUE, f"build {key} entry is not an object")
            path = row.get("path")
            validate_rel_path(path)
            _require(path not in result, E_DUPLICATE_PATH, f"duplicate build {key} path: {path}")
            _require(type(row.get("sizeBytes")) is int and row["sizeBytes"] > 0
                     and isinstance(row.get("sha256"), str) and HEX64_RE.fullmatch(row["sha256"])
                     and row["sha256"] not in PLACEHOLDER_HASHES,
                     E_BAD_VALUE, f"build {key} needs pinned size/hash: {path}")
            result[path] = row
        return result

    entries("inputs")
    outputs = entries("outputs")
    package = manifest["packageId"]
    expected = {f["path"].removeprefix(package + "/"): f for f in manifest["files"]
                if f["path"] != build["provenancePath"]}
    _require(set(outputs) == set(expected), E_BAD_VALUE, "build outputs differ from manifest file set")
    external = {p for f in manifest["files"] for p in (f.get("externalData") or [])}
    for path, row in outputs.items():
        target = expected[path]
        _require(row["sha256"] == target["sha256"] and row["sizeBytes"] == target["sizeBytes"],
                 E_BAD_VALUE, f"build output differs from manifest bytes: {path}")
        role = row.get("role")
        if "role" in row:
            graph = manifest["roles"].get(role)
            graph_entry = next((f for f in manifest["files"] if f["path"] == graph), {})
            _require(target["path"] == graph or target["path"] in (graph_entry.get("externalData") or []),
                     E_ROLE_MISSING, f"build output role differs from manifest: {path}")
        _require(type(row.get("externalData", False)) is bool
                 and row.get("externalData", False) == (target["path"] in external),
                 E_EXTERNAL_DATA_MISSING, f"build external data differs from manifest: {path}")


def verify_build_record(manifest: dict, package_dir: Path) -> None:
    build = manifest["source"].get("build")
    if build is None:
        return
    path = package_dir / build["provenancePath"].removeprefix(manifest["packageId"] + "/")
    _require(path.stat().st_size <= 1024 * 1024, E_BAD_VALUE, "build record exceeds 1 MiB")
    try:
        record = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_json_keys,
                            parse_constant=reject_json_constant)
    except (ValueError, UnicodeError) as exc:
        raise ContractError(E_BAD_JSON, f"invalid build record: {exc}") from exc
    validate_build_provenance(manifest, record)

def validate_rel_path(path: str) -> None:
    """Reject absolute paths, '..'/''.' segments, empty segments, backslashes,
    drive letters, NUL bytes, leading/trailing whitespace and non-NFC unicode
    (prevents visually-identical canonical duplicates)."""
    if not isinstance(path, str) or not path:
        raise ContractError(E_PATH_ESCAPE, f"path must be a non-empty string, got {path!r}")
    if "\x00" in path:
        raise ContractError(E_PATH_ESCAPE, "path contains a NUL byte")
    if path != path.strip():
        raise ContractError(E_PATH_ESCAPE, f"path has leading/trailing whitespace: {path!r}")
    if "\\" in path:
        raise ContractError(E_PATH_ESCAPE, f"path contains backslash (use '/'): {path!r}")
    if path.startswith("/") or (len(path) > 1 and path[1] == ":"):
        raise ContractError(E_PATH_ESCAPE, f"path is absolute: {path!r}")
    if unicodedata.normalize("NFC", path) != path:
        raise ContractError(E_PATH_ESCAPE, f"path is not NFC-normalized (canonical duplicate risk): {path!r}")
    segments = path.split("/")
    for seg in segments:
        if seg == "":
            raise ContractError(E_PATH_ESCAPE, f"path has an empty segment: {path!r}")
        if seg in (".", ".."):
            raise ContractError(E_PATH_ESCAPE, f"path contains '{seg}' segment: {path!r}")


def validate_source_revision(revision: Any, status: str) -> None:
    """Immutable-source rule: `source.revision` must be a full 40-char lowercase
    git commit SHA. Branch names (main/master/...), tags, 'latest' and short
    SHAs are mutable refs and rejected; null is allowed only for draft."""
    if revision is None:
        if status == "verified":
            raise ContractError(E_REVISION_NOT_PINNED, "verified manifest requires source.revision")
        return
    _require(isinstance(revision, str), E_REVISION_NOT_PINNED, f"source.revision must be a string or null, got {revision!r}")
    if not REVISION_SHA_RE.match(revision):
        raise ContractError(
            E_REVISION_NOT_PINNED,
            f"source.revision {revision!r} is not an immutable full 40-char commit SHA; "
            "branch names, tags and 'main'/'latest' are moving refs and must not be used",
        )


def validate_archive_source(archive: Any) -> None:
    """Release archives are content-pinned; a release tag is not a git pin."""
    _require(isinstance(archive, dict), E_BAD_VALUE, "source.archive must be an object")
    _require(set(archive) == {"url", "sha256", "sizeBytes", "stripPrefix"},
             E_BAD_VALUE, "archive requires only url/sha256/sizeBytes/stripPrefix")
    url = archive.get("url")
    _require(isinstance(url, str), E_BAD_VALUE, "archive.url must be an HTTPS URL")
    try:
        parsed = urlsplit(url)
        valid = (parsed.scheme == "https" and parsed.hostname and parsed.username is None
                 and parsed.password is None and "#" not in url and "?" not in url
                 and not any(c.isspace() for c in url))
    except ValueError:
        valid = False
    _require(valid, E_BAD_VALUE, "archive.url must be an HTTPS URL without credentials/query/fragment")
    sha = archive.get("sha256")
    _require(isinstance(sha, str) and bool(HEX64_RE.fullmatch(sha)) and sha not in PLACEHOLDER_HASHES,
             E_BAD_VALUE, "archive.sha256 must be a non-placeholder SHA-256")
    size = archive.get("sizeBytes")
    _require(type(size) is int and size > 0, E_BAD_VALUE, "archive.sizeBytes must be positive")
    prefix = archive.get("stripPrefix")
    _require(isinstance(prefix, str), E_BAD_VALUE, "archive.stripPrefix must be a relative path")
    validate_rel_path(prefix)


# ---------------------------------------------------------------------------
# model manifest v2
# ---------------------------------------------------------------------------

def _require(cond: Any, code: str, message: str) -> None:
    if not cond:
        raise ContractError(code, message)


def _file_entry_fields(entry: Any, status: str, seen_paths: set[str], index: int) -> dict[str, Any]:
    _require(isinstance(entry, dict), E_BAD_VALUE, f"files[{index}] must be an object")
    allowed_keys = {"path", "sizeBytes", "sha256", "classification", "externalData"}
    unknown = set(entry.keys()) - allowed_keys
    _require(not unknown, E_BAD_VALUE, f"files[{index}] has unknown keys (v2 uses one field system): {sorted(unknown)}")
    path = entry.get("path")
    _require(isinstance(path, str), E_MISSING_REQUIRED, f"files[{index}].path missing")
    validate_rel_path(path)
    _require(path not in seen_paths, E_DUPLICATE_PATH, f"duplicate file path: {path!r}")
    seen_paths.add(path)

    size = entry.get("sizeBytes")
    _require(
        size is None or (isinstance(size, int) and not isinstance(size, bool) and size > 0),
        E_BAD_VALUE,
        f"{path}: sizeBytes must be a positive integer or null, got {size!r}",
    )
    if status == "verified":
        _require(size is not None, E_MISSING_REQUIRED, f"{path}: verified manifest requires sizeBytes")

    sha = entry.get("sha256")
    if sha is not None:
        _require(isinstance(sha, str), E_BAD_VALUE, f"{path}: sha256 must be a string or null")
        _require(sha != "", E_EMPTY_HASH, f"{path}: sha256 must not be an empty string (use null)")
        _require(
            bool(HEX64_RE.match(sha)),
            E_BAD_VALUE,
            f"{path}: sha256 must be 64 lowercase hex chars",
        )
    if status == "verified":
        _require(sha is not None, E_MISSING_REQUIRED, f"{path}: verified manifest requires sha256")
        _require(sha not in PLACEHOLDER_HASHES, E_PLACEHOLDER, f"{path}: placeholder (all-zero) sha256")

    classification = entry.get("classification")
    _require(
        classification in CLASSIFICATIONS,
        E_BAD_VALUE,
        f"{path}: classification must be one of {CLASSIFICATIONS}, got {classification!r}",
    )

    ext = entry.get("externalData")
    _require(ext is None or isinstance(ext, list), E_BAD_VALUE, f"{path}: externalData must be an array or null")
    return {"path": path, "entry": entry}


def validate_manifest_v2(data: Any) -> dict[str, Any]:
    """Validate an in-memory v2 manifest object. Raises ContractError. Returns the object."""
    _require(isinstance(data, dict), E_BAD_JSON, "manifest must be a JSON object")

    version = data.get("schemaVersion")
    if version != SCHEMA_VERSION_V2:
        raise ContractError(E_UNKNOWN_SCHEMA, f"unsupported schemaVersion {version!r}; expected '2' (v1 must go through migrate_manifest_v1_to_v2)")

    package_id = data.get("packageId")
    _require(package_id in PACKAGE_IDS, E_BAD_VALUE, f"packageId must be one of {PACKAGE_IDS}, got {package_id!r}")

    status = data.get("status")
    _require(status in STATUSES, E_MISSING_REQUIRED, f"status must be one of {STATUSES}, got {status!r}")

    pkg_version = data.get("version")
    _require(isinstance(pkg_version, str) and pkg_version, E_MISSING_REQUIRED, "version must be a non-empty string")
    if status == "verified":
        _require(
            pkg_version != "0.0.0" and not PLACEHOLDER_VERSION_RE.search(pkg_version),
            E_PLACEHOLDER,
            f"verified manifest has placeholder version {pkg_version!r}",
        )

    # source ------------------------------------------------------------
    source = data.get("source")
    _require(isinstance(source, dict), E_MISSING_REQUIRED, "source must be an object")
    _require(set(source) <= {"repoId", "revision", "upstreamModelId", "licenseSource",
                             "archive", "transform", "build"},
             E_BAD_VALUE, "unknown source fields")
    for key in ("repoId", "upstreamModelId", "licenseSource"):
        _require(
            isinstance(source.get(key), str) and source[key],
            E_MISSING_REQUIRED,
            f"source.{key} must be a non-empty string",
        )
    revision = source.get("revision")
    archive = source.get("archive")
    if "archive" in source:
        validate_archive_source(archive)
    validate_source_revision(revision, "draft" if archive is not None else status)

    # runtime ------------------------------------------------------------
    runtime = data.get("runtime")
    _require(isinstance(runtime, dict), E_MISSING_REQUIRED, "runtime must be an object")
    _require(runtime.get("backend") in BACKENDS, E_BAD_VALUE, f"runtime.backend must be one of {BACKENDS}")
    _require(
        isinstance(runtime.get("apiContractVersion"), str) and runtime["apiContractVersion"],
        E_MISSING_REQUIRED,
        "runtime.apiContractVersion must be a non-empty string",
    )
    platforms = runtime.get("targetPlatforms")
    _require(isinstance(platforms, list) and platforms, E_EMPTY_FILE_SET, "runtime.targetPlatforms must be a non-empty array")
    for plat in platforms:
        _require(isinstance(plat, dict) and plat.get("platform") in PLATFORMS, E_BAD_VALUE, f"targetPlatform entry invalid: {plat!r}")
    _require(isinstance(runtime.get("streaming"), bool), E_BAD_VALUE, "runtime.streaming must be a boolean")
    runtime_revision = runtime.get("runtimeRevision")
    if status == "verified":
        _require(
            isinstance(runtime_revision, str) and runtime_revision,
            E_MISSING_REQUIRED,
            "verified manifest requires runtime.runtimeRevision",
        )

    # capabilities ---------------------------------------------------------
    caps = data.get("capabilities")
    _require(isinstance(caps, dict), E_MISSING_REQUIRED, "capabilities must be an object")
    modes = caps.get("modes")
    _require(isinstance(modes, list), E_BAD_VALUE, "capabilities.modes must be an array")
    for mode in modes:
        _require(mode in ("transcribe", "translate", "synthesize", "clone"), E_BAD_VALUE, f"unknown mode {mode!r}")
    if status == "verified":
        _require(bool(modes), E_EMPTY_FILE_SET, "verified manifest requires non-empty capabilities.modes")
        _require(
            isinstance(caps.get("languages"), list) and caps["languages"],
            E_EMPTY_FILE_SET,
            "verified manifest requires non-empty capabilities.languages",
        )
        _require(bool(caps.get("verification")), E_MISSING_REQUIRED, "verified manifest requires capabilities.verification states")
    verification = caps.get("verification")
    _require(verification is None or isinstance(verification, dict), E_BAD_VALUE, "capabilities.verification must be an object")
    if isinstance(verification, dict):
        for state in verification.values():
            _require(state in VERIFICATION_STATES, E_BAD_VALUE, f"verification state must be one of {VERIFICATION_STATES}, got {state!r}")

    # files ----------------------------------------------------------------
    files = data.get("files")
    _require(isinstance(files, list), E_BAD_VALUE, "files must be an array")
    if status == "verified":
        _require(bool(files), E_EMPTY_FILE_SET, "verified manifest requires a non-empty files list")
    seen: set[str] = set()
    parsed_files = [_file_entry_fields(entry, status, seen, i) for i, entry in enumerate(files)]
    all_paths = seen

    for item in parsed_files:
        for ext_path in item["entry"].get("externalData") or []:
            validate_rel_path(ext_path)
            _require(
                ext_path in all_paths,
                E_EXTERNAL_DATA_MISSING,
                f"{item['path']}: externalData {ext_path!r} is not present in files",
            )

    # roles ------------------------------------------------------------------
    roles = data.get("roles")
    _require(isinstance(roles, dict), E_MISSING_REQUIRED, "roles must be an object")
    for role, target in roles.items():
        _require(isinstance(role, str) and role, E_BAD_VALUE, "role names must be non-empty strings")
        validate_rel_path(target)
        _require(
            target in all_paths,
            E_ROLE_MISSING,
            f"role {role!r} references {target!r} which is not listed in files",
        )
        entry = next(item["entry"] for item in parsed_files if item["path"] == target)
        _require(
            entry.get("classification") == "model",
            E_BAD_VALUE,
            f"role {role!r} must reference a classification=model file, {target!r} is {entry.get('classification')!r}",
        )
    if status == "verified":
        _require(bool(roles), E_EMPTY_FILE_SET, "verified manifest requires at least one role")

    if "build" in source:
        build = source["build"]
        _require(isinstance(build, dict) and set(build) == {"recipeId", "provenancePath"},
                 E_BAD_VALUE, "source.build requires only recipeId/provenancePath")
        _require(build["recipeId"] == "auralis.qwen3-tts.api2.fp32.v1"
                 and package_id == "tts" and runtime["backend"] == "onnx"
                 and runtime["apiContractVersion"] == "2" and "transform" not in source,
                 E_BAD_VALUE, "unsupported build recipe or model API")
        validate_source_revision(revision, "verified")
        validate_rel_path(build["provenancePath"])
        _require(build["provenancePath"].startswith(package_id + "/"),
                 E_BAD_VALUE, "build provenance must belong to its package")
        record = next((item["entry"] for item in parsed_files
                       if item["path"] == build["provenancePath"]), None)
        _require(record is not None and record.get("classification") == "supportAsset"
                 and build["provenancePath"].endswith(".json"),
                 E_BAD_VALUE, "build provenance must be a declared JSON supportAsset")

    if "transform" in source:
        transform = source["transform"]
        _require(isinstance(transform, dict) and set(transform) == {"id", "inputPath", "inputSha256"},
                 E_BAD_VALUE, "source.transform requires id/inputPath/inputSha256")
        _require(package_id == "mt" and archive is None and runtime["backend"] == "gguf-llama-cpp",
                 E_BAD_VALUE, "the fixed STQ transform is only for an MT GGUF source")
        _require(transform["id"] == STQ_TRANSFORM and transform["inputSha256"] == STQ_INPUT_SHA256,
                 E_BAD_VALUE, "unknown transform or unaudited transform input")
        _require(isinstance(transform["inputPath"], str), E_BAD_VALUE, "transform.inputPath must be a string")
        validate_rel_path(transform["inputPath"])
        _require(len(files) == 1 and files[0]["sha256"] == STQ_OUTPUT_SHA256
                 and files[0]["sizeBytes"] == 461860704 and roles.get("translator") == files[0]["path"]
                 and runtime.get("runtimeRevision") == STQ_RUNTIME_REVISION,
                 E_BAD_VALUE, "STQ transform requires its audited output size/hash/role/runtime")

    _require(set(data.keys()) <= {
        "schemaVersion", "packageId", "version", "status", "source", "runtime",
        "capabilities", "files", "roles",
    }, E_BAD_VALUE, f"unknown top-level keys: {sorted(set(data.keys()) - {'schemaVersion', 'packageId', 'version', 'status', 'source', 'runtime', 'capabilities', 'files', 'roles'})}")
    return data


def load_manifest_v2(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_json_keys,
                          parse_constant=reject_json_constant)
    except (json.JSONDecodeError, UnicodeError) as exc:
        raise ContractError(E_BAD_JSON, f"{path}: invalid JSON: {exc}") from exc
    return validate_manifest_v2(data)


def unique_json_keys(pairs):
    result = {}
    seen = set()
    for key, value in pairs:
        normalized = unicodedata.normalize("NFC", key)
        if normalized in seen:
            raise ContractError(E_BAD_JSON, f"duplicate JSON key: {key!r}")
        seen.add(normalized)
        result[key] = value
    return result


def reject_json_constant(value):
    raise ContractError(E_BAD_JSON, f"non-JSON numeric constant: {value}")


# ---------------------------------------------------------------------------
# v1 -> v2 migration
# ---------------------------------------------------------------------------

class MigrationError(Exception):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message

    def __str__(self) -> str:  # pragma: no cover
        return f"[{self.code}] {self.message}"


def _pick_size(entry: dict[str, Any], where: str) -> int | None:
    """Unified sizeBytes for a v1 file entry. sizeBytes wins; a conflicting size_bytes is an error."""
    camel = entry.get("sizeBytes")
    snake = entry.get("size_bytes")
    if camel is not None and snake is not None and camel != snake:
        raise MigrationError(
            E_FIELD_CONFLICT,
            f"{where}: sizeBytes={camel!r} conflicts with size_bytes={snake!r}",
        )
    size = camel if camel is not None else snake
    if size is None:
        return None
    if isinstance(size, bool) or not isinstance(size, int) or size <= 0:
        raise MigrationError(E_BAD_VALUE, f"{where}: size must be a positive integer or absent, got {size!r}")
    return size


def migrate_manifest_v1_to_v2(data: Any) -> dict[str, Any]:
    """Migrate a v1 manifest object to the unified v2 field system.

    Rules (specs 01 C02): one internal field system; v1 `size_bytes` accepted;
    conflicting size fields fail; v1 information is never invented (missing
    sizes/hashes become null); migrated manifests are always `draft` because
    v1 carries no verification evidence.
    """
    if not isinstance(data, dict):
        raise MigrationError(E_BAD_JSON, "v1 manifest must be a JSON object")
    if data.get("schemaVersion") != "1":
        raise MigrationError(
            E_UNKNOWN_SCHEMA,
            f"migrate_manifest_v1_to_v2 expects schemaVersion '1', got {data.get('schemaVersion')!r}",
        )

    package_id = data.get("packageId")
    if package_id not in PACKAGE_IDS:
        raise MigrationError(E_BAD_VALUE, f"v1 packageId must be one of {PACKAGE_IDS}, got {package_id!r}")

    if "checksumAlgorithm" in data and data["checksumAlgorithm"] != "sha256":
        raise MigrationError(E_BAD_VALUE, f"unsupported checksumAlgorithm {data['checksumAlgorithm']!r}")

    # files ----------------------------------------------------------------
    v1_files = data.get("files")
    if not isinstance(v1_files, list):
        raise MigrationError(E_BAD_VALUE, "v1 files must be an array")
    asset_classes: dict[str, str] = data.get("assetClasses") or {}
    role_targets: set[str] = set((data.get("roles") or {}).values())

    paths: list[str] = []
    for raw in v1_files:
        if not isinstance(raw, dict) or not isinstance(raw.get("path"), str):
            raise MigrationError(E_BAD_VALUE, f"v1 file entry needs a string path: {raw!r}")
        paths.append(raw["path"])

    def classify(path: str) -> str:
        declared = asset_classes.get(path)
        if declared is not None:
            return "supportAsset" if "SupportAsset" in declared else "model"
        if path in role_targets:
            return "model"
        if path.endswith((".onnx", ".gguf")):
            return "model"
        return "supportAsset"

    files_v2: list[dict[str, Any]] = []
    for raw in v1_files:
        path = raw["path"]
        validate_rel_path(path)  # v1 must already be sane; escape stays an error
        size = _pick_size(raw, path)
        sha = raw.get("sha256")
        if sha is not None:
            if not isinstance(sha, str):
                raise MigrationError(E_BAD_VALUE, f"{path}: v1 sha256 must be a string or null")
            if sha == "":
                sha = None  # empty hash = unknown; never fabricate
            elif not HEX64_RE.match(sha):
                raise MigrationError(E_BAD_VALUE, f"{path}: v1 sha256 not 64 hex chars")
        if sha is not None and sha in PLACEHOLDER_HASHES:
            sha = None  # all-zero placeholder carries no information
        entry: dict[str, Any] = {"path": path, "sizeBytes": size, "sha256": sha, "classification": classify(path)}
        if path.endswith(".onnx") and path + ".data" in paths:
            entry["externalData"] = [path + ".data"]
        files_v2.append(entry)

    # roles ------------------------------------------------------------------
    roles_v1 = data.get("roles") or {}
    roles_v2: dict[str, str] = {}
    for role, target in roles_v1.items():
        validate_rel_path(target)
        if target not in paths:
            raise MigrationError(E_ROLE_MISSING, f"role {role!r} references {target!r} which is not in v1 files")
        roles_v2[role] = target
    if not roles_v2:
        # Give the primary artifact a role when obvious from the package kind;
        # this is structural bookkeeping, not new model information.
        models = [f["path"] for f in files_v2 if f["classification"] == "model"]
        if package_id == "mt" and len(models) == 1:
            roles_v2["translator"] = models[0]
        elif package_id == "asr" and len(models) == 1:
            roles_v2["asr"] = models[0]

    # source -------------------------------------------------------------------
    bundle = data.get("sourceBundle") or {}
    repo_id = bundle.get("repoId") or data.get("sourcePackageName")
    upstream = bundle.get("baseModel") or data.get("sourcePackageName")
    if not isinstance(repo_id, str) or not repo_id:
        raise MigrationError(E_MISSING_REQUIRED, "v1 manifest has no usable source repo id")
    v2: dict[str, Any] = {
        "schemaVersion": SCHEMA_VERSION_V2,
        "packageId": package_id,
        "version": data.get("version") or "0.0.0",
        "status": "draft",  # v1 carries no verification evidence
        "source": {
            "repoId": repo_id,
            "revision": None,
            "upstreamModelId": upstream if isinstance(upstream, str) and upstream else repo_id,
            "licenseSource": bundle.get("kind") or "upstream-repository",
        },
        "runtime": {
            "backend": "gguf-llama-cpp" if package_id == "mt" else "onnx",
            "apiContractVersion": "1",
            "quantization": None,
            "runtimeRevision": None,
            "executionProviders": data.get("supportedExecutionTargets") or ["cpu"],
            "targetPlatforms": [],
            "streaming": False,
        },
        "capabilities": {
            "modes": {"asr": ["transcribe"], "mt": ["translate"], "tts": ["synthesize"]}[package_id],
            "languages": [],
            "verification": {},
        },
        "files": files_v2,
        "roles": roles_v2,
    }

    compat = data.get("compatibility") or {}
    targets: list[dict[str, Any]] = []
    if "minAndroidSdk" in compat:
        targets.append({"platform": "android", "minSdk": compat["minAndroidSdk"], "abis": ["arm64-v8a"]})
    if "minIosVersion" in compat:
        targets.append({"platform": "ios", "minOsVersion": compat["minIosVersion"], "abis": ["arm64"]})
    if not targets:
        targets = [{"platform": "android", "minSdk": 29, "abis": ["arm64-v8a"]}, {"platform": "ios", "minOsVersion": "17.0", "abis": ["arm64"]}]
    v2["runtime"]["targetPlatforms"] = targets

    # quantizationByRole: keep as declared quantization when unanimous
    quant = data.get("quantizationByRole") or {}
    quant_values = {entry.get("quantization") for entry in quant.values() if isinstance(entry, dict)}
    if len(quant_values) == 1:
        v2["runtime"]["quantization"] = quant_values.pop()
    validate_manifest_v2(v2)
    return v2


def migrate_manifest_file(input_path: Path, output_path: Path) -> dict[str, Any]:
    try:
        data = json.loads(input_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise MigrationError(E_BAD_JSON, f"{input_path}: invalid JSON: {exc}") from exc
    v2 = migrate_manifest_v1_to_v2(data)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    tmp = output_path.with_suffix(output_path.suffix + ".tmp")
    tmp.write_text(json.dumps(v2, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(output_path)
    return v2


# ---------------------------------------------------------------------------
# dialect catalog
# ---------------------------------------------------------------------------

def validate_catalog(data: Any) -> dict[str, Any]:
    _require(isinstance(data, dict), E_BAD_JSON, "catalog must be a JSON object")
    if data.get("schemaVersion") != CATALOG_SCHEMA_VERSION:
        raise ContractError(E_UNKNOWN_SCHEMA, f"catalog schemaVersion must be {CATALOG_SCHEMA_VERSION!r}, got {data.get('schemaVersion')!r}")
    dialects = data.get("dialects")
    _require(isinstance(dialects, list) and dialects, E_EMPTY_FILE_SET, "catalog.dialects must be a non-empty array")
    seen_ids: set[str] = set()
    for d in dialects:
        _require(isinstance(d, dict), E_BAD_VALUE, f"dialect entry must be an object: {d!r}")
        did = d.get("id")
        _require(isinstance(did, str) and re.fullmatch(r"[a-z0-9-]+", did or ""), E_BAD_VALUE, f"dialect id invalid: {did!r}")
        _require(did not in seen_ids, E_DUPLICATE_PATH, f"duplicate dialect id: {did!r}")
        seen_ids.add(did)
        _require(isinstance(d.get("displayLabel"), str) and d["displayLabel"], E_MISSING_REQUIRED, f"{did}: displayLabel required")
        _require(isinstance(d.get("asrLanguage"), str) and d["asrLanguage"], E_MISSING_REQUIRED, f"{did}: asrLanguage required")
        for key in ("shortLabel", "family", "mtLanguage", "ttsLanguageCode"):
            if key in d and not isinstance(d[key], str):
                raise ContractError(E_BAD_VALUE, f"{did}: {key} must be a string")
    targets = data.get("targetLanguages")
    _require(isinstance(targets, list) and targets, E_EMPTY_FILE_SET, "catalog.targetLanguages must be a non-empty array")
    seen_targets: set[str] = set()
    for t in targets:
        _require(isinstance(t, dict), E_BAD_VALUE, f"target entry must be an object: {t!r}")
        tid = t.get("id")
        _require(isinstance(tid, str) and re.fullmatch(r"[a-z]{2,3}(-[a-zA-Z0-9-]+)?", tid or ""), E_BAD_VALUE, f"target id invalid: {tid!r}")
        _require(tid not in seen_targets, E_DUPLICATE_PATH, f"duplicate target id: {tid!r}")
        seen_targets.add(tid)
        _require(isinstance(t.get("displayLabel"), str) and t["displayLabel"], E_MISSING_REQUIRED, f"{tid}: displayLabel required")
    return data


def load_catalog(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ContractError(E_BAD_JSON, f"{path}: invalid JSON: {exc}") from exc
    return validate_catalog(data)


# ---------------------------------------------------------------------------
# shared directory checks
# ---------------------------------------------------------------------------

def check_shared_dir(shared_dir: Path) -> list[str]:
    """Validate the live shared contracts. Returns a list of human-readable check names."""
    checks: list[str] = []
    catalog_path = shared_dir / "dialect-catalog" / "catalog.json"
    if not catalog_path.is_file():
        raise ContractError(E_MISSING_REQUIRED, f"missing {catalog_path}")
    load_catalog(catalog_path)
    checks.append(f"dialect-catalog valid: {catalog_path}")

    index_path = shared_dir / "model-manifests" / "index.json"
    if not index_path.is_file():
        raise ContractError(E_MISSING_REQUIRED, f"missing {index_path}")
    try:
        index = json.loads(index_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ContractError(E_BAD_JSON, f"{index_path}: invalid JSON: {exc}") from exc
    if index.get("schemaVersion") != SCHEMA_VERSION_V2:
        raise ContractError(E_UNKNOWN_SCHEMA, f"{index_path}: schemaVersion must be '2'")
    packages = index.get("packages")
    _require(isinstance(packages, dict) and packages, E_EMPTY_FILE_SET, f"{index_path}: packages must be a non-empty object")
    seen_pkgs: set[str] = set()
    for pkg_id, rel in packages.items():
        _require(pkg_id in PACKAGE_IDS, E_BAD_VALUE, f"{index_path}: unknown package id {pkg_id!r}")
        _require(pkg_id not in seen_pkgs, E_DUPLICATE_PATH, f"{index_path}: duplicate package {pkg_id!r}")
        seen_pkgs.add(pkg_id)
        manifest_path = shared_dir / "model-manifests" / rel
        if not manifest_path.is_file():
            raise ContractError(E_MISSING_REQUIRED, f"missing manifest {manifest_path}")
        manifest = load_manifest_v2(manifest_path)
        _require(manifest["packageId"] == pkg_id, E_BAD_VALUE, f"{manifest_path}: packageId mismatch with index key {pkg_id!r}")
        checks.append(f"manifest valid ({manifest['status']}): {manifest_path}")
    checks.append(f"index valid: {index_path}")
    return checks


# ---------------------------------------------------------------------------
# fixture suite runner
# ---------------------------------------------------------------------------

def run_fixture_suite(fixtures_dir: Path) -> list[str]:
    """Run valid/ (must pass) and invalid/ (must fail with the declared code) fixtures."""
    ran: list[str] = []
    valid_dir = fixtures_dir / "valid"
    invalid_dir = fixtures_dir / "invalid"
    if valid_dir.is_dir():
        for path in sorted(valid_dir.glob("*.json")):
            data = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_json_keys,
                              parse_constant=reject_json_constant)
            if data.get("schemaVersion") == CATALOG_SCHEMA_VERSION and "dialects" in data:
                validate_catalog(data)
            else:
                validate_manifest_v2(data)
            ran.append(f"valid {path.name}")
    if invalid_dir.is_dir():
        for path in sorted(invalid_dir.glob("*.json")):
            raw = path.read_text(encoding="utf-8")
            expected_code = None
            data: Any = None
            parse_failed = False
            try:
                data = json.loads(raw, object_pairs_hook=unique_json_keys,
                                  parse_constant=reject_json_constant)
            except (json.JSONDecodeError, ContractError) as exc:
                _require(path.name.endswith(".invalidjson.json"), E_BAD_JSON, f"unexpected JSON error in {path.name}: {exc}")
                expected_code = E_BAD_JSON
                parse_failed = True
            if not parse_failed:
                meta = data.get("$expectedError") if isinstance(data, dict) else None
                _require(
                    isinstance(meta, dict) and isinstance(meta.get("code"), str),
                    E_BAD_VALUE,
                    f"{path.name}: invalid fixture must declare $expectedError.code",
                )
                expected_code = meta["code"]
                data.pop("$expectedError", None)
            if not parse_failed:
                failed = False
                try:
                    if isinstance(data, dict) and "dialects" in data:
                        validate_catalog(data)
                    else:
                        validate_manifest_v2(data)
                except ContractError as exc:
                    failed = True
                    _require(
                        exc.code == expected_code,
                        E_BAD_VALUE,
                        f"{path.name}: expected error {expected_code!r} but got {exc.code!r} ({exc.message})",
                    )
                _require(failed, E_BAD_VALUE, f"{path.name}: expected contract error {expected_code!r} but validation passed")
            ran.append(f"invalid {path.name} -> {expected_code}")
    v1_dir = fixtures_dir / "v1"
    if v1_dir.is_dir():
        for input_path in sorted(v1_dir.glob("*.json")):
            expected_path = v1_dir / "expected" / input_path.name
            _require(expected_path.is_file(), E_MISSING_REQUIRED, f"missing expected output for {input_path.name}")
            try:
                data = json.loads(input_path.read_text(encoding="utf-8"))
            except json.JSONDecodeError as exc:
                raise ContractError(E_BAD_JSON, f"{input_path}: invalid JSON: {exc}") from exc
            migrated = migrate_manifest_v1_to_v2(data)
            expected = json.loads(expected_path.read_text(encoding="utf-8"))
            _require(
                migrated == expected,
                E_BAD_VALUE,
                f"{input_path.name}: migration output differs from expected {expected_path.name}",
            )
            ran.append(f"v1 migration {input_path.name}")
        v1_invalid_dir = v1_dir / "invalid"
        if v1_invalid_dir.is_dir():
            for path in sorted(v1_invalid_dir.glob("*.json")):
                data = json.loads(path.read_text(encoding="utf-8"))
                meta = data.get("$expectedError") if isinstance(data, dict) else None
                _require(
                    isinstance(meta, dict) and isinstance(meta.get("code"), str),
                    E_BAD_VALUE,
                    f"{path.name}: v1 invalid fixture must declare $expectedError.code",
                )
                data.pop("$expectedError", None)
                failed = False
                try:
                    migrate_manifest_v1_to_v2(data)
                except MigrationError as exc:
                    failed = True
                    _require(
                        exc.code == meta["code"],
                        E_BAD_VALUE,
                        f"{path.name}: expected {meta['code']!r} but got {exc.code!r} ({exc.message})",
                    )
                except ContractError as exc:
                    failed = True
                    _require(
                        exc.code == meta["code"],
                        E_BAD_VALUE,
                        f"{path.name}: expected {meta['code']!r} but got ContractError {exc.code!r}",
                    )
                _require(failed, E_BAD_VALUE, f"{path.name}: expected migration error {meta['code']!r} but migration succeeded")
                ran.append(f"v1 migration invalid {path.name} -> {meta['code']}")
    return ran


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main(argv: list[str] | None = None) -> int:
    parser = ArgParser(description="Auralis shared contract validator / v1 migrator")
    sub = parser.add_subparsers(dest="command", required=True)

    p_check = sub.add_parser("check", help="validate the live shared directory")
    p_check.add_argument("--shared-dir", type=Path, default=Path(__file__).resolve().parents[1] / "shared")

    p_fix = sub.add_parser("check-fixtures", help="run the valid/invalid/v1 fixture suite")
    p_fix.add_argument("--fixtures-dir", type=Path, default=Path(__file__).resolve().parents[1] / "shared" / "fixtures")

    p_mig = sub.add_parser("migrate-v1", help="migrate one v1 manifest to v2")
    p_mig.add_argument("--input", type=Path, required=True)
    p_mig.add_argument("--output", type=Path, required=True)

    args = parser.parse_args(argv)
    try:
        if args.command == "check":
            for line in check_shared_dir(args.shared_dir):
                print(line)
        elif args.command == "check-fixtures":
            ran = run_fixture_suite(args.fixtures_dir)
            for line in ran:
                print(line)
            print(f"fixture suite passed ({len(ran)} cases)")
        elif args.command == "migrate-v1":
            v2 = migrate_manifest_file(args.input, args.output)
            print(f"wrote v2 manifest: {args.output} (status={v2['status']})")
        return 0
    except (ContractError, MigrationError) as exc:
        print(f"contract error: {exc}", file=sys.stderr)
        return 3
    except OSError as exc:
        print(f"environment error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
