"""Stdlib unit tests for the shared contract layer (no third-party deps).

Run: python3 -m unittest discover -s convert/tests
Covered per specs 05 V11: CLI/exit codes, schema rules, v1 migration rules,
path rules, and the guarantee that invalid fixtures fail loudly.
"""

from __future__ import annotations

import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path

CONVERT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CONVERT_DIR))

import manifest_contract as mc  # noqa: E402

FIXTURES = CONVERT_DIR.parent / "shared" / "fixtures"


def valid_draft() -> dict:
    return {
        "schemaVersion": "2",
        "packageId": "asr",
        "version": "0.1.0",
        "status": "draft",
        "source": {"repoId": "Qwen/Qwen3-ASR-0.6B", "revision": None,
                   "upstreamModelId": "Qwen/Qwen3-ASR-0.6B", "licenseSource": "upstream repository"},
        "runtime": {"backend": "onnx", "apiContractVersion": "1", "targetPlatforms":
                    [{"platform": "android", "minSdk": 29, "abis": ["arm64-v8a"]}], "streaming": False},
        "capabilities": {"modes": ["transcribe"], "languages": [], "verification": {}},
        "files": [{"path": "asr/m.onnx", "sizeBytes": None, "sha256": None, "classification": "model"}],
        "roles": {"asr": "asr/m.onnx"},
    }


class ManifestValidationTests(unittest.TestCase):
    def test_valid_draft_passes(self) -> None:
        self.assertEqual(mc.validate_manifest_v2(valid_draft())["packageId"], "asr")

    def test_unknown_schema_rejected(self) -> None:
        data = valid_draft()
        data["schemaVersion"] = "1"
        with self.assertRaises(mc.ContractError) as ctx:
            mc.validate_manifest_v2(data)
        self.assertEqual(ctx.exception.code, mc.E_UNKNOWN_SCHEMA)

    def test_empty_hash_rejected(self) -> None:
        data = valid_draft()
        data["files"][0]["sha256"] = ""
        with self.assertRaises(mc.ContractError) as ctx:
            mc.validate_manifest_v2(data)
        self.assertEqual(ctx.exception.code, mc.E_EMPTY_HASH)

    def test_verified_requires_hash_and_size(self) -> None:
        data = valid_draft()
        data["status"] = "verified"
        data["version"] = "1.0.0"
        data["source"]["revision"] = "0123456789abcdef0123456789abcdef01234567"
        data["runtime"]["runtimeRevision"] = "ort-1.22.0"
        data["capabilities"]["languages"] = ["zh"]
        data["capabilities"]["verification"] = {"languages": "unverified"}
        with self.assertRaises(mc.ContractError) as ctx:
            mc.validate_manifest_v2(data)
        self.assertEqual(ctx.exception.code, mc.E_MISSING_REQUIRED)

        for f in data["files"]:
            f["sizeBytes"] = 10
            f["sha256"] = "a" * 64
        mc.validate_manifest_v2(data)  # now valid

    def test_placeholder_version_and_hash_rejected_for_verified(self) -> None:
        data = valid_draft()
        data["status"] = "verified"
        data["version"] = "0.0.0"
        with self.assertRaises(mc.ContractError) as ctx:
            mc.validate_manifest_v2(data)
        self.assertEqual(ctx.exception.code, mc.E_PLACEHOLDER)

    def test_path_rules(self) -> None:
        for bad in ("/abs/m.onnx", "../up.onnx", "a//b.onnx", "a\\b.onnx", "C:/m.onnx", "dir/"):
            with self.assertRaises(mc.ContractError, msg=bad) as ctx:
                mc.validate_rel_path(bad)
            self.assertEqual(ctx.exception.code, mc.E_PATH_ESCAPE)
        mc.validate_rel_path("asr/tokenizer/tokenizer.json")

    def test_duplicate_path_rejected(self) -> None:
        data = valid_draft()
        data["files"].append(copy.deepcopy(data["files"][0]))
        with self.assertRaises(mc.ContractError) as ctx:
            mc.validate_manifest_v2(data)
        self.assertEqual(ctx.exception.code, mc.E_DUPLICATE_PATH)

    def test_role_missing_rejected(self) -> None:
        data = valid_draft()
        data["roles"]["ghost"] = "asr/missing.onnx"
        with self.assertRaises(mc.ContractError) as ctx:
            mc.validate_manifest_v2(data)
        self.assertEqual(ctx.exception.code, mc.E_ROLE_MISSING)

    def test_external_data_missing_rejected(self) -> None:
        data = valid_draft()
        data["files"][0]["externalData"] = ["asr/m.onnx.data"]
        with self.assertRaises(mc.ContractError) as ctx:
            mc.validate_manifest_v2(data)
        self.assertEqual(ctx.exception.code, mc.E_EXTERNAL_DATA_MISSING)

    def test_empty_files_rejected_for_verified_only(self) -> None:
        data = valid_draft()
        data["files"] = []
        data["roles"] = {}
        mc.validate_manifest_v2(data)  # draft tolerated
        data["status"] = "verified"
        data["version"] = "1.0.0"
        data["source"]["revision"] = "0123456789abcdef0123456789abcdef01234567"
        data["runtime"]["runtimeRevision"] = "ort"
        data["capabilities"]["languages"] = ["zh"]
        data["capabilities"]["verification"] = {"languages": "unverified"}
        with self.assertRaises(mc.ContractError) as ctx:
            mc.validate_manifest_v2(data)
        self.assertEqual(ctx.exception.code, mc.E_EMPTY_FILE_SET)

    def test_v2_rejects_snake_case_size(self) -> None:
        data = valid_draft()
        data["files"][0]["size_bytes"] = 5
        with self.assertRaises(mc.ContractError) as ctx:
            mc.validate_manifest_v2(data)
        self.assertEqual(ctx.exception.code, mc.E_BAD_VALUE)


