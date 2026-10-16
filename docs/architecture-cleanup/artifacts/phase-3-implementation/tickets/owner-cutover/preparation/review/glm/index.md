---
title: "GLM independent critique of S5 preparation r2 (Gemini)"
kind: review
comments: none
---

# Verdict

**REVISIONS REQUIRED before the atomic edit.** The r2 direction (authority-mediated commits, custody-before-admission-return, admitted retry wrapper, borrow lifetime at boundary) is correct, but FIVE items (B1-B5) are not implementable as written: the owned-receipt mechanism is not a proof and cites nonexistent members, the S-custody holder is unnamed (double-recycle possible), the extraction ranges omit the middle render/retry, and the cleanup nesting is unspecified (plus the origin-parameter compile gap). Reviewed cold against actual source at `ff21fd15` (anchors re-read this session). This critique is of Gemini's r2 replacement; the historical GLM r1 draft (incl. its withdrawn Option B) is superseded — I concur with r2's K3 rejection of Option B for the stronger reason below. Post-review precision notes from the parent (thread follow-up): the :995-1001 flake COMMENT is a hypothesis, not source proof of internal group-commit mutation — the actual mutation site must be traced in the current store/drain (ownedPage/nextVersion/patchPage) to prove the complete drift-source inventory; the store-certified discriminator (B1 ii) must NOT be forced if option (i) all-attempt-patches-through-authority establishes the exact chain; no new origin param if the mechanism no longer needs it; and the B5 grouped releases must EACH be individually nested-finally so no potentially-throwing cleanup can skip another.

# Blockers

**B1 — §5 owned-receipt progression: not executable, cites nonexistent members, and is observational equivalence, not proof.**
r2's five checks reference `snapshot.userEditedAt`, `snapshot.sourceFingerprint`, `snapshot.cleanedImageName` — NONE is a `ChapterTranslationStore.PageSnapshot` member (:428-441: `page, generation, pageVersion, blockFingerprints, leaseToken, artifactPageVersion, candidateGenerationId, dependencyFingerprint`). `userEditedAt` exists only on `TranslationBlock` (:366); page-level edit detection would be a block scan (`blocks.any { it.userEditedAt != null }`), not a snapshot field. Even corrected, the five-point checklist cannot prove absence of foreign mutation: a foreign writer can bump `pageVersion` at the same generation while leaving all five observables identical (another stage's field patch, attempt-counter writes, a batch re-plan touching none of them). Required (pick ONE, named precisely in r3):

- **(i) All attempt-relevant patches through the authority** — INCLUDING the lazy worker's internal hash patch, which today is itself a fresh-snapshot `store.patchPage` inside the receipt work lambda (`CleanedPublication.kt:394-400`; its result is ignored, :390-405 returns `Accepted` regardless). Routing it through `attempt.write` makes the accepted-receipt/predecessor chain exact by construction: every attempt-time version bump is authority-mediated, so any drift the authority did not produce is foreign by definition. NOTE: this alone does NOT cover store-internal group-commit publication (the :995-1001 flake comment: "a deferred group-commit publication can bump pageVersion at the SAME generation" — store-internal, not patch-mediated).
- **(ii) Store-certified own-drain discriminator** — the store KNOWS its own coalesced/drain writes; add the minimal store-side certification (e.g. a query/membership the authority consults: "was the version progression `from→to` at this generation produced by this store's own drain for the receipt this authority registered?") so the authority absorbs ONLY progressions the store certifies as its own. `registerReceipt` already provides the exact receipt linkage. Any non-certified drift stays a typed `Rejected`.
Either mechanism must be: single bounded re-try, under `attempt.mutex`, foreign ⇒ honored rejection. Prose verification lists are not implementable — r3 must name API, holder, and ordering.

**B2 — §5 check (1) uses an `origin` the authority does not have.**
`AttemptAuthority(store, pageKey, initial, callerJob)` (`AttemptAuthority.kt:46-56`) has no origin. Either the authority gains `origin` (one constructor param — smallest, and it IS an S1-file delta the manifest must count) or the lease-owner check lives at the boundary and the authority receives an already-verified input. r2 must state which; today the check as written does not compile.

