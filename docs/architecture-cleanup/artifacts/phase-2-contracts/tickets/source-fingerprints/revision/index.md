---
title: "P2.5 revision ? post-rekey evidence and real cache contention"
kind: ticket
status: 2
assignee: ""
comments: none
---

Independent review requires revisions before integration. Parent verified the stale snapshot and ineffective contention gate in the actual diff against 2efde3b5.

1. Resolve persisted evidence from current store state AFTER alias/rekey, in orderedStreams order, before constructing the unchanged synchronous resume planner. Never use the pre-rekey persistedPages snapshot for this selection. Preserve pristine zero-hash behavior and fail-closed unknown evidence.
2. Force the first same-key caller to enter compute and suspend on a release gate; only then start the second caller and run it to the Mutex wait. Assert both remain incomplete and compute count stays one before releasing. Retain single-compute/result assertions afterward.
3. Add regression coverage exercising the production evidence-preparation path across legacy-to-natural rekey, out-of-order persisted insertion versus canonical stream order, and pristine input. Use an existing store/translator fixture where possible. A duplicated test-local filter or resolveAll(empty) alone cannot detect the production snapshot bug. A small same-file cohesive extraction called by production is acceptable if necessary, but its selection must read current store state at invocation and tests must exercise the actual extracted path after real rekey. Do not create a generic framework or additional production file. Report any unavoidable harness expansion before implementing it.

Preserve the currently failing implementation as a separate regression RED evidence run if practical, then apply the correction and focused checks in both flavors. Retain previous XML/logs and write new revision evidence separately. Run the existing P2.5 focused selector set plus regression class, inspect full formatter violation inventory, and return precise scope/results for independent recheck. Full app suites remain P2.6; no unsolicited full reruns, commits, pushes, device/model/provider actions or persistence-internal edits.

Reviewer artifact contains separate descriptive errors (per-key mutex map, throw-to-UNKNOWN, wrong paths/author/run accounting); those are not implementation instructions. Actual accepted contract is one cache Mutex + HashMap, computed-null cached UNKNOWN, throws/cancellation install nothing and propagate.

# Integration

Independent final recheck accepted; parent committed nine paths as `999bb9be6563f957ee508f739ba2f042bf1c90ee` (+438/-83). Production selection reads current store state after rekey, in stream order. Revision evidence: 53 focused tests per flavor passing; regression RED retained. Final formatter inventory contains only baseline LiteRT debt. Full phase validation and publication remain P2.6.
