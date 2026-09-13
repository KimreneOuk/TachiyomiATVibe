# T924 Stage 1 — semantic fingerprints report (T924-FP-02..FP-09)

Phase 2b implementer report · 2026-09-05 · worktree `TachiyomiAT-t924-impl`,
branch `t924/batch-profile-pipeline` (base `bc94045`, uncommitted by design —
orchestrator reviews and commits).

## 1. Scope of change

Two files, both owned by this phase:

- `app/src/main/java/eu/kanade/translation/artifact/StageFingerprints.kt` —
  added 7 builders, 2 supporting value types, 3 constants, 2 text helpers.
  All existing functions, their bodies, and the private
  `fingerprintIndexed`/`appendField` encoding core are byte-identical to the
  `bc94045` state (verified below).
- `app/src/test/java/eu/kanade/translation/artifact/SemanticFingerprintTest.kt`
  — new, 19 tests (T924-FP-09 gate). `StageFingerprintsTest.kt` untouched
  (its 6 tests are part of the regression proof).

No other file was created or edited. The working tree also carries Phase 2a's
in-flight edits to `ChapterTranslationStore.kt` / `ChapterArtifactStore.kt`;
they are not mine and were never touched.

## 2. Contract re-verification (clause → function → fields)

Contract: `stage0/contracts-schemas-fingerprints.md` §5 (authoritative).
Note on numbering: the task brief labels `PageOcrContentFingerprint` "FP-01";
the contract numbers it **T924-FP-02** (T924-FP-01 is the global exclusion
rule, not a function). The contract was followed.

### T924-FP-01 — Global exclusion list (rule, no function)

Not implemented as a builder; enforced by construction and tested:
`SemanticFingerprintTest."page ocr content excludes translation state, user
edits, colors and score"` and `..."profile content ignores operational
fields"`. Excluded everywhere: generation ids, page versions, sidecar file
names, monotonic versions as validity keys, timestamps, attempt/retry counts,
envelope ids, `userEditedAt`, display state, `activeCandidateGenerationIds`.
No new builder accepts any of these as a parameter (audit by signature).

### T924-FP-02 → `StageFingerprints.pageOcrContentFingerprint(...)`

Fixed-order inputs (label `"page-ocr-content"`):

| # | Included (contract) | Parameter / encoding |
|---|---|---|
| 1 | corpus schema version | `OCR_CORPUS_SCHEMA_VERSION = 1` |
| 2 | pageKey | `pageKey: String` |
| 3 | naturalPageIndex | `Int?`; null encodes `<null>` |
| 4 | source sha256+width+height+orientation | 4 params |
| 5 | detection fingerprint or `SKIPPED` marker | `detectionFingerprint: String?` → `DETECTION_SKIPPED = "SKIPPED"` when null |
| 6 | OCR engine/model/config fingerprint | `ocrFingerprint: String` (the persisted `StageFingerprints.ocr(...)` value; see interpretation I-1) |
| 7–9 | per block, in order: stable block id; NFC/LF-normalized text; `toRawBits` of x/y/width/height/angle + label | `blocks: List<OcrBlockContent>`, marker `block[i]` per block |
| 10 | textless state | `textless: Boolean` |
| 11 | ordered `inpaintMaskBoxes` with label + `inpaintMaskRevision` | `inpaintMaskBoxes: List<InpaintMaskBox>`, marker `mask[i]`, then revision int |

Excluded per contract: block `translation`, `userEditedAt`, colors,
score, candidate/page versions, file names. `OcrBlockContent` physically
cannot carry them; the `pageOcrContentBlocks(PageTranslation)` mapping helper
copies only the 8 allowed fields. Test proves a `TranslationBlock` with edited
translation/userEditedAt/textColor/strokeColor/strokeWidth/score maps to the
same fingerprint.

### T924-FP-03 → `StageFingerprints.ocrCorpusFingerprint(pages, expectedPageCount, expectedPageCountTrusted, naturalOrderProven)`

Inputs (label `"ocr-corpus"`): `OCR_CORPUS_SCHEMA_VERSION`; expected page
count; trusted flag; `page[i]` entries of (pageKey, per-page
`pageOcrContentFingerprint`); explicit `naturalOrderProven` field.
`naturalOrderProven=false` sorts entries by pageKey inside the builder, so a
permuted input list hashes identically and the unproven mode is an explicit
hashed field (never-guess rule for `naturalPageIndex`).