**B3 — §3 custody holder unnamed ⇒ boundary double-recycle possible.**
r2 says the boundary finally "recycles S if not transferred" while segment 2's transfer-aware finally ALSO recycles S when `!owner.owns(S)` — on every non-alias path BOTH would fire. Required, concretely: a single-slot custody holder (private class in the pipeline file — same caliber as the approved private sealed outcome; no framework) with exactly: `register(bitmap)` (fails fast on double register), `detach()` called by segment 2's finally when it recycles S itself AND when the alias transfers into the owner, and boundary-finally `getAndSet(null)?.recycle()` — one recycler by construction. Registration point: immediately at `val bitmap = decoded.bitmap` (`SinglePageOnnxPhase.kt:521`), BEFORE any subsequent statement; decode-failure paths (:487-519) produce NO bitmap by the decode helper's contract and register nothing — state that contract. **Resume §4.1 has the same hole**: transfer-aware finally without detach ⇒ double recycle when the owner holds the alias; same holder rule, detach on transfer, single recycler after real exit.

**B4 — §2 extraction ranges omit the middle render/retry and contradict §6.**
`renderSinglePageTranslated` claims ":704–795 and :985–1060", but between those ranges sit: the retry path (:808-935 — `retryInpaintDownscaled` call, retry persist :825-836, retry permit/render :846-915, retry failure :916-935), the no-blocks recycle branch (:937-950), and the flush prelude (:951-967). §6 places the retry INSIDE renderSinglePageTranslated — so the range must include :808-935 (or the retry moves to the boundary scope; either is fine, but §2 and §6 must agree). As written the extracted function silently drops the retry.

