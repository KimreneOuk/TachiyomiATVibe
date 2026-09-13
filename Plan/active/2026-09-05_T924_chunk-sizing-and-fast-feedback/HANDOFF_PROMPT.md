# T924 handoff — CURRENT STATE (route complete)

> **Status (2026-09-12):** the route below is IMPLEMENTED, tested, and live on
> branch `t924/batch-profile-pipeline` (HEAD `439226e`; installed on the
> Director device as `0.17.1-452`). The original "do not authorize production
> implementation" gate is long passed. For what actually shipped, wave by wave,
> read `implementation-sequence.md` — it supersedes every planning-era doc in
> this folder. This file now serves as the architecture handoff for developers
> entering the codebase.

Read the package in the authoritative order in `README.md`. Do not start with
`design/chunk-sizing-options.md` or `design/batch-architecture-overview.md`; they
are superseded decision history.

The shipped architecture — ONE Batch coordinator, TWO engine lanes:

```text
validate/download
-> freeze run configuration
-> source/reuse plan
-> bounded full OCR preflight with atomic checkpoints      [BOTH lanes]
-> STANDARD lane: per-page batch translation via the legacy
   per-page machinery (no analysis, no profile, no glossary)
-> AI lane: hierarchical chapter analysis and reconciliation
   -> frozen Chapter Translation Profile
   -> global multi-budget whole-page envelope plan
   -> profile-aware translation with bounded structural recovery
-> translation/inpaint overlap                              [BOTH lanes]
-> color and persisted layout preparation
-> display-ready commit (translation-terminal; display via overlay,
   candidate snapshots, and FF-02 persisted layouts when enabled)
```

Engine category alone picks the lane (`profilePipelineDispatchKind`);
the FF-01 A/B flag and the legacy `SequentialBatchCoordinator` are deleted.

Manual/Auto remains latency-oriented. Preserve page leases, candidate versus
committed display, user-edit authority, gap-free rolling context, one Batch provider
request in flight, hard token ceilings, shared provider governance, foreground
service behavior and reader priority. Each further change must cite stable
requirement IDs and attach its automated/device/provider evidence, and record
its wave in `implementation-sequence.md`.
