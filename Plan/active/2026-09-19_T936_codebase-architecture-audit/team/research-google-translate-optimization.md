# Google Translate page-translation optimization research

Date: 2026-09-21  
Scope: TachiyomiAT page translation (6–30 short OCR blocks/page), Android 8+, bounded memory.  
Method: repository inspection, current Google Cloud documentation, current OSS source, and small live probes of the undocumented web endpoint.  

## Executive conclusion

The current 9 s/6-block result is dominated by six serial HTTP attempts plus the shared governor's 1,000 ms minimum spacing, not by JSON parsing.  
The reliable path to approximately 1–2 s/page is one documented Cloud Translation Basic (v2) POST containing the page's block strings as a `q` array; the response is an ordered translation array and the first 500,000 characters/month receive the current free credit.  
The no-key web endpoint has no published quota or batch contract: repeated `q` values did not produce an ordered list in live tests, while newline input sometimes merged and sometimes produced separate sentence entries.  
If a no-key mode is required, an HTML `<span data-id="bN">…</span>` envelope is the most promising one-request experiment, but it needs strict marker/count validation and a per-block fallback; it must not be treated as a supported API.  
Do not add 2–4-way free-endpoint parallelism as the primary fix: community evidence shows IP/CAPTCHA blocking after burst/bulk use, and there is no defensible “safe” rate to promise.

## A. What our code does today

### Call path and request shape

* `app/src/main/java/eu/kanade/translation/translator/providers/GoogleTranslator.kt:25-32` constructs a `GoogleTranslator` with a fresh `OkHttpClient` for that translator instance. `StandardTranslatorKind.kt:26-29` constructs it for the Google standard engine.
* `GoogleTranslator.kt:34-43` iterates every page and every block in map order. The call is logically serial: each block invokes the suspending `translateText(...)` before the next block is assigned. Blank blocks return early inside `translateText`.
* `GoogleTranslator.kt:45-76` builds one GET `Request` per nonblank block, then charges the shared `ProviderRequestGovernor` around the actual `OkHttp` call. `ProviderRequestMetadata` uses backend `google`, an estimated token count, operation `translate`, and a text hash envelope id.
* `GoogleTranslator.kt:100-110` uses `https://translate.google.com/translate_a/single` with `client=gtx`, explicit `sl`/`tl`, eleven repeated `dt` parameters (`at`, `bd`, `ex`, `ld`, `md`, `qca`, `rw`, `rm`, `ss`, `t`), `otf`, `ssel`, `tsel`, `kc`, a locally calculated `tk`, and one URL-encoded `q`.
* `GoogleTranslator.kt:85-87` parses only `JSONArray(body)[0][0][0]`. That is correct for the current one-block request but would discard all later top-level translation entries in a multi-sentence response.
* `GoogleTranslator.kt:56-75` uses `withTranslationRetry`; non-2xx responses are classified, including `Retry-After` when supplied. `GoogleTranslator.kt:88-96` logs and returns an empty string for invalid JSON, so a 200 HTML/challenge page is not a typed success.
* `ProviderRequestGovernor.kt:68-78` defaults to 60 requests/minute, 60,000 estimated tokens/minute, 1,000 ms minimum spacing, and `maxInFlight=1`. Therefore the current implementation intentionally cannot overlap Google requests. `GoogleTranslator.kt:169-172` evicts and shuts down the client when the translator closes; while alive, its client can reuse connections.

The observed 9 s for six blocks is consistent with serial request latency plus approximately five inter-request spacing intervals. Native batching removes both the per-block connection/request overhead and almost all governor pacing cost.

## B. Findings by research question

### 1. Free web endpoint: multi-segment behavior and boundary preservation

#### Contract status

