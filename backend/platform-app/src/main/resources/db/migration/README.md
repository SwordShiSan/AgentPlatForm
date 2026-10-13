# Migration boundary

Flyway dependencies are BOM-managed. R0.1 creates no schema or business table and requires no database. R0.3 will establish isolated pf/pf_runtime schemas and a single migration job before application startup. Never connect this shell to the legacy database.
