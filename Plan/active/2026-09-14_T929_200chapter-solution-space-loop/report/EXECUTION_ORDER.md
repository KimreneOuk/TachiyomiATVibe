# Consolidated Execution Order v3 — task ledger + succession

2026-09-14. Plan of record. Supersedes v2 (changelog below). Derived from
T928/T929 audits, T931 test audit, T932 legacy sweep, T930 README (incl.
binding Amendments A–H), T933 unified-context proposal (adopted as design of
record for context work; implementation still gated per task below).
Priority order: correctness/data safety > resume safety > reader
responsiveness > memory > throughput.

v3 changes: (1) every phase item is now a ledger task with ID, dependency
edges, DONE-WHEN criteria, and rollback point; (2) T933 corrections folded
in — memory contract into 1.1, InputAccountingContract into 1.2, unified
context increments as tasks 1.3/2.5; (3) Slice A/B internals given explicit
succession (A1→A2→A3→A4, B1→B4) instead of prose lists.

## Safety net — definition (binds every gate)

Net = T931 DIRECTOR_REPORT §4 "KEEP — load-bearing" row: all of
`app/src/test/java/eu/kanade/translation/coexistence/**`; artifact
transaction/crash family (CheckpointOcrTransactionTest,
SidecarCrashPublicationTest, ChapterArtifactStoreStaleManifestRetryTest,
ChapterArtifactStoreRetireActiveRunTest, AtomicChapterDocumentsTest,
ChapterArtifactStoreTest, ChapterArtifactDeletionTest,
LegacyArtifactMigrationTest); UI-truth suite (`P5*Test`,
T918SheetRetryTruthTest); manager pending-ack/durable-reconstruction;
pipeline/batch write-gate & mid-run durability tests.

Gate rule (assertion-level): no net ASSERTION weakened or deleted.
Mechanical conversions preserving assertion semantics, explicitly listed in
a task's CONVERT clause, are permitted.

## Task ledger

Legend: `→` = depends on. Rollback = how to revert if the task goes wrong.
All tasks also gate on: full unit suite green (test tasks, not `check`).

### Phase 0 — feedback loop (est. ~0.5 day)

| ID | Task | Deps | DONE-WHEN | Rollback |
|---|---|---|---|---|
| 0.0 | Baseline run: full suite green + duration recorded | — | recorded numbers in task README | n/a |
| 0.1 | Promote ManualRenderProbeBaseline.kt WITH `runBlocking<Unit>` cure (:11); cure 4 pre-existing guard hits (D8 x3, FenceTest:192) | 0.0 | probe tracked & green in CI; guard `check` green | git revert single commit |
| 0.2 | CI: both workflows → `testDevReleaseUnitTest testStandardReleaseUnitTest`; drop `build-tools;29.0.3` step | 0.0 | one CI run green, suite executes once per variant, dev compile covered | revert workflow line |
| 0.3 | Move Page15MockRig.kt → `tools/dev/` + drop-back README + fix stale KDoc | 0.0 | no rig execution in suite; no `rig-out/` writes | move back |
| 0.4 | De-flake: FenceTest:244 thread-state BLOCKED await; MultiSelectBatch:410 scope-quiesce; DownloadCacheRenewalGuard await-one-renewal restructure (keep by-design 10s deadline) | 0.0 | zero Thread.sleep flake sites remaining in those files; suite repeat-run x3 green | per-file revert |

**GATE P0:** suite green; no net assertion weakened (0.1/0.4 conversions are the permitted mechanical kind).

### Phase 1 — quick wins (est. ~1 day)

| ID | Task | Deps | DONE-WHEN | Rollback |
|---|---|---|---|---|
| 1.1 | Glossary fold: per-store accumulator under store mutex; seeded once per store from `translatedPairs()` (never the 30-entry map); per-page watermark replaces retranslated contributions; D5 equal-map no-op + fold-then-stamp order preserved; regex hoist. **T933 memory contract:** persisted page-contribution records + incrementally maintained ranking (Stats maps are unbounded — exact semantics + constant memory is impossible otherwise); streamed recompute allowed only as labeled fallback; never seed counts from the capped map | 0.x | timed test: per-page fold cost independent of corpus size over 200-page synthetic set; new watermark/replace tests green; D5 unedited | flag not needed — behavior-equal by construction; revert commit |
| 1.1a | Attribute Director-reported BATCH-lane stall: reproduce huge-text-region chapter through the real batch lane; file finding or refute | 1.1 | written attribution in T929 README (cause + fix pointer or refuted-with-evidence) | n/a (investigation) |
| 1.2 | 8k compliance: LM_STUDIO 16,000→8,192 (TranslationContextChunkPlanner.kt:196-198); EnvelopePlannerPolicy → in ≤4,096 / out ≤3,584 / blocks 32→12 / pages 8→3; AnalysisChunkPlanner → in ≤4,608 / out ≤3,072; execution-time check on BOTH envelope shapes (incl. legacy identity split); ban the 256-token-floor fit hack (PAUSE instead); rebalance rolling budgets under 8k | 0.x | NEW cap tests: final input+output+512 ≤ 8,192 for both profiles + analysis + envelope policy; planner tests may be edited (none in net) | constants revert |
| 1.2a | **T933 InputAccountingContract audit:** per provider (LM Studio/DeepSeek/OpenRouter/Gemini) exact final-message counter or certified conservative bound; no contract → no dispatch under 8k | 1.2 | each provider has a documented counter + test proving final-message accounting | per-provider disable = dispatch refusal |

