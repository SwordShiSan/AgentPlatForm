# R5.4 workbench, attachments and release

## Why
Chat currently rejects attachments and the 8K context ceiling prevents practical document work. R5 file and preview capabilities require an integrated workbench and a production release with a data-safe R4 fallback.

## What Changes
- Add V21 durable independent chat attachments, extraction, typed source references and per-call context reservations; preserve V1–V20 and schema1 runtime baseline.
- Freeze a 1M model capability with 256K default and 64K–512K configurable task window, 4096 output default and explicit safety margin, and retain old bundle/accounting semantics.
- Process text/PDF/OOXML in the isolated renderer through reliable asset jobs; use Tika 3.3.3 for Office and existing PDFBox. Keep the heavy slot shared with sandbox, with precise stop and UNKNOWN rules.
- Add attachment upload, extraction, preview and storage projections; integrate workbench, chat, version-pinned artifacts and responsive UI.
- Validate on independent fixtures, promote via protected PRs, then release the same artifacts with maintenance-only snapshot rollback and R4 data protection.

## Impact
No OCR, automatic summarization, permanent deletion, new Agent loop, cloud sandbox or R6. Ordinary user daily 1M token quota remains independently configurable; administrator is unlimited but metered.
