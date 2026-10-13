# R0.2 AgentScope contracts

## Why
R0.1 supplies a verified engineering shell but no runtime proof that AgentScope 2.0.4 obeys platform commit, permission, restoration and completion boundaries.

## What Changes
- Add HC-1 pure types/ports, build-time composition and a disabled-by-default AgentScope adapter.
- Probe the real SDK using deterministic gateways/tools and durable test fixtures, including process recovery and CAS.
- Add offline non-root Linux probe acceptance to the existing required CI context and retain shell regression.

## Impact
No new public APIs/events, database migrations, real supplier calls, legacy changes or production deployment. Real models belong to R1, production storage to R0.3. Fake dependencies and real SDK evidence are explicitly distinguished.
