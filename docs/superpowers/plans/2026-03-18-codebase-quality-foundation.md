# Codebase Quality Foundation Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Normalize the workspace into a single contract-driven monorepo with root developer workflows, explicit generated-output boundaries, and shared dialect/model metadata consumed by Android, iOS, and tooling.

**Architecture:** First establish the monorepo root, ignore policy, and normalized directory layout. Then extract cross-platform metadata into versioned JSON contracts under `shared/`, wire tooling to publish those contracts, and update Android and iOS to consume them through small loader units. Finally add root orchestration commands (`bootstrap`, `doctor`, `verify`, `clean`) and rewrite the top-level docs so local-first verification is obvious.

**Tech Stack:** Kotlin + Gradle, Swift + Xcode/XCTest, Python 3 standard library + existing tooling scripts, JSON contract files, POSIX shell shims.

---

## File Structure

### Final Workspace Layout

```text
.
|- apps/
|  |- android/
|  '- ios/
|- tooling/
|  '- model-convert/
|- shared/
|  |- dialect-catalog/
|  '- model-manifests/
|- docs/
|  |- architecture/
|  |- runbooks/
|  |- specs/
|  '- superpowers/plans/
|- scripts/
|  |- bootstrap
|  |- doctor
|  |- verify
|  |- clean
|  '- workspace/
|- tests/
|  '- workspace/
|- config/
|- .gitignore
'- README.md
```

### Root Governance Files

- Create: `.gitignore`
- Create: `tests/workspace/test_layout.py`
- Create: `tests/workspace/test_workspace_cli.py`
- Create: `scripts/workspace/__init__.py`
- Create: `scripts/workspace/paths.py`
- Create: `scripts/workspace/contracts.py`
- Create: `scripts/workspace/process.py`
- Create: `scripts/workspace/bootstrap.py`
- Create: `scripts/workspace/doctor.py`
- Create: `scripts/workspace/verify.py`
- Create: `scripts/workspace/clean.py`
- Create: `scripts/workspace/main.py`
- Create: `scripts/bootstrap`
- Create: `scripts/doctor`
- Create: `scripts/verify`
- Create: `scripts/clean`

### Shared Contract Files

- Create: `shared/dialect-catalog/catalog.json`
- Create: `shared/dialect-catalog/README.md`
- Create: `shared/model-manifests/index.json`
- Create: `shared/model-manifests/asr.json`
- Create: `shared/model-manifests/tts.json`
- Create: `tests/workspace/test_dialect_catalog.py`
- Create: `tests/workspace/test_model_manifests.py`

### Domain Moves

- Move: `android/` -> `apps/android/`
- Move: `ios/` -> `apps/ios/`
- Move: `convert/` -> `tooling/model-convert/`

### Tooling Integration Files

- Create: `tooling/model-convert/shared_contracts.py`
- Create: `tooling/model-convert/tests/test_shared_contracts.py`
- Modify: `tooling/model-convert/export_asr_onnx.py`
- Modify: `tooling/model-convert/export_tts_onnx.py`
- Modify: `tooling/model-convert/validate_models.py`
- Modify: `tooling/model-convert/benchmark_onnx_runtime.py`
- Modify: `tooling/model-convert/README.md`

### Android Integration Files

- Create: `apps/android/app/src/main/java/com/dialect/interpreter/contracts/DialectCatalog.kt`
- Create: `apps/android/app/src/main/java/com/dialect/interpreter/contracts/ModelManifest.kt`
- Create: `apps/android/app/src/main/java/com/dialect/interpreter/contracts/ContractLoader.kt`
- Create: `apps/android/app/src/test/java/com/dialect/interpreter/contracts/DialectCatalogLoaderTest.kt`
- Create: `apps/android/app/src/test/java/com/dialect/interpreter/contracts/ModelManifestLoaderTest.kt`
- Modify: `apps/android/app/build.gradle.kts`
- Modify: `apps/android/app/src/main/java/com/dialect/interpreter/inference/AsrEngine.kt`
- Modify: `apps/android/app/src/main/java/com/dialect/interpreter/ui/components/DialectSelector.kt`
- Modify: `apps/android/app/src/main/java/com/dialect/interpreter/ui/screens/InterpretScreen.kt`
- Modify: `apps/android/app/src/main/java/com/dialect/interpreter/data/ModelRepository.kt`

### iOS Integration Files

- Create: `apps/ios/DialectInterpreter/Contracts/DialectCatalog.swift`
- Create: `apps/ios/DialectInterpreter/Contracts/ModelManifest.swift`
- Create: `apps/ios/DialectInterpreter/Contracts/ContractLoader.swift`
- Create: `apps/ios/DialectInterpreterTests/ContractLoaderTests.swift`
- Modify: `apps/ios/DialectInterpreter/Inference/AsrEngine.swift`
- Modify: `apps/ios/DialectInterpreter/UI/Components/DialectSelectorView.swift`
- Modify: `apps/ios/DialectInterpreter/UI/Screens/InterpretView.swift`
- Modify: `apps/ios/DialectInterpreter/Data/ModelRepository.swift`
- Modify: `apps/ios/DialectInterpreter.xcodeproj/project.pbxproj`
- Modify: `apps/ios/DialectInterpreter.xcodeproj/xcshareddata/xcschemes/DialectInterpreter.xcscheme`

### Documentation Files

- Create: `docs/architecture/workspace.md`
- Create: `docs/runbooks/bootstrap.md`
- Create: `docs/runbooks/verify.md`
- Create: `docs/runbooks/clean.md`
- Create: `docs/runbooks/model-refresh.md`
- Modify: `README.md`

## Chunk 1: Normalize The Workspace Root

### Task 1: Establish root version control, ignore policy, and layout tests

**Files:**
- Create: `.gitignore`
- Create: `tests/workspace/test_layout.py`

- [ ] **Step 1: Write the failing layout test**

