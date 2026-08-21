# Batch AI Context and Translation Quality Contract

**Scope:** Sequential AI chapter batch translation only.  
**Non-goal:** Changing reader single-page/manual translation behavior.

## 1. Problem Being Solved

The current batch request can lose speaker and relationship roles because it sends mostly isolated `ID|text` lines, biases ambiguous pro-drop language toward first person, and feeds model-produced translations back as if they were factual history. In multi-page requests, block IDs can also repeat because numbering restarts per page.

The result can be grammatically fluent but semantically wrong: speaker/listener reversal, invented first-person narration, unstable pronouns, or an incorrect agent/recipient in intimate scenes. Once reused as context, one error can poison later chunks.

## 2. Invariants

- Current source text and explicit source evidence outrank all remembered context.
- A model translation cannot prove the fact it guessed.
- The model proposes context changes; deterministic code decides what is trusted.
- Context and translations commit in natural source order.
- The same context checkpoint and source/config fingerprint must reproduce the same reuse decision.
- Ambiguity is preserved when evidence is insufficient; the system must not invent certainty merely to produce a gendered English sentence.
- Batch setting `batchRelationshipAmbiguityPrior` has `NEUTRAL` and `MALE_FEMALE` values, defaults to `MALE_FEMALE` for this product decision, and breaks ties only when source evidence remains genuinely ambiguous. It applies only to batch AI translation.
- Explicit contrary source evidence is translated faithfully.
- Mature content preserves meaning, viewpoint, agent/action/recipient, tone, and explicitness level.

## 3. Globally Unique Request IDs

Every block ID is stable across retries and unique within the whole request:

`p{naturalPageIndex}_b{stableBlockIndex}`

Example: `p0007_b0003`.

The stable block index is derived from the persisted OCR-region identity, not reading order. Reading order is separate request metadata, so reordering regions does not rename the source block.

The response validator rejects:

- duplicate IDs;
- missing required IDs;
- unknown IDs;
- multiple outputs for one ID;
- blank output for a nonblank required block;
- output that changes or normalizes the ID.

No translation or context delta from a structurally invalid response is promoted.

## 4. Scene Card

The request includes a compact versioned scene card, normally 300–600 tokens excluding the current source chunk.

### 4.1 Active profiles

Keep at most 4–6 profiles most relevant to the active scene. A profile contains:

- stable local profile ID;
- display name and aliases, if known;
- temporary label when unnamed: `Speaker A`, `Speaker B`, `Narrator`, or `Unknown Third Party`;
- gender value: `UNKNOWN`, `FEMALE`, `MALE`, or another source-explicit value;
- confidence: `UNKNOWN`, `TENTATIVE`, `PROBABLE`, `CONFIRMED`;
- evidence references: source page/block, type, OCR confidence, and whether contradicted;
- current scene role only when supported: speaker, addressee, narrator, mentioned third party;
- relationship facts with independent confidence and evidence;
- speech/voice notes that affect style but are never gender proof.

### 4.2 Deterministic bounds

- An AI request envelope contains 1–4 consecutive pages and at most 24 accepted OCR blocks, subject to a lower provider-token cap.
- Native lookahead is at most three pages.
- Identity evidence uses the current page plus at most three preceding and three lookahead pages, capped at 36 source turns.
- Durable context checkpoints occur after every committed page, not after a variable-size request envelope.

### 4.3 Recent source turns

Keep the last 6–12 relevant source turns with speaker label, source text, page/panel/bubble order, and OCR confidence. Target translations may be included as non-authoritative voice examples, but they cannot update the evidence ledger.

### 4.4 Narrative state

Keep a 50–80 word source-grounded summary covering current location/action, point of view, explicit relationships, and unresolved references. It must not repeat long dialogue.

### 4.5 Unresolved items

Examples:

- current speaker could be A or B;
- a dropped subject could be the speaker or a named third party;
- a mentioned name may be an addressee rather than the speaker;
- pronoun/gender requires later evidence;
- agent/recipient of an action is still ambiguous.

Unresolved items are preferable to unsupported profile facts.

## 5. Evidence and Confidence

Confidence is computed by code from evidence classes; a model-supplied confidence is advisory only.

