"""Behavioral regression tests; all temporary data stays on D via TMPDIR."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tarfile
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / 'harness/scripts/runtime-lifecycle.py'


def eventually(predicate, timeout=5):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if predicate():
            return
        time.sleep(.02)
    raise AssertionError('condition did not become true')


class RecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='harness-recovery-')
        self.addCleanup(self.temp.cleanup)
        self.dir = Path(self.temp.name)

    def test_install_lock_is_exclusive_and_released_after_sigkill(self):
        ready = self.dir / 'ready'
        cmd = [sys.executable, str(HELPER), 'lock', str(self.dir / 'install.lock')]
        owner = subprocess.Popen(cmd + [sys.executable, '-c',
            'import pathlib,time,sys; pathlib.Path(sys.argv[1]).touch(); time.sleep(30)', str(ready)])
        self.addCleanup(lambda: owner.poll() is None and owner.kill())
        eventually(ready.exists)
        rejected = subprocess.run(cmd + ['true'], capture_output=True)
        self.assertNotEqual(rejected.returncode, 0, 'concurrent install was allowed')
        owner.kill()
        owner.wait()
        recovered = subprocess.run(cmd + ['true'], capture_output=True)
        self.assertEqual(recovered.returncode, 0, recovered.stderr.decode())

    def archive(self, broken=False):
        source = self.dir / ('broken' if broken else 'complete')
        (source / 'bin').mkdir(parents=True)
        node = source / 'bin/node'
        node.write_text('#!/bin/sh\nif [ "$1" = --version ]; then echo v1.2.3; else echo 10.0.0; fi\n')
        node.chmod(0o700)
        if not broken:
            (source / 'lib/node_modules/npm/bin').mkdir(parents=True)
            (source / 'lib/node_modules/npm/bin/npm-cli.js').write_text('// fixture\n')
        archive = self.dir / (source.name + '.tar.xz')
        with tarfile.open(archive, 'w:xz') as tar:
            tar.add(source, arcname='node-v1.2.3-linux-arm64')
        return archive

    def install(self, archive):
        return subprocess.run(['bash', str(ROOT / 'harness/scripts/install-node.sh'),
            str(archive), str(self.dir / 'node-1.2.3'), '1.2.3', hashlib.sha256(archive.read_bytes()).hexdigest()], capture_output=True)

    def test_partial_old_node_is_replaced_only_by_complete_validated_runtime(self):
        target = self.dir / 'node-1.2.3'
        (target / 'bin').mkdir(parents=True)
        (target / 'bin/node').write_text('partial')
        rejected = self.install(self.archive(broken=True))
        self.assertNotEqual(rejected.returncode, 0)
        self.assertFalse((target / '.operit-complete').exists())
        installed = self.install(self.archive())
        self.assertEqual(installed.returncode, 0, installed.stderr.decode())
        self.assertTrue((target / 'lib/node_modules/npm/bin/npm-cli.js').is_file())
        self.assertTrue((target / '.operit-complete').is_file())

    def test_failed_extraction_does_not_publish_and_retry_succeeds(self):
        archive = self.archive()
        raw = archive.read_bytes()
        archive.write_bytes(raw[:len(raw)//2])
        rejected = self.install(archive)
        self.assertNotEqual(rejected.returncode, 0)
        self.assertFalse((self.dir / 'node-1.2.3').exists())
        archive.write_bytes(raw)
        installed = self.install(archive)
        self.assertEqual(installed.returncode, 0, installed.stderr.decode())

    def test_sigkill_after_extraction_leaves_no_published_node_and_retry_recovers(self):
        import shutil
        archive = self.archive()
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        target = self.dir / 'node-1.2.3'
        wrappers = self.dir / 'wrappers'
        wrappers.mkdir()
        ready = self.dir / 'extracted'
        tar = wrappers / 'tar'
        # The OS command boundary blocks after real extraction, before validation
        # or publish, so SIGKILL exercises the exact dangerous partial-runtime gap.
        tar.write_text(f'#!/bin/sh\n"{shutil.which("tar")}" "$@" || exit $?\ntouch "{ready}"\nexec sleep 30\n')
        tar.chmod(0o700)
        proc = subprocess.Popen(['bash', str(ROOT / 'harness/scripts/install-node.sh'),
            str(archive), str(target), '1.2.3', digest], start_new_session=True,
            env=dict(os.environ, PATH=str(wrappers) + os.pathsep + os.environ['PATH']))
        self.addCleanup(lambda: proc.poll() is None and os.killpg(proc.pid, signal.SIGKILL))
        eventually(ready.exists)
        os.killpg(proc.pid, signal.SIGKILL)
        proc.wait()
        self.assertFalse(target.exists(), 'SIGKILL exposed a partial runtime')
        self.assertTrue(list(self.dir.glob('node-1.2.3.staging.*')))
        installed = self.install(archive)
        self.assertEqual(installed.returncode, 0, installed.stderr.decode())
        self.assertFalse(list(self.dir.glob('node-1.2.3.staging.*')))

    def test_wamr_android_cmake_configures_with_shared_optimization_helper(self):
        import shutil
        cmake = os.environ.get('CMAKE', '/Volumes/D盘/软件数据/Android/sdk/cmake/3.22.1/bin/cmake')
        if not Path(cmake).is_file():
            self.skipTest('set CMAKE to installed cmake')
        cpp = self.dir / 'app/src/main/cpp'
        shutil.copytree(ROOT / 'app/src/main/cpp', cpp)
        (self.dir / 'cmake').mkdir()
        wamr = self.dir / 'wamr'
        (wamr / 'build-scripts').mkdir(parents=True)
        (wamr / 'core/iwasm/include').mkdir(parents=True)
        (wamr / 'dummy.c').write_text('int fixture;\n')
        (wamr / 'build-scripts/runtime_lib.cmake').write_text(f'set(WAMR_RUNTIME_LIB_SOURCE "{wamr}/dummy.c")\n')
        (self.dir / 'cmake/operit_git_source.cmake').write_text(
            'function(operit_prepare_git_source src build)\n'
            f'set(${{src}} "{wamr}" PARENT_SCOPE)\nset(${{build}} "{wamr}/build" PARENT_SCOPE)\nendfunction()\n')
        result = subprocess.run([cmake, '-S', str(cpp), '-B', str(self.dir / 'build'),
            '-DANDROID=TRUE', '-DANDROID_ABI=arm64-v8a', '-Dlog-lib=m'], capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr.decode())


if __name__ == '__main__':
    unittest.main()
