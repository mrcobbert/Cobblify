#!/usr/bin/env python3
"""Bind compiled icon outputs to their exact inputs and source commit."""
import hashlib
import json
import os
from pathlib import Path
import sys


def digest_tree(root):
    root = Path(root)
    files = sorted(p for p in root.rglob('*') if p.is_file())
    if not files or any(p.is_symlink() for p in root.rglob('*')):
        raise ValueError('icon must be a nonempty regular tree')
    return {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in files}


def manifest(source, output):
    return {'commit': os.environ['GITHUB_SHA'], 'source': digest_tree(source),
            'outputs': digest_tree(output)}


if __name__ == '__main__':
    operation, source, output = sys.argv[1:]
    output = Path(output)
    record = output / 'provenance.json'
    if operation == 'record':
        if record.exists():
            raise SystemExit('provenance already exists')
        record.write_text(json.dumps(manifest(source, output), sort_keys=True))
    elif operation == 'verify':
        expected = json.loads(record.read_text())
        actual_outputs = digest_tree(output)
        actual_outputs.pop('provenance.json')
        if (expected['commit'] != os.environ['GITHUB_SHA'] or
                expected['source'] != digest_tree(source) or expected['outputs'] != actual_outputs):
            raise SystemExit('compiled icon provenance mismatch')
    else:
        raise SystemExit('unknown operation')
