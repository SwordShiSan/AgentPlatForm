# R5.3 durable preview pipeline

## Why
R5.2 supplies immutable versions and verified artifacts, but complex files still lack a durable, authorized preview. Preview must not change working files, bypass existing transfer admission, or expose an Office process in API/worker.

## What Changes
- Add V20 preview jobs/attempts, signed command/receipt, independent derived-object reservation/reference and shared heavy-runtime admission.
- Reuse transactional outbox, asset MQ jobs, MyBatis/Spring transactions, AWS SDK and signed runner. Run fixed JODConverter/LibreOffice/PDFBox/image/HTML conversion in an ephemeral gVisor container.
- Add version-pinned create/query/content/retry APIs and one shared Vue preview for current/history/artifact views; preserve original attachment downloads.
- Extend the fixed R53 launcher and targeted real-dependency/browser acceptance; zero supplier calls and three-minute observation.

## Impact
Six-module boundaries, runtime baseline1 and migrations V1-V19 remain unchanged. R5 is2/4 VERIFIED until acceptance and protected promotion; production staysR4. No R5.4, cloud sandbox, permanent Office service, source rebuild or production deployment.