**B5 — Cleanup nesting unspecified: a throwing `owner.release()` skips the rest.**
§1 Close lists a FLAT "lease release + owner.release() + translator borrow release". `owner.release()` is fail-closed and PROPAGATES on a receipt-protocol violation (`AttemptAuthority.kt:285-304`) — in a flat sequence that skip would strand the translator borrow, stream registry, and page lease. Required, concretely: individually nested finallys — EACH potentially-throwing release is wrapped so no failure can skip another: `try { owner.release() } finally { try { streamRegistry.clearPage(...) } finally { try { recognitionEngine.reclaimPooledMemory() } finally { engines.endTranslatorUse() } } }`, with the LEASE release in the OUTER boundary finally (today's :722-724 position). Every failure logged at its own layer; no failure suppresses another release; lease release reachable from every path.

# Major (fix with r3, not independent blockers)

**M1 — activeTranslator/epoch allocation missing.** r2 never maps `activeTranslator`/`epochAtCapture` (:259-261 init; epoch rebuild :462-478 mutates the HTTP-local reference; nothing post-join reads it). Required: initialized at boundary pre-fork (same instant as today), passed into the HTTP function; rebuild stays HTTP-local.
**M2 — governor span ownership.** The span is HTTP-phase-only (lazy creation :530-535; first-invocation settle :425-428; monolith-finally settle :1063-1066). Settle belongs in translateSinglePageHttpOnly's own finally (same lazy semantics), not the boundary finally — an HTTP failure must still settle it at phase scope.
**M3 — manifest self-inconsistent.** "Production Code (8 files)" counts item 8 (`EngineLane.kt`) which the item itself marks UNCHANGED ⇒ 7 changed production files. Add the custody-holder placement (which file hosts it). Item 4 must enumerate its concrete deltas (B1 mechanism + B2 origin param) — the manifest currently implies S1 files may change without saying so.
**M4 — drift-source inventory: the :995-1001 comment is a hypothesis, not proof.** The flake comment claims a store-internal group-commit publication can bump pageVersion, but that is an UNVERIFIED hypothesis in current source — the actual mutation site must be traced in the live store/drain (the owned-page/next-pageVersion/patchPage write paths) to prove the COMPLETE inventory of real drift sources. The mechanism choice follows the proof: if tracing shows every real attempt-time version bump flows through a patch call, option (i) (all attempt patches through the authority) alone establishes the exact chain and the store-certified discriminator (ii) must NOT be forced; (ii) is required only for proven store-internal non-patch mutations. Similarly, if the mechanism no longer needs a lease-origin comparison, no origin parameter is added. r3 must attach the traced mutation sites (@line) as the inventory proof.

# Minor

- **m1** — review/index.md K1's "withNativeLane returns null or throws; the lambda's return value is discarded" is correct — carry the custody-NOT-on-return-value statement explicitly into preparation §3 (it is currently implied).
- **m2** — §4.1: also require holder `detach()` when the owner later releases an alias, so a subsequent attempt on the same page never observes a stale holder.
- **m3** — Definitive: GLM r1 Option B is WITHDRAWN and must remain recorded as rejected (review K3). The rejection reason is stronger than foreign-edit destruction alone: the five-field equivalence check cannot certify own-progression at all (B1).

# Disposition

Gemini r3 must: name the owned-receipt mechanism (B1) with API/ordering and its drift-source coverage (M4); resolve the origin param (B2); specify the custody holder + detach + single-recycler rule for fresh AND resume (B3); reconcile §2 ranges with §6 (B4); specify nested cleanup (B5); fix M1-M3. Production edits and Gradle remain held until the parent accepts r3; my review of the ACTUAL cutover code follows the implementation handoff. Standing discipline noted: future long-running background commands carry monitored watchdogs/deadlines for compilation and setup, not only the 300s test guard; owned-run diagnostics preserved on breach; no duplicate greens.

# r3 §5 source-grounded disposition (parent-requested independent check — artifact fences)

Parent task: r3 §5 proposes all own patches through the authority but NULLS `artifactPageVersion`/`candidateGenerationId`/`dependencyFingerprint` on every write; the fences have not been accepted as removable. Investigated against actual store source:

**Q1 — can the artifact fence fields change without a live pageVersion/generation/lease change?** **CORRECTED — YES; the LAZY branch falsifies my original atomic co-publication claim (parent counterexample, thread `21cd6ea1`, re-verified in source).** The fields are MANIFEST-derived at snapshot time (`snapshotLocked` :4625-4638 reads `artifactManifest.pages[pageKey].pageVersion` / `.candidate.generationId` / `.candidate.dependencyFingerprint`-or-page-fallback). The actual mutation shapes:

- **LAZY branch — the default for active readers, and a REAL own-progression without live writes**: `publishLocked` :3715-3718 routes durableFailure==null publications to `publishLazyLocked` :3741-3781, which queues a DETACHED `PendingLazyMutation` (expected built from a queue-time `snapshotLocked` :3748, :3763-3771) and returns WITHOUT touching the manifest. `patchPage`'s `Accepted(snapshotLocked(pageKey))` :1999 therefore carries PRE-drain artifact fields. The coordinator flushes LATER — `flushPendingLazyMutationsWithJournalCapture` :1426-1434 (`mutex.withLock`) → `flushLazyMutationsLocked` :1378-1416 → `persistArtifactMutationLocked` :1390 — advancing `artifactPageVersion`/candidate/committed with NO `ownedPage` live write. So an async OWN drain CAN move the artifact fences between two authority writes while live pageVersion/generation/lease are unchanged. **Verbatim receipts do NOT suffice across flush; my earlier "all writes co-publish atomically" was false for this branch**.
- **DIRECT branch** (publishLocked :3720-3738 → `persistArtifactMutationLocked` inside the patch's mutex hold, :4057-4090 candidate persist/promote): manifest updated BEFORE `Accepted` — receipts exact here.
- **Manifest-only maintenance** (registration/sidecar-pointer transactions :3857-3909) is pointer-set-only and preserves existing durable fields BY CONSTRUCTION (LI-x comment :3858-3869) — not a drift source for our record.
- **Candidate publication **requires a page write (lease-fenced); `deleteLivePage` :4610-4623 is reset-class (generation moves).

**Q2 — non-null exclusive lease for reader entries: TRUE.** Fresh (:489-499 lease under INTERACTIVE priority), prepared (:863-873 AUTO lease), resume (inside the boundary that holds the lease). `patchPage`'s pair (:1969-1972: expected-non-null+token-mismatch ⇒ reject; expected-null+live-lease ⇒ "page lease token required") is coherent with a precondition captured under lease; the authority's initial precondition derives from a boundary-entry snapshot taken under the lease.

**Q3 — does nulling permit an obsolete commit? YES.** The fences (:1944-1965) fire only when `expected.X != null`. Nulling them at every write disables candidate-generation/dependency/artifact-version protection entirely: a commit computed against an obsolete candidate/source would land after any candidate/dependency change that the (now-blind) chain missed. The ORIGINAL :1005-1009 nulling is NOT precedent for a per-write policy — it is a SINGLE, lease-gated, fresh-snapshot re-base at the FINAL commit, justified by the lease-holder's exclusivity comment (:953-967).

**Required r3 correction (exact mechanism):** all attempt patches through the authority; artifact fence fields INCLUDED, never nulled (the :1005-1009 nulling is a single lease-gated final re-base, NOT a per-write precedent); own-drain progression absorbed ONLY through the capability mechanism below; foreign drift ⇒ typed `Rejected` (terminal-and-ignore). Nulling fields per-write must be removed from r3 §5.

# AttemptArtifactLease — minimum concrete mechanism (proposed, thread `21cd6ea1`; corrects the "verbatim receipts suffice" claim)

**Type** (new, ~10 lines, `pipeline/AttemptAuthority.kt`; operation-scoped, no registry, nothing durable):

```kotlin
/** Binds one attempt's authority to its own lazy-drain artifact progressions. */
class AttemptArtifactLease internal constructor() {
    @Volatile internal var lastOwnDrainSnapshot: PageSnapshot? = null // exact post-drain store view
}
```

Stored: ONE instance per authority; one reference copied onto each queued `PendingLazyMutation`; one overwritten bounded metadata record on the lease. **Placement (parent correction): the capability type lives in the PERSISTENCE/STORE layer (next to `PatchPrecondition` in the store file) — the store must NOT import the pipeline; the authority holds an instance of the store-layer type.** Memory bounded by active attempts; GC'd with the authority; reset/cancellation need no cleanup (a post-close stamp lands on a garbage object). **Bounded stamp record (parent correction — NOT a whole `PageSnapshot`)**: stamp page-bound metadata only — `generation`, live `pageVersion`, `artifactPageVersion`, `candidateGenerationId`, `dependencyFingerprint` — a small data class; no draft, no blockFingerprints list retained.

**Write path** (`patchPage` PUBLIC API PRESERVED — no internal parameter leaks: public `patchPage(pageKey, expected, description, update)` unchanged; an internal overload / private core carries the lease, called by the authority on EVERY attempt write — the authority remains the only attempt write path). Inside the existing store `mutex.withLock`, after the generation/pageVersion checks pass and BEFORE the artifact fence clauses (:1944-1965), an exact-match ABSORPTION (refresh, not a fence). On certification mismatch: NO refresh — every fence (lease, block, generation, live version, artifact) runs as-is ⇒ typed `Rejected`:

```kotlin
val manifestPage = artifactManifest?.pages?.get(pageKey)
val certified = artifactLease?.lastOwnDrain   // bounded record, stamped at flush
val currentDep = manifestPage?.candidate?.dependencyFingerprint
    ?: pages[pageKey]?.let(StageFingerprints::pageSnapshot)   // SAME expression as snapshotLocked :4636-4637
val effective = if (manifestPage != null && certified != null &&
    certified.generation == expected.generation &&
    certified.livePageVersion == expected.pageVersion &&      // live unchanged since drain
    certified.artifactPageVersion == manifestPage.pageVersion &&
    certified.candidateGenerationId == manifestPage.candidate?.generationId &&
    certified.dependencyFingerprint == currentDep             // same-expression compare, both sides
) expected.copy(
    candidateGenerationId = certified.candidateGenerationId,
    dependencyFingerprint = certified.dependencyFingerprint,
    artifactPageVersion = certified.artifactPageVersion,
) else expected
// ALL existing artifact/lease/blockFingerprints fences then run against `effective`
```

ALL THREE artifact fields must equal the certified record AND the live invariants (generation, pageVersion) must still equal the precondition — any partial match, absent lease/record, or live movement falls through to the existing fences ⇒ typed `Rejected` (foreign drift, S4 terminal-and-ignore). The fences are NOT disabled — absorption only re-bases them to the certified own-drain values. **`effective` FLOWS DOWNSTREAM (parent correction)**: it is not a fence-local — `publishLocked` receives `effective` and `publishLazyLocked` stamps it as the queued `PendingLazyMutation.expected` (:3763-3771 shape), so the drain's later `persistArtifactMutationLocked` validates against the certified values, not stale pre-drain ones.

**Drain path**: `PendingLazyMutation` gains the lease reference; `publishLazyLocked` :3759 stamps the CURRENT capability per queue (coalescing replaces the entry, so the coalesced mutation always carries the live lease) plus the effective expected; `flushLazyMutationsLocked` :1390 forwards it to `persistArtifactMutationLocked`, which stamps `lastOwnDrain = <bounded record from snapshotLocked fields>` ONLY after a FULL Committed manifest swap (:3901-3902 registration, :4047 candidate, :4083 direct/promotion) — computed and written under the caller's mutex hold (:1376; :1432-1434). **NO stamp on Rejected — including the partial-recoverable path :4085-4090 where `artifactManifest` IS updated from `recoverableCandidateManifest` on a Rejected outcome (parent correction: my "rejection ⇒ manifest unchanged" claim is NOT universally true).** A recoverable adoption without a stamp means the next authority write sees manifest fields ≠ certified record ⇒ no refresh ⇒ fences judge as-is; a false Rejected there is the CORRECT fail-closed direction (own-but-unabsorbed progression surfaces as a typed rejection, retryable per drain policy, never a blind accept). Direct branch: no change needed (receipts already exact).

**Returned with which Accepted**: unchanged — `PatchResult.Accepted(snapshotLocked(pageKey))` :1999. On the lazy path its artifact fields stay pre-drain; the NEXT authority write absorbs via the lease before fencing, so stale artifact fields neither false-reject own progression nor mask foreign drift. The authority's precondition advances exactly as today (last Accepted snapshot); the absorption is store-side.

**Deadlock proof (TWO locks — parent correction: not "single lock")**: an attempt write holds the ATTEMPT's authority mutex and then takes the store mutex — order authority→store, one direction only. The drain takes the store mutex (:1432-1434) and does a PLAIN stamp field write — it never takes the authority mutex and invokes no authority method, so the reverse order never occurs ⇒ no cycle. `scheduleDrain` :3779 only enqueues under the store mutex today (flush is async on the coordinator). The stamp is `@Volatile` on a store-layer object: data handoff, not locking.

**Scenario coverage**: multiple authority writes before drain (each queue re-stamps; absorption re-bases per write) ✓ · writes during/between taskwork and drain (drain holds the mutex; interleave point is queue vs flush only) ✓ · foreign artifact-only change (≠ certified snapshot ⇒ fence rejects, typed) ✓ · stale/rejected drain (no stamp, no absorb, requeue per :1400-1405) ✓ · reset (generation fence rejects first; lease GC'd) ✓ · cancellation (garbage lease; no cleanup) ✓ · live version changed after certified snapshot (pageVersion invariant fails ⇒ typed Rejected) ✓ · no unbounded memory (one object + one field per attempt) ✓.

**Residual (unchanged store behavior, noted)**: `flushLazyMutationsLocked` :1407-1413 — on Throwable it releases the failed mutation's credit, drops ALL later queued mutations' credits, and rethrows (their page mutations stay live-only; durability catch-up per existing rules). The mechanism tolerates this: vanished queue entries simply never stamp.

# r5 on-disk status (thread `21cd6ea1`) — CORRECTED

**Correction (parent): my earlier r5 claims were STALE — current r5 on disk HAS the AtomicReference custody holder (B3 named) and the :704-1060 range (B4 covers the middle render/retry). Those two items are FIXED on disk; do not re-raise.** Remaining r5 observations at last read:

- §5 "query OR token/receipt OR sequence" disjunction — now MOOT as a review demand: the parent accepted the concrete capability direction; Gemini implements the store-linked chain (the refined proposal above, with the parent's corrections) in the ONE atomic edit.
- **Invented API flagged**: r5's `PatchResult.Rejected.Detail.ArtifactPageVersionMismatch` does NOT exist — the `Detail` sealed interface maps only `ArtifactPublicationFailed` (:4664-4671; interface at :463). Reference it as a NEW API addition in the manifest or drop it.
- Cleanup sketch must still include `streamRegistry.clearPage(...)` and must not silently swallow `reclaimPooledMemory()` failure — each release failure logged at its own layer (B5 per-release nesting: swallow nothing silently, skip nothing).
- C1-C7 case policy must revert to the approved plan verbatim (no automatic retry/fallback); governor first-invocation settle semantics and epoch/activeTranslator mapping still to be stated.

# Status (thread `31870c70` authorization)

The parent ACCEPTED the capability direction and authorized Gemini's ONE atomic actual cutover edit + tests. NO Gradle until the actual diff passes parent pre-launch review. I hold for the ACTUAL Gemini source and then run the cold code review against it (mechanism conformance to the corrected proposal above, custody slot discipline, nested cleanup, C1-C7 approved case policy, governor/epoch mapping, manifest accuracy) BEFORE any test launch. No new model, no subagents, no source edits by me.

# r4 verdict + refined store-linked proposal (thread `21cd6ea1`, follow-up)

**r4 NOT approved.** §5 still observational equivalence (six-invariant own-drain guess; "stableFingerprint" is hypothetical and type-confused — no such discriminator exists; `StageFingerprints.pageSnapshot` :2220/:4637 is a content hash, not provenance). The store-linked receipt chain is real and must be used: `PendingLazyMutation` queued at :3759 → `flushLazyMutationsLocked` :1378-1416 (mutex) → `persistArtifactMutationLocked` :1390 → manifest swap → receipt. Also required of r5, per parent instructions (all confirmed against source):

- **§3 custody**: AtomicReference slot — `register(bitmap)` immediately at decode; `take()` = `getAndSet(null)` called INSIDE the owner-guarded block (producer owns through its finally; transfer into `CleanedOwner` or local recycle both take); boundary finally cleans ONLY what is still registered (`getAndSet(null)?.recycle()`). NO boolean flag. Kills the r4 double-recycle (`markTransferred` covers alias only; non-alias local recycle + boundary close still both fire).
- **§4 cleanup**: every potentially-throwing release individually nested-finally (B5); streamRegistry clear included; no silent swallows.
- **C1-C7**: revert to the approved plan case policy verbatim — the rename in r4 changed SEMANTICS (native post-join timeout became "automatic retry/fallback"), which violates the approved post-join timeout disposition: timeout is a typed terminal outcome; retry exists ONLY as the separate third reader path (native 1 / inpaint 2 / retry reader 3; batch delegate untouched). No automatic retry/fallback inside the cutover.
- **Governor**: approved semantics — lazy span creation at phase entry (:530-535), settle on the HTTP phase's own finally at phase scope (:1063-1066 equivalent), NOT settle-on-response.
- **Epoch/activeTranslator (M1)**: allocated at boundary pre-fork, passed into the HTTP function; rebuild stays HTTP-local (:462-478); nothing post-join reads it.

**Store-linked receipt chain — bounded concrete delta (the mechanism above, made exact):**

1. Persistence/store layer (store file, next to `PatchPrecondition`): `AttemptArtifactLease` class + bounded `CertifiedArtifactProgress` record + one lease field on `AttemptAuthority`-equivalent usage from the pipeline (the AUTHORITY depends on the store type — never the reverse). (~15 lines)
2. `ChapterTranslationStore.kt`: (a) `PendingLazyMutation` + lease reference field; (b) `publishLazyLocked` :3759 stamps the current lease + effective expected per queue; (c) `flushLazyMutationsLocked` :1390 forwards it; (d) `persistArtifactMutationLocked` internal lease param, stamps the bounded record after EVERY FULL Committed manifest swap (:3901-3902 registration, :4047 candidate, :4083 direct/promotion) — never on Rejected, including the recoverable-adoption path :4085-4090; (e) public `patchPage` API preserved — internal overload/private core carries the lease and the exact-match absorption block before the artifact fences, `effective` flows into `publishLocked`. (~35 lines)
Nothing durable, no schema change, no third registry; bounded to the two files. Absorption derivation: `dependencyFingerprint` compares the SAME snapshotLocked fallback expression on both sides (:4636-4637 — candidate fingerprint, else `StageFingerprints.pageSnapshot` of the live page) — never a "grace disarmed" null substitute; candidate-less pages compare page-hash to page-hash.
