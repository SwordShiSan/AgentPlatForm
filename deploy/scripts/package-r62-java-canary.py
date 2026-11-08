#!/usr/bin/env python3
"""Build an immutable, self-checking fat JAR for the R6.2 Linux canary."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tempfile
import zipfile

REPO = Path(__file__).resolve().parents[2]
SOURCE = REPO / "deploy/probes/R62CapabilityMountCanary.java"
ENTRY = "BOOT-INF/classes/com/agentplatform/app/workspace/R62CapabilityMountCanary.class"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def extract_runtime(jar: Path, root: Path) -> Path:
    runtime = root / "runtime"
    runtime.mkdir(mode=0o700)
    seen: set[str] = set()
    with zipfile.ZipFile(jar) as source:
        for info in source.infolist():
            name = info.filename
            if not name.startswith(("BOOT-INF/classes/", "BOOT-INF/lib/")):
                continue
            relative = PurePosixPath(name)
            mode = info.external_attr >> 16
            if relative.is_absolute() or any(part in ("", ".", "..") for part in relative.parts):
                raise ValueError("FAT_JAR_PATH_INVALID")
            if mode and (mode & 0o170000) == 0o120000:
                raise ValueError("FAT_JAR_SYMLINK_REJECTED")
            if name in seen:
                raise ValueError("FAT_JAR_DUPLICATE_ENTRY")
            seen.add(name)
            target = runtime.joinpath(*relative.parts)
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True, mode=0o700)
                continue
            target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            with source.open(info) as input_stream, target.open("xb") as output_stream:
                shutil.copyfileobj(input_stream, output_stream, 1024 * 1024)
            target.chmod(0o600)
    runtime_adapter = runtime / "BOOT-INF/classes/com/agentplatform/infrastructure/workspace/DockerSandboxRuntime.class"
    if not runtime_adapter.is_file():
        nested = runtime / "BOOT-INF/lib/platform-infrastructure-0.1.0-SNAPSHOT.jar"
        if not nested.is_file():
            raise ValueError("RUNTIME_ADAPTER_MISSING")
        with zipfile.ZipFile(nested) as archive:
            if "com/agentplatform/infrastructure/workspace/DockerSandboxRuntime.class" not in archive.namelist():
                raise ValueError("RUNTIME_ADAPTER_MISSING")
    return runtime


def run(command: list[str], *, cwd: Path) -> str:
    result = subprocess.run(command, cwd=cwd, text=True, capture_output=True, check=False)
    if result.returncode:
        raise RuntimeError("CANARY_PACKAGE_STEP_FAILED: " + Path(command[0]).name + " exit=" + str(result.returncode))
    return (result.stdout + result.stderr).strip()


def build(source_jar: Path, cache: Path) -> Path:
    source_jar = source_jar.resolve(strict=True)
    cache = cache.resolve()
    if not source_jar.is_file() or source_jar.is_symlink() or not SOURCE.is_file():
        raise ValueError("CANARY_PACKAGE_INPUT_INVALID")
    if cache != (REPO / ".cache").resolve():
        raise ValueError("CANARY_PACKAGE_CACHE_INVALID")
    cache.mkdir(parents=True, exist_ok=True)
    source_hash = sha256(source_jar)
    probe_hash = hashlib.sha256(SOURCE.read_bytes()).hexdigest()
    with tempfile.TemporaryDirectory(prefix="r62-java-build-", dir=cache) as temporary:
        temp = Path(temporary)
        runtime = extract_runtime(source_jar, temp)
        compiled = temp / "compiled"
        compiled.mkdir(mode=0o700)
        classpath = str(runtime / "BOOT-INF/classes") + os.pathsep + str(runtime / "BOOT-INF/lib/*")
        javac = shutil.which("javac")
        java = shutil.which("java")
        if not javac or not java:
            raise RuntimeError("JDK_21_REQUIRED")
        javac_version = run([javac, "-version"], cwd=REPO)
        if not javac_version.endswith("21.0.10") and not javac_version.endswith("21"):
            raise RuntimeError("JDK_21_REQUIRED")
        run([javac, "-encoding", "UTF-8", "--release", "21", "-cp", classpath,
             "-d", str(compiled), str(SOURCE)], cwd=REPO)
        probe_class = compiled / "com/agentplatform/app/workspace/R62CapabilityMountCanary.class"
        if not probe_class.is_file():
            raise RuntimeError("CANARY_CLASS_NOT_COMPILED")
        self_check = run([java, "-Xms32m", "-Xmx128m", "-cp", classpath + os.pathsep + str(compiled),
                          "com.agentplatform.app.workspace.R62CapabilityMountCanary", "--self-check"], cwd=REPO)
        if "R62_JAVA_CANARY_READY" not in self_check:
            raise RuntimeError("CANARY_RUNTIME_SELF_CHECK_FAILED")
        output = temp / "platform-canary.jar"
        with zipfile.ZipFile(source_jar) as source, zipfile.ZipFile(output, "w") as result:
            for info in source.infolist():
                if info.filename == ENTRY:
                    continue
                result.writestr(info, source.read(info.filename))
            result.writestr(ENTRY, probe_class.read_bytes(), compress_type=zipfile.ZIP_DEFLATED)
        with zipfile.ZipFile(output) as verify:
            matches = [item for item in verify.infolist() if item.filename == ENTRY]
            if len(matches) != 1 or verify.read(ENTRY) != probe_class.read_bytes():
                raise RuntimeError("CANARY_ARCHIVE_CLASS_MISMATCH")
        output_hash = sha256(output)
        destination = cache / ("r62-java-artifact-" + output_hash[:12])
        if destination.exists():
            existing = destination / "platform-canary.jar"
            if not existing.is_file() or sha256(existing) != output_hash:
                raise RuntimeError("CANARY_ARTIFACT_ID_COLLISION")
        else:
            destination.mkdir(mode=0o700)
            final_jar = destination / "platform-canary.jar"
            shutil.copyfile(output, final_jar)
            final_jar.chmod(0o600)
        manifest = {
            "sourceJarSha256": source_hash,
            "probeSourceSha256": probe_hash,
            "canaryJarSha256": output_hash,
            "canaryJarBytes": output.stat().st_size,
            "javac": javac_version,
            "selfCheck": "PASS",
        }
        (destination / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        (destination / "manifest.json").chmod(0o600)
        return destination / "platform-canary.jar"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=REPO / "backend/platform-app/target/platform-app-0.1.0-SNAPSHOT.jar")
    args = parser.parse_args()
    result = build(args.jar, REPO / ".cache")
    print(json.dumps({"status": "PASS", "jar": str(result), "sha256": sha256(result)}))


if __name__ == "__main__":
    main()
