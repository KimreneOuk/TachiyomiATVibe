# Checkpoints

## Checkpoint 1 — investigation

- Traced FAST/QUALITY inpaint calls, cleaned-image persistence, reader stream selection, and live overlay binding.
- Confirmed both modes execute inpainting; QUALITY can use push-pull fallback.
- Found a concrete stale-overlay/image-decode lifecycle hazard.
- Existing QA artifacts do not prove a current Android pixel mismatch.

## Checkpoint 2 — implementation

- Added selected-image/decode lifecycle state to `ReaderPageImageView`.
- Hold overlay blocks until the selected translated image is decoded; clear them on original selection, image-load failure, and recycle.
- Pager and webtoon holders announce the selected image before starting decode.
- Added a page-state regression test covering cleaned-image readiness separately from overlay readiness.
- Removed speculative mask and inpainting-mode propagation changes from the focused implementation.

## Checkpoint 3 — validation

- Focused planner, page-state, batch-resume, and lifecycle-policy unit tests passed.
- `git diff --check` passed.
- Emulator/manual visual validation, instrumentation tests, and the full unit suite were not run.
