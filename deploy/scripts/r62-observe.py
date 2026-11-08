#!/usr/bin/env python3
"""Collect one bounded resource observation for the isolated R6.2 fixture."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / ".cache" / "r62-local"
PROJECT = "agentplatform-r62-local"
EXPECTED = {
    "api", "worker", "gateway", "fixture", "mcp-fixture", "mcp-proxy",
    "seaweedfs", "rabbitmq", "postgres",
}


def call(args: list[str]) -> str:
    result = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace",
                            timeout=30, check=False)
    if result.returncode:
        raise RuntimeError("OBSERVATION_COMMAND_FAILED:" + Path(args[0]).name)
    return result.stdout.strip()


def running() -> list[dict]:
    raw = call(["docker", "ps", "--filter", f"label=com.docker.compose.project={PROJECT}",
                "--format", "{{json .}}"])
    rows = [json.loads(line) for line in raw.splitlines() if line.strip()]
    services = {row["Names"].removeprefix(PROJECT + "-").rsplit("-", 1)[0] for row in rows}
    if services != EXPECTED:
        raise RuntimeError("OBSERVATION_SERVICE_SET_CHANGED")
    return rows


def parse_bytes(value: str) -> int:
    match = re.fullmatch(r"([0-9.]+)(B|kB|KB|KiB|MB|MiB|GB|GiB)", value.strip())
    if not match:
        raise RuntimeError("OBSERVATION_MEMORY_FORMAT_UNKNOWN")
    amount = float(match.group(1))
    factor = {"B": 1, "kB": 1000, "KB": 1000, "KiB": 1024, "MB": 1000**2,
              "MiB": 1024**2, "GB": 1000**3, "GiB": 1024**3}[match.group(2)]
    return int(amount * factor)


def sample() -> dict:
    rows = running()
    ids = [row["ID"] for row in rows]
    stats_raw = call(["docker", "stats", "--no-stream", "--format", "{{json .}}", *ids])
    stats = [json.loads(line) for line in stats_raw.splitlines() if line.strip()]
    by_name = {item["Name"]: item for item in stats}
    if len(by_name) != len(EXPECTED):
        raise RuntimeError("OBSERVATION_STATS_INCOMPLETE")
    usage = {}
    for row in rows:
        service = row["Names"].removeprefix(PROJECT + "-").rsplit("-", 1)[0]
        item = by_name.get(row["Names"])
        if item is None:
            raise RuntimeError("OBSERVATION_CONTAINER_MISSING")
        current = item["MemUsage"].split("/", 1)[0].strip()
        usage[service] = parse_bytes(current)
    inspected = json.loads(call(["docker", "inspect", *ids]))
    if any(container["State"].get("OOMKilled") or container["State"].get("Status") != "running"
           for container in inspected):
        raise RuntimeError("OBSERVATION_CONTAINER_UNHEALTHY")
    return {"at": datetime.now(timezone.utc).isoformat(), "containerMemoryBytes": usage,
            "totalContainerMemoryBytes": sum(usage.values())}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seconds", type=int, choices=(180, 300), default=180)
    parser.add_argument("--interval", type=int, choices=(15, 30), default=15)
    args = parser.parse_args()
    DATA.mkdir(parents=True, exist_ok=True)
    images = json.loads((DATA / "images.json").read_text(encoding="utf-8"))
    start = time.monotonic()
    samples = []
    while True:
        samples.append(sample())
        elapsed = time.monotonic() - start
        if elapsed >= args.seconds:
            break
        time.sleep(min(args.interval, args.seconds - elapsed))
    output = {
        "status": "PASS",
        "sourceRevision": images["source"],
        "buildInputHash": images["inputHash"],
        "imageConfigIds": {name: value["configId"] for name, value in images["images"].items()},
        "durationSeconds": round(time.monotonic() - start),
        "sampleCount": len(samples),
        "samples": samples,
        "maximumSampledContainerMemoryBytes": max(item["totalContainerMemoryBytes"] for item in samples),
        "observationScope": "sampled Docker container usage; not an absolute peak or concurrency claim",
        "supplierCalls": 0,
    }
    target = DATA / ("observe-" + str(time.time_ns()) + ".json")
    target.write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    print(json.dumps({"status": output["status"], "durationSeconds": output["durationSeconds"],
                      "sampleCount": output["sampleCount"],
                      "maxSampledMiB": round(output["maximumSampledContainerMemoryBytes"] / 1024**2, 1),
                      "sourceRevision": output["sourceRevision"], "evidenceSha256": digest,
                      "evidence": str(target.relative_to(ROOT))}))


if __name__ == "__main__":
    main()