```python
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class WorkspaceLayoutTest(unittest.TestCase):
    def test_expected_root_directories_exist(self):
        expected = [
            "apps",
            "tooling",
            "shared",
            "docs/architecture",
            "docs/runbooks",
            "scripts",
            "config",
            "tests/workspace",
        ]
        missing = [path for path in expected if not (ROOT / path).exists()]
        self.assertEqual([], missing)

    def test_gitignore_covers_generated_output_boundaries(self):
        ignore_text = (ROOT / ".gitignore").read_text(encoding="utf-8")
        for pattern in [
            ".superpowers/",
            "apps/android/.gradle/",
            "apps/android/**/build/",
            "tooling/model-convert/.venv/",
            "tooling/model-convert/__pycache__/",
        ]:
            self.assertIn(pattern, ignore_text)
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `python3 -m unittest tests.workspace.test_layout -v`
Expected: FAIL because `apps/`, `tooling/`, `shared/`, `config/`, and `.gitignore` do not exist yet.

- [ ] **Step 3: Create the root scaffold and ignore rules**

```bash
git rev-parse --show-toplevel >/dev/null 2>&1 || git init
mkdir -p apps tooling shared docs/architecture docs/runbooks scripts/workspace tests/workspace config
cat > .gitignore <<'EOF'
.DS_Store
.superpowers/
apps/android/.gradle/
apps/android/**/build/
apps/android/local.properties
tooling/model-convert/.venv/
tooling/model-convert/__pycache__/
tooling/model-convert/**/*.pyc
EOF
```

- [ ] **Step 4: Run the layout test to verify it passes**

Run: `python3 -m unittest tests.workspace.test_layout -v`
Expected: PASS with both root-layout assertions succeeding.

- [ ] **Step 5: Commit the scaffold**

```bash
git add .gitignore tests/workspace/test_layout.py apps tooling shared docs scripts config
git commit -m "chore: establish monorepo root scaffold"
```

### Task 2: Move Android, iOS, and tooling into the normalized layout

**Files:**
- Move: `android/` -> `apps/android/`
- Move: `ios/` -> `apps/ios/`
- Move: `convert/` -> `tooling/model-convert/`
- Modify: `README.md`
- Test: `tests/workspace/test_layout.py`

- [ ] **Step 1: Extend the failing layout test for normalized domain paths**

```python
    def test_existing_domains_live_under_normalized_roots(self):
        for path in [
            ROOT / "apps/android/app/build.gradle.kts",
            ROOT / "apps/ios/DialectInterpreter.xcodeproj/project.pbxproj",
            ROOT / "tooling/model-convert/export_asr_onnx.py",
        ]:
            self.assertTrue(path.exists(), path)

    def test_legacy_root_domain_paths_are_removed(self):
        for path in [ROOT / "android", ROOT / "ios", ROOT / "convert"]:
            self.assertFalse(path.exists(), path)
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `python3 -m unittest tests.workspace.test_layout -v`
Expected: FAIL because the domain directories still live at the old root paths.

- [ ] **Step 3: Move the directories and rewrite the root README paths**

```bash
mv android apps/android
mv ios apps/ios
mv convert tooling/model-convert
python3 - <<'PY'
from pathlib import Path
root = Path("README.md")
text = root.read_text(encoding="utf-8")
text = text.replace("`android/`", "`apps/android/`")
text = text.replace("`ios/`", "`apps/ios/`")
text = text.replace("`convert/`", "`tooling/model-convert/`")
text = text.replace("cd android", "cd apps/android")
text = text.replace("cd convert", "cd tooling/model-convert")
root.write_text(text, encoding="utf-8")
PY
```

- [ ] **Step 4: Run the layout test to verify the moved paths resolve**

Run: `python3 -m unittest tests.workspace.test_layout -v`
Expected: PASS with the normalized domain-path assertion succeeding.

- [ ] **Step 5: Commit the move**

```bash
git add README.md apps tooling tests/workspace/test_layout.py
git commit -m "refactor: normalize top-level workspace layout"
```

## Chunk 2: Extract And Publish Shared Contracts

### Task 3: Create the shared dialect catalog contract and validator

**Files:**
- Create: `shared/dialect-catalog/catalog.json`
- Create: `shared/dialect-catalog/README.md`
- Create: `tests/workspace/test_dialect_catalog.py`

- [ ] **Step 1: Write the failing dialect catalog contract test**