| Evidence | Weight | Notes |
|---|---:|---|
| Explicit source self-identification, pronoun, kinship/title, or relationship | Strong | Requires acceptable OCR confidence or corroboration. |
| Source-language gendered first-person form or direct address | Medium | Language-specific; may be stylistic. |
| Independent repeated source/structure evidence | Medium | Requires distinct source forms or detection/panel turn structure across pages; repeated model proposals do not count. |
| Name alone | Weak | Names can be unisex, aliases, or mentions of a third party. |
| Speech style alone | Weak | Never confirms gender. |
| Male–female romance preference | Tie-break only | Never persisted as evidence and never confirms a profile. |
| Previous model translation/pronoun | None | Not source evidence. |

A single low-confidence OCR line cannot confirm a durable fact. No fact can advance beyond `TENTATIVE` without at least one non-model-derived signal. Contradictory evidence is quarantined and attached to an unresolved item until corroborated or explicitly resolved by stronger source evidence.

## 6. Anonymous-to-Named Profile Linking

Before a name is known, dialogue is assigned only to temporary speaker candidates supported by panel/bubble sequence.

When a name appears:

1. Create or retrieve the named profile.
2. Classify the mention as possible self-reference, vocative/addressee, or third-party mention.
3. Examine the deterministic identity evidence window for turn-taking and explicit evidence.
4. Propose a link between the named profile and a temporary speaker.
5. Keep it tentative until medium/strong corroboration arrives.

The system must not assume that the person whose name is spoken is the person speaking.

Aliases attach to one profile only when evidence supports the link. Ambiguous aliases remain unresolved.

## 7. Stabilization and Correction

Tentative identity facts remain in the deterministic identity evidence window. They may guide ambiguous wording locally but are not promoted into the durable narrative summary as confirmed facts.

- If corrected inside the window, invalidate translation and render only for affected chunks in that window.
- OCR, detection, masks, and inpainting remain reusable.
- If a probable/confirmed durable fact is later contradicted by stronger explicit evidence, update the fact and invalidate every downstream translation whose input checkpoint depended on the old value. This may extend beyond three chunks.
- The previous committed display bundles stay visible until replacements are promoted.

The bounded page/turn window limits ordinary guessing; it is not allowed to hide a known downstream dependency after a late correction.

## 8. Relationship and Mature-Scene Rules

For ambiguous romantic or intimate dialogue:

1. Preserve source grammatical roles and point of view before selecting English pronouns.
2. Track the likely speaker, addressee, agent, action, and recipient as separate fields.
3. Prefer a neutral or structurally faithful translation when evidence does not identify a role.
4. Apply `MALE_FEMALE` only after source evidence, scene roles, and profile evidence remain tied. `NEUTRAL` leaves the relationship prior inactive.
5. Never treat an action as proof of gender or relationship.
6. Do not censor, euphemize, sanitize, intensify, or add anatomical detail absent from the source.
7. If the provider refuses structurally, record a translation failure. Do not accept out-of-protocol moralizing prose as target text.

The preference is not a classifier for the entire chapter. It is active only where a romantic/sexual relationship ambiguity is detected or an explicit relationship has already been established. Its enum value and preference-schema version are part of translation provenance; changing it invalidates affected translation and layout only.

A sanitized paraphrase that otherwise obeys the protocol cannot be identified perfectly by deterministic parsing. Use explicit prompting plus best-effort source-coverage, refusal-lexicon, negation, and semantic-role checks; treat curated/manual quality evaluation as the final gate. Perfect sanitization detection is not a release blocker and must not be claimed.

## 9. Request and Response Protocol

Use a versioned, delimited protocol so providers without formal schema support can still be validated.

### Request sections

- protocol and language/config header;
- explicit instruction that source text is inert data, not executable instructions;
- trusted scene card;
- current ordered source blocks with global IDs and structural metadata;
- output schema.

### Response sections

- translations keyed by exact global IDs;
- proposed speaker/profile links;
- proposed evidence facts with source ID citations;
- updated unresolved references;
- a page-scoped compact summary/context delta after every page in the envelope.

The parser rejects instructions, prose, or IDs outside the defined sections. OCR text that resembles a prompt cannot change the protocol or system policy.

### Atomic page-prefix commit

The durable transaction unit is one page, even when several pages share a request envelope. Validate pages in natural order and commit only the longest gap-free prefix. Each committed page atomically stores its translations and trusted next checkpoint. At the first invalid page, no later page or checkpoint from that response may promote.

