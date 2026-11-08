"""Verify a failed knowledge rebuild retains its previous active index.

This probe is restricted to the dedicated local R63 Compose project and the
clearly labelled pre-extracted fixture. It briefly mismatches only the fixture
extraction's expected hash, waits for the durable rebuild to fail, then restores
the original expected hash. It never changes object bytes or production data.
"""
from pathlib import Path
import hashlib
import importlib.util
import json
import re
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / ".cache/r63-local"
PRIVATE = DATA / "fixture"
POSTGRES = "agentplatform-r63-local-postgres-1"
FIXTURE_BUNDLE = "r63-fixture-pre-extracted-text-v1"


def load_acceptance():
    spec = importlib.util.spec_from_file_location(
        "r63_knowledge_acceptance", ROOT / "deploy/scripts/r63-knowledge-acceptance.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def docker(*args, input_text=None):
    result = subprocess.run(["docker", "--context", "desktop-linux", *args], text=True,
                            input=input_text, capture_output=True, timeout=45)
    if result.returncode:
        detail = (result.stderr or "").strip().splitlines()
        safe_detail = detail[-1][-240:] if detail else "no diagnostic text"
        raise RuntimeError(f"Isolated R63 index-retention probe failed ({result.returncode}): {safe_detail}")
    return result.stdout.strip()


def sql(database, statement):
    return docker("exec", "-i", POSTGRES, "psql", "-X", "-q", "-A", "-F", "|", "-t",
                  "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", database, input_text=statement)


def lit(value):
    return "'" + str(value).replace("'", "''") + "'"


def resume_quarantined_delivery(database, state_path, receipt_path):
    state = json.loads(state_path.read_text(encoding="utf-8"))
    if state.get("phase") != "RECONCILIATION_REQUIRED" or state.get("database") != database:
        raise RuntimeError("Resume requires the exact preserved reconciliation journal")
    document = state["documentId"]
    extraction = state["extractionId"]
    expected_sha = state["originalExpectedSha256"]
    wrong_sha = state["wrongExpectedSha256"]
    active_version = state["activeIndexVersion"]
    base_id = sql(database, f"SELECT knowledge_base_id FROM pf.knowledge_document WHERE id={lit(document)};")
    if not base_id:
        raise RuntimeError("The isolated document no longer has its source knowledge base")
    state["baseId"] = base_id
    hashes = sql(database, f"""
SELECT e.output_sha256||'|'||d.extraction_sha256
FROM pf.document_extraction e JOIN pf.knowledge_document d ON d.extraction_id=e.id
WHERE e.id={lit(extraction)} AND d.id={lit(document)} AND e.parser_bundle={lit(FIXTURE_BUNDLE)};
""")
    if hashes != wrong_sha + "|" + wrong_sha:
        raise RuntimeError("The exact injected hash pair changed; refuse to resume this delivery")

    row = sql(database, f"""
SELECT b.id,b.state,coalesce(b.owner_id::text,''),b.index_version,b.generation,
 d.active_index_version,d.status,o.id,o.space_id,o.state,coalesce(o.relay_owner::text,''),o.generation,
 (SELECT count(*) FROM pf.knowledge_index_inbox i WHERE i.build_id=b.id),
 (SELECT count(*) FROM pf.knowledge_index_chunk c WHERE c.document_id=b.document_id AND c.index_version=b.index_version)
FROM pf.knowledge_index_build b
JOIN pf.knowledge_document d ON d.id=b.document_id AND d.space_id=b.space_id
JOIN pf.knowledge_index_outbox o ON o.build_id=b.id AND o.generation=b.generation
WHERE b.document_id={lit(document)} ORDER BY b.created_at DESC LIMIT 1;
""")
    fields = row.split("|") if row else []
    if len(fields) != 14:
        raise RuntimeError("The preserved build/outbox identity is unavailable")
    build_id,state_name,owner,index_version,generation,active,doc_state,outbox_id,space_id,outbox_state,relay_owner,outbox_generation,inbox_count,chunk_count=fields
    if (state_name != "QUEUED" or owner or active != active_version or doc_state != "BUILDING"
            or index_version == active_version or outbox_state != "PUBLISHED" or not relay_owner
            or generation != outbox_generation or inbox_count != "0" or chunk_count != "0"):
        raise RuntimeError("The build is no longer proven unclaimed; do not requeue")
    slot = sql(database, "SELECT kind IS NULL AND reference_id IS NULL AND owner_id IS NULL AND lease_until IS NULL FROM pf.heavy_runtime_slot WHERE id=1;")
    if slot != "t":
        raise RuntimeError("The shared heavyweight slot is not idle; do not requeue")

    payload = json.dumps({"id": outbox_id, "buildId": build_id, "spaceId": space_id,
                          "generation": int(generation), "relayOwner": relay_owner},
                         separators=(",", ":")).encode("utf-8")
    payload_hash = hashlib.sha256(payload).hexdigest()
    last_quarantine = sql(database, "SELECT body_hash FROM pf.knowledge_index_delivery_quarantine ORDER BY created_at DESC LIMIT 1;")
    if last_quarantine != payload_hash:
        raise RuntimeError("The latest quarantine receipt does not match the exact original delivery")

    requeued = sql(database, f"""
UPDATE pf.knowledge_index_outbox SET state='PENDING',relay_owner=NULL,relay_until=NULL
WHERE id={lit(outbox_id)} AND build_id={lit(build_id)} AND generation={int(generation)}
 AND state='PUBLISHED' AND relay_owner={lit(relay_owner)} RETURNING id;
""")
    if requeued != outbox_id:
        raise RuntimeError("Could not requeue exactly the proven quarantined outbox")
    state.update(phase="RESUMED_KNOWN_QUARANTINED_DELIVERY", buildId=build_id,
                 outboxId=outbox_id, quarantinedPayloadSha256=payload_hash)
    state_path.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")

    deadline = time.monotonic() + 120
    terminal = None
    while time.monotonic() < deadline:
        current = sql(database, f"""
SELECT b.id,b.state,b.error_code,b.index_version,d.active_index_version,d.status
FROM pf.knowledge_index_build b JOIN pf.knowledge_document d ON d.id=b.document_id AND d.space_id=b.space_id
WHERE b.id={lit(build_id)};
""")
        if current:
            values = current.split("|")
            if len(values) == 6 and values[1] in {"FAILED", "COMMITTED", "BLOCKED"}:
                terminal = {"buildId": values[0], "state": values[1], "errorCode": values[2],
                            "indexVersion": values[3], "activeIndexVersion": values[4], "documentState": values[5]}
                break
        time.sleep(1)
    if terminal is None:
        state["phase"] = "RECONCILIATION_REQUIRED"
        state_path.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
        raise RuntimeError("The original build remains nonterminal; input hash stays frozen")

    current_hashes = sql(database, f"""
SELECT e.output_sha256||'|'||d.extraction_sha256
FROM pf.document_extraction e JOIN pf.knowledge_document d ON d.extraction_id=e.id
WHERE e.id={lit(extraction)} AND d.id={lit(document)} AND e.parser_bundle={lit(FIXTURE_BUNDLE)};
""")
    if current_hashes == wrong_sha + "|" + wrong_sha:
        restored = sql(database, f"""
BEGIN;
SET CONSTRAINTS ALL DEFERRED;
UPDATE pf.document_extraction SET output_sha256={lit(expected_sha)}
WHERE id={lit(extraction)} AND parser_bundle={lit(FIXTURE_BUNDLE)} AND output_sha256={lit(wrong_sha)} RETURNING id;
UPDATE pf.knowledge_document SET extraction_sha256={lit(expected_sha)}
WHERE id={lit(document)} AND extraction_id={lit(extraction)} AND extraction_sha256={lit(wrong_sha)} RETURNING id;
COMMIT;
""")
        if set(restored.splitlines()) != {extraction, document}:
            raise RuntimeError("Could not restore the original fixture hash pair")
    elif current_hashes != expected_sha + "|" + expected_sha:
        raise RuntimeError("Unexpected fixture hash pair after terminal build; manual reconciliation required")

    api = load_acceptance()
    client = api.Client("r63_admin")
    documents = client.call("GET", f"/api/v1/knowledge-bases/{base_id}/documents")
    active_doc = next((item for item in documents if item["id"] == document), None)
    if terminal["state"] != "FAILED" or terminal["activeIndexVersion"] != active_version \
            or terminal["documentState"] != "READY" or active_doc is None \
            or active_doc["activeIndexVersion"] != active_version or active_doc["state"] != "READY":
        raise AssertionError("Failed rebuild changed or hid the previous active index")
    search = client.call("POST", "/api/v1/knowledge/search",
                         {"query": "北极星流程 实时权限 持久化修改", "baseIds": [base_id],
                          "limit": 8, "semanticRequired": True})
    if not any("R63_NORTHSTAR_731" in hit["text"] for hit in search["hits"]):
        raise AssertionError("The previous active index is no longer searchable")

    state.update(phase="COMPLETED", fixtureHashRestored=True, failedBuild=terminal)
    state_path.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
    receipt = {"status": "PASS_LIMITED", "database": database, "documentId": document,
               "initialActiveIndexVersion": active_version, "failedBuild": terminal,
               "originalDeliveryRequeuedOnlyAfterNoExecutionProof": True,
               "quarantinedPayloadSha256": payload_hash, "oldIndexStillSearchable": True,
               "fixtureHashRestored": True, "supplierCalls": 0, "localSandboxStarted": False,
               "scope": "FAILED_REBUILD_PRESERVES_ACTIVE_INDEX; PREEXTRACTED_FIXTURE"}
    receipt_path.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    digest = hashlib.sha256(receipt_path.read_bytes()).hexdigest()
    print(json.dumps({"status": receipt["status"], "receipt": str(receipt_path),
                      "sha256": digest, "oldIndexStillSearchable": True}))


def main():
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--confirm-isolated-db", required=True)
    parser.add_argument("--resume-existing", action="store_true")
    args = parser.parse_args()
    manifest = json.loads((PRIVATE / "manifest.json").read_text(encoding="utf-8"))
    database = manifest["database"]
    if args.confirm_isolated_db != database or not re.fullmatch(r"r63_[a-f0-9]{12}", database):
        raise RuntimeError("Refusing to modify anything except the named isolated R63 database")
    state_path = DATA / f"index-retention-{database}.json"
    receipt_path = DATA / f"index-retention-result-{database}.json"
    if receipt_path.exists():
        raise RuntimeError("Index-retention receipt is immutable once written")
    if args.resume_existing:
        if not state_path.is_file():
            raise RuntimeError("No preserved interrupted test identity is available")
        resume_quarantined_delivery(database, state_path, receipt_path)
        return
    if state_path.exists():
        previous = json.loads(state_path.read_text(encoding="utf-8"))
        if previous.get("phase") != "PREPARED" or previous.get("database") != database:
            raise RuntimeError("A prior mutation may be unresolved; reconcile its original identity instead of retrying")
        actual = sql(database, f"""
SELECT e.output_sha256||'|'||d.extraction_sha256
FROM pf.document_extraction e JOIN pf.knowledge_document d ON d.extraction_id=e.id
WHERE e.id={lit(previous['extractionId'])} AND d.id={lit(previous['documentId'])} AND e.parser_bundle={lit(FIXTURE_BUNDLE)};
""")
        if actual != previous.get("originalExpectedSha256") + "|" + previous.get("originalExpectedSha256"):
            raise RuntimeError("Fixture metadata differs from the pre-mutation journal; manual reconciliation is required")
        archive = state_path.with_name(state_path.stem + f"-pre-mutation-failure-{time.time_ns()}.json")
        state_path.replace(archive)

    row = sql(database, """
SELECT d.id,d.knowledge_base_id,d.active_index_version,d.status,e.id,e.output_sha256,e.parser_bundle
FROM pf.knowledge_document d JOIN pf.document_extraction e ON e.id=d.extraction_id
WHERE d.display_name='r63-knowledge-evidence.txt' AND e.parser_bundle='r63-fixture-pre-extracted-text-v1'
ORDER BY d.created_at DESC LIMIT 1;
""")
    parts = row.split("|") if row else []
    if len(parts) != 7:
        raise RuntimeError("The exact labelled pre-extracted R63 fixture was not found")
    document, base, active_version, doc_state, extraction, expected_sha, bundle = parts
    if doc_state != "READY" or not active_version or bundle != FIXTURE_BUNDLE or not re.fullmatch(r"[a-f0-9]{64}", expected_sha):
        raise RuntimeError("The fixture must have a READY document and a committed active index")

    api = load_acceptance()
    client = api.Client("r63_admin")
    listed = client.call("GET", f"/api/v1/knowledge-bases/{base}/documents")
    if not any(item["id"] == document and item["activeIndexVersion"] == active_version for item in listed):
        raise RuntimeError("The API view does not match the frozen active-index identity")

    request_id = str(uuid.uuid4())
    wrong_sha = "0" * 64 if expected_sha != "0" * 64 else "f" * 64
    state = {"phase": "PREPARED", "database": database, "documentId": document,
             "extractionId": extraction, "activeIndexVersion": active_version,
             "baseId": base,
             "originalExpectedSha256": expected_sha, "requestId": request_id,
             "wrongExpectedSha256": wrong_sha}
    state_path.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
    changed = sql(database, f"""
BEGIN;
SET CONSTRAINTS ALL DEFERRED;
UPDATE pf.document_extraction SET output_sha256={lit(wrong_sha)}
WHERE id={lit(extraction)} AND parser_bundle={lit(FIXTURE_BUNDLE)} AND status='READY'
 AND output_sha256={lit(expected_sha)} RETURNING id;
UPDATE pf.knowledge_document SET extraction_sha256={lit(wrong_sha)}
WHERE id={lit(document)} AND extraction_id={lit(extraction)} AND extraction_sha256={lit(expected_sha)} RETURNING id;
COMMIT;
""")
    if set(changed.splitlines()) != {extraction, document}:
        state_path.unlink()
        raise RuntimeError("Could not atomically change the exact isolated fixture hash pair")
    state["phase"] = "HASH_MISMATCHED"
    state_path.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")

    accepted = False
    failure_build = None
    try:
        # Persist SENT before the mutation request; never replay this request on an unknown outcome.
        state["phase"] = "REBUILD_SENT"
        state_path.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
        accepted = True  # A transport error after this point is an unknown outcome, never a replay signal.
        try:
            response = client.call("POST", f"/api/v1/knowledge-documents/{document}/rebuild", None,
                                  {"Idempotency-Key": request_id})
        except api.Failure as rejected:
            if 400 <= rejected.status < 500:
                accepted = False
            raise
        state["phase"] = "REBUILD_ACKNOWLEDGED"
        state["responseDocumentState"] = response.get("state")
        state_path.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")

        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            latest = sql(database, f"""
SELECT b.id,b.state,b.error_code,b.index_version,d.active_index_version,d.status
FROM pf.knowledge_index_build b JOIN pf.knowledge_document d ON d.id=b.document_id AND d.space_id=b.space_id
WHERE b.document_id={lit(document)} AND b.index_version<>{lit(active_version)}::uuid
ORDER BY b.created_at DESC LIMIT 1;
""")
            if latest:
                fields = latest.split("|")
                if len(fields) == 6 and fields[1] in {"FAILED", "COMMITTED", "BLOCKED"}:
                    failure_build = {"buildId": fields[0], "state": fields[1], "errorCode": fields[2],
                                     "indexVersion": fields[3], "activeIndexVersion": fields[4], "documentState": fields[5]}
                    break
            time.sleep(1)
        if failure_build is None:
            raise RuntimeError("Rebuild outcome remains pending; its request identity is preserved")
        if failure_build["state"] != "FAILED":
            raise AssertionError("Expected the deliberately mismatched extraction hash to fail the build")
        if failure_build["activeIndexVersion"] != active_version or failure_build["documentState"] != "READY":
            raise AssertionError("A failed rebuild changed or hid the prior active index")
    finally:
        # Restore only the exact synthetic mismatch. If a rebuild is still RUNNING/unknown, do not
        # mutate its input underneath the worker; preserve the journal for explicit reconciliation.
        current = sql(database, f"""
SELECT e.output_sha256||'|'||d.extraction_sha256
FROM pf.document_extraction e JOIN pf.knowledge_document d ON d.extraction_id=e.id
WHERE e.id={lit(extraction)} AND d.id={lit(document)} AND e.parser_bundle={lit(FIXTURE_BUNDLE)};
""")
        if current == wrong_sha + "|" + wrong_sha:
            if failure_build is None and accepted:
                state["phase"] = "RECONCILIATION_REQUIRED"
                state_path.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
                raise RuntimeError("Rebuild is not terminal; fixture hash remains frozen for reconciliation")
            restored = sql(database, f"""
BEGIN;
SET CONSTRAINTS ALL DEFERRED;
UPDATE pf.document_extraction SET output_sha256={lit(expected_sha)}
WHERE id={lit(extraction)} AND parser_bundle={lit(FIXTURE_BUNDLE)} AND output_sha256={lit(wrong_sha)} RETURNING id;
UPDATE pf.knowledge_document SET extraction_sha256={lit(expected_sha)}
WHERE id={lit(document)} AND extraction_id={lit(extraction)} AND extraction_sha256={lit(wrong_sha)} RETURNING id;
COMMIT;
""")
            if set(restored.splitlines()) != {extraction, document}:
                raise RuntimeError("Could not atomically restore the original labelled fixture hash pair")
        elif current != expected_sha + "|" + expected_sha:
            raise RuntimeError("Fixture hash pair has an unexpected mixed state; manual reconciliation is required")

    doc = client.call("GET", f"/api/v1/knowledge-bases/{base}/documents")
    active = next((item for item in doc if item["id"] == document), None)
    if active is None or active["activeIndexVersion"] != active_version or active["state"] != "READY":
        raise AssertionError("The old active index was not restored as the current searchable projection")
    search = client.call("POST", "/api/v1/knowledge/search",
                         {"query": "北极星流程 实时权限 持久化修改", "baseIds": [base], "limit": 8, "semanticRequired": True})
    if not any("R63_NORTHSTAR_731" in hit["text"] for hit in search["hits"]):
        raise AssertionError("The prior active index did not remain searchable after the failed rebuild")

    receipt = {"status": "PASS_LIMITED", "database": database, "documentId": document,
               "initialActiveIndexVersion": active_version, "failedBuild": failure_build,
               "oldIndexStillSearchable": True, "fixtureHashRestored": True,
               "supplierCalls": 0, "localSandboxStarted": False,
               "scope": "FAILED_REBUILD_PRESERVES_ACTIVE_INDEX; PREEXTRACTED_FIXTURE"}
    receipt_path.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    digest = hashlib.sha256(receipt_path.read_bytes()).hexdigest()
    print(json.dumps({"status": receipt["status"], "receipt": str(receipt_path),
                      "sha256": digest, "oldIndexStillSearchable": True}))


if __name__ == "__main__":
    main()
