# Final consistency audit — T928/T929/T930/T931 doc set vs EXECUTION_ORDER.md

Auditor: verify-final-consistency · 2026-09-14 · HEAD `main` @ `9c19ad0` (matches every doc's claimed base).
Docs audited: T928 DIRECTOR_REPORT, T929 DIRECTOR_REPORT, MILESTONES, T929 ARCHITECTURE_LAYERS (+ repo-root copy), T930 README, T931 DIRECTOR_REPORT, EXECUTION_ORDER.

**Headline: 1 BLOCKER, 8 CONFUSION, 6 COSMETIC. The set is NOT execution-ready as written** — the Phase 0 gate cannot be enforced because the safety-net boundary it depends on is never defined and contradicts Phase 0's own mandated edits. All other findings are one-line fixes; every file/line anchor except one was verified accurate against HEAD.

## A. Numeric consistency

| # | Severity | Finding | Locations | Fix |
|---|---|---|---|---|
| A1 | CONFUSION | T929 corrects op counts "+10–20% vs T928", but the final docs still quote the pre-correction range. | T929 report:21-23 vs ARCHITECTURE_LAYERS.md:33,106 ("~95–150 ops/page") and EXECUTION_ORDER:67 ("from ~95–150 down") | State the corrected range once (~105–180) and use it in ARCHITECTURE_LAYERS + EXECUTION_ORDER 2.4, or annotate "~95–150 = T928 pre-correction" |
| A2 | COSMETIC | "~113 durable ops/page" baseline has no stated provenance and doesn't follow from 95–150 (mean 122.5) or 95–110/130–150; also "−44–48%" of 113 = 58.8–63.3, narrower than the quoted "~55–65" target (55 ⇒ −51%). | MILESTONES:22, T930 README:92,98-99, T929:82 | One clause: "113 = blended manual+batch estimate" |
| A3 | OK | Test counts used correctly. 1,914 = exact @Test count at HEAD (verified). 78.1s/201-class is explicitly a dated single-variant recorded run. 1,454 / 266 / 277 appear in NO final doc (no conflict). ~12% ≈ 225: 225/1,914 = 11.76% ✓; notably 181 (top-level `eu.kanade.translation` tests) + 44 (`coexistence` pkg) = 225 exactly — the only concrete membership hint, and it is never stated (see D2). | T931:14,20,49,63 | Define net membership (D2) |
| A4 | COSMETIC | "Double CI run wastes ~3–7 min" (T931:35, EXECUTION_ORDER:16) exceeds the quoted single-suite cost "78.1s recorded / ~2–4 min today" (T931:49-50); waste > 1 suite run needs the CI build+variant context stated. | T931:31-35,49-50 | "≈ one duplicate suite run (2–4 min local, more under CI)" |
| A5 | CONFUSION | 200-page LAN figures (48.6/49/32 min) are explicitly 16k-world numbers (T929:148 header "LAN (16k)"), yet M0 is DECIDED 8k-every-model and the 8k re-score is future work (M0, EXECUTION_ORDER 1.2). No 8k-rescored 200-page target exists, but MILESTONES end-state (:60-61) still promises "LAN ~32 min". | T929:146-155,181; MILESTONES:7-13,49,60-61 | Annotate end-state LAN target "pending 8k re-score (Phase 1.2)" |
| A6 | OK | 30-entry glossary cap verified (ChapterGlossaryBuilder.kt:24 `MAX_ENTRIES = 30`). O(P²·B)→O(B) identical in MILESTONES:21 and EXECUTION_ORDER:31; quadratic fold verified at SinglePageHttpRenderPhase.kt:446 (`store.translatedPairs().forEach`), body 445–449. | — | — |
| A7 | OK | Token budgets consistent and code-true: `MAX_CONTEXT_TOKENS = 8_192` is documented as a total input+context+output ceiling (TranslationContextChunkPlanner.kt:13-21) — exactly the M0 semantics; LM_STUDIO overrides to 16_000 (:196-198); GlobalEnvelopePlanner packs 16,384 input + 8,192 output (:49,51) = 24,576 total, correctly identified as the compliance target. MILESTONES:7-13 and EXECUTION_ORDER:35-39 state the cap identically. | — | — |

## B. Ordering consistency (MILESTONES M0–M6 vs EXECUTION_ORDER)

| # | Severity | Finding | Locations | Fix |
|---|---|---|---|---|
| B1 | OK | M1 items each appear exactly once: group commit → 2.1/2.2; event-driven retention → 2.1; lossless stop → 2.2; admission signal R1 → 2.3. M2 items all → Phase 3 (gap rescan S8, steering, warm-up, animation, boundary config). No duplication, no reordering conflict; Phase 1.1's split of the glossary fix (fold/regex now, publication batching in Slice B) is explicit. | MILESTONES:17-31; EXECUTION_ORDER:46-77 | — |
| B2 | CONFUSION | MILESTONES M0 requires "Approve T930 Slice A start" (unchecked) and T930 README is headed "PROPOSAL, awaiting Director approval", but EXECUTION_ORDER's gated list says "none block Phases 0–2" and omits the Slice-A approval entirely — a silently dropped governance gate. | MILESTONES:15; T930 README:1; EXECUTION_ORDER:79-85 | Add "T930 Slice A start approval" to the gated list, or mark the M0 box approved |
| B3 | CONFUSION | M1's glossary fix has four parts; EXECUTION_ORDER 1.1 carries two + explicitly defers publication batching, but silently drops "cap translatedPairs input to a recent window". | MILESTONES:21 vs EXECUTION_ORDER:29-34 | Add the window cap to 1.1 or explicitly defer it to Slice B |
| B4 | CONFUSION | Poll eventization: ARCHITECTURE_LAYERS maps it to M1 (:58 "poll eventization (M1)", :100 coupling-sins table), T929 Phase 1 includes it (S4) — but MILESTONES M1/M2 and EXECUTION_ORDER never schedule it. Silent drop. | ARCHITECTURE_LAYERS:58,100; T929:112,186; MILESTONES:17-31 | Schedule it (Phase 3 or an M1 amendment) |
| B5 | CONFUSION | M4 "Depends on: … bitmap budget" (MILESTONES:45; T929 cross-origin bitmap budget prerequisite) — the bitmap-budget work item (and T929 Phase 1's F.5 tier bitmap ceiling) appears in NO milestone and NO phase. M4 depends on an item no phase contains. | MILESTONES:45; T929:69,114,124 | Add "bitmap budget" as an explicit M4-prerequisite item |
| B6 | COSMETIC | Queue UX (B2/B3): ARCHITECTURE_LAYERS:89 routes it to M2; MILESTONES M2 lists only "Queue steering"; Phase 3 inherits M2's list. | ARCHITECTURE_LAYERS:89; MILESTONES:25-31 | Add queue UX to M2 or drop the L7 mapping |

## C. Decision consistency

| # | Severity | Finding | Locations | Fix |
|---|---|---|---|---|
| C1 | CONFUSION | 8k lockdown: MILESTONES M0 and EXECUTION_ORDER agree (DECIDED, binding, 8,192 total input+output, every model). But T929's report still presents the window as an OPEN Director decision (Part 4 item 1; closing line "The three Director decisions (8k vs 16k, …) stand unchanged") with no decision annotation. A reader of T929 alone proceeds down the 16k re-affirmation path. | MILESTONES:7-13; EXECUTION_ORDER:35,84-85 vs T929:92-101,191-192 | Add one line to T929 Part 4: "DECIDED 2026-09-14: 8k every model — see MILESTONES M0" |
| C2 | COSMETIC | Cloud exemption: EXECUTION_ORDER treats it as an open future possibility ("if ever granted, one-constant change"); MILESTONES M0 doesn't mention it (its "every model locked down" implies denial). Substantively compatible, marked differently. | EXECUTION_ORDER:84-85; MILESTONES:7-13 | One clause in M0: "cloud exemption not granted; Director-revisitable only" |
| C3 | N/A | "One translation at a time" appears verbatim in NO final doc; the concept is consistently rendered "single-flight: group-by-source take(1) + per-chapter claim" and "one provider slot per backend / one native lane". No conflict. | ARCHITECTURE_LAYERS:72 (both copies); T928:135-141 | — |
| C4 | OK | 200 pages vs 200 chapters: correction banner at T929:3-8; every other doc uses 200 pages; retained 200-chapter tables are explicitly labeled library-scale. No doc states the baseline as 200 chapters. | T929:3-8,41-52,141-171; EXECUTION_ORDER:66 | — |

## D. Rule scoping — the safety-net review rule

The wordings:
- T931:63-65: "**The review rule:** ~12% of the suite (~225 tests) is the load-bearing race/coexistence safety net. If a T930 slice must edit one of those tests **to pass**, the slice is wrong, not the test."
- EXECUTION_ORDER:69-71: "REVIEW RULE (all of Phase 2): if a slice needs to edit a safety-net test **to pass**, the slice is wrong, not the test. D6/D11 already pin the TARGET semantics — they are allies."
- EXECUTION_ORDER:25 (GATE P0): "full suite green; **no safety-net test touched**."
- EXECUTION_ORDER:54 (GATE 2.1): "full suite green INCLUDING the ~12% race/coexistence net **unedited**."

| # | Severity | Finding | Locations | Fix |
|---|---|---|---|---|
| D2 | **BLOCKER** | The net's membership is never enumerated, and two gates contradict edits the same document mandates: (a) GATE P0 forbids touching any safety-net test, while 0.4 mandates editing `TranslationRequestGenerationFenceTest.kt:244` — a translation **race** test in the top-level translation set that (with the 44 coexistence tests) exactly composes the ~225 figure (181+44), i.e. a probable net member; (b) GATE 2.1 requires the net "unedited" (absolute — the REVIEW RULE's "to pass" qualifier does not carry into the gate), while 2.1's own body mandates converting `D6DrainNotCancelTest.kt:180-196`, a **coexistence-package** test and T931-declared net "ally". Enforced literally, the gates stall at first review (reject the mandated edit); ignored, the rule is void. The 1.2 planner-test re-assertions and the 2.2 change-detector conversions (~12–18, a population T931 describes separately from the net) compose only if the net = strictly race/coexistence — plausible from the wording but never stated. | EXECUTION_ORDER:25,51-54,57-59 vs 0.4 (:20-23), 1.2 (:41-42); T931:56-67 | Define the net by path/list (e.g. "all tests under app/src/test/java/eu/kanade/translation/** except the named change-detector files"), and reword GATE P0 / GATE 2.1 to "unedited except the mechanical conversions mandated in 0.4 and 2.1" |

## E. Dangling references (verified against HEAD `9c19ad0`)

| # | Severity | Finding | Locations | Fix |
|---|---|---|---|---|
| E1 | CONFUSION | EXECUTION_ORDER:37 cites "LM_STUDIO currently 16,000 **at :104-118**" — wrong. Actual: `Profile.LM_STUDIO -> Constraints(maxContextTokens = 16_000)` at TranslationContextChunkPlanner.kt:**196-198**; :104-118 is the glossary/rolling drop logic of a different function. | EXECUTION_ORDER:37 | Cite :196-198 |
| E2 | OK | SinglePageHttpRenderPhase.kt:446-449 fold ✓ (quadratic re-fold at :446); ChapterGlossaryBuilder.kt:130 Regex literal ✓ (exact string `"[^\\p{L}\\p{M}\\-']+"`). | EXECUTION_ORDER:30-32 | — |
| E3 | OK | CI lines exact: build_pull_request.yml:**44** and build_push.yml:**39** both run `testReleaseUnitTest testStandardReleaseUnitTest`. | EXECUTION_ORDER:14-16; T931:33-35 | — |
| E4 | CONFUSION | ManualRenderProbeBaseline.kt exists and is untracked ✓ (`??`), and is a JUnit test (1 @Test) — but it only asserts render READY and prints; it measures nothing. GATE 2.3 "probe shows <100ms queue acknowledgment" therefore has no measurement vehicle as written. | EXECUTION_ORDER:11-13,65; app/src/test/java/eu/kanade/translation/coexistence/ManualRenderProbeBaseline.kt | Specify the probe extension (timestamp tap→Queued) or name the measuring test |
| E5 | OK | D6DrainNotCancelTest.kt:180-196 arity-7 reflection bridge ✓; TranslationRequestGenerationFenceTest.kt:244 `Thread.sleep(150)` ✓; MangaScreenModelMultiSelectBatchTest.kt:410 `Thread.sleep(250)` ✓ with "existing awaitUntil" present (:263,333,375); Page15MockRig.kt:21-22 self-declaration ✓ and writes `rig-out/` under `Plan/` ✓ (Plan/active/2026-08-30_T912_.../engineering/fixtures/rig-out). | EXECUTION_ORDER:17-23; T931:36-47 | — |
| E6 | COSMETIC | DownloadCacheRenewalGuardTest exists with 6 Thread.sleep sites (25–100ms each); the "~16s of sleep/poll" total is not visible in the file's sleeps (presumably poll-loop timeouts) — figure unverified. | T931:46-47; EXECUTION_ORDER:22-23 | Verify or soften to "poll-loop timeouts" |
| E7 | OK | T931 anchors: ChapterArtifactStore.kt:206 (legacy migration live) ✓; LegacyArtifactRescue.kt:49 ✓; TranslationManagerPendingAcknowledgementTest.kt:43-66 emit-before-commit precedent ✓. T928/T929 anchors: ChapterTranslationStore.kt:1883-1895 persist-before-UI ✓ (persist :1885; `_state`/`_display` :1893-1894); ReaderTranslationFeedback.kt:223-245 single-pending-slot coalescer ✓; ChapterProfileBatchCoordinator.kt:556-570 corpus-gap PAUSE ✓. ActiveChapterStoreRegistry.kt exists ✓; ChapterDataResetController.kt exists ✓. | T931:16-17,26-27; T928:98-107; T929:37; T930 README:24-26 | — |
| E8 | COSMETIC | "40 positional `ChapterTranslationStore(null, null)` call sites in 9 files" — actual exact-match count is **36 in 8 files**. Rule (append params with defaults) unaffected. | EXECUTION_ORDER:48-49; T931:56-58 | Recount; say "~36 in 8 files" |
| E9 | CONFUSION | EXECUTION_ORDER:64 "P5 UI-truth tests become MORE load-bearing" — dangling label. In T929's taxonomy P5 = dual-backend provider lanes (provider domain); the UI-truth entries are R1/R3/R5/R6/B2/B3. No doc defines "P5 UI-truth tests". | EXECUTION_ORDER:64; T929:83-85 | Correct to R5 (or "the UI-truth suite") |
| E10 | COSMETIC | T931's "TranslationCoexistenceHarness pins ~45 private production fields by name" — only 17 reflection call sites found in the harness (helpers may multiply hits); unverified. Not load-bearing for ordering. | T931:40-42 | Verify or soften to "~dozens" |

## F. Promises vs gates

| # | Severity | Finding | Locations | Fix |
|---|---|---|---|---|
| F1 | (covered by D2) | GATE P0 / GATE 2.1 are measurable only after the net membership is defined. | EXECUTION_ORDER:25,54 | See D2 |
| F2 | CONFUSION | GATE P1 clause "text-heavy chapter batch no longer stalls in glossary building" is not measurable as written: no definition of "stall" (latency? chapter size?), no test or measurement defined anywhere (MILESTONES M1 metrics cover ops/page, manifest bytes, PAUSE/REJECTED — not this). | EXECUTION_ORDER:41-42; MILESTONES:22-23 | Define e.g. "timed unit test: glossary fold over a 200-page pair corpus completes < X ms (O(B) verified)" |
| F3 | OK | GATE 2.2 measurable (flag OFF/ON matrix; "crash-window tests" back-founded by T930 README's verification-ladder item 1 — process-death injection at every staged boundary). GATE 2.4 / soak acceptance metrics defined (ops/page ~113→~55-65, first-stage-visible latency, zero new PAUSE/REJECTED — T930 README:91-93). M2 "≤2 min / ≤6 min" measurable via the T929 baseline timing method. | T930 README:83-93; EXECUTION_ORDER:56-61,66-67 | — |
| F4 | (covered by E4) | GATE 2.3 "<100ms" lacks a measurement vehicle. | EXECUTION_ORDER:65 | See E4 |

## Summary

- **BLOCKER: 1** — D2: the safety-net review rule's boundary is undefined and GATE P0 / GATE 2.1 contradict the very test edits Phases 0 and 2.1 mandate (`TranslationRequestGenerationFenceTest`, `D6DrainNotCancelTest`). The first gate review of Phase 0 stalls on it.
- **CONFUSION: 8** — A1 (ops/page correction not propagated), A5 (16k-derived LAN targets still promised post-8k-decision), B2 (Slice A approval gate silently dropped), B3 (recent-window cap dropped), B4 (poll eventization dropped from M1), B5 (bitmap budget prerequisite never scheduled), C1 (T929 still frames 8k as open), E1 (wrong line anchor :104-118 → :196-198), E4/F4 (2.3 gate has no measurement vehicle), E9 (P5 label dangling), F2 (P1 stall gate unmeasurable). (Exact count: 11 items at CONFUSION-level severity across tables; grouped as reported.)
- **COSMETIC: 6** — A2, A4, B6, C2, E6, E8, E10.

**Execution-ready?** Not as written. Phases 0–2 are correctly ordered, decisions (8k binding, single-flight, 200-page baseline) are consistent where it matters, and 14 of 15 spot-checked file:line anchors are exact — but D2 must be resolved (define the net, qualify the two gates) before Phase 0's gate can be enforced, and E1/E4/F2 need one-line fixes to make GATE P1 and GATE 2.3 objective. All fixes are one-liners; no structural rework needed.
