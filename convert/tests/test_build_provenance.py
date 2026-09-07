import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from manifest_contract import ContractError, validate_build_provenance, verify_build_record


class BuildProvenanceTests(unittest.TestCase):
    def setUp(self):
        fixture = Path(__file__).resolve().parents[2] / 'shared/fixtures/valid/manifest-tts-built-draft.json'
        self.manifest = json.loads(fixture.read_text())
        self.record = dict(schemaVersion=1, recipeId='auralis.qwen3-tts.api2.fp32.v1',
            upstream=dict(repoId='Qwen/Qwen3-TTS-12Hz-0.6B-Base', revision=self.manifest['source']['revision']),
            sourceCode=dict(revision='022e286b98fbec7e1e916cb940cdf532cd9f488e'),
            recipe=dict(path='build_tts_api2_package.py', sha256='a'*64),
            toolchain=dict(onnxruntime='1.24.2'),
            inputs=[dict(path='model.safetensors', sizeBytes=8, sha256='e'*64)], outputs=[])
        for f in self.manifest['files']:
            if f['path'].endswith('build-provenance.json'):
                continue
            entry = {k: f[k] for k in ('path', 'sha256', 'sizeBytes')}
            entry['path'] = entry['path'].removeprefix('tts/')
            if entry['path'].startswith('vocoder'):
                entry['role'] = 'vocoder'
            if entry['path'].endswith('.data'):
                entry['externalData'] = True
            self.record['outputs'].append(entry)

    def test_exact_record(self):
        validate_build_provenance(self.manifest, self.record)

    def test_shared_cross_platform_fixtures(self):
        path = Path(__file__).resolve().parents[2] / 'shared/build-provenance-fixtures.json'
        for case in json.loads(path.read_text()):
            with self.subTest(case=case['name']):
                if case['valid']:
                    validate_build_provenance(case['manifest'], case['record'])
                else:
                    with self.assertRaises(ContractError):
                        validate_build_provenance(case['manifest'], case['record'])

    def test_each_source_identity_is_bound(self):
        for section, field, bad in [('upstream','revision','f'*40), ('upstream','repoId','unknown'),
                                   ('sourceCode','revision','f'*40), ('recipe','sha256','0'*64),
                                   ('toolchain','onnxruntime','1.29.0')]:
            with self.subTest(section=section):
                changed = copy.deepcopy(self.record)
                changed[section][field] = bad
                with self.assertRaises(ContractError):
                    validate_build_provenance(self.manifest, changed)

    def test_hash_size_role_and_external_data_must_match(self):
        for field, value in [('sha256','e'*64), ('sizeBytes',1), ('sizeBytes',True),
                             ('role','talker'), ('role',None), ('externalData',True)]:
            with self.subTest(field=field, value=value):
                changed = copy.deepcopy(self.record)
                changed['outputs'][0][field] = value
                with self.assertRaises(ContractError):
                    validate_build_provenance(self.manifest, changed)

    def test_missing_extra_duplicate_and_traversal_outputs(self):
        for mode in ('missing','extra','duplicate','traversal'):
            with self.subTest(mode=mode):
                changed = copy.deepcopy(self.record)
                if mode == 'missing': changed['outputs'].pop()
                elif mode == 'extra': changed['outputs'].append(dict(path='extra.bin',sha256='a'*64,sizeBytes=1))
                elif mode == 'duplicate': changed['outputs'].append(changed['outputs'][0].copy())
                else: changed['outputs'][0]['path'] = '../outside'
                with self.assertRaises(ContractError):
                    validate_build_provenance(self.manifest, changed)

    def test_record_never_hashes_itself(self):
        self.record['outputs'].append(dict(path='build-provenance.json',sha256='a'*64,sizeBytes=1))
        with self.assertRaises(ContractError):
            validate_build_provenance(self.manifest, self.record)

    def test_duplicate_keys_nonfinite_and_size_limit(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'build-provenance.json'
            for raw in ('{"recipeId":1,"recipeId":2}', '{"x":NaN}', ' '*(1024*1024+1)):
                path.write_text(raw)
                with self.assertRaises(ContractError): verify_build_record(self.manifest, Path(d))

    def test_api1_has_no_new_record_requirement(self):
        del self.manifest['source']['build']
        validate_build_provenance(self.manifest, {})


if __name__ == '__main__': unittest.main()
