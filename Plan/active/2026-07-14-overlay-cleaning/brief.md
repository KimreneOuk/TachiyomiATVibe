# Brief

## Objective

Ensure translated text is never drawn over an image that has not finished decoding as the selected cleaned image.

## Current symptom or desired behavior

Investigation confirmed that both FAST and QUALITY invoke the inpaint stage; QUALITY may use its logged, memory-aware push-pull route when neural processing is unavailable. The reader has separate image and overlay lifecycles, and stale translated blocks could be rebound while a replacement image was still decoding.

## Scope boundary

Keep the existing live-overlay architecture and FAST/QUALITY route policy. Do not rewrite the inpainting models, change segmentation-mask composition, or change blank-OCR preservation semantics. Changes are limited to reader image/overlay lifecycle synchronization, focused state coverage, and task handoff documentation.

## Acceptance criteria

- Overlay blocks are hidden while a new image is decoding and rebound only after the selected translated image is ready.
- Original-image selection clears pending translated overlay state.
- Image-load failure and holder recycle clear stale overlay state.
- Cleaned-image display readiness remains distinct from translated-overlay readiness.
- Targeted unit tests pass; no existing dirty user changes are overwritten.

## Constraints

Respect AGENT.md, low-memory Android targets, existing worktree modifications, and no fallback/suppressed-error principles. No destructive operations.

## Stop conditions

Stop and report if targeted validation reveals a compile/API incompatibility, if the fix requires changing persisted schema semantics beyond the current serializer defaults, or if runtime evidence contradicts the investigated root cause.
