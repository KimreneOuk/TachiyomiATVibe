---
title: "P2.4 finalization — complete formatting evidence and bounded repairs"
kind: ticket
status: 2
assignee: ""
comments: none
---

Parent [chapter replacement](..), base aef57913. Primary verified baseline XML 10 tests and post XML 13 tests per flavor, all passing. The latest formatting conclusion was inaccurate: log 47dfcb26 lists LiteRTMangaInpaintingEngine, TranslationUiTruth and TranslationScheduler, not LiteRT alone. UI truth has an else/when formatting hunk; scheduler has CRLF working-copy drift. Prior logs also missed later file entries. Preserve the original logs and supersede inaccurate conclusions explicitly.

Authorized bounded repairs:

- Inspect files changed in committed Phase 2 (`dab5c124..HEAD`). For any CRLF working copy whose normalized bytes equal HEAD, restore exact HEAD bytes. Primary found ten such files: TranslationScheduler and nine tests. Skip meaningful working edits; no resets or broad formatting.
- Fix only the rejected mapper's else/when brace layout in TranslationUiTruth to formatter expectations. Preserve every category, branch, label, action and return value. This adds one meaningful path to P2.4's two-file scope.
- Run read-only spotlessCheck and inspect the full violation filename inventory, accounting for output truncation. Leave baseline LiteRT wrapping debt untouched. Never infer the complete list from its first entry.
- Run P5OutcomeProjectionTest and the two P2.4 classes in both flavors with fresh compilation (source forecast 33 tests per flavor). Preserve finalization evidence separately; do not overwrite previous runs.

Return exact diff, normalized/exact byte proofs, full formatting disposition and executed XML counts to the parent and Gemini. No commits, pushes, Phase 2.5, device/provider/model work, unrelated code changes or new tests. All claims of final formatting acceptance remain pending the corrected evidence.

# Integration

Accepted by Gemini 3.8 Flash/high through Antigravity and committed locally as `2efde3b516dadd03c42ce21d89ea47777b6a9e4c` on architecture-cleanup. Four meaningful paths, +122/-13 lines. Final focused XML: 33 tests per flavor, zero failures/errors/skips. Final full formatting inventory: only pre-existing LiteRT debt. Phase publication follows P2.6.