```python
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "shared/dialect-catalog/catalog.json"


class DialectCatalogContractTest(unittest.TestCase):
    def test_catalog_contains_schema_and_required_entries(self):
        payload = json.loads(CATALOG.read_text(encoding="utf-8"))
        self.assertEqual("1", payload["schemaVersion"])
        self.assertEqual(22, len(payload["dialects"]))
        self.assertGreaterEqual(len(payload["targetLanguages"]), 10)
        first = payload["dialects"][0]
        for field in ["id", "displayLabel", "shortLabel", "family", "asrLanguage", "ttsLanguageCode"]:
            self.assertIn(field, first)
        self.assertEqual(len({item["id"] for item in payload["dialects"]}), len(payload["dialects"]))
        self.assertEqual(len({item["displayLabel"] for item in payload["dialects"]}), len(payload["dialects"]))
        for language in payload["targetLanguages"]:
            for field in ["id", "displayLabel", "asrLanguage"]:
                self.assertIn(field, language)

    def test_catalog_readme_names_owner_and_update_rule(self):
        readme = (ROOT / "shared/dialect-catalog/README.md").read_text(encoding="utf-8")
        self.assertIn("Owner:", readme)
        self.assertIn("Canonical file:", readme)
        self.assertIn("Update process:", readme)
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `python3 -m unittest tests.workspace.test_dialect_catalog -v`
Expected: FAIL because `shared/dialect-catalog/catalog.json` does not exist yet.

- [ ] **Step 3: Add the catalog file and reference notes**

```json
{
  "schemaVersion": "1",
  "dialects": [
    {"id": "mandarin", "displayLabel": "普通话", "shortLabel": "普通话", "family": "mandarin", "asrLanguage": "Chinese", "ttsLanguageCode": "zh"},
    {"id": "cantonese", "displayLabel": "粤语", "shortLabel": "粤语", "family": "yue", "asrLanguage": "Cantonese", "ttsLanguageCode": "zh"},
    {"id": "sichuan", "displayLabel": "四川话", "shortLabel": "四川", "family": "mandarin", "asrLanguage": "Sichuan", "ttsLanguageCode": "zh"},
    {"id": "dongbei", "displayLabel": "东北话", "shortLabel": "东北", "family": "mandarin", "asrLanguage": "Dongbei", "ttsLanguageCode": "zh"},
    {"id": "henan", "displayLabel": "河南话", "shortLabel": "河南", "family": "mandarin", "asrLanguage": "Henan", "ttsLanguageCode": "zh"},
    {"id": "hunan", "displayLabel": "湖南话", "shortLabel": "湖南", "family": "mandarin", "asrLanguage": "Hunan", "ttsLanguageCode": "zh"},
    {"id": "hubei", "displayLabel": "湖北话", "shortLabel": "湖北", "family": "mandarin", "asrLanguage": "Hubei", "ttsLanguageCode": "zh"},
    {"id": "shandong", "displayLabel": "山东话", "shortLabel": "山东", "family": "mandarin", "asrLanguage": "Shandong", "ttsLanguageCode": "zh"},
    {"id": "shaanxi", "displayLabel": "陕西话", "shortLabel": "陕西", "family": "mandarin", "asrLanguage": "Shaanxi", "ttsLanguageCode": "zh"},
    {"id": "fujian", "displayLabel": "福建话", "shortLabel": "福建", "family": "min", "asrLanguage": "Fujian", "ttsLanguageCode": "zh"},
    {"id": "anhui", "displayLabel": "安徽话", "shortLabel": "安徽", "family": "mandarin", "asrLanguage": "Anhui", "ttsLanguageCode": "zh"},
    {"id": "gansu", "displayLabel": "甘肃话", "shortLabel": "甘肃", "family": "mandarin", "asrLanguage": "Gansu", "ttsLanguageCode": "zh"},
    {"id": "guizhou", "displayLabel": "贵州话", "shortLabel": "贵州", "family": "mandarin", "asrLanguage": "Guizhou", "ttsLanguageCode": "zh"},
    {"id": "hebei", "displayLabel": "河北话", "shortLabel": "河北", "family": "mandarin", "asrLanguage": "Hebei", "ttsLanguageCode": "zh"},
    {"id": "jiangxi", "displayLabel": "江西话", "shortLabel": "江西", "family": "gan", "asrLanguage": "Jiangxi", "ttsLanguageCode": "zh"},
    {"id": "ningxia", "displayLabel": "宁夏话", "shortLabel": "宁夏", "family": "mandarin", "asrLanguage": "Ningxia", "ttsLanguageCode": "zh"},
    {"id": "shanxi", "displayLabel": "山西话", "shortLabel": "山西", "family": "jin", "asrLanguage": "Shanxi", "ttsLanguageCode": "zh"},
    {"id": "tianjin", "displayLabel": "天津话", "shortLabel": "天津", "family": "mandarin", "asrLanguage": "Tianjin", "ttsLanguageCode": "zh"},
    {"id": "yunnan", "displayLabel": "云南话", "shortLabel": "云南", "family": "mandarin", "asrLanguage": "Yunnan", "ttsLanguageCode": "zh"},
    {"id": "zhejiang", "displayLabel": "浙江话", "shortLabel": "浙江", "family": "wu", "asrLanguage": "Zhejiang", "ttsLanguageCode": "zh"},
    {"id": "wu", "displayLabel": "吴语", "shortLabel": "吴语", "family": "wu", "asrLanguage": "Wu", "ttsLanguageCode": "zh"},
    {"id": "minnan", "displayLabel": "闽南语", "shortLabel": "闽南", "family": "min", "asrLanguage": "Minnan", "ttsLanguageCode": "zh"}
  ],
  "targetLanguages": [
    {"id": "zh", "displayLabel": "中文", "asrLanguage": "Chinese"},
    {"id": "en", "displayLabel": "English", "asrLanguage": "English"},
    {"id": "ja", "displayLabel": "日本語", "asrLanguage": "Japanese"},
    {"id": "ko", "displayLabel": "한국어", "asrLanguage": "Korean"},
    {"id": "de", "displayLabel": "Deutsch", "asrLanguage": "German"},
    {"id": "fr", "displayLabel": "Français", "asrLanguage": "French"},
    {"id": "ru", "displayLabel": "Русский", "asrLanguage": "Russian"},
    {"id": "pt", "displayLabel": "Português", "asrLanguage": "Portuguese"},
    {"id": "es", "displayLabel": "Español", "asrLanguage": "Spanish"},
    {"id": "it", "displayLabel": "Italiano", "asrLanguage": "Italian"}
  ]
}
```

Also add `shared/dialect-catalog/README.md` with concrete contents like:

```markdown
# Dialect Catalog Contract

