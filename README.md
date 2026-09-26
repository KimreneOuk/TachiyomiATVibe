# TachiyomiATVibe

An Android manga reader with a chapter-translation pipeline: on-device text detection and OCR, translation through on-device or configured providers, inpainting, and rendered text overlays. TachiyomiATVibe is a community fork in the TachiyomiAT/Mihon lineage.

This independent fork is not affiliated with or endorsed by those projects or their maintainers.

Most of the fork-specific development (translation pipeline, tests, tooling) was produced with AI coding assistants under human direction and review.

## Features

- Browse manga sources through extensions, organize your library with categories, download chapters, and sync reading progress with trackers.
- Translate a single page manually, translate pages automatically while reading, or queue a chapter for batch translation.
- Choose from standard translation engines, including on-device ML Kit, Google Translate, and DeepL, or AI providers including Gemini, OpenRouter, DeepSeek, and LM Studio. DeepL, Gemini, OpenRouter, and DeepSeek need API keys you provide. LM Studio uses a base URL and model; the app does not ask for an API key for ML Kit or Google Translate.
- Run text detection and OCR, translate recognized text, clean the original text from the page with inpainting, and render translated text over the image.
- Read long webtoon pages with cross-page seam stitching for text regions that continue across page boundaries.
- Choose the font used for translated text.

## Requirements and getting the app

Android 8.0 (API 26) or newer is required. There are no prebuilt public releases at this time, so build the app from source using the instructions below. The main-branch [CI workflow](https://github.com/KimreneOuk/TachiyomiATVibe/actions/workflows/build_push.yml) uploads an unsigned arm64 APK as a run artifact; open a successful main-branch run and look under **Artifacts**.

## Translation quickstart

1. Open **Settings → Translations**.
2. Set **Translate From** and **Translate To**, then choose a **Translator type**. Select a standard engine or an AI provider and enter any required credentials or connection details.
3. Open a chapter. Use the page's **Translate** action for a single page, the reader's translation controls for automatic translation while reading, or the chapter translation action for batch translation.

The ONNX model assets are fetched and converted before the app is built, then packaged into the APK. The app copies these packaged assets into its private storage when needed; it does not fetch those assets on the first translation. ML Kit may separately download its language model the first time a language is used.

## Building from source

You need Python 3, JDK 17, and the Android SDK. Android Studio can install the SDK components required by the project. The model fetch step downloads and verifies upstream models and locally converts derived models before Gradle packages the app. See [MODEL_SOURCES.md](docs/MODEL_SOURCES.md) for model sources, conversion notes, and license details.

```sh
git clone https://github.com/KimreneOuk/TachiyomiATVibe.git
cd TachiyomiATVibe
python3 -m pip install -r scripts/converters/requirements.txt
python3 scripts/fetch_models.py
./gradlew :app:assembleStandardDebug
```

On Windows PowerShell, run `py -3 -m pip install -r scripts/converters/requirements.txt`, `py -3 scripts/fetch_models.py`, then `.\gradlew.bat :app:assembleStandardDebug`. The Standard debug APKs are written under `app/build/outputs/apk/standard/debug/`.

## Development

Run the JVM unit tests with:

```sh
./gradlew test
```

A small number of integration tests in the batch-translation coexistence suite — plus one screen-model fixture whose boot await can starve on 2-core CI runners — are load-ordering sensitive under full-suite JVM churn and are temporarily tagged `quarantined-flaky` and excluded from CI. They remain part of the tree and can be run explicitly with:

```
./gradlew :app:testDevReleaseUnitTest -PincludeQuarantinedTests
```

Stabilizing these tests and removing the tag is tracked work. The CI build uses the Standard release variant. Automated release creation remains disabled for this fork until its maintainers configure a release policy and signing credentials.

## Contributing

Please read [CONTRIBUTING.md](CONTRIBUTING.md) and the [Code of Conduct](CODE_OF_CONDUCT.md) before opening an issue or pull request. Contributors working on translation behavior can also start with the [translation architecture guide](docs/translation-architecture.md).

## Credits and lineage

Project lineage: TachiyomiATVibe ← [TachiyomiAT](https://github.com/mannu691/TachiyomiAT) ← [Mihon](https://github.com/mihonapp/mihon) ← [Tachiyomi (inazuma110)](https://github.com/inazuma110/tachiyomi). Credits include Javier Tomás, the Mihon team, and contributors to the Tachiyomi project. [NOTICE](NOTICE) is the authoritative file for project attribution and notices.

## Licensing

The application code is licensed under the [Apache License 2.0](LICENSE). Except where otherwise noted, code is under Apache-2.0; see [NOTICE](NOTICE), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), and [docs/MODEL_SOURCES.md](docs/MODEL_SOURCES.md) for project attribution, third-party notices, and model terms. Model weights are not covered by this project's Apache-2.0 license; their terms are those of their individual upstream sources, subject to the open questions in the model sources page, including one AGPL-3.0 model.

The bundled fonts are described as free for personal use, but redistribution permission for these copies has not been verified. A maintainer decision is pending; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Disclaimer

The developers are not affiliated with content providers. The application does not include or host reading content; users are responsible for the sources and content they choose to use.
