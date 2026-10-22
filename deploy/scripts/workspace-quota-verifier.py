"""Read-only mount/identity/quota attestation; never allocate or repair a directory."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys

from importlib.util import spec_from_file_location, module_from_spec

spec = spec_from_file_location("quota", Path(__file__).with_name("workspace-quota-helper.py"))
quota = module_from_spec(spec)
spec.loader.exec_module(quota)


def verify(space, workspace, project, expected_uuid):
    if project < 200001:
        raise ValueError("WORKSPACE_PROJECT_MISMATCH")
    root = quota.ROOT
    mount = json.loads(quota.run(["findmnt", "-J", "--target", str(root)]))["filesystems"][0]
    if mount["target"] != str(root) or mount["fstype"] != "xfs" or not {"prjquota", "pquota"}.intersection(mount["options"].split(",")):
        raise ValueError("WORKSPACE_MOUNT_UNAVAILABLE")
    quota.quota_device(mount["source"])
    if expected_uuid not in quota.run(["xfs_io", "-r", "-c", "fsuuid", str(root)]):
        raise ValueError("WORKSPACE_MOUNT_IDENTITY_MISMATCH")
    work = root / space / workspace / "work"
    marker = root / ".workspace-control" / (workspace + ".json")
    if quota.unsafe(work) or quota.unsafe(marker):
        raise ValueError("WORKSPACE_PATH_UNSAFE")
    for parent in (root, root / space, root / space / workspace, root / ".workspace-control"):
        metadata = parent.stat(follow_symlinks=False)
        if not stat.S_ISDIR(metadata.st_mode) or metadata.st_uid != 0 or metadata.st_mode & 0o022:
            raise ValueError("WORKSPACE_PATH_UNSAFE")
    metadata = work.stat(follow_symlinks=False)
    if not stat.S_ISDIR(metadata.st_mode) or metadata.st_uid != 10001 or metadata.st_gid != 10001 or stat.S_IMODE(metadata.st_mode) != 0o700:
        raise ValueError("WORKSPACE_OWNER_MISMATCH")
    fd = os.open(marker, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
    with os.fdopen(fd, "rb") as stream:
        if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
            raise ValueError("WORKSPACE_MARKER_MISMATCH")
        raw = stream.read(65537)
    if len(raw) > 65536:
        raise ValueError("WORKSPACE_MARKER_MISMATCH")
    facts = json.loads(raw)
    for key, value in {"space": space, "workspace": workspace, "project": project,
                       "uuid": expected_uuid, "bytes": 2147483648, "inodes": 100000,
                       "verified": True, "ownerUid": 10001, "ownerGid": 10001, "mode": "0700"}.items():
        if facts.get(key) != value:
            raise ValueError("WORKSPACE_MARKER_MISMATCH")
    state = quota.run(["xfs_quota", "-x", "-c", "state -p", str(root)])
    if not re.search(r"Accounting:\s+ON", state) or not re.search(r"Enforcement:\s+ON", state):
        raise ValueError("WORKSPACE_QUOTA_NOT_VERIFIED")
    for flag, expected in (("-b", 2097152), ("-i", 100000)):
        report = quota.run(["xfs_quota", "-x", "-c", f"report -p {flag} -n -N -L {project} -U {project}", str(root)])
        quota.hard_limit(report, project, expected)
    # O_PATH obtains the checked directory handle without granting root a DAC bypass.
    # Enter it only after dropping to its owner;0700 ancestors remain unchanged.
    work_fd = os.open(work, os.O_PATH | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        os.setgroups([])
        os.setgid(10001)
        os.setuid(10001)
        os.fchdir(work_fd)
    finally:
        os.close(work_fd)
    project_stat = quota.run(["xfs_io", "-r", "-c", "stat", "."])
    if not re.search(rf"fsxattr\.projid\s*=\s*{project}\b", project_stat):
        raise ValueError("WORKSPACE_PROJECT_MISMATCH")
    return {"verified": True, "allocationHash": hashlib.sha256(raw).hexdigest()}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--space", required=True, type=quota.identity)
    parser.add_argument("--workspace", required=True, type=quota.identity)
    parser.add_argument("--project", required=True, type=int)
    parser.add_argument("--mount-uuid", required=True, type=quota.identity)
    args = parser.parse_args()
    try:
        print(json.dumps(verify(args.space, args.workspace, args.project, args.mount_uuid)), flush=True)
    except Exception as failure:
        code = str(failure)
        if not isinstance(failure, (ValueError, RuntimeError)) or not re.fullmatch("[A-Z_]{1,64}", code):
            code = "WORKSPACE_VERIFICATION_UNAVAILABLE"
        print(json.dumps({"verified": False, "errorCode": code}), flush=True)
        sys.exit(1)
