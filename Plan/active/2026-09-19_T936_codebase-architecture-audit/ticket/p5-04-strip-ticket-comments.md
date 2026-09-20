# Ticket P5-04: Strip internal ticket tags and sprint essays from source comments

**Phase:** 5 (after P5-03) | **Risk:** Low (comments only) | **Type:** Comment hygiene

## Scope

Audit baseline: 1,611 internal ticket references across 145 files in `app/src` (T924 ×701, T917 ×354,
T934 ×216, T911 ×131, T909 ×107, plus more). Recount at start with:

```powershell
Select-String -Path (Get-ChildItem -Recurse app\src -Include *.kt,*.java,*.xml) -Pattern '\b(T9\d\d|D\d{1,2}|ST-\d\d|TX-\d\d|FF-\d\d|LI-\d|R1\.\d)\b' -AllMatches
```

Report before/after counts per pattern class.

## Rules

1. Remove the ticket identifier; keep the technical invariant. Rewrite the sentence if it becomes
   broken ("T917 D1 priority matrix: a MANUAL request evicts AUTO" → "A MANUAL request evicts AUTO").
2. DELETE pure-history header essays (phase-by-phase delivery chronicles like the old coordinator
   header ST-02..ST-09/TX-22/FP-04 narrative). Keep any line that states a live contract, invariant,
   or gotcha. When in doubt: keep the technical sentence, drop the code.
3. Test names/classes were handled by P5-03; comment tags inside ALL files (prod + test) are this
   ticket. String literals that users see (UI strings) are NOT comments — do not touch string
   resources. Log message text: leave semantics, may drop the tag prefix.
4. Code zero diff: only comments (and KDoc) change. `git diff` must show no executable-line changes —
   verify with `git diff -w --stat` review and state it in the report.
5. Tag patterns in Plan/ docs are OUT of scope (they are the historical record).

## Verification

Recount → target: 0 matches in `app/src` for the pattern classes above (any residue = justify each:
e.g., a genuine false positive like a hex literal). Full both-flavor suites green (comment-only
changes, but compile catches broken comment syntax); `assembleDevDebug` green.

## Commit

`chore(translation): strip internal ticket tags and sprint essays from comments`
