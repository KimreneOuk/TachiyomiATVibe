# TachiyomiATVibe

An Android manga reader with a chapter-translation pipeline: on-device text detection and OCR, translation through on-device or configured providers, inpainting, and rendered text overlays. TachiyomiATVibe is a community fork in the TachiyomiAT/Mihon lineage.

This independent fork is not affiliated with or endorsed by those projects or their maintainers.

Most of the fork-specific development (translation pipeline, tests, tooling) was produced with AI coding assistants under human direction and review.

## Project status

- **Development:** An active community fork; the translation subsystem is still evolving and is not presented as a stable or production release.
- **Public releases:** No [GitHub Releases](https://github.com/KimreneOuk/TachiyomiATVibe/releases) are published. The [main CI workflow](https://github.com/KimreneOuk/TachiyomiATVibe/actions/workflows/build_push.yml) builds and uploads unsigned arm64 APK artifacts to successful workflow runs; these are per-run artifacts, not releases.
- **Translation maturity:** Manual page, automatic reader, and chapter batch workflows are implemented, but their behavior continues to evolve.
- **Test stability:** Tests tagged `quarantined-flaky` are excluded from default Gradle test runs; `-PincludeQuarantinedTests` opts them in.
- **Third-party binaries:** Model assets are fetched or converted before a build and packaged in APKs, but are not tracked in Git. Their licenses are individual upstream terms, including an AGPL-3.0 panel-detector derivative; Manga109 dataset terms and bundled-font redistribution permission remain unresolved. See [model sources](docs/MODEL_SOURCES.md) and [third-party notices](THIRD_PARTY_NOTICES.md).

## Repository map

- `app/` is the Android application; the translation subsystem is under `app/src/main/java/eu/kanade/translation/`.
- Shared Gradle modules include `core/`, `core-metadata/`, `data/`, `domain/`, `repo/`, `source-api/`, and `source-local/`.
- `presentation-core/`, `presentation-widget/`, `i18n/`, and `i18n-at/` hold shared presentation and localization modules.
- `docs/` and `scripts/` contain developer guides and model-fetch/conversion tooling.

## Features

- Browse manga sources through extensions, organize your library with categories, download chapters, and sync reading progress with trackers.
- Translate a single page manually, translate pages automatically while reading, or queue a chapter for batch translation.
- Choose from standard translation engines, including on-device ML Kit, Google Translate, and DeepL, or AI providers including Gemini, OpenRouter, DeepSeek, and LM Studio. DeepL, Gemini, OpenRouter, and DeepSeek need API keys you provide. LM Studio uses a base URL and model; the app does not ask for an API key for ML Kit or Google Translate.
- Run text detection and OCR, translate recognized text, clean the original text from the page with inpainting, and render translated text over the image.
- Read long webtoon pages with cross-page seam stitching for text regions that continue across page boundaries.
- Choose the font used for translated text.

## Requirements and getting the app

Android 8.0 (API 26) or newer is required (arm64-v8a physical device or x86_64 emulator).

There are no prebuilt public releases at this time, so build the app from source using the instructions below. Note that the main-branch [CI workflow](https://github.com/KimreneOuk/TachiyomiATVibe/actions/workflows/build_push.yml) produces **unsigned** verification artifacts; they cannot be installed directly on a device without signing. Building locally automatically signs the debug APK with your local Android debug key.

For a comprehensive guide covering IDE setup, adb commands, APK variants, and troubleshooting, see the [Development Setup Guide](docs/DEVELOPMENT_SETUP.md).

## Translation quickstart

1. Open **More → Settings → Translations**.
2. Set **Translate From** and **Translate To**, then choose a **Translator type**.
   - To test immediately without credentials, select **Google Translate** (requires internet) or **ML Kit** (on-device; downloads language models on first use).
   - For AI providers (Gemini, DeepSeek, OpenRouter, DeepL), enter your personal API key.
3. Open a chapter. Tap the page's **Translate** action for a single page, enable automatic translation in the reader dialog while reading, or queue a chapter for batch translation.

The ONNX model assets are fetched and converted before the app is built, then packaged into the APK. The app copies these packaged assets into its private storage when needed; it does not fetch those assets on the first translation.

## Building from source

Prerequisites:
- **Python 3.10 – 3.12** (pinned by converter dependencies)
- **JDK 17** (or Android Studio's bundled JetBrains Runtime; set `JAVA_HOME`)
- **Android SDK Platform 35** (`android-35`; set `ANDROID_HOME` or define `sdk.dir` in `local.properties`)

### Linux & macOS

```sh
git clone https://github.com/KimreneOuk/TachiyomiATVibe.git
cd TachiyomiATVibe

# Set up Python virtual environment & install converter dependencies
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r scripts/converters/requirements.txt

# Fetch, convert, and verify model assets (~159 MB)
python scripts/fetch_models.py

# Verify environment readiness
python scripts/setup_check.py

# Build standard debug APK
./gradlew :app:assembleStandardDebug
```

### Windows (PowerShell)

```powershell
git clone https://github.com/KimreneOuk/TachiyomiATVibe.git
cd TachiyomiATVibe

# Set up Python virtual environment & install converter dependencies
py -3 -m venv .venv
.venv\Scripts\Activate.ps1
python -m pip install -r scripts/converters/requirements.txt

# Fetch, convert, and verify model assets (~159 MB)
python scripts/fetch_models.py

# Verify environment readiness
python scripts/setup_check.py

# Build standard debug APK
.\gradlew.bat :app:assembleStandardDebug
```

The debug APKs will be generated in `app/build/outputs/apk/standard/debug/` (e.g. `app-standard-arm64-v8a-debug.apk` for phones, `app-standard-x86_64-debug.apk` for emulators, or `app-standard-universal-debug.apk`). See [DEVELOPMENT_SETUP.md](docs/DEVELOPMENT_SETUP.md) for details.

## Development

Run the JVM unit tests with:

```sh
./gradlew test
```

A small number of integration tests in the batch-translation coexistence suite — plus two screen-model fixtures whose boot await can starve on 2-core CI runners — are load-ordering sensitive under full-suite JVM churn and are temporarily tagged `quarantined-flaky` and excluded from CI. They remain part of the tree and can be run explicitly with:

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
