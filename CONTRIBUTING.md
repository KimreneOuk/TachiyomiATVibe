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

## Project lineage

This is an unofficial fork of TachiyomiAT, based on Mihon and the Tachiyomi project. See the project links and attribution in [README.md](README.md). The upstream projects have separate issue trackers, communities, and contribution processes; use this repository for TachiyomiATVibe changes.

## License

By contributing, you agree that your contributions are provided under the repository's [Apache License 2.0](LICENSE).