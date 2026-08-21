# Batch Translation Artifact Lifecycle Contract

**Purpose:** Define exactly when each artifact is complete, reusable, invalid, reader-visible, and safe to delete.  
**Applies to:** Chapter batch translation and the shared store/reader boundary.

## 1. Core Model

Every page record contains:

- `sourceIdentity`: stable page key, byte hash, dimensions, and source revision metadata;
- `committedBundle`: the last complete displayable generation, if any;
- `candidateGeneration`: the current work generation, if any;
- `stages`: artifact metadata and provenance for detection, OCR, inpaint, translation, and layout;
- `pageVersion`: monotonically increasing optimistic-concurrency version;
- `schemaVersion`: persistence schema version.

Installed detector/OCR/inpaint model hashes are computed once per installed asset version and cached. A no-op planner scan reads cached identities and artifact metadata; it does not rehash model assets or decode page bitmaps.

Every artifact identity is content-addressed by a deterministic fingerprint of its direct inputs and configuration. A stage can be reused only when:

1. it has a terminal successful status;
2. all required payloads/files exist and validate;
3. its recorded fingerprint equals the newly computed expected fingerprint;
4. all dependency identities still exist and match;
5. it is not superseded by an authoritative user edit;
6. its schema/protocol version is supported.

Timestamps, queue state, and a generic `DONE` flag are never sufficient provenance.

## 2. Status Vocabulary

All stages use explicit statuses:

- `ABSENT`: no artifact exists;
- `RUNNING`: candidate work is active;
- `READY`: complete and reusable;
- `TEXTLESS`: terminal success with no accepted source text, where applicable;
- `SKIPPED`: terminal only when a documented dependency rule makes the stage unnecessary;
- `FAILED_RETRYABLE`: incomplete and eligible for retry;
- `FAILED_TERMINAL`: incomplete until input/configuration/user action changes;
- `STALE`: payload may exist but provenance no longer matches;
- `CORRUPT`: metadata, file, or payload validation failed.

`PARTIAL` may exist as diagnostic candidate state, but it is never promotable and never counted as ready.

## 3. Source Identity

### Complete when

- The canonical chapter/page key is known.
- Source bytes are available.
- SHA-256, decoded width, decoded height, and orientation are recorded.

The hash is computed once while bytes are already being opened for the first required pass, then reused. A trustworthy archive/content checksum may avoid a second read, but must not replace SHA-256 unless collision and stability properties are equivalent.

### Invalidation

Any source byte-hash or decoded-geometry change invalidates every downstream candidate artifact. The old committed bundle may remain visible until a replacement is promoted, but is marked as originating from an older source.

## 4. Detection, Segmentation, Masks, and Reading Order

Treat the native analysis payload as one versioned artifact with separately addressable components:

- text/balloon detections and confidence;
- segmentation or erase mask identity;
- accepted/rejected regions and reasons;
- panel assignment, bubble assignment, and reading order;
- source geometry transforms.

### Complete when

- Native inference finished without cancellation.
- All region coordinates validate against source dimensions.
- Region IDs are stable and unique within the page.
- Mask dimensions match the source.
- Accepted/rejected classification and reading order are persisted.
- Zero accepted regions is explicitly recorded, not inferred from an empty/missing payload.

### Provenance fingerprint

`sourceHash + detectorModelHash + segmenterModelHash + nativeProtocolVersion + thresholds + maskPostprocessVersion + panelAssignmentVersion + readingOrderVersion`

### Reuse

Reuse only if the complete fingerprint matches. Model file name alone is insufficient; use the actual model asset/version hash.

## 5. OCR Source Blocks

### Complete when

- Every accepted OCR region has a stable block ID, geometry, source text, confidence, and reading-order position.
- Blank/noise regions are explicitly rejected with a reason.
- Every detection region is accounted for.
- `TEXTLESS` is explicit when no accepted source text remains.

### Provenance fingerprint

`detectionArtifactId + ocrEngineVersion + ocrModelHash + sourceLanguage + preprocessingVersion + textNormalizationVersion`

### Reuse

