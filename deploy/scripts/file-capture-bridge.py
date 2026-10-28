"""Fixed immutable-output capture framing; no object address, credentials or program input.

The owner must stop the sandbox and retain its exclusive workspace lease first.
A descriptor and READY handshake bind a second streaming pass to the same open
file. The receiver must validate the trailing digest AND process exit before it
can complete its object or publish a file version.
"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import stat
import sys
import uuid

_spec = importlib.util.spec_from_file_location("fixed_file_stream", Path(__file__).with_name("file-stream-bridge.py"))
fs = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(fs)
Rejected = fs.Rejected
PROTOCOL = "file-capture-v1"
MAX_FRAME = 8192
CHUNK = 65536


def frame(source):
    raw = source.readline(MAX_FRAME + 1)
    if len(raw) > MAX_FRAME or not raw.endswith(b"\n"):
        raise Rejected("FILE_CAPTURE_FRAME_REJECTED")
    value = json.loads(raw)
    if not isinstance(value, dict):
        raise Rejected("FILE_CAPTURE_FRAME_REJECTED")
    return value


def regular(info):
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
        raise Rejected("FILE_KIND_REJECTED")
    return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns, info.st_nlink)


def write_all(target, content):
    view = memoryview(content)
    while view:
        count = target.write(view)
        if count is None or count <= 0:
            raise Rejected("FILE_CAPTURE_TRANSPORT_CLOSED")
        view = view[count:]


def capture(header, source, output, root="/workspace", require_device=False):
    fields = {"protocol", "operationId", "path", "maxBytes"}
    if isinstance(header, dict) and "filesystemDevice" in header:
        fields.add("filesystemDevice")
    if not isinstance(header, dict) or set(header) != fields or header.get("protocol") != PROTOCOL:
        raise Rejected("FILE_CAPTURE_FRAME_REJECTED")
    try:
        if str(uuid.UUID(header["operationId"])) != header["operationId"]:
            raise ValueError()
    except (ValueError, TypeError, AttributeError):
        raise Rejected("FILE_CAPTURE_FRAME_REJECTED") from None
    maximum = header["maxBytes"]
    if type(maximum) is not int or not 0 <= maximum <= fs.MAX_BYTES:
        raise Rejected("FILE_TOO_LARGE")
    parts = fs.path_parts(header["path"])
    fs.verify_device(root, header.get("filesystemDevice"), require_device)
    parent = fs.parent_fd(root, parts[:-1])
    fd = None
    try:
        fs.canonical_collision(parent, parts[-1])
        try:
            fd = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC, dir_fd=parent)
        except FileNotFoundError:
            raise Rejected("FILE_CAPTURE_MISSING") from None
        except OSError:
            raise Rejected("FILE_KIND_REJECTED") from None
        before = os.fstat(fd)
        identity = regular(before)
        if before.st_size > maximum:
            raise Rejected("FILE_TOO_LARGE")
        digest = hashlib.sha256()
        total = 0
        while chunk := os.read(fd, min(CHUNK, before.st_size - total + 1)):
            total += len(chunk)
            if total > before.st_size:
                raise Rejected("FILE_CHANGED_DURING_READ")
            digest.update(chunk)
        if total != before.st_size or regular(os.fstat(fd)) != identity:
            raise Rejected("FILE_CHANGED_DURING_READ")
        descriptor = {"protocol": PROTOCOL, "operationId": header["operationId"],
                      "state": "DESCRIBED", "bytes": total, "sha256": digest.hexdigest()}
        write_all(output, json.dumps(descriptor, separators=(",", ":")).encode("ascii") + b"\n")
        output.flush()
        ready = frame(source)
        if ready != {"protocol": PROTOCOL, "operationId": header["operationId"], "ready": True,
                     "sha256": descriptor["sha256"]} or type(ready.get("ready")) is not bool:
            raise Rejected("FILE_CAPTURE_READY_REJECTED")
        if regular(os.fstat(fd)) != identity:
            raise Rejected("FILE_CHANGED_DURING_READ")
        os.lseek(fd, 0, os.SEEK_SET)
        second = hashlib.sha256()
        remaining = total
        while remaining:
            chunk = os.read(fd, min(CHUNK, remaining))
            if not chunk:
                raise Rejected("FILE_CHANGED_DURING_READ")
            second.update(chunk)
            write_all(output, chunk)
            remaining -= len(chunk)
        if os.read(fd, 1) or regular(os.fstat(fd)) != identity or second.digest() != digest.digest():
            raise Rejected("FILE_CHANGED_DURING_READ")
        write_all(output, second.digest())
        output.flush()
        return descriptor
    finally:
        if fd is not None:
            os.close(fd)
        os.close(parent)


def main():
    os.umask(0o077)
    try:
        capture(frame(sys.stdin.buffer), sys.stdin.buffer, sys.stdout.buffer, require_device=True)
        return 0
    except (Rejected, ValueError, TypeError, UnicodeError, OSError):
        # Fixed stderr only: no file content, host paths or input reflection.
        sys.stderr.write("FILE_CAPTURE_UNCONFIRMED\n")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
