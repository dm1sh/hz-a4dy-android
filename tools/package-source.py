#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Package only original project sources/docs/evidence. Never package builds, keys or APKs."""
import argparse
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--output', type=Path, default=root.parent / 'A4DY-Print-0.1.0-source.zip')
a = p.parse_args()
excluded = {'.git', '.gradle', '.kotlin', '.idea', '.cache', 'build', '__pycache__', 'node_modules', 'artifacts'}
files = []
for path in sorted(root.rglob('*')):
    relative = path.relative_to(root)
    if not path.is_file() or path.is_symlink() or any(part in excluded for part in relative.parts):
        continue
    if (path.name in {'local.properties', 'keystore.properties', 'signing.properties', 'credentials.json', 'actions-secrets.json'}
            or path.name.startswith('.env')
            or any('signing-backup' in part.lower() for part in relative.parts)
            or path.suffix.lower() in {'.apk', '.apks', '.aab', '.jks', '.keystore', '.p12', '.pfx', '.key', '.pem'}):
        continue
    files.append((path, relative))
a.output.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(a.output, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for path, relative in files:
        archive.write(path, Path('A4DY-Print-0.1.0-source') / relative)
with zipfile.ZipFile(a.output) as archive:
    assert archive.testzip() is None
print(f'{len(files)} files → {a.output} ({a.output.stat().st_size:,} bytes)')
