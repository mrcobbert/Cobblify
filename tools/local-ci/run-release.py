#!/usr/bin/env python3
"""Dispatch one reviewed release commit, run exactly two ephemeral local jobs, stop.

First provision runner binaries at ~/.local/share/cobblify-ci/runner-macos and
Windows equivalent (see README). Never runs on pull requests or listens as a service.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import secrets
import signal
import subprocess
import sys
import time

REPO = 'mrcobbert/Cobblify'
ROOT = Path(__file__).resolve().parents[2]
HOME = Path.home() / '.local/share/cobblify-ci'


def command(args, **kwargs):
    return subprocess.run(args, check=True, text=True, **kwargs)


def gh(*args):
    return command(['gh', *args], capture_output=True).stdout


def api(path, *args):
    return json.loads(gh('api', path, *args))


def run_record(run):
    return api(f'repos/{REPO}/actions/runs/{run}')


def runner_ids():
    return api(f'repos/{REPO}/actions/runners')['runners']


def validate_run(record, commit, title):
    if not (record['head_sha'] == commit and record['event'] == 'workflow_dispatch'
            and record['actor']['login'] == 'mrcobbert' and record['display_title'] == title
            and record['path'].split('@')[0] == '.github/workflows/launcher-update.yml'
            and record['repository']['full_name'] == REPO):
        raise RuntimeError('Workflow identity mismatch')


def stop(signum, frame):
    raise KeyboardInterrupt()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--publish-dev', action='store_true')
    options = parser.parse_args()
    command(['gh', 'auth', 'switch', '--user', 'mrcobbert'], capture_output=True)
    branch = command(['git', 'branch', '--show-current'], cwd=ROOT, capture_output=True).stdout.strip()
    commit = command(['git', 'rev-parse', 'HEAD'], cwd=ROOT, capture_output=True).stdout.strip()
    if not branch or branch == 'main' or command(['git', 'status', '--porcelain'], cwd=ROOT, capture_output=True).stdout:
        raise RuntimeError('Use a clean release branch, never main')
    remote = command(['git', 'ls-remote', 'origin', f'refs/heads/{branch}'], cwd=ROOT, capture_output=True).stdout.split()
    if not remote or remote[0] != commit:
        raise RuntimeError('Push this exact commit before dispatch')
    version = next(line.split('=', 1)[1] for line in (ROOT / 'gradle.properties').read_text().splitlines() if line.startswith('mod_version='))
    nonce = secrets.token_hex(16)
    title = f'Launcher {version} local {nonce}'
    HOME.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(HOME, 0o700)
    # Only one local release controller may own the two persistent workspaces.
    import fcntl
    lock = (HOME / 'controller.lock').open('w')
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    started = time.monotonic()
    started_at = datetime.now(timezone.utc).isoformat()
    gh('workflow', 'run', 'launcher-update.yml', '--repo', REPO, '--ref', branch,
       '-f', f'version={version}', '-f', 'local_build=true', '-f', f'local_id={nonce}',
       '-f', f'publish_dev={str(options.publish_dev).lower()}')
    record = None
    for _ in range(60):
        records = api(f'repos/{REPO}/actions/workflows/launcher-update.yml/runs?event=workflow_dispatch&per_page=10')['workflow_runs']
        record = next((r for r in records if r['display_title'] == title), None)
        if record:
            break
        time.sleep(2)
    if not record:
        raise RuntimeError('Dispatched run not found; no runners were started')
    validate_run(record, commit, title)
    run = str(record['id'])
    print(f"Run {run}: {record['html_url']}", flush=True)
    logdir = HOME / 'runs' / run
    logdir.mkdir(mode=0o700, parents=True)
    processes, logs = [], []
    names = {f'cobblify-macos-{run}', f'cobblify-windows-{run}'}
    finished = False
    try:
        # Wait for the hosted authorization/icon job before exposing any local listener.
        while True:
            current = run_record(run)
            validate_run(current, commit, title)
            jobs = api(f'repos/{REPO}/actions/runs/{run}/jobs')['jobs']
            assets = next((j for j in jobs if j['name'] == 'local-assets'), None)
            if assets and assets['status'] == 'completed':
                if assets['conclusion'] != 'success':
                    raise RuntimeError('Local authorization/icon prerequisite failed')
                break
            if time.monotonic() - started > 600:
                raise RuntimeError('Hosted prerequisite timeout')
            time.sleep(5)
        # Verify all queued local jobs belong to the expected workflow and exact platform pair.
        jobs = api(f'repos/{REPO}/actions/runs/{run}/jobs')['jobs']
        if {j['name'] for j in jobs} - {'local-assets', 'macos', 'windows'}:
            raise RuntimeError('Unexpected job in local release')
        env = os.environ.copy()
        env.update(COBBLIFY_LOCAL_CI='1', COBBLIFY_CI_CACHE=str(HOME / 'cache'))
        hook = HOME / 'job-started.sh'
        expected = {'GITHUB_RUN_ID': run, 'GITHUB_SHA': commit, 'GITHUB_REPOSITORY': REPO,
                    'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_ACTOR': 'mrcobbert', 'GITHUB_JOB': 'macos'}
        hook.write_text('#!/bin/bash\nset -eu\n' + ''.join(f'test "${{{key}:-}}" = "{value}" || exit 1\n' for key, value in expected.items()))
        hook.chmod(0o700)
        env['ACTIONS_RUNNER_HOOK_JOB_STARTED'] = str(hook)
        runner = HOME / 'runner-macos'
        if (runner / '.runner').exists():
            raise RuntimeError('Mac runner already configured; inspect before reuse')
        token = api(f'repos/{REPO}/actions/runners/registration-token', '--method', 'POST')['token']
        config_env = dict(env, RUNNER_INPUT_TOKEN=token)
        command([str(runner / 'config.sh'), '--unattended', '--ephemeral', '--no-default-labels',
                 '--url', f'https://github.com/{REPO}', '--name', f'cobblify-macos-{run}',
                 '--labels', f'cobblify-{nonce}-macos', '--work', str(HOME / 'work')],
                cwd=runner, env=config_env, capture_output=True)
        del token, config_env
        command(['scp', str(ROOT / 'tools/local-ci/start-windows.ps1'), 'win:C:/Users/agent/.local/share/cobblify-ci/start-windows.ps1'], capture_output=True)
        for platform in ['macos', 'windows']:
            log = (logdir / f'{platform}.log').open('w')
            os.chmod(log.name, 0o600)
            logs.append(log)
            if platform == 'macos':
                proc = subprocess.Popen([str(runner / 'run.sh')], cwd=runner, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            else:
                token = api(f'repos/{REPO}/actions/runners/registration-token', '--method', 'POST')['token']
                remote = ('powershell -NoProfile -ExecutionPolicy Bypass -File C:\\Users\\agent\\.local\\share\\cobblify-ci\\start-windows.ps1 '
                          f'-Label cobblify-{nonce}-windows -Run {run} -Commit {commit}')
                proc = subprocess.Popen(['ssh', 'win', remote], stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, text=True, start_new_session=True)
                proc.stdin.write(token + '\n')
                proc.stdin.close()
                del token
            processes.append(proc)
        print('One-shot local runners started; private logs stay outside the repository.', flush=True)
        last_state = None
        while True:
            record = run_record(run)
            jobs = api(f'repos/{REPO}/actions/runs/{run}/jobs')['jobs']
            state = ', '.join(f"{j['name']}={j.get('conclusion') or j['status']}" for j in jobs)
            if state != last_state:
                print(state, flush=True)
                last_state = state
            if record['status'] == 'completed':
                finished = True
                break
            if time.monotonic() - started > 3600:
                raise RuntimeError('Local release exceeded one hour')
            for proc in processes:
                if proc.poll() is not None and proc.returncode != 0:
                    raise RuntimeError('A local runner failed; inspect private logs')
            time.sleep(10)
        report = {'run': run, 'url': record['html_url'], 'commit': commit, 'startedAt': started_at,
                  'elapsedSeconds': round(time.monotonic() - started, 1), 'conclusion': record['conclusion'],
                  'jobs': [{k: j.get(k) for k in ['name', 'started_at', 'completed_at', 'conclusion']} for j in jobs]}
        (logdir / 'timing.json').write_text(json.dumps(report, indent=2))
        print(json.dumps(report, indent=2), flush=True)
        if record['conclusion'] != 'success':
            raise RuntimeError('Release workflow failed')
    finally:
        cleanup_errors = []
        if not finished:
            try:
                gh('run', 'cancel', run, '--repo', REPO)
            except Exception as error:
                cleanup_errors.append(type(error).__name__ + ': cancellation failed')
        # Every cleanup attempt is independent: an API failure must never leave a listener running.
        for proc in processes:
            try:
                proc.wait(timeout=20)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(proc.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
        try:
            script = ("$root='C:\\Users\\agent\\.local\\share\\cobblify-ci\\runner-windows'; "
                      f"if ((Test-Path \"$root\\.runner\") -and ((Get-Content \"$root\\.runner\" | ConvertFrom-Json).agentName -eq 'cobblify-windows-{run}')) {{ "
                      "Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'Runner.Listener.exe' -and $_.ExecutablePath -eq \"$root\\bin\\Runner.Listener.exe\" } | ForEach-Object { & taskkill /PID $_.ProcessId /T /F | Out-Null }; }")
            command(['ssh', 'win', script], capture_output=True, timeout=30)
        except Exception as error:
            cleanup_errors.append(type(error).__name__ + ': Windows process cleanup failed')
        for proc in processes:
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(proc.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
        try:
            for item in runner_ids():
                if item['name'] in names:
                    gh('api', f"repos/{REPO}/actions/runners/{item['id']}", '--method', 'DELETE')
            if any(item['name'] in names for item in runner_ids()):
                raise RuntimeError('runner still registered')
            # Tokens are spent and registrations gone. Remove only runner authentication state.
            state_file = HOME / 'runner-macos' / '.runner'
            if state_file.exists() and json.loads(state_file.read_text())['agentName'] == f'cobblify-macos-{run}':
                for filename in ['.runner', '.credentials', '.credentials_rsaparams', '.credentials_migrated']:
                    (HOME / 'runner-macos' / filename).unlink(missing_ok=True)
            command(['ssh', 'win', "$root='C:\\Users\\agent\\.local\\share\\cobblify-ci\\runner-windows'; "+
                     f"if ((Test-Path \"$root\\.runner\") -and ((Get-Content \"$root\\.runner\" | ConvertFrom-Json).agentName -eq 'cobblify-windows-{run}')) {{ " +
                     "'.runner','.credentials','.credentials_rsaparams','.credentials_migrated' | ForEach-Object { $p=Join-Path $root $_; if(Test-Path $p){Remove-Item -LiteralPath $p} } }"], capture_output=True, timeout=30)
        except Exception as error:
            cleanup_errors.append(type(error).__name__ + ': registration cleanup failed')
        for log in logs:
            log.close()
        if cleanup_errors:
            raise RuntimeError('; '.join(cleanup_errors))
        print('Both local runners stopped and deregistered.', flush=True)


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, stop)
    main()
