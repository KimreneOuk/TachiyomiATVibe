# Batch Translation Validation, Migration, and Rollout Matrix

**Purpose:** Prove that the implementation preserves completed work, resumes minimally, displays what it reports, and maintains ordered context.

## 1. Test Layers

### Pure unit tests

- artifact fingerprint construction;
- lifecycle planner decisions and reason codes;
- invalidation matrix;
- response ID/cardinality validation;
- evidence weighting and profile-link state machine;
- checkpoint hashing/dependency invalidation;
- canonical page ordering and progress numbering.

### Store and pipeline integration tests

- candidate writes with generation/page-version preconditions;
- atomic promotion;
- pause/cancel/failure/process-death recovery;
- file validation and garbage collection;
- exact worker invocation counts;
- page-scoped context and translation transaction boundaries inside multi-page request envelopes.

### Reader/UI integration tests

- the drawer, chapter list, and reader consume the same committed display state;
- a candidate transition never replaces a committed stream with the original image;
- ready counts match visible translated pages;
- pause/cancel/retry state survives sheet dismissal and reopening.

### Manual/device tests

- low-memory device behavior;
- real chapter images and large/long chapters;
- app background/foreground and process kill;
- provider refusal, network loss, and rate limiting;
- reader opened while batch is actively promoting pages.

## 2. Artifact Reuse Matrix

Every row asserts both output correctness and exact stage invocation counts.

| Scenario | Detect | OCR | Inpaint | Translate | Layout | Visible result during work |
|---|---:|---:|---:|---:|---:|---|
| Completed chapter, unchanged config | 0 | 0 | 0 | 0 | 0 | Existing committed bundle |
| Target language changes | 0 | 0 | 0 | affected pages | affected pages | Old language until promotion |
| Translator/model/prompt changes | 0 | 0 | 0 | affected pages | affected pages | Old bundle until promotion |
| Inpaint mode/revision changes | 0 | 0 | affected pages | 0 | affected pages | Old bundle until promotion |
| Font/layout preference changes | 0 | 0 | 0 | 0 | affected pages | Old layout until promotion |
| Cleaned file missing | 0 if mask valid | 0 | affected page | 0 | affected page | Old bundle only if file still readable; otherwise original with explicit corruption state |
| Layout payload corrupt | 0 | 0 | 0 | 0 | affected page | Old bundle only if prior layout validates |
| OCR config changes | 0 if detection valid | affected pages | 0 if mask valid | affected pages | affected pages | Old bundle until promotion |
| Detection model/threshold changes | affected pages | affected pages | affected pages | affected pages | affected pages | Old bundle until promotion |
| Source image changes | affected page | affected page | affected page | affected page | affected page | Mark old-source result while refreshing |
| Manual target edit | 0 | 0 | 0 | 0 | affected page | Old bundle until edited layout promotes |

## 3. Ordering and Resume Matrix

- Starting a batch while reading page 20 still scans and reports pages 1, 2, 3… in natural order.
- Pages 1–9 complete and page 10 OCR-only: pages 1–9 invoke no stage; page 10 resumes at the earliest missing stage.
- Page 4 has invalid translation while page 7 lacks inpaint: page 4 is the first work item; page 7 waits behind ordered progress.
- Pages 1–5 display-complete but context checkpoint 3 is corrupt: preserve all committed displays, reuse native artifacts, and retranslate from the last trusted checkpoint through page 5 unless the exact checkpoint is recoverable from a validated sidecar.
- Bounded native lookahead never promotes translation/context out of order.
- Reader viewport changes do not reorder the batch.
- Reader single-page translation and batch contention results in one page/stage owner, not duplicate inference.
- A reader-originated `READER_ADHOC` bundle remains visible but is retransmitted through ordered batch translation when reached; native stages are reused and the ad-hoc result never advances batch context.

## 4. Last-Known-Good and Failure Injection

Inject cancellation, exception, and process death at every boundary:

