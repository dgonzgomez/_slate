#!/usr/bin/env python3
"""Inspect the would-be Git file list without printing secret values."""
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN = re.compile(r'(^|/)(?:\.tools|build|dremio_app|dremio_legacy|tests|test)(/|$)|(^|/)[^/]*(?:master|handover|handoff)[^/]*|\.(?:keystore|jks|pem|p12|key)$|(^|/)\.env(?:\.|$)|^scripts/test\.sh$', re.I)
SECRETS = [
    re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
    re.compile(rb'gh[pousr]_[A-Za-z0-9_]{20,}'),
    re.compile(rb'github_pat_[A-Za-z0-9_]{20,}'),
    re.compile(rb'https://addon\.debridio\.com/[^\s/]{40,}/manifest\.json'),
]

def audit():
    raw = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=ROOT)
    files = sorted(set(p for p in raw.decode().split('\0') if p))
    errors = []
    for name in files:
        path = ROOT / name
        if FORBIDDEN.search(name):
            errors.append(f'Private or generated path: {name}')
        if path.is_symlink():
            errors.append(f'Review symbolic link before publishing: {name}')
        elif path.is_file() and path.stat().st_size < 2_000_000:
            if any(pattern.search(path.read_bytes()) for pattern in SECRETS):
                errors.append(f'Possible credential in {name}; value hidden')
    if errors:
        raise SystemExit('\n'.join(errors))
    print(f'{len(files)} candidate repository files passed the privacy scan.')

if __name__ == '__main__':
    audit()
