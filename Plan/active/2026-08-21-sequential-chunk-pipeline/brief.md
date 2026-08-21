# Sequential chunk translation pipeline

Objective:
Replace the chapter batch's page-interleaved schedule with sequential, reader-visible chunks.

Current symptom or desired behavior:
The live 67-page run advances OCR/inpaint across the chapter but issues no AI requests and renders no new pages. Page `001.webp` is a zero-block page left at `inpaint=PENDING`, so it appears as `Unknown error` and can block the ordered translation frontier.

Scope boundary:
- Chapter-level batch translation using `ContextualTextTranslator`.
- Textless terminal handling shared by batch analysis.
- Chunk planning, progress/diagnostics, cancellation, resume, and reader publication.
- Standard per-page translators and reader ad-hoc single-page translation retain their existing execution paths.

Acceptance criteria:
- A chapter is assigned a target window of 5 pages (<=35 total), 7 pages (36-60), or 10 pages (>60).
- Each logical chunk is reduced at page boundaries after OCR when provider block/token limits require it.
- Chunk stages are ordered: OCR all selected pages; one contextual request on the happy path; then inpaint/render and publish pages; then the next chunk.
- No native OCR/inpaint from a later logical chunk overlaps the current chunk's provider or render phase.
- Each rendered page becomes reader-visible immediately; the reader does not wait for the chapter.
- A zero-block page becomes terminal textless and never blocks later chunks.
- Cancellation/restart preserves committed OCR, translation, cleaned images, render state, and trusted scene context.
- Provider/protocol failure may use bounded retry or smaller recovery envelopes; later chunks do not start until the current logical chunk is terminal.
- Logs identify planned chunk page ranges, page/block counts, request attempts, stage boundaries, and outcomes.

Constraints:
- One decoded page/native tensor set at a time; bounded memory on devices with at least 6 GB RAM.
- Natural chapter order and scene-context prefix rules remain authoritative.
- Existing generation, lease, guarded-write, and artifact-publication contracts remain intact.
- One provider request per logical chunk is the normal path. A single page that cannot fit one provider envelope is an exceptional logical chunk and may use bounded transport slices without publishing partial page output.

Stop conditions:
- Stop and realign if the existing artifact/lease model cannot publish pages independently without breaking generation safety.
- Stop and realign if a provider's context contract cannot calculate a safe whole-page boundary before request submission.

