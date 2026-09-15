# T928 Director Report — Translation pipeline layer audit (manual / auto / batch)

Audit base: branch `main` @ `9c19ad0`, tracked code only (codebase over
documentation, per Director directive). Three parallel read-only investigations;
Main Leader spot-verified 8 load-bearing claims in code — all confirmed.

- Slice reports: `team/io/report.md`, `team/ui/report.md`, `team/sched/report.md`
- Every claim below carries file:line evidence in the slice reports.

## Verdict

The pipeline is not slow because of compute. It is slow because of three
compounding structures, all code-verified:

1. **Durable I/O amplification** — ~95–110 backend storage ops per fresh page
   (manual/auto), ~130–150 in batch; each op is a SAF round trip and all of them
   serialize behind one store mutex. A 100-page batch chapter ≈ 14,000 ops and
   ~700 full-manifest rewrites (O(P²) manifest bytes).
2. **UI stage signals are an afterthought on two of three paths** — the manual
   path emits nothing until its first durable write (which happens after lease,
   decode and model warm-up), and the render-side coalescer drops fast stages.
3. **Deliberate global serializations with no priority lane for the reader** —
   one native lane (FCFS, no preemption), one provider slot per backend, and a
   batch design that OCRs the whole chapter before translating page 1, which
   starves a manual tap into a 210-second attach wait.

None of the safety machinery is wrong; the cost accounting is. The fix path
keeps every crash-safety invariant (`.bak` rotation, sidecar-before-pointer,
guarded writes) and removes redundancy, not safety.

## Q1 — Slowness / I/O: what each page actually pays

Per fresh page (manual ≈ auto), code-verified op counts:

| Write | Ops |
|---|---|
| Registration manifest publish (first write) | 8 |
| OCR candidate open + snapshot (2 sidecars + 2 manifests) | 26 |
| Cleaned JPEG write+verify | 3 |
| Cleaned-image commit (candidate + manifest) | 13 |
| Glossary sidecar + listing + manifest (contextual translators) | 14 |
| Final commit (candidate + manifest) | 13 |
| Promotion to committed (read-back + committed + generation + manifest) | 19 |
| **Total** | **~95–110** |

Each "manifest publish" = 3 full manifest reads (CAS + two future-schema
guards) + 5 write ops (tmp write, tmp read-back byte-compare, bak delete, 2
renames). Batch adds a per-page OCR checkpoint (+21 ops and a recursive
retention crawl) and a layout-plan publish.

Root-cause findings (full table in io report §c):

- **R1 HIGH** — whole-manifest rewrite per stage write: 7 manifest publications
  per page, manifest size grows with page count ⇒ O(P²) bytes per chapter.
- **R2 HIGH** — triple full-manifest read per publication; the future-schema
  flag changes only at app upgrade.
- **R3/R4 MED** — candidate written then immediately rewritten as committed;
  per-page glossary sidecar + manifest publish.
- **R5 MED** — per-page recursive retention crawl in batch (O(P²) listings).
- **R6 MED** — every disk round trip holds the ChapterTranslationStore mutex,
  so one page's commit blocks every other page's store access.
- **No fsync anywhere** — the heavy protocol buys process-death safety only;
  power loss is not covered. This matters for the cost/benefit argument of
  slimming it.

### Recommended optimization path (keeps all crash invariants)

1. **Low risk, independent, ~40% of ops removed:** cache the future-schema
   guard and drop the redundant CAS read (OPT-2); merge candidate→promotion
   into one transaction (OPT-3); event-driven retention instead of per-page
   crawl (OPT-4); drop the `.tmp` byte-compare read-back on local files
   (OPT-7).
2. **Structural fix, ~70% of ops and most per-stage latency:** group manifest
   commits — stage sidecar writes in memory, publish ONE combined manifest
   update at page-terminal/checkpoint boundaries or a 250ms debounce (OPT-1).
   Crash mid-page re-runs that stage from the last published state — the same
   recovery semantics as today's stale-writer recovery.
3. **Then:** batched glossary commits (OPT-5); move sidecar/JPEG writes off the
   store mutex onto a single-writer storage lane (OPT-6).
4. **Explicitly keep:** `.bak` rotation and sidecar-before-pointer ordering —
   they are the only protection for the reader's last-known-good page.
   WAL journal (OPT-8) only if group commit proves insufficient.

Note: I/O is not the only wall-clock driver — see Q3; batch's phase barriers
and native-lane occupancy dominate batch-start latency.

## Q2 — Processing animation: why late, why stages go missing, why inconsistent

There are **three disjoint stage vocabularies and three transports** for the
same pipeline: manual = durable store writes only (no event system, no Queued
state reachable); auto = push events through stage listeners behind a
resetting 150ms debounce; batch = typed channel + tracker + projector (the
best-behaved of the three).

- **Late:** manual emits nothing at admission — first signal is the pipeline's
  first store write, which happens after lease wait, decode and ONNX session
  warm-up; and `publishLocked` runs the artifact persist BEFORE setting the UI
  StateFlows (ChapterTranslationStore.kt:1883–1895, verified), so even that
  first pill waits behind manifest I/O. Holder channels additionally wait on
  store open / legacy migration I/O. Auto adds a fixed 150ms debounce that
  rapid scrolling keeps resetting.
