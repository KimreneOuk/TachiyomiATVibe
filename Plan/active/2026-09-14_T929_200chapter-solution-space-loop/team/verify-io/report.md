# T929 Round 1 — adversarial verification of T928 `team/io/report.md`

Base: `main` @ `9c19ad0`. All paths under `app/src/main/java/eu/kanade/translation/`.
Every claim below was re-derived from code I read at HEAD, not from the report's citations.

## 1. Verdict table

| # | Finding (T928 io) | Verdict | My evidence |
|---|---|---|---|
| R1 | Whole-manifest rewrite per stage write; O(P²) bytes; ~7 MPs/page | AGREE | `artifact/ChapterArtifactManifest.kt:22` pages map O(P); every stage write ends in `publishManifestInternal` (`artifact/ChapterArtifactStore.kt:1791-1809`); 7 MP call sites per fresh manual page (see §2). |
| R2 | Triple full-manifest read per MP | AGREE | CAS `staleManifestRejection`→`readManifest()` primary read (`ChapterArtifactStore.kt:1641-1649`, `:226-232`); primary future-schema read `:1792`; backup read `futureBackupPresent()` `:1787-1789`, `:1801`. = 2 primary + 1 backup full reads per MP. |
| R3 | Candidate written then immediately re-committed | AGREE-WITH-NUANCE | `ChapterTranslationStore.kt:2054-2063` (persistLiveCandidate SP+MP) then `:2076-2095` (promote). Nuance: promote does NOT rewrite the candidate file when read-back matches (`ChapterArtifactStore.kt:1229-1234`) — the redundancy is 1 MP + 1 CAS read + 1 read-back, not a second candidate SP. |
| R4 | Per-page glossary SP + listing + MP | AGREE-WITH-NUANCE | Gated on `ContextualTextTranslator` (`pipeline/SinglePageHttpRenderPhase.kt:444-449`) AND content change (`store/ChapterGlossaryStore.kt:63` `if (glossary != updated)`). It is ONE manifest publication, not "two" (report's R4 wording error): SP `:78`, MP `:84`. |
| R5 | Retention crawl per page in batch | AGREE | `checkpointOcr` CLOSE sets `sweepAfterCommit` (`ChapterArtifactStore.kt:632`, `:742-745`); coordinator uses CLOSE per page (`pipeline/batch/ChapterProfileBatchCoordinator.kt:2859`); `cancelCandidate` crawls `:1548`. Crawl = recursive `io.list`+`io.exists`, each dir listed TWICE (`artifact/ArtifactRetention.kt:39,43`) — worse than reported. |
| R6 | All durable I/O under store mutex | AGREE-WITH-NUANCE | Mutex held across full publish chain: `ChapterTranslationStore.kt:579` (patchPage), `:667`, `:839`, `:968`, `:1029` (checkpoint), `:340` (snapshot). Nuance: reader display resolution is lock-free — `resolveDisplayPage` `:2228-2229` reads volatiles; `_display` published `:1895`. Readers see stale-but-safe state; only store callers (scheduler, pipeline snapshots, patches) serialize. |
| R7 | `.tmp` read-back doubles payload | AGREE | `artifact/ChapterDocumentIo.kt:239-244`. |
| R8 | Legacy flat path dead under ARTIFACTS | AGREE | `store/StorePersistenceScheduler.kt:74-85`; open probes `ChapterTranslationStore.kt:2590-2595`. |
| R9 | `flush()` no-op yet called on tails | AGREE-WITH-NUANCE | `StorePersistenceScheduler.kt:74-85` no-op VERIFIED. Nuance: `flushDirtyLocked` `:93-101` retries a dirty GLOSSARY publication, so a tail flush after a failed glossary publish does do I/O. |
| R10 | Same PageTranslation JSON in up to 3 stores/page | AGREE (upgrade to VERIFIED) | Candidate SP `ChapterArtifactStore.kt:1108`; OCR snapshot SP `:559-561`; committed SP `:1235-1238`. |
| No fsync anywhere | AGREE | Grep over `eu/kanade/translation/**` for `force()|fsync|FileChannel`: zero matches. `write()` = openOutputStream+write+flush only (`ChapterDocumentIo.kt:141-148`). |
| ~7 manifest publications per fresh manual page | AGREE | Counted in §2: registration, openCandidate, mergeOcr-persist, cleaned-commit persist, glossary, final-commit persist, promotion = 7. |
| ~95-110 ops fresh manual / ~130-150 batch | AGREE-WITH-NUANCE | My counts: ~113-122 manual (first/subsequent page), ~130-150+RC batch. Report ~10-15% LOW because it (a) counts publish as 5 ops, missing `io.exists(name)` (`ChapterDocumentIo.kt:246`) → publish=6, SP=6, MP=9; (b) omits `displayBaseIsValid` probe on promotion (see §4); (c) its stated op unit (findFile counted) is applied inconsistently — each io-call costs ≥2 provider round trips on SAF (resolve findFile + action, `ChapterDocumentIo.kt:91-101`). Direction and magnitude of the thesis unaffected. |
| 8 ops per manifest publication incl. 3 full reads | AGREE-WITH-NUANCE | Reads: 3 VERIFIED. Writes: 6 not 5 (missing `io.exists` at `:246`) → MP=9. |

## 2. Independent op-count derivation (io-call granularity)

Unit = one `ChapterDocumentIo` call (each = ≥1 backend op; on SAF ≥2 round trips).
`publish()` happy path with primary present (`ChapterDocumentIo.kt:236-255`): write tmp(1) + read tmp(1) + delete bak(1) + **exists(1)** + rename primary→bak(1) + rename tmp→primary(1) = **6** (report: 5).

- **MP (manifest publication)** = CAS read(1) + primary future read(1) + backup future read(1) + publish(6) = **9** (report: 8).
- **SP (sidecar publish)** = **6** (report: 5).

**(a) One manifest publication** via `persistLiveCandidate` (`:1079-1150`):
CAS read via `candidateWriteRejection`(`:1552-1574`) 1 + candidate SP 6 + MP 9 = **16** (report: 13).

**(b) One fresh manual page** (not yet registered, contextual translator, full pipeline):

| Step | Site | My ops (report) |
|---|---|---|
| Registration MP | `ChapterTranslationStore.kt:1947-1968` | 9 (8) — first write only |
| openCandidate | `:2020` → `ChapterArtifactStore.kt:1403-1483` | 16 (13) |
| mergeOcr persist | `ChapterTranslationStore.kt:968/2054` | 16 (13) |
| Cleaned JPEG write+verify | `pipeline/CleanedPublication.kt:122-134` | 5 (3) — findFile+create+write+exists+length |
| Cleaned commit persist | `CleanedPublication.kt:144` | 16 (13) |
| Glossary SP+listing+MP | `ChapterGlossaryStore.kt:78-92` | 17 (14) |
| Final commit persist | `SinglePageHttpRenderPhase.kt:717-734` | 16 (13) |
| Promotion | `ChapterArtifactStore.kt:1188-1318` | 26-27 (19) — CAS 1 + read-back 1 + displayBase probe 3-4 + committed SP 6 + gen SP 6 + MP 9 |
| Tail (retire delete, flush) | `SinglePageHttpRenderPhase.kt:748,765` | 0-1 (0-1) |

**Total ≈ 122** with registration MP, **≈ 113** without. Report: 95-110. Same structure, report slightly low.

**(c) One batch page** (T924 shell): mergeOcr open+persist 32 + JPEG 5 + cleaned persist 16 (+ promotion 26 only if the cleaned commit reaches `hasRenderedResult` — `model/PageTranslationState.kt:70` ties it to translation display-readiness; flagged batch keeps render PENDING, so usually no per-page promotion, matching the report's divergence note but contradicting its own batch-table row) + checkpoint CLOSE (CAS 1 + 3 SP 18 + MP 9 = 28) + retention crawl + layout plan (CAS 1 + SP 6 + MP 9 = 16) ≈ **97-123 + RC per page**, i.e. with retry/CAS extras the report's 130-150 band is a fair upper envelope. Verdict: AGREE-WITH-NUANCE.

## 3. Missed mitigations (report too pessimistic here)

1. **Durability gate skips all I/O for placeholders**: `publishLocked` (`ChapterTranslationStore.kt:1884-1888`) does zero artifact work when the write is not durable AND the page is already in the manifest; `shouldPersistUpdate` (`:2522-2548`) keeps RUNNING stamps, OCR-start placeholders, and inpaint RUNNING stamps memory-only. The report counts this only for OCR-start.
2. **Glossary no-op guard**: `ChapterGlossaryStore.kt:63` — pages adding no new terms cost 0 ops (R4 applies only to term-bearing pages).
3. **Registration MP fires once per page lifetime** (`:1943-1944` filters already-registered keys); `preRegisterPages` publishes nothing on resume when counts unchanged (`:1779-1783`).
4. **Promotion skips candidate rewrite** when read-back matches (`ChapterArtifactStore.kt:1229-1234`).
5. **Open-path crawl already deferred for >8 pages** (T921: `ChapterArtifactStore.kt:166-178`) and `closeAndFlush` skips the sweep (`StorePersistenceScheduler.kt:104-112`) — open/close are cheaper than the report's general RC framing implies; the per-page batch crawl (R5) remains real.
6. **`dirCache`** (`ChapterDocumentIo.kt:69-89`) avoids directory re-traversal, though cache hits still pay one `exists()` per segment, so the saving is partial.

## 4. Missed problems (report too optimistic here)

1. **`displayBaseIsValid` on every promotion with a cleaned image** (`ChapterArtifactStore.kt:1217-1226`, `:1695-1706`): exists + length + openInputStream + JPEG bounds decode, under the store mutex — 3-4 ops + decode not counted anywhere in the report.
2. **Probe stores are a second writer**: `manager/DurableChapterStatusResolver.kt:207` `withProbeStore` opens a separate store/`ChapterArtifactStore` over the same directory (`ActiveChapterStoreRegistry.kt:23` probeStores); newly created probes can run the one-way rescue publication (`:222-225`), and `verifyLegacyArtifactHealth` republishes manifests on the >8-page open path (`ChapterArtifactStore.kt:1778-1785`; `artifact/LegacyChapterMigrationSource.kt:249,262`). Extra uncounted MP/reads — and it invalidates OPT-2's premise (see §5).
3. **Batch preflight re-reads checkpoint sidecars per page** (`ChapterProfileBatchCoordinator.kt:2869-2896`), and checkpoint ADOPT reads the full committed snapshot (`ChapterArtifactStore.kt:704`) — uncounted reads in scenario C (partial chapters).
4. **Attempt-ledger SP per translate attempt** (`store/ChapterAttemptLedger.kt:153` → 6-op publish) is per-attempt, not merely "chapter-scoped when persisted".
5. **Source fingerprinting**: per-page SHA-256 over source bytes feeding checkpoint source identity (`ChapterProfileBatchCoordinator.kt:2861`) — CPU + a full source read outside the manga-dir model; decode/ArchiveReader/Coil reads similarly uncounted (arguably out of scope, but they dominate wall-clock in scenario A).
6. **SharedPreferences `commit=true`** is synchronous disk I/O per queue mutation and per pending-phase update (`TranslationQueueStore.kt:40-72`; `TranslationPendingRequestStore.kt:78-110`) — counted by the report at 1 op each, but batch phase transitions can emit several per chapter.

## 5. OPT-1..OPT-8 sanity check against invariants

- **OPT-1 (group manifest commits)** — Flag: the CAS is object equality against the DURABLE manifest (`staleManifestRejection`, `ChapterArtifactStore.kt:1644`). With staged-unpublished state, the façade manifest differs from disk between page start and page boundary, so EVERY intermediate `persistLiveCandidate`/`checkpointOcr` CAS rejects, not just the batch preflight the report flags. Implementing OPT-1 means redefining CAS equality for staged state everywhere — closer to HIGH-MED risk than MED. Invariants (no partial commit, committed display) survive; resume fidelity drops as stated.
- **OPT-2 (in-memory CAS)** — DISAGREE with premise: "all writers funnel through one store mutex; the CAS is process-internal" is false. Probe stores (`DurableChapterStatusResolver.kt:207`, `ActiveChapterStoreRegistry.kt:23`) are independent `ChapterArtifactStore` instances publishing the same manifest; the disk-truth CAS is the only guard against probe-vs-live drift (the exact LI-4 seam documented at `ChapterArtifactStore.kt:1657-1670`). OPT-2 needs registry-level coordination or must keep disk CAS on rescue/health-verify seams. Risk understated at LOW-MED.
- **OPT-3 (merge candidate+promotion)** — AGREE, LOW risk holds: promotion already rejects without a candidate (`:1210-1211`); keeping the candidate publish on the durable-failure/resume branch (as the report says) preserves retry semantics; committed display unaffected.
- **OPT-4 (event-driven retention)** — AGREE. Reality already matches its premise: >8-page open crawl is deferred (`:166-178`), closeAndFlush skips (`StorePersistenceScheduler.kt:104-112`); GC deferral costs space, never correctness (content-addressed names).
- **OPT-5 (batch glossary commits)** — AGREE-WITH-NUANCE. The dirty-flag retry machinery exists (`StorePersistenceScheduler.kt:93-101`); wins only apply to term-bearing pages; the D5 version-stamp caveat the report raises is real (`SinglePageHttpRenderPhase.kt:451-459`).
- **OPT-6 (storage lane off the mutex)** — AGREE-WITH-NUANCE. Ordering can be preserved (DeferredPagePublications precedent, `pipeline/DeferredPagePublications.kt`), but awaiting publish results while holding the store mutex (checkpoint CAS, promotion) recreates the serialization unless those seams are redesigned; bounded queue is mandatory (6 GB devices).
- **OPT-7 (drop read-back)** — AGREE, LOW. `publishJson` already parse-validates the read-back (`ChapterDocumentIo.kt:257-260`); note the read-back also catches provider-level silent corruption, so keep it on SAF as proposed.
- **OPT-8 (WAL/journal)** — AGREE with MED-HIGH; replay must preserve sidecar-before-pointer and idempotence; correctly framed as fallback.
- Not-recommended list (remove `.bak`/sidecar-before-pointer, revoke committed display, partial commits) — concur; these guard the only crash windows over committed display (`ChapterDocumentIo.kt:213-226`; `ChapterArtifactStore.kt:808-817`).

## Bottom line

The report's structural findings (R1-R10) and headline magnitudes are correct: 7 MPs per fresh page, O(P²) manifest bytes, no fsync, per-page batch retention crawls all verify at HEAD. Its absolute op counts are ~10-20% low (missing `io.exists`, display-base probe, findFile resolution costs), its R4 wording overstates glossary MP count (1, not 2) while the real behavior is more conditional than claimed, and it misses probe-store concurrency — which both adds uncounted I/O and breaks OPT-2's core premise. Round-2 solution mappers should use MP=9/SP=6, ~115-125 ops per fresh manual page, and treat OPT-2 as requiring registry-wide writer coordination.
