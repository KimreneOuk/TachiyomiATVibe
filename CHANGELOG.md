# TachiyomiATVibe Changelog

## Unreleased

### Added

- The initial public source snapshot includes manual page, automatic reader, and chapter batch translation workflows combining text detection and OCR, provider-based translation, inpainting, and rendered text.
- The public snapshot also includes cross-page seam stitching for text regions that continue across long webtoon pages.

### Changed

- Model binaries are no longer tracked in Git. Builds use `scripts/fetch_models.py` and `scripts/models.manifest` to fetch pinned upstream files, verify hashes, and regenerate derived assets before packaging.

### Architecture-Development

- Reorganized the translation subsystem around workflow, scheduling, pipeline, engines, persistence, and presentation ownership; grouped batch analysis, envelope, progress, and recovery code by responsibility. Added contributor and architecture maps.
- Tagged load-sensitive tests `quarantined-flaky`; default Gradle test runs exclude tagged tests, and `-PincludeQuarantinedTests` opts in.

## Upstream history

The Mihon changelog carried into the initial public source snapshot is preserved in [docs/UPSTREAM_CHANGELOG.md](docs/UPSTREAM_CHANGELOG.md), with its original entries, dates, and contributor attributions. Those entries describe upstream history; they are not TachiyomiATVibe releases.
