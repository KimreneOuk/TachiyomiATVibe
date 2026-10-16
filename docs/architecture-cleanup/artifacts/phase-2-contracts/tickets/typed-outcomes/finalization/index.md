---
title: "P2.2 finalization — source hygiene and attempt wording"
kind: ticket
status: 2
assignee: ""
comments: none
---

Parent [typed outcomes](..); base d8252cbe. Primary inspection and Gemini independently confirmed three bounded issues. These fixes are authorized while Gemini completes the full semantic review; neither side should wait for another initial handoff.

Completed: all three fixes independently verified, finalization XML 37/37 tests per flavor, Gemini final acceptance recorded in [review](../review). Parent committed the full slice as `f015a33f` on architecture-cleanup: 20 paths, +491/-86 lines. Tracked tree clean afterward; original user report remains untracked and unchanged. Commit is local pending Phase 2 completion/push.

1. In TranslationPackageBoundaryTest, replace the literal NUL fallback character with the readable Kotlin source escape `\u0000`. Keep lexer behavior identical; verify no literal NUL remains, so Git treats the new source as text.
2. Clarify SinglePageOutcome.Rejected / PERSISTENCE_FAILED KDoc: this attempt's publication was rejected; a previous committed display may still exist. No semantics change.
3. Restore RoiPageRecognitionEngine's exact HEAD bytes after verifying its current content differs only in line endings. It was a planted boundary violation, now removed semantically. Do not restore or reset any other changes.

Keep all other code frozen. Explicit UTF-8 or byte-preserving writes only; scan meaningful changed/new files for BOM, literal NUL and replacement characters, check diff whitespace and preserve the original untracked user report. Keep the corrected 20 meaningful paths manifest.

Run the existing five focused classes in both flavors with fresh compilation, preserving separate finalization XML/logs (directories created before copies; executed failure XML retained too). No duplicate broad suites or unrelated formatting. Deliver final diff/evidence for Gemini recheck before commit. Prior failed runs and original baseline evidence remain intact. No child commits/pushes or P2.3 work.
