# T934 diagnosis — what the model actually omits in batch envelope responses

Date: 2026-09-18 (lab replay), failure observed on-device 2026-09-17 22:17–22:39.
Scope: read-only diagnosis + desktop lab replay. No app code modified.

## 1. On-device failure (facts from pulled artifacts + logcat)

Chapter: `[Kuroiwa Menou] Kinbaku Soukan ~Nureru Mesuahaha~` (206 pages),
provider `lm_studio:unsloth/hy-mt2-7b` @ `http://192.168.100.7:3456/v1`,
temperature 0.5 (app prefs), envelope policy maxBlocks=64.

Three envelopes failed tonight with the identical 4-attempt pattern
(whole → whole → missing → missing, then attempt budget exhausted):

| envelope hash | pages | blocks | missing after attempt 2 | missing after attempt 3 | died with |
|---|---|---|---|---|---|
| `eacc8b4d80515053` (22:17) | 8 | 36 | 9 | 2 | 2 |
| `629df6d84e54af58` (22:18) | 8 | 64 | 52 | 26 | 26 |
| `10d33ff59d9274c0` = e-0 (22:39) | 5 | 30 | 20 | 16 | 16 |

Notes:
- The first attempt of each envelope triggered a WHOLE retry, which per
  `AiTranslationRetryController` only happens when the response contained a
  protocol violation (malformed / unknown-id / duplicate / rejected-nonblank
  lines) — not merely missing blocks.