class MigrationTests(unittest.TestCase):
    def test_v1_snake_case_migrates_to_draft(self) -> None:
        v1 = {
            "schemaVersion": "1", "packageId": "mt", "version": "0.0.0",
            "sourcePackageName": "AngelSlim/Hy-MT1.5-1.8B-1.25bit-GGUF", "checksumAlgorithm": "sha256",
            "files": [{"path": "mt/m.gguf", "size_bytes": 5, "sha256": ""}],
        }
        v2 = mc.migrate_manifest_v1_to_v2(v1)
        self.assertEqual(v2["status"], "draft")
        self.assertIsNone(v2["files"][0]["sha256"])  # empty hash -> null, never fabricated
        self.assertEqual(v2["files"][0]["sizeBytes"], 5)
        self.assertEqual(v2["roles"], {"translator": "mt/m.gguf"})

    def test_conflicting_size_fields_fail(self) -> None:
        v1 = {
            "schemaVersion": "1", "packageId": "asr", "sourcePackageName": "x",
            "files": [{"path": "asr/m.onnx", "sizeBytes": 1, "size_bytes": 2}],
        }
        with self.assertRaises(mc.MigrationError) as ctx:
            mc.migrate_manifest_v1_to_v2(v1)
        self.assertEqual(ctx.exception.code, mc.E_FIELD_CONFLICT)

    def test_equal_size_fields_accepted(self) -> None:
        v1 = {
            "schemaVersion": "1", "packageId": "asr", "sourcePackageName": "x",
            "files": [{"path": "asr/m.onnx", "sizeBytes": 3, "size_bytes": 3}],
        }
        v2 = mc.migrate_manifest_v1_to_v2(v1)
        self.assertEqual(v2["files"][0]["sizeBytes"], 3)

    def test_non_v1_rejected(self) -> None:
        with self.assertRaises(mc.MigrationError) as ctx:
            mc.migrate_manifest_v1_to_v2({"schemaVersion": "2"})
        self.assertEqual(ctx.exception.code, mc.E_UNKNOWN_SCHEMA)

    def test_v1_path_traversal_rejected(self) -> None:
        v1 = {"schemaVersion": "1", "packageId": "asr", "sourcePackageName": "x",
              "files": [{"path": "../evil", "sizeBytes": 1}]}
        # Migration must reject escapes; the raised type is either
        # MigrationError or ContractError but the code is E_PATH_ESCAPE.
        raised = None
        try:
            mc.migrate_manifest_v1_to_v2(v1)
        except (mc.MigrationError, mc.ContractError) as exc:
            raised = exc
        self.assertIsNotNone(raised)
        self.assertEqual(raised.code, mc.E_PATH_ESCAPE)

    def test_migrated_output_is_valid_v2(self) -> None:
        v1 = {
            "schemaVersion": "1", "packageId": "tts", "version": "1.0.0",
            "sourcePackageName": "x/y", "checksumAlgorithm": "sha256",
            "files": [{"path": "tts/v.onnx", "sizeBytes": 5, "sha256": "b" * 64},
                      {"path": "tts/v.onnx.data", "sizeBytes": 6, "sha256": "c" * 64}],
            "roles": {"vocoder": "tts/v.onnx"},
            "assetClasses": {"tts/v.onnx.data": "requiredSupportAsset"},
        }
        v2 = mc.migrate_manifest_v1_to_v2(v1)
        mc.validate_manifest_v2(v2)
        self.assertEqual(v2["files"][0]["externalData"], ["tts/v.onnx.data"])


