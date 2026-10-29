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
import sys

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


def execute(header, root='/workspace', source=None):
    if not isinstance(header,dict) or header.get('protocol')!='file-mutation-v1' or header.get('kind') not in ('MOVE','DELETE','DELETE_CLEANUP','RESTORE_FILE','RESTORE_TREE'):
        raise Rejected('FILE_PROTOCOL_REJECTED')
    operation=header.get('operationId')
    if str(uuid.UUID(operation))!=operation:
        raise Rejected('FILE_OPERATION_REJECTED')
    stream.verify_device(root,header.get('filesystemDevice'),True)
    manifest=header.get('manifestJson')
    if not isinstance(manifest,str) or hashlib.sha256(manifest.encode()).hexdigest()!=header.get('manifestHash'):
        raise Rejected('FILE_RECEIPT_MISMATCH')
    expected=json.loads(manifest)
    if header['kind']=='RESTORE_TREE':
        return restore_tree(header,expected,root,source)
    if header['kind']=='RESTORE_FILE':
        if not isinstance(expected,dict) or type(expected.get('sourceMissing')) is not bool:
            raise Rejected('FILE_PROTOCOL_REJECTED')
        path(header['sourcePath']);path(header['targetPath'])
        moved=False
        try:
            if not expected['sourceMissing'] and header['sourcePath']!=header['targetPath']:
                original=inventory(root,header['sourcePath'])
                if len(original)!=1 or original[0]['kind']!='FILE' or original[0]['sha256']!=expected['expectedSha256']:
                    raise Rejected('FILE_VERSION_CONFLICT')
                move(root,header['sourcePath'],header['targetPath'],digest(original));moved=True
            result=stream.apply({'protocol':'file-stream-v2','operationId':operation,'path':header['targetPath'],
                'bytes':header['bytes'],'sha256':header['sha256'],'expectedSha256':expected['expectedSha256'],
                'filesystemDevice':header['filesystemDevice']},source,root,True)
        except Exception:
            if moved:raise Rejected('FILE_RESULT_UNKNOWN') from None
            raise
        return {'protocol':'file-mutation-v1','operationId':operation,'state':'MUTATED','kind':'RESTORE_FILE',
                'manifestHash':header['manifestHash'],'bytes':result['bytes'],'sha256':result['sha256']}
    if header['kind']=='DELETE_CLEANUP':
        parent=header.get('parentOperationId')
        if str(uuid.UUID(parent))!=parent:
            raise Rejected('FILE_OPERATION_REJECTED')
        fd=stream.parent_fd(root,[AREA,parent])
        try:
            descriptor=os.open('intent.json',os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK,dir_fd=fd)
            with os.fdopen(descriptor,'rb') as file:
                info=os.fstat(file.fileno())
                if not stat.S_ISREG(info.st_mode) or info.st_nlink!=1:raise Rejected('FILE_RECEIPT_MISMATCH')
                original=json.loads(file.read(8*1024*1024+1))
        finally:os.close(fd)
        public=[{k:v for k,v in row.items() if k not in ('device','inode')} for row in original['manifest']]
        if original['operationId']!=parent or public!=expected:raise Rejected('FILE_RECEIPT_MISMATCH')
        cleanup(root,parent,original['manifestHash'])
        return {'protocol':'file-mutation-v1','operationId':operation,'state':'MUTATED','kind':'DELETE_CLEANUP',
                'manifestHash':header['manifestHash'],'entries':len(expected)}
    actual=inventory(root,header['sourcePath'])
    public=[{k:v for k,v in row.items() if k not in ('device','inode')} for row in actual]
    if public!=expected:
        raise Rejected('FILE_VERSION_CONFLICT')
    if header['kind']=='MOVE':move(root,header['sourcePath'],header['targetPath'],digest(actual))
    else:isolate(root,header['sourcePath'],operation,digest(actual))
    return {'protocol':'file-mutation-v1','operationId':operation,'state':'MUTATED','kind':header['kind'],
            'manifestHash':header['manifestHash'],'entries':len(actual)}


def remove_owned(parent,name):
    info=os.stat(name,dir_fd=parent,follow_symlinks=False)
    if stat.S_ISDIR(info.st_mode):
        child=os.open(name,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=parent)
        try:
            for entry in os.listdir(child):remove_owned(child,entry)
            os.fsync(child)
        finally:os.close(child)
        os.rmdir(name,dir_fd=parent)
    elif stat.S_ISREG(info.st_mode) and info.st_nlink==1:os.unlink(name,dir_fd=parent)
    else:raise Rejected('FILE_RESULT_UNKNOWN')
    os.fsync(parent)