### T924-FP-04 → `StageFingerprints.profileInputFingerprint(...)`

Inputs (label `"profile-input"`): ocr corpus fingerprint; source/target
language; analysis schema version; analysis prompt version; analyzer
provider/model/credential signature; analyzer policy fingerprint;
user-authority fingerprint; series-authority fingerprint. Null authority →
`AUTHORITY_ABSENT = "ABSENT"` literal; test proves `"ABSENT"` ≠ `""` ≠
absent-of-different-call (absence is a value, never an empty-string
collision).

### T924-FP-05 → `StageFingerprints.profileContentFingerprint(ChapterTranslationProfile)`

T924-SC-10 canonical re-encode: hashing copy zeroes `version`, blanks
`contentFingerprint` (self-reference impossible), `sourceRunId`, zeroes
`frozenAtEpochMs`; encodes through the shared `ArtifactDocumentJson`
(T924-SC-06 — no bespoke Json); SHA-256 lowercase hex over UTF-8 bytes;
stored bytes never mutated. `profileInputFingerprint` and
`analyzerProvenance` remain hashed fields (contract excludes only
version/frozenAtEpochMs/sourceRunId). Tests: version-only bump ⇒ equal;
fact/scene/provenance change ⇒ different; decode→encode round trip ⇒ equal;
golden `e18be544e838bf4c59b29cf996b6816cb5382284f3efac4dac79568a54b667e9`.

### T924-FP-06 → `StageFingerprints.translationProvenanceFingerprint(...)`

Inputs (label `"translation-provenance"`): profile content fingerprint;
translator provider/model/credential signature/protocol version; prompt
version; source/target language; `contributingPages: List<TranslationProvenancePage>`
— per page: `pageOcrContentFingerprint` + ordered stable block ids + ordered
per-block source-text hashes actually sent (helper
`StageFingerprints.sourceExcerptHash` = T924-SC-09 NFC/LF-normalized SHA-256).
Envelope policy and envelope-plan fingerprints are NOT parameters (matrix row
7: envelope-policy-only change must not invalidate translations).
Rolling-context / profile-subset fingerprints are deliberately not merged —
KDoc directs callers to record them separately, per contract.

### T924-FP-07 → `StageFingerprints.layoutCompatibilityFingerprint(...)`

Label `"layout-compatible"`. First 7 parameters = the existing
`StageFingerprints.layout` input list in the same order (translation artifact
id, cleaned-image-or-original id, layout engine version, font identity, font
scale prefs, style prefs, output dimensions); then ALL contract additions:
font asset name, font asset sha256, typeface/style (`BOLD`), paint
measurement flags (`ANTI_ALIAS|SUBPIXEL_TEXT`), layout planner version,
`platformShapingKey`, stroke policy version, decode sample size, source page
width/height (floats via `toRawBits()`). No SSIV/pan/zoom/orientation
parameter exists. 17-field sensitivity test: each single change ⇒ different.

### T924-FP-08 → `StageFingerprints.colorStyleFingerprint(...)`

Label `"color-style"`. Inputs: color estimator version; consumed image
identity (`DisplayBaseKind.CLEANED_IMAGE` + cleaned file name +
`inpaintRevision`, or `DisplayBaseKind.ORIGINAL_SOURCE` + original source
sha256 — the kind is an explicit hashed field); per-block geometry
fingerprints consumed by estimation (`geometry[i]`); page dims
(`toRawBits`). No font, planner-version or translated-text parameter.
Tests: cleaned-mode identity/revision/geometry/dims sensitivity; both
original-source modes distinct from each other and from cleaned mode.

### T924-FP-09 — determinism/equivalence gate

Implemented as `SemanticFingerprintTest` (19 tests): (a) repeated computation
identical for every builder; (b) FP-01 exclusions proven for FP-02 and FP-05;
(c) delimiter-forgery fixtures for FP-02 (single-field forgery and two-block
split); (d) golden fixtures — frozen profile content fingerprint and a
200-page synthetic corpus fingerprint, both hardcoded and byte-stable. NFC +
CRLF/CR normalization tests (T924-SC-09) for FP-02 and `sourceExcerptHash`,
including case-sensitivity (no case folding).

## 3. Proof that existing builder outputs are unchanged

1. `git diff` on `StageFingerprints.kt` shows only additions; no existing
   declaration, the private encoding core, and the file header KDoc are
   byte-identical to `bc94045`.
