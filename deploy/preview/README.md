# Isolated preview renderer

This is an independently packaged entrypoint of the existing app module. It has no API server, database client, S3 credentials, workspace mount or model key. The normal app artifact excludes renderer classes and Office dependencies; only the `preview-renderer` profile builds the classifier and its explicitly selected classpath.

## Build once, reuse exact payloads

1. Run `python deploy/scripts/fetch-preview-office.py` from the repository root. It caches LibreOffice26.2.6 under ignored `.cache/foundation`, verifies the upstream SHA256 and rejects an altered existing cache. The carrier can change; the accepted bytes cannot.
2. From `backend`, use the repository Maven wrapper with Java21 and `-Ppreview-renderer package`. The outputs are `platform-app/target/platform-app-0.1.0-SNAPSHOT-renderer.jar` and `renderer-libs/`.
3. Check `python deploy/scripts/preview-renderer-lock.py`. Only an explicit reviewed dependency change updates the lock with `--write`.
4. Build `deploy/preview-renderer.Dockerfile` locally using the actual source revision. Record image/config/archive identity separately; a worktree experiment label is not a release-source assertion.

The renderer uses JODConverter5.1.1, its official UNO client26.2.2, LibreOffice engine26.2.6, PDFBox3.0.8, TwelveMonkeys3.13.1 and jsoup1.23.2. UNO client and Office engine are different artifacts and need not have identical patch versions. Container package versions are recorded in `/opt/renderer-packages.txt`.

## Physical protocol

The runner admits one persistent command, stages and verifies its source, then mounts only that file read-only. The fixed entrypoint receives a controlled extension, output format and expected SHA256. The manifest and permitted output are written under bounded tmpfs. Office processes end before the immutable manifest is published; the entrypoint waits for the runner to read the result and stop the exact container.

Docker archive/cp does not reliably expose tmpfs. The runner therefore uses only fixed `/usr/bin/cat` exec reads for `manifest.properties`, `content.pdf`, `content.png` or `content.html`. Every exec identity is persisted before start. Stdout streams through bounded Docker Frame callbacks; manifest memory is capped at16KiB, document bytes go to a bounded private temporary file. No user command, target path or executable is accepted.

After all read callbacks and execs are stopped, the runner confirms the exact container exit, commits its signed stop receipt, and only then publishes S3/output facts and releases capacity. Closing an HTTP transport, receiving404 or expiry of a lease does not prove an existing container stopped. A result with unknown physical termination retains admission and is reconciled against the original command.

## Proof boundaries

`preview-renderer-smoke.py` defaults to runsc. On Docker Desktop, `--runtime runc --functional-only` permits a **format-library experiment only**; its receipt explicitly does not validate the signed durable pipeline or sandbox isolation. There is no production runc fallback. Actual runsc and shared-capacity evidence comes from the isolated Linux acceptance window.

The initial renderer cap is512MiB including256MiB tmpfs, CPU1/PIDs128/no swap expansion. It shares the existing heavy-operation gate with the business sandbox. Reuse current R4 isolation evidence only for unchanged components; never call a successful format experiment a complete R5.3 acceptance.