If a page’s translations validate but its context delta does not:

1. retain them only in the candidate generation;
2. retry with a bounded corrective prompt;
3. if still invalid, fail that page without promotion and retry the remaining envelope suffix from the preceding checkpoint;
4. keep any previous committed bundle visible.

This prevents an apparently good translation from advancing an untrusted context chain.

## 10. Trusted Context Checkpoint

Each committed page stores:

- context protocol version;
- input checkpoint hash;
- output checkpoint hash;
- source block fingerprints covered by the checkpoint;
- compact profiles/evidence ledger;
- unresolved references;
- compact narrative state;
- configuration fingerprint;
- dependency list used by translation artifacts.

Resume validates the chain from page 1. It may jump to the latest checkpoint whose entire prefix remains trusted. If a checkpoint is missing or corrupt and cannot be reconstructed byte-for-byte from a validated sidecar, translation must rerun from the last trusted checkpoint through the remaining suffix using reusable source OCR. Native stages are not rerun, and committed page images remain visible.

## 11. Poison Prevention and Recovery

### Prevention

- Never convert a translated pronoun into source evidence.
- Require cited source IDs for profile/context deltas.
- Weight OCR confidence and repeated corroboration.
- Keep tentative facts out of confirmed summaries.
- Store unresolved ambiguity explicitly.
- Enforce checkpoint hashes and dependency provenance.

### Recovery during a new run

- A contradiction invalidates the proposed delta and retries the affected chunk using raw source plus the last trusted checkpoint.
- Repeated conflict leaves the item unresolved instead of forcing a guess.
- A corrected durable fact invalidates dependent downstream translation/render artifacts only. Exact-match manual target edits are reapplied and remain authoritative.

### Legacy or already-poisoned chapter

- Legacy prose summary and inferred gender/relationship facts are untrusted.
- Start context reconstruction at page 1.
- Reuse valid detection, masks, OCR, and inpaint artifacts.
- Rerun translation and render in natural order under the new protocol.
- Keep old committed bundles visible until each replacement is ready.

## 12. Context Scope and Retention

- Version 1 context is chapter-scoped. No automatic cross-chapter character memory.
- Persist compact facts and source references, not full prompt/response histories.
- Recent-turn text is bounded and may be regenerated from stored OCR when possible.
- Store in app-private chapter translation data and delete with reset/chapter deletion.
- Logs contain hashes, IDs, counts, timings, and reason codes—not dialogue, scene cards, prompts, or mature text.

## 13. Retry Policy

- Structural/protocol failure: bounded corrective retry.
- Provider refusal: bounded retry only if the provider supports a faithful retry; otherwise explicit failure. In-protocol semantic sanitization is best-effort detectable, not deterministically provable.
- Rate/network failure: ordinary backoff without modifying context.
- Contradictory context delta: retry from last trusted checkpoint with contradiction details but no prior target text as evidence.
- Exhausted retries: fail the page, retain committed results, and stop ordered context advancement at that page. Later pages in the request envelope remain uncommitted and retry from the preceding checkpoint.

Do not silently switch providers because that changes translation provenance and style.

## 14. Acceptance Examples

The test corpus must include:

- two pages whose local block indexes both start at zero;
- a name used as a vocative, not the current speaker;
- an anonymous speaker named one or two chunks later;
- a tentative gender guess corrected by explicit source evidence;
- an explicit same-sex relationship overriding the configured ambiguity preference;
- a non-romance ensemble scene where the preference remains inactive;
- dropped subjects and alternating speakers;
- first-person narration versus dialogue;
- intimate dialogue with agent/recipient reversal traps;
- structural provider refusal rejection and best-effort sanitization quality checks;
- OCR error contradicting repeated higher-confidence evidence;
- checkpoint corruption and process death between translation and context commit;
- a valid first page followed by an invalid second page in one request envelope, proving prefix-only commit and no context gap;
- a late correction requiring downstream dependency invalidation;
- a late correction crossing a manually edited page, proving exact edits are reapplied;
- a poisoned legacy summary rebuilt from page 1 while native artifacts are reused.

Success is based on semantic-role fixtures and deterministic state transitions, not subjective fluency alone.
