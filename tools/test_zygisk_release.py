import hashlib
import io
import json
from pathlib import Path
import stat
import struct
import tempfile
import unittest
import zipfile

import release


class ZygiskReleaseTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name) / release.ZIP_NAME
        self.tag = '8-0.5.0'
        self.files = {name: b'fixture' for name in (
            'customize.sh', 'ctl.sh', 'uninstall.sh', 'webroot/index.html', 'webroot/app.js',
            'webroot/bridge.mjs', 'webroot/style.css', 'webroot/strings.mjs', 'licenses/project.txt', 'licenses/LSPlant.txt',
            'licenses/Dobby.txt', 'licenses/DexBuilder.txt', 'licenses/parallel-hashmap.txt',
            'licenses/libxposed-api.txt', 'licenses/GPL-3.0.txt', 'licenses/VENDORED.md', 'licenses/source.txt')}
        self.files['module.prop'] = b'id=flexible_esim_transfer\nversionCode=8\nversion=0.5.0\n'
        self.files['payload.dex'] = b'dex\n039\0' + b'fixture-not-executable'
        self.payload()
        for abi, (bits, machine) in release.ABIS.items():
            elf = bytearray(64)
            elf[:6] = b'\x7fELF' + bytes([bits, 1])
            struct.pack_into('<HH', elf, 16, 3, machine)
            self.files['zygisk/' + abi + '.so'] = bytes(elf)

    def payload(self, dex=None, tag=None):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as archive:
            archive.writestr('classes.dex', self.files['payload.dex'] if dex is None else dex)
            archive.writestr('version.json', json.dumps(dict(package=release.PACKAGE, code='8', name='0.5.0', tag=tag or self.tag)))
            archive.writestr('diagnostic-rules.tsv', b'fixture')
            archive.writestr('diagnostic-reader.sh', b'fixture')
        self.files['payload.jar'] = stream.getvalue()

    def write(self, *, mode=None, corrupt_manifest=False):
        manifest = {name: hashlib.sha256(value).hexdigest() for name, value in self.files.items()}
        if corrupt_manifest: manifest['payload.dex'] = '0' * 64
        files = dict(self.files, **{'files.sha256.json': json.dumps(manifest).encode()})
        with zipfile.ZipFile(self.path, 'w') as archive:
            for name, value in files.items():
                entry = zipfile.ZipInfo(name)
                entry.external_attr = (mode if name == 'ctl.sh' and mode else stat.S_IFREG | 0o644) << 16
                archive.writestr(entry, value)
        checksum = self.path.with_suffix('.zip.sha256')
        checksum.write_text(hashlib.sha256(self.path.read_bytes()).hexdigest() + '  ' + release.ZIP_NAME + '\n')
        return checksum

    def test_matching_pair_metadata_and_checksum(self):
        checksum = self.write()
        self.assertEqual(release.verify_zygisk(self.path, self.tag, checksum), hashlib.sha256(self.path.read_bytes()).hexdigest())

    def test_bad_tag_checksum_and_version(self):
        checksum = self.write()
        for tag in ('0.5.0', '8-0.5.1', '9-0.5.0'):
            with self.assertRaises(ValueError): release.verify_zygisk(self.path, tag)
        checksum.write_text('0' * 64 + '  ' + release.ZIP_NAME + '\n')
        with self.assertRaises(ValueError): release.verify_zygisk(self.path, self.tag, checksum)

    def test_missing_abi_and_wrong_native_machine(self):
        name = 'zygisk/arm64-v8a.so'
        original = self.files.pop(name); self.write()
        with self.assertRaises(ValueError): release.verify_zygisk(self.path, self.tag)
        self.files[name] = self.files['zygisk/x86.so']; self.write()
        with self.assertRaises(ValueError): release.verify_zygisk(self.path, self.tag)
        self.files[name] = original

    def test_hash_mismatch_is_rejected(self):
        self.write(corrupt_manifest=True)
        with self.assertRaises(ValueError): release.verify_zygisk(self.path, self.tag)

    def test_payload_mismatch_and_stale_version_are_rejected(self):
        self.payload(dex=b'dex\n039\0other'); self.write()
        with self.assertRaises(ValueError): release.verify_zygisk(self.path, self.tag)
        self.payload(tag='7-0.4.1'); self.write()
        with self.assertRaises(ValueError): release.verify_zygisk(self.path, self.tag)

    def test_no_traversal_symlinks_or_apk_dependency(self):
        for extra in ('../outside', '/absolute', 'companion.apk'):
            self.files[extra] = b'bad'; self.write()
            with self.assertRaises(ValueError): release.verify_zygisk(self.path, self.tag)
            del self.files[extra]
        self.write(mode=stat.S_IFLNK | 0o777)
        with self.assertRaises(ValueError): release.verify_zygisk(self.path, self.tag)

    def test_duplicate_module_property_is_rejected(self):
        self.files['module.prop'] += b'versionCode=8\n'; self.write()
        with self.assertRaises(ValueError): release.verify_zygisk(self.path, self.tag)


if __name__ == '__main__': unittest.main()
