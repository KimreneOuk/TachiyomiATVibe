---
kind: spec
title: "S6 dictionary asset prerequisite"
comments: none
---

The first full StandardDebug run skipped the shipped-dictionary policy test because this checkout lacked `app/src/main/assets/models/ocr/paddle-v6-small/PP-OCRv6_small_rec.txt`. The synthetic journal benchmark was the other skip; neither is passing evidence.

The coordinator found the existing dictionary in the registered primary workspace and verified its SHA-256 against the [pinned asset audit](../../../../phase-0-truth-audit). Three registered local copies agreed. Copied only this dictionary into the previously absent checkout path, with exclusive creation and a second destination hash check. No model weights, OCR algorithm, source contract or test assertion changed.

- SHA-256: `b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d`
- Size: 74,947 bytes; 18,708 text lines.
- Source workspace: `C:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiATVibe`.
- Local provenance: `validation/s6-dictionary-prerequisite-01.json`.

The restored asset has not yet been exercised. The next corrected full suite should execute this policy test; its actual XML remains authoritative. The earlier full result stays 2,309 cases / four failures / two skips. No native inference or device validation is implied.


## StandardDebug execution verified

`s6-full-standard-02` executed `PaddleOcrDynamicPageBatchPolicyTest`: 7 tests, zero failures/errors/skips. The restored dictionary check passed. Full app XML now has only the pre-existing synthetic benchmark skip. DevDebug execution remains pending.


## DevDebug execution verified

Full Dev02 also executed the dictionary policy class: 7 tests, zero failures/errors/skips. Asset SHA256 remained unchanged; the only full-suite skip was the existing synthetic benchmark.
