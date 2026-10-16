---
title: "S4 results - retain uncertain cleaned publications"
kind: spec
comments: none
---

# Status

Implemented per ticket + parent brief: retain-on-uncertainty publication policy. NEW name retained on commit throw AND cancellation (outcome uncertain — never deleted, never registered into retirement, truthful ERROR uncollected-debt ledger); rejected-name physical deletion requires INDEPENDENT never-accepted-live-name knowledge AND the store's mutex-held point-in-time authorization, delete outside the store lock; non-lazy (fresh private versioned name) and lazy (already-accepted provenance → always retain) caller policies are SEPARATE. Focused verification green on the FIRST run of each authorized gate (iteration green = final): standardDebug 27/0 + DevDebug 27/0. No storage redesign, no owner cutover, no S5 surfaces. No commits/staging/pushes; parent publishes.

# Source manifest (exact, with SHA256 at delivery)

MODIFIED production (3 files):

- `app/src/main/java/eu/kanade/translation/persistence/chapter/CleanedImagePublisher.kt`
`5E164DE8E50D25011A86E19AE996954BB44EAA39639BBAA6290C671ED5163A76`
Failure matrix rewrite: commit CancellationException → RETAIN newName, ERROR log `retained=true commitOutcome=uncertain (uncollected debt if the store never accepted)`, rethrow (was: delete + rethrow). Commit non-cancellation throw → RETAIN, same ERROR ledger, returns `Rejected(unpublishedCleaned=false)` (was: delete then return true). `PatchResult.Rejected` → physical delete ONLY via the new `mayDeleteRejected` seam (default `{ false }` — fail-closed); seam contract: true ONLY on independent never-accepted knowledge AND store mutex-held authorization; seam failures fail closed with CancellationException rethrown; unauthorized retained name logs ERROR `uncollectedDebt=true` (plan §10 ledger), generic Rejected summary stays WARN. Accepted+previous-file matrix (mayDeletePrevious / retirePrevious / delete-fallback) UNCHANGED. KDoc states: a Rejected alone does NOT establish never-accepted.
- `app/src/main/java/eu/kanade/translation/persistence/chapter/ChapterTranslationStore.kt`
`63F53E2A2B4CBD1DE8458206DAB4D3EF9A004EFCCF71B6F89F9342D02C31DC13` (+14 lines, one function, zero behavior change to existing paths)
NEW `suspend authorizeCleanedImageDeletion(pageKey, name)` = `mutex.withLock { mayDeleteCleanedImage(pageKey, name) }`. KDoc is point-in-time honest: the mutex protects ONLY the decision; the caller's delete runs after return, outside the lock, and can interleave with later mutations — safe only with independent never-accepted provenance. No deadlock (predicate is lock-free reads).
- `app/src/main/java/eu/kanade/translation/pipeline/CleanedPublication.kt`
`E8AE77C6474BE1272012EE79C08B74AECB1E6A9FDDB8064ABA2BE3A6FB35A5BE` (+2 seams, one per production publish site)
In-phase site: `mayDeleteRejected = { name -> store.authorizeCleanedImageDeletion(pageKey, name) }` — valid because the name is a FRESH PRIVATE VERSIONED name whose only possible acceptance is this commit, which just returned Rejected (comment states it). Lazy receipt-worker site: `mayDeleteRejected = { _ -> false }` — the worker exists because an earlier publication ALREADY accepted a live name; the hash-patch Rejected says nothing about that acceptance; fail-closed retain even when the point-in-time check would authorize (comment states it).

MODIFIED tests (1 file):

