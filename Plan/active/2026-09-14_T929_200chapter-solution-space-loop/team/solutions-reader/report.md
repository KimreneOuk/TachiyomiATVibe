# T929 Round 2 — solutions-reader: reader-experience solution catalog (200-chapter baseline)

Base `main` @ `9c19ad0`, clean tracked tree. Inputs: task README, T928 `team/ui/report.md`,
verify-ui corrections (BINDING), model report §4–5. Code paths relative to `app/src/main/java/`.
Constraints honored: never revoke committed display; identity fencing + monotonic rank +
stale-callback rejection (`ui/reader/viewer/ReaderTranslationFeedback.kt`); no flicker;
no partial commits; no provider/native parallelism beyond quota.

## Catalog

| # | Entry | Tag | Scenarios | Reader-speed effect | Risk |
|---|---|---|---|---|---|
| R1 | Queued-at-admission for manual taps + M1 death repair | T928-fix | A,C,D,E | tap→truth: dead gap → instant Queued | Low |
| R2 | UI-before-persist for transient stage writes | T928-fix | C,D | first stage −manifest-write latency | Med |
| R3 | Non-resetting auto debounce (same-chapter fast-forward) | T928-fix | A | Queued slots during scroll; re-entry kicks unstalled | Low |
| R4 | REFRESHING truth — REDESIGNED precedence tier | T928-fix(redesign) | A,C | retry animates instead of stale "Translated" | Med-High |
| R5 | Unified stage vocabulary (incl. AI-lane states) | T928-fix | all | one mental model pill/sheet/rail | Med |
| R6 | Coalescer flush-on-overwrite | T928-fix | A,E | no silently eaten middle stages | Low-Med |
| B1 | Reader-adjacent queue steering ("read next now" reprioritization) | NOVEL | B,A | jump TTFP 1.77 h→~49 s cloud | Low |
| B2 | 200-chapter progress map drawer (per-chapter state, jump target) | NOVEL | B,F | makes scenario B navigable at all | Med |
| B3 | ETA/queue-depth estimates from model constants | IMAGINATIVE | B,A | expectation-setting; ~free | Low |
| C1 | Dual-lane refresh: standard lane serves now, AI lane refreshes | NOVEL | A,C,D | ch.1 TTFP 49 s→34 s; pages readable ~47–85 % earlier | High |
| C2 | Labeled raw-OCR preview chip (never as translation) | IMAGINATIVE | C | scenario-C norm becomes readable early | Med |
| C3 | In-reader queue position line (#N of 200) | fix | B,A | kills "is my chapter coming?" blindness | Low |
| E1 | Stage→result animation continuity (180 ms crossfade as one session) | fix | A,C | perceived seamlessness; no blank flash | Low |
| E2 | Next-chapter OCR pre-start while user reads current | IMAGINATIVE | A | next chapter's OCR barrier hidden in read time | Med |
| F1 | Glossary/name-consistency chip in sheet | quality | all | quality made visible | Low |
| F2 | Re-translate + dedup truth with latch-respecting attempt open; peekStore seed | T928-fix(R7/R8) | A,C | retry/already-running give visible truth; rebind seeds instantly | Med |
| F3 | Per-page QA-warning marker (run-record warnings) | quality | all | trust; no display change | Low |

NOVEL/IMAGINATIVE: B1, B2, C1, C2, E2, B3 (6 ≥ 4 required).

## A. T928 animation fixes re-validated against verify-ui

**R1 — Queued at admission + M1 silent-death repair.**
(1) Mechanism: on `translateSinglePage` acceptance emit a scheduler outcome
`SinglePageOutcome.Admitted` (in-memory, identity-fenced map) rendered via
`TranslationUiTruth.forManualOutcome`. A durable `ocrStatus=PENDING` write is NOT a substitute:
`PageTranslation.toReaderPageFeedback()` has no PENDING branch (`else -> null`,
`ui/reader/viewer/ReaderTranslationFeedback.kt:78-87`). Simultaneously close M1: silent returns at
`ReaderViewModel.kt:2203-2206` (manga null), `:2214` (source cast), `:2263-2268` (no bytes),
`:2292-2298` (lazy-download failure) must each emit a terminal Failed/ManualTruth — today the page
stays dead forever, not just late.
(2) Anchors: `ReaderViewModel.kt:2202-2320`; `scheduling/TranslationScheduler.kt:658-702`;
`ReaderTranslationFeedback.kt:122-152`.
(3) Scenarios: A/C — tap produces truth in <1 frame instead of after lease+decode+warm-up; D —
cold-start provider stalls become visible instead of silent.
(4) Quality: none (no pipeline change).
(5) Risk: low; admission placeholder ages into M2's stranded-sweep CANCELLED heal benignly.
Invariants: identity fence intact (outcome keyed chapterId+pageKey); monotonic rank untouched
(Queued rank 0).
(6) Interactions: durability — optional PENDING store write pays first-registration manifest I/O at
tap time (acceptable, moves latency); scheduler dedup path shared with F2.

**R2 — Publish store StateFlows before persist for transient writes only.**
(1) Mechanism: in `publishLocked`, for updates where `shouldPersistUpdate == false`
(placeholders, bare RUNNING/PENDING flips), set `_state`/`_display` before
`persistArtifactMutationLocked`; keep persist-first for durable results.
(2) Anchors: `eu/kanade/translation/ChapterTranslationStore.kt:1876-1897` (persist 1883-1889,
publish 1894-1895), first-registration 1943-1969, `shouldPersistUpdate` 2522-2548.
(3) Scenarios: C/D — removes manifest-write latency from the FIRST visible stage; A — faster
stage-to-stage cadence.
(4) Quality: none; placeholders carry no committed content.
(5) Risk/Invariant: verifier caveat — today a rejected write rolls back BEFORE publication
(`restorePageLocked`, 1899-1903); publish-first leaks a transient that then rolls back
(coalescer reset flicker). Mitigation: run precondition checks before publish, publish only
accepted transients. Never applies to committed display (persist-first retained there).
(6) Interactions: durability domain owns reorder safety; io slice treats this as one I/O
checkpoint removed per stage write (model: 5–7 durable writes/page baseline unchanged).

**R3 — Non-resetting auto debounce.**
(1) Mechanism: keep the 150 ms delay only for chapter change; while a valid window exists for the
same chapter, forward `onPageSelected` to the coordinator immediately (trigger is CONFLATED,
windowVersion-fenced: `scheduling/RollingAutoCoordinator.kt:189-199`); preserve the
stale-cross-chapter drop (`ReaderViewModel.kt:1249-1259`).
(2) Anchors: `ReaderViewModel.kt:1272-1279` (`delay(150L)` at 1275); re-entry kicks also route
through it at 603/628/644/685/2545 (M3/M4), so the fix de-stalls toggle/prefetch/engine/foreground
resumption too.
(3) Scenarios: A — fast scrolling no longer indefinitely defers the whole auto window incl. Queued
slots; F — foreground resume re-kick is immediate.
(4) Quality: none.
(5) Risk/invariant: low — the debounce guards no correctness invariant (verifier AGREE); cost is
more `updateWindow`/reconcile churn, bounded by conflation.
(6) Interactions: scheduling (more reconcile calls), none with durability/provider.

**R4 — REFRESHING surfacing: redesigned precedence tier (replaces T928 R4 patch).**
(1) Mechanism: T928's "feed display-state into the pill" is a CONFLICT, not a patch (verifier):
(a) `selectReaderPageFeedback` gives durable Translated/Failed precedence over everything but an
active attempt (`ReaderTranslationFeedback.kt:100-102`); (b) `beginAttempt` fires only when
`isPageBeingTranslated` flips true (`pager/PagerPageHolder.kt:248-250`), but under committed-first
resolution `page.translation` IS the committed bundle (`ChapterTranslationStore.kt:2227-2229`;
`ReaderViewModel.kt:2766,2802`), so the latch never opens and line 193 eats every stage.
Redesign: a refresh intent gets a NEW attempt identity (forced-retry intent id / pageVersion bump)
that (i) opens a dedicated coalescer attempt via `beginAttempt()` only at admission of THAT intent,
(ii) introduces one explicit precedence tier "refresh-active over committed terminal", evaluated
only while that identity is live, (iii) expires on the identity, so stale callbacks from the old
attempt still hit the latch and are rejected — the latch's purpose (identity fencing, monotonic
rank, stale-callback rejection) is preserved, not bypassed. Committed image never leaves the
screen; only the pill/badge animates ("Updating…").
(2) Anchors: `PageDisplayProjection.kt:93` (`REFRESHING_WITH_COMMITTED_RESULT` computed, unused);
`ReaderTranslationFeedback.kt:95-104, 158-232`; M8 third latch trigger 181-186.
(3) Scenarios: A/C — retries and C1's AI-refresh show truthful animation; perceived quality up.
(4) Quality: none; strictly honest display.
(5) Risk: Med-High (verifier: "low risk is wrong") — touches precedence core; needs the attempt
identity designed with the scheduling domain (who admits refresh intents).
(6) Interactions: scheduling (C1 refresh admission), durability (pageVersion source of identity),
provider (retry outcomes terminate the tier).

