# Phase 1 protocol checkpoint

## Scope completed

The live chapter-batch AI path now uses version 1 of a line-delimited envelope. A request carries
natural-page sections inside an inert source-data section; a response carries exact page sections,
optional page-scoped context-delta sections, and a required response footer.

Block IDs are canonical and globally unique within a request:

```text
p{naturalPageIndex}_b{stableBlockIndex}
```

Both indexes are zero-based, matching the existing `PageTranslation`/pipeline page indexes. The
stable block index comes from the persisted block ID when available, and otherwise from deterministic
OCR-region geometry. Reading-order sorting happens after assignment and cannot rename a block.

## Validation and promotion

Batch responses are fail-closed. The parser rejects duplicate, missing, unknown, malformed or
normalized IDs, blank output for a nonblank source block, wrong-page IDs, missing/duplicate page
sections, unclosed/extra sections, wrong protocol markers, and extra content outside the envelope.
No translation or context delta is applied when the strict batch is structurally invalid.

The response retains page-scoped context deltas as opaque data for the later context-quality phase;
Phase 1 does not interpret, persist, or promote scene/profile facts.

An empty/textless requested page is represented by one empty PAGE section and is valid; it has no
required translation IDs. Provider-added trailing whitespace is trimmed only from a translation
tail. IDs and all envelope markers remain exact. Source CR/LF characters are flattened before they
enter the inert source-data fence.

Structural failure is surfaced as a typed, privacy-safe reason/count summary. The live retry
controller fails the affected request once and does not recursively split an invalid envelope as
if it were merely missing content. The batch output budget reserves fixed, per-page, per-block,
delimiter, ID, and bounded-whitespace overhead before selecting a chunk cap.

## Compatibility decision

The strict envelope is selected only by `TranslationContextChunk.protocol == BATCH_V1`, which is set
by the chapter streaming planner. Reader/single-page translation explicitly remains `LEGACY`, so its
existing `bN|text` contract and prompt are unchanged. This is a deliberate protocol boundary rather
than a permissive fallback: a batch provider response that is not version 1 is invalid and cannot
silently fall back to last-page-wins ID handling.
