import importlib.util
import json
import os
from pathlib import Path
import shutil
import signal
import select
import subprocess
import sys
import tempfile
import time
import unittest

HELPER = Path(__file__).resolve().parents[1] / 'scripts/runtime-lifecycle.py'


def eventually(predicate, timeout=8):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if predicate():
            return
        time.sleep(.02)
    raise AssertionError('condition did not become true')


@unittest.skipUnless(sys.platform == 'linux', 'actual Linux /proc and pidfd required')
class HostTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='host-lifecycle-')
        self.addCleanup(self.temp.cleanup)
        self.runtime = Path(self.temp.name)
        # Python stands in only for the external DSH executable. The production
        # supervisor, kernel flock, /proc identity and pidfd signals run unchanged.
        self.node = Path(sys.executable).resolve()
        self.engine = self.runtime / 'engine'
        script = self.engine / 'node_modules/.bin/dsh'
        script.parent.mkdir(parents=True)
        script.write_text('import time\nprint("host-ready", flush=True)\ntime.sleep(60)\n')
        spec = importlib.util.spec_from_file_location('lifecycle', HELPER)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)

    def call(self, action):
        return subprocess.run([sys.executable, str(HELPER), action, str(self.runtime),
            str(self.node), str(self.engine)], capture_output=True, timeout=15)

    def start(self):
        owner = subprocess.Popen([sys.executable, str(HELPER), 'start', str(self.runtime),
            str(self.node), str(self.engine)], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        def cleanup():
            if owner.poll() is None:
                self.call('stop')
                try:
                    owner.wait(timeout=12)
                except subprocess.TimeoutExpired:
                    owner.kill()
                    owner.wait()
            owner.stdout.close()
            owner.stderr.close()
        self.addCleanup(cleanup)
        def published():
            try:
                return json.loads((self.runtime / 'host.pid').read_text()).get('owner', {}).get('pid') == owner.pid
            except (FileNotFoundError, ValueError, AttributeError):
                return False
        eventually(published)
        return owner

    def loader_fixture(self, executable=True, mapped_image=None):
        # PRoot runs a loader as /proc/PID/exe while mapping the requested ELF.
        # Model that kernel representation with a real executable file mapping,
        # keeping the production ownership validation and pidfd signaling real.
        expected = self.runtime / 'mapped node image (loader)'
        shutil.copyfile(self.node, expected)
        image = mapped_image or expected
        ready = self.runtime / 'mapping-ready'
        script = self.engine / 'node_modules/.bin/dsh'
        script.write_text(
            'import mmap,pathlib,sys,time\n'
            'with open(sys.argv[1], "rb") as image:\n'
            f'    mapping = mmap.mmap(image.fileno(), 0, prot=mmap.PROT_READ {"| mmap.PROT_EXEC" if executable else ""})\n'
            'pathlib.Path(sys.argv[2]).touch()\n'
            'time.sleep(60)\n')
        generation = 'loader-fixture-generation'
        proc = subprocess.Popen([str(self.node), str(script), str(image), str(ready)],
            env=dict(os.environ, OPERIT_HOST_GENERATION=generation),
            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        def cleanup():
            if proc.poll() is None:
                proc.kill()
            proc.wait()
            proc.stderr.close()
        self.addCleanup(cleanup)
        eventually(ready.exists)
        self.assertNotEqual((Path('/proc') / str(proc.pid) / 'exe').resolve(), expected.resolve(),
            'fixture must represent a loader rather than directly executing the expected file')
        record = dict(self.module.identity(proc.pid), generation=generation,
            owner=self.module.identity(os.getpid()))
        return proc, record, expected

    def test_loader_executable_mapping_identifies_and_stops_only_owned_host(self):
        proc, record, expected = self.loader_fixture()
        fd = self.module.owned_pidfd(record, expected, self.engine)
        self.assertIsNotNone(fd, 'loader host has the expected executable mapping but was rejected')
        try:
            self.module.terminate(fd)
            proc.wait(timeout=5)
        finally:
            os.close(fd)

    def test_read_only_image_mapping_does_not_authorize_host_signal(self):
        proc, record, expected = self.loader_fixture(executable=False)
        fd = self.module.owned_pidfd(record, expected, self.engine)
        try:
            self.assertIsNone(fd, 'non-executable data mapping authorized a host signal')
            self.assertIsNone(proc.poll())
        finally:
            if fd is not None:
                os.close(fd)

    def test_identical_image_bytes_with_different_inode_do_not_authorize_host_signal(self):
        other = self.runtime / 'other-image'
        shutil.copyfile(self.node, other)
        proc, record, expected = self.loader_fixture(mapped_image=other)
        fd = self.module.owned_pidfd(record, expected, self.engine)
        try:
            self.assertIsNone(fd, 'different mapped inode authorized a host signal')
            self.assertIsNone(proc.poll())
        finally:
            if fd is not None:
                os.close(fd)

    def test_sigkill_supervisor_can_restart_without_manual_pid_cleanup(self):
        owner = self.start()
        first = json.loads((self.runtime / 'host.pid').read_text())
        owner.kill()
        owner.wait()
        # The kernel parent-death guard also closes the spawn-before-record gap.
        def old_host_exited():
            try:
                state = (Path('/proc') / str(first['pid']) / 'stat').read_text().rsplit(')', 1)[1].split()[0]
                return state == 'Z'
            except FileNotFoundError:
                return True
        eventually(old_host_exited)
        restarted = self.start()
        eventually(lambda: json.loads((self.runtime / 'host.pid').read_text())['generation'] != first['generation'])
        self.assertIsNone(restarted.poll())
        self.assertEqual(self.call('stop').returncode, 0)
        restarted.wait(timeout=10)
        self.assertFalse((self.runtime / 'host.pid').exists())

    def test_dead_record_and_legacy_pid_are_reclaimed_without_signaling_unrelated_process(self):
        unrelated = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])
        def cleanup_unrelated():
            if unrelated.poll() is None:
                unrelated.kill()
            unrelated.wait()
        self.addCleanup(cleanup_unrelated)
        (self.runtime / 'host.pid').write_text(str(unrelated.pid))
        owner = self.start()
        self.assertIsNone(unrelated.poll(), 'legacy PID was signaled')
        self.assertEqual(self.call('stop').returncode, 0)
        owner.wait(timeout=10)
        # A valid-looking reused PID with another start identity/generation is stale.
        (self.runtime / 'host.pid').write_text(json.dumps({
            'pid': unrelated.pid, 'start': '0', 'boot': self.module.identity(unrelated.pid)['boot'],
            'generation': 'old-generation', 'owner': self.module.identity(unrelated.pid)}))
        self.assertEqual(self.call('stop').returncode, 0)
        self.assertIsNone(unrelated.poll(), 'reused PID was signaled')
        self.assertFalse((self.runtime / 'host.pid').exists())

    def test_existing_live_owner_rejects_duplicate_start(self):
        owner = self.start()
        duplicate = self.call('start')
        self.assertNotEqual(duplicate.returncode, 0)
        self.assertIsNone(owner.poll())
        self.assertEqual(self.call('stop').returncode, 0)
        owner.wait(timeout=10)

    def test_reused_supervisor_identity_reconciles_only_the_owned_host(self):
        original = self.start()
        record = json.loads((self.runtime / 'host.pid').read_text())
        record['owner']['start'] = '0'  # PID exists, but ownership generation is stale.
        (self.runtime / 'host.pid').write_text(json.dumps(record))
        replacement = self.start()
        original.wait(timeout=10)
        self.assertIsNone(replacement.poll())
        self.assertEqual(self.call('stop').returncode, 0)
        replacement.wait(timeout=10)

    def test_generation_mismatch_never_signals_even_same_executable_and_command(self):
        owner = self.start()
        record = json.loads((self.runtime / 'host.pid').read_text())
        original_generation = record['generation']
        record['generation'] = 'mismatched-generation'
        (self.runtime / 'host.pid').write_text(json.dumps(record))
        fd = os.pidfd_open(record['pid'])
        try:
            self.assertEqual(self.call('stop').returncode, 0)
            poll = select.poll()
            poll.register(fd, select.POLLIN)
            self.assertEqual(poll.poll(0), [], 'generation-mismatched process was terminated')
        finally:
            os.close(fd)
            # Restore genuine ownership for cleanup even if the assertion failed.
            record['generation'] = original_generation
            (self.runtime / 'host.pid').write_text(json.dumps(record))
            self.call('stop')
            owner.wait(timeout=10)


if __name__ == '__main__':
    unittest.main()