2. `StageFingerprintsTest.kt` unmodified (empty `git diff`); its 6 tests pass.
3. Full artifact package suite: 10 suites, **143 tests, 0 failures,
   0 skipped** — the pre-existing 124 artifact tests plus the 19 new ones.
   The 124 include `AtomicChapterDocumentsTest` and `ChapterArtifactStoreTest`,
   which assert persisted fingerprint behavior.
4. All new builders use fresh distinct labels (`"page-ocr-content"`,
   `"ocr-corpus"`, `"profile-input"`, `"translation-provenance"`,
   `"layout-compatible"`, `"color-style"`), so they cannot alias any existing
   builder's encoding.

## 4. Build / test invocations

From worktree `TachiyomiAT-t924-impl`, `JAVA_HOME` = Android Studio JBR:

- `./gradlew :app:compileStandardDebugKotlin` — BUILD SUCCESSFUL (FP-02
  compiled alone first to unblock Phase 2a; re-verified after FP-03..08).
- `./gradlew :app:testStandardDebugUnitTest --tests
  "eu.kanade.translation.artifact.*"` — BUILD SUCCESSFUL in 1m 44s;
  143 tests, 0 failures, 0 skipped (124 pre-existing + 19 new).

Phase 2a interference: 6 compile attempts hit transient errors in
`ChapterTranslationStore.kt` (foreign file, mid-edit); per protocol I waited
60 s and retried without touching it; compile then succeeded.

## 5. Deviations / interpretations

- **I-1 (FP-02 OCR config input).** Contract: "OCR engine/model/config
  fingerprint (`StageFingerprints.ocr` inputs…)". Implemented as the single
  persisted `ocrFingerprint` value (the `StageFingerprints.ocr(...)` output
  already carried by `PageOcrCheckpoint.ocrFingerprint`) rather than 5 raw
  config parameters. Same information content (engine version, model hash,
  source language, preprocessing + normalization versions, detection artifact
  id), avoids re-deriving a divergent config hash. Flag for Technical Lead
  ratification.
- **I-2 (FP-03 pageKey explicit).** Contract hashes "the ordered sequence of
  per-page `PageOcrContentFingerprint` values"; I feed (pageKey, fingerprint)
  pairs so the sorted-pageKey fallback order is itself hashed. Deterministic;
  each fingerprint already embeds its pageKey, so the extra field adds
  order-provability, not new semantics.
- **I-3 (FP-08 mode literal).** Contract's "cleaned file name +
  inpaintRevision, or ORIGINAL_SOURCE marker + source sha256" is encoded with
  an explicit `CLEANED_IMAGE`/`ORIGINAL_SOURCE` kind field (reuses
  `DisplayBaseKind` names) so the two modes can never alias.
- **Test-file choice.** Contract names no test file; used
  `SemanticFingerprintTest.kt` per task option. Existing
  `StageFingerprintsTest.kt` left untouched to keep the regression proof
  clean.
- No existing function was modified — no loud deviation required.

## 6. Remaining risks

- **Hex formatting locale.** The shared encoding core (pre-existing, reused
  unchanged) renders bytes via `"%02x".format(byte)`, which follows the
  default locale on JVM. Existing fingerprints share this behavior, so
  consistency is preserved, but on a locale with non-ASCII default digits hex
  output could deviate. Changing it would alter persisted values and is out
  of scope for this phase; recommend a separate L1 task if the Director wants
  hard locale pinning.
- **Golden fixtures are JVM-stable, cross-process verified only on this
  machine** (single process). kotlinx.serialization output for these DTOs is
  declaration-order deterministic, so risk is low; the Stage-2 exit audit
  (FP-09d) should re-run goldens on a second device/process.
- **`platformShapingKey` bucketing is a free string here** (FP-07); Director
  decision 7.5 (SDK-int vs tested bucket) still open — the fingerprint
  accepts whatever policy value is supplied, no change needed later.
- **Phase 2a integration:** `pageOcrContentFingerprint` is available and
  compiled; Phase 2a must map its snapshot blocks via
  `StageFingerprints.pageOcrContentBlocks(...)` (or an equivalent 8-field
  mapping) and pass the checkpoint's `inpaintMaskRevision` — not
  `PageTranslation.inpaintRevision`, which tracks cleaned-image rewrites and
  is a different counter.
