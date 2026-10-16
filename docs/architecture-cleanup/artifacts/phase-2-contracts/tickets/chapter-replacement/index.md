---
title: "P2.4 — ordered chapter replacement without blocking bridges"
kind: ticket
status: 2
assignee: ""
comments: none
---

# Scope

## Prepared ordering regression

[Read-only preparation](../../preparation/context-and-replacement) confirms one production caller already in the suspending IO context and no synchronous test stubs of this method at `d8252cbe`. Recheck at implementation base. Use the existing TranslationManagerDeleteResetOrderingTest fixture rather than adding a harness.

The regression must hold cancellation behind a CompletableDeferred release while the replacement operation runs as a controlled child; externally verify that clearing and removal have not begun. Release cancellation, hold clearing behind a second release, and verify removal has not begun. Release clearing and join, then assert the exact cancel/clear/remove sequence. Also cover the absent-chapter guard. Use deterministic scheduling/gates, no sleeps or new wall-clock policy. An assertion inside the cancellation stub before clearing begins is insufficient by itself to prove suspension is awaited.

Make workflow/TranslationManager.cancelRunningChapterForReplace suspending, removing its two runBlocking wrappers. Directly await scheduler.cancelPageTranslations, then store.clearTransientQueuePages, then remove the chapter queue entry. The existing MangaScreenModel.confirmReplaceRunningChapter calls inside launchNonCancellable on IO; preserve that order and acknowledge this is not a demonstrated main-thread ANR path.

Refresh all callers/mocks before editing. Update suspend-aware mocks as required and add a meaningful gate-based ordering assertion in existing chapter replacement workflow coverage. No fire-and-forget conversion, synchronous compatibility shim, changed timeout/admission policy, store initialization work or scheduler cancellation API rewrite. Preserve committed/terminal artifacts.

Focused replacement/conflict/UI tests and fresh compilation both flavors, with prior baseline and preserved XML. Return actual diff/results for Gemini/Antigravity review. No child commits/pushes or unrelated UI cleanup.

# Integration

Accepted by Gemini 3.8 Flash/high through Antigravity and committed locally as `2efde3b516dadd03c42ce21d89ea47777b6a9e4c` on architecture-cleanup. Four meaningful paths, +122/-13 lines. Final focused XML: 33 tests per flavor, zero failures/errors/skips. Final full formatting inventory: only pre-existing LiteRT debt. Phase publication follows P2.6.