class CatalogTests(unittest.TestCase):
    def test_duplicate_dialect_id_rejected(self) -> None:
        catalog = {"schemaVersion": "1",
                   "dialects": [{"id": "wu", "displayLabel": "吴语", "asrLanguage": "Wu"},
                                {"id": "wu", "displayLabel": "吴语2", "asrLanguage": "Wu"}],
                   "targetLanguages": [{"id": "zh", "displayLabel": "中文"}]}
        with self.assertRaises(mc.ContractError) as ctx:
            mc.validate_catalog(catalog)
        self.assertEqual(ctx.exception.code, mc.E_DUPLICATE_PATH)

    def test_live_catalog_loads(self) -> None:
        path = CONVERT_DIR.parent / "shared" / "dialect-catalog" / "catalog.json"
        data = mc.load_catalog(path)
        ids = [d["id"] for d in data["dialects"]]
        self.assertEqual(len(ids), len(set(ids)))


class CliExitCodeTests(unittest.TestCase):
    def test_check_ok_is_zero(self) -> None:
        self.assertEqual(mc.main(["check"]), 0)

    def test_check_missing_dir_is_contract_error_3(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(mc.main(["check", "--shared-dir", str(Path(tmp) / "nope")]), 3)

    def test_check_fixtures_ok(self) -> None:
        self.assertEqual(mc.main(["check-fixtures"]), 0)

    def test_migrate_writes_output(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "v1.json"
            src.write_text(json.dumps({
                "schemaVersion": "1", "packageId": "mt", "sourcePackageName": "a/b",
                "files": [{"path": "mt/m.gguf", "size_bytes": None, "sha256": None}],
            }), encoding="utf-8")
            dst = Path(tmp) / "v2.json"
            self.assertEqual(mc.main(["migrate-v1", "--input", str(src), "--output", str(dst)]), 0)
            self.assertEqual(json.loads(dst.read_text())["schemaVersion"], "2")

    def test_bad_args_exit_4(self) -> None:
        with self.assertRaises(SystemExit) as ctx:
            mc.main(["nonsense-command"])
        self.assertEqual(ctx.exception.code, 4)


class FixtureSuiteTests(unittest.TestCase):
    def test_suite_passes(self) -> None:
        ran = mc.run_fixture_suite(FIXTURES)
        self.assertGreater(len(ran), 10)


if __name__ == "__main__":
    unittest.main()