- OCR is reusable across target-language, translator, prompt, glossary, profile, inpaint-engine, and font changes.
- OCR is not reusable after source, detection geometry, OCR engine/model, source language, preprocessing, or normalization changes.
- A reading-order-only change may preserve raw OCR strings but must rebuild ordered OCR block metadata and invalidate ordered translation/context downstream.

## 6. Cleaned Image / Inpaint Artifact

### Complete when

- Every accepted erase region has a corresponding valid mask region.
- Inpainting completed without cancellation.
- The versioned output file exists, is non-empty, decodes successfully, and matches source dimensions.
- File hash and file identity are recorded.
- If no erase regions exist, `SKIPPED(NO_ERASE_REGIONS)` is a terminal success and the original is the display base.

If accepted translatable text exists but required erase regions are missing, this is failure, not `TEXTLESS` and not a valid skip.

### Provenance fingerprint

`sourceHash + maskArtifactId + inpaintEngineVersion + inpaintModelHash + inpaintMode + inpaintSettings + cleanupRevision`

### Reuse

- Reusable across OCR-text corrections when mask geometry did not change.
- Reusable across target language, translator, prompt, context, glossary, and font/layout changes.
- Invalidated by source, mask, inpaint model/mode/settings, cleanup revision, missing/corrupt output file, or output-geometry changes.

## 7. Translation Artifact

### Complete when

- Every accepted nonblank OCR block has exactly one target result with the same globally unique ID.
- No required ID is missing, duplicated, or unknown.
- Results pass language/protocol validation and are nonblank unless an explicit source-preserving rule allows blank output.
- The associated context delta is valid.
- The chunk’s trusted next-context checkpoint is committed atomically with its translations.
- Any provider refusal is recorded as failure, not as a translated result.

An AI request may carry 1–4 pages, but translation completeness and context checkpoints are page-scoped. The response must provide an ordered delta after each page. Only the longest validated, gap-free page prefix can commit. A failure on page P prevents context or translation promotion for later pages in that envelope; the suffix retries from the checkpoint before P.

### Provenance fingerprint

`orderedOcrBlockIdsAndTextHashes + sourceLanguage + targetLanguage + provider + model + modelSettings + promptProtocolVersion + contextInputCheckpointHash + glossaryVersion + profileLedgerVersion + batchRelationshipAmbiguityPriorValue + ambiguityPriorSchemaVersion`

The current context checkpoint is an input. The newly proposed checkpoint is an output.

### Reuse

- Reusable across detection/inpaint changes only when ordered OCR block identities and source texts still match.
- Invalidated by any ordered OCR content change, language change, provider/model/settings change, prompt/protocol change, affected glossary/profile change, or context checkpoint change.
- Prior target text is never accepted as factual provenance for identity or relationships.

### Manual edits

Manual target edits are authoritative for their exact OCR block/source fingerprint. They invalidate render only. If OCR changes, an edit is carried forward only when its source block can be matched exactly; otherwise retain it as an orphaned edit for review, never silently apply or delete it.

The same rule applies to context-driven retranslation: exact-match manual edits are reapplied after model translation and remain authoritative. A nonmatching edit is quarantined as an orphan and never silently overwritten.

## 8. Render Layout Artifact

The render artifact is the durable layout plan, not a cached bitmap copy of the whole page. It contains translated block IDs, wrapped lines, font metrics, origins, safe regions, alignment, colors/strokes, and any scale transforms required by the reader overlay.

### Complete when

- Every translated block has a valid layout entry.
- All geometry is within the display base dimensions.
- Font resolution succeeded.
- Collision/safe-region validation completed.
- The layout references the exact translation and cleaned-image identities.

### Provenance fingerprint

`translationArtifactId + cleanedImageArtifactIdOrOriginalSourceId + layoutEngineVersion + fontIdentity + fontScalePreferences + stylePreferences + outputDimensions`

### Reuse

Invalidated by translation, cleaned-image/display-base, layout-engine, font, style, or output-dimension changes. It is not invalidated by unrelated context facts that did not affect the translation artifact.

## 9. Committed Display Bundle

A display bundle contains immutable references to:

- source identity;
- display-base identity: validated cleaned image or original for a legitimate no-erase case;
- complete translation artifact;
- complete render-layout artifact;
- bundle fingerprint and generation ID.

