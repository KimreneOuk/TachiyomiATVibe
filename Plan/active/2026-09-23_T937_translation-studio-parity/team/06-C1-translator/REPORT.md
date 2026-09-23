# T937 C1 — Translator fidelity

**Status:** implemented on `t937-c1-translator-fidelity`, based on
`d2637a01027c5e787aceee8edfc23b33e62deb72`.

## Delivered

- Replaced the clients5/MyMemory Google fallback chain with the Android GTX
  span envelope, ordered `data-id` reconstruction, 5,000-codepoint chunking,
  and the Android tokenized per-block GTX fallback. Transport retries use
  three attempts, 1-second exponential backoff, and a 30-second inline cap.
  Requests are serialized and paced at 60 per rolling minute with 1-second
  spacing.
- Provider failures, blank outputs, and source-equal echoes remain
  untranslated; they are not written to `translations.json`. Existing cached
  values equal to the current OCR source are cleared and retried, matching
  Android's source-equal validation rule.
- GTX and OpenAI-compatible HTTP attempts both use Android's transient retry
  policy and per-provider request governor. OpenAI-compatible buckets are
  shared by normalized endpoint and model.
- Replaced per-region OpenAI-compat calls with Android's manga-localizer
  system prompt and `ID|source` batch lines. Request IDs use natural chapter
  page order plus Android's y/x/width/height request-local block ordering.
  Unknown, duplicate, blank, malformed, or missing response IDs reject that
  request chunk, which remains untranslated.
- Added the Android context ceiling and page-atomic output planner: 8,192 context tokens,
  512 safety margin, 256 output floor, 1,400 prompt reserve, and the batch
  response reserve constants. As in Android, all blocks from one page stay in
  one request or the full page is rejected. The requested output default is
  Android's 8,192 and is clamped to the available budget.

## Kotlin verification and report diffs

Verified the Android source directly before coding:

- `GoogleTranslator.kt:49-85, 89-130, 296-350` pins source/target codes,
  plans spans using `codePointCount` including markup, requires the exact
  ordered IDs, and falls back to its tokenized serial request after an invalid
  envelope. The task survey's 5,000-codepoint summary is correct; the cap
  applies to the wrapped span strings, not only OCR text.
- `TranslationRetry.kt:136-142` sets 3 attempts, 1,000 ms base delay, and
  30,000 ms maximum inline wait. `ProviderRequestGovernor.kt:69-78, 682-690`
  confirms the general 60/minute, 60,000 ms window, 1,000 ms spacing, and one
  in-flight request policy used by both Google GTX and the OpenAI-compatible
  translator. `OpenAiCompatibleTranslator.kt:43-45, 173-247, 351-364`
  confirms LM Studio uses that retry wrapper and shared governor around each
  chat-completion attempt.
- `TranslationPrompts.kt:77-108` contains the copied manga-localizer rules and
  one-line ID format. `ContextualRequestBuilder.kt:38-72, 177-196` supplies
  the `p{page}_b{block}` identity and y/x/width/height ordering;
  `ContextualResponseParser.kt:159-220` validates unknown, duplicate, blank,
  and missing IDs. Its narrow one-character separator recovery for a requested
  ID is also preserved.
- `TranslationContextChunkPlanner.kt:18-34, 187-198` sets the 8,192 context,
  512 margin, 256 minimum output, 1,400 prompt overhead, and batch output
  reserves. `StreamingChunkPlanner.kt:236-260` clamps output to the available
  budget. `AiTranslatorKind.kt:31` defaults requested max output to 8,192.

Differences from the survey: Android's chunk limit counts each escaped,
span-wrapped string, which is slightly stricter than counting source text
alone. Android defaults its AI temperature to 0.3; the Studio's existing
translation path used 0.2, so that setting was retained. The Studio has no
source-language selector; it defaults to Japanese as the old Google path did,
while accepting an optional `source_lang` setting.

## Compatibility choices and remaining risks

- The Android AI planner uses CL100K tokenization and a provider accounting
  contract. The Studio has no tokenizer dependency, so page admission uses
  UTF-8 prompt bytes as a conservative upper bound plus Android's 1,400-token
  prompt reserve. A long page can therefore be rejected where Android's exact
  tokenizer would admit it; the planner never splits a page across requests.
- Android's shared governor also budgets 60,000 tokens/minute and has a quota
  cooldown. C1 ports the request-rate, one-in-flight, and spacing policy; it
  does not reproduce the token bucket or interactive priority/cooldown
  machinery. Retry-After waits over 30 seconds are deferred by leaving the
  affected request untranslated rather than retrying inline.
- A malformed OpenAI response rejects the whole request chunk. Android can
  retain accepted per-line results alongside rejected entries in its structured
  batch object; this implementation keeps incomplete chunks wholly untranslated
  to avoid persisting a partially framed response.
- Output IDs are request-local and never replace region IDs. Region ordering,
  `translations.json` carry behavior, and prune behavior from C2 are untouched.
- A malformed GTX envelope falls back to serial gtx requests. A failed or
  deferred transport request leaves the page untranslated without generating
  more per-block traffic. No live Google or LM Studio request was required;
  offline fixtures and fake openers provide all network evidence.

## Verification

Run from `tools/translation_studio/`:

```text
python -m py_compile translation_providers.py pipeline.py test_translation_providers.py
python -m unittest test_translation_providers -v
```

Result: **16 tests passed**. Test output is in
`team/06-C1-translator/evidence/test-results.txt`. Reproducible offline
responses are in `tools/translation_studio/fixtures/`:

- `google_gtx_envelope.json`
- `google_gtx_bad_id.json`
- `google_gtx_single.json`
- `openai_compat_valid.json`

The tests cover GTX span parsing and ordered IDs, exact codepoint boundaries,
malformed-envelope fallback, retry/backoff and cap behavior, governor spacing
and rolling-minute limit, cache behavior on failure/source echo, OpenAI prompt
and stable IDs, OpenAI retry and pacing, strict response validation, and
page-atomic token ceilings.

## Changed implementation files

- `tools/translation_studio/translation_providers.py`
- `tools/translation_studio/pipeline.py`
- `tools/translation_studio/test_translation_providers.py`
- `tools/translation_studio/fixtures/`
