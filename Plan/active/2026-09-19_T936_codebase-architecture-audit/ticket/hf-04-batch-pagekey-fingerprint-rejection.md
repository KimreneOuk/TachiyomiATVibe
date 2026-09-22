# Ticket HF-04: Batch page-key and dependency-fingerprint rejection

**Priority:** BLOCKING (device-verified batch failure) | **Branch:** `t936/hotfix-batch-rejection`

## Device evidence

Evidence is preserved at `%TEMP%/opencode/live_capture3.txt` (pid `11252`, local
2026-09-22 08:28:28–08:28:38). The failing chapter has zero-padded source names
`001.jpg` through `013.jpg`.

Observed chain:

```text
08:28:29.216 batch START chapter=Chapter 6.1 pages=13 engine=RoiPageRecognitionEngine translator=GoogleTranslator (sid 893caf15-s4, chapter c5161d402bb1fdf88)
08:28:32.707 recognition succeeded on first page (blocks=9, providers all cpu-warm)
08:28:32.800 ChapterTranslationStore: ocr checkpoint rejected: generation=1 operation=t924 ocr preflight checkpoint reason=page missing: pageKey=001.jpg
08:28:32.801 PreflightWorker: t924 preflight checkpoint rejected pageHash=287562acd8eaf869 reason=page missing: pageKey=001.jpg
08:28:34.968→37.884 six× artifact candidate write rejected: pageKey=001.jpg reason=dependency fingerprint changed
08:28:35.786 batch stopped before tail reconciliation status=FAILED anchor=287562acd8eaf869
```

The UI displayed `Provider work is temporarily unavailable` immediately after
`Detect+OCR 1/13`, although the evidence identifies a page-structure/checkpoint
rejection rather than a provider outage.

## Scope and stop-gate

Trace, then fix, the complete batch path:

1. Compare the keys created by batch pre-registration with the keys consumed by
   checkpoint, worker, artifact-candidate, and reconciliation paths for a chapter
   whose files are `001.jpg` … `013.jpg`. Find where an unpadded or otherwise derived
   key survives. HF-01 fixed reader/manual keying; do not assume the batch registration
   seam shares that fix.
2. Trace the dependency fingerprint inputs and lifecycle. Determine whether HF-03 lazy
   persistence changed when the fingerprint is computed versus candidate validation,
   or whether the same key mismatch is causing the lookup to validate the wrong page.
3. Categorize rejection origins so a store/page-structure rejection cannot surface as
   `Provider work is temporarily unavailable`. Preserve provider-outage messaging for
   actual provider failures.
4. Add behavioral coverage for:
   - zero-padded batch chapter registration → checkpoint → artifact write → tail
     reconciliation;
   - dependency-fingerprint stability through the lazy-persistence path;
   - error-origin categorization for page/store rejection versus provider failure.

**STOP:** If registration keys already match the padded source names, do not change
key derivation. Report the actual divergence and its evidence to the assigning agent
before making a fix.

## Constraints

- Preserve HF-01 canonical source-file-name keying, HF-02 session gates, HF-03 lazy
  persistence ownership/durability, T930/T934 durability contracts, and provider
  fallback behavior.
- Do not weaken assertions or hide checkpoint/artifact rejection.
- Do not alter NNAPI/hardware routing, model assets, or unrelated batch scheduling.
- Keep legacy on-disk data readable; this hotfix must not delete user data.

## Verification

- Focused batch/checkpoint/artifact/error-origin suites, with new regression tests green.
- Flavor-qualified full suites: `:app:testDevDebugUnitTest`
  and `:app:testStandardDebugUnitTest`, using `--no-parallel --max-workers=1`.
- `:app:assembleDevDebug` and APK inspection as required by the current campaign.
- Remove any temporary `app/google-services.json` in a `finally` block.

## Report

`Plan/active/2026-09-19_T936_codebase-architecture-audit/team/hf-04-implementation-report.md`
