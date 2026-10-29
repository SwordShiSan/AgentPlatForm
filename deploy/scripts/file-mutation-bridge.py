"""Fixed file lifecycle primitive; integration must supply the workspace lease.

This is not a shell interface. Paths and frozen inventory come from a signed
runner command. No caller may treat a transport cancellation as stop evidence.
"""
import ctypes
import errno
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import stat
import uuid

spec = importlib.util.spec_from_file_location('stream', Path(__file__).with_name('file-stream-bridge.py'))
stream = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stream)
Rejected = stream.Rejected
AREA = '.ap-file-operations'
MAX_ENTRIES = 1000


def path(value):
    parts = stream.path_parts(value)
    if parts[0] == AREA:
        raise Rejected('FILE_RESERVED_PATH')
    return parts


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')


def digest(value):
    return hashlib.sha256(encoded(value)).hexdigest()


def inventory(root, relative, internal=False):
    """Bounded fd-based inventory, rejecting links and nonordinary entries."""
    parts = stream.path_parts(relative) if internal else path(relative)
    parent = stream.parent_fd(root, parts[:-1])
    rows = []

    def walk(fd, name, suffix):
        info = os.stat(name, dir_fd=fd, follow_symlinks=False)
        if len(rows) >= MAX_ENTRIES:
            raise Rejected('FILE_BATCH_TOO_LARGE')
        if stat.S_ISDIR(info.st_mode):
            child = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            try:
                opened = os.fstat(child)
                if (info.st_dev, info.st_ino) != (opened.st_dev, opened.st_ino):
                    raise Rejected('FILE_CHANGED_DURING_READ')
                rows.append({'path': suffix, 'kind': 'DIRECTORY', 'device': info.st_dev, 'inode': info.st_ino})
                names = []
                with os.scandir(child) as entries:
                    for item in entries:
                        if len(names) + len(rows) >= MAX_ENTRIES:
                            raise Rejected('FILE_BATCH_TOO_LARGE')
                        names.append(item.name)
                for entry in sorted(names):
                    stream.path_parts(entry)
                    stream.canonical_collision(child, entry)
                    walk(child, entry, suffix + '/' + entry if suffix else entry)
            finally:
                os.close(child)
        else:
            if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
                raise Rejected('FILE_KIND_REJECTED')
            checked = stream.hash_regular(fd, name)
            if checked is None:
                raise Rejected('FILE_SOURCE_MISSING')
            rows.append({'path': suffix, 'kind': 'FILE', **checked})

    try:
        walk(parent, parts[-1], '')
    finally:
        os.close(parent)
    return rows


def no_replace(source_fd, source, target_fd, target):
    stream.canonical_collision(target_fd, target)
    fn = getattr(ctypes.CDLL(None, use_errno=True), 'renameat2', None)
    if fn is None:
        raise Rejected('FILE_ATOMIC_CREATE_UNAVAILABLE')
    fn.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    fn.restype = ctypes.c_int
    if fn(source_fd, os.fsencode(source), target_fd, os.fsencode(target), 1):
        code = ctypes.get_errno()
        if code in (errno.EEXIST, errno.ENOTEMPTY):
            raise Rejected('FILE_NAME_CONFLICT')
        raise OSError(code, os.strerror(code))
    os.fsync(source_fd)
    os.fsync(target_fd)


def move(root, source, target, expected):
    src, dst = path(source), path(target)
    if dst[:len(src)] == src:
        raise Rejected('FILE_MOVE_INTO_SELF')
    before = inventory(root, source)
    if digest(before) != expected:
        raise Rejected('FILE_VERSION_CONFLICT')
    a = stream.parent_fd(root, src[:-1])
    b = stream.parent_fd(root, dst[:-1])
    try:
        no_replace(a, src[-1], b, dst[-1])
    finally:
        os.close(a)
        os.close(b)
    after = inventory(root, target)
    if after != before:
        raise Rejected('FILE_RESULT_UNKNOWN')
    return {'state': 'APPLIED', 'manifestHash': expected, 'entries': len(after)}


