# T909 — God-File Dismantling (Investigation & Plan)

Date: 2026-08-29 · Branch: `optimize_translation_finishing_page`

## Goal

Produce an evidence-based, phased plan to dismantle the translation module's
god-files. **Investigation and planning only** — no code restructuring in this
task. Implementation follows Director approval of the plan.

## Targets (size-ranked)

| File | Lines | Role |
|---|---|---|
| `translation/TranslationPipeline.kt` | 5,415 | page pipeline orchestration (pages → OCR → translate → inpaint → render) |
| `translation/ChapterTranslationStore.kt` | 2,552 | durable chapter store + legacy flat-file rescue/migration |
| `translation/TranslationManager.kt` | 2,083 | chapter/queue lifecycle + legacy decode paths |
| `recognition/RoiPageRecognitionEngine.kt` | 1,517 | per-page OCR orchestration |
| `artifact/ChapterArtifactStore.kt` | 1,504 | artifact persistence authority |
| `inpainting/AOTInpainting.kt` | 1,430 | AOT inpainting engine (ONNX, pools) |
| `rendering/TextLayoutPlanner.kt` | 1,380 | layout/line-breaking planning |
| `scheduling/RollingAutoCoordinator.kt` | 1,003 | rolling auto-translate coordination |

## Constraints

- READ-ONLY investigation. Investigators must not run gradle builds or modify
  source; they write only their own report.
- Plans must preserve behavior: pure moves/extractions, no logic changes.
- Each proposed phase needs its own verification gate (targeted tests → full
  suite) and rollback story.
- Normal manga behavior must never regress (project constraint).

## Reports (in `reports/`)

| File | Owner | Topic |
|---|---|---|
| `pipeline-investigation.md` | pipeline investigator | TranslationPipeline.kt structure, seams, extraction plan |
| `store-manager-investigation.md` | store/manager investigator | ChapterTranslationStore.kt + TranslationManager.kt structure and boundary |
| `second-tier-triage.md` | second-tier triage | the 7 files of 800–1,517 lines: split or leave |
| `dismantling-plan.md` | planner | unified phased plan, ordered by risk, with gates |

## Exit criteria

`dismantling-plan.md` exists with phases small enough to review individually,
and the Director has approved (or amended) phase 1.