**R5 — Unified stage vocabulary.**
(1) Mechanism: one stage enum produced once per page; interim: reuse `TranslationProgressStage`
in `toReaderPageFeedback` so pill, sheet cards, indicator and rail share labels; include the
verifier's missed vocabularies (`AiPageProgressState` `TranslationBatchProgressTracker.kt:95-99`;
`BatchPhase.DISPLAY` `snapshotFor` 195-197) in the unification scope. Also reconcile the two
disagreeing priority mappers: tracker render>TRANSLATE>INPAINT>ocr (`:495-498`) vs pill
render>inpaint>translate>ocr (`ReaderTranslationFeedback.kt:81-84`).
(2) Anchors: `TranslationProgressSnapshot.kt:227-238`; `ReaderAutoTranslationUiState.kt:90-99`;
`ReaderTranslationFeedback.kt:78-87`.
(3) Scenarios: all — one mental model across 200 chapters of sheet/indicator/pill.
(4) Quality: perception only.
(5) Risk: Med — mapping table must keep manual Queued intentional (R1) and not regress the
terminal-latch semantics.
(6) Interactions: none direct; prerequisite for honest B2 drawer states.

**R6 — Coalescer flush-on-overwrite.**
(1) Mechanism: when a new stage arrives while `pending` is occupied, flush the OLD pending
immediately and pend the newer (verifier alternative). This fixes S1's eaten middle stages without
frame-rate-dependent heuristics; alternative contract trade: each stage's minimum display is
shortened. Do NOT bump `highestStageRank` before a display decision — today the rank is bumped at
`ReaderTranslationFeedback.kt:220-221` before the drop, so a skipped stage can never re-show.
(2) Anchors: `ReaderTranslationFeedback.kt:158-160, 220-231, 234-240`.
(3) Scenarios: A/E — LAN's long stages make intermediate stages visible anyway, but fast cloud
runs currently skip Reading→Cleaning entirely.
(4) Quality: none. (5) Risk: Low-Med — breaks no invariant; slight label churn on fast runs.
(6) Interactions: none outside the pill.

