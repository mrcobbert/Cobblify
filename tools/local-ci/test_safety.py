"""Run with python3 -m unittest discover -s tools/local-ci -p 'test_*.py'."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('release', HERE / 'run-release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseSafety(unittest.TestCase):
    def test_only_exact_authorized_run_identity_is_accepted(self):
        record = {'head_sha': 'a' * 40, 'event': 'workflow_dispatch', 'actor': {'login': 'mrcobbert'},
                  'display_title': 'expected', 'path': '.github/workflows/launcher-update.yml',
                  'repository': {'full_name': 'mrcobbert/Cobblify'}}
        release.validate_run(record, 'a' * 40, 'expected')
        changes = {'head_sha': 'b' * 40, 'event': 'pull_request', 'actor': {'login': 'someone'},
                   'display_title': 'wrong', 'path': '.github/workflows/untrusted.yml',
                   'repository': {'full_name': 'someone/fork'}}
        for field, value in changes.items():
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                release.validate_run(dict(record, **{field: value}), 'a' * 40, 'expected')

    def test_icon_adapter_rejects_changed_source_outputs_and_commit(self):
        with tempfile.TemporaryDirectory() as tmp:
            source, output, dest = [Path(tmp) / n for n in ('Icon.icon', 'compiled', 'out')]
            source.mkdir(); output.mkdir()
            (source / 'icon.json').write_text('{"icon":"test"}')
            (output / 'Assets.car').write_bytes(b'compiled')
            (output / 'assetcatalog_generated_info.plist').write_text('plist')
            env = dict(os.environ, GITHUB_SHA='a' * 40, COBBLIFY_ICON_ASSETS=str(output))
            def run(name, *args):
                return subprocess.run(['python3', str(HERE / name), *map(str,args)], env=env, capture_output=True)
            self.assertEqual(run('icon-assets.py', 'record', source, output).returncode, 0)
            self.assertEqual(run('icon-assets.py', 'verify', source, output).returncode, 0)
            self.assertIn(b'short-bundle-version: 26.3', run('actool.py', '--version', '--output-format=human-readable-text').stdout)
            args = [source, '--compile', dest, '--app-icon', 'Icon', '--output-partial-info-plist', dest / 'assetcatalog_generated_info.plist']
            self.assertEqual(run('actool.py', *args).returncode, 0)
            self.assertEqual((dest / 'Assets.car').read_bytes(), b'compiled')
            (source / 'icon.json').write_text('changed')
            self.assertNotEqual(run('actool.py', *args).returncode, 0)
            self.assertNotEqual(run('icon-assets.py', 'verify', source, output).returncode, 0)
            (source / 'icon.json').write_text('{"icon":"test"}')
            (output / 'Assets.car').write_bytes(b'changed')
            self.assertNotEqual(run('actool.py', *args).returncode, 0)
            (output / 'Assets.car').write_bytes(b'compiled')
            env['GITHUB_SHA'] = 'b' * 40
            self.assertNotEqual(run('actool.py', *args).returncode, 0)
            self.assertNotEqual(run('icon-assets.py', 'verify', source, output).returncode, 0)


if __name__ == '__main__':
    unittest.main()
