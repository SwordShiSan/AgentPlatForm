"""Fixed pre-admission mount canary, then idle. No script or endpoint input."""
import argparse
import hashlib
import json
import os
import re
import time
import uuid


def identity(value):
    parsed = str(uuid.UUID(value))
    if parsed != value:
        raise ValueError("Noncanonical identity")
    return parsed


def content(environment, space, workspace, command, generation, fence, policy):
    values = [identity(value) for value in (environment, space, workspace, command)]
    if generation < 1 or fence < 1 or not re.fullmatch(r"[0-9a-f]{64}", policy):
        raise ValueError("Invalid policy identity")
    return ("r32-ready-v1\n" + "\n".join(values + [str(generation), str(fence), policy]) + "\n").encode()


def attest(root, environment, space, workspace, command, generation, fence, policy):
    data = content(environment, space, workspace, command, generation, fence, policy)
    directory = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        name = ".platform-ready-" + command
        fd = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC,
                     0o600, dir_fd=directory)
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.fsync(directory)
        fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=directory)
        with os.fdopen(fd, "rb") as stream:
            if stream.read(4097) != data:
                raise ValueError("Canary readback mismatch")
    finally:
        os.close(directory)
    return {"ready": True, "commandId": command, "policyHash": policy,
            "contentHash": hashlib.sha256(data).hexdigest()}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    for field in ("environment", "space", "workspace", "command"):
        parser.add_argument("--" + field, type=identity, required=True)
    parser.add_argument("--generation", type=int, required=True)
    parser.add_argument("--fence", type=int, required=True)
    parser.add_argument("--policy", required=True)
    args = vars(parser.parse_args())
    print(json.dumps(attest("/workspace", **args), separators=(",", ":")), flush=True)
    while True:
        time.sleep(3600)