## B. Reading-position intelligence

**B1 — NOVEL: reader-adjacent queue steering.**
(1) Mechanism: `TranslationManager.prioritizeChapter(chapterId, preempt=false)` moves the entry to
the head of the outstanding list (`queueState`, `ChapterTranslator.kt:151-152`; FIFO pick is
`candidates.first()` per source at `:424-434`), then `persistQueue()` (one save,
`:167-173`; `TranslationQueueStore.kt:42-49`). UI: "Read next now" chip in the reader batch line +
auto-suggestion on chapter transition. Per-chapter checkpoints make any reorder safe and free
(model §5.2); preemption of the in-flight chapter is opt-in only (costs ≤1 re-OCR page + ~20 s
resume, model §4.4).
(2) Anchors: `ChapterTranslator.kt:380-434`; `manager/BatchProgressProjector.kt:36-48` (outstanding
definition); queue persistence `TranslationQueueStore.kt:42-64`.
(3) Scenarios: B — jump TTFP collapses from 99 marginals (~1.77 h cloud / ~6.0 h LAN) to that
chapter's own (a) (49 s cloud / 144 s LAN); A — the chapter ahead of the reader is always next.
(4) Quality: none.
(5) Risk/invariant: requeued entry must keep status QUEUE semantics (rearm block `:380-393`);
never revokes committed display (completed chapters are skipped by the outstanding filter).
(6) Interactions: scheduling domain (admission order is the only lever — no parallelism added);
durability — one extra queue save per steer; provider — none.

