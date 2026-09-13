# T924 Director verification note — ZERO-LEGACY build

**Installed APK (2026-09-12, 03:51):** `0.17.1-452` on
192.168.100.223:41647 — code at `eeff99f` (doc-truth pass `439226e` is
comment-only, not in the APK). Branch `t924/batch-profile-pipeline`.

**What this build is:** the zero-legacy end state. ONE Batch coordinator
(`ChapterProfileBatchCoordinator`) with TWO engine lanes:

- **Standard engines (MLKit / Google / DeepL):** pure full-chapter OCR
  preflight → per-page batch translation through the proven per-page
  machinery → overlap inpaint → single COMPLETE record. No glossary, no
  analysis, no profile, no envelopes.
- **AI engines:** the same OCR preflight → chapter analysis → frozen
  profile → envelope translation → the same shared FINALIZE.

There is NO legacy batch path and NO feature flag anymore: the FF-01 A/B
flag and `SequentialBatchCoordinator` are deleted. The Experiments group
(debug settings) now shows only FF-02 (persisted layout reader bridge) —
still OFF pending its own gate-7.8 device evidence; leave it OFF for now.
Verified state: forced unit sweep 242 suites / 1764 tests / 0 failures.

**What to verify in real usage (this replaces the old per-gate checklist):**

1. **Standard engine, end-to-end:** translate a chapter with a standard
   engine — full OCR progress first, then page-by-page translation; chapter
   completes TRANSLATED (not ERROR); re-request is zero-work.
2. **AI engine, end-to-end:** unchanged behavior vs the A/B window —
   analysis → profile → envelopes; per-envelope logcat lines remain the
   request-count truth (built-chunk counters are not sent requests).
3. **Restart resume:** kill the app mid-run, re-request the chapter —
   OCR checkpoints are reused (no re-OCR), translation resumes.
4. **Reset:** "reset translation data" then re-request — the run retires
   and work genuinely re-runs (never a silent no-op).
5. **Tracker:** progress settles cleanly at completion (no pages stuck
   QUEUED; fraction reaches full).
6. **Degenerate config guard:** if the AI engine is somehow configured
   without a contextual translator, translation pauses with a typed
   CONFIGURATION reason — that is correct behavior now, not a bug to
   route around.

**Anything unexpected is a real bug** — there is no flag to flip and no
legacy fallback. Report it and it goes through the normal wave process
(records in `implementation-sequence.md`, follow-ups section).
