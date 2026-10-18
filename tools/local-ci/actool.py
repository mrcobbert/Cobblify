#!/usr/bin/env python3
"""Tauri adapter for icon outputs compiled by Xcode in this same workflow run.

This is not an icon compiler. All inputs/outputs must match the hosted compiler's
provenance; a changed icon or unexpected invocation fails closed.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys

args = sys.argv[1:]
assets = Path(os.environ['COBBLIFY_ICON_ASSETS'])
record = json.loads((assets / 'provenance.json').read_text())
if record['commit'] != os.environ['GITHUB_SHA']:
    raise SystemExit('wrong icon source commit')
for relative, digest in record['outputs'].items():
    if hashlib.sha256((assets / relative).read_bytes()).hexdigest() != digest:
        raise SystemExit('compiled icon output changed')
if '--version' in args:
    print('actool version 26.3 (verified precompiled icon adapter)')
    raise SystemExit(0)
source = Path(args[0])
actual = {str(p.relative_to(source)): hashlib.sha256(p.read_bytes()).hexdigest()
          for p in sorted(source.rglob('*')) if p.is_file()}
if actual != record['source'] or any(p.is_symlink() for p in source.rglob('*')):
    raise SystemExit('Tauri requested a different icon')
if args[args.index('--app-icon') + 1] != 'Icon':
    raise SystemExit('unexpected app icon name')
output = Path(args[args.index('--compile') + 1])
output.mkdir(parents=True, exist_ok=True)
for relative in record['outputs']:
    src = assets / relative
    dest = output / relative
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(src, dest)
plist = Path(args[args.index('--output-partial-info-plist') + 1])
if plist != output / 'assetcatalog_generated_info.plist':
    shutil.copyfile(assets / 'assetcatalog_generated_info.plist', plist)
print('Using exact same-run Xcode icon outputs')
