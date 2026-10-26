#!/usr/bin/env python3
"""Validated release metadata shared by the manifest, app updater and CI."""
import os
import pathlib
import re
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]

def metadata():
    values = dict(line.strip().split('=', 1) for line in (ROOT / 'release.properties').read_text().splitlines() if line.strip() and not line.startswith('#'))
    version = values['version.name']
    code = values['version.code']
    repo = os.environ.get('SLATE_REPOSITORY', values['update.repository'])
    if not re.fullmatch(r'\d+\.\d+\.\d+', version) or not code.isdigit() or not 1 <= int(code) <= 2100000000:
        raise ValueError('Use a stable X.Y.Z version and an increasing positive Android version code')
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo):
        raise ValueError('Invalid update repository')
    return version, code, repo

if __name__ == '__main__':
    version, code, repo = metadata()
    ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')
    manifest = ET.parse(ROOT / 'app/AndroidManifest.xml')
    manifest.getroot().set('{http://schemas.android.com/apk/res/android}versionCode', code)
    manifest.getroot().set('{http://schemas.android.com/apk/res/android}versionName', version)
    stage = ROOT / 'build/staging'
    stage.mkdir(parents=True, exist_ok=True)
    manifest.write(stage / 'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
    generated = stage / 'generated/dev/slate'
    generated.mkdir(parents=True, exist_ok=True)
    (generated / 'BuildConfig.java').write_text('package dev.slate;\npublic final class BuildConfig {\n'
        f' public static final String VERSION_NAME="{version}", UPDATE_REPOSITORY="{repo}";\n'
        f' public static final int VERSION_CODE={code};\n private BuildConfig() {{}}\n}}\n')
