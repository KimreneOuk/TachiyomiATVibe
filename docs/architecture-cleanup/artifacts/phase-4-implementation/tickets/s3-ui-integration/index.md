---
title: "S3 - reader controller, resolver rename and ViewModel integration"
kind: ticket
status: 0
assignee: ""
comments: none
---

# Goal

Move the reader callers to the typed queue facade and retire the mirrored auto UI projection at its consumers. After S3 the scheduler's snapshot adapter and the coordinator have no production callers.

# Files

| Path | Action |
| --- | --- |
| `ui/reader/ReaderAutoTranslationPageResolver.kt` | Rename to `ReaderPageWorkResolver.kt` (net zero); return `ReaderPageWork(pageKey, readerPageIndex, streamFn)`; drop owner-version tracking; bind to reader session token + store identity; keep generation fencing, stream-selection order, canonical key preference, invalidation-before-open gate; source-ready predicate becomes shared reader semantics (manual deferred demand wakes with auto off). |
| `ui/reader/ReaderTranslationController.kt` | `handleAutoTranslation` debounce + IO handler replaced by session bind + `submitCurrent`/`updateViewport` (scroll updates viewport immediately — async store/source preparation must not drop the latest viewport); the three manual dispatch branches collapse to one `submitCurrent` for the captured session (cached bytes, cancellable online download with session/config/page revalidation, downloaded fallback); one session/page cancellation path; delete `observeAutoSnapshot` + accepted owner/window version projection; keep source-ready callback generation checks and reset-before-recycle ordering; keep `setReaderAutoEnabled`/cancel-session semantics incl. the disabled toast. |
| `ui/reader/ReaderViewModel.kt` | Delete `autoTranslationUiState`, `State.autoTranslation`, `autoSnapshotJob`, auto identity/owner-version/window-version/fence fields, `autoTranslationScrollJob`; keep one reader session generation, resolver/readiness lifetime, `observePageView`, live/batch store observations; engine-family collector becomes the compute-epoch rebind trigger; landing-page and `onPageSelected` kicks go through the controller. |
| `ui/reader/ReaderAutoTranslationUiState.kt` | Last consumers gone; file deleted here or in S4 (S3 if no remaining references). |
| Tests | `ReaderAutoTranslationLifecycleTest` invariants re-homed (N/N+1 demand, session invalidation, stream teardown); `P5OutcomeProjectionTest` drops auto-slot inputs; overlay copy/stale-session cases from `ReaderAutoTranslationUiStateTest` absorbed into existing projection tests; per-page truth now via `forManualOutcome` + `readerOutcomes`. |

# Work items

1. Resolver rename and contract change (token + store identity binding).
2. Controller auto path → bind/submit/viewport with immediate scroll updates; manual path unification incl. late-download revalidation.
3. ViewModel field/state cleanup; auto-toggle collector semantics preserved.
4. Re-home lifecycle/UI-state/P5 test invariants per the absorption table.

# Test plan

Controller-level: auto-off tap dedup through the controller; viewport promotion via scroll; stale cross-chapter drop; session invalidation before resource recycle; master-toggle cancellation path. Resolver: generation fencing, cache/original stream selection, invalidation gate, stale-token rejection (case 6). Projection: P5 truth without auto slots (case 10).

# Gates

Focused reader-UI + scheduling classes on both flavors; read-only Spotless. Out of scope: coordinator/AutoWindowState file deletion, `forAutoSlot` removal, preference/slider retirement (S4).
