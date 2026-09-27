# Contributing

Contributions to TachiyomiATVibe are welcome. For bugs and feature requests, use the issue templates in this repository. For code changes, open a pull request against `main` and describe the behavior changed and how you verified it.

Please follow the [Code of Conduct](CODE_OF_CONDUCT.md). Larger changes are easier to review when discussed in an issue before implementation.

## Prerequisites

- Familiarity with Kotlin and Android development
- Android Studio with JDK 17 and the Android SDK
- An emulator or Android device for changes that need runtime verification

## Build and test

Build the Standard debug app with:

```sh
./gradlew :app:assembleStandardDebug
```

Run JVM unit tests with:

```sh
./gradlew test
```

On Windows, use `gradlew.bat` in place of `./gradlew`.

## Translation changes

Before changing `eu.kanade.translation`, use the [translation architecture guide](docs/translation-architecture.md) to find the owner for the behavior. Put request, session, and chapter lifecycle decisions in `workflow`; use `scheduling` for when page jobs run. Keep shared page execution in `pipeline`, shared stage planning in `pipeline/planning`, memory budgets in `pipeline/memory`, and mode-specific batch policy in `pipeline/batch`. Keep provider request logic under `engines/translator/providers`; put recognition, inpainting, rendering, and model runtime changes in their matching `engines` packages. `persistence/chapter` owns live state and `TranslationFileProvider`; `persistence/artifact` and `persistence/queue` own durable records.

Preserve reader and batch coexistence, cancellation, crash recovery and committed artifact behavior. Keep dependencies directed toward concrete owners; add no package cycles, process-global state, or utility dumping grounds. Add or update focused tests for behavior changes; concurrency, lease, commit and state-machine changes need tests for the affected transitions and races. For a translation-focused JVM pass, run both variants:

```sh
./gradlew testDevReleaseUnitTest testStandardReleaseUnitTest --tests "eu.kanade.translation.*" --max-workers=2
```

On Windows, use `gradlew.bat` and PowerShell's `./gradlew.bat` invocation syntax.

## Project lineage

This is an unofficial fork of TachiyomiAT, based on Mihon and the Tachiyomi project. See the project links and attribution in [README.md](README.md). The upstream projects have separate issue trackers, communities, and contribution processes; use this repository for TachiyomiATVibe changes.

## License

By contributing, you agree that your contributions are provided under the repository's [Apache License 2.0](LICENSE).
