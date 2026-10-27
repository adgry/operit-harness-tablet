#!/usr/bin/env python3
"""Private Ubuntu runtime locks and generation-bound, pidfd-safe host ownership."""
import contextlib
import ctypes
import fcntl
import json
import os
from pathlib import Path
import select
import signal
import subprocess
import sys
import uuid


@contextlib.contextmanager
def lock(path, blocking=True):
    # Never unlink a lock file: all contenders must lock the same inode.
    with open(path, 'a') as handle:
        fcntl.flock(handle, fcntl.LOCK_EX | (0 if blocking else fcntl.LOCK_NB))
        yield handle


def identity(pid):
    proc = Path('/proc') / str(pid)
    # comm may contain spaces and parentheses; fields after its final ')' start at 3.
    fields = (proc / 'stat').read_text().rsplit(')', 1)[1].split()
    return {'pid': pid, 'start': fields[19],
            'boot': Path('/proc/sys/kernel/random/boot_id').read_text().strip()}


def process_generation(pid):
    prefix = b'OPERIT_HOST_GENERATION='
    values = (Path('/proc') / str(pid) / 'environ').read_bytes().split(b'\0')
    return next((v[len(prefix):].decode() for v in values if v.startswith(prefix)), None)


def read_record(path):
    try:
        record = json.loads(path.read_text())
        return record if isinstance(record, dict) else None
    except (FileNotFoundError, ValueError):
        return None


def has_executable_mapping(proc, node):
    # PRoot's /proc/PID/exe is its ELF loader, including from another PRoot.
    # Kernel-backed executable mappings identify the actual image uniformly on
    # ordinary Linux and PRoot, independent of guest/host path aliases.
    expected = node.stat()
    device = (os.major(expected.st_dev), os.minor(expected.st_dev))
    for line in (proc / 'maps').read_text(errors='surrogateescape').splitlines():
        fields = line.split(maxsplit=5)
        if len(fields) < 5 or 'x' not in fields[1]:
            continue
        major, minor = fields[3].split(':')
        if ((int(major, 16), int(minor, 16)) == device
                and int(fields[4]) == expected.st_ino):
            return True
    return False


def owned_pidfd(record, node, engine):
    if not record or not isinstance(record.get('pid'), int) or record['pid'] <= 0:
        return None
    try:
        fd = os.pidfd_open(record['pid'])
    except ProcessLookupError:
        return None
    try:
        current = identity(record['pid'])
        proc = Path('/proc') / str(record['pid'])
        command = (proc / 'cmdline').read_bytes().split(b'\0')
        valid = (current['start'] == record.get('start') and current['boot'] == record.get('boot')
                 and has_executable_mapping(proc, node)
                 and os.fsencode(str(engine / 'node_modules/.bin/dsh')) in command
                 and bool(record.get('generation'))
                 and process_generation(record['pid']) == record['generation'])
        # pidfd was opened BEFORE validation; even PID reuse between validation and
        # signaling cannot redirect this descriptor to a different process.
        if valid:
            return fd
    except (FileNotFoundError, ProcessLookupError):
        pass
    os.close(fd)
    return None


def terminate(fd):
    try:
        signal.pidfd_send_signal(fd, signal.SIGTERM)
        poll = select.poll()
        poll.register(fd, select.POLLIN)
        if not poll.poll(8000):
            signal.pidfd_send_signal(fd, signal.SIGKILL)
            if not poll.poll(2000):
                raise RuntimeError('Owned host did not stop')
    except ProcessLookupError:
        pass


def owner_alive(record):
    owner = record.get('owner')
    if not isinstance(owner, dict) or not isinstance(owner.get('pid'), int):
        return False
    try:
        return identity(owner['pid']) == owner
    except (FileNotFoundError, ProcessLookupError):
        return False


def child_parent_guard(parent):
    # Close the spawn-before-record window: if the supervisor dies before it can
    # publish ownership, the kernel terminates its unrecorded child. No PID kill.
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.prctl(1, signal.SIGTERM, 0, 0, 0) != 0:  # PR_SET_PDEATHSIG
        raise OSError(ctypes.get_errno(), 'Cannot guard Harness parent lifetime')
    if os.getppid() != parent:
        os._exit(1)


def lifecycle(action, runtime, node, engine):
    # Explicit capability gate: do not substitute racy kill(pid) on older kernels.
    probe = os.pidfd_open(os.getpid())
    try:
        signal.pidfd_send_signal(probe, 0)
    finally:
        os.close(probe)
    path = runtime / 'host.pid'
    control = runtime / '.host-control.lock'
    with lock(control):
        record = read_record(path)
        fd = owned_pidfd(record, node, engine)
        if fd is not None:
            try:
                if action == 'start' and owner_alive(record):
                    raise RuntimeError('The owned Harness host is already running')
                terminate(fd)
            finally:
                os.close(fd)
        path.unlink(missing_ok=True)
        if action == 'stop':
            return 0
        generation = uuid.uuid4().hex
        env = dict(os.environ, OPERIT_HOST_GENERATION=generation)
        workspace = runtime / 'workspace'
        workspace.mkdir(exist_ok=True)
        parent = os.getpid()
        child = subprocess.Popen([str(node), str(engine / 'node_modules/.bin/dsh'),
                                  'web', '--no-open', '--host', '127.0.0.1', '--port', '0'],
                                 cwd=workspace, env=env,
                                 preexec_fn=lambda: child_parent_guard(parent))
        # A child can exit immediately. Keep its zombie unreaped until its start
        # identity is captured; the PID cannot be reused while it remains a child.
        record = dict(identity(child.pid), generation=generation, owner=identity(os.getpid()))
        temp = path.with_suffix('.pid.part')
        temp.write_text(json.dumps(record) + '\n')
        temp.replace(path)
    def interrupted(signum, frame):
        raise SystemExit(128 + signum)
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        return child.wait()
    finally:
        with lock(control):
            fd = owned_pidfd(record, node, engine)
            if fd is not None:
                try:
                    terminate(fd)
                finally:
                    os.close(fd)
            child.wait()
            if read_record(path) == record:
                path.unlink(missing_ok=True)


def main():
    action, *args = sys.argv[1:]
    if action == 'lock':
        try:
            with lock(args[0], blocking=False) as handle:
                # exec means SIGKILL releases the OS lock; descendants retain it
                # until ongoing extraction/writes finish, preventing overlap.
                os.set_inheritable(handle.fileno(), True)
                os.execvp(args[1], args[1:])
        except BlockingIOError:
            raise RuntimeError('Another install is running; retry when it finishes')
    else:
        if action not in ('start', 'stop'):
            raise RuntimeError('Expected lock, start, or stop')
        return lifecycle(action, *(Path(arg) for arg in args))


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, RuntimeError, AttributeError) as error:
        print(f'operit-harness: {error}', file=sys.stderr)
        sys.exit(1)
