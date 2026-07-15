# External Brainstorming: Translation Quality & Token Usage Reduction

This document provides the necessary context for external brainstorming regarding improvements to translation quality and strategies for reducing token usage in the AI translation pipeline.

## 1. Current State of Translation

The app currently uses a block-by-block text translation pipeline powered by LLMs (Gemini, OpenRouter, DeepSeek, LM Studio). The workflow is as follows:

- **Input Extraction:** The OCR engine extracts text blocks from the image. It uses bounding boxes to detect if a text block is inside a speech bubble or not.
- **Request Format (JSON):** Pages and their respective text blocks are grouped and sent as a JSON object to the LLM. 
  Example:
  ```json
  {
    "page_1": ["[SPEECH] 行く。", "三日後"]
  }
  ```
  *(Note: The `[SPEECH]` tag is an internal marker injected to denote text inside a speech bubble.)*
- **System Prompts:** 
  - The model uses a highly specific `jsonSystemPrompt` (from `TranslationPrompts.kt`).
  - It contains rules for: Point of View (First-person vs. Third-person depending on the `[SPEECH]` tag), handling of pro-drop languages (Japanese, Korean, Chinese), deictics, and reading order (RTL vs LTR).
  - It expects the model to return a JSON object with the exact same keys and array lengths containing *only* the translated strings.
- **Context Injection:** The prompt can optionally include:
  - **Glossary:** Established terms for consistency.
  - **Rolling Context:** Recent translated pairs to maintain pronoun, name, and speaker continuity.

## 2. Token Usage Constraints & Current Issues

Currently, users configure a `maxOutputTokens` setting (default is `8192`). However, token usage scales with chapter size, and API costs or local inference delays can add up quickly. 

### What consumes tokens?
1. **System Prompt Overhead:** The system prompt is detailed and verbose (approx. 400-500 words), and is sent with every single request.
2. **JSON Overhead:** Sending the source text and receiving the output in a mapped JSON array structure `{"page_k": ["text1", "text2"]}` includes quotes, brackets, and keys that consume tokens, especially for pages with many small text blocks (e.g., sound effects).
3. **Rolling Context:** Supplying past translations to maintain continuity adds a significant token tax on subsequent pages.

## 3. Translation Quality Constraints & Current Issues

1. **Context Limits:** Without `rollingContext`, the LLM has no idea who is speaking, leading to gender or pronoun hallucination. With `rollingContext`, the model sometimes gets confused by too much history.
2. **JSON Brittleness:** If the LLM generates 5 translations for 6 source blocks (array length mismatch), the pipeline detects it (`expected != actual`) but falls back to partial translation because it cannot safely align the indices.
3. **No Chain-of-Thought (CoT):** The model is strictly instructed to *"Return ONLY a JSON object... (no explanations)."* By forbidding reasoning/CoT, we artificially limit the LLM's translation quality, especially for highly contextual manga dialogue.

## 4. Brainstorming Objectives

We need to discuss and find solutions for the following:

### A. Reducing Token Usage
- **Format Alternatives:** Should we move away from JSON to a denser format like line-separated text (`[1] translated text\n[2] translated text`) to save tokens on brackets and quotes? 
- **Prompt Compression:** Can we condense the pro-drop, POV, and speech bubble rules without degrading the translation?
- **Context Pruning:** How can we efficiently provide character/speaker continuity without dumping the entire `rollingContext`? Should we use an LLM summarization step or key-value memory for names/genders?

