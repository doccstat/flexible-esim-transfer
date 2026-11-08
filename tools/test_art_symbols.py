"""Exercise the actual loaded-library resolver against generated, non-proprietary ELFs."""
import lzma
from pathlib import Path
import shutil
import subprocess
import struct
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(sys.platform.startswith('linux') and all(shutil.which(tool) for tool in ('g++', 'cc', 'objcopy')),
                     'Linux host C/C++/ELF tools unavailable')
class ArtSymbolsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        cls.binary = cls.root / 'check'
        cls.fixture = cls.root / 'fixture.so'
        cls.run_command(['g++', '-shared', '-fPIC', str(ROOT / 'zygisk/tests/art_fixture.cpp'), '-o', str(cls.fixture)])
        debug = cls.root / 'debug.elf'
        cls.run_command(['objcopy', '--only-keep-debug', str(cls.fixture), str(debug)])
        cls.debug = debug.read_bytes()
        vendor = ROOT / 'zygisk/vendor/xz-embedded'
        objects = []
        for source in sorted(vendor.glob('*.c')):
            obj = cls.root / (source.stem + '.o')
            cls.run_command(['cc', '-std=c11', '-O2', '-DXZ_USE_CRC64', '-DXZ_DEC_DYNALLOC',
                             '-c', str(source), '-o', str(obj)])
            objects.append(str(obj))
        cls.run_command(['g++', '-std=c++20', '-Wall', '-Wextra', '-Werror', '-DXZ_USE_CRC64',
                         '-I' + str(ROOT / 'zygisk/native'), '-I' + str(vendor),
                         str(ROOT / 'zygisk/tests/art_symbols.cpp'), *objects, '-ldl', '-o', str(cls.binary)])

    @staticmethod
    def run_command(command):
        subprocess.run(command, check=True, timeout=30)

    def check(self, compressed=None, *, private=True, stripped=True):
        with tempfile.TemporaryDirectory(dir=self.root) as directory:
            library = Path(directory) / 'libart.so'
            shutil.copyfile(self.fixture, library)
            if stripped:
                self.run_command(['objcopy', '--strip-all', str(library)])
            if compressed is not None:
                data = Path(directory) / 'debug.xz'
                data.write_bytes(compressed)
                self.run_command(['objcopy', '--add-section', '.gnu_debugdata=' + str(data), str(library)])
            self.run_command([str(self.binary), str(library), '1' if private else '0'])

    def test_plain_symbols_and_absent_debugdata(self):
        self.check(stripped=False)
        self.check(private=False)

    def test_compressed_private_symbols_and_load_bias(self):
        for check in (lzma.CHECK_CRC32, lzma.CHECK_CRC64):
            with self.subTest(check=check):
                self.check(lzma.compress(self.debug, check=check))

    def test_packed_symbol_table(self):
        elf64 = self.debug[4] == 2
        header = struct.Struct('<16sHHIQQQIHHHHHH' if elf64 else '<16sHHIIIIIHHHHHH')
        section = struct.Struct('<IIQQQQIIQQ' if elf64 else '<IIIIIIIIII')
        fields = header.unpack_from(self.debug)
        packed = bytearray(self.debug)
        for i in range(fields[12]):
            at = fields[6] + i * fields[11]
            entry = list(section.unpack_from(self.debug, at))
            if entry[1] != 2:
                continue
            symbols = self.debug[entry[4]:entry[4] + entry[5]]
            packed += b'\0'
            entry[4] = len(packed)
            self.assertNotEqual(entry[4] % 8, 0)
            packed += symbols
            section.pack_into(packed, at, *entry)
        self.check(lzma.compress(packed))

    @unittest.skipUnless(shutil.which('xz'), 'XZ encoder unavailable')
    def test_multiple_xz_blocks(self):
        compressed = subprocess.check_output(['xz', '-c', '--threads=1', '--block-size=512', '--check=crc32'],
                                             input=self.debug, timeout=30)
        self.check(compressed)

    def test_corrupt_truncated_and_trailing_streams(self):
        compressed = lzma.compress(self.debug)
        corrupt = bytearray(compressed)
        corrupt[-12] ^= 1
        for data in (b'not xz', compressed[:-8], bytes(corrupt), compressed + b'junk', compressed + compressed):
            with self.subTest(length=len(data)):
                self.check(data, private=False)

    def test_malformed_elf_tables_are_rejected(self):
        elf64 = self.debug[4] == 2
        header = struct.Struct('<16sHHIQQQIHHHHHH' if elf64 else '<16sHHIIIIIHHHHHH')
        section = struct.Struct('<IIQQQQIIQQ' if elf64 else '<IIIIIIIIII')
        fields = list(header.unpack_from(self.debug))
        table = [section.unpack_from(self.debug, fields[6] + i * fields[11]) for i in range(fields[12])]
        symtab = next(i for i, entry in enumerate(table) if entry[1] == 2)
        cases = [b'', self.debug[:32]]
        for offset, value in ((0, 0), (4, 1 if elf64 else 2), (5, 2), (18, 0)):
            data = bytearray(self.debug)
            data[offset] = value
            cases.append(data)
        for field, value in ((1, 1), (6, len(self.debug) + 8), (6, fields[6] + 1), (12, 65535)):
            changed = list(fields)
            changed[field] = value
            data = bytearray(self.debug)
            header.pack_into(data, 0, *changed)
            cases.append(data)
        for field, value in ((4, len(self.debug) + 8), (5, table[symtab][5] + 1), (6, len(table)), (9, 0)):
            changed = list(table[symtab])
            changed[field] = value
            data = bytearray(self.debug)
            section.pack_into(data, fields[6] + symtab * fields[11], *changed)
            cases.append(data)
        strings = table[table[symtab][6]]
        data = bytearray(self.debug)
        data[strings[4]:strings[4] + strings[5]] = b'x' * strings[5]
        cases.append(data)
        for i, data in enumerate(cases):
            with self.subTest(case=i):
                self.check(lzma.compress(data), private=False)

    def test_decompression_limits(self):
        self.check(lzma.compress(self.debug, filters=[{'id': lzma.FILTER_LZMA2, 'dict_size': 64 * 1024 * 1024}]),
                   private=False)
        self.check(lzma.compress(b'\0' * (32 * 1024 * 1024 + 1), preset=0), private=False)


if __name__ == '__main__':
    unittest.main()