### Promotable when

- All dependency fingerprints match the candidate plan.
- All required target blocks are translated.
- All referenced files validate.
- The page version and run generation still match expected preconditions.
- No cancellation was observed before the commit transaction.

Promotion swaps one committed pointer. Store observers must never see a half-promoted combination.

### Textless page

An OCR-confirmed textless page is terminally complete but is not a translated display bundle. It uses `TEXTLESS_COMPLETE` and counts as processed rather than translated-ready. With no erase regions it displays the original and inpaint is `SKIPPED(NO_ERASE_REGIONS)`. With detector-only/watermark erase regions, inpaint still runs and the validated cleaned image becomes its display base; translation and text layout are `SKIPPED(TEXTLESS)`.

## 10. Invalidation Matrix

`X` means rerun; `R` means reuse; `D` means recompute metadata/order only.

| Change | Detection/mask | OCR | Inpaint | Translation/context | Layout |
|---|---:|---:|---:|---:|---:|
| Source bytes/dimensions | X | X | X | X | X |
| Detector/segmenter/model/threshold | X | X | X | X | X |
| Panel/reading-order algorithm only | D | D | R unless masks changed | X | X |
| OCR model/preprocessing/source language | R | X | R | X | X |
| Target language | R | R | R | X | X |
| Translator/provider/model/settings | R | R | R | X | X |
| Prompt/context protocol | R | R | R | X | X |
| Glossary/profile fact affecting page | R | R | R | X from earliest dependency | X |
| Inpaint model/mode/settings/revision | R | R | X | R | X |
| Cleaned file missing/corrupt | R | R | X | R | X |
| Font/layout/style/output size | R | R | R | R | X |
| Manual target edit | R | R | R | R as authoritative edit | X |
| Context checkpoint missing/corrupt | R | R | R | retranslate from the last trusted checkpoint through the affected suffix | X when target text changes; preserve old committed layout until replacement |

The planner selects the earliest invalid dependency, not a hard-coded whole-page mode such as `FULL`.

## 11. Resume Algorithm

For page 1 through page N:

1. Compute current source and configuration fingerprints.
2. Validate committed and candidate payload/file integrity.
3. Determine the earliest non-reusable stage.
4. If every stage and the required context checkpoint are reusable, return `SKIP_ALL`.
5. If display artifacts are complete but a mid-chain context checkpoint is missing/corrupt, preserve the committed display and retranslate from the last trusted checkpoint through the remaining suffix. Native stages remain reusable. If the checkpoint can be reconstructed byte-for-byte from a separately validated sidecar, retranslation is unnecessary; otherwise it is required.
6. If work is needed, preserve the committed pointer and continue/create the candidate at that stage.

The first page with a `RUN` decision is the batch work start. Earlier pages may still be read to validate the trusted prefix. Native stages are not rerun unless their artifacts are invalid; translation may rerun from an earlier trusted checkpoint when context recovery requires rebuilding the chain.

## 12. Reader-Path Interoperability

Reader single-page translations use provenance class `READER_ADHOC`:

- They may form a committed display bundle and remain reader-visible.
- They do not contain or advance an ordered batch context checkpoint.
- They are never factual input to the batch evidence ledger.
- When batch reaches the page, it reuses valid detection/OCR/inpaint artifacts but retranslates under the current batch checkpoint and protocol. The ad-hoc bundle stays visible until promotion.
- A page/stage lease has one owner. If batch owns it, a reader request attaches to the batch result or waits/cancels; it cannot open a competing writer. If reader owns it first, batch waits for the atomic stage boundary, then validates/reuses its artifacts and acquires the next lease.
- Reader and batch writes both use generation, expected page version, and dependency fingerprint preconditions.

Display readiness and batch completeness are separate: a `READER_ADHOC` page can count as readable while the resume planner still schedules batch-protocol translation/context work.

## 13. Pause, Cancel, Failure, and Process Death

