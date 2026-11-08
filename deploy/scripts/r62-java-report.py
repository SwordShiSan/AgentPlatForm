#!/usr/bin/env python3
"""Read a bounded, sanitized report from the latest R6.2 Java materialization run."""
import json
from pathlib import Path
import shutil
import subprocess

BASE = Path("/srv/agentplatform/acceptance")
TEST_DISK = BASE / "r62-mount-poc-c15bd087764e44d983f54b2868d3f7d3/test.xfs"


def call(*args):
    result = subprocess.run(args, capture_output=True, text=True, timeout=30, check=False)
    if result.returncode:
        return None
    return result.stdout.strip()


def main():
    roots = sorted((p for p in BASE.glob("r62-java-material-*") if p.is_dir()),
                   key=lambda p: p.stat().st_mtime, reverse=True)
    if not roots:
        raise SystemExit("NO_CANARY_RECORD")
    root = roots[0]
    diagnostic = []
    log = root / "runner.private.log"
    if log.is_file():
        for line in log.read_text(errors="replace").splitlines():
            if line.startswith(("R62_JAVA_CANARY_DIAGNOSTIC ", "R62_JAVA_CAPABILITY_MOUNT_PASS ")):
                diagnostic.append(line[:500])
    restored = None
    for name in ("restored.json", "watchdog-restored.json"):
        path = root / name
        if path.is_file():
            restored = json.loads(path.read_text())
            break
    mount = call("findmnt", "--mountpoint", str(TEST_DISK.parent / "workspaces"))
    loops = call("losetup", "-j", str(TEST_DISK))
    child_id = None
    operation_path = root / "operation.json"
    if operation_path.is_file():
        operation = json.loads(operation_path.read_text())["operationId"]
        child_id = call("docker", "inspect", "--format", "{{.Id}}", "ap-capability-material-" + operation)
    outer_id = call("docker", "inspect", "--format", "{{.Id}}", "ap-r62-java-" + root.name.removeprefix("r62-java-material-"))
    print(json.dumps({
        "status": json.loads((root / "probe.json").read_text()).get("status") if (root / "probe.json").is_file() else "FAIL",
        "run": root.name,
        "diagnostic": diagnostic,
        "restored": restored,
        "testMountPresent": bool(mount),
        "testLoopPresent": bool(loops),
        "canaryContainerPresent": bool(child_id or outer_id),
        "rootFreeBytes": shutil.disk_usage("/").free,
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