1. before candidate creation;
2. after detection payload write;
3. after OCR payload write;
4. during image file write;
5. after file validation but before metadata publication;
6. after translation response but before context validation;
7. after context validation but before page-prefix commit;
8. during layout generation;
9. immediately before display-bundle promotion;
10. immediately after promotion but before old-file cleanup.

For every boundary:

- committed metadata remains internally consistent;
- the reader either shows the complete old bundle or the complete new bundle, never a mix;
- pause/resume continues from the earliest incomplete stage;
- cancel removes only candidate-owned artifacts;
- a stale worker cannot commit after a newer generation wins;
- reopening the drawer reports the same durable state.

## 5. Readiness and UI Cases

| Stored state | Ready count | Reader image | Action/state |
|---|---:|---|---|
| OCR + translations, no cleaned image/layout | 0 | Original | Translating/cleaning, not ready |
| Candidate running, no committed bundle | 0 | Original | Progress visible |
| Candidate running, committed bundle exists | 1 | Committed translated bundle | Refreshing; read translated enabled |
| Candidate failed, committed bundle exists | 1 | Committed translated bundle | Warning + retry |
| Candidate failed, no committed bundle | 0 | Original | Failure + retry |
| Complete candidate just promoted | 1 | New committed bundle | Ready |
| `READER_ADHOC` committed result awaiting batch context | 1 readable, 0 batch-complete | Ad-hoc translated bundle | Queued for ordered batch refresh |
| OCR-confirmed textless page | 0 translated; 1 processed | Original | Textless complete |
| Partial required translations | 0 unless old committed exists | Old bundle or original | Partial/failure, not ready |

Also verify that real chapter page numbers—not queue positions—appear in progress and errors.

## 6. Context and Translation Quality Cases

### Protocol correctness

- Two or more pages with repeated local block numbers map to distinct global IDs.
- Missing, duplicate, unknown, or normalized IDs reject the entire candidate response.
- Prompt-like OCR text cannot escape source delimiters or alter output rules.
- Invalid context delta prevents chunk promotion even when target strings parse.
- In a two-page envelope where page 1 validates and page 2 fails, page 1 and its checkpoint commit; page 2 and all later pages remain uncommitted and context does not cross the gap.

### Speaker and profile behavior

- An unnamed `Speaker A` becomes a named profile after corroboration within the bounded page/turn evidence window.
- A spoken name used as an addressee is not assigned as the speaker.
- Name-only and speech-style-only evidence remain tentative.
- A low-confidence OCR pronoun cannot override repeated strong evidence.
- Correcting a tentative fact reruns only translation/layout in the stabilization window.
- Correcting a durable fact invalidates every dependent downstream translation checkpoint.
- A durable correction crossing a manually edited block reapplies the exact-match edit and never replaces it with model output.

### Relationship and mature content

- Source-explicit gender/relationship overrides the configured ambiguity preference.
- `batchRelationshipAmbiguityPrior` defaults to `MALE_FEMALE`, is visible only in batch-translation settings, and switching to `NEUTRAL` invalidates translation/layout but invokes no native stage.
- The preference remains inactive in non-romance ensemble dialogue.
- Ambiguous heterosexual romance uses the preference only as a final tie-break.
- Intimate-scene fixtures assert speaker, viewpoint, agent, action, recipient, and negation separately.
- Curated semantic-role/manual fixtures check that output is neither sanitized nor made more explicit than the source; this is a quality gate, not a claim of perfect deterministic detection.
- Structural provider refusal is exposed as failure and does not advance context; best-effort heuristics flag likely in-protocol sanitization for retry/review.

### Poison recovery

- A model-produced wrong pronoun never becomes evidence for the next chunk.
- Contradictory deltas retry from the last trusted checkpoint.
- Repeated unresolved conflict stays unresolved.
- Legacy poisoned context rebuilds from page 1 using reusable source OCR.

## 7. Migration Cases

Test representative legacy combinations:

