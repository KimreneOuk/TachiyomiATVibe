# Observable automatic revision

## Decision

Keep AI Pass 2 automatic after the batch first-pass barrier. Do not add a
manual revision action or persist revision progress in this iteration.

## Current behavior

- OCR, inpainting, first-pass translation, and rendering stream page results
  into the shared chapter store.
- The reader can display first-pass drafts before the chapter batch finishes.
- Pass 2 selects nonblank flagged blocks that have not been user-edited,
  revises them in chunks, and updates the live overlay after each chunk.

## Observability contract

`TranslationProgressSnapshot` carries the live batch phase and a
`RevisionProgress` value with eligible, completed, failed, skipped, and
user-edited counts plus the active revision page/chunk. The manga progress
sheet, reader control, and reader settings sheet consume the same snapshot.

## Validation

Focused Gradle validation was attempted with the Android Studio JDK, but the
build exceeded the tool timeout before reporting a result. Static diff checks
and source-level symbol/path checks passed; the timed-out Gradle run is not a
passing test result.