**B2 — NOVEL: 200-chapter progress map / batch drawer.**
(1) Mechanism: replace the single-chapter sheet-centric view with a virtualized per-chapter list:
state chip per chapter (done / warnings / OCR-only / queued #N / failed / paused) joined from
(a) per-chapter run records (1 durable COMPLETE record per OCR page,
`pipeline/batch/ChapterProfileBatchCoordinator.kt:480-483`; resume uses them at `:2019-2069`),
(b) `queueState`, (c) live tracker snapshot. Tapping a queued chapter offers B1-prioritize +
jump-to-chapter. Search/filter (unread, failed).
(2) Anchors: today's chapter-level-only surfaces: `TranslationProgressSheet.kt` (single
`observeBatchProgress(chapterId)`, `manager/BatchProgressProjector.kt:179-202`);
`ChapterTranslationIndicator.kt:227-314` (ring+percent).
(3) Scenarios: B — gives the jump a target and shows its cost before committing; F — crash state
visible per chapter (which of the 200 are durable); A — "am I outrunning the queue?" glanceable.
(4) Quality: none. (5) Risk: Med — list must be virtualized (200 rows trivial; images banned);
terminal reconstruction must reuse `reconstructDurableTerminalSnapshot` (M9,
`BatchProgressProjector.kt:229-294`) so post-crash truth stays "not running".
(6) Interactions: durability (run-record reads are cheap sidecar reads); scheduling (position from
B1 order); memory — bounded by lazy per-chapter queries.

**B3 — IMAGINATIVE: ETA and queue-depth estimates.**
(1) Mechanism: sheet hero + drawer header show "≈X h remaining, your chapter ≈ #N (~Y min)".
Computable from code constants: marginal ≈ P·t_ocr + (C+E)·max(4 s, L+1 s) + fixed overheads;
pacing floor 4 s from `BATCH_REQUESTS_PER_MINUTE=15`/60 s (`translator/ProviderRequestGovernor.kt:710,730-737`), shared 1 s spacing (`:69`), one in-flight (`:70`); per-chapter marginals
64 s (flagship cloud) / 109 s (mid) / 219 s (LAN) per model §4.2.
(2) Anchors: `ProviderRequestGovernor.kt:67-73`; `model/TranslationProgressSnapshot.kt:105`
(fraction today); position already computed `BatchProgressProjector.kt:301-315`.
(3) Scenarios: B/A — expectation-setting converts "infinite wait" into a decision (read now vs
steer via B1). (4) Quality: none. (5) Risk: must be labeled estimate ([P] OCR/Latency params);
wrong ETAs erode trust — clamp + refresh on marginal recompute.
(6) Interactions: provider domain supplies current profile class (cloud vs LAN) for the constant set.

## C. Progressive reading UX

**C1 — NOVEL: dual-lane refresh (standard lane serves now, AI lane refreshes).**
(1) Mechanism: for the chapter the user is actually reading (and only it), run the standard lane
per-page pipeline (one provider request per page in order, `ChapterProfileBatchCoordinator.kt:1749-1979`, per-page request `:1843-1850`; `BatchLaneWorkers.kt:933-956`) so pages become
readable as OCR+1 request each completes (standard flagship ch.1 TTFP 34 s, model §4.3a), then the
AI lane re-runs the chapter and replaces pages via the full commit protocol, surfaced by R4's
REFRESHING tier. Manual/auto lanes are already exempt from the 15/min AI sublimit
(`ProviderRequestGovernor.kt:691-706`; `pipeline/batch/ProfileEnvelopeExecutor.kt:69-72`).
(2) Anchors: warm-window batch bypass precedent `ReaderViewModel.kt:836-837`
(`attachTranslatedStreamIfWarm`); committed replacement path `ChapterTranslationStore` manifest TX.
(3) Scenarios: A — pages readable 47–85 % of chapter-time EARLIER (model §4.3c: the post-OCR
fraction is exactly what arrives sooner); C — same; D — first readable page at 34 s vs 49 s.
(4) Quality: EXPLICIT TRADE — pages read before AI arrival show standard-lane quality; % pages
temporarily sub-AI = f(user speed vs marginal). AI refresh is reference quality and final.
(5) Risk/invariant: High — conflicts with the T924 one-live-schedule-per-chapter claim
(`ChapterTranslator.kt:445+` claim set) and needs scheduling-domain design (second lane
admission); committed display never revoked mid-page (replacement is a full commit + R4 badge);
no partial commits.
(6) Interactions: scheduling (lane arbitration), provider (standard lane rides shared bucket;
sublimit untouched), durability (TX-20: committed pages never re-paid; refresh replan safe).