def restore_tree(header,expected,root,source):
    if not isinstance(expected,list) or not 1<=len(expected)<=1000 or expected[0]!={'kind':'DIRECTORY','path':''}:
        raise Rejected('FILE_PROTOCOL_REJECTED')
    seen=set();total=0
    for row in expected:
        if not isinstance(row,dict) or row.get('path') in seen:raise Rejected('FILE_PROTOCOL_REJECTED')
        seen.add(row['path'])
        if row['path']:path(row['path'])
        if row.get('kind')=='DIRECTORY':
            if set(row)!={'kind','path'}:raise Rejected('FILE_PROTOCOL_REJECTED')
        elif row.get('kind')=='FILE':
            if set(row)!={'kind','path','bytes','sha256'} or type(row['bytes']) is not int or not 0<=row['bytes']<=stream.MAX_BYTES:
                raise Rejected('FILE_PROTOCOL_REJECTED')
            if not isinstance(row['sha256'],str) or len(row['sha256'])!=64 or any(c not in '0123456789abcdef' for c in row['sha256']):raise Rejected('FILE_PROTOCOL_REJECTED')
            total+=row['bytes']
        else:raise Rejected('FILE_KIND_REJECTED')
    if total>2147483648:raise Rejected('FILE_STORAGE_FULL')
    target=path(header['targetPath']);destination=stream.parent_fd(root,target[:-1])
    staging=None;published=False;created_payload=False
    try:
        stream.canonical_collision(destination,target[-1])
        try:os.stat(target[-1],dir_fd=destination,follow_symlinks=False)
        except FileNotFoundError:pass
        else:raise Rejected('FILE_NAME_CONFLICT')
        available=os.fstatvfs(destination);block=available.f_frsize or available.f_bsize
        if available.f_bavail*block<total or available.f_favail<len(expected)+3:raise Rejected('FILE_STORAGE_FULL')
        staging=operation_area(root,header['operationId'])
        os.mkdir('payload',0o700,dir_fd=staging);created_payload=True;os.fsync(staging)
        for row in expected[1:]:
            parts=path(row['path'])
            parent=stream.parent_fd(root,[AREA,header['operationId'],'payload',*parts[:-1]])
            try:
                if row['kind']=='DIRECTORY':os.mkdir(parts[-1],0o700,dir_fd=parent)
                else:
                    fd=os.open(parts[-1],os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600,dir_fd=parent)
                    with os.fdopen(fd,'wb') as file:
                        remaining=row['bytes'];sha=hashlib.sha256()
                        while remaining:
                            chunk=source.read(min(65536,remaining))
                            if not chunk:raise Rejected('FILE_CONTENT_MISMATCH')
                            file.write(chunk);sha.update(chunk);remaining-=len(chunk)
                        if sha.hexdigest()!=row['sha256']:raise Rejected('FILE_CONTENT_MISMATCH')
                        file.flush();os.fsync(file.fileno())
                os.fsync(parent)
            finally:os.close(parent)
        trailer=bytearray()
        while len(trailer)<32:
            chunk=source.read(32-len(trailer))
            if not chunk:raise Rejected('FILE_CONTENT_MISMATCH')
            trailer.extend(chunk)
        if bytes(trailer)!=bytes.fromhex(header['manifestHash']):raise Rejected('FILE_CONTENT_MISMATCH')
        actual=inventory(root,AREA+'/'+header['operationId']+'/payload',internal=True)
        if [{k:v for k,v in row.items() if k not in ('device','inode')} for row in actual]!=expected:raise Rejected('FILE_CONTENT_MISMATCH')
        no_replace(staging,'payload',destination,target[-1]);published=True
        return {'protocol':'file-mutation-v1','operationId':header['operationId'],'state':'MUTATED','kind':'RESTORE_TREE',
                'manifestHash':header['manifestHash'],'entries':len(expected)}
    except Exception as failure:
        if published:raise Rejected('FILE_RESULT_UNKNOWN') from None
        if staging is not None and created_payload:
            try:remove_owned(staging,'payload')
            except Exception:raise Rejected('FILE_RESULT_UNKNOWN') from None
        if isinstance(failure,OSError) and failure.errno in (errno.ENOSPC,errno.EDQUOT):raise Rejected('FILE_STORAGE_FULL') from None
        raise
    finally:
        os.close(destination)
        if staging is not None:os.close(staging)


def main():
    try:
        raw=sys.stdin.buffer.readline(8*1024*1024+1)
        if len(raw)>8*1024*1024 or not raw.endswith(b'\n'):
            raise Rejected('FILE_PROTOCOL_REJECTED')
        result=execute(json.loads(raw),source=sys.stdin.buffer)
        print(json.dumps(result),flush=True)
    except Rejected as failure:
        print(json.dumps({'state':'FAILED','code':str(failure)}),flush=True)
        raise SystemExit(1)
    except Exception:
        print(json.dumps({'state':'UNKNOWN','code':'FILE_RESULT_UNKNOWN'}),flush=True)
        raise SystemExit(2)


if __name__=='__main__':main()
