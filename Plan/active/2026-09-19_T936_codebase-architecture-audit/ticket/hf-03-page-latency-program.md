# Ticket HF-03: Page-translation latency program — free-endpoint batching, interactive priority, lazy persistence, truth honesty

**Priority:** HIGH (device-verified UX/perf) | **Branch:** `t936/perf-manual-latency`
**Decision (Director):** stay on the FREE Google endpoint (no Cloud API key). Improve what exists.

## Measured baseline (PKG110, v0.17.1-612, chapter 6485 page 5.jpg, warm process)

Wall 19.5s = compute 2.6s + translate stage 9.0s (1.6s governor admission + 6 serial round-trips
paced 1,000ms apart, maxInFlight=1) + storage-on-critical-path 4.2s (cleaned_persist 1.05 +
store_commit 2.85 + flush 0.36) + unattributed gaps 3.6s (2.65s pre-detect, 0.98s pre-inpaint).
Tap→first truth ("Reading Text") = **2.67s** (first live update at 58.126 vs tap 55.453).
"Cleaning Bubbles" label held 2.56s beyond actual inpaint (persist + governor wearing the label).
"Finishing Page" label held 3.2s (store_commit + flush). `nativeBusyMs=4,977` vs ~1.3s page
compute → native lane shared ~3.6s with warm-window prefetch (`warm=5`).

Full evidence: `%TEMP%\opencode\full_buf.txt` capture 2026-09-21 10:53-10:56 (orchestrator), and
`team/research-google-translate-optimization.md`.

## Work items (one branch, sequential commits, each independently verified)

### 1. Free-endpoint span-ID envelope batching (provider latency: 9s → ~1-2s)
Implement per research report §C.2 (rank-2 option, now primary):
- One request per page: blocks wrapped as escaped `<span data-id="bN">text</span>`, joined; host
  `translate.googleapis.com`, `client=gtx`, `dt=t` only.
- STRICT validation on response: exactly one span per expected ID, no duplicate/missing/reordered
  IDs; on ANY envelope anomaly → fall back to existing per-block path for that page (one envelope
  attempt, no envelope retries).
- Envelope cap ≤5,000 source chars incl. markup; larger pages chunk (envelope per chunk).
- Governor: charge ONE request per page/envelope (count drops 6-30 → 1: strictly safer than today).
  Keep Retry-After/exponential-backoff/circuit-breaker; CAPTCHA/HTML body must trip the breaker,
  never be parsed as a translation.
- Do NOT add concurrency; do NOT relax spacing for per-block fallback.

### 2. Native-lane interactive priority (tap→truth 2.7s → <300ms; kills the 3.6s gaps)
Manual/interactive pages must preempt warm-window prefetch at the NATIVE executor queue (current
priority stops at scheduler admission). Confirm the 2.65s/0.98s gaps are prefetch contention by
instrumenting native-lane queue wait (temporary, removed before merge), then implement preemption
or a priority queue. Prefetch resumes after interactive work drains.

**Status: cancelled — mechanism absorbed into item 3.** Local queue-wait instrumentation recorded
`queueMs=0`, with no AUTO/native-prefetch activity; the warm ONNX sessions were already cached.
The ~2.65s pre-detect and ~0.98s pre-inpaint gaps were synchronous durable publication through
`updatePageGuarded → persistArtifactMutationLocked` under the store mutex (with approximately
290ms of GC observed in the latter gap). No native-priority change was made; item 3 moves this
publication off the live display path.

### 3. Lazy persistence (4.2s off the display path)
- Render/store completion publishes to the store's LIVE state immediately; display projects from
  live state. Disk work (PNG encode, write, fsync) moves to a background flush worker.
- Invariants: (a) hydration is STORE-FIRST (live entries before disk — HF-01 class protection);
  (b) flush worker checks store generation before write, discards defunct; (c) atomic write
  discipline (temp+rename) unchanged; (d) BATCH session-end durability barrier: flush-all completes
  before "batch complete" is declared (T930/T934 contracts preserved); manual/auto worst case =
  re-translate on crash, acceptable.

### 4. Truth-label honesty
- RUNNING truth emitted at stage ADMISSION (including first stage: tap→"Reading Text" <300ms),
  not after side-quests (persist/governor waits must not bill stage labels).
- Labels sequence verified: Reading Text → Cleaning Bubbles → Translating Text → Finishing Page.

## Tests (behavioral, named)
- Envelope: valid reconstruction; each anomaly class (missing/dup/reordered ID, HTML challenge
  body) falls back to per-block; chunking at cap.
- Governor: charges 1/page under batching; breaker trips on CAPTCHA body.
- Native priority: manual page ahead of queued prefetch work.
- Lazy persistence: live-first hydration; defunct generation not flushed; batch-end barrier blocks
  completion; crash mid-flush leaves no partial artifact.
- Truth: admission-time RUNNING emissions incl. first stage.

## Constraints
- No regression to HF-01 keying, HF-02 session gating/lease protection, durability contracts.
- Free endpoint treated as untrusted: every parse validated, per-block fallback always available.
- Full both-flavor suites green + assembleDevDebug; device verification (orchestrator) with live
  logcat: target tap→Reading Text <300ms, warm manual page <5s wall (stretch ~3s).

## Report
`team/hf-03-implementation-report.md`