**C2 — IMAGINATIVE: labeled raw-OCR preview chip.**
(1) Mechanism: when a page is OCR-done but untranslated (scenario C norm), offer an expandable
chip showing the raw recognized text, explicitly labeled "Original text (untranslated)" — never
the display image, never styled as translation. Legal today: projection state
ORIGINAL_ONLY/CANDIDATE_RUNNING (`model/PageDisplayProjection.kt:99,124`); blocks exist per-page
in the store candidate.
(2) Anchors: `PageDisplayProjection.kt:117-138`; store display source
`ReaderViewModel.kt:2904-2908`.
(3) Scenarios: C — the 47–85 % post-OCR waiting time becomes skim-able; A/E less relevant.
(4) Quality: must NOT read as machine output — quality perception risk if styling mimics the
translation overlay; mitigated by explicit label + tap-to-dismiss.
(5) Risk/invariant: never touches committed display authority; no OCR-as-translation invariant is
new but should be codified in the mapper.
(6) Interactions: none (read-only store access); small text-store hydration via
`getOrLoadPageSnapshot` precedent (`ReaderViewModel.kt:848-856`).

**C3 — In-reader queue position line.**
(1) Mechanism: render `snapshot.queuePosition/queueTotal` (already computed,
`BatchProgressProjector.kt:310-315`) in the reader bottom bar batch line
(`presentation/reader/BottomReaderBar.kt:68-90`; state at `ReaderActivity.kt:478-480,563`):
"Translating — this chapter is #37 of 200".
(2) Anchors: as above; reader already collects `translationBatchProgress`
(`ReaderViewModel.kt:2689-2693`).
(3) Scenarios: B — the jump's blindness ("is 100 coming?") is answered in place; A — pace
awareness. (4) Quality: none. (5) Risk: Low — pure label; distinctUntilChanged already dedupes.
(6) Interactions: none; prerequisite plumbing for B1's affordance.

## D/E. Perceived-speed levers

**E1 — Stage→result animation continuity.**
(1) Mechanism: treat pill → translated result → (optional refresh) as ONE scrim session: the
180 ms `TRANSLATION_CROSSFADE_DURATION_MS` (`viewer/ReaderPageImageView.kt:186,1177`) is the
missing delay-inventory item (verifier §3); keep the 250 ms scrim fade (`:674,693-697`) and the
900 ms Translated auto-hide (`:1178,718-726`) as the only terminations, so no blank flash exists
between stage end and image arrival.
(2) Anchors: `ReaderPageImageView.kt:186, 618-630, 655-666, 718-726, 1177-1178`.
(3) Scenarios: A/C — the perceived "pop" at commit is the moment users judge speed; continuous
scrim removes the flash-frame. (4) Quality: none. (5) Risk: Low — pure view-layer; no-flicker
invariant strengthened. (6) Interactions: none.

**E2 — IMAGINATIVE: next-chapter OCR pre-start while reading.**
(1) Mechanism: when the reader enters the final ~20 % of chapter N (position known:
`onPageSelected` → `ReaderViewModel.kt:1238-1253`), steer the queue so N+1 is head (reuse B1) —
its serial OCR barrier (45–70 % of chapter time, model §5.1) then runs while the user finishes
reading N, hiding `P·t_ocr` behind reading time. Deeper variant (chapter N+1 analysis pre-warm)
requires the C1 lane arbitration and is parked.
(2) Anchors: `ReaderViewModel.kt:1238-1253`; queue head semantics `ChapterTranslator.kt:424-434`.
(3) Scenarios: A — chase TTFP per chapter approaches own-(a) only; B/D unchanged.
(4) Quality: none. (5) Risk: Med — same one-active-chapter-per-source tension as C1: without
preemption the pre-start lands only if the current chapter finishes first (still beneficial);
with preemption, ≤1 page re-OCR (~20 s, model §4.4). Memory bounded (checkpoints per page).
(6) Interactions: scheduling (admission order), durability (checkpoint adoption on switch), none
with provider quota (OCR is native).