### B. Improving Translation Quality
- **Enabling CoT (Chain of Thought):** How can we allow the LLM to output a reasoning block *before* the JSON/final output, to improve nuance, without blowing up the `maxOutputTokens` or breaking the JSON parser?
- **Better Alignment:** How do we make the LLM robust against missing array elements (so we don't discard valid translations when it skips a sound effect)?
- **Prompt Engineering:** Are there more effective ways to nudge the model about speaker intent (e.g. `[SPEECH]` vs. `[THOUGHT]` vs. `[NARRATION]`)?

---
*Please use the context above to generate ideas, architectural changes, or prompt optimizations.*
🏗️ Core Architecture Shifts
Kill the JSON: We are completely moving to ID-Mapped Plain Text (ID|Text). This saves ~20-30% on output tokens by removing brackets, quotes, and keys.

Drop the Guesses: We are removing the forced [SPEECH] / [NARRATION] tags. The OCR can't reliably link them anyway, so we let the LLM do a fast literal pass first.

The 2-Pass Workflow: We split the workload. A cheap/fast pass does the heavy lifting, and a smart pass only fixes the broken pieces.

⚙️ How Your Modes Will Work
Manual Mode: Runs Pass 1 only. Instant translation, literal output.

Auto Mode (Prefetch): Runs Pass 1 only. Fast enough to stay ahead of the reader while they scroll.

Pre-Translation (Batch): Runs Pass 1 on everything, then groups the text into scene chunks and runs Pass 2 to fix context, pronouns, and names.

🚜 Pass 1: The Fast Draft (Manual / Auto / Batch)
This is your frontline worker. It translates literally and flags anything it isn't 100% sure about (pro-drop, missing subjects, weird names).

Input Format: [ID]|[Source Text]

System Prompt:

Plaintext
You are a fast manga and manhwa translator. Your job is to translate CJK source text into literal English. 

RULES:
- Translate the text accurately and directly.
- Asian languages frequently drop subjects (pro-drop). If you have to guess a missing pronoun, subject, or unclear name, append the tag [FLAG] to your output.
- If the line is straightforward, append the tag [OK].
- You MUST maintain the exact ID provided.
- DO NOT use JSON, markdown, or add any reasoning.

OUTPUT FORMAT:
ID|Translated Text|[STATUS]
Output Example:
P1_B1|He died.|[FLAG] >> (App code sees the pipe | and parses it perfectly)

🦅 Pass 2: The Eagle Eye Patch (Batch Mode Only)
This is your smart editor (e.g., Gemini Pro). It reads a chunk of English text from Pass 1, looks at your Glossary, and ONLY rewrites the flagged lines based on context.

Input Format: A block of ~3-5 pages of Pass 1 output (both [OK] and [FLAG] lines) + Character Glossary.

System Prompt:

Plaintext
You are an expert manga/manhwa localization editor fixing a rough machine translation.

RULES:
- Read the provided block of English dialogue containing [OK] and [FLAG] lines. 
- The [FLAG] lines contain literal translations where the original language dropped the subject or pronoun.
- Use the surrounding [OK] lines and the Character Glossary to deduce who is actually speaking.
- Rewrite ONLY the [FLAG] lines so they flow naturally and have the correct pronouns/names.
- DO NOT output the [OK] lines. DO NOT add explanations.

GLOSSARY: 
{Insert Character Dictionary here}

OUTPUT FORMAT:
ID|Corrected Text
Output Example:
P1_B1|The boss died. >> (App code overwrites the Pass 1 draft with this final version)

💻 App Implementation Flow
OCR extracts bounding boxes and assigns IDs (e.g., Page1_Box1).

App sends source text to Pass 1.

App receives ID|Text|[STATUS]. If in Manual/Auto mode, immediately render the Text to the UI.

If in Batch mode, collect 3-5 pages of Pass 1 outputs.

Scan for [FLAG]. If found, send the chunk to Pass 2.

Overwrite the [FLAG] strings in your database with the Pass 2 output.

Here are 22 edge-case questions broken down by where they happen in your architecture ~

🔍 OCR & Input Data Edge Cases
What happens if a single sentence is split across three different bounding boxes/IDs, causing Pass 1 to translate each fragment as a standalone, weird sentence?

If the OCR misreads vertical formatting artifacts or furigana as actual words, how will the LLM react to the garbage text?

What if the OCR completely hallucinates and merges two separate speech bubbles from different characters into one single ID?

If a page has zero text detected, does the app still send an empty array and waste an API call, or bypass it?

🚜 Pass 1 (The Bulldozer) Edge Cases
What if the Pass 1 model completely ignores the | delimiter and decides to use colons (:), arrows (->), or markdown tables instead?

How do you handle it if Pass 1 translates the text perfectly but entirely forgets to append the [OK] or [FLAG] status tag?

What if Pass 1 gets "lazy" and skips IDs because the page is flooded with 50+ tiny sound effect boxes?

If the translated dialogue naturally contains the literal string "[FLAG]" or "[OK]" (e.g., "Raise the red [FLAG]!"), will your regex parser break?

How does Pass 1 handle stutters (e.g., "W-W-What?!") or screaming text — will it drop them or hallucinate weird formatting?

What happens if a cheap local model gets cowardly and starts flagging 90% of the lines, destroying your API budget for Pass 2?

🦅 Pass 2 (The Eagle Eye) Edge Cases
What if Pass 2 realizes a Pass 1 [OK] line is completely wrong based on context, but it's only allowed to patch [FLAG] lines?

If two characters are talking over each other in the same panel, will Pass 2 blend their personalities together in the translation?

What if the Character Dictionary directly conflicts with the plot (e.g., a male character is temporarily disguised as a female), will Pass 2 force the dictionary rules and break the disguise?

How does Pass 2 handle a [FLAG] line that references a plot point from 15 pages ago, if it's only receiving a 3-page context chunk?

If using Tool Calling for Pass 2, what happens if the returned JSON payload exceeds the API's max output token limit on a really text-heavy chapter?

Will Pass 2 accidentally overwrite sound effects ([SFX]) with dialogue if it misinterprets the context chunk?

⚡ Concurrency & UI State Edge Cases
If the user rapidly scrolls past Page 3 before the Pass 2 API call finishes, do we cancel the request to save money, or let it finish and save silently to the database?

What happens if the user manually edits a text box in the app, but Pass 2 is currently processing that exact ID in the background?

If the app goes offline or loses connection mid-batch, how does the Pre-translation resume without duplicating costs and starting over?

What if Auto Mode prefetches Page 5 based on a bad Pass 1 draft of Page 4, and then Pass 2 fixes Page 4 after Page 5 is already rendered?

What happens if the user triggers a mass "Clear Cache" while background batch translations are actively writing to the database?

If rate limits (HTTP 429) hit exactly when Pass 2 is firing, does the UI show an error, or silently fall back to the Pass 1 draft?

Which of these categories (OCR, Models, or UI State) are you most worried about handling first in your Kotlin backend?