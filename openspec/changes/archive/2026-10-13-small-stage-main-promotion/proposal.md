## Why

The current workflow treated `main` as production-only and delayed its update until an entire major phase had passed cloud validation. The user has clarified that every individually stable small phase must advance `main` after local Docker acceptance, while production release remains a separate major-phase gate.

## What Changes

- Define `main` as the latest locally verified small-phase code, with production tracked separately by immutable tag and image digest.
- Require a phase integration branch and an individual step branch for each small phase; after local acceptance, review step→phase and phase→main PRs, then synchronize main back into phase.
- Keep cloud deployment, online observation, production tagging, and major-phase RELEASED status at the major-phase boundary.
- On cloud failure, roll back production only; preserve locally stable main and repair through a new step branch and normal gates.
- Align the reconstruction runbooks, progress ledgers, repository instructions, and platform skill.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `reconstruction-governance`: separate per-small-phase local stable promotion from per-major-phase production release.

## Impact

Documentation and repository governance only: the canonical reconstruction plan, progress control, deployment/release instructions, `AgentPlatForm/AGENTS.md`, this OpenSpec spec, and the local `ai-agent-platform` skill. No runtime code, database, deployment, remote branch, or production system is changed.