- `app/src/test/java/eu/kanade/translation/persistence/chapter/CleanedImagePublisherTest.kt`
`4B32AD89648DAA4F10400B3D53E46134C600EFFBFA35F457CD39C7AE28E4A0A6`
11 tests (all rewritten as one coherent unit after the F1 duplicate/ghost-API incident — see history): accepted-commit old-file deletion; rejection deletes ONLY under explicit seam authorization; rejection WITHOUT authorization retains (default seam; reason asserted `shouldContain "pageVersion"` — no brittle exact string); commit-throw retains; commit-cancel retains + rethrows the SAME CancellationException instance; rejected-hash-patch with the name made LIVE between write and rejection → real store authorization REFUSES → retained, live pointer intact; SAME-NAME prior-live-then-cleared (store accepts `new.jpg` live once, updatePage clears it, publisher's write resolves to the SAME name as writeVerifiedCleanedImage does for matching existing verified bytes; point-in-time authorization for that name is TRUE yet the lazy-policy seam RETAINS — deterministic parent scenario proof); write-cancel propagation; committed-display retention (unchanged); retirement-callback (unchanged); store-authorized never-registered name deleted — outside-lock proof is TWO-PART (event ordering `[write, commit, authorize, delete]` + source-read that `mutex.withLock` returns on completion; the test comment explicitly disclaims events-alone). None of the 11 hold gates across children (direct calls only). No sleeps, no invented APIs.

NOT touched: HTTP/native phases, scheduler, admission, queue, owner cutover surfaces (S5), retirement registries (no blind registration added).

# Run provenance (registered monitored shells; actual exits retained; guarded route per validation audit)

Evidence root: `C:\Users\ADMIN\AppData\Local\Temp\opencode\phase-3-s4`. Guard `s4-guarded-run.sh` copied per-attempt from the S3 copy; `fc /b` vs approved S1 v3.4: no differences (re-verified during pre-launch). Preflight order (parent-corrected): atomic one-use claim `mkdir "$EV/.launch-claim"` BEFORE any per-attempt copy/log write; rejects existing `guarded-run.log`/`*dump*`; then init+guard copies; then init/executable check; `PREFLIGHT_OK` before Gradle. Attempt dirs were ABSENT until each launcher prepared them. Focused pair both gates: `CleanedImagePublisherTest` + `ChapterTranslationStoreLazyPersistenceTest` (the lazy publisher path lives in the latter's territory).

- Gate 1 — FINAL STANDARDDEBUG (first run, iteration green = final): shell `3e53459e-a14f-4c03-98cf-b002bbccfdf1`, guard run id `f4b8616c-0190-4547-b8e8-354bd998bc88`, dir `s4-std`, task+guard tag `:app:testStandardDebugUnitTest`, results path `app/build/test-results/testStandardDebugUnitTest`. Exit 0, `S1_GUARD_BREACH=0`, within 300s. XML `s4-std\xml\`: CleanedImagePublisherTest tests=11 failures=0 errors=0 skipped=0; ChapterTranslationStoreLazyPersistenceTest tests=16 failures=0 errors=0 skipped=0 — 27/0.
- Gate 2 — DEVDEBUG: shell `ac2ad4d5-3b47-45f2-a364-5f911008d930`, guard run id `f20bd145-6590-4471-9652-cd1cb7c7bf40`, dir `s4-devdebug`, task+guard tag `:app:testDevDebugUnitTest`, results path `app/build/test-results/testDevDebugUnitTest`. Exit 0, breach 0, within cap. XML: 11/0 + 16/0 — 27/0. NO StandardRelease run in S4.
- Format (single read-only inventory): shell `1151a709-9171-4a36-90bf-c01f36be1c07`, dir `format-s4`. `:app:spotlessKotlinCheck` exit 1 with EXACTLY ONE violating path `src\main\java\eu\kanade\translation\engines\inpainting\litert\LiteRTMangaInpaintingEngine.kt` (pre-existing debt); NO truncation marker in the log (complete path list, rg-verified). ZERO violations in any S4-touched file. Formatting-only result → NO test rerun (standing rule).
- Post-run hashes = manifest hashes exactly (bytes the gates validated are the delivered bytes). Report SHA `EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD` unchanged. Tracked content diff = exactly the 4 manifest files (275+/14−); the 10 baseline metadata M entries content-empty.

# Review history (r0 → r1, per parent/Gemini briefs)

- r0 checkpoint REJECTED pre-launch: F1 compile blockers (duplicate test method + one leftover invented `pagesSnapshotForTest` reference from a partial edit — the earlier "removed" claim was false at file level); F2 material gap (never-accepted knowledge NOT implemented — Rejected was conflated with never-accepted; lazy path's name had historical acceptance a point-in-time check cannot see). Gemini pre-validation confirmed the same two findings, no additional material ones; throw/cancel retain matrix + outside-lock delete approved.
- r1: test file rewritten whole (11 unique tests, zero ghost refs); provenance encoded as CALLER POLICY through the same seam (in-phase store-authorized, lazy fail-closed `{ _ -> false }`); store KDoc de-blanketed ("cannot race" removed → point-in-time honesty); second parent pass caught (a) prior-live test not using the SAME name (fixed: store accepts `new.jpg` first, clears, publisher writes the same name), (b) retained-debt ledger WARN→ERROR per plan §10, (c) outside-lock claim bounded to ordering+source-read. All three fixed before authorization.

# Remaining limits

Host-only evidence (JVM); actual device I/O for retained files and sweeps remains device evidence per the plan's D-gates. The lazy path's retained files are UNBOUNDED uncollected debt by design — reclaimed only by a future explicit sweep (out of S4 scope; no reset/teardown path claims otherwise). Retention-on-uncertainty means a commit-throw/cancel file stays on disk even when the store provably never accepted it (the publisher cannot know) — an accepted trade per the approved failure matrix. The outside-lock proof is ordering+code-read, not a mutex-state assertion (no existing API exposes the mutex). No owner cutover: publication call sites remain pre-S5.
