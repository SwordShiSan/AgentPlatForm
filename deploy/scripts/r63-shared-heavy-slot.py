"""Verify the shared heavyweight-slot admission without a local sandbox.

The isolated R63 database is made to represent an already-held SANDBOX slot;
the real API then attempts semantic retrieval and must refuse before inference.
The fixture owner is released in a guarded finally block. No Docker sandbox is
started and no provider call is made.
"""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import re
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / ".cache/r63-local"
FIXTURE = DATA / "fixture"
POSTGRES = "agentplatform-r63-local-postgres-1"
API = "agentplatform-r63-local-api-1"


def docker(*args: str, input_text: str | None = None) -> str:
    result = subprocess.run(["docker", "--context", "desktop-linux", *args], text=True,
                            input=input_text, capture_output=True, timeout=45)
    if result.returncode:
        raise RuntimeError("R63 isolated shared-slot probe failed; inspect protected process output")
    return result.stdout.strip()


def sql(statement: str) -> str:
    # Quiet mode suppresses psql's command tags (for example, `UPDATE 1`), so
    # callers can compare RETURNING output exactly and always run guarded cleanup.
    return docker("exec", "-i", POSTGRES, "psql", "-X", "-q", "-A", "-t", "-v", "ON_ERROR_STOP=1",
                  "-U", "postgres", "-d", json.loads((FIXTURE / "manifest.json").read_text())["database"],
                  input_text=statement)


def load_client():
    spec = importlib.util.spec_from_file_location("r63_knowledge_acceptance", ROOT / "deploy/scripts/r63-knowledge-acceptance.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.Client, module.Failure


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--case-id", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,31}", args.case_id):
        raise RuntimeError("Case identity must be a short lowercase identifier")
    manifest = json.loads((FIXTURE / "manifest.json").read_text(encoding="utf-8"))
    receipt_path = DATA / f"shared-heavy-slot-{manifest['database']}-{args.case_id}.json"
    if receipt_path.exists():
        raise RuntimeError("Shared-slot receipt is immutable once written")
    api = json.loads(docker("inspect", API))[0]
    if api["State"]["Status"] != "running":
        raise RuntimeError("R63 API must be running")
    idle = sql("SELECT kind IS NULL AND reference_id IS NULL AND owner_id IS NULL AND lease_until IS NULL FROM pf.heavy_runtime_slot WHERE id=1;")
    if idle != "t":
        raise RuntimeError("The shared slot is already owned; do not overwrite its persisted state")

    marker = uuid.uuid4()
    acquired = False
    search_denied = False
    denial_code = None
    try:
        changed = sql("UPDATE pf.heavy_runtime_slot SET kind='SANDBOX',reference_id='" + str(marker) +
                      "',generation=1,owner_id=NULL,lease_until=clock_timestamp()+interval '10 minutes',fence=fence+1,revision=revision+1 "
                      "WHERE id=1 AND kind IS NULL AND reference_id IS NULL RETURNING reference_id::text;")
        # Set this before validating the output: if psql returns the row plus
        # incidental output, the finally block must still release our exact
        # isolated marker rather than leaving the test slot occupied.
        acquired = str(marker) in changed.splitlines()
        if changed != str(marker):
            raise RuntimeError("Could not acquire the isolated synthetic SANDBOX slot")
        Client, Failure = load_client()
        client = Client("r63_admin")
        bases = client.call("GET", "/api/v1/knowledge-bases")
        if not bases:
            raise RuntimeError("R63 administrator has no knowledge-base search fixture")
        try:
            client.call("POST", "/api/v1/knowledge/search",
                        {"query": "R63 shared heavy-slot admission", "baseIds": [bases[0]["id"]],
                         "limit": 1, "semanticRequired": True})
        except Failure as failure:
            search_denied = failure.status == 423 and failure.code == "HEAVY_RUNTIME_BUSY"
            denial_code = failure.code
            if not search_denied:
                raise
        if not search_denied:
            raise AssertionError("Knowledge retrieval ran while the shared SANDBOX slot was occupied")
    finally:
        if acquired:
            released = sql("UPDATE pf.heavy_runtime_slot SET kind=NULL,reference_id=NULL,generation=NULL,owner_id=NULL,lease_until=NULL,revision=revision+1 "
                           "WHERE id=1 AND kind='SANDBOX' AND reference_id='" + str(marker) + "' RETURNING id;")
            if released != "1":
                raise RuntimeError("Guarded synthetic sandbox-slot release did not match its owner")
    if sql("SELECT kind IS NULL AND reference_id IS NULL AND owner_id IS NULL AND lease_until IS NULL FROM pf.heavy_runtime_slot WHERE id=1;") != "t":
        raise RuntimeError("Shared heavy slot did not return to its idle state")

    receipt = {"status": "PASS_LIMITED", "database": manifest["database"],
               "apiContainerId": api["Id"], "apiImageConfigId": api["Image"],
               "syntheticOwner": str(marker), "simulatedHeldKind": "SANDBOX",
               "semanticSearchRejected": search_denied, "stableError": denial_code,
               "slotIdleAfterProbe": True, "localSandboxStarted": False, "supplierCalls": 0}
    receipt_path.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    digest = hashlib.sha256(receipt_path.read_bytes()).hexdigest()
    print(json.dumps({"status": receipt["status"], "receipt": str(receipt_path), "sha256": digest,
                      "stableError": denial_code, "localSandboxStarted": False}))


if __name__ == "__main__":
    main()
