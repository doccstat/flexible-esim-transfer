import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from native_build import module_graph

ROOT = Path(__file__).resolve().parents[1]

class ZygiskTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which('g++'), 'Host C++ compiler unavailable')
    def test_process_policy(self):
        with tempfile.TemporaryDirectory() as temp:
            binary = Path(temp) / 'policy'
            subprocess.run(['g++', '-std=c++20', '-Wall', '-Wextra', '-Werror',
                            '-I' + str(ROOT / 'zygisk/native'), str(ROOT / 'zygisk/tests/policy.cpp'), '-o', str(binary)], check=True)
            subprocess.run([str(binary)], check=True)

    @unittest.skipUnless(shutil.which('node'), 'Node unavailable')
    def test_webui(self):
        subprocess.run(['node', str(ROOT / 'zygisk/tests/webui.mjs')], check=True)

    def test_cpp_module_dependencies(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            first, second = root / 'a.ixx', root / 'b.ixx'
            first.write_text('export module app:part;\nimport dep;\n')
            second.write_text('export module dep;\n')
            self.assertEqual([name for name, _ in module_graph([first, second])], ['dep', 'app:part'])
            second.write_text('export module dep;\nimport app:part;\n')
            with self.assertRaises(ValueError): module_graph([first, second])
            second.write_text('export module app:part;\n')
            with self.assertRaises(ValueError): module_graph([first, second])
            with self.assertRaises(ValueError): module_graph([first])
        source = ROOT / 'zygisk/vendor/lsplant/lsplant/src/main/jni'
        modules = module_graph([*source.rglob('*.ixx'), *source.rglob('*.cxx')])
        self.assertIn('lsplant:class_linker', [name for name, _ in modules])

    def test_no_automatic_restart_or_private_state_deletion(self):
        for name in ('customize.sh', 'uninstall.sh', 'ctl.sh'):
            text = (ROOT / 'zygisk/module' / name).read_text()
            for forbidden in ('killall', 'pkill', 'setprop ctl.', 'rm -rf', 'pm uninstall'):
                self.assertNotIn(forbidden, text)
        self.assertFalse((ROOT / 'zygisk/module/service.sh').exists())

    def test_frontend_is_local_and_has_no_apk_requirement(self):
        html = (ROOT / 'zygisk/module/webroot/index.html').read_text()
        self.assertIn("connect-src 'none'", html)
        self.assertNotIn('https://', html)
        ctl = (ROOT / 'zygisk/module/ctl.sh').read_text()
        self.assertIn('payload.jar', ctl)
        self.assertNotIn('base.apk', ctl)

if __name__ == '__main__': unittest.main()