- Owner: product/platform maintainers
- Canonical file: `shared/dialect-catalog/catalog.json`
- Consumers: Android UI, iOS UI, model-tooling validation
- Update process: edit `catalog.json`, run `python3 -m unittest tests.workspace.test_dialect_catalog -v`, then run platform verification for any consumer touched by the change
```

- [ ] **Step 4: Run the contract test to verify it passes**

Run: `python3 -m unittest tests.workspace.test_dialect_catalog -v`
Expected: PASS with the schema/version/count checks succeeding.

- [ ] **Step 5: Commit the shared catalog**

```bash
git add shared/dialect-catalog tests/workspace/test_dialect_catalog.py
git commit -m "feat: add shared dialect catalog contract"
```

### Task 4: Create shared model manifests and make tooling publish them

**Files:**
- Create: `shared/model-manifests/index.json`
- Create: `shared/model-manifests/asr.json`
- Create: `shared/model-manifests/tts.json`
- Create: `tests/workspace/test_model_manifests.py`
- Create: `tooling/model-convert/shared_contracts.py`
- Create: `tooling/model-convert/tests/test_shared_contracts.py`
- Modify: `tooling/model-convert/export_asr_onnx.py`
- Modify: `tooling/model-convert/export_tts_onnx.py`
- Modify: `tooling/model-convert/validate_models.py`
- Modify: `tooling/model-convert/benchmark_onnx_runtime.py`
- Modify: `tooling/model-convert/README.md`

- [ ] **Step 1: Write the failing manifest and publisher tests**

```python
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
INDEX = ROOT / "shared/model-manifests/index.json"
sys.path.insert(0, str((ROOT / "tooling/model-convert").resolve()))

from shared_contracts import build_manifest, write_workspace_manifests


class ModelManifestContractTest(unittest.TestCase):
    def test_index_points_to_asr_and_tts_manifests(self):
        payload = json.loads(INDEX.read_text(encoding="utf-8"))
        self.assertEqual("1", payload["schemaVersion"])
        self.assertEqual(["asr", "tts"], sorted(payload["packages"].keys()))
        self.assertEqual("asr.json", payload["packages"]["asr"])
        self.assertEqual("tts.json", payload["packages"]["tts"])

    def test_package_manifest_contains_required_fields(self):
        for package_id in ["asr", "tts"]:
            manifest_path = ROOT / "shared/model-manifests" / f"{package_id}.json"
            self.assertTrue(manifest_path.exists(), manifest_path)
            payload = json.loads(manifest_path.read_text(encoding="utf-8"))
            for field in ["schemaVersion", "packageId", "version", "sourcePackageName", "checksumAlgorithm", "supportedExecutionTargets", "compatibility", "files"]:
                self.assertIn(field, payload)
            self.assertEqual(package_id, payload["packageId"])
```

```python
import json
import tempfile
import unittest
from pathlib import Path