**GATE P1:** 1.1/1.2/1.2a tests green; 1.1a attribution filed; net untouched.

### Unified context — Increment 1 (after GATE P1; independent of T930)

| ID | Task | Deps | DONE-WHEN | Rollback |
|---|---|---|---|---|
| 1.3 | T933 Increment 1: `ChapterContextService` read-only projection + shared prompt assembly (one `prepare(ContextRequest)` for manual/auto/profile); rename `profileAwareGlossaryPrefix` → honest name; standard batch keeps no-context adapter but submits outputs to term producer; D5 semantic version + fold-then-stamp preserved; allocator order per T933 (terms 320 → safeguards 96 → pairs 288 → scene/style 96) behind the 1.2/1.2a accounting | GATE P1 | cross-feed works both directions in probe (batch→manual sheet, manual→batch terms); D5/D6/D9/D10/D11 unedited; no new storage pointer | revert (no durable change) |

### Phase 2 — T930 group commit (ALL gated on Director approval of Slice A start)

| ID | Task | Deps | DONE-WHEN | Rollback |
|---|---|---|---|---|
| 2.1 | **Slice A** (flag OFF, zero behavior): A1 commit-point contract → A2 writer registry (observability-only flag OFF; exclusion semantics per Amendment B) → A3 schema-guard cache (Amendment A scope: normalization only; guard reads :130/:230 NEVER cached) → A4 event-driven retention (Amendment D: `close()` sweep only). Implementer rules: append ctor params w/ defaults (~36 positional sites/8 files); harness flag wiring once; D6 arity bridge → direct ctor only if ctor changes | Director approval; GATE P1 | full suite green flag OFF; net assertions unedited; `check` green | each sub-task = own commit; flag does not exist yet for A1-A4 (pure foundation) |
| 2.2 | **Slice B** (flag, default OFF): B1 staged mutations + combined publish at commit points/250ms → B2 candidate-promotion merge → B3 read-back elision (File-backed only) → B4 drain-to-commit stop (Amendment E grace bound; D9 ledger NEVER staged; Amendment F glossary lane force-flush; Amendment C flush-time story). CONVERT (flag-ON point only): the ~12-18 change-detector tests → commit-point equivalents | 2.1 | flag OFF = suite green unchanged; flag ON = converted tests green, crash-window tests green BOTH ways, zero new PAUSE/REJECTED in soak harness | flag OFF (default) |
| 2.3 | **Slice C** (same flag): R1 admission signal (<100ms) + R2 restricted UI-before-persist. Measurement: probe stamps tap→Queued | 2.2 | p95 < 100ms on debug build OR virtual-time proof; D11 green; UI-truth suite unedited | flag off |
| 2.4 | Soak + flag flip: enable by default after soak; 200-page baseline re-run; ops/page ~105-180 → 55-65 verified; oversized-PAUSE counter feeds block-splitting decision | 2.3 | soak metrics recorded in T929 README | flag revert |
| 2.5 | **T933 Increment 2**: durable unified context — `ChapterContextSnapshot` sidecar + `context` manifest pointer (schema 3→4 bump); three identities (revision/fingerprint/reuseCompatibility per T933); request-context sidecar referenced before D9 dispatch; memory budget measured on 200-page high-distinctness fixture | 2.2 (Slice B) + 1.3 | T933 reviewer conditions implemented (crash-after-ledger test, predecessor-replacement fail-closed, heap/work measurement); schema guard tests extended for v4 | pointer unpublished = feature inert |

**GATE P2 (per slice):** as each task's DONE-WHEN; standing rule — a slice needing a net-assertion edit is wrong, not the test.

### Phase 3 — M2+ (after Phase 2; per MILESTONES.md M2–M6)

Steering/priority, gap rescan S8, warm-up, animation completeness, poll
eventization, boundary config, OCR engine profiles + T927 batching port
(2.1→0.7 s/page), waves (bitmap-budget prerequisite first), LAN carry-over
(re-score after 1.2), background durability. Detailed ledger to be written
when Phase 2 lands (same format).

## Decisions still gated on the Director

- T930 Slice A start — blocks all of Phase 2 (2.1–2.5). Phases 0/1/1.3 are NOT blocked.
- Quota-constant edits; auto-resume-on-launch — M2 only.
- Legacy migration-machine deletion (T932 Tier 2) — do devices carry pre-artifact data?
- Block-level page splitting under 8k — decide with 2.4 soak data.
