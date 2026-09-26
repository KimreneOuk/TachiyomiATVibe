# TachiyomiATVibe

TachiyomiATVibe is an unofficial, independent fork of [TachiyomiAT](https://github.com/mannu691/TachiyomiAT). It is based on [Mihon](https://github.com/mihonapp/mihon), which continues the [Tachiyomi](https://github.com/tachiyomiorg/Tachiyomi) project. This fork is not affiliated with or endorsed by those projects or their maintainers.

Most of the fork-specific development (translation pipeline, tests, tooling) was produced with AI coding assistants under human direction and review.

This repository contains application source and third-party notices, but no prebuilt application downloads, reading content, or model binaries. Source builds fetch pinned model files and generate converted models locally; check each project's license and notices when redistributing code or assets.

## About

A Mihon-based Android reader with chapter translation and reader workflow changes maintained in this fork. The minimum supported Android version is Android 8.0 (API 26). Some translation providers require credentials that you supply yourself.

## Models and building

Before building, install Python 3, install the pinned converter dependencies, and fetch model assets from the repository root:

```sh
python3 -m pip install -r scripts/converters/requirements.txt
python3 scripts/fetch_models.py
```

On Windows PowerShell, use `py -3 -m pip install -r scripts/converters/requirements.txt` and `py -3 scripts/fetch_models.py`. The fetcher downloads direct upstream files and locally converts derived files, checking source and output size and full SHA-256 values from [scripts/models.manifest](scripts/models.manifest). It skips verified files and fails if a source is unreachable, a converter fails, or any hash does not match. The app continues seeding fetched assets into its private files directory at runtime. See [docs/MODEL_SOURCES.md](docs/MODEL_SOURCES.md) for model provenance, conversion notes, and license status.

You also need JDK 17 and the Android SDK installed. Android Studio can install the SDK components required by the project.

```sh
git clone https://github.com/KimreneOuk/TachiyomiATVibe.git
cd TachiyomiATVibe
python3 -m pip install -r scripts/converters/requirements.txt
python3 scripts/fetch_models.py
./gradlew :app:assembleStandardDebug
```

On Windows PowerShell, run `py -3 -m pip install -r scripts/converters/requirements.txt`, `py -3 scripts/fetch_models.py`, then `.\gradlew.bat :app:assembleStandardDebug`. The Standard debug APKs are written under `app/build/outputs/apk/standard/debug/`.

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

A small number of integration tests in the batch-translation coexistence suite — plus one screen-model fixture whose boot await can starve on 2-core CI runners — are load-ordering sensitive under full-suite JVM churn and are temporarily tagged `quarantined-flaky` and excluded from CI. They remain part of the tree and can be run explicitly with:

```
./gradlew :app:testDevReleaseUnitTest -PincludeQuarantinedTests
```

Stabilizing these tests and removing the tag is tracked work.

## License and third-party assets

The project is distributed under the [Apache License 2.0](LICENSE). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for fetched model and bundled font notices.

## Disclaimer

The developers are not affiliated with content providers. The application does not include or host reading content; users are responsible for the sources and content they choose to use.
