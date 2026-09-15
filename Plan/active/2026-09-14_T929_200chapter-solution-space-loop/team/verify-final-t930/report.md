# T930 adversarial verification — red-team report (verify-final-t930)

2026-09-14. Verifier: adversarial red-team of
`Plan/active/2026-09-14_T930_durability-group-commit-implementation/README.md`
against production code and the pinned invariant tests. Evidence is file:line.
Read-only; no tests were run. Attacks are labeled PLAN-GAP (text
ambiguity/omission the author can fix in wording) vs UNSOUND (the design as
stated would break the app or the suite).

Binding invariant used throughout: the redesign may change WHEN bytes hit
disk, never WHO owns a write.

---

## Surface A — "Slice A breaks 0 behavioral tests; flag OFF = today's IO choreography"

### A1. Event-driven retention — attack largely FAILS, one ambiguity survives

Attacked claim: rewiring retention to events could change sweep TIMING and
break the orphan-count tests flag-OFF.

Evidence:

- Today's retention triggers are: open path with `existing.pages.size <= 8`
  (`app/src/main/java/eu/kanade/translation/artifact/ChapterArtifactStore.kt:174-178`),
  post-commit sweep inside `checkpointOcr` CLOSE/REBASE
  (`ChapterArtifactStore.kt:744-749`), post-commit sweep in `cancelCandidate`
  (`ChapterArtifactStore.kt:1548`), and teardown via `StorePersistenceScheduler.close()`
  (`app/src/main/java/eu/kanade/translation/store/StorePersistenceScheduler.kt:119-128`).
  >8-page open sweeps are ALREADY deferred today (T921,
  `ChapterArtifactStore.kt:166-173`) — the plan's "open (>8 pages deferred)"
  is parity, not a change.
- The exact-count tests are trigger-agnostic: `reconcileRetention uses
  canonical layout paths...` (deletedCount 6) calls `reconcileRetention`
  directly (`app/src/test/java/eu/kanade/translation/artifact/ChapterArtifactStoreTest.kt:758`,
  `:774`); `deletedCount shouldBe 0` backup test also direct
  (`ChapterArtifactStoreTest.kt:908`); the retired-run orphan test drives
  `ArtifactRetention` directly
  (`app/src/test/java/eu/kanade/translation/artifact/ChapterArtifactStoreRetireActiveRunTest.kt:108-110`);
  the reload sweep test goes through a fresh open of a small chapter
  (`ChapterArtifactStoreTest.kt:778-808`), which survives as long as the
  <=8-page open sweep stays. So the "deletes exactly 6 orphans" and reload
  sweep tests do NOT pin trigger timing; the event-driven rewiring, if it
  keeps `reconcileRetention` semantics and the open-path sweep, breaks none
  of them.

Surviving PLAN-GAP: the README says "full reachability crawl only at open
(>8 pages deferred), **teardown**, user reset" (README Slice A item 4). Today
`closeAndFlush()` deliberately does NOT sweep (T921: it runs on the
reader-entry finally path and the crawl stalled first opens ~15s;
`StorePersistenceScheduler.kt:103-117`, esp. the comment at :104-112), while
`close()` does (:119-128). If an implementer reads "teardown" as
closeAndFlush/probe-store teardown and adds a crawl there, Slice A
re-introduces the exact stall T921 removed, flag-OFF, with no flag to revert.
Required wording fix: name the trigger set exactly — "teardown = existing
`close()` sweep only; `closeAndFlush`/probe teardown remains sweep-free per
T921" — and state that Slice A changes no trigger, only codifies today's.

Verdict: PLAN-GAP (trigger parity must be stated explicitly; otherwise sound).

### A2. Writer registry — ambiguity survives, attack partially survives

Attacked claim: if the registry serializes writers that today run
concurrently, timing-observable tests change flag-OFF; if it does not
serialize, it adds nothing.