## F. Quality-perception

**F1 — Glossary/name-consistency chip.**
(1) Mechanism: after profile freeze, expose the frozen names/terms list read-only in the sheet
("Names: Raiju→雷獣 …") and optionally long-press-a-block → show original text + glossary entry.
(2) Anchors: freeze step (reconcile+freeze, model §2) `ChapterProfileBatchCoordinator.kt`
analysis phase; context ceiling constants `translator/contextual/TranslationContextChunkPlanner.kt:21,196-202`.
(3) Scenarios: all — makes the AI lane's continuity VISIBLE, which is the quality argument for
accepting C1's temporary standard-lane pages. (4) Quality: perception only; zero pipeline change.
(5) Risk: Low — read-only artifact access; i18n burden only. (6) Interactions: provider (profile
artifact already durable); none else.

**F2 — Re-translate + dedup truth with latch-respecting attempt open; peekStore seed (T928 R7/R8 re-validated).**
(1) Mechanism: force tap exists (`ReaderActivity.kt:666`; `pager/PagerPageHolder.kt:145`;
`prepareForcedRetry` `ReaderViewModel.kt:2221-2227`) but (a) duplicate taps early-return silently
(`TranslationScheduler.kt:667-672`) and (b) the terminal latch — three triggers per M8, `ReaderTranslationFeedback.kt:181-186, 193, 208-214` — swallows the re-emitted truth on an
already-translated page (verifier R7 nuance). Design: a user-initiated retry creates a NEW intent
identity (as R4); the holder calls `beginAttempt()` only after the identity-fenced admission
outcome arrives (`readerManualOutcomeFeedback` lookup, `:122-152`); dedup emits
"already translating" ManualTruth. Plus R8: `peekStore(chapterId)` from the existing
`activeStores` registry (`TranslationManager.kt:313, 214-216`) seeds rebinding holders without the
IO store-open wait (fixes L6/I3).
(3) Scenarios: A/C — retry and second-tap give immediate truth; rebind shows the live stage
instantly. (4) Quality: none. (5) Risk: Med — attempt-open must stay behind the identity fence
or stale-callback rejection breaks; peekStore is registry-hit-only with graceful miss.
(6) Interactions: scheduling (dedup path), durability (pageVersion identity), none with provider.

**F3 — Per-page QA-warning marker.**
(1) Mechanism: run-record warnings (READY_WITH_WARNINGS is chapter-level today:
`ChapterTranslationIndicator.kt:324,352` label-only; `TranslationProgressSheet.kt:615`) surface as
a small amber dot on affected pages in the reader; tap → one-line reason + re-translate (F2).
(2) Anchors: warning states `BatchProgressProjector.kt:53-70`; sheet label `:615`.
(3) Scenarios: all — trust marker across 200-chapter batches where the user cannot inspect each
page. (4) Quality: makes QA flags actionable instead of a chapter-label afterthought.
(5) Risk: Low — additive overlay element; must never alter the committed image (decorative view
only). (6) Interactions: durability (warning evidence lives in run records).

## Cross-domain summary

Reader domain adds NO new parallelism; its speed wins come from truth-latency (R1/R2/R3/R6),
information (B1/B2/B3/C3), and lane tactics that scheduling/provider domains must ratify (C1/E2 —
both hinge on relaxing one-active-chapter-per-source or admitting a reader-adjacent standard lane).
Every display-side entry preserves: committed display authority (never revoked), identity fencing +
monotonic rank + terminal latch (extended by intent identity in R4/F2, never bypassed), and
no-flicker (E1). Quality trades are explicit in exactly one entry (C1: temporary standard-lane
pages, AI refresh final).
