# Migration boundary

Flyway dependencies are BOM-managed. The new platform uses isolated `pf`/`pf_runtime` schemas and a single migration job before application startup. Never connect it to the legacy database.

V14 extends the existing environment operation ledger for lifecycle intent, drain handoff, immutable physical targets, observation and cleanup facts. It preserves V1–V13 and runtime baseline schema1. Historical V2 operations retain their protocol and kind; no delete or cleanup success is synthesized. New V3 rows must explicitly freeze their target and intent. The operation remains the state authority; cleanup rows are evidence. This migration alone does not expose lifecycle APIs or prove runtime acceptance.