- **Pause:** finish or safely checkpoint the current atomic stage, persist candidate state, release workers, retain committed bundle.
- **Resume:** validate the candidate and continue from its first incomplete stage.
- **Cancel:** stop workers, discard uncommitted candidate metadata and generation-owned files, retain committed bundle.
- **Failure:** retain candidate diagnostics and committed bundle; expose retry from the failed stage.
- **Process death:** any completed stage payload survives; a `RUNNING` stage without a complete commit marker becomes retryable. Committed pointer remains valid.

`FAILED_TERMINAL`, retry count, last failure category, and next eligible retry time are durable fields. They do not rely on the current transient `attemptCount`. A configuration/source/protocol change may convert a terminal failure back to retryable when its failure fingerprint no longer matches.

Candidate writes require `(chapterId, pageKey, generationId, expectedPageVersion, expectedDependencyFingerprint)`. A stale worker cannot overwrite newer work.

## 14. Legacy Migration

Migration must not mutate the only copy of a legacy record in place.

1. Write the migrated manifest to a new temporary file, flush and validate it, then atomically rename it over the primary manifest while retaining a recoverable backup until the next successful load.
2. Snapshot a legacy displayable page as a provisional committed bundle.
3. Validate physical cleaned files and block/layout data.
4. Infer only provenance that can be proven from stored revisions and current assets.
5. Mark unprovable provenance `UNKNOWN_LEGACY`.
6. When refresh is required, create a candidate and rerun from the earliest uncertain stage while keeping the provisional bundle visible.
7. Legacy rolling prose summaries and translated-pronoun facts are untrusted. Rebuild context from page 1 using stored source OCR where valid.
8. Preserve glossary entries as user/provider vocabulary hints, but do not treat them as speaker, gender, relationship, or narrative evidence. Migrate them to a versioned glossary sidecar and fingerprint their version.

Legacy state mapping:

| Legacy shape | Initial display state | Migration action |
|---|---|---|
| Valid cleaned image + complete target blocks + valid layout | `DISPLAY_READY` provisional | Preserve as committed; refresh unknown provenance in candidate. |
| Recognized/translated blocks but no cleaned image or layout | `ORIGINAL_ONLY` with reusable candidate data | Do not call ready; validate OCR/translation, run earliest missing display stages. |
| Partial target blocks | `ORIGINAL_ONLY` unless an older complete bundle exists | Preserve partial data as candidate diagnostics; resume translation. |
| Persisted `RUNNING` | Committed state if valid, otherwise `ORIGINAL_ONLY` | Convert interrupted stage to retryable candidate. |
| Missing/corrupt cleaned file | `FAILED_NO_RESULT` or old valid committed result | Regenerate from reusable masks; never point reader at missing file. |
| Corrupt record | Recover backup/last valid manifest or `FAILED_NO_RESULT` | Quarantine corrupt payload; never overwrite the only recoverable copy. |

## 15. Storage Layout and File Retention

Each chapter uses:

- `chapter.translation.manifest.json`: schema version, page records, committed pointers, active candidate IDs, durable failures, and glossary pointer;
- `artifacts/<pageKey>/<stage>/<fingerprint>.json`: immutable detection, OCR, translation, and layout payloads;
- `images/<pageKey>/<generation>-<fingerprint>.<ext>`: immutable cleaned images;
- `context/<naturalPageIndex>-<checkpointHash>.json`: immutable page-scoped trusted checkpoints;
- `generations/<generationId>.json`: run ownership, leases, candidate reachability, and lifecycle state;
- `chapter.glossary.<version>.json`: vocabulary hints without inferred identity facts.

The manifest’s committed pointer is the single atomic reader-resolution boundary. Candidate sidecars are invisible until promotion. During a full refresh, disk retention is bounded to current committed + active candidate + one previous committed file generation per page; orphan reconciliation runs after promotion/cancel and at chapter load.

- File names include page key, generation, and artifact fingerprint.
- Write to a temporary file, flush, validate, then atomically publish the artifact identity.
- Never delete a committed file before the replacement pointer is committed.
- Retain the immediately previous committed generation until no active stream references it or a bounded grace period expires.
- On cancellation, delete only files proven to belong exclusively to the canceled candidate generation.
- Periodic reconciliation removes orphan candidate files using store reachability, never filename age alone.
