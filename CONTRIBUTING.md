# Contributing

Contributions to TachiyomiATVibe are welcome. For bugs and feature requests, use the issue templates in this repository. For code changes, open a pull request against `main` and describe the behavior changed and how you verified it.

Please follow the [Code of Conduct](CODE_OF_CONDUCT.md). Larger changes are easier to review when discussed in an issue before implementation.

## Prerequisites

- Familiarity with Kotlin and Android development
- **Python 3.10 – 3.12** for model conversion tooling
- **Android Studio** with **JDK 17** (or bundled JBR) and **Android SDK Platform 35**
- An emulator (x86_64) or Android device (Android 8.0+, arm64-v8a) for runtime verification
- For complete fresh-machine setup instructions, see the [Development Setup Guide](docs/DEVELOPMENT_SETUP.md).

## Build and test

The translation model assets are not committed to Git. Before building or testing translation features, set up a virtual environment and fetch them once (~159 MB, hash-verified):

```sh
# 1. Virtual environment & converter dependencies
python3 -m venv .venv
source .venv/bin/activate  # On Windows: .venv\Scripts\Activate.ps1
python -m pip install -r scripts/converters/requirements.txt

# 2. Fetch and convert model assets
python scripts/fetch_models.py

# 3. Optional diagnostic check
python scripts/setup_check.py
```

Build the Standard debug app with:

```sh
./gradlew :app:assembleStandardDebug
# On Windows: .\gradlew.bat :app:assembleStandardDebug
```

JVM unit tests do not require the model assets. Run them with:

```sh
./gradlew test
# On Windows: .\gradlew.bat test
```

## Translation changes

Before changing `eu.kanade.translation`, use the [translation architecture guide](docs/translation-architecture.md) to find the owner for the behavior. Put request, session, and chapter lifecycle decisions in `workflow`; use `scheduling` for when page jobs run. Keep shared page execution in `pipeline`, shared stage planning in `pipeline/planning`, decode/preflight/prefetch policy in `pipeline/memory`, engine heap/native-memory facts in `engines/runtime`, and mode-specific batch policy in `pipeline/batch`. Keep provider request logic under `engines/translator/providers`; put recognition, inpainting, rendering, and model runtime changes in their matching `engines` packages. `persistence/chapter` owns live state and `TranslationFileProvider`; `persistence/internal` owns the chapter glossary accumulator; `persistence/artifact` and `persistence/queue` own durable records.

Preserve reader and batch coexistence, cancellation, crash recovery and committed artifact behavior. Keep dependencies directed toward concrete owners; add no package cycles, process-global state, or utility dumping grounds. Add or update focused tests for behavior changes; concurrency, lease, commit and state-machine changes need tests for the affected transitions and races. For a translation-focused JVM pass, run both variants:

```sh
./gradlew testDevReleaseUnitTest testStandardReleaseUnitTest --tests "eu.kanade.translation.*" --max-workers=2
```

On Windows, use `gradlew.bat` and PowerShell's `./gradlew.bat` invocation syntax.

## Project lineage

This is an unofficial fork of TachiyomiAT, based on Mihon and the Tachiyomi project. See the project links and attribution in [README.md](README.md). The upstream projects have separate issue trackers, communities, and contribution processes; use this repository for TachiyomiATVibe changes.

## License

By contributing, you agree that your contributions are provided under the repository's [Apache License 2.0](LICENSE).
