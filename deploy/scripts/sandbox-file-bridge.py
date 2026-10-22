"""Fixed private data-plane protocol. No shell, host path or arbitrary program input."""
import base64
import errno
import hashlib
import json
import os
import secrets
import stat
import sys

MAX_FILE = 32768
MAX_REQUEST = 65536
MAX_LIST = 128


class Rejected(ValueError):
    pass


def components(value, root_allowed=False):
    if not isinstance(value, str) or len(value.encode("utf-8")) > 4096:
        raise Rejected("FILE_PATH_REJECTED")
    if not value and root_allowed:
        return []
    if not value or value.startswith("/") or "\\" in value or "\x00" in value:
        raise Rejected("FILE_PATH_REJECTED")
    parts = value.split("/")
    if any(part in ("", ".", "..") for part in parts):
        raise Rejected("FILE_PATH_REJECTED")
    return parts


def directory(root, parts):
    fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        for part in parts:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=fd)
            os.close(fd)
            fd = child
        return fd
    except BaseException:
        os.close(fd)
        raise


def regular(metadata):
    if not stat.S_ISREG(metadata.st_mode) or metadata.st_nlink != 1:
        raise Rejected("FILE_KIND_REJECTED")


def execute(request, root="/workspace"):
    if not isinstance(request, dict) or set(request) - {"action", "path", "contentBase64"}:
        raise Rejected("FILE_REQUEST_REJECTED")
    action = request.get("action")
    if action not in ("READ", "WRITE", "LIST"):
        raise Rejected("FILE_ACTION_REJECTED")
    if action != "WRITE" and "contentBase64" in request:
        raise Rejected("FILE_REQUEST_REJECTED")
    parts = components(request.get("path"), action == "LIST")
    parent = directory(root, parts if action == "LIST" else parts[:-1])
    try:
        if action == "LIST":
            # scandir is lazy: the number of returned entries and bytes is bounded.
            entries = []
            truncated = False
            with os.scandir(parent) as items:
                for item in items:
                    if len(entries) == MAX_LIST:
                        truncated = True
                        break
                    metadata = item.stat(follow_symlinks=False)
                    if stat.S_ISDIR(metadata.st_mode):
                        kind = "DIRECTORY"
                    elif stat.S_ISREG(metadata.st_mode) and metadata.st_nlink == 1:
                        kind = "FILE"
                    else:
                        kind = "REJECTED"
                    entries.append({"name": item.name, "kind": kind})
            return {"ok": True, "entries": entries, "truncated": truncated}
        name = parts[-1]
        if action == "READ":
            # NONBLOCK prevents a substituted FIFO from blocking before fstat.
            fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK, dir_fd=parent)
            with os.fdopen(fd, "rb") as stream:
                regular(os.fstat(stream.fileno()))
                content = stream.read(MAX_FILE + 1)
                if len(content) > MAX_FILE:
                    raise Rejected("FILE_TOO_LARGE")
        else:
            value = request.get("contentBase64")
            if not isinstance(value, str) or len(value) > 4 * ((MAX_FILE + 2) // 3):
                raise Rejected("FILE_TOO_LARGE")
            try:
                content = base64.b64decode(value, validate=True)
            except (ValueError, TypeError):
                raise Rejected("FILE_REQUEST_REJECTED") from None
            if len(content) > MAX_FILE:
                raise Rejected("FILE_TOO_LARGE")
            try:
                regular(os.stat(name, dir_fd=parent, follow_symlinks=False))
            except FileNotFoundError:
                pass
            temporary = ".bridge-" + secrets.token_hex(16)
            fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC,
                         0o600, dir_fd=parent)
            try:
                with os.fdopen(fd, "wb") as stream:
                    stream.write(content)
                    stream.flush()
                    os.fsync(stream.fileno())
                os.replace(temporary, name, src_dir_fd=parent, dst_dir_fd=parent)
                os.fsync(parent)
            finally:
                try:
                    os.unlink(temporary, dir_fd=parent)
                except FileNotFoundError:
                    pass
        result = {"ok": True, "bytes": len(content), "sha256": hashlib.sha256(content).hexdigest()}
        if action == "READ":
            result["contentBase64"] = base64.b64encode(content).decode("ascii")
        return result
    finally:
        os.close(parent)


def main():
    os.umask(0o077)
    try:
        # docker-java exec does not promise an input half-close after a finite stream.
        # One bounded JSONL frame is complete without waiting for transport EOF.
        raw = sys.stdin.buffer.readline(MAX_REQUEST + 1)
        if len(raw) > MAX_REQUEST:
            raise Rejected("FILE_REQUEST_TOO_LARGE")
        if not raw.endswith(b"\n"):
            raise Rejected("FILE_REQUEST_REJECTED")
        request = json.loads(raw)
        result = execute(request)
    except Rejected as failure:
        result = {"ok": False, "error": str(failure)}
    except (ValueError, UnicodeError, OSError) as failure:
        result = {"ok": False, "error": "FILE_QUOTA_EXCEEDED" if isinstance(failure, OSError)
                  and failure.errno in (errno.EDQUOT, errno.ENOSPC) else "FILE_ACCESS_REJECTED"}
    encoded = json.dumps(result, ensure_ascii=True, separators=(",", ":"))
    if len(encoded.encode()) > 65536:
        encoded = '{"ok":false,"error":"FILE_RESULT_TOO_LARGE"}'
    print(encoded, flush=True)
    return 0 if result.get("ok") else 1


if __name__ == "__main__":
    raise SystemExit(main())
