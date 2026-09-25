# TachiyomiATVibe

TachiyomiATVibe is an unofficial, independent fork of [TachiyomiAT](https://github.com/mannu691/TachiyomiAT). It is based on [Mihon](https://github.com/mihonapp/mihon), which continues the [Tachiyomi](https://github.com/tachiyomiorg/Tachiyomi) project. This fork is not affiliated with or endorsed by those projects or their maintainers.

This repository contains application source and bundled runtime assets, but no prebuilt application downloads or reading content. Check each project's license and notices when redistributing code or assets.

## About

A Mihon-based Android reader with chapter translation and reader workflow changes maintained in this fork. The minimum supported Android version is Android 8.0 (API 26). Some translation providers require credentials that you supply yourself.

## Build from source

You need JDK 17 and the Android SDK installed. Android Studio can install the SDK components required by the project.

```sh
git clone https://github.com/KimreneOuk/TachiyomiATVibe.git
cd TachiyomiATVibe
./gradlew :app:assembleStandardDebug
```

On Windows PowerShell, run `.\gradlew.bat :app:assembleStandardDebug`. The Standard debug APKs are written under `app/build/outputs/apk/standard/debug/`.

Run the JVM unit tests with:

```sh
./gradlew test
```

The CI build uses the Standard release variant. Automated release creation remains disabled for this fork until its maintainers configure a release policy and signing credentials.

## Project lineage

The TachiyomiAT, Mihon, and Tachiyomi projects retain their own histories and contributor attribution. This repository is a curated source snapshot with a fresh Git history; it does not reproduce the upstream commit history.

## Contributing

Please read [CONTRIBUTING.md](CONTRIBUTING.md) and the [Code of Conduct](CODE_OF_CONDUCT.md). Open an issue or pull request in this repository for changes to TachiyomiATVibe.

## Test stability quarantine

A small number of integration tests in the batch-translation coexistence suite are load-ordering sensitive under full-suite JVM churn and are temporarily tagged `quarantined-flaky` and excluded from CI. They remain part of the tree and can be run explicitly with:

```
./gradlew :app:testDevReleaseUnitTest -PincludeQuarantinedTests
```

Stabilizing these tests and removing the tag is tracked work.

## License and third-party assets

The project is distributed under the [Apache License 2.0](LICENSE). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the bundled model and font asset manifest.

## Disclaimer

The developers are not affiliated with content providers. The application does not include or host reading content; users are responsible for the sources and content they choose to use.