def operation_area(root, operation):
    if str(uuid.UUID(operation)) != operation:
        raise Rejected('FILE_OPERATION_REJECTED')
    fd = stream.parent_fd(root, [])
    try:
        created = False
        try:
            os.mkdir(AREA, 0o700, dir_fd=fd)
            created = True
            os.fsync(fd)
        except FileExistsError:
            pass
        area = os.open(AREA, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
        try:
            if created:
                marker = os.open('owner', os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=area)
                with os.fdopen(marker, 'wb') as file:
                    file.write(b'file-mutation-v1\n')
                    file.flush()
                    os.fsync(file.fileno())
                os.fsync(area)
            else:
                try:
                    marker = os.open('owner', os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=area)
                except OSError:
                    raise Rejected('FILE_RESERVED_PATH_CONFLICT') from None
                with os.fdopen(marker, 'rb') as file:
                    if not stat.S_ISREG(os.fstat(file.fileno()).st_mode) or file.read(128) != b'file-mutation-v1\n':
                        raise Rejected('FILE_RESERVED_PATH_CONFLICT')
            # A new command never adopts an existing operation directory.
            os.mkdir(operation, 0o700, dir_fd=area)
            os.fsync(area)
            return os.open(operation, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=area)
        finally:
            os.close(area)
    finally:
        os.close(fd)


def isolate(root, source, operation, expected):
    parts = path(source)
    before = inventory(root, source)
    if digest(before) != expected:
        raise Rejected('FILE_VERSION_CONFLICT')
    staging = operation_area(root, operation)
    parent = stream.parent_fd(root, parts[:-1])
    intent = {'protocol': 'file-mutation-v1', 'operationId': operation,
              'source': source, 'manifest': before, 'manifestHash': expected}
    try:
        fd = os.open('intent.json', os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=staging)
        try:
            with os.fdopen(fd, 'wb') as file:
                file.write(encoded(intent))
                file.flush()
                os.fsync(file.fileno())
        finally:
            os.fsync(staging)
        no_replace(parent, parts[-1], staging, 'payload')
    finally:
        os.close(parent)
        os.close(staging)
    return {'state': 'ISOLATED', 'manifestHash': expected, 'operationId': operation}


def query_isolation(root, operation, expected):
    """Observe original intent only; never repeats a mutation or infers cleanup."""
    if str(uuid.UUID(operation)) != operation:
        raise Rejected('FILE_OPERATION_REJECTED')
    fd = stream.parent_fd(root, [AREA, operation])
    try:
        intent_fd = os.open('intent.json', os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd)
        with os.fdopen(intent_fd, 'rb') as file:
            info = os.fstat(file.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
                raise Rejected('FILE_RECEIPT_MISMATCH')
            raw = file.read(8 * 1024 * 1024 + 1)
        if len(raw) > 8 * 1024 * 1024:
            raise Rejected('FILE_PROTOCOL_REJECTED')
        intent = json.loads(raw)
        if intent['operationId'] != operation or intent['manifestHash'] != expected or digest(intent['manifest']) != expected:
            raise Rejected('FILE_RECEIPT_MISMATCH')
        try:
            complete = os.open('cleanup.json', os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd)
        except FileNotFoundError:
            complete = None
        if complete is not None:
            with os.fdopen(complete, 'rb') as file:
                info = os.fstat(file.fileno())
                if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
                    raise Rejected('FILE_RECEIPT_MISMATCH')
                result = json.loads(file.read(4096))
            if result != {'state': 'CLEANED', 'operationId': operation, 'manifestHash': expected}:
                raise Rejected('FILE_RECEIPT_MISMATCH')
            return result
        try:
            info = os.stat('payload', dir_fd=fd, follow_symlinks=False)
        except FileNotFoundError:
            return {'state': 'UNKNOWN', 'operationId': operation}
        first = intent['manifest'][0]
        if (info.st_dev, info.st_ino) != (first['device'], first['inode']):
            return {'state': 'UNKNOWN', 'operationId': operation}
        if inventory(root, AREA + '/' + operation + '/payload', internal=True) != intent['manifest']:
            return {'state': 'UNKNOWN', 'operationId': operation}
        return {'state': 'ISOLATED', 'operationId': operation, 'manifestHash': expected}
    finally:
        os.close(fd)


def cleanup(root, operation, expected):
    """Runner invokes only after the tombstone commit; no lease release here."""
    result = query_isolation(root, operation, expected)
    if result['state'] == 'CLEANED':
        return result
    if result['state'] != 'ISOLATED':
        raise Rejected('FILE_RESULT_UNKNOWN')
    staging = stream.parent_fd(root, [AREA, operation])

    def remove(fd, name):
        info = os.stat(name, dir_fd=fd, follow_symlinks=False)
        if stat.S_ISDIR(info.st_mode):
            child = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            try:
                for entry in os.listdir(child):
                    remove(child, entry)
                os.fsync(child)
            finally:
                os.close(child)
            os.rmdir(name, dir_fd=fd)
        elif stat.S_ISREG(info.st_mode) and info.st_nlink == 1:
            os.unlink(name, dir_fd=fd)
        else:
            raise Rejected('FILE_RESULT_UNKNOWN')
        os.fsync(fd)

    try:
        remove(staging, 'payload')
        result = {'state': 'CLEANED', 'operationId': operation, 'manifestHash': expected}
        fd = os.open('cleanup.json', os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=staging)
        with os.fdopen(fd, 'wb') as file:
            file.write(encoded(result))
            file.flush()
            os.fsync(file.fileno())
        os.fsync(staging)
        return result
    finally:
        os.close(staging)
