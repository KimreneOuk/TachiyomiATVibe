# T928/T929 — The translation system in layers

Everything below is verified against code at `main` @ `9c19ad0` (file anchors in
the T928/T929 slice reports). Read bottom-up: each layer only knows the layer
beneath it; the pathologies are mostly *coupling violations* between layers.

## L0 — OS / Android environment
Doze & App Standby · foreground services (one dataSync FGS exists for batch) ·
**no wake locks anywhere** · SAF/DocumentProvider as the storage transport ·
targetSdk 34 (6-hour FGS cap arrives at 35). → M6.

## L1 — Identity & naming (the substrate everything references)
Page keys (`sourceFileName`) + natural page indexes · run IDs & generations ·
version counters (`pageVersion`, glossary version) · lease tokens · origin
stamps (MANUAL/AUTO/BATCH → artifact origin) · **fingerprints**: source
(per-page bytes), stage, corpus, dependency, model identity (ModelIdentityCache)
· content-addressed sidecar names (semantic no-collision) · file-name
vocabulary: `name.tmp` / `name.bak` / `name.corrupt` quarantine, versioned
`.cleaned.jpg`, legacy flat-file dual vocabulary (retained, R8).
**This layer is why resume, adoption, SKIP_ALL, and fenced writes work. It is
the best-designed layer in the system.**

## L2 — Persistence & storage (I/O)
`ChapterDocumentIo` over UniFile (SAF vs raw-file backends; rename semantics
differ) · `AtomicChapterDocuments.publish` = tmp write → read-back byte-compare
→ bak delete → 2 renames (5 ops, 2 payload transfers; **no fsync anywhere**) ·
`ChapterArtifactStore`: manifest + sidecars + checkpoints + generation records
+ retention GC, CAS via durable-manifest compare · `ChapterTranslationStore`:
pages map, display projection, **store mutex serializing every disk op** ·
glossary sidecar · queue/pending in SharedPreferences (`commit=true`) · legacy
migration/rescue as second writer class.
Pathologies: whole-manifest rewrite per mutation (O(P²) bytes), triple
manifest read per publish, per-checkpoint retention crawl, ~95–150 ops/page.
→ T930 group commit (M1), WAL/shards as structural alternatives.

## L3 — Data processing primitives (pure compute)
ONNX sessions (detector, Paddle/MangaOcr/MLKit OCR, AOT/bubble inpaint;
EP ladder qnn_htp→gpu→nnapi→xnnpack→cpu; MangaOcr **CPU-only by design**) ·
token estimation & planners (envelope 32blk/8pg, chunk 16pg, 8k lock per M0;
trim ladders scenes→pairs→subset; splitForTokenFit) · glossary miner
(30-entry cap; **fold is quadratic at call site + per-call regex compile**) ·
source-fingerprint hashing · rolling-context assembly · JPEG encode/decode,
bitmap decode.
Pathologies: engine-bound OCR (2.1 s/page), no warm-up, re-mining per page.
→ M3 (T927 batching, engine profiles), glossary fix in M1.

## L4 — Data communication (transports & signals)
In-process: conflated StateFlows (`_state`, `_display`, tracker, VM state) ·
channels (batch events UNLIMITED + reducer; auto trigger CONFLATED) · stage
listeners (auto only — manual path has none) · `Event.RefreshTranslationPages`
→ ReaderActivity · DeferredPagePublications (ordered off-permit drain) ·
**polling**: governor 50 ms, chapter claim 100 ms.
Cross-process: provider HTTP (**blocking, non-streaming; SSE absent**) · SAF
binder round trips (every L2 op) · JNI/ONNX boundary (one invocation at a
time; cancellation impossible mid-run).
Pathologies: conflation drops intermediate stages; three disjoint stage
vocabularies; persist-before-UI publishes storage latency into UI latency.
→ R1/R3/R5/R6 + P3 streaming (M1/M2/M5), poll eventization (M1).

## L5 — Domain pipeline (stage choreography)
Decode → detect → OCR(+checkpoint) → [corpus gate] → analysis chunks →
profile freeze → envelopes (translate) → inpaint (overlap **only inside
provider waits**) → render join → finalize → COMPLETE.
Pathologies: whole-chapter OCR barrier before any translation (48–85 % of
chapter time sits after a chapter's OCR), corpus-gap PAUSE, decode inside the
native permit, glossary fold inline between pages.
→ M2 (gap rescan), M4 (per-page tail + waves).

## L6 — Application orchestration (workload management)
TranslationManager facade · TranslationScheduler (manual/auto page jobs) ·
RollingAutoCoordinator (windows, suppression during batch) · ChapterTranslator
(queue, **single-flight: group-by-source take(1) + per-chapter claim**,
never-auto-start) · ChapterProfileBatchCoordinator (phase machine, run
records) · NativeRunQuarantine (FCFS mutex, no preemption, timeout-keeps-
ownership) · EngineLane (rebuild triggers, rebuild inside permit) ·
ProviderRequestGovernor (per-backend buckets, 1s spacing, maxInFlight 1,
20 % interactive reserve, batch 15-RPM sublimit) · PageStageLeaseTable
(manual evicts auto; never preempts batch) · memory governance (tier budgets,
fail-fast gates, pool-only relief).
Pathologies: no reader priority on the native lane; FIFO queue; translator-wide
`isPaused` interplay. → M2 steering, M4, arbitration owner = scheduling.

## L7 — Presentation (what the user sees)
Page holders (pager/webtoon), ReaderViewModel, feedback coalescer (120 ms
min-display, terminal latch, monotonic rank), pills/sheets/indicators, queue
UX (single-chapter granularity today).
Pathologies: silent 5–10 s admission, skipped stages, inconsistent vocabulary,
flip-flopping pill source, no mid-run config affordance.
→ M1 admission signal, M2 animation completeness + boundary config + queue UX.

## The coupling sins (where the real cost lives)

| Sin | Layers | Symptom | Fix |
|---|---|---|---|
| UI state published only after durable write | L7←L2 | first stage late/silent | publish transients first (R2-restricted) |
| Atomicity protocol priced per mutation, not per transaction | L2 | ~7 manifest rewrites/page | group commit (T930) |
| Phase barriers couple scheduling to corpus completeness | L6←L5 | PAUSE stalls whole run; provider lane idle during OCR | gap rescan + waves (M2/M4) |
| Conflated state where events were needed | L4 | skipped stages | non-conflated stage channel |
| GC (retention) on the critical path | L2←L5 | per-page crawls | event-driven retention |
| Polling where completion signals exist | L4 | 50/100 ms dead time | eventization |
| Provider communication blocking only | L4 | no progressive page commits | SSE sub-envelope commits (P3) |
| One engine set, rebuilt inside the permit | L6/L3 | seconds of lockdown per config change | warm-up + boundary config |

## I/O, storage, naming — the concrete account
- **I/O**: every op is synchronous + SAF round trip + under the store mutex;
  ~95–150 per page, ~14k per 100-page chapter; read-back byte-compare doubles
  payload per publish; no fsync (process-death-safe, not power-loss-safe).
- **Storage layout**: one manifest doc (O(chapter) size) + per-page sidecars
  (candidates, committed, generations, OCR snapshots, checkpoints, layouts) +
  glossary sidecar + run records + cleaned JPEGs + tmp/bak/corrupt shadow
  files; retention = reachability crawl over the tree.
- **Naming**: content-addressed sidecars make GC safe and adoption
  fingerprint-gated; dual legacy vocabulary retained; version counters drive
  CAS. Naming is load-bearing for every safety guarantee — the one layer not
  to refactor casually.
