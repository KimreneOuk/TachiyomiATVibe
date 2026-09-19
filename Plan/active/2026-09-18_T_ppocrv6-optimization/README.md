# PP-OCRv6 acceleration investigation bootstrap

Status: in progress

## Scope

Establish a reproducible, read-only research baseline for PP-OCRv6 small
detection/recognition performance on Android-shaped inputs. Production code is
out of scope for this bootstrap.

## Contract

- Preserve the existing worktree and all external chapter files in place.
- Pin and verify the upstream `PaddlePaddle/PP-OCRv6_small_det_onnx` model
  repository under ignored `research/cache/`.
- Record current in-app model paths, preprocessing/postprocessing entry points,
  and detector alternatives.
- Inventory the supplied chapter plus the two downloaded chapters under the
  GUI probe output directory without copying page binaries.
- Check in only research docs, manifests, inventory JSON, and scripts.

## Deliverables

- `REPO_HEALTH.md`: repository safety baseline.
- `research/findings/bootstrap.md`: concise findings and blockers.
- `research/results/bootstrap.json`: raw machine-readable inventory.
- `research/dataset/manifest.json`: fixed page/region fixture manifest.
- `research/scripts/build_dataset_manifest.py`: reproducible manifest builder.

