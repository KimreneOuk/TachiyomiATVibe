# MangaOCR mobile acceleration bootstrap

## Scope

Bootstrap repository-safe research for `ogkalu/manga-ocr-mobile`: verify the
branch/worktree, inspect ignore policy, acquire a complete local model checkout,
and record exact model/config/tokenizer evidence. No application code,
packaging, or runtime behavior is in scope.

## Evidence labels

- **OBSERVED** — directly verified from repository files, command output, or
  pinned model metadata.
- **INFERRED** — reasoned from observed evidence; assumptions are explicit.
- **UNVERIFIED** — requires a later runtime, Android, or integration test.
- **BLOCKED** — attempted but prevented; command and error must be recorded.

## Deliverables

- Repository health: `REPO_HEALTH.md`.
- Bootstrap evidence: `research/findings/repository-bootstrap.md`.
- Model inventory: `research/results/model-inventory.md`.
- Local payloads: ignored `research/cache/manga-ocr-mobile/`.
