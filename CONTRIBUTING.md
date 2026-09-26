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

Before changing `eu.kanade.translation`, use the [translation architecture guide](docs/translation-architecture.md) to find the owner for the behavior. Keep provider-specific request logic under `translator/providers`, batch policy under `pipeline/batch`, and state or persistence logic with its current owner. Do not use `util` as a home for translation policy, add package cycles or new global state, or move provider behavior into orchestration.

Preserve reader and batch coexistence, cancellation, crash recovery and committed artifact behavior. Add or update focused tests for behavior changes; concurrency, lease, commit and state-machine changes need tests for the affected transitions and races. For a translation-focused JVM pass, run both variants:

```sh
./gradlew testDevReleaseUnitTest testStandardReleaseUnitTest --tests "eu.kanade.translation.*" --max-workers=2
```

On Windows, use `gradlew.bat` and PowerShell's `./gradlew.bat` invocation syntax.

## Project lineage

This is an unofficial fork of TachiyomiAT, based on Mihon and the Tachiyomi project. See the project links and attribution in [README.md](README.md). The upstream projects have separate issue trackers, communities, and contribution processes; use this repository for TachiyomiATVibe changes.

## License

By contributing, you agree that your contributions are provided under the repository's [Apache License 2.0](LICENSE).
