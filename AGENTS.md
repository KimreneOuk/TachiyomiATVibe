# TachiyomiAT workspace instructions

## Project

TachiyomiAT is a Kotlin Android manga/manhwa/manhua reader based on Mihon. The app adds automatic translation using on-device OCR/ONNX detection, segmentation and inpainting, translators, and rendered text overlays. Android 8.0+ is supported; preserve bounded memory and performance on devices with at least 6 GB RAM.

## Repository layout

- `app/` — Android application, UI, reader, translation pipeline, assets, and tests.
- `domain/`, `data/` — application/domain logic and persistence.
- `core/`, `core-metadata/` — shared platform/core functionality and metadata.
- `presentation-core/`, `presentation-widget/` — reusable presentation components.
- `source-api/`, `source-local/` — extension/source interfaces and local sources.
- `i18n/`, `i18n-at/` — shared and TachiyomiAT-specific resources/translations.
- `buildSrc/` — shared Gradle convention plugins and formatting rules.
- `docs/project_context/` — investigation and implementation guidance; `Plan/active/` — task-specific plans.

## Build and validation

Use the Gradle wrapper from the repository root:

```bash
./gradlew spotlessCheck assembleStandardRelease testReleaseUnitTest testStandardReleaseUnitTest
```

For focused app unit tests, use the matching Gradle test task (for example, `./gradlew :app:testDevDebugUnitTest --tests 'fully.qualified.TestName'`). `spotlessCheck` runs ktlint-based Kotlin/Kotlin-script formatting and XML formatting. A configured JDK and Android SDK are required; do not infer build success when `JAVA_HOME` or `java` is unavailable.

The app module has `standard` and `dev` flavors plus `debug`, `release`, `preview`, and `benchmark` build types. The standard application ID is `app.kanade.tachiyomi.at`; debug/preview variants add `.debug`.

## Architecture and editing rules

- Trace live code and tests before relying on comments or historical docs. Verify model paths, packaging, loaders, invocation, and downstream consumers when changing translation/model code.
- Keep UI/presentation concerns in presentation/UI layers and business/data behavior in their existing modules; avoid introducing app-layer dependencies into lower-level modules.
- Translation changes generally span `app/src/main/java/eu/kanade/translation/` and reader/manga UI call sites. Preserve stage ownership, lifecycle, cancellation, and memory boundaries.
- Match surrounding Kotlin/Compose naming, imports, and comment density. Prefer self-documenting code; do not use comments to define behavior.
- Add or update focused regression tests under the corresponding `app/src/test` (or `androidTest`) package when behavior changes.
- Preserve unrelated local changes. Review the diff before reporting results, and never claim a build or test passed unless it was actually run successfully.

## Documentation and workflow

Before substantial edits, read the relevant files in `docs/project_context/` (`planning.md`, `implementing.md`, and `knowledge_base.md`). Put durable architecture knowledge in `docs/`; put task reasoning and checkpoints in `Plan/active/<YYYY-MM-DD>-<topic>/`. Treat live code/tests as authoritative, followed by `progress.md`, active plans, and then historical notes.