Evidence: `ActiveChapterStoreRegistry` today is a chapter-keyed STORE map with
per-chapter opening locks only
(`app/src/main/java/eu/kanade/translation/ActiveChapterStoreRegistry.kt:20-24`,
`:73-95`); there is no write-mutual-exclusion at registry level. Actual
serialization today is the store mutex + the durable-manifest CAS
(`staleManifestRejection`, `ChapterArtifactStore.kt:1641-1649`). The plan
says "ALL writers register — store, probe stores, DurableChapterStatusResolver,
LegacyChapterMigrationSource, health-verify" (README Slice A item 2) but never
says whether registration EXCLUDES or merely RECORDS.

- If it excludes (blocks a second writer while another is mid-window): the
  probe/verify republish choreography that `verifyLegacyArtifactHealth`
  performs behind a caller's back (the LI-4 scenario,
  `app/src/test/java/eu/kanade/translation/artifact/ChapterArtifactStoreStaleManifestRetryTest.kt:14-30`)
  would be reordered or blocked — a flag-OFF timing change that the
  coexistence net can observe.
- If it only records: it adds diagnostics and a hook, but zero protection;
  the actual flag-ON protection must come from something else (see Surface C).

Required wording fix: "the registry is observability-only flag-OFF; exclusion
(employed only flag-ON) is: second writers flush the owning store's staged
buffer before their own publication and re-read durable truth" — or the
equivalent. As written, Slice A item 2 cannot be both "no behavior change"
and protective.

Verdict: PLAN-GAP (state enforce-vs-record; both readings are implementable
from the current text and one of them violates the Slice A gate).

### A3. Schema-guard cache — attack SURVIVES; concrete flag-OFF suite break

Attacked claim: cached schema decisions vs tests that mutate schema-relevant
state mid-test.

Evidence: the future-schema guard is evaluated on EVERY read of the backup,
fresh from disk: `loadOrMigrate` reads the backup and refuses future schemas
(`ChapterArtifactStore.kt:130-143`), and `readManifest()` re-checks on every
call (`ChapterArtifactStore.kt:226-232`) — which is also the CAS reference
read used by every transaction (`:1642`). The test
`usable primary wins over a future backup without touching it`
(`ChapterArtifactStoreTest.kt:620-636`) uses ONE store instance, calls
`loadOrMigrate` twice, and writes a future-schema backup BETWEEN the calls;
the second load must re-read the backup and preserve it (`:635`).