`translate.googleapis.com/translate_a/single` and the corresponding `translate.google.*` paths are web/AJAX endpoints, not the documented Cloud Translation API. A Google Developer forum answer explicitly says the endpoint has no public limits/documentation and that Google discourages relying on such workarounds: [Google Developer forum discussion](https://discuss.google.dev/t/translate-googleapis-com-translate-a/126639). Treat every behavior below as observed, version-dependent behavior, not an API guarantee.

#### Repeated `q` parameters are not a reliable batch API

Live probe on 2026-09-21:

`https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=es&dt=t&q=Hello&q=World`

returned one translation entry for `Hello` (`Hola`), not two ordered entries. This is consistent with ordinary query-string parsers keeping the first value; it is not the documented v2 behavior. Do not implement free-endpoint batching as repeated `q` parameters unless the exact parser and response are continuously verified.

#### Newline joining is useful context, but not a stable segment protocol

Two live probes demonstrate both failure modes:

* `https://translate.google.co.uk/translate_a/single?client=gtx&sl=en&tl=es&dt=t&q=Good%20morning%0AHow%20are%20you%3F` returned two top-level sentence entries. The first translated string had a trailing newline and the second entry contained the second translation.
* `https://translate.google.co.in/translate_a/single?client=gtx&sl=en&tl=es&dt=t&q=One%0ATwo%0AThree` returned one translated string (`Uno, dos, tres`), merging all three short fragments.

Newline is therefore punctuation/context, not an opaque record separator. Depending on language and content, Google may merge lines, preserve a newline, emit multiple sentence arrays, or change whitespace. A parser that reads only `[0][0][0]` loses later entries. If newline batching is tested, parse every entry under response `[0]`, preserve empty inputs explicitly, and reject any response whose reconstructed block count cannot be proven.

#### HTML tags are the strongest observed boundary carrier, but still unofficial here

The free endpoint currently treats simple inline tags as protected markup in live tests. For example:

`https://translate.google.co.uk/translate_a/single?client=gtx&sl=en&tl=es&dt=t&q=%3Cspan%20data-id%3D%22b0%22%3EGood%20morning%3C%2Fspan%3E%3Cspan%20data-id%3D%22b1%22%3EHow%20are%20you%3F%3C%2Fspan%3E`

returned two `<span data-id="…">` elements, with the inner text translated and the attributes/order retained. A similar `<pre>` probe retained `<pre>` elements: [live `<pre>` probe](https://translate.google.co.in/translate_a/single?client=gtx&sl=en&tl=es&dt=t&q=%3Cpre%3EGood%20morning%3C%2Fpre%3E%3Cpre%3EHow%20are%20you%3F%3C%2Fpre%3E). This does not establish a guarantee for every language pair, tag, or future endpoint revision.

The documented Cloud API gives useful corroborating markup semantics: it does not translate HTML tags and retains the tags around translated text; it warns that non-HTML markup is undefined ([Cloud Translation text guide](https://docs.cloud.google.com/translate/docs/translate-text), [markup guidance](https://docs.cloud.google.com/translate/markup)). The GDELT project reports the practical distinction observed while preserving subtitle line ids: inline `<span>`/short `<s>` tags do not affect translation, while breaking tags such as `<p>` act like punctuation and can split context; it wraps each source line in an inline tag and uses block tags only for sharding ([GDELT HTML experiment](https://blog.gdeltproject.org/using-google-translates-html-support-to-create-high-resolution-translated-google-speech-to-text-srt-captioning/)).

For TachiyomiAT, if the free path is retained, use escaped, short inline spans with numeric IDs, for example:

```html
<span data-id="b0">source block 0</span><span data-id="b1">source block 1</span>
```

Escape OCR `<`, `>`, and `&` before embedding. Parse the returned HTML, require exactly one matching span for each id, reject duplicate/missing/reordered ids, and fall back to individual requests for the failed envelope. Do not put human-readable sentinel words in the text: markers can be translated, deleted, or reordered. Do not use `<p>`/`<br>` as “hard” record delimiters unless the desired behavior is a sentence break.

#### `dt` and response parsing

`dt=t` is the minimal translation result mode used by common clients and the live probes. The current code asks for many `dt` branches but consumes only the first translation string. On the free endpoint this is undocumented, but reducing to `dt=t` should reduce response work/bytes and makes parsing less exposed to unrelated response-shape changes. It is a small optimization, not the main latency fix.

The `client=gtx` variant is widely used by unofficial clients. Current OSS code documents `translate.googleapis.com` as the preferred non-webapp service URL and uses `gtx` for a fallback path ([manga-image-translator Google translator](https://raw.githubusercontent.com/zyddnys/manga-image-translator/main/manga_translator/translators/google.py), lines 51-54 and 118-127). It is not a supported Cloud API contract, and token requirements differ by host/client mode. The existing token calculator must not be assumed valid forever.

### 2. Free endpoint quotas, bursts, 429s, and parallelism

There is no published per-IP quota, burst ceiling, or supported request rate for `translate_a/single`. The absence of a quota table is itself the important result; a number such as “N requests/second is safe” would be invented.

Evidence of risk:

* The `py-googletrans` documentation labels the library as web-AJAX scraping, caps a single text at 15,000 characters, says the web version is not guaranteed to work, recommends the official API for stability, and warns that 5xx/errors can mean Google has banned the client IP ([project documentation](https://github.com/ssut/py-googletrans/blob/main/docs/index.rst), lines 193-208).
* Its bulk-request issue reports an automated-traffic/CAPTCHA block after roughly 40–50 different translations. The issue explicitly says the threshold is uncertain and that the block expires after traffic stops ([issue #76](https://github.com/ssut/py-googletrans/issues/76)). This is evidence of an anti-abuse detector, not a quota guarantee.
* A community answer about 429s correctly distinguishes this unofficial web endpoint from the stable Cloud API and says unofficial clients are eventually blocked ([Stack Overflow answer](https://stackoverflow.com/questions/66370556/429-errors-returned-from-google-translate-when-using-googletrans-library)).

Operational recommendation for free mode:

* Keep one shared pacing/cooldown bucket per endpoint/IP. Honor `Retry-After` if present; otherwise use exponential backoff with jitter and stop/requeue after a small finite retry budget. Do not retry an HTML CAPTCHA body as if it were a JSON translation.
* A single envelope request per page is lower-risk than 6–30 requests/page. It is not evidence that 2–4 concurrent envelopes are safe: concurrency increases burstiness and has no published allowance. Parallel small batches might reduce a single page's wall time on a friendly IP, but they raise ban/CAPTCHA risk and do not solve 30-block pages as reliably as one native Cloud batch.
* The current 1 request/s, one-in-flight governor is conservative and explains the measured latency. Relaxing it for the free endpoint should be an opt-in, measured experiment with circuit breaking, not the default.

### 3. Cloud Translation API: documented batch paths, quotas, pricing, and Android REST

#### Cloud Translation Basic v2 is the right synchronous batch primitive

The v2 `translate` method is a single `POST https://translation.googleapis.com/language/translate/v2`. Its `q` parameter accepts an array of strings, with a maximum of 128 strings; `format` can be `text` or `html`; and the `translations[]` response contains one result per supplied query ([v2 REST reference](https://docs.cloud.google.com/translate/docs/reference/rest/v2/translate), lines 99-169). The API's response list is explicitly per `q`, so block-to-result order is a documented contract. For OCR blocks, send `format=text` unless the source deliberately contains HTML.

The Basic API supports API keys. Google documents the `key` query parameter and says all Basic (v2) methods such as `translate` and `detect` support API keys, whereas Advanced (v3) does not ([authentication guide](https://docs.cloud.google.com/translate/docs/authentication)). Android can therefore call v2 directly with a small OkHttp JSON POST without shipping the heavy Google client library. Prefer the `x-goog-api-key` header where supported; the documentation warns that putting a key in a URL exposes it to URL scans ([API-key guidance](https://docs.cloud.google.com/docs/authentication/api-keys-use)). An APK-embedded key is extractable, so it needs API restriction/quotas and should be treated as user/project configuration, not a secret.

#### Current free credit and price

The current pricing table gives the first 500,000 characters/month as a free `$10` monthly credit collectively across Cloud Translation Basic and Advanced. Above that, NMT text translation is `$20 per 1,000,000 characters` for the standard tier ([Cloud Translation pricing](https://cloud.google.com/products/translate/pricing), lines 110-117). Billing/project setup is still required; “free tier” does not mean an unmetered anonymous endpoint. Google counts Unicode code points/characters, including whitespace and markup, so envelope tags add billable characters ([pricing, charged characters](https://cloud.google.com/products/translate/pricing), lines 133-140).

For scale intuition, a page with 30 blocks averaging 50 source characters is about 1,500 billed characters before whitespace/markup. The 500,000-character credit would cover roughly 333 such pages/month, subject to the actual corpus and any other Cloud Translation use.

#### Quotas and request sizing

The current documented quotas are far above this page workload: general-model content quota is 6,000,000 characters/project/minute (v2 and v3), v2 requests are 300,000/project/minute, and v3 requests are 6,000/project/minute ([quotas and limits](https://docs.cloud.google.com/translate/quotas), lines 54-93). The API recommends requests of about 5,000 characters for latency; Cloud Basic has a 100,000-byte maximum request size, while Advanced has a 30,000-codepoint maximum per request ([quotas, content limits](https://docs.cloud.google.com/translate/quotas), lines 68-71). A normal 6–30-block manga page is comfortably below these limits.

#### v3 synchronous and `batchTranslateText`

v3 synchronous `translateText` also accepts a `contents[]` array and its response `translations[]` has the same length as `contents` ([v3 REST method](https://docs.cloud.google.com/translate/docs/reference/rest/v3/projects.locations.translateText), [response contract](https://docs.cloud.google.com/translate/docs/reference/rest/v3/TranslateTextResponse)). It requires OAuth/IAM and a project/location parent, not an API key; it is viable for a backend or a user-authenticated flow but is less suitable than v2 for a simple Android API-key setting.

v3 `batchTranslateText` is a different product: it is asynchronous, returns a long-running Operation, reads input from Cloud Storage, and writes output to Cloud Storage. The documented limits include at most 100 files per batch and at most 100M Unicode code points ([batch method reference](https://docs.cloud.google.com/translate/docs/reference/rest/v3/projects.locations.batchTranslateText), [batch guide](https://docs.cloud.google.com/translate/docs/advanced/batch-translation)). It is appropriate for chapter/offline jobs, not a reader's 1–2 s page path; the GCS setup and operation polling would dominate.

### 4. Speed mechanics applicable to both paths

#### HTTP/2 and connection reuse

OkHttp supports HTTP/2 socket sharing, connection pooling, and transparent GZIP ([OkHttp README](https://github.com/square/okhttp/blob/master/README.md), lines 193-200). TachiyomiAT already keeps one `OkHttpClient` for the lifetime of a `GoogleTranslator`, so subsequent calls can reuse a connection to the same host. However, the governor's `maxInFlight=1` and serial block loop prevent HTTP/2 multiplexing from helping the current page. Native one-call batching is the safer way to exploit connection reuse without opening a burst of free requests. If concurrency is later tested against Cloud, use a shared client and a bounded dispatcher; do not create a new client per block.

#### Compression

For these short blocks, TLS handshake/request headers and server processing dominate more than body bytes. OkHttp's transparent GZIP handles compressed responses when the server offers them; GET query parameters themselves are not compressed. A Cloud v2 JSON POST can be gzip-compressed if a future page envelope becomes large, but a normal page under 5K characters will not materially benefit. There is no documented reason to force Brotli (`br`) for the free endpoint; avoid adding a codec dependency solely for this path.

#### Host choice and regional endpoints

Do not assume `translate.google.com`, `translate.googleapis.com`, or a country TLD is a regional quota bucket. The free path has no published routing/region contract, and randomizing TLDs can lose connection reuse while leaving anti-abuse decisions IP/fingerprint dependent. Benchmark one stable host per mode. For the documented Cloud Advanced API, Google does publish global and US/EU multi-regional endpoints for residency (`translate.googleapis.com`, `translate-us.googleapis.com`, `translate-eu.googleapis.com`), but that is a data-location feature rather than a promise of lower reader latency ([Cloud endpoint guide](https://docs.cloud.google.com/translate/docs/advanced/endpoints)).

#### `client=gtx`, `dt`, and size sweet spots

`client=gtx` plus `dt=t` is the smallest commonly observed free request shape. Switching the existing `.com` URL to `translate.googleapis.com` and dropping the token is a candidate experiment, not a supported migration. The practical unofficial-client limit is commonly documented as 15K characters for one text by `py-googletrans`; the official Cloud sizing target is much clearer: about 5K characters/request for latency, 100K bytes maximum for v2, and 128 v2 strings. For TachiyomiAT, one Cloud v2 request per page is the default; free envelopes should be capped conservatively (for example, <=5K source characters including marker overhead) and split/fallback when the page is larger.

### 5. Comparable OSS projects and their protocols

#### `manga-image-translator`

The current `GoogleTranslator` groups queries by a detected language, joins each group with `"\\n"`, sends one request per language, then splits the returned text on newline and assigns results positionally ([Google translator source](https://raw.githubusercontent.com/zyddnys/manga-image-translator/main/manga_translator/translators/google.py), lines 130-152). Its default path uses a newer RPC-style web call; its fallback uses `gtx`. The client is `httpx.AsyncClient(http2=True)` and is reused, with three one-second retries (lines 101-127, 175-183). The common translator pads/truncates a response to the requested list length, preserves non-valuable text, and returns empty strings when the server “translated incorrectly” ([common translator source](https://raw.githubusercontent.com/zyddnys/manga-image-translator/main/manga_translator/translators/common.py), lines 152-210).

This is a useful latency pattern—one request per language group, HTTP/2, connection reuse—but its newline protocol has exactly the free-endpoint ambiguity demonstrated above. It does not prove record preservation; TachiyomiAT should add stronger ID/count validation before adopting it.

#### `py-googletrans` and related wrappers

`py-googletrans` exposes list/bulk translation, custom service URLs, connection pooling, and HTTP/2, but its own docs explicitly call out the 15K single-text ceiling and instability/IP bans ([documentation](https://github.com/ssut/py-googletrans/blob/main/docs/index.rst), lines 193-208 and 231-249). This is evidence that session reuse helps speed, not evidence that the web endpoint has a supported batch contract. Other wrappers commonly loop list items or join on newline; neither is equivalent to the Cloud v2 `q` array.

#### Marker reconstruction pattern

The GDELT subtitle experiment is the strongest published practical analogue: short inline tags carrying IDs, block tags only for sharding, then parse the translated HTML and reconstruct the original line order ([GDELT experiment](https://blog.gdeltproject.org/using-google-translates-html-support-to-create-high-resolution-translated-google-speech-to-text-srt-captioning/), lines 88-128). It also calls out that tags count toward input length and that breaking tags affect sentence segmentation. This maps directly to a possible free-endpoint fallback, with stricter validation for manga blocks.

## C. Ranked options for TachiyomiAT

| Rank | Option | Expected page latency (6–30 short blocks) | Reliability / cost risk | Recommendation |
|---|---|---:|---|---|
| 1 | Cloud Translation Basic v2: one JSON POST with `q: [block0, …]`, `source`, `target`, `format=text`, API key, parse ordered `translations[]` | One network round trip; approximately 0.3–1.5 s on a warm mobile connection, so 1–2 s is plausible | Low protocol risk; first 500K chars/month free credit, then $20/M chars; requires project/billing/key handling | **Primary path.** Add strict response-count validation and per-block fallback/retry. Keep the existing shared client/governor, charging one request with total input cost. |
| 2 | Free endpoint HTML envelope with `<span data-id="bN">…</span>`, `client=gtx`, `dt=t`, one request/page, strict ID reconstruction | Similar one-round-trip latency; 1–2 s is plausible when not challenged | High: undocumented behavior, IP/CAPTCHA/429 risk, markup can change; tags add length | **Optional no-key mode only.** Use a small corpus/canary, circuit breaker, and individual-request fallback. Never trust positional/newline parsing alone. |
| 3 | Free endpoint newline envelope, parse every response segment | Similar one-round-trip latency | High: lines can merge, split, or be reordered/whitespace-normalized; current parser would lose all but first | Use only as a measured experiment if HTML markers are rejected; do not make it the default. |
| 4 | 2–4 concurrent per-block/free requests over HTTP/2, bounded by a relaxed governor | Could reduce six blocks to roughly 1–3 s on a friendly network; 30 blocks still takes multiple waves | High anti-abuse/ban risk and no documented safe burst rate; requires changing `maxInFlight`/spacing | **Do not use as the primary optimization.** It is a last-resort opt-in for Cloud or a controlled benchmark, not a free default. |
| 5 | v3 synchronous `translateText` with `contents[]` and OAuth | One round trip, similar to v2 | Correct protocol but more auth/project complexity; API key unsupported | Suitable for a backend/user-authenticated configuration, not the simplest Android path. |
| 6 | v3 `batchTranslateText` | Not page-latency-bound; asynchronous operation plus GCS | Correct for offline chapters, wrong shape for reader interaction | Do not use for single-page translation. |

### Recommended combination to hit 1–2 s/page

1. Implement Cloud v2 native batching as the production Google provider: one request per page, 6–30 `q` strings (up to 128), explicit source language already available in this code, target language, `format=text`, and one ordered response validation.
2. Preserve the existing shared request governor/retry envelope, but charge one actual request per page rather than one request per block. Set the request's estimated input cost to the sum of blocks and retain `Retry-After`/exponential backoff.
3. Keep a separate no-key free mode only if the product requirement demands it. Prefer the span-ID envelope, fixed `translate.googleapis.com` host, `dt=t`, one in-flight page request, conservative <=5K-character chunks, strict ID/count/HTML validation, and fallback to the existing per-block path after one failed envelope.
4. Do not combine free batching with 2–4-way concurrency by default. It offers less predictable latency than native v2 batching and increases the likelihood of IP throttling/CAPTCHA.
5. Benchmark warm and cold connections separately on representative 6-, 15-, and 30-block pages. Record total characters, request count, DNS/TLS time, HTTP protocol, response count, marker failures, 429/403/HTML-challenge rate, and p50/p95 latency before changing defaults.

No production code was modified for this research.
