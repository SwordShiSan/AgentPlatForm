"""Fixed trusted file-stream v1 protocol: bounded JSON header followed by exact raw bytes.

This program is only installed in the pinned non-networked file helper. It is
not a user shell or a public executable. The runner persists intent/ownership
before invoking it and requires a durable result and confirmed stop afterwards.
"""
import errno
import hashlib
import json
import os
import secrets
import stat
import sys
import unicodedata

MAX_BYTES = 100 * 1024 * 1024
CHUNK_BYTES = 64 * 1024
MAX_HEADER = 8192


class Rejected(ValueError):
    pass


def path_parts(path):
    if not isinstance(path, str) or not path or len(path.encode("utf-8")) > 4096:
        raise Rejected("FILE_PATH_REJECTED")
    if unicodedata.normalize("NFC", path) != path or "\\" in path or "\x00" in path:
        raise Rejected("FILE_PATH_REJECTED")
    parts = path.split("/")
    if any(part in ("", ".", "..") or len(part.encode("utf-8")) > 255 for part in parts):
        raise Rejected("FILE_PATH_REJECTED")
    return parts


def parent_fd(root, parts):
    fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        for part in parts:
            canonical_collision(fd, part)
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=fd)
            os.close(fd)
            fd = child
        return fd
    except BaseException:
        os.close(fd)
        raise


def canonical_collision(parent, name):
    with os.scandir(parent) as entries:
        for entry in entries:
            if entry.name != name and unicodedata.normalize("NFC", entry.name) == name:
                raise Rejected("FILE_NAME_NORMALIZATION_CONFLICT")


def hash_regular(parent, name):
    try:
        fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC, dir_fd=parent)
    except FileNotFoundError:
        return None
    try:
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1:
            raise Rejected("FILE_KIND_REJECTED")
        digest = hashlib.sha256()
        while chunk := os.read(fd, CHUNK_BYTES):
            digest.update(chunk)
        after = os.fstat(fd)
        if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns, before.st_ctime_ns) != (
                after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns):
            raise Rejected("FILE_CHANGED_DURING_READ")
        return {"sha256": digest.hexdigest(), "bytes": after.st_size,
                "device": after.st_dev, "inode": after.st_ino}
    finally:
        os.close(fd)


def apply(header, source, root="/workspace"):
    fields = {"protocol", "operationId", "path", "bytes", "sha256", "expectedSha256"}
    if not isinstance(header, dict) or set(header) != fields or header["protocol"] != "file-stream-v1":
        raise Rejected("FILE_PROTOCOL_REJECTED")
    import uuid
    try:
        if str(uuid.UUID(header["operationId"])) != header["operationId"]:
            raise ValueError()
    except (ValueError, TypeError, AttributeError):
        raise Rejected("FILE_OPERATION_REJECTED") from None
    size = header["bytes"]
    if type(size) is not int or not 0 <= size <= MAX_BYTES:
        raise Rejected("FILE_SIZE_REJECTED")
    for key in ["sha256", "expectedSha256"]:
        value = header[key]
        if key == "expectedSha256" and value is None:
            continue
        if not isinstance(value, str) or len(value) != 64 or any(ch not in "0123456789abcdef" for ch in value):
            raise Rejected("FILE_HASH_REJECTED")
    parts = path_parts(header["path"])
    parent = parent_fd(root, parts[:-1])
    temporary = ".ap-upload-" + header["operationId"] + "-" + secrets.token_hex(8)
    temporary_exists = False
    renamed = False
    try:
        canonical_collision(parent, parts[-1])
        original = hash_regular(parent, parts[-1])
        if header["expectedSha256"] is None:
            if original is not None:
                raise Rejected("FILE_NAME_CONFLICT")
        elif original is None or original["sha256"] != header["expectedSha256"]:
            raise Rejected("FILE_VERSION_CONFLICT")
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC,
                     0o600, dir_fd=parent)
        temporary_exists = True
        try:
            digest = hashlib.sha256()
            remaining = size
            while remaining:
                chunk = source.read(min(CHUNK_BYTES, remaining))
                if not chunk:
                    raise Rejected("FILE_CONTENT_MISMATCH")
                digest.update(chunk)
                view = memoryview(chunk)
                while view:
                    written = os.write(fd, view)
                    view = view[written:]
                remaining -= len(chunk)
            if source.read(1) or digest.hexdigest() != header["sha256"]:
                raise Rejected("FILE_CONTENT_MISMATCH")
            os.fsync(fd)
        finally:
            os.close(fd)
        current = hash_regular(parent, parts[-1])
        if current != original:
            raise Rejected("FILE_VERSION_CONFLICT")
        # The runner's exclusive data-plane lease excludes all authorized writers.
        # Never invoke this routine again to recover an uncertain original effect.
        os.rename(temporary, parts[-1], src_dir_fd=parent, dst_dir_fd=parent)
        renamed = True
        temporary_exists = False
        os.fsync(parent)
        return {"protocol": "file-stream-v1", "operationId": header["operationId"],
                "state": "APPLIED", "bytes": size, "sha256": header["sha256"]}
    except OSError as failure:
        if renamed:
            raise Rejected("FILE_RESULT_UNKNOWN") from None
        if failure.errno in (errno.ENOSPC, errno.EDQUOT):
            raise Rejected("FILE_STORAGE_FULL") from None
        raise
    finally:
        if temporary_exists:
            os.unlink(temporary, dir_fd=parent)
        os.close(parent)


def main():
    try:
        line = sys.stdin.buffer.readline(MAX_HEADER + 1)
        if len(line) > MAX_HEADER or not line.endswith(b"\n"):
            raise Rejected("FILE_PROTOCOL_REJECTED")
        result = apply(json.loads(line), sys.stdin.buffer)
    except (Rejected, ValueError, OSError) as failure:
        result = {"state": "FAILED", "code": str(failure) if isinstance(failure, Rejected) else "FILE_IO_FAILURE"}
    print(json.dumps(result, separators=(",", ":")))
    return 0 if result.get("state") == "APPLIED" else 1


if __name__ == "__main__":
    raise SystemExit(main())
