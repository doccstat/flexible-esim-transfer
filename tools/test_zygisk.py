import shutil
import subprocess
import sys
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

    @unittest.skipUnless(sys.platform.startswith('linux') and shutil.which('g++'), 'Linux C++ tools unavailable')
    def test_settings_channel(self):
        with tempfile.TemporaryDirectory() as temp:
            binary = Path(temp) / 'settings_channel'
            subprocess.run(['g++', '-std=c++20', '-Wall', '-Wextra', '-Werror', '-pthread',
                            '-I' + str(ROOT / 'zygisk/native'), str(ROOT / 'zygisk/tests/settings_channel.cpp'),
                            '-o', str(binary)], check=True, timeout=30)
            subprocess.run([str(binary)], check=True, timeout=10)

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

    def test_bootstrap_logs_follow_uid_specialization(self):
        source = (ROOT / 'zygisk/native/module.cpp').read_text()
        pre, post = source.split('void postAppSpecialize(', 1)
        self.assertNotIn('bootstrap: SELECTED', pre)
        self.assertIn('bootstrap: SELECTED', post)
        self.assertLess(post.index('if (failure)'), post.index('bool ok = bootstrap('))
        self.assertIn('bootstrap: FAILED:%s', post)

    def test_bootstrap_hooks_attachment_not_its_deoptimized_caller(self):
        source = (ROOT / 'zygisk/java/com/lixingchi/flexibleesimtransfer/ZygiskEntry.java').read_text()
        self.assertIn('NativeHooks.install(Application.class.getDeclaredMethod("attach", Context.class)', source)
        self.assertNotIn('NativeHooks.install(Instrumentation.class', source)
        self.assertIn('caller.getName().equals("newApplication") && !NativeHooks.deoptimize(caller)', source)
        self.assertIn('caller.getName().startsWith("makeApplication") && !NativeHooks.deoptimize(caller)', source)

    def test_frontend_is_local_and_has_no_apk_requirement(self):
        html = (ROOT / 'zygisk/module/webroot/index.html').read_text()
        self.assertIn("connect-src 'none'", html)
        self.assertNotIn('https://', html)
        ctl = (ROOT / 'zygisk/module/ctl.sh').read_text()
        self.assertIn('payload.jar', ctl)
        self.assertNotIn('base.apk', ctl)

if __name__ == '__main__': unittest.main()
