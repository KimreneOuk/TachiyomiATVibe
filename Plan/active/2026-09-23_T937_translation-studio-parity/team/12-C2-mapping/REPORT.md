# T937 C2 — Stable translation mapping and prune

**Status:** Complete
**Branch:** `t937-c2-translation-mapping`

## Implementation

- OCR regions receive page-local `bN` identities in Android order: y, x, width, height, then original index. Existing valid IDs are reserved page-wide before new IDs are allocated. A unique matching `artifact_id` carries an ID across an OCR refresh.
- Each region stores an OCR fingerprint over text, box, parent box, label, score, and segmentation `mask_ref`.
- `translations.json` uses stable block keys and records containing `text`, `origin`, `ocr_fingerprint`, and a region snapshot. Auto translations follow a matching stable ID and take the current fingerprint. User edits remain active only when their saved fingerprint exactly matches the OCR region.
- Unmapped entries and fingerprint mismatches are removed from the active mapping and appended to that page's `_pruned` audit list with reason, text, provenance, prior fingerprint, region snapshot, and timestamp. The existing `/api/ocr` route calls `Pipeline.ocr_page`; fresh and cached OCR paths now both reconcile translations and return the prune list.
- The artifact view reads `_pruned` from `translations.json` and creates OCR-group records for the B2 tree. User-edited and pruned translation filters enable when matching records exist.

## Migration

On chapter load, a unique exact fingerprint match maps first. Otherwise, a legacy ordinal key maps through the corresponding persisted OCR region ID and its newly assigned stable ID. Existing `bN` and `pN_bN` IDs normalize to `bN`. Legacy text-only values become `origin: legacy` records and receive a fingerprint when safely attached to a current OCR region. If OCR data is absent, entries stay pending; if a later OCR result cannot map one, it moves to `_pruned` rather than disappearing.

When an explicit `user` or `auto` stable record conflicts with an ordinal alias, the explicit stable record remains active and the alias is audited. If both records have unknown legacy provenance, the mapped ordinal value remains active and the superseded value is audited as `migration-conflict-superseded`.

## Verification

The final full run passed all checks. Exact command from the repository root:

```powershell
python Plan/active/2026-09-23_T937_translation-studio-parity/team/12-C2-mapping/evidence/run_verification.py
```

It runs the six baseline suites (`selftest_cache_fingerprints.py`, `selftest_mask_planner.py`, `selftest_ocr`, `selftest_inpaint.py`, `selftest_provenance.py`, and `selftest_server_concurrency.py`), the six-test C2 selftest, C1's 16 provider tests, and B2 smoke. Results are in [verification_results.json](evidence/verification_results.json) and [verification.log](evidence/verification.log); suite evidence is under [baseline](evidence/baseline) and [b2_smoke](evidence/b2_smoke).

Also passed:

```powershell
python -m py_compile tools/translation_studio/pipeline.py tools/translation_studio/selftest_translation_mapping.py
node --check tools/translation_studio/static/app.js
git diff --check -- tools/translation_studio/pipeline.py tools/translation_studio/static/app.js tools/translation_studio/static/index.html tools/translation_studio/selftest_translation_mapping.py
```

The focused tests cover spatial ordering and persisted-ID reuse, collision-free allocation before an existing `b0`, auto carry onto that same region, fingerprint field coverage, exact user-edit retention, mismatch pruning, legacy migration and persistence, and reconciliation through the cached OCR path.

## Scope notes and remaining risk

`server.py` needed no change: `/api/ocr` already forwards `Pipeline.ocr_page` results, and `/api/artifacts` already reads `translations.json`. No inpaint, leg, or variant code changed. The independent review found and re-reviewed the ID reservation fix; its artifact reports no outstanding findings.

Legacy files do not record whether a text value was user-edited or automatic. Those values stay labeled `legacy`; when the original OCR cache is missing, positional mapping by an ordinal region ID is only a heuristic. Unmapped or conflicting entries remain recoverable from `_pruned`. Stable-ID continuity across refreshes depends on matching persisted IDs or a unique detector `artifact_id`; unmatched regions are assigned by current spatial order.
