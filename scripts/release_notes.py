#!/usr/bin/env python3
"""Read the current version's feature bullets without changing the README."""
import argparse
from pathlib import Path
import re
from prepare_build import metadata

ROOT = Path(__file__).resolve().parents[1]

def notes(version, source=None):
    text = source if source is not None else (ROOT / 'CHANGELOG.md').read_text()
    match = re.search(r'^### ' + re.escape(version) + r'\s*\n(.*?)(?=^#{1,3} |\Z)', text, re.M | re.S)
    if not match or not re.search(r'^- \S', match.group(1), re.M):
        raise ValueError(f'Add feature bullets under ### {version} in CHANGELOG.md before releasing')
    return match.group(1).strip() + '\n'

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--version', action='store_true', help='Print only the validated version')
    args = parser.parse_args()
    version, _, _ = metadata()
    if args.version:
        print(version)
    else:
        print(notes(version), end='')
