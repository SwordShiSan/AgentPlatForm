"""Fixed read-only, bounded directory-page protocol for the trusted file helper."""
import heapq
import json
import os
import stat
import sys
import uuid
import importlib.util

module_spec = importlib.util.spec_from_file_location("stream_bridge", os.path.join(os.path.dirname(__file__), "file-stream-bridge.py"))
bridge = importlib.util.module_from_spec(module_spec)
module_spec.loader.exec_module(bridge)
MAX_RESULT = 28 * 1024


def stamp(fd):
    value = os.fstat(fd)
    return ":".join(str(part) for part in (value.st_dev, value.st_ino, value.st_mtime_ns, value.st_ctime_ns))


def encoded(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def scan(header, root="/workspace", require_device=False):
    fields = {"protocol", "operationId", "parent", "cursor", "epoch", "stamp"}
    if isinstance(header, dict) and "filesystemDevice" in header:
        fields.add("filesystemDevice")
    if not isinstance(header, dict) or set(header) != fields or header["protocol"] != "file-scan-v1":
        raise bridge.Rejected("FILE_PROTOCOL_REJECTED")
    for field in ("operationId", "epoch"):
        if str(uuid.UUID(header[field])) != header[field]:
            raise bridge.Rejected("FILE_OPERATION_REJECTED")
    parent = header["parent"]
    parts = [] if parent == "" else bridge.path_parts(parent)
    cursor = header["cursor"]
    if cursor is not None:
        bridge.path_parts(cursor)
        if cursor.rpartition("/")[0] != parent:
            raise bridge.Rejected("FILE_PATH_REJECTED")
    bridge.verify_device(root, header.get("filesystemDevice"), require_device)
    fd = bridge.parent_fd(root, parts)
    try:
        original = stamp(fd)
        result = {"protocol": "file-scan-v1", "operationId": header["operationId"], "state": "SCANNED",
                  "parent": parent, "epoch": header["epoch"], "stamp": original, "cursor": cursor,
                  "next": None, "restart": False, "entries": []}
        if cursor is not None and header["stamp"] != original:
            result["restart"] = True
            return result

        def candidates():
            with os.scandir(fd) as entries:
                for entry in entries:
                    if entry.name.startswith(".ap-upload-"):
                        continue
                    path = parent + "/" + entry.name if parent else entry.name
                    bridge.path_parts(path)  # NFC and valid byte/component limits; never normalize an ambiguous disk name.
                    info = entry.stat(follow_symlinks=False)
                    if not stat.S_ISDIR(info.st_mode) and (not stat.S_ISREG(info.st_mode) or info.st_nlink != 1):
                        raise bridge.Rejected("FILE_KIND_REJECTED")
                    if cursor is None or path.encode("utf-8") > cursor.encode("utf-8"):
                        yield entry.name

        selected = heapq.nsmallest(101, candidates(), key=lambda name: name.encode("utf-8"))
        for index, name in enumerate(selected[:100]):
            path = parent + "/" + name if parent else name
            info = os.stat(name, dir_fd=fd, follow_symlinks=False)
            if stat.S_ISDIR(info.st_mode):
                child = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=fd)
                os.close(child)
                item = {"path": path, "kind": "DIRECTORY", "bytes": None, "sha256": None}
            elif stat.S_ISREG(info.st_mode) and info.st_nlink == 1:
                # Oversized external files remain explicitly unsealed, never represented by an old content hash.
                identity = bridge.hash_regular(fd, name) if info.st_size <= bridge.MAX_BYTES else None
                if identity is None and info.st_size <= bridge.MAX_BYTES:
                    raise bridge.Rejected("FILE_CHANGED_DURING_READ")
                item = {"path": path, "kind": "FILE", "bytes": identity["bytes"] if identity else info.st_size,
                        "sha256": identity["sha256"] if identity else None}
            else:
                raise bridge.Rejected("FILE_KIND_REJECTED")
            result["entries"].append(item)
            result["next"] = path if index + 1 < len(selected) else None
            if len(encoded(result)) > MAX_RESULT:
                result["entries"].pop()
                if not result["entries"]:
                    raise bridge.Rejected("FILE_SCAN_ENTRY_TOO_LARGE")
                result["next"] = result["entries"][-1]["path"]
                break
        if stamp(fd) != original:
            result.update(restart=True, next=None, entries=[])
        return result
    finally:
        os.close(fd)


def main():
    try:
        line = sys.stdin.buffer.readline(bridge.MAX_HEADER + 1)
        if len(line) > bridge.MAX_HEADER or not line.endswith(b"\n"):
            raise bridge.Rejected("FILE_PROTOCOL_REJECTED")
        result = scan(json.loads(line), require_device=True)
    except (ValueError, OSError, TypeError, KeyError) as failure:
        result = {"state": "FAILED", "code": str(failure) if isinstance(failure, bridge.Rejected) else "FILE_SCAN_FAILED"}
    sys.stdout.buffer.write(encoded(result) + b"\n")
    return 0 if result.get("state") == "SCANNED" else 1


if __name__ == "__main__":
    raise SystemExit(main())