Under the plan's literal wording — "future-schema primary/backup read once
per store instance; invalidated on migration/upgrade" (README Slice A item 3)
— the second `loadOrMigrate` uses the cached first-load decision ("no future
backup"), falls through the guard at `:130`, reaches the ARTIFACTS branch, and
executes `io.delete(backupName())` at `:193-195` (backup discard after clean
load). Result: the test at `:635` fails flag-OFF (there is no flag on Slice
A), GATE 2.1 ("full suite green ... unedited") fails, and the REVIEW RULE
("if a slice needs to edit a safety-net test to pass, the slice is wrong")
fires. Worse than the test: in production this exact code path is how an
older build opening a chapter written by a NEWER build would DELETE the
newer build's document — the guard exists precisely to prevent that
("Never touch the future backup", `ChapterArtifactStore.kt:131-137`).

"Invalidated on migration/upgrade" does not cover this: no migration or
upgrade event occurs between the two loads; the future document simply
appears on disk mid-instance.

Required wording fix: scope the cache to the schema-NORMALIZATION decision
only (`normalizeSupportedSchema`, `ChapterArtifactStore.kt:1751-1756`) and
explicitly exempt the future-schema guard reads at `:130` and `:230` from any
caching — or invalidate on every backup write. As written, item 3 is
unsound-by-literal-reading; I rate it PLAN-GAP with HIGH breakage risk
because the fix is a sentence, but an implementer following the text
literally ships data loss.

Verdict: PLAN-GAP (fix the wording; literal implementation is a data-loss
bug + guaranteed gate failure).

---

## Surface B — "drain-to-commit stop" vs the D6 bounded-drain contract

Attacked claim: the T930 stop language can be read as "always commit on
stop", contradicting the grace-expiry-does-not-commit contract.

Evidence:

- D6 pins BOTH branches. Branch 1: a cancelled window DRAINS the in-flight
  call to terminal commit
  (`app/src/test/java/eu/kanade/translation/coexistence/D6DrainNotCancelTest.kt:226-278`,
  commit asserted at `:263-267`, ledger consumed at `:272`). Branch 2: grace
  expiry cancels cleanly and must NOT commit
  (`D6DrainNotCancelTest.kt:285-333`, "grace expiry must NOT commit" at
  `:313-317`, ledger entry stays unresolved exactly once at `:318-329`).
  The bound is real production code:
  `PROVIDER_DRAIN_GRACE_MS = TranslationPipeline.ATTACH_TIMEOUT_MS`
  (`app/src/main/java/eu/kanade/translation/scheduling/RollingAutoCoordinator.kt:1359`),
  pinned by D6 at `D6DrainNotCancelTest.kt:210-218` and D7
  (`D7EngineEpochStopRaceTest.kt:258`).
- D7 extends the same two-branch contract to the engine-epoch path: drain to
  terminal commit (`D7EngineEpochStopRaceTest.kt:284`) vs "grace expiry closes
  under the call and the epoch retry lands on the rebuilt translator"
  (`D7EngineEpochStopRaceTest.kt:430`).
- The T930 README, Slice B item 8: "translator stop/cancel finishes the
  in-flight page to its next commit point, publishes, then releases. User
  stop becomes lossless". Race register #4 mitigates "User stop mid-page"
  with "drain-to-commit stop (8)". NOWHERE does the README mention the grace
  bound, the expiry branch, or the no-commit-on-expiry rule.

Two failure modes from the literal text:

1. An implementer builds an UNBOUNDED drain ("finishes the in-flight page")
   — a wedged provider hangs reader teardown indefinitely (today the grace
   cancels it). This is an app-breaking regression on a hung network call.
2. Or the implementer commits whatever is staged at expiry — violating D6
   branch 2 (`:313-317`) and the D9 ledger contract at expiry (entry must
   remain unresolved, `:318-329`). Under the overlay's own GATE 2.2
   ("crash-window tests green BOTH ways"), that fails the suite flag-ON.

Also "User stop becomes lossless" overstates: on grace expiry the page is
deliberately NOT committed (cancellation-class); stop is lossless only within
the grace window.

Required wording fix (register #4 or item 8): "drain-to-commit stop is
bounded by PROVIDER_DRAIN_GRACE_MS; within the grace the in-flight call
finishes to its next commit point and publishes; on expiry the call is
cancelled cleanly, NOTHING commits, and the D9 attempt entry stays
unresolved (D6/D7 contract unchanged)". The DESIGN is compatible with D6
(this is a wording omission, not a design break) — the register simply never
reconciles the two branches.

Verdict: PLAN-GAP (must reconcile; uncorrected, one of the two D6 branches
breaks flag-ON).

---

## Surface C — staged mutations + fenced CAS seams

### C1. Flush-time re-check / second writer during the staged window — attack SURVIVES

Attacked claim: two writers can both pass against stale state and collide at
flush; the plan covers force-flush-before-compare but not the reverse order.

Evidence:

- Today's CAS compares the caller's manifest against the DURABLE document,
  read fresh: `staleManifestRejection` = `readManifest()` + whole-object
  equality (`ChapterArtifactStore.kt:1641-1649`). Every transaction seams it
  (`:418, :523, :862, :1031, :1336, :1381, :1412, :1498, :1559`), so today
  the durable manifest is a true serialization point and the stale window is
  ~zero (mutations are built and CAS-checked in one locked pass,
  e.g. the facade's `persistArtifactMutationLocked` chain
  `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt:1912-2098`).
- The one-shot stale retry exists at exactly TWO seams — `publishActiveRun`
  (`ChapterArtifactStore.kt:350-355`) and `checkpointOcr` (`:478-487`) —
  both wrapped by `retryOnStaleManifest` (`:1671-1686`). `persistLiveCandidate`
  (`:1080`), `promoteLiveCandidate` (`:1189`), `cancelLiveCandidate`
  (`:1322`), `openCandidate` (`:1404`), `recordDurableFailure` (`:790`),
  `cancelCandidate` (`:1492`) have NO retry; stale rejection is final there.
- Under flag-ON staging, the store's cached manifest (`artifactManifest`,
  updated at `ChapterTranslationStore.kt:2036, :2074, :2096`) deliberately
  diverges from durable truth for up to the 250 ms debounce (README item 5).
  The plan's mitigation #1 ("Fenced preconditions ... force-flush BEFORE
  comparing — the durable manifest remains the comparison truth at those
  seams", README item 5; register #1) covers the store comparing ITS OWN
  preconditions. It does NOT cover: a second writer (probe store, health
  verify, migration) publishing durable changes DURING the staged window, so
  that the store's later combined flush is built on a stale base. At flush,
  `staleManifestRejection` compares staged-base vs mutated-durable →
  Rejected. Today the same collision is impossible (no window) or retried
  (two LI-4 seams). Flag-ON, EVERY page publication inherits the LI-4 race
  shape with NO retry outside the two seams. The observable result is page
  writes failing (`publishLocked` false → `restorePageLocked`,
  `ChapterTranslationStore.kt:644, :1876-1897`) — new REJECTED/failed-write
  outcomes, violating the plan's own acceptance metric "zero new
  PAUSE/REJECTED outcomes" (README verification ladder item 4).
- Register #2's mitigation for second writers is "writer registry (2); disk
  CAS at LI-4 republish seams" — a registry that (per A2) is not stated to
  exclude, plus CAS retained only at the two LI-4 seams. Neither is a flush
  story.

What is missing from the plan text (any ONE of these closes the hole, but the
plan must name one):

1. Registry-gated exclusion: second writers flush+quiesce the owning store's
   staged window before publishing (flag-ON serialization — acceptable,
   flag-scoped); or
2. Flush-time rebase: on flush rejection, rebuild the staged mutations
   against the fresh durable manifest and retry once (generalizing
   `retryOnStaleManifest` beyond LI-4); or
3. Declare all facade-side seams force-flush-before-mutate (i.e., staging
   only accumulates within a single page pipeline, flushed at each fenced
   entry) — which sharply limits what actually batches and should be stated.

Verdict: PLAN-GAP on the plan text, and UNSOUND if built exactly as written
(flag-ON path manufactures stale-CAS failures today's code cannot produce).
This is the highest-value attack; the race register needs a new entry:
"Second writer commits during staged window → flush CAS rejects → [chosen
rebase/exclusion story]".

### C2. Staged-but-unflushed mutations at process death / paid-call ordering — attack SURVIVES (narrow, fixable)

Attacked claims: (a) does the plan specify RECOVER or DROP for staged state
at process death, consistently with the D9 attempt-ledger contract; (b) a
paid call whose sidecar mutation was only staged would violate "entry durable
BEFORE the paid call".

Evidence:

- Register #3 says "Process death with staged unpublished state | recovery =
  today's stale-writer path; checkpoints always durable". But today's
  interrupted-stage recovery operates on DURABLE RUNNING stages
  (`recoverInterruptedStages`, `ChapterArtifactStore.kt:183-196` and
  `:1560-1627` — it rewrites RUNNING→FAILED_RETRYABLE in the durable
  manifest). Under staging there are no durable RUNNING stages to recover;
  the correct semantic is DROP (stage re-runs from last publish). The plan's
  own Slice B item 8 says this ("stage re-runs from last publish") for stop,
  but register #3's "recovery = today's stale-writer path" names a path that
  flag-ON will find nothing to do. That is reconcilable but must be stated
  as DROP-with-ledger-trace, else an implementer "recovers" from empty
  durable state and ships a no-op.
- D9 ordering: the attempt ledger entry must be durable BEFORE the paid call
  (`app/src/test/java/eu/kanade/translation/coexistence/D9AttemptLedgerTest.kt:219-239`,
  "the attempt entry must be durable BEFORE the paid provider call" at
  `:226-229`). Today the entry is written synchronously through the store:
  `recordStartLocked` under the store mutex
  (`ChapterTranslationStore.kt:388`, owner at `:238`) →
  `ChapterAttemptLedger.persistLocked` →
  `store.artifactStore?.publishAttemptLedger` → crash-safe
  `documents.publishJson`
  (`app/src/main/java/eu/kanade/translation/store/ChapterAttemptLedger.kt:150-161`;
  `ChapterArtifactStore.kt:277-279`). The T930 README's commit-point list
  (Slice A item 1) and the race register NEVER mention the attempt ledger.
  If an implementer routes "all store mutations" through the staged buffer,
  the ledger entry inherits the 250 ms debounce and a paid call can start
  with no durable entry — resurrecting the exact false-crash/uncapped-death
  defect D9 exists to prevent (three unresolved deaths pause the chapter,
  `D9AttemptLedgerTest.kt:285-294`).
- Consistency check the plan also omits: DROP of staged state at death IS
  consistent with D9 (entry unresolved → consumed at startup reconcile,
  `ChapterAttemptLedger.kt:122-133`; page re-runs) — provided the entry
  itself was never staged. So the two fixes are one sentence: "D9
  attempt-ledger publishes are direct durable writes, never staged; staged
  page state is DROPPED at death (ledger is the only trace), matching
  register #3."

Verdict: PLAN-GAP (name the ledger as exempt; restate register #3 as DROP).

### C3. Bonus finding (from the same seam family): non-transactional `publishManifest` callers can durable-ize staged state

`ChapterArtifactStore.publishManifest` is an UNCONDITIONAL synchronized
publish — no `staleManifestRejection` (`ChapterArtifactStore.kt:235-237`).
Two in-facade callers build their publication from the store's CACHED
manifest: glossary pointer updates (`updateGlossary`,
`app/src/main/java/eu/kanade/translation/store/ChapterGlossaryStore.kt:77-92`)
and `persistGlossaryLocked` (`:114-125`), plus backup-recovery paths. Under
flag-ON staging, the cached manifest includes staged page mutations; a
glossary update publishing `manifest.copy(glossary = pointer)` would (a)
write staged page truth to durable OUTSIDE the commit discipline, and (b) if
staged mutations defer SIDECAR writes, install manifest pointers at sidecar
files that do not yet exist — a dangling pointer, the exact failure mode
T924-SC-22 eliminated ("Sidecars are published FIRST ... one atomic manifest
publication installs their pointers SECOND",
`ChapterArtifactStore.kt:285-292`). Alternatively, if the cached manifest
excludes staged state, the glossary publish races the flush (C1). Register
#9 ("staged pending-version stamping, D5 rider") gestures at glossary
ordering but does not name this hazard. Required wording: "glossary/legacy
pointer publications force-flush the staged buffer first and are built only
from durable truth" (or are staged under the same discipline).

Verdict: PLAN-GAP (must be added to the register; uncorrected it is a
flag-ON data-integrity bug).

---

## Surface D — legacy lane coexistence

Attacked claim: the writer registry/commit discipline may orphan the legacy
flat-file persist lane, leaving two commit disciplines alive.

Evidence:

- Post-cutover the legacy flat-file lane is already a no-op:
  `StorePersistenceScheduler.persistLocked()` returns true WITHOUT writing
  once authority is ARTIFACTS
  (`StorePersistenceScheduler.kt:74-85` — "all page durability is handled by
  the artifact bridge in publishLocked()"). So for ARTIFACTS chapters the
  T930 commit-point contract covers the only live page lane. The registry/
  staging cannot orphan what no longer writes.
- PRE-cutover, however, two durable streams run in the same locked pass:
  `publishLocked` writes the artifact compatibility documents
  synchronously (`persistArtifactMutationLocked` runs regardless of
  authority, `ChapterTranslationStore.kt:1884-1888`, `:1912-2098`) and THEN
  schedules the flat-file persist only when authority != ARTIFACTS
  (`:1891-1893`), serviced by the 250 ms debounce
  (`StorePersistenceScheduler.kt:141-153`). The README's commit points are
  exclusively artifact-shaped; the flat-file lane and its debounce are never
  mentioned. If staging captures the artifact side but the flat-file lane
  keeps its own debounce, the relative ordering of flat-file bytes and
  artifact bytes changes flag-ON in exactly the window (legacy authority,
  migration/rescue chapters) where crash-consistency between the two matters
  most (legacy rescue re-reads the flat file:
  `ChapterArtifactStore.kt:198-203`, `:206`).
- The glossary lane publishes manifests directly, outside the page
  transaction path (see C3) — the other "second discipline".

Required wording fix: one register row — "Legacy flat-file lane (pre-cutover)
and glossary lane: [staged under the same commit discipline | force-flushed
at every commit point | explicitly out of scope because cutover happens at
first open and rescue re-reads are open-time only]" — with the chosen option
justified.

Verdict: PLAN-GAP (low real-world window, but the plan currently silences on
it; "two commit disciplines" is a fair reading of the text today).

---

## Surface E — race register completeness vs what the suites pin

Checked the register (README, ~10 entries) against the pinned suites:
`app/src/test/java/eu/kanade/translation/coexistence/` (D1-D11, P5, T918,
Standard/Normal suites) and `app/src/test/java/eu/kanade/translation/pipeline/batch/`.

Missed by the register (each already has pinned tests):

1. **Reader/store teardown during the staged window.** Register #4 is about
   translation stop, not store close. Today `flush()`/`closeAndFlush()`
   flush dirty state under the store mutex
   (`StorePersistenceScheduler.kt:87-101, :103-117`); under ARTIFACTS the
   page half is a no-op ONLY because page writes are synchronous today
   (`:74-85`). Flag-ON, an orderly close/eviction with a live staged buffer
   must either flush it (new work at teardown — fine, but must be stated) or
   drop it (a loss mode today's orderly teardown does not have — today's
   mid-stage candidate snapshots are durable via `persistLiveCandidate`,
   `ChapterArtifactStore.kt:1080`). Register #3 covers crash only.
   Suggested row: "Orderly teardown/eviction during staged window → flush
   staged buffer at close; committed display never revoked (already
   persist-first per R2)."
2. **Engine-epoch stop during the staged window.** D7 pins stop-drain,
   grace-expiry close-under-call, epoch retry on the rebuilt translator
   (`D7EngineEpochStopRaceTest.kt:284, :430, :488`). Staged mutations
   produced by an epoch that is being torn down (which epoch owns the
   staged buffer's contents? does a dead epoch's staged work flush at its
   stop-drain commit point or is it dropped?) — absent from the register.
3. **Partial-download admission during the staged window.** D10 pins subset
   run records, fenced wait, and rerun-clears-partial
   (`D10PartialDownloadAdmissionTest.kt:337-490`). Register #7 covers only
   manual-tap-during-batch OCR (corpus gap). A partial-download admission
   reading chapter truth while page mutations sit staged (run-record
   preflight comparing against durable truth that lags staged state) is a
   distinct interleaving.
4. **Defunct store mid-staging.** `persistLocked` refuses when defunct
   (`StorePersistenceScheduler.kt:75`), mutation entry points check `defunct`
   (`ChapterTranslationStore.kt:1560`, `ChapterGlossaryStore.kt:56`). A
   staged flush arriving after `markDefunct` must be dropped or joined —
   register silent; minor but the scheduler's bounded join
   (`PERSIST_JOIN_TIMEOUT_MS`, `:32`) interacts with flush-at-close from #1.

Covered (adequately or thinly): migration concurrent with staging — register
#2 names migration as a second writer (thin, but present); manual tap during
batch OCR — #7 (explicitly deferred, honest); auto+manual same page — #10;
glossary pending-version — #9 (thin, see C3); staged-vs-CAS batch preflight
— #1 (store-side only; the second-writer half is the C1 gap).

Verdict: PLAN-GAP — add rows for teardown-during-staged-window,
engine-epoch stop, partial-download admission, defunct-mid-staging; and
strengthen #2/#9 per C1/C3.

---

## Ranked surviving attacks (app-breakage risk)

1. **Schema-guard cache literal implementation deletes future-schema
   documents and fails the flag-OFF suite** — breaks
   `ChapterArtifactStoreTest.kt:620-636` (and `:574-637` family) without a
   flag; production path is older-build-deletes-newer-build's-backup.
   Risk: HIGH (data loss), fix: one sentence exempting the guard reads at
   `ChapterArtifactStore.kt:130` and `:230`.
2. **No flush-time story for a second writer committing during a staged
   window** — flag-ON manufactures stale-CAS rejections at the seams that
   have no retry (`ChapterArtifactStore.kt:1080/:1189/:1322/:1404` vs the
   two retried seams `:350-355/:478-487`), producing new failed page writes
   in violation of the plan's own zero-new-REJECTED metric. Risk: HIGH
   (flag-ON correctness), fix: choose exclusion vs rebase vs
   flush-at-every-fence and add the register row.
3. **Non-transactional `publishManifest` callers (glossary lane) publishing
   from the staged in-memory manifest** — durable-izes staged state and can
   install dangling sidecar pointers (`ChapterGlossaryStore.kt:77-92,
   :114-125`; `ChapterArtifactStore.kt:235-237`). Risk: HIGH (flag-ON data
   integrity), fix: force-flush-before-publish rule for those callers.
4. **"Drain-to-commit stop" without the grace bound** — literal build either
   hangs teardown on a wedged provider or commits on expiry, breaking
   `D6DrainNotCancelTest.kt:285-333` / `D7EngineEpochStopRaceTest.kt:430`.
   Risk: MEDIUM (flag-ON; suite-caught, but the hang variant is
   production-visible), fix: reconcile register #4 with the
   PROVIDER_DRAIN_GRACE_MS contract.
5. **D9 attempt-ledger write not named as never-staged** — a staged ledger
   entry lets a paid call start with no durable trace
   (`ChapterAttemptLedger.kt:150-161` vs `D9AttemptLedgerTest.kt:226-232`).
   Risk: MEDIUM (false-crash chapter pauses), fix: one exempt line.
6. **"Teardown" retention ambiguity re-introducing the T921 stall** in
   `closeAndFlush`/probe teardown (`StorePersistenceScheduler.kt:103-117`
   vs `:119-128`). Risk: MEDIUM (flag-OFF perf regression, no flag to
   revert), fix: name `close()` as the only teardown sweep.
7. **Orderly teardown/eviction during a staged window unregistered** — drop
   vs flush unstated; today's orderly teardown loses nothing, flag-ON it
   would lose staged stage-work. Risk: MEDIUM-LOW (re-run cost, honest UI),
   fix: register row (flush at close).
8. **Writer registry enforce-vs-record ambiguity** — one reading violates
   the Slice A zero-behavior gate, the other adds no protection. Risk:
   LOW-MED, fix: state observability-only flag-OFF.
9. **Legacy flat-file lane absent from the commit contract** (pre-cutover
   window, rescue chapters). Risk: LOW (narrow window), fix: register row.
10. **Event-driven retention trigger drift** — tests are trigger-agnostic
    (`ChapterArtifactStoreTest.kt:758, :774`; `:778-808`), so the attack on
    the orphan-count tests FAILS; residual risk is only the teardown
    ambiguity (item 6). Risk: LOW.

## Attacks that did NOT survive

- "Event-driven retention breaks the exactly-6-orphans / reload-sweep tests":
  those tests call `reconcileRetention`/`ArtifactRetention` directly or go
  through a small-chapter open — trigger-agnostic. Parity holds if the plan
  keeps the open-path sweep.
- "The drain-to-commit design contradicts D6": the DESIGN is compatible
  (branch 1 is exactly D6 test 1); only the plan TEXT omits branch 2.
- "Slice B item 6 (candidate-promotion merge) changes WHO owns a write": the
  merge keeps the store as sole writer and elides a content-equal
  intermediate publish — a WHEN change, invariant-respecting.
- "Read-back elision breaks durability": parse-validate on File-backed local
  storage with SAF byte-compare retained is a read-side change; no invariant
  test pins a local read-back.

## Bottom line

No attack shows the core group-commit DESIGN is wrong: the store-mutex
ownership is preserved, the durable-manifest-as-CAS-truth principle is
explicitly kept at the fenced seams, and the invariant tests that look
timing-pinned are mostly trigger-agnostic or flag-scoped-convertible per the
overlay's rules. The plan's weakness is REGISTER INCOMPLETENESS at the
boundary between the staged window and everything that today relies on
"durable truth == in-memory truth, always": second writers (C1), the
glossary/legacy lanes (C3, D), the D9 ledger and the drain grace (C2, B),
teardown (E), and one Slice A cache whose literal reading is a data-loss bug
(A3). All are closable in wording before implementation; items 1-3 above are
mandatory pre-approval amendments, not deferrals.
