# MangaOCR mobile acceleration research

## Scope

This task bootstraps a reproducible, read-only research area for evaluating
`ogkalu/manga-ocr-mobile` for mobile OCR acceleration. It records repository
health, acquisition provenance, model files, runtime configuration, and
tokenizer evidence. It does not change application code, Gradle configuration,
packaging, or runtime behavior.

Local model bytes belong under `research/models/` (or `research/cache/`) and
are intentionally ignored. Tracked notes under `research/findings/` and
`research/results/` are the durable research outputs.

## Evidence labels

- **OBSERVED** — directly verified from a checked-out file, command output, or
  repository metadata; include the path, revision, and/or command.
- **INFERRED** — a reasoned conclusion from observed evidence; state the
  assumptions and do not present it as a model guarantee.
- **UNVERIFIED** — a claim or follow-up that still needs an execution test,
  Android integration check, or independent source.
- **BLOCKED** — an attempted check or acquisition could not complete; record
  the exact command and error so it can be reproduced.

## Reproduction

The intended acquisition source is the Hugging Face Git repository
`https://huggingface.co/ogkalu/manga-ocr-mobile`, not a GitHub URL. A complete
checkout (including Git LFS payloads when available) can be reproduced with:

```powershell
git clone https://huggingface.co/ogkalu/manga-ocr-mobile research/models/manga-ocr-mobile
git -C research/models/manga-ocr-mobile lfs pull
```

The model checkout is local-only and must not be committed or copied into
Android assets by this bootstrap task.

## Outputs

- `findings/repository-bootstrap.md` — branch/worktree and ignore-policy
  evidence.
- `results/model-inventory.md` — exact model files, sizes, hashes, config and
  tokenizer inventory, plus acquisition status.
