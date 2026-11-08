#!/usr/bin/env python3
"""Build the standalone ZIP from pinned source with an existing SDK/NDK/JDK. No installs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile
from release import ROOT, ZIP_NAME, metadata, verify_zygisk
from native_build import native_build

ABIS = ('arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86')


def run(command):
    subprocess.run(list(map(str, command)), check=True)


def build(args):
    version = metadata()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    sdk = args.sdk.resolve()
    android = sdk / 'platforms/android-36/android.jar'
    java = args.java.resolve() / 'bin'
    suffix = '.exe' if os.name == 'nt' else ''
    tools = sdk / 'build-tools/35.0.0'
    source = ROOT / 'zygisk'
    api = args.api.resolve()
    if api.suffix == '.aar':
        with zipfile.ZipFile(api) as archive:
            api = out / 'libxposed-api.jar'
            api.write_bytes(archive.read('classes.jar'))
    # AGP compiles against Android's Java API, not the host JDK's larger library.
    jar = out / 'classes.jar'
    with zipfile.ZipFile(source / 'build/outputs/aar/zygisk-release.aar') as library:
        jar.write_bytes(library.read('classes.jar'))
    constants = subprocess.check_output([str(java / ('javap' + suffix)), '-constants', '-classpath', str(jar),
        'com.lixingchi.flexibleesimtransfer.standalone.BuildConfig'], text=True)
    if (f'MODULE_CODE = {version["code"]};' not in constants
            or f'MODULE_NAME = "{version["name"]}";' not in constants):
        raise ValueError('Rebuild :zygisk:assembleRelease for the declared version')
    dex = out / 'dex'; dex.mkdir()
    run([java / ('java' + suffix), '-cp', tools / 'lib/d8.jar', 'com.android.tools.r8.D8',
         '--min-api', '33', '--lib', android, '--output', dex, jar, api])
    if sorted(p.name for p in dex.iterdir()) != ['classes.dex']:
        raise ValueError('Expected one bounded payload dex')
    stage = out / 'module'; shutil.copytree(source / 'module', stage)
    shutil.copyfile(dex / 'classes.dex', stage / 'payload.dex')
    with zipfile.ZipFile(stage / 'payload.jar', 'w', zipfile.ZIP_DEFLATED) as payload:
        payload.write(dex / 'classes.dex', 'classes.dex')
        payload.writestr('version.json', json.dumps(version, sort_keys=True) + '\n')
        for name in ('diagnostic-rules.tsv', 'diagnostic-reader.sh'):
            payload.write(ROOT / 'app/src/main/resources' / name, name)
    (stage / 'module.prop').write_text(
        'id=flexible_esim_transfer\nname=Flexible eSIM Transfer\n'
        f'version={version["name"]}\nversionCode={version["code"]}\n'
        'author=doccstat\ndescription=Standalone Zygisk eUICC routing with private WebUI diagnostics.\n', encoding='utf-8')
    (stage / 'zygisk').mkdir()
    for abi in ABIS:
        native = out / ('native-' + abi)
        library = native_build(args, source, native, abi)
        shutil.copyfile(library, stage / 'zygisk' / (abi + '.so'))
    licenses = stage / 'licenses'; licenses.mkdir()
    shutil.copyfile(ROOT / 'LICENSE', licenses / 'project.txt')
    shutil.copyfile(source / 'libxposed-api-LICENSE.txt', licenses / 'libxposed-api.txt')
    shutil.copyfile(source / 'GPL-3.0.txt', licenses / 'GPL-3.0.txt')
    (licenses / 'source.txt').write_text('Corresponding source and build/relink instructions:\n'
        'https://github.com/doccstat/flexible-esim-transfer/tree/' + version['tag'] + '\n', encoding='utf-8')
    for name, path in [('LSPlant', 'lsplant/LICENSE'), ('Dobby', 'dobby/LICENSE'), ('XZ-Embedded', 'xz-embedded/COPYING'),
                       ('DexBuilder', 'lsplant/lsplant/src/main/jni/external/dex_builder/LICENSE'),
                       ('parallel-hashmap', 'lsplant/lsplant/src/main/jni/external/dex_builder/external/parallel_hashmap/LICENSE')]:
        shutil.copyfile(source / 'vendor' / path, licenses / (name + '.txt'))
    shutil.copyfile(source / 'VENDORED.md', licenses / 'VENDORED.md')
    manifest = {p.relative_to(stage).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                for p in sorted(stage.rglob('*')) if p.is_file()}
    (stage / 'files.sha256.json').write_text(json.dumps(manifest, sort_keys=True, indent=2) + '\n', encoding='utf-8')
    archive = out / ZIP_NAME
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as package:
        for file in sorted(stage.rglob('*')):
            if not file.is_file(): continue
            info = zipfile.ZipInfo(file.relative_to(stage).as_posix(), (2026, 1, 1, 0, 0, 0))
            info.external_attr = (0o100755 if file.suffix == '.sh' else 0o100644) << 16
            package.writestr(info, file.read_bytes(), compress_type=zipfile.ZIP_DEFLATED)
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    archive.with_suffix('.zip.sha256').write_text(digest + '  ' + archive.name + '\n', encoding='ascii')
    verify_zygisk(archive, version['tag'], archive.with_suffix('.zip.sha256'))
    print(archive)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('sdk', 'ndk', 'java', 'api', 'cmake', 'ninja', 'out'):
        parser.add_argument('--' + name, required=True, type=Path)
    build(parser.parse_args())


if __name__ == '__main__':
    main()
