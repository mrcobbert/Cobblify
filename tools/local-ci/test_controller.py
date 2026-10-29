"""Fault injection: cleanup must survive API failures and preserve other runners."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.dont_write_bytecode = True
SOURCE = Path(__file__).resolve().parent / 'run-release.py'
spec = importlib.util.spec_from_file_location('review_release', SOURCE)
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
COMMIT = 'a' * 40
NONCE = 'b' * 32
RUN = '123456'
TITLE = f'Launcher 0.18.1 local {NONCE}'

class FakeProc:
    next_pid = 90000
    def __init__(self, harness, args, **kwargs):
        self.harness = harness
        self.args = args
        self.pid = FakeProc.next_pid
        FakeProc.next_pid += 1
        self.returncode = None
        self.stdin = io.StringIO() if kwargs.get('stdin') else None
        self.is_windows = args[0] == 'ssh'
        harness.processes.append(self)
        if self.is_windows:
            if harness.scenario == 'existing_windows':
                self.returncode = 1
            else:
                harness.windows_state.write_text(json.dumps({'agentName': f'cobblify-windows-{RUN}'}))
                harness.windows_listener = True
                harness.registrations.append({'name': f'cobblify-windows-{RUN}', 'id': 2})
    def poll(self):
        return self.returncode
    def wait(self, timeout):
        self.harness.events.append(('wait', self.pid, timeout))
        if self.returncode is None:
            if self.harness.scenario in ('happy', 'delete_failure'):
                self.returncode = 0
                if self.is_windows:
                    self.harness.windows_listener = False
            else:
                raise subprocess.TimeoutExpired(self.args, timeout)
        return self.returncode

class Harness:
    def __init__(self, scenario):
        self.scenario = scenario
        self.tmp = tempfile.TemporaryDirectory(prefix='cobblify-ci-review-')
        self.root = Path(self.tmp.name)
        self.home = self.root / 'ci'
        self.home.mkdir()
        self.mac = self.home / 'runner-macos'
        self.mac.mkdir()
        self.windows_state = self.root / 'windows.runner'
        self.windows_hook = self.root / 'windows-hook.ps1'
        (self.root / 'gradle.properties').write_text('mod_version=0.18.1\n')
        self.processes = []
        self.events = []
        self.registrations = []
        self.windows_listener = False
        self.records = 0
        if scenario == 'existing_mac':
            (self.mac / '.runner').write_text(json.dumps({'agentName': 'pre-existing-mac'}))
            (self.mac / '.credentials').write_text('pre-existing-credentials')
            (self.home / 'job-started.sh').write_text('pre-existing-hook')
        if scenario == 'existing_windows':
            self.windows_state.write_text(json.dumps({'agentName': 'pre-existing-windows'}))
    def record(self, run=None):
        self.records += 1
        if self.records > 1 and self.scenario in ('runtime_api_failure', 'cancel_failure', 'registration_api_failure', 'windows_cleanup_failure'):
            raise RuntimeError('mock workflow API unavailable')
        completed = self.records > 1 and self.scenario in ('happy', 'delete_failure')
        return {'id': int(RUN), 'head_sha': COMMIT, 'event': 'workflow_dispatch', 'actor': {'login': 'mrcobbert'},
            'display_title': TITLE, 'path': '.github/workflows/launcher-update.yml', 'repository': {'full_name': release.REPO},
            'html_url': 'https://example.invalid/run', 'status': 'completed' if completed else 'in_progress', 'conclusion': 'success' if completed else None}
    def api(self, path, *args):
        self.events.append(('api', path, args))
        if '/workflows/launcher-update.yml/runs?' in path:
            # Listing does not consume runtime polling sequence.
            self.records -= 1
            return {'workflow_runs': [self.record()]}
        if path.endswith('/registration-token'):
            return {'token': 'mock-token'}
        if path.endswith('/jobs'):
            return {'jobs': [{'name': 'local-assets', 'status': 'completed', 'conclusion': 'failure' if self.scenario == 'assets_failure' else 'success'},
                             {'name': 'macos', 'status': 'queued', 'conclusion': None}, {'name': 'windows', 'status': 'queued', 'conclusion': None}]}
        raise AssertionError(('unexpected API', path, args))
    def runner_ids(self):
        self.events.append(('list-runners',))
        if self.scenario == 'registration_api_failure':
            raise RuntimeError('mock runner API unavailable')
        return list(self.registrations)
    def gh(self, *args):
        self.events.append(('gh', *args))
        if args[:2] == ('run', 'cancel') and self.scenario == 'cancel_failure':
            raise subprocess.CalledProcessError(1, ['gh', *args])
        if args[0] == 'api' and args[-1] == 'DELETE':
            if self.scenario == 'delete_failure':
                raise subprocess.CalledProcessError(1, ['gh', *args])
            identity = int(args[1].split('/')[-1])
            self.registrations = [r for r in self.registrations if r['id'] != identity]
        return ''
    def command(self, args, **kwargs):
        self.events.append(('command', *args))
        out = ''
        if args[0] == 'git':
            if args[1:3] == ['branch', '--show-current']: out = 'feat/local-ci\n'
            elif args[1:3] == ['rev-parse', 'HEAD']: out = COMMIT + '\n'
            elif args[1:3] == ['status', '--porcelain']: out = ''
            elif args[1:3] == ['ls-remote', 'origin']: out = COMMIT + '\trefs/heads/feat/local-ci\n'
            else: raise AssertionError(args)
        elif args[0].endswith('/config.sh'):
            (self.mac / '.runner').write_text(json.dumps({'agentName': f'cobblify-macos-{RUN}'}))
            (self.mac / '.credentials').write_text('mock-current-run-credentials')
            self.registrations.append({'name': f'cobblify-macos-{RUN}', 'id': 1})
        elif args[0] == 'ssh':
            source = args[-1]
            if ' -EncodedCommand ' in source:
                import base64
                encoded = source.split(' -EncodedCommand ')[1].split(';')[0]
                source = base64.b64decode(encoded).decode('utf-16-le')
            if '.Kill($true)' in source:
                if self.scenario == 'windows_cleanup_failure':
                    raise subprocess.CalledProcessError(1, args)
                if self.windows_state.exists() and json.loads(self.windows_state.read_text())['agentName'] == f'cobblify-windows-{RUN}':
                    self.windows_listener = False
            if 'Remove-Item' in source:
                assert '.agentName -eq' in source, 'Windows auth cleanup missing ownership gate'
                if self.windows_state.exists() and json.loads(self.windows_state.read_text())['agentName'] == f'cobblify-windows-{RUN}':
                    self.windows_state.unlink()
        return subprocess.CompletedProcess(args, 0, stdout=out, stderr='')
    def killpg(self, pid, sig):
        self.events.append(('killpg', pid, sig))
        proc = next(p for p in self.processes if p.pid == pid)
        proc.returncode = -sig
    def run(self):
        stack = contextlib.ExitStack()
        with stack:
            for name, value in [('ROOT', self.root), ('HOME', self.home), ('command', self.command), ('gh', self.gh), ('api', self.api), ('run_record', self.record), ('runner_ids', self.runner_ids)]:
                stack.enter_context(patch.object(release, name, value))
            stack.enter_context(patch.object(release.secrets, 'token_hex', lambda n: NONCE))
            stack.enter_context(patch.object(release.subprocess, 'Popen', lambda *a, **kw: FakeProc(self, *a, **kw)))
            stack.enter_context(patch.object(release.os, 'killpg', self.killpg))
            stack.enter_context(patch.object(release.time, 'sleep', lambda n: None))
            stack.enter_context(patch.object(release.sys, 'argv', ['run-release.py']))
            stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            release.main()

class CleanupReview(unittest.TestCase):
    def case(self, scenario):
        h = Harness(scenario)
        self.addCleanup(h.tmp.cleanup)
        return h
    def assert_stopped(self, h):
        self.assertTrue(all(p.returncode is not None for p in h.processes), 'a local tracked process remains alive')
        self.assertFalse(h.windows_listener, 'Windows listener remains alive')
    def test_happy_path_stops_processes_and_clears_owned_config(self):
        h = self.case('happy'); h.run(); self.assert_stopped(h)
        self.assertFalse(h.registrations)
        self.assertFalse((h.mac / '.runner').exists())
        self.assertFalse(h.windows_state.exists())
    def test_preexisting_mac_auth_is_preserved(self):
        h = self.case('existing_mac')
        with self.assertRaisesRegex(RuntimeError, 'already configured'): h.run()
        self.assertEqual(json.loads((h.mac / '.runner').read_text())['agentName'], 'pre-existing-mac')
        self.assertEqual((h.mac / '.credentials').read_text(), 'pre-existing-credentials')
        self.assertFalse(h.processes)
    def test_preexisting_mac_hook_is_preserved(self):
        h = self.case('existing_mac')
        with self.assertRaisesRegex(RuntimeError, 'already configured'): h.run()
        self.assertEqual((h.home / 'job-started.sh').read_text(), 'pre-existing-hook')
    def test_preexisting_windows_auth_is_preserved(self):
        h = self.case('existing_windows')
        with self.assertRaisesRegex(RuntimeError, 'runner failed'): h.run()
        self.assert_stopped(h)
        self.assertEqual(json.loads(h.windows_state.read_text())['agentName'], 'pre-existing-windows')
    def test_hosted_prerequisite_failure_starts_no_process(self):
        h = self.case('assets_failure')
        with self.assertRaisesRegex(RuntimeError, 'prerequisite failed'): h.run()
        self.assertFalse(h.processes)
    def test_runtime_api_failure_stops_both_processes(self):
        h = self.case('runtime_api_failure')
        with self.assertRaisesRegex(RuntimeError, 'workflow API unavailable'): h.run()
        self.assert_stopped(h); self.assertFalse(h.registrations)
    def test_cancel_failure_does_not_skip_process_cleanup(self):
        h = self.case('cancel_failure')
        with self.assertRaisesRegex(RuntimeError, 'cancellation failed'): h.run()
        self.assert_stopped(h); self.assertFalse(h.registrations)
    def test_registration_api_failure_does_not_skip_process_cleanup(self):
        h = self.case('registration_api_failure')
        with self.assertRaisesRegex(RuntimeError, 'registration cleanup failed'): h.run()
        self.assert_stopped(h)
        self.assertTrue(h.registrations, 'API failure is surfaced rather than silently cleared')
    def test_delete_failure_surfaces_error_after_processes_stop(self):
        h = self.case('delete_failure')
        with self.assertRaisesRegex(RuntimeError, 'registration cleanup failed'): h.run()
        self.assert_stopped(h)
    def test_windows_cleanup_failure_surfaces_uncertainty(self):
        h = self.case('windows_cleanup_failure')
        with self.assertRaisesRegex(RuntimeError, 'Windows process cleanup failed'): h.run()
        self.assertTrue(all(p.returncode is not None for p in h.processes))
        self.assertTrue(h.windows_listener, 'remote cleanup failure must remain explicit')

if __name__ == '__main__': unittest.main(verbosity=2)