- Attempt durations (4.7/4.3/3.6/2.9 s at the provider's ~60 tok/s) imply the
  provider generated ~230–300 tokens per attempt and stopped early — it did
  NOT exhaust the 776 output cap. Consistent with finish=stop.
- Run record (`envelopeFailures=1, envelopeReplans=1, preflightStop=1`): the
  on-disk envelope plan was REPLANNED to 30 smaller envelopes (maxPages=5)
  after the failures; the two 8-page envelopes above belong to the discarded
  plan and are not reconstructible from disk.

## 2. Failing envelope e-0 (`10d33ff59d9274c0`) — block inventory

Identity reproduced exactly (FNV-1a over page/index/blockId/sourceText)
from the pulled OCR snapshots — pulled texts are byte-identical to what the
app sent. Pages 009–013, 30 blocks. Lengths in chars (JP source), "SFX/gasp"
= pure interjection/noise fragment, no full sentence:

| block | page | len | type |
|---|---|---|---|
| p8_b0 | 009 | 14 | sentence |
| p8_b1 | 009 | 10 | sentence |
| p9_b0..b9 | 010 | 2–7 | 10x SFX/gasp |
| p10_b0..b6 | 011 | 4–6 | 7x SFX/gasp |
| p11_b0 | 012 | 11 | gasp+exclaim |
| p12_b0 | 013 | 12 | sentence |
| p12_b1 | 013 | 10 | gasp sequence |
| p12_b2 | 013 | 12 | name sequence |
| p12_b3 | 013 | 14 | explicit sentence |
| p12_b4..b9 | 013 | 2–11 | SFX/gasp/exclaim |

Artifacts pulled (JSON only, per constraints): `C:/Users/User/t934-rca/pull/`
(envelope plan, run record, 10 OCR checkpoints + snapshots, `e0-inventory.json`).

## 3. Lab replay setup

- On-device provider host `192.168.100.7:3456` was DOWN tonight ("no route to
  host" from both PC and phone) — the real endpoint could not be probed.
- Lab replay instead: `llama-server` (LM Studio bundled llama.cpp, vulkan
  build) on this PC serving the EXACT model family+quant the device lists as
  recently used: `unsloth/Hy-MT2-7B-GGUF/Hy-MT2-7B-UD-Q4_K_XL.gguf`
  (downloaded from HF, size-verified; matches device's `hy-mt2-7b@q4_k_xl`).
- Prompt replicated byte-exactly from app source:
  `pass1SystemPrompt(batchProtocol=true)` + `characterAndTermSheetPrefix`
  (facts=0 scenes=0, matches on-device log `contextTokens=127`) via
  `ContextualRequestBuilder.renderPrompt`; `max_tokens=776`, temp 0.5.
- Response validation replicated from `ContextualResponseParser.parseBatch`
  incl. thinking-tag strip, `|` separator requirement, blank/duplicate/echo
  handling. Full request + raw response logged per run:
  `C:/Users/User/t934-rca/pull/replay-logs/`, script `replay_envelope.py`.

## 4. Replay results (7 full-envelope runs)

| run | received | missing |
|---|---|---|
| 1 | 29/30 | p12_b6 |
| 2 | 28/30 | p12_b6, p12_b9 |
| 3 | 29/30 | p12_b6, p12_b9* |
| 4 | 29/30 | p12_b6 |
| 5 | 27/30 | p12_b6, p12_b8, p12_b9 |
| 6 | 28/30 | p12_b6, p12_b9 |
| 7 | 29/30 | p12_b6 |

*p12_b9 was in run2/3/5/6's missing set; runs 3/7 missing list shows 1 item
(p12_b6) — p12_b9 present. All 7 runs: finish_reason=stop,
completion_tokens 283–307 (cap 776 never reached).

Mechanism, directly observed in raw responses: for specific lines the model
substitutes a WRONG SEPARATOR for the required `ID|text` format, e.g. for
`p12_b6` (source `く...ッ!`, 6 chars) it emitted in 7/7 runs:

```
p12_b6>Ku...!
```

`parseBatch` finds no `|` → "malformed line" → the block remains missing.
This corruption is INPUT-DEPENDENT (same lines every time: p12_b6 always;
p12_b8/p12_b9 intermittently — the last lines of the envelope), i.e.
deterministic decoding artifact, not sampling noise.

Mirror test of the app's attempt-4 repair semantics: re-asking the same
blocks in a missing-only sub-request formats CLEANLY (`p12_b6|Ugh...!`,
3/3 trials) — the corruption is context-dependent (full 30-block envelope),
so targeted retries CAN repair it, but the app's budget ran out first.

## 5. Characterization of omitted blocks

- Explicit/NSFW content: NEVER declined. The most explicit lines
  (p12_b3 "すっごい気持ちいい...ッ!" → "It's so damn good...!") were translated
  in 7/7 runs. Decline/refusal hypothesis: DISPROVEN at the model level.
- Truncation: DISPROVEN (finish=stop, ≤307 tokens of 776 in all runs).
- "Empty/noise blocks the model skips": DISPROVEN — every 2–7-char gasp was
  rendered (e.g. p9_b2 "ん...っ" → "Hnn...!").
- Actual omission class: OUTPUT-FORMAT CORRUPTION (separator substitution and
  near-tail degradation of the `ID|text` frame) on specific source lines,
  concentrated on the envelope's LAST PAGE. All locally omitted blocks sit on
  page 013 (the final page of the envelope): lengths 5–11 chars, gasp /
  exclamation / emphatic-sentence fragments.

## 6. Conclusion

The model does not "decline" blocks and does not skip SFX. The envelope
failures are a format-fidelity failure mode: the model deterministically
corrupts the `ID|text` separator frame for certain input lines (shown: `>`
for `|`, 7/7 on p12_b6), the strict batch parser then counts those blocks as
missing, retries re-ask them, the model re-corrupts them (input-dependent),
and the attempt budget exhausts. On-device the corruption rate was much
higher (16–20 of 30 blocks unattributable vs 1–3 locally); the deployed
endpoint (LM Studio on 192.168.100.7 — different runtime/template/sampler
and possibly a different quant than q4_k_xl) plausibly amplifies it, but that
host was unreachable tonight, so the on-device raw responses remain
uncaptured. The first-attempt protocol violations seen on-device (malformed /
unknown / duplicate lines — required for the whole-retry to trigger) are the
same failure class at scale.

Secondary suspect worth one check when the provider is reachable: the echo
guard (a response identical to source counts as REJECTED/missing, by design)
— pure-gasp blocks could come back unchanged under the deployed sampler and
perpetually "miss" without any malformed line.

## 7. Recommended next steps

1. When 192.168.100.7:3456 is up: rerun `replay_envelope.py` against it
   (already parameterized) to capture the deployed model's raw responses and
   confirm which corruption class dominates (separator vs echo vs blank).
2. App-side hardening candidates (for the Director to approve, not done here):
   lenient separator parsing (accept `[|>：:]` or fuzzy `p\d+_b\d+` anchored
   lines), and/or log the raw provider response + finish_reason for envelopes
   entering semantic retry (this diagnosis needed the lab to see it).
3. Consider raising `maxTotalAttempts` or budget for missing-only repair of
   ≤4 residual blocks (the 22:17 envelope died 2 blocks short).

## Evidence paths

- Pulled device artifacts: `C:/Users/User/t934-rca/pull/` (envelope plan,
  run record, OCR snapshots, e0-inventory.json)
- Replay logs (request+response+meta per run): `C:/Users/User/t934-rca/pull/replay-logs/`
- Replay script: `C:/Users/User/t934-rca/pull/replay_envelope.py`
- Model: `C:/Users/User/.lmstudio/models/unsloth/Hy-MT2-7B-GGUF/Hy-MT2-7B-UD-Q4_K_XL.gguf`
- App code refs (worktree): `app/src/main/java/eu/kanade/translation/translator/contextual/ContextualResponseParser.kt`,
  `app/src/main/java/eu/kanade/translation/translator/retry/AiTranslationRetryController.kt`,
  `app/src/main/java/eu/kanade/translation/translator/contextual/TranslationPrompts.kt`,
  `app/src/main/java/eu/kanade/translation/translator/providers/OcrArtifactSanitizer.kt`
- On-device logcat excerpts: `C:/Users/User/t934-rca/logcat-run4.log` (22:17–22:19, 22:39),
  `C:/Users/User/t934-rca/logcat-run5.log` (22:39)