- cleaned image + complete translated blocks + old revision metadata;
- translated blocks but missing mask metadata;
- cleaned file missing;
- OCR blocks with no target language/protocol provenance;
- partially translated blocks;
- a page persisted in `RUNNING` after process death;
- legacy rolling summary containing wrong pronouns;
- manual target edits;
- corrupted JSON record and orphaned versioned files.
- reader ad-hoc committed result with no batch checkpoint;
- persisted terminal failure/retry metadata;
- legacy glossary and poisoned identity facts.

Migration must preserve a displayable legacy page as provisional committed state. Refresh occurs in a candidate and never blanks it. Unprovable context is rebuilt; valid native artifacts are reused when their dependency identity can be established.

## 8. Performance and Resource Gates

- Instrument and assert at most one full-resolution inpaint decode plus one bounded OCR decode per page in the normal path. Permit only measured adaptive OCR re-decodes for sampling/OOM recovery.
- Native inference concurrency is one unless measurements justify a different bounded value.
- Native lookahead is limited to three pages. AI request envelopes are 1–4 pages and at most 24 accepted blocks; identity evidence is capped at three pages on each side and 36 turns.
- Channels have explicit capacities; cancellation releases decoded frames and native buffers.
- Long-chapter test demonstrates flat bounded memory rather than growth with page count.
- Context stays within profile, recent-turn, summary, and token bounds.
- Completed-chapter no-op run finishes by metadata/file validation without loading full bitmaps.
- Record stage durations, queue depth, memory high-water mark, reuse reason counts, and retry counts.

Do not log source/translated dialogue, prompts, context cards, relationship details, or mature content.

## 9. Branch Integration Procedure

1. Verify the latest local/remote `feat/npu-acceleration-and-hardware-discovery` head at implementation time.
2. Create the implementation branch from that head before Phase 1; land each phase directly on this NPU-based branch.
3. Inventory commits unique to `refactor_batch_translation_ux` and classify them as prerequisite, reusable, superseded, or unrelated.
4. Port a small reviewed series; do not merge stale lifecycle assumptions wholesale.
5. Preserve the NPU hardware/backend changes and rerun translation tests against that backend.
6. Review the final diff against both branch heads for accidental reversions.
7. Run the validation tiers required by the authoritative root `AGENTS.md` in the NPU target workspace. That target instruction overrides differing guidance in the refactor source worktree:
   - focused `:app:testStandardDebugUnitTest` filters for each touched translation/reader package;
   - `./gradlew spotlessCheck :app:testStandardDebugUnitTest :domain:testReleaseUnitTest` before task completion;
   - the full release gate only at CI/pre-merge or when explicitly requested.

No branch mutation, cherry-pick, merge, or build belongs to the planning session.

## 10. Rollout Gates

### Gate A — Shadow planning

Run the new lifecycle planner in diagnostic mode beside current decisions. Compare stage decisions and invocation counts without changing outputs.

### Gate B — Candidate/committed store

Enable last-known-good storage and reader consumption while retaining the existing coordinator. Prove no translated-page disappearance.

### Gate C — Natural-order scheduler

Enable first-incomplete sequential execution and the consolidated native path. Prove order, reuse, memory, and cancellation.

### Gate D — Versioned AI context

Enable the new protocol for new batch runs. Legacy context refreshes translation/render from page 1 while preserving old committed pages.

### Gate E — Cleanup

Remove inactive coordinators, viewport-priority batch logic, old readiness inference, and legacy context writers only after telemetry/tests show no remaining consumers.

## 11. Release Blockers

Do not merge if any of these remain:

- a candidate state can make a committed page display original content;
- ready count and reader output disagree;
- target-language change can reuse an old-language translation;
- cancel deletes committed metadata/files;
- duplicate block IDs can enter a request;
- context advances past a failed/untrusted page;
- reader-originated writes can overwrite an active batch candidate or become authoritative batch context;
- viewport or `lastPageRead` changes batch order;
- completed no-op runs invoke expensive stages;
- legacy migration rewrites the only displayable copy in place;
- raw story/context text appears in production logs.
