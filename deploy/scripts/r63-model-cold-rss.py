"""Measure one cold local embedding load on the isolated R6.3 API.

This control-plane probe restarts only the R63 API container, performs one
semantic search with the local ONNX model, and records cgroup memory samples.
It never starts a local sandbox or contacts a model supplier.
"""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / ".cache/r63-local"
FIXTURE = DATA / "fixture"
API = "agentplatform-r63-local-api-1"


def docker(*args: str) -> str:
    result = subprocess.run(["docker", "--context", "desktop-linux", *args],
                            text=True, capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError("R63 cold-model probe Docker query failed; inspect protected process output")
    return result.stdout.strip()


def bytes_from(value: str) -> int:
    match = re.fullmatch(r"\s*([0-9]+(?:\.[0-9]+)?)\s*(B|KiB|MiB|GiB)\s*", value)
    if not match:
        raise RuntimeError("Docker returned an unknown memory unit")
    return int(float(match.group(1)) * {"B": 1, "KiB": 1024, "MiB": 1024**2, "GiB": 1024**3}[match.group(2)])


def memory_sample() -> dict:
    raw = docker("stats", "--no-stream", "--format", "{{json .}}", API)
    row = json.loads(raw)
    used = row["MemUsage"].split(" / ", 1)
    return {"usedBytes": bytes_from(used[0]), "limitBytes": bytes_from(used[1]),
            "usedDisplay": used[0], "limitDisplay": used[1]}


def process_rss_sample() -> dict:
    raw = docker("exec", API, "sh", "-lc",
                 "grep -E '^(Name|VmRSS|VmHWM|RssAnon|RssFile|RssShmem):' /proc/1/status")
    values = {}
    for line in raw.splitlines():
        key, value = line.split(":", 1)
        parts = value.strip().split()
        if key == "Name":
            values[key] = parts[0]
        elif len(parts) == 2 and parts[1] == "kB":
            values[key] = int(parts[0]) * 1024
    if values.get("Name") != "java" or not all(key in values for key in ("VmRSS", "VmHWM")):
        raise RuntimeError("Could not read the Java process RSS from /proc/1/status")
    return values


def client_class():
    spec = importlib.util.spec_from_file_location("r63_acceptance", ROOT / "deploy/scripts/r63-knowledge-acceptance.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.Client


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--case-id", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,31}", args.case_id):
        raise RuntimeError("Case identity must be a short lowercase identifier")
    manifest = json.loads((FIXTURE / "manifest.json").read_text(encoding="utf-8"))
    receipt_path = DATA / f"model-cold-rss-{manifest['database']}-{args.case_id}.json"
    if receipt_path.exists():
        raise RuntimeError("Cold model receipt is immutable once written")
    inspect = json.loads(docker("inspect", API))[0]
    expected_image = inspect["Image"]
    if inspect["State"]["Status"] != "running":
        raise RuntimeError("R63 API must be running before the cold-load measurement")

    before_restart = memory_sample()
    docker("restart", API)
    deadline = time.monotonic() + 240
    while time.monotonic() < deadline:
        health = json.loads(docker("inspect", API))[0]["State"].get("Health", {}).get("Status")
        if health == "healthy":
            break
        time.sleep(2)
    else:
        raise RuntimeError("R63 API did not return healthy after its isolated restart")

    after_start = memory_sample()
    rss_after_start = process_rss_sample()
    Client = client_class()
    client = Client("r63_admin")
    bases = client.call("GET", "/api/v1/knowledge-bases")
    if not bases:
        raise RuntimeError("R63 admin needs its existing isolated knowledge-base fixture")
    result = client.call("POST", "/api/v1/knowledge/search",
                         {"query": "R63 cold local vector model capacity check",
                          "baseIds": [bases[0]["id"]], "limit": 1, "semanticRequired": True})
    if not result.get("semanticAvailable"):
        raise RuntimeError("The local semantic encoder did not complete the cold-load request")
    after_inference = memory_sample()
    rss_after_inference = process_rss_sample()
    current = json.loads(docker("inspect", API))[0]
    if current["Id"] != inspect["Id"] or current["Image"] != expected_image:
        raise RuntimeError("API identity changed during the cold-load probe")
    receipt = {
        "status": "PASS_LIMITED",
        "database": manifest["database"],
        "apiContainerId": inspect["Id"],
        "apiImageConfigId": expected_image,
        "measurements": {
            "beforeRestartWarmUsage": before_restart,
            "afterColdStartBeforeSearch": after_start,
            "afterFirstSemanticSearch": after_inference,
            "javaProcessRssAfterColdStart": rss_after_start,
            "javaProcessRssAfterFirstSemanticSearch": rss_after_inference,
            "firstSemanticSearchRssDeltaBytes": max(0, rss_after_inference["VmRSS"] - rss_after_start["VmRSS"]),
            "coldStartSearchDeltaBytes": max(0, after_inference["usedBytes"] - after_start["usedBytes"]),
        },
        "semanticSearchAccepted": True,
        "supplierCalls": 0,
        "localSandboxStarted": False,
    }
    payload = json.dumps(receipt, indent=2) + "\n"
    receipt_path.write_text(payload, encoding="utf-8")
    digest = hashlib.sha256(receipt_path.read_bytes()).hexdigest()
    print(json.dumps({"status": receipt["status"], "receipt": str(receipt_path), "sha256": digest,
                      "coldStartSearchDeltaBytes": receipt["measurements"]["coldStartSearchDeltaBytes"],
                      "supplierCalls": 0, "localSandboxStarted": False}))


if __name__ == "__main__":
    main()
