#!/usr/bin/env python3
"""Authorize an existing successful build for the dev-only publisher."""
import json
import os
from pathlib import Path
import re
import subprocess

REPO = 'mrcobbert/Cobblify'
WORKFLOW = '.github/workflows/launcher-update.yml'


def validate_source(record, jobs, expected_commit):
    if not re.fullmatch(r'[0-9a-f]{40}', expected_commit):
        raise ValueError('Expected source commit must be a full SHA')
    if not (record['repository']['full_name'] == REPO
            and record['head_repository']['full_name'] == REPO
            and record['path'].split('@')[0] == WORKFLOW
            and record['event'] == 'workflow_dispatch'
            and record['actor']['login'] == 'mrcobbert'
            and record['triggering_actor']['login'] == 'mrcobbert'
            and record['status'] == 'completed' and record['conclusion'] == 'success'
            and record['head_sha'] == expected_commit
            and isinstance(record['run_number'], int) and record['run_number'] > 0):
        raise ValueError('Existing build identity does not match the authorized source')
    # A targeted rerun may hide the original platform jobs in the default API
    # view. Inspect every page/attempt, choosing the newest result per platform.
    for platform in ('macos', 'windows'):
        matches = [job for job in jobs if job['name'] == platform]
        latest = max(matches, key=lambda job: (job['run_attempt'], job['id'])) if matches else None
        if not latest or latest['conclusion'] != 'success' or latest['head_sha'] != expected_commit:
            raise ValueError(f'Existing {platform} build did not succeed')


def main():
    env = os.environ
    if env['GITHUB_REPOSITORY'] != REPO or env['GITHUB_ACTOR'] != 'mrcobbert':
        raise ValueError('Only the repository owner may publish a preview')
    version = env['RELEASE_VERSION']
    if not re.fullmatch(r'\d+\.\d+\.\d+', version):
        raise ValueError('Expected a plain base version')
    source_run = env.get('EXISTING_RUN', '')
    expected_commit = env.get('EXISTING_COMMIT', '')
    if source_run:
        if not re.fullmatch(r'[1-9][0-9]*', source_run) or not re.fullmatch(r'[0-9a-f]{40}', expected_commit):
            raise ValueError('Existing build requires a numeric run ID and exact commit')
        if env.get('LOCAL_BUILD') != 'false':
            raise ValueError('Existing build recovery cannot start local runners')
        record = json.loads(subprocess.check_output(['gh', 'api', f'repos/{REPO}/actions/runs/{source_run}']))
        pages = json.loads(subprocess.check_output(['gh', 'api', '--paginate', '--slurp',
            f'repos/{REPO}/actions/runs/{source_run}/jobs?filter=all&per_page=100']))
        validate_source(record, [job for page in pages for job in page['jobs']], expected_commit)
        number = record['run_number']
    else:
        if expected_commit:
            raise ValueError('Source commit requires an existing run')
        source_run, expected_commit, number = env['GITHUB_RUN_ID'], env['GITHUB_SHA'], int(env['GITHUB_RUN_NUMBER'])
    values = {'SOURCE_RUN': source_run, 'SOURCE_COMMIT': expected_commit,
              'BUILD_VERSION': f'{version}-dev.{number}'}
    with Path(env['GITHUB_ENV']).open('a') as stream:
        for key, value in values.items():
            stream.write(f'{key}={value}\n')


if __name__ == '__main__':
    main()