- **Missed stages:** the render-side coalescer holds ONE pending slot with a
  120ms minimum display time — a new stage overwrites an unflushed pending
  stage, which is then never rendered (ReaderTranslationFeedback.kt:223–245,
  verified); overlapping stages collapse by fixed precedence (render >
  inpaint > translate > ocr); every hop is a conflated StateFlow; a terminal
  latch then blocks all later stage updates.
- **Inconsistent:** different labels per surface (pill "Reading/Cleaning/
  Translating/Finishing" vs sheet "OCR/AI/Inpaint/Render"); the pill's source
  flip-flops between vocabularies when auto window and manual tap overlap;
  pager vs webtoon seed differently on rebind; restored pages jump straight to
  "Translated" with no stage animation; a committed page under retry shows
  stale "Translated" because REFRESHING state is computed but never surfaced.

### Recommended rework (ranked in ui report §c)

1. Emit a Queued state at admission for manual/batch (biggest perceived win,
   low risk).
2. Publish UI StateFlows before durable persist for transient (non-durable)
   stage updates; keep persist-first for durable results.
3. Replace the resetting 150ms auto debounce with a non-resetting forward
   policy within the same chapter.
4. Surface the already-computed REFRESHING_WITH_COMMITTED_RESULT state.
5. Unify the stage vocabulary across paths (one enum mapped at the edges);
   interim: reuse TranslationProgressStage for manual so manual and batch
   share names.
6. Coalescer: flush an occupied pending slot instead of overwriting it.

## Q3 — Resource allocation & scheduling

Three deliberate serializations protect bounded memory and crash safety and
must NOT be parallelized away (explicit no-go list in sched report F.4):

1. **One native lane** — a single process-wide FCFS mutex admits all ONNX work
   (manual, auto, batch alike); concurrency = 1; no preemption (a timed-out
   native call keeps ownership until real exit — leak-instead-of-crash policy).
2. **One provider slot per backend** — 60 RPM, 1s minimum spacing,
   maxInFlight=1, 20% token reserve for INTERACTIVE (reader) requests; batch
   traffic additionally passes a nested 15-RPM gate; an admitted batch request
   is never interrupted.
3. **One bitmap memory envelope** — tier-scaled prefetch (6/4/2), fail-fast
   memory gates that defer (never kill), pressure reactions release pools and
   native buffers but never model sessions (no reload thrash — verified).

Arbitration: manual evicts auto on the same page; manual NEVER preempts batch —
it attaches and observes for up to `ATTACH_TIMEOUT_MS` = 210s; auto is fully
suppressed while a batch run is retained; native admission is FIFO with no
priority, so a manual request queues behind every batch native job.

**Where dead time actually accumulates (per scenario, sched report §e):**

- Manual page, reader idle: chain is contiguous; the only structural dead time
  is one-time engine session creation + first-inference cost (no warm-up
  exists), charged to the first translated page of a process.
- Manual page while batch runs (worst case): whole-chapter OCR preflight means
  the tapped page cannot reach a terminal state until the entire chapter is
  OCR'd (+ AI analysis), so the 210s attach times out — structural, HIGH.
  Even on free pages: native FIFO behind batch jobs, decode running inside the
  native permit, possible engine rebuild inside the permit.
- Batch run start: queue admission → store open → full-chapter fingerprint
  hashing barrier → whole-chapter OCR → (AI: analysis + profile freeze) →
  first translated page. First user-visible output lands only after all of it.

### Recommended scheduling changes (sched report §f)

1. **F.1 (highest impact, MED risk):** remove the whole-chapter OCR→translate
   barrier for the STANDARD lane — start a page's translation as soon as its
   OCR checkpoint is committed; keep the AI-lane barrier (corpus context is
   genuinely chapter-wide).
2. **F.2 (LOW-MED risk):** move batch page decode out of the native permit.
3. **F.3 (LOW risk):** opportunistic engine warm-up at reader open / batch
   queue, memory-gated.
4. **F.5 (LOW risk):** wire the tier-scaled held-bitmap ceiling — the API
   exists with zero callers; flagship devices currently get the 48MiB baseline
   ceiling, forcing needless disk reloads at render.
5. **F.6/F.8 (LOW risk):** event-driven chapter claim (currently 100ms
   busy-poll); rebuild-free clearQueue (release buffers, not sessions).

## Recommended action plan

Phase A — low-risk, high-perceived-impact (recommend approving first):
animation fixes 1–4 (Queued at admission, UI-before-persist for transients,
auto debounce, REFRESHING surfacing) + IO OPT-2/3/4/7 + F.5/F.6/F.8.

Phase B — structural: F.1 standard-lane barrier removal, F.2 decode off
permit, F.3 warm-up, IO OPT-1 group manifest commit. Each lands behind the
existing T924 checkpoint/fencing tests.

Phase C — consolidation: OPT-5/6, unified stage vocabulary.

Priority order honored throughout: correctness/data safety > resume/crash
safety > reader responsiveness > memory safety > throughput.
