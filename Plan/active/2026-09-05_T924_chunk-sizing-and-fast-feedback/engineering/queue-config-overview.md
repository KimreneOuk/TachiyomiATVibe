# T924 queue, lifecycle, and configuration overview

Architecture investigation only. HEAD `adbe643` verified 2026-09-05. No implementation edits/tests. Paths below relative to `app/src/main/java/`.

## Existing queue behavior — VERIFIED

- Same chapter is deduplicated at preflight and authoritative queue mutation: `eu/kanade/translation/ChapterTranslator.kt:495-503,778-789`.
- Only one chapter worker selected globally: candidate queue grouped by source then `.take(1)`; an active translating entry takes precedence so newly added/rearmed work does not preempt it (`ChapterTranslator.kt:368-410`). Thus not one simultaneous chapter per source.
- Single-chapter Start Batch is replacement-oriented within a source: `TranslationManager.kt:883-892` checks a different actively translating chapter of same source; `ui/manga/MangaScreenModel.kt:1215-1221` opens conflict dialog; `ui/manga/MangaScreen.kt:384-408` offers replace confirmation/cancel. Confirmed replacement removes running chapter, clears transient queued states while retaining accepted work (`TranslationManager.kt:928-941`).
- More surprising: single-chapter admission silently evicts other same-source QUEUE entries (`TranslationManager.kt:765-768,903-918`). This can remove future chapters a user intentionally queued; artifacts remain. Multi-selection uses separate append path, without this eviction (`TranslationManager.kt:807-844`, `MangaScreenModel.kt:1191-1201`). Different-source chapter has no replacement conflict and waits behind active work.
- Queue state/progress available through canonical `getTranslationProgress` (`TranslationManager.kt:676-678`); queue position attached by `manager/BatchProgressProjector.kt:301`. Existing UI does provide conflict feedback, but cannot describe all entry points as one consistent “queued behind current” UX.

## Background and reader lifecycle — VERIFIED

- Manager starts foreground service when queue active (`TranslationManager.kt:691-697`). Service shows ongoing DATA_SYNC notification and Stop All action (`data/translation/TranslationForegroundService.kt:49-64`), progress (`:174-215`), and paused/retry outcomes (`:67-95,255-282`). Stop action clears queue (`:69-71`), not merely hide notification.
- Reader teardown cancels reader work but only stops translator if no batch active (`manager/ReaderTeardownCoordinator.kt:67-78`); chapter switching retains batch-owned stores (`:92-100`). Therefore reading, chapter switching, or leaving reader is intended to coexist with batch continuation.
- Foreground service is `START_NOT_STICKY` (`TranslationForegroundService.kt:95`): do not promise unstoppable execution. Process death stops work.
- Queue membership/order persisted synchronously (`TranslationQueueStore.kt:42-49`); status and per-page results belong to artifacts. Manager restores queue without auto-start (`TranslationManager.kt:247-248,280-285`; explicit no-start at `:633-634`). User must resume/rearm; paused outcomes restored too. Committed durable work reusable, interrupted in-memory work not promised.
- Pause cancels translator job and moves translating entry back to QUEUE (`ChapterTranslator.kt:329-334`). Rearm honors cooldown, unless forced, and does not start second worker (`:342-360`). Successful/ready-with-warnings entries removed; paused/error entries remain (`:415-442`). One chapter failure need not kill rest of queue.

## Configuration is NOT a single frozen batch snapshot — VERIFIED / STRONG INFERENCE

- Queue construction parses source/target languages (`ChapterTranslator.kt:508-528`), but batch execution independently rereads current language prefs at chapter start (`pipeline/batch/BatchChapterTranslator.kt:282-284`). A queued entry is not a trustworthy immutable configuration contract.
- Batch engine setup checks current preferences under rebuild/native gates (`BatchChapterTranslator.kt:299-312`). AI flag, contextual translator reference, output-token preference, and profile selected once at start (`:348-356`). Workers receive those snapshots plus current shared engine suppliers (`:570-592`).
- `BatchLaneWorkers.kt:1397` reads reading order live during admission. `:176-178` resolves generic translator/recognition through supplier each call, whereas contextual translator captured at batch start. Thus “configuration changes apply only next batch” is false as blanket statement.
- `pipeline/EngineLane.kt:410-439` rebuilds recognition and translator when language/model/inpaint/reading-order/full provider signature differ. AI translator captures configuration at construction (`:433-438`). This is shared engine infrastructure, so mixed captured/live behavior during reader-side rebuild deserves targeted test before any safety promise. This report does not prove a particular user-visible race/repro; it identifies an architectural inconsistency.
- Reader master translation disable explicitly stops translator with engine close (`ui/reader/ReaderViewModel.kt:664`); user Stop All also closes engines (`:2375`). These are stronger lifecycle actions than passive preference changes.

Recommendation: specify new batch configuration contract explicitly. Freeze source/target language, OCR/inpaint choice, provider/model, prompt/token settings, reading order and chunk mode for a run; settings edits apply at next run, with explicit stop-and-restart if wanted now. Shared engine ownership must support this; freezing a few fields alone is insufficient. This is PROPOSED, not existing behavior.

## UX recommendations / edge cases to surface

1. Prefer consistent Add to queue as default for any new chapter. Offer Replace current explicitly. Existing single-select silent same-source queued eviction should be disclosed and reconsidered; do not smuggle queue rewrite into chunk cap implementation.
2. Duplicate same chapter should open its existing progress rather than suggest second batch is launched.
3. Background continuation is supported, process survival is not guaranteed; restart restores intent/artifacts and requires user action.
4. Distinguish pause/retry cooldown, explicit Stop All clearing queue, chapter replacement, and failed chapter; those have different resume semantics.
5. Configuration changes while running need coherent policy and tests; currently neither fully live nor fully frozen.
6. Fast/Efficient batching modes remain proposed, not implemented. Existing inpainting FAST/QUALITY names are a separate feature (`EngineLane.kt:418-419`), and must not be confused with envelope batching modes.
7. A notification Stop All clears accepted queued intent; artifacts remain but the queue cannot auto-resume from that action. Settings source/target changes before a waiting chapter starts change the actual batch language despite its enqueue-time language fields.

Main leader independently located already-existing buffered progress UI in TranslationProgressSheet.kt:698,980; do not claim all buffered progress UI must be introduced from zero.
