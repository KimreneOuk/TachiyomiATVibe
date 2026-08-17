# Brief — Translation-quality brainstorm (2026-07-16)

## Objective
Prepare an external brainstorming packet identifying **remaining** translation-
quality and logic gaps after the 2-pass ID-mapped pipeline shipped, so an external
reviewer can propose concrete improvements. (UX/UI screenshot review is handled
separately by the user; this is logic/pipeline only.)

## Current symptom / desired behavior
Translation works end-to-end (detect → OCR → inpaint → Pass-1 → Pass-2 → render),
but quality ceilings remain: pronoun/speaker drift across long chapters, garbage
OCR reaching the LLM uncritically, no human-in-the-loop correction path, Pass-2
can't fix a wrong `[OK]`, provider parity gaps.

## Scope boundary
- IN: OCR/recognition, inpainting/cleaning, the 2-pass contextual translation
  pipeline, and the reader/UX *logic* that consumes translations.
- OUT: UX/UI visual design (user screenshots separately). No production code
  changes — this is investigation + an external-review artifact only.

## Acceptance criteria
- A self-contained `external/context_packet.md` grounded in live code (file:line
  refs), explicitly marking already-solved work so it isn't re-proposed.
- Labeled gap IDs (G-OCR / G-INP / G-TX / G-UX) with constraints and open
  questions a reviewer can act on.

## Constraints
- Source of truth: live code under `app/src/main/java/eu/kanade/translation/`
  and `.../tachiyomi/ui/reader/`. Deleted docs are NOT relied upon.
- Follow `AGENT.md` and `docs/project_context/knowledge_base.md`: external review
  loop only on explicit request (this task is that request).
- ≥6 GB RAM target, strict no-fallback, atomic state patches, plain-JVM tests,
  no external services beyond the user's LLM provider.

## Stop conditions
- Packet delivered and the stale `brainstorm_context.md` marked superseded.
- No commits unless the user asks.

## Artifacts
- `external/context_packet.md` — the brainstorming packet (authoritative).
- Supersedes `Plan/active/translation-quality/external/brainstorm_context.md`
  (pre-2-pass, historical).