class SharedContractsPublisherTest(unittest.TestCase):
    def test_write_workspace_manifests_merges_packages_and_rebuilds_index(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            export_root = root / "exports"
            manifest_root = root / "shared/model-manifests"
            (export_root / "tts").mkdir(parents=True)
            (export_root / "tts" / "vocoder_int4.onnx").write_bytes(b"abc123")
            (export_root / "asr").mkdir(parents=True)
            (export_root / "asr" / "asr_encoder_int4.onnx").write_bytes(b"xyz789")
            write_workspace_manifests(
                manifest_root=manifest_root,
                manifests={
                    "tts": build_manifest(package_id="tts", version="1.0.0", source_package_name="Qwen/Qwen3-TTS-12Hz-0.6B-Base", root_dir=export_root / "tts", supported_execution_targets=["cpu", "nnapi", "coreml"])
                },
            )
            write_workspace_manifests(
                manifest_root=manifest_root,
                manifests={
                    "asr": build_manifest(package_id="asr", version="1.0.0", source_package_name="Qwen/Qwen3-ASR-0.6B", root_dir=export_root / "asr", supported_execution_targets=["cpu", "nnapi", "coreml"])
                },
            )

            index_payload = json.loads((manifest_root / "index.json").read_text(encoding="utf-8"))
            self.assertEqual(["asr", "tts"], sorted(index_payload["packages"].keys()))
            package_payload = json.loads((manifest_root / "tts.json").read_text(encoding="utf-8"))
            self.assertEqual("sha256", package_payload["checksumAlgorithm"])
            self.assertEqual("vocoder_int4.onnx", package_payload["files"][0]["path"])
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `python3 -m unittest tests.workspace.test_model_manifests -v && python3 -m unittest discover -s tooling/model-convert/tests -p 'test_*.py' -v`
Expected: FAIL because the shared manifest files and publisher helper do not exist yet.

- [ ] **Step 3: Add the manifests and publisher helper**

```json
{
  "schemaVersion": "1",
  "packages": {
    "asr": "asr.json",
    "tts": "tts.json"
  }
}
```

```python
import argparse
import json
import hashlib
from pathlib import Path


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_manifest(package_id: str, version: str, source_package_name: str, root_dir: Path, supported_execution_targets: list[str]) -> dict:
    files = []
    for path in sorted(root_dir.rglob("*")):
        if path.is_file():
            files.append(
                {
                    "path": path.relative_to(root_dir).as_posix(),
                    "sizeBytes": path.stat().st_size,
                    "sha256": sha256_file(path),
                }
            )
    return {
        "schemaVersion": "1",
        "packageId": package_id,
        "version": version,
        "sourcePackageName": source_package_name,
        "checksumAlgorithm": "sha256",
        "supportedExecutionTargets": supported_execution_targets,
        "compatibility": {"minAndroidSdk": 28, "minIosVersion": "17.0"},
        "files": files,
    }


def write_workspace_manifests(manifest_root: Path, manifests: dict[str, dict]) -> None:
    manifest_root.mkdir(parents=True, exist_ok=True)
    existing = {
        path.stem: json.loads(path.read_text(encoding="utf-8"))
        for path in manifest_root.glob("*.json")
        if path.name != "index.json"
    }
    existing.update(manifests)
    index = {"schemaVersion": "1", "packages": {package_id: f"{package_id}.json" for package_id in sorted(existing)}}
    (manifest_root / "index.json").write_text(json.dumps(index, indent=2, ensure_ascii=False), encoding="utf-8")
    for package_id, payload in existing.items():
        (manifest_root / f"{package_id}.json").write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--package-id", choices=["asr", "tts"], required=True)
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--source-package-name", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--manifest-root", type=Path, required=True)
    args = parser.parse_args()
    manifest = build_manifest(
        package_id=args.package_id,
        version=args.version,
        source_package_name=args.source_package_name,
        root_dir=args.source_dir,
        supported_execution_targets=["cpu", "nnapi", "coreml"],
    )
    write_workspace_manifests(args.manifest_root, {args.package_id: manifest})
```

Generate the initial checked-in package manifests with the new CLI instead of hand-authoring them:

```bash
python3 tooling/model-convert/shared_contracts.py --package-id asr --source-dir models/asr --source-package-name "Qwen/Qwen3-ASR-0.6B" --version 1.0.0 --manifest-root shared/model-manifests
python3 tooling/model-convert/shared_contracts.py --package-id tts --source-dir models/tts --source-package-name "Qwen/Qwen3-TTS-12Hz-0.6B-Base" --version 1.0.0 --manifest-root shared/model-manifests
```

The generated `asr.json` and `tts.json` must share the same exact shape emitted by `build_manifest(...)`: top-level package metadata plus per-file `path`, `sizeBytes`, and `sha256` entries. Update both export scripts to call `build_manifest(...)` plus `write_workspace_manifests(...)` so `shared/model-manifests/index.json`, `shared/model-manifests/asr.json`, and `shared/model-manifests/tts.json` are rewritten from exporter output. Update `validate_models.py` and `benchmark_onnx_runtime.py` so their default model root becomes `Path(__file__).resolve().parents[2] / "models"` after the move to `tooling/model-convert/`.

The write path must be merge-safe: publishing `asr` after `tts` (or the reverse) must preserve both package entries in `index.json`.

- [ ] **Step 4: Run the tests to verify manifests and publisher logic pass**

Run: `python3 -m unittest tests.workspace.test_model_manifests -v && python3 -m unittest discover -s tooling/model-convert/tests -p 'test_*.py' -v`
Expected: PASS with contract structure, package metadata, and shared-manifest publisher assertions succeeding, including checks that `index.json` and package manifest files are written to the workspace manifest directory.

- [ ] **Step 5: Commit the shared manifest integration**

```bash
git add shared/model-manifests tooling/model-convert tests/workspace/test_model_manifests.py
git commit -m "feat: publish shared model manifests from tooling"
```

## Chunk 3: Add Root Commands And Wire Platform Consumers

### Task 5: Implement the root workspace command library and shell shims

**Files:**
- Create: `scripts/workspace/__init__.py`
- Create: `scripts/workspace/paths.py`
- Create: `scripts/workspace/contracts.py`
- Create: `scripts/workspace/process.py`
- Create: `scripts/workspace/bootstrap.py`
- Create: `scripts/workspace/doctor.py`
- Create: `scripts/workspace/verify.py`
- Create: `scripts/workspace/clean.py`
- Create: `scripts/workspace/main.py`
- Create: `scripts/bootstrap`
- Create: `scripts/doctor`
- Create: `scripts/verify`
- Create: `scripts/clean`
- Create: `tests/workspace/test_workspace_cli.py`

- [ ] **Step 1: Write the failing CLI contract tests**

```python
import unittest

from scripts.workspace.clean import GENERATED_PATHS, build_clean_targets
from scripts.workspace.doctor import diagnose_prerequisites
from scripts.workspace.main import EXIT_CONTRACT, EXIT_PREREQUISITE, EXIT_USAGE, exit_code_for_error
from scripts.workspace.verify import build_verify_plan


class WorkspaceCliTest(unittest.TestCase):
    def test_verify_plan_fast_scope_keeps_shared_first(self):
        plan = build_verify_plan(mode="fast", scope="android")
        self.assertEqual("shared", plan[0].name)
        self.assertEqual("android", plan[-1].name)

    def test_clean_only_targets_declared_generated_paths(self):
        self.assertIn("apps/android/.gradle", GENERATED_PATHS)
        self.assertIn(".superpowers", GENERATED_PATHS)
        self.assertNotIn("shared", GENERATED_PATHS)

    def test_exit_code_mapping_is_stable(self):
        self.assertEqual(EXIT_USAGE, exit_code_for_error("usage"))
        self.assertEqual(EXIT_PREREQUISITE, exit_code_for_error("prerequisite"))
        self.assertEqual(EXIT_CONTRACT, exit_code_for_error("contract"))

    def test_full_verify_plan_runs_every_domain(self):
        plan = build_verify_plan(mode="full", scope=None)
        self.assertEqual(["shared", "tooling", "android", "ios"], [step.name for step in plan])

    def test_doctor_reports_missing_jdk_as_prerequisite(self):
        diagnostics = diagnose_prerequisites({"java": False, "xcodebuild": True, "python3": True})
        self.assertTrue(any(item.domain == "android" and item.kind == "prerequisite" for item in diagnostics))

    def test_clean_scope_android_only_uses_android_generated_paths(self):
        targets = build_clean_targets(scope="android")
        self.assertIn("apps/android/.gradle", targets)
        self.assertNotIn("tooling/model-convert/.venv", targets)
```

- [ ] **Step 2: Run the CLI tests to verify they fail**

Run: `python3 -m unittest tests.workspace.test_workspace_cli -v`
Expected: FAIL because the `scripts.workspace` modules do not exist yet.

- [ ] **Step 3: Add the Python command library and thin shell entrypoints**

```python
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EXIT_VERIFY = 1
EXIT_PREREQUISITE = 2
EXIT_CONTRACT = 3
EXIT_USAGE = 4
GENERATED_PATHS = [
    ".superpowers",
    "apps/android/.gradle",
    "apps/android/app/build",
    "tooling/model-convert/.venv",
    "tooling/model-convert/__pycache__",
]


@dataclass(frozen=True)
class VerifyStep:
    name: str
    command: list[str]


@dataclass(frozen=True)
class Diagnostic:
    domain: str
    kind: str
    message: str


def exit_code_for_error(kind: str) -> int:
    return {
        "verification": EXIT_VERIFY,
        "prerequisite": EXIT_PREREQUISITE,
        "contract": EXIT_CONTRACT,
        "usage": EXIT_USAGE,
    }[kind]


def build_verify_plan(mode: str, scope: str | None) -> list[VerifyStep]:
    shared = VerifyStep("shared", ["python3", "-m", "unittest", "tests.workspace.test_dialect_catalog", "tests.workspace.test_model_manifests", "-v"])
    tooling = VerifyStep("tooling", ["python3", "-m", "unittest", "discover", "-s", "tooling/model-convert/tests", "-p", "test_*.py", "-v"])
    android = VerifyStep("android", ["./gradlew", ":app:testDebugUnitTest", ":app:lintDebug"])
    ios = VerifyStep("ios", ["xcodebuild", "test", "-project", "DialectInterpreter.xcodeproj", "-scheme", "DialectInterpreter", "-destination", "platform=iOS Simulator,name=iPhone 16"])
    if scope == "android":
        return [shared, android]
    if scope == "ios":
        return [shared, ios]
    if scope == "tooling":
        return [shared, tooling]
    return [shared, tooling] if mode == "fast" else [shared, tooling, android, ios]


def diagnose_prerequisites(tool_status: dict[str, bool]) -> list[Diagnostic]:
    diagnostics = []
    if not tool_status.get("java", False):
        diagnostics.append(Diagnostic("android", "prerequisite", "JDK 17 missing"))
    if not tool_status.get("xcodebuild", False):
        diagnostics.append(Diagnostic("ios", "prerequisite", "Xcode command line tools missing"))
    if not tool_status.get("python3", False):
        diagnostics.append(Diagnostic("tooling", "prerequisite", "python3 missing"))
    return diagnostics


def build_clean_targets(scope: str) -> list[str]:
    mapping = {
        "android": ["apps/android/.gradle", "apps/android/app/build"],
        "ios": [],
        "tooling": ["tooling/model-convert/.venv", "tooling/model-convert/__pycache__"],
        "all": GENERATED_PATHS,
    }
    return mapping[scope]
```

```bash
#!/usr/bin/env bash
set -euo pipefail
python3 -m scripts.workspace.main verify "$@"
```

Implement `bootstrap`, `doctor`, `verify`, and `clean` as separate modules that only orchestrate known paths and subprocesses. Keep output terse and owner-attributed: `shared: PASS`, `android: FAIL`, etc.

- `paths.py` owns root-relative source and generated path constants only
- `contracts.py` owns shared-contract validation only
- `process.py` owns subprocess execution and owner-attributed summaries only
- `main.py` owns argument parsing and exit-code mapping only
- `bootstrap.py`, `doctor.py`, `verify.py`, and `clean.py` each own one command flow only

Implement the CLI with explicit subcommands and options:

```text
./scripts/bootstrap
./scripts/doctor
./scripts/verify --mode fast
./scripts/verify --mode full
./scripts/verify --scope android
./scripts/clean --scope android
./scripts/clean --scope all
```

Expected text contract:

- `doctor` prints one line per issue such as `android: prerequisite: JDK 17 missing`
- `verify --mode fast` prints `shared: PASS` and `tooling: PASS`, plus any scoped domain selected
- `clean --scope android` prints only Android-owned generated paths before deleting them
- invalid command usage exits with code `4` and prints the argparse usage banner

- [ ] **Step 4: Run the CLI tests to verify they pass**

Run: `python3 -m unittest tests.workspace.test_workspace_cli -v`
Expected: PASS with verify-plan ordering and generated-path ownership checks succeeding.

- [ ] **Step 5: Commit the root command surface**

```bash
git add scripts tests/workspace/test_workspace_cli.py
git commit -m "feat: add root workspace commands"
```

### Task 6: Update Android to read the shared contracts instead of hard-coded lists

**Files:**
- Create: `apps/android/app/src/main/java/com/dialect/interpreter/contracts/DialectCatalog.kt`
- Create: `apps/android/app/src/main/java/com/dialect/interpreter/contracts/ModelManifest.kt`
- Create: `apps/android/app/src/main/java/com/dialect/interpreter/contracts/ContractLoader.kt`
- Create: `apps/android/app/src/test/java/com/dialect/interpreter/contracts/DialectCatalogLoaderTest.kt`
- Create: `apps/android/app/src/test/java/com/dialect/interpreter/contracts/ModelManifestLoaderTest.kt`
- Modify: `apps/android/app/build.gradle.kts`
- Modify: `apps/android/app/src/main/java/com/dialect/interpreter/inference/AsrEngine.kt`
- Modify: `apps/android/app/src/main/java/com/dialect/interpreter/ui/components/DialectSelector.kt`
- Modify: `apps/android/app/src/main/java/com/dialect/interpreter/ui/screens/InterpretScreen.kt`
- Modify: `apps/android/app/src/main/java/com/dialect/interpreter/data/ModelRepository.kt`

- [ ] **Step 1: Write the failing Android unit tests**

```kotlin
class DialectCatalogLoaderTest {
    @Test
    fun loadsCatalogFromSharedAssets() {
        val loader = ContractLoader(assetReader = FakeAssetReader("dialect-catalog/catalog.json", SAMPLE_CATALOG_JSON))
        val catalog = loader.loadDialectCatalog()

        assertEquals(22, catalog.dialects.size)
        assertEquals("普通话", catalog.dialects.first().displayLabel)
    }
}
```

```kotlin
class ModelManifestLoaderTest {
    @Test
    fun readsSharedManifestIndexAndPackageFiles() {
        val loader = ContractLoader(assetReader = FakeAssetReader.multi(SAMPLE_INDEX_JSON, SAMPLE_ASR_JSON, SAMPLE_TTS_JSON))
        val manifests = loader.loadModelManifests()

        assertTrue(manifests.containsKey("asr"))
        assertTrue(manifests["tts"]!!.files.any { it.path.endsWith("vocoder_int4.onnx") })
    }
}
```

- [ ] **Step 2: Run the Android tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.dialect.interpreter.contracts.*"`
Workdir: `apps/android`
Expected: FAIL because the `contracts` package and test fixtures do not exist yet.

- [ ] **Step 3: Add the contract loader and swap Android consumers to it**

```kotlin
@Serializable
data class DialectCatalog(
    val schemaVersion: String,
    val dialects: List<DialectEntry>,
    val targetLanguages: List<TargetLanguageEntry>,
)

@Serializable data class DialectEntry(val id: String, val displayLabel: String, val asrLanguage: String, val ttsLanguageCode: String)
@Serializable data class TargetLanguageEntry(val id: String, val displayLabel: String, val asrLanguage: String)
```

```kotlin
class ContractLoader(private val assetReader: AssetReader) {
    fun loadDialectCatalog(): DialectCatalog = json.decodeFromString(assetReader.readText("dialect-catalog/catalog.json"))
    fun loadModelManifests(): Map<String, ModelManifest> {
        val index = json.decodeFromString<ModelManifestIndex>(assetReader.readText("model-manifests/index.json"))
        return index.packages.mapValues { (_, path) -> json.decodeFromString(assetReader.readText(path)) }
    }
}
```

Update `app/build.gradle.kts` so the Android app packages the canonical `shared/` directory as build input:

```kotlin
android {
    sourceSets["main"].assets.srcDirs("../../../shared")
}
```

Because `../../../shared` becomes the asset root, the loader should resolve `dialect-catalog/catalog.json` and `model-manifests/index.json`, not paths prefixed with `shared/`. Then replace `AsrEngine.CHINESE_DIALECTS`, `AsrEngine.SUPPORTED_LANGUAGES`, and `ModelRepository` manifest assumptions with loader-backed values injected from `DialectApp` or lazy singleton accessors.

- [ ] **Step 4: Run the Android unit tests and lint to verify the integration passes**

Run: `./gradlew :app:testDebugUnitTest :app:lintDebug`
Workdir: `apps/android`
Expected: PASS with the new contract-loader tests and existing Android lint checks succeeding.

- [ ] **Step 5: Commit the Android integration**

```bash
git add apps/android shared apps/android/app/src/test
git commit -m "refactor: load Android metadata from shared contracts"
```

### Task 7: Update iOS to read the shared contracts and add contract tests

**Files:**
- Create: `apps/ios/DialectInterpreter/Contracts/DialectCatalog.swift`
- Create: `apps/ios/DialectInterpreter/Contracts/ModelManifest.swift`
- Create: `apps/ios/DialectInterpreter/Contracts/ContractLoader.swift`
- Create: `apps/ios/DialectInterpreterTests/ContractLoaderTests.swift`
- Modify: `apps/ios/DialectInterpreter/Inference/AsrEngine.swift`
- Modify: `apps/ios/DialectInterpreter/UI/Components/DialectSelectorView.swift`
- Modify: `apps/ios/DialectInterpreter/UI/Screens/InterpretView.swift`
- Modify: `apps/ios/DialectInterpreter/Data/ModelRepository.swift`
- Modify: `apps/ios/DialectInterpreter.xcodeproj/project.pbxproj`
- Modify: `apps/ios/DialectInterpreter.xcodeproj/xcshareddata/xcschemes/DialectInterpreter.xcscheme`

- [ ] **Step 1: Write the failing iOS contract-loader test**

```swift
final class ContractLoaderTests: XCTestCase {
    func testLoadsBundledDialectCatalog() throws {
        let loader = ContractLoader(bundle: Bundle(for: ContractLoaderTests.self))
        let catalog = try loader.loadDialectCatalog()

        XCTAssertEqual("1", catalog.schemaVersion)
        XCTAssertEqual(22, catalog.dialects.count)
        XCTAssertEqual("普通话", catalog.dialects.first?.displayLabel)
    }

    func testLoadsBundledManifestIndex() throws {
        let loader = ContractLoader(bundle: Bundle(for: ContractLoaderTests.self))
        let manifestIndex = try loader.loadModelManifestIndex()

        XCTAssertEqual("asr.json", manifestIndex.packages["asr"])
        XCTAssertEqual("tts.json", manifestIndex.packages["tts"])
    }
}
```

- [ ] **Step 2: Run the iOS test to verify it fails**

Run: `xcodebuild test -project "DialectInterpreter.xcodeproj" -scheme "DialectInterpreter" -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:DialectInterpreterTests/ContractLoaderTests`
Workdir: `apps/ios`
Expected: FAIL because the `Contracts` group, test target sources, and shared JSON resources are not wired into the project yet.

- [ ] **Step 3: Add the Swift loader types and bundle the shared JSON resources**

```swift
struct DialectCatalog: Decodable {
    let schemaVersion: String
    let dialects: [DialectEntry]
    let targetLanguages: [TargetLanguageEntry]
}

struct DialectEntry: Decodable {
    let id: String
    let displayLabel: String
    let asrLanguage: String
    let ttsLanguageCode: String
}
```

```swift
final class ContractLoader {
    private let bundle: Bundle

    init(bundle: Bundle = .main) {
        self.bundle = bundle
    }

    func loadDialectCatalog() throws -> DialectCatalog {
        try loadJSON(named: "catalog", subdirectory: "shared/dialect-catalog")
    }

    func loadModelManifestIndex() throws -> ModelManifestIndex {
        try loadJSON(named: "index", subdirectory: "shared/model-manifests")
    }
}
```

Update `project.pbxproj` so the app target and test target both include a blue folder reference named `shared` that points to `../../shared`. That preserves a bundled resource layout of `shared/dialect-catalog/catalog.json` and `shared/model-manifests/index.json`, which matches the loader subdirectory contract. Then replace the hard-coded dialect arrays in `AsrEngine.swift` and the manifest assumptions in `ModelRepository.swift` with `ContractLoader` lookups cached near app startup.

- [ ] **Step 4: Run the iOS test and scheme build to verify the integration passes**

Run: `xcodebuild test -project "DialectInterpreter.xcodeproj" -scheme "DialectInterpreter" -destination 'platform=iOS Simulator,name=iPhone 16'`
Workdir: `apps/ios`
Expected: PASS with `ContractLoaderTests` and the app scheme building against the shared resource references.

- [ ] **Step 5: Commit the iOS integration**

```bash
git add apps/ios shared
git commit -m "refactor: load iOS metadata from shared contracts"
```

## Chunk 4: Rewrite Docs And Lock In Verification

### Task 8: Rewrite the root docs and runbooks around the new command contract

**Files:**
- Create: `docs/architecture/workspace.md`
- Create: `docs/runbooks/bootstrap.md`
- Create: `docs/runbooks/verify.md`
- Create: `docs/runbooks/clean.md`
- Create: `docs/runbooks/model-refresh.md`
- Modify: `README.md`
- Test: `tests/workspace/test_layout.py`

- [ ] **Step 1: Add a failing documentation smoke test**

```python
    def test_readme_points_to_new_runbooks(self):
        text = (ROOT / "README.md").read_text(encoding="utf-8")
        self.assertIn("docs/architecture/workspace.md", text)
        self.assertIn("docs/runbooks/bootstrap.md", text)
        self.assertIn("docs/runbooks/verify.md", text)
        self.assertIn("docs/runbooks/clean.md", text)
```

- [ ] **Step 2: Run the smoke test to verify it fails**

Run: `python3 -m unittest tests.workspace.test_layout -v`
Expected: FAIL because the README does not point to the new architecture and runbook docs yet.

- [ ] **Step 3: Write the docs and simplify the root README into a navigation layer**

```markdown
# Cross Dialect Communication

## Workspace Guide

- Architecture: `docs/architecture/workspace.md`
- Bootstrap: `docs/runbooks/bootstrap.md`
- Verify: `docs/runbooks/verify.md`
- Clean: `docs/runbooks/clean.md`
- Model refresh: `docs/runbooks/model-refresh.md`
```

`docs/architecture/workspace.md` should explain ownership boundaries, the output matrix, and how `shared/` is consumed. `docs/runbooks/bootstrap.md` should document prerequisite checks and first-time setup. `docs/runbooks/verify.md` should document `./scripts/verify --mode fast`, `./scripts/verify --mode full`, and scoped verification. `docs/runbooks/clean.md` should document `./scripts/clean --scope android|ios|tooling|all` and list the exact generated paths each scope can remove. `docs/runbooks/model-refresh.md` should explain how exporters publish updated manifests.

- [ ] **Step 4: Run the smoke test and root fast verification to confirm the docs match the command surface**

Run: `python3 -m unittest tests.workspace.test_layout -v && ./scripts/verify --mode fast`
Expected: PASS with the README link smoke test succeeding and the fast verification reporting `shared: PASS` plus at least the tooling checks.

- [ ] **Step 5: Commit the documentation rewrite**

```bash
git add README.md docs tests/workspace/test_layout.py
git commit -m "docs: add workspace architecture and runbooks"
```

### Task 9: Run the full workspace gate and remove temporary migration shims

**Files:**
- Modify: `scripts/workspace/verify.py`
- Modify: `docs/runbooks/verify.md`
- Modify: `tests/workspace/test_layout.py`
- Modify: `apps/android/app/build.gradle.kts`
- Modify: `apps/ios/DialectInterpreter.xcodeproj/project.pbxproj`
- Test: `tests/workspace/test_workspace_cli.py`

- [ ] **Step 1: Add a failing regression test for the final verification contract**

```python
    def test_full_mode_runs_all_domain_steps(self):
        plan = build_verify_plan(mode="full", scope=None)
        self.assertEqual(["shared", "tooling", "android", "ios"], [step.name for step in plan])

    def test_workspace_has_no_platform_local_shared_contract_copies(self):
        for path in [
            ROOT / "apps/android/app/src/main/assets/dialect-catalog",
            ROOT / "apps/android/app/src/main/assets/model-manifests",
            ROOT / "apps/ios/DialectInterpreter/Resources/shared",
        ]:
            self.assertFalse(path.exists(), path)
```

- [ ] **Step 2: Run the regression test to verify it fails if full mode is still partial**

Run: `python3 -m unittest tests.workspace.test_workspace_cli -v`
Expected: FAIL until `verify.py` guarantees full-mode ordering and no platform-local duplicate contract paths remain.

- [ ] **Step 3: Finalize `verify.py`, delete migration shims, and ensure the documented commands match reality**

```python
def build_verify_plan(mode: str, scope: str | None) -> list[VerifyStep]:
    if mode == "full" and scope is None:
        return [shared_step(), tooling_step(), android_step(), ios_step()]
    ...
```

At this step keep only canonical shared-contract locations. Ensure `apps/android/app/build.gradle.kts` still points at `../../../shared` instead of copied asset folders, ensure `apps/ios/DialectInterpreter.xcodeproj/project.pbxproj` still references the root `../../shared` folder reference instead of copied resource duplicates, and delete any accidental platform-local copies under:

- `apps/android/app/src/main/assets/dialect-catalog/`
- `apps/android/app/src/main/assets/model-manifests/`
- `apps/ios/DialectInterpreter/Resources/shared/`

- [ ] **Step 4: Run the complete workspace verification**

Run: `python3 -m unittest tests.workspace -v && ./scripts/verify --mode full`
Expected: PASS with root Python tests, tooling tests, Android lint/unit tests, and iOS scheme tests all succeeding from the normalized workspace.

- [ ] **Step 5: Commit the final Phase 1 state**

```bash
git add .
git commit -m "chore: finish codebase quality foundation"
```
